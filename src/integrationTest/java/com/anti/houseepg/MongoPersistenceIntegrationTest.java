package com.anti.houseepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.application.config.ReseedRegistry;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.RefreshGuide;
import com.anti.houseepg.application.guide.RefreshResult;
import com.anti.houseepg.application.guide.ServeGuide;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.RawSnapshot;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.infrastructure.persistence.MongoChannelMatchStore;
import com.anti.houseepg.infrastructure.persistence.MongoGuideStore;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three persistence layers against a real, locally installed MongoDB. It uses its own database and
 * drops it, so it can never touch real guide data (design §7, §8; the risk is accepted in §13).
 */
class MongoPersistenceIntegrationTest {

    private static final String URI = "mongodb://127.0.0.1:27017";
    private static final String DATABASE = "houseepg_test_mongo";

    private static final MongoClient CLIENT = MongoClients.create(URI);
    private static final MongoDatabase DB = CLIENT.getDatabase(DATABASE);

    private static final EpgSource A = EpgSource.of("a", "Source A", "fixture:a", null, Duration.ofHours(8));

    private final MongoGuideStore guideStore = new MongoGuideStore(DB, CLIENT.getDatabase("admin"));
    private final MongoChannelMatchStore matchStore = new MongoChannelMatchStore(DB);
    private final Map<String, SourceEpg> documents = new LinkedHashMap<>();
    private final AtomicBoolean aFails = new AtomicBoolean();

