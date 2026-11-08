package com.anti.houseepg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return new SourceEpg(new com.anti.houseepg.domain.guide.model.SourceId(sourceId),
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
