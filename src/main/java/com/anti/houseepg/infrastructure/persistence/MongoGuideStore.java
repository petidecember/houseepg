package com.anti.houseepg.infrastructure.persistence;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;

import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.RawSnapshot;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SlotLoss;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.model.StoredGuide;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Adapter: the three persistence layers in MongoDB, one collection per layer (design §7). */
@Component
public class MongoGuideStore implements GuideStore {

    static final String RAW_SNAPSHOTS = "rawSnapshots";
    static final String PROGRAMMES = "programmes";
    static final String CANONICAL_CHANNELS = "canonicalChannels";
    static final String MERGED_CHANNELS = "mergedChannels";
    static final String MERGED_PROGRAMMES = "mergedProgrammes";
    static final String GUIDE_MARKERS = "guideMarkers";
    static final String SOURCE_HEALTH = "sourceHealth";
    static final String SOURCE_LOSSES = "sourceLosses";
    static final String SOURCE_CHANNELS = "sourceChannels";
    static final String GUIDE_MARKER_ID = "merged";

    private static final Logger log = LoggerFactory.getLogger(MongoGuideStore.class);

    private final MongoDatabase database;
    private final MongoDatabase admin;
    private final AtomicBoolean indexesEnsured = new AtomicBoolean();

    /**
     * @param database the guide's own database
     * @param admin the same server's {@code admin} database, because {@code renameCollection} — the one-step
     *     swap {@link #saveRegistry} uses — is an admin command (design §7, HOU-32)
     */
    public MongoGuideStore(@Qualifier("houseEpgDatabase") MongoDatabase database,
            @Qualifier("houseEpgAdminDatabase") MongoDatabase admin) {
        this.database = database;
        this.admin = admin;
    }

    /**
     * Indexes are created on first use rather than at startup, so the application still boots when the
     * database happens to be down and unit tests never need one (design §7).
     */
    private void ensureIndexes() {
        if (!indexesEnsured.compareAndSet(false, true)) {
            return;
        }
        try {
            programmes().createIndex(Indexes.compoundIndex(Indexes.ascending("canonicalChannelId"),
                    Indexes.ascending("start")));
            programmes().createIndex(Indexes.compoundIndex(Indexes.ascending("sourceId"),
                    Indexes.ascending("canonicalChannelId")));
            programmes().createIndex(Indexes.ascending("stop"));
            sourceChannels().createIndex(Indexes.compoundIndex(Indexes.ascending("sourceId"),
                    Indexes.ascending("sourceChannelId")), new IndexOptions().unique(true));
            mergedProgrammes().createIndex(Indexes.compoundIndex(Indexes.ascending("channelId"),
                    Indexes.ascending("start")));
        } catch (RuntimeException e) {
            indexesEnsured.set(false);
            log.warn("Could not ensure MongoDB indexes yet: {}", e.toString());
        }
    }

    private MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    private MongoCollection<Document> programmes() {
        return collection(PROGRAMMES);
    }

    private MongoCollection<Document> sourceChannels() {
        return collection(SOURCE_CHANNELS);
    }

    private MongoCollection<Document> mergedProgrammes() {
        return collection(MERGED_PROGRAMMES);
    }

    @Override
    public Optional<RawSnapshot> rawSnapshot(SourceId source) {
        ensureIndexes();
        Document document = collection(RAW_SNAPSHOTS).find(eq("_id", source.value())).first();
        if (document == null) {
            return Optional.empty();
        }
        String content = document.getString("content");
        Long sizeBytes = document.getLong("sizeBytes");
        return Optional.of(new RawSnapshot(source, instant(document.get("fetchedAt")), content,
                sizeBytes == null ? (content == null ? 0 : content.length()) : sizeBytes));
    }