    @BeforeEach
    void resetDatabase() {
        assertThat(DATABASE).as("integration tests must never use the real database").isNotEqualTo("houseepg");
        DB.drop();
        documents.clear();
        aFails.set(false);
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @Test
    void storesTheLastGoodPayloadAndNeverOverwritesItWithAFailedFetch() {
        documents.put("a", source("a", programme("AMC (HD).hu", 1, 2, "Show Alpha")));
        guide().refresh();

        RawSnapshot first = guideStore.rawSnapshot(A.id()).orElseThrow();
        assertThat(first.content()).contains("Show Alpha");

        aFails.set(true);
        guide().refresh();

        RawSnapshot afterFailure = guideStore.rawSnapshot(A.id()).orElseThrow();
        assertThat(afterFailure.content()).isEqualTo(first.content());
        assertThat(guideStore.health()).singleElement()
                .satisfies(health -> assertThat(health.state()).isEqualTo(SourceHealth.State.STALE));
    }

    @Test
    void normalizesProgrammesUnderTheirCanonicalChannelAndIndexesThem() {
        documents.put("a", source("a", programme("AMC (HD).hu", 1, 2, "Show Alpha")));

        guide().refresh();

        Document programme = DB.getCollection("programmes").find().first();
        assertThat(programme).isNotNull();
        assertThat(programme.getString("canonicalChannelId")).isEqualTo("amc.xx");
        assertThat(programme.getString("sourceId")).isEqualTo("a");
        assertThat(indexNames("programmes"))
                .contains("canonicalChannelId_1_start_1", "sourceId_1_canonicalChannelId_1", "stop_1");
        assertThat(guideStore.normalized(A.id())).isPresent();
    }

    @Test
    void servesTheMaterializedGuideAndKeepsServingItWhenTheSourceBreaks() {
        documents.put("a", source("a", programme("AMC (HD).hu", 1, 2, "Show Alpha")));
        guide().refresh();

        ServeGuide serveGuide = new ServeGuide(guide(), guideStore, matchStore);
        assertThat(serveGuide.current().guide().programmes()).extracting(programme -> programme.title())
                .containsExactly("Show Alpha");

        aFails.set(true);
        guide().refresh();

        assertThat(serveGuide.current().guide().programmes()).extracting(programme -> programme.title())
                .containsExactly("Show Alpha");
    }

    @Test
    void aRegistryWriteThatCannotCompleteLeavesTheStoredRegistryAlone() {
        guideStore.saveRegistry(List.of(playlistChannel("amc.xx", "AMC"), playlistChannel("bravo.xx", "Bravo")));

        // The store keeps one row per id, so a duplicate id cannot be written at all. What matters is that
        // the refused write did not first delete what was there (design §6.4, HOU-32).
        assertThatThrownBy(() -> guideStore.saveRegistry(List.of(
                playlistChannel("amc.xx", "AMC"), playlistChannel("amc.xx", "AMC HD"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactlyInAnyOrder("amc.xx", "bravo.xx");
    }

    @Test
    void aStagingCollectionLeftBehindByACrashDoesNotBleedIntoTheNextRegistry() {
        // A process that dies between filling the staging collection and the rename cannot leave a partial
        // registry, but it can leave the staging collection populated. The next write must not inherit it.
        DB.getCollection("canonicalChannels_next")
                .insertOne(new Document("_id", "ghost.xx").append("displayName", "Ghost"));

        guideStore.saveRegistry(List.of(playlistChannel("amc.xx", "AMC")));

        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("amc.xx");
        assertThat(DB.getCollection("canonicalChannels_next").countDocuments()).isZero();
    }

    @Test
    void aSourceThatDeclaresOneIdTwiceStillSeedsEveryDistinctChannel() {
        documents.put("a", new SourceEpg(A.id(), List.of(
                new SourceChannel("AMC", "AMC", null),
                new SourceChannel("Bravo", "Bravo", null),
                new SourceChannel("AMC", "AMC H.265", null)),
                List.of(programme("AMC", 1, 2, "Show Alpha"))));

        // No playlist: the top-priority source seeds the registry (design §6.4, ADR-0002).
        GuideSettings settings = new GuideSettings(List.of(A), Optional.empty(), Duration.ofDays(7));
        new RefreshGuide(settings, this::read, location -> List.of(), matchStore, guideStore).refresh();

        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("AMC", "Bravo");
        assertThat(DB.getCollection("canonicalChannels").countDocuments()).isEqualTo(2);
        assertThat(DB.getCollection("canonicalChannels_next").countDocuments())
                .as("the staging collection is renamed away, not left behind")
                .isZero();
    }

    @Test
    void prunesProgrammesThatEndedLongAgo() {
        documents.put("a", source("a",
                programmeEndedDaysAgo("AMC (HD).hu", 30),
                programme("AMC (HD).hu", 1, 2, "Show Alpha")));

        guide().refresh();

        assertThat(DB.getCollection("programmes").countDocuments()).isEqualTo(1);
        assertThat(guideStore.mergedGuide().orElseThrow().guide().programmes())
                .extracting(programme -> programme.title()).containsExactly("Show Alpha");
    }

    @Test
    void aCorrectedMatchSurvivesANewRefreshGuideInstance() {
        documents.put("a", source("a", programme("AMC (HD).hu", 1, 2, "Show Alpha")));
        guide().refresh();

        SourceChannelRef ref = new SourceChannelRef(A.id(), "AMC (HD).hu");
        matchStore.decide(ChannelMatch.corrected(ref, new ChannelId("bravo.xx"), MatchConfidence.EXACT_ID));

        RefreshResult next = guide().refresh();

        assertThat(next.matches()).singleElement().satisfies(match -> {
            assertThat(match.state()).isEqualTo(MatchState.CORRECTED);
            assertThat(match.canonicalId().value()).isEqualTo("bravo.xx");
        });
        assertThat(next.guide().programmes()).extracting(programme -> programme.channelId().value())
                .containsExactly("bravo.xx");
    }

    @Test
    void aDeclaredChannelThatPublishesNoProgrammeSurvivesTheRoundTrip() {
        // The whole of HOU-39: "what does this source declare?" must have one answer, and a channel the
        // source names but never publishes for is still declared. Before the declarations had their own
        // collection this channel was rebuilt from the programme rows, which by definition do not mention it.
        SourceEpg document = new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("Duck TV.hu", "Duck TV", "https://example.invalid/duck.png"),
                        new SourceChannel("AMC (HD).hu", "AMC", null)),
                List.of(programme("AMC (HD).hu", 1, 2, "Show Alpha")));

        guideStore.saveNormalized(document, Map.of());

        SourceEpg restored = guideStore.normalized(A.id()).orElseThrow();
        // The set is what matters, not the order: the read is served by the (sourceId, sourceChannelId) index,
        // so MongoDB returns it in index order rather than declaration order. Ids are the identity (ADR-0002).
        assertThat(restored.channels()).extracting(SourceChannel::id)
                .containsExactlyInAnyOrder("Duck TV.hu", "AMC (HD).hu");
        assertThat(restored.channels())
                .filteredOn(channel -> channel.id().equals("Duck TV.hu"))
                .singleElement()
                .satisfies(channel -> {
                    assertThat(channel.displayName()).isEqualTo("Duck TV");
                    assertThat(channel.iconUrl()).isEqualTo("https://example.invalid/duck.png");
                });
        // The programmes are unaffected: the declaration list is additional, not a replacement.
        assertThat(restored.programmes()).extracting(SourceProgramme::title).containsExactly("Show Alpha");
    }

    @Test
    void aSourceThatPublishesNothingAtAllIsStillStoredAndStillRestored() {
        // A source can declare channels and publish no programmes for any of them. Storing that document is
        // what makes the seed reproducible afterwards rather than a one-off read of a live fetch.
        SourceEpg document = new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("hirtv.hu", "Hír TV", null)), List.of());

        guideStore.saveNormalized(document, Map.of());

        assertThat(guideStore.normalized(A.id()).orElseThrow().channels())
                .extracting(SourceChannel::id).containsExactly("hirtv.hu");
    }

    @Test
    void theDeclarationsAreIndexedPerSource() {
        guideStore.saveNormalized(new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC (HD).hu", "AMC", null)), List.of()), Map.of());

        assertThat(indexNames("sourceChannels")).contains("sourceId_1_sourceChannelId_1");
    }

    @Test
    void aDatabaseWrittenByAnOlderBuildIsBackfilledByTheNextRefresh() {
        // The migration story, proven rather than asserted: an existing database has programme rows and no
        // declarations, so its programme-less channels are simply not there yet. The next refresh writes the
        // declarations it fetched, and from then on the round trip is complete. Nothing is deleted, and the
        // registry the older build seeded is untouched.
        DB.getCollection("programmes").insertOne(new Document()
                .append("sourceId", "a").append("sourceChannelId", "AMC (HD).hu")
                .append("sourceChannelName", "AMC").append("canonicalChannelId", "amc.xx")
                .append("start", new java.util.Date()).append("stop", new java.util.Date(System.currentTimeMillis()
                        + java.time.Duration.ofHours(2).toMillis()))
                .append("title", "Show Alpha"));
        assertThat(DB.getCollection("sourceChannels").countDocuments()).isZero();

        // Before the refresh the old fallback answers, which is the pre-change behaviour exactly.
        assertThat(guideStore.normalized(A.id()).orElseThrow().channels()).extracting(SourceChannel::id)
                .containsExactly("AMC (HD).hu");

        documents.put("a", new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC (HD).hu", "AMC", null),
                        new SourceChannel("Duck TV.hu", "Duck TV", null)),
                List.of(programme("AMC (HD).hu", 1, 2, "Show Alpha"))));
        guide().refresh();

        assertThat(DB.getCollection("sourceChannels").countDocuments()).isEqualTo(2);
        assertThat(guideStore.normalized(A.id()).orElseThrow().channels()).extracting(SourceChannel::id)
                .containsExactlyInAnyOrder("AMC (HD).hu", "Duck TV.hu");
    }

    @Test
    void theSeedAndTheReseedAgreeAboutWhatASourceDeclares() {
        // The acceptance criterion, end to end over the real store: a source declares three channels and
        // publishes programmes for one of them. The seed writes the registry from the declarations; the
        // re-seed rebuilds it from what the store kept. Both must name the same three ids, and a re-seed over
        // a registry that is already right must propose no change (HOU-39).
        documents.put("a", new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC (HD).hu", "AMC", null),
                        new SourceChannel("Duck TV.hu", "Duck TV", null),
                        new SourceChannel("mtveuro.hu", "MTV Europe", null)),
                List.of(programme("AMC (HD).hu", 1, 2, "Show Alpha"))));
        GuideSettings settings = new GuideSettings(List.of(A), Optional.empty(), Duration.ofDays(7));

        refreshGuide(settings).refresh();
        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("AMC (HD).hu", "Duck TV.hu", "mtveuro.hu");

        ReseedRegistry reseed = new ReseedRegistry(settings, guideStore, location -> List.of());
        ReseedRegistry.Preview preview = reseed.preview();

        assertThat(preview.added()).isEmpty();
        assertThat(preview.removed()).isEmpty();
        assertThat(preview.changesAnything()).isFalse();
        assertThat(preview.proposed()).extracting(channel -> channel.id().value())
                .containsExactly("AMC (HD).hu", "Duck TV.hu", "mtveuro.hu");
    }

    @Test
    void theSeedAndTheReseedAgreeWhenTheSourcePublishesNothingAtAll() {
        // The edge that made the bug visible on the production feed: every channel of this source is
        // programme-less, so a re-seed that rebuilt its channels from programme rows would propose an empty
        // registry and withdraw every id.
        documents.put("a", new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("Hustler.TV.hu", "Hustler", null),
                        new SourceChannel("mtveuro.hu", "MTV Europe", null)), List.of()));
        GuideSettings settings = new GuideSettings(List.of(A), Optional.empty(), Duration.ofDays(7));

        refreshGuide(settings).refresh();
        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("Hustler.TV.hu", "mtveuro.hu");

        ReseedRegistry.Preview preview = new ReseedRegistry(settings, guideStore, location -> List.of()).preview();

        assertThat(preview.removed()).isEmpty();
        assertThat(preview.changesAnything()).isFalse();
    }

    private RefreshGuide refreshGuide(GuideSettings settings) {
        return new RefreshGuide(settings, this::read, location -> List.of(), matchStore, guideStore);
    }

    private RefreshGuide guide() {
        GuideSettings settings = new GuideSettings(List.of(A), Optional.of("fixture:playlist.m3u"), Duration.ofDays(7));
        return new RefreshGuide(settings, this::read, location -> List.of(
                playlistChannel("amc.xx", "AMC"), playlistChannel("bravo.xx", "Bravo")), matchStore, guideStore);
    }

    private SourceEpg read(EpgSource source) {
        if (aFails.get()) {
            throw new IllegalStateException("source is down");
        }
        SourceEpg document = documents.get(source.id().value());
        if (document == null) {
            throw new IllegalStateException("no fixture for " + source.id().value());
        }
        String payload = document.programmes().stream()
                .map(programme -> "<programme><title>" + programme.title() + "</title></programme>")
                .reduce("<tv>", String::concat) + "</tv>";
        guideStore.saveRawSnapshot(RawSnapshot.of(source.id(), Instant.now(), payload));
        return document;
    }

    private List<String> indexNames(String collection) {
        List<String> names = new ArrayList<>();
        DB.getCollection(collection).listIndexes().forEach(index -> names.add(index.getString("name")));
        return names;
    }

    private static Channel playlistChannel(String id, String displayName) {
        return new Channel(new ChannelId(id), displayName, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceEpg source(String sourceId, SourceProgramme... programmes) {
        Map<String, SourceChannel> channels = new LinkedHashMap<>();
        for (SourceProgramme programme : programmes) {
            channels.putIfAbsent(programme.sourceChannelId(),
                    new SourceChannel(programme.sourceChannelId(), programme.sourceChannelId(), null));
        }
        return new SourceEpg(new SourceId(sourceId),
                List.copyOf(channels.values()), List.of(programmes));
    }

    private static SourceProgramme programme(String channelId, int startHours, int stopHours, String title) {
        OffsetDateTime base = OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0);
        return new SourceProgramme(channelId, new Slot(base.plusHours(startHours), base.plusHours(stopHours)),
                title, "Synopsis", null, null, null);
    }

    private static SourceProgramme programmeEndedDaysAgo(String channelId, int days) {
        OffsetDateTime start = OffsetDateTime.now().minusDays(days);
        return new SourceProgramme(channelId, new Slot(start, start.plusHours(1)), "Old Show", null, null, null, null);
    }
}