    @Override
    public void saveRawSnapshot(RawSnapshot snapshot) {
        ensureIndexes();
        Document document = new Document("_id", snapshot.source().value())
                .append("fetchedAt", date(snapshot.fetchedAt()))
                .append("content", snapshot.content())
                .append("sizeBytes", snapshot.sizeBytes());
        collection(RAW_SNAPSHOTS).replaceOne(eq("_id", document.get("_id")), document,
                new ReplaceOptions().upsert(true));
    }

    /**
     * The channel declarations are read from their own collection, so a declared channel that publishes no
     * programme still survives the round trip (HOU-39). Before that collection existed the declarations were
     * not stored at all and were rebuilt from the programme rows, which silently dropped exactly those
     * channels — the fallback below is what an un-migrated database gets. The two paths in the code that ask
     * "what does this source declare?" (the seed and the re-seed) therefore now read one truth, and this
     * fallback only keeps a database written by an older build working until its next refresh fills it in.
     */
    @Override
    public Optional<SourceEpg> normalized(SourceId source) {
        ensureIndexes();
        List<Document> rows = programmes().find(eq("sourceId", source.value())).into(new ArrayList<>());
        List<Document> declarations = sourceChannels().find(eq("sourceId", source.value())).into(new ArrayList<>());
        if (rows.isEmpty() && declarations.isEmpty()) {
            return Optional.empty();
        }
        Map<String, SourceChannel> channels = new LinkedHashMap<>();
        for (Document declaration : declarations) {
            String id = declaration.getString("sourceChannelId");
            if (id != null) {
                channels.putIfAbsent(id, new SourceChannel(id, declaration.getString("sourceChannelName"),
                        declaration.getString("sourceChannelIconUrl")));
            }
        }
        List<SourceProgramme> restored = new ArrayList<>();
        for (Document row : rows) {
            String sourceChannelId = row.getString("sourceChannelId");
            // A programme whose declaration is missing — the un-migrated case — still brings its channel in.
            channels.computeIfAbsent(sourceChannelId,
                    key -> new SourceChannel(key, row.getString("sourceChannelName"), null));
            restored.add(new SourceProgramme(sourceChannelId, slot(row), row.getString("title"),
                    row.getString("description"), row.getString("category"), row.getString("episodeNumber"),
                    row.getString("iconUrl")));
        }
        return Optional.of(new SourceEpg(source, List.copyOf(channels.values()), restored));
    }

    @Override
    public void saveNormalized(SourceEpg document, Map<SourceChannelRef, ChannelId> canonicalIds) {
        ensureIndexes();
        programmes().deleteMany(eq("sourceId", document.source().value()));
        sourceChannels().deleteMany(eq("sourceId", document.source().value()));
        Map<String, String> names = new LinkedHashMap<>();
        document.channels().forEach(channel -> names.put(channel.id(), channel.displayName()));
        // One row per declaration id, first one wins. The store reads declarations back the same way, so a
        // provider that names an id twice describes one channel here too — a unique index would otherwise turn
        // a repeated declaration into a failed write, which is the failure mode this layer must not have
        // (HOU-32 for the registry, HOU-39 for this collection).
        Map<String, SourceChannel> declared = new LinkedHashMap<>();
        document.channels().forEach(channel -> declared.putIfAbsent(channel.id(), channel));
        List<Document> declarations = declared.values().stream()
                .map(channel -> new Document()
                        .append("sourceId", document.source().value())
                        .append("sourceChannelId", channel.id())
                        .append("sourceChannelName", channel.displayName())
                        .append("sourceChannelIconUrl", channel.iconUrl()))
                .toList();
        if (!declarations.isEmpty()) {
            sourceChannels().insertMany(declarations);
        }
        List<Document> rows = document.programmes().stream()
                .map(programme -> new Document()
                        .append("sourceId", document.source().value())
                        .append("sourceChannelId", programme.sourceChannelId())
                        .append("sourceChannelName", names.get(programme.sourceChannelId()))
                        .append("canonicalChannelId",
                                canonicalId(canonicalIds, document.source(), programme.sourceChannelId()))
                        .append("start", time(programme.slot().start()))
                        .append("stop", time(programme.slot().stop()))
                        .append("title", programme.title())
                        .append("description", programme.description())
                        .append("category", programme.category())
                        .append("episodeNumber", programme.episodeNumber())
                        .append("iconUrl", programme.iconUrl()))
                .toList();
        if (!rows.isEmpty()) {
            programmes().insertMany(rows);
        }
    }

    @Override
    public Optional<StoredGuide> mergedGuide() {
        ensureIndexes();
        Document marker = collection(GUIDE_MARKERS).find(eq("_id", GUIDE_MARKER_ID)).first();
        if (marker == null) {
            return Optional.empty();
        }
        List<Channel> channels = collection(MERGED_CHANNELS).find().into(new ArrayList<>()).stream()
                .map(document -> new Channel(new ChannelId(document.getString("_id")),
                        document.getString("displayName"), document.getString("iconUrl"),
                        document.getString("number"), Channel.Origin.valueOf(document.getString("origin"))))
                .toList();
        List<Programme> programmes = mergedProgrammes().find().into(new ArrayList<>()).stream()
                .map(document -> new Programme(new ChannelId(document.getString("channelId")), slot(document),
                        new SourceId(document.getString("winningSource")), document.getString("title"),
                        document.getString("description"), document.getString("category"),
                        document.getString("episodeNumber"), document.getString("iconUrl")))
                .sorted(Comparator.comparing((Programme programme) -> programme.channelId().value())
                        .thenComparing(programme -> programme.slot().start()))
                .toList();
        return Optional.of(new StoredGuide(new Guide(channels, programmes),
                instant(marker.get("materializedAt"))));
    }

    @Override
    public void saveMergedGuide(Guide guide, Instant materializedAt) {
        ensureIndexes();
        collection(MERGED_CHANNELS).deleteMany(new Document());
        mergedProgrammes().deleteMany(new Document());
        List<Document> channels = guide.channels().stream()
                .map(channel -> new Document("_id", channel.id().value())
                        .append("displayName", channel.displayName())
                        .append("iconUrl", channel.iconUrl())
                        .append("number", channel.number())
                        .append("origin", channel.origin().name()))
                .toList();
        List<Document> programmes = guide.programmes().stream()
                .map(programme -> new Document("channelId", programme.channelId().value())
                        .append("winningSource", programme.winningSource().value())
                        .append("start", time(programme.slot().start()))
                        .append("stop", time(programme.slot().stop()))
                        .append("title", programme.title())
                        .append("description", programme.description())
                        .append("category", programme.category())
                        .append("episodeNumber", programme.episodeNumber())
                        .append("iconUrl", programme.iconUrl()))
                .toList();
        if (!channels.isEmpty()) {
            collection(MERGED_CHANNELS).insertMany(channels);
        }
        if (!programmes.isEmpty()) {
            mergedProgrammes().insertMany(programmes);
        }
        Document marker = new Document("_id", GUIDE_MARKER_ID).append("materializedAt", date(materializedAt));
        collection(GUIDE_MARKERS).replaceOne(eq("_id", GUIDE_MARKER_ID), marker, new ReplaceOptions().upsert(true));
    }

    @Override
    public List<Channel> registry() {
        ensureIndexes();
        return collection(CANONICAL_CHANNELS).find().into(new ArrayList<>()).stream()
                .map(document -> new Channel(new ChannelId(document.getString("_id")),
                        document.getString("displayName"), document.getString("iconUrl"),
                        document.getString("number"), Channel.Origin.valueOf(document.getString("origin"))))
                .toList();
    }

    @Override
    public void saveRegistry(List<Channel> channels) {
        ensureIndexes();
        List<Document> rows = channels.stream()
                .map(channel -> new Document("_id", channel.id().value())
                        .append("displayName", channel.displayName())
                        .append("iconUrl", channel.iconUrl())
                        .append("number", channel.number())
                        .append("origin", channel.origin().name()))
                .toList();
        if (rows.isEmpty()) {
            collection(CANONICAL_CHANNELS).deleteMany(new Document());
            return;
        }
        // Built beside the live collection and swapped in, so a rejected row cannot leave the registry
        // half-written: the old one keeps serving until the new one is complete. A plain delete-then-insert
        // would already have dropped it by the time the write failed (design §6.4, ADR-0002).
        String staging = CANONICAL_CHANNELS + "_next";
        MongoCollection<Document> next = collection(staging);
        next.deleteMany(new Document());
        next.insertMany(rows);
        // renameCollection is an admin command even when both collections live in this database.
        MongoDatabase commandTarget = admin != null ? admin : database;
        commandTarget.runCommand(new Document("renameCollection", database.getName() + "." + staging)
                .append("to", database.getName() + "." + CANONICAL_CHANNELS)
                .append("dropTarget", true));
    }

    @Override
    public List<SourceHealth> health() {
        ensureIndexes();
        return collection(SOURCE_HEALTH).find().into(new ArrayList<>()).stream()
                .map(document -> new SourceHealth(new SourceId(document.getString("_id")),
                        document.getString("name"), SourceHealth.State.valueOf(document.getString("state")),
                        document.getInteger("programmeCount", 0), document.getString("error")))
                .toList();
    }

    @Override
    public void saveHealth(SourceHealth health) {
        ensureIndexes();
        Document document = new Document("_id", health.source().value())
                .append("name", health.name())
                .append("state", health.state().name())
                .append("programmeCount", health.programmeCount())
                .append("error", health.error());
        collection(SOURCE_HEALTH).replaceOne(eq("_id", document.get("_id")), document,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public SlotLoss.BySource slotLosses() {
        ensureIndexes();
        Map<SourceId, SlotLoss> losses = new LinkedHashMap<>();
        for (Document document : collection(SOURCE_LOSSES).find()) {
            losses.put(new SourceId(document.getString("_id")),
                    new SlotLoss(document.getInteger("dropped", 0),
                            document.getInteger("duplicateSlots", 0),
                            document.getInteger("partialOverlaps", 0),
                            document.getInteger("lostToHigherPriority", 0)));
        }
        return new SlotLoss.BySource(losses);
    }

    @Override
    public void saveSlotLosses(SlotLoss.BySource losses) {
        ensureIndexes();
        // Replaced whole: these describe the last refresh, so a source that stopped losing must stop
        // being reported rather than keep its old count forever.
        collection(SOURCE_LOSSES).deleteMany(new Document());
        List<Document> rows = new ArrayList<>();
        losses.losses().forEach((source, loss) -> rows.add(new Document("_id", source.value())
                .append("dropped", loss.dropped())
                .append("duplicateSlots", loss.duplicateSlots())
                .append("partialOverlaps", loss.partialOverlaps())
                .append("lostToHigherPriority", loss.lostToHigherPriority())));
        if (!rows.isEmpty()) {
            collection(SOURCE_LOSSES).insertMany(rows);
        }
    }

    @Override
    public void prune(Instant before) {
        ensureIndexes();
        programmes().deleteMany(lt("stop", date(before)));
    }

    private static String canonicalId(Map<SourceChannelRef, ChannelId> canonicalIds, SourceId source,
            String sourceChannelId) {
        ChannelId canonical = canonicalIds.get(new SourceChannelRef(source, sourceChannelId));
        return canonical == null ? sourceChannelId : canonical.value();
    }

    private static Date date(Instant instant) {
        return Date.from(instant);
    }

    /** Programme slots carry an offset; MongoDB stores an instant, so UTC is the round-trip format. */
    private static Date time(OffsetDateTime time) {
        return Date.from(time.toInstant());
    }

    private static OffsetDateTime time(Object value) {
        return ((Date) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        return ((Date) value).toInstant();
    }

    private static Slot slot(Document document) {
        return new Slot(time(document.get("start")), time(document.get("stop")));
    }

    static IndexOptions unique() {
        return new IndexOptions().unique(true);
    }
}
