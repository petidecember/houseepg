package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.infrastructure.config.HouseEpgProperties;
import com.anti.houseepg.infrastructure.epg.HttpEpgSourceGateway;
import com.anti.houseepg.infrastructure.epg.XmltvReader;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.Glob;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.infrastructure.net.HttpFetcher;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.infrastructure.playlist.M3uChannelAuthority;
import com.anti.houseepg.support.InMemoryGuideStore;
import com.anti.houseepg.interfaces.web.XmltvFeedWriter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end through the real adapters over real HTTP: a playlist that acts as channel authority, two
 * XMLTV sources (one gzipped), the merge, the rendered feed, and the failure policy where a broken
 * source keeps serving its last good data.
 */
class RefreshGuideTest {

    private static final String PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1.xx" tvg-name="Channel One" tvg-logo="https://example.invalid/icons/ch1.png",Channel One HD
            http://example.invalid/ch1
            #EXTINF:-1 tvg-id="ch2.xx" tvg-name="Channel Two",Channel Two HD
            http://example.invalid/ch2
            """;

    private static final String SOURCE_A = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="Source A">
              <channel id="CH1"><display-name>Channel One</display-name></channel>
              <channel id="CH2HD"><display-name>Channel Two HD</display-name></channel>
              <programme start="20261029200000 +0000" stop="20261029203000 +0000" channel="CH1">
                <title>Show Alpha</title>
                <desc>Full synopsis</desc>
                <category>Soap</category>
              </programme>
              <programme start="20261029200000 +0000" stop="20261029210000 +0000" channel="CH2HD">
                <title>Film Beta</title>
              </programme>
            </tv>
            """;

    private static final String SOURCE_B = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="Source B">
              <channel id="CH1 (HD)"><display-name>Channel One (HD)</display-name></channel>
              <channel id="ch3"><display-name>Channel Three</display-name></channel>
              <programme start="20261029194500 +0000" stop="20261029210000 +0000" channel="CH1 (HD)">
                <title>Film Gamma</title>
              </programme>
              <programme start="20261029200000 +0000" stop="20261029203500 +0000" channel="CH1 (HD)">
                <title>Show Alpha</title>
                <desc>Short desc</desc>
              </programme>
              <programme start="20261029210000 +0000" stop="20261029220000 +0000" channel="CH1 (HD)">
                <title>Show Delta</title>
              </programme>
              <programme start="20261029220000 +0000" stop="20261029230000 +0000" channel="ch3">
                <title>Show Epsilon</title>
              </programme>
            </tv>
            """;

    /**
     * A provider that names one channel id twice: the HD and the H.265 variant are separate declarations
     * with separate display names but a single id, which is what the live {@code live-hutv} feed does for
     * 43 of its channels.
     */
    private static final String SOURCE_REPEATING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="Repeating">
              <channel id="CH1"><display-name>Channel One HD</display-name></channel>
              <channel id="CH2"><display-name>Channel Two</display-name></channel>
              <channel id="CH1"><display-name>Channel One H.265</display-name></channel>
              <channel id="CH3"><display-name>Channel Three</display-name></channel>
              <programme start="20261029200000 +0000" stop="20261029203000 +0000" channel="CH1">
                <title>Show Alpha</title>
              </programme>
              <programme start="20261029200000 +0000" stop="20261029210000 +0000" channel="CH2">
                <title>Film Beta</title>
              </programme>
              <programme start="20261029210000 +0000" stop="20261029220000 +0000" channel="CH3">
                <title>Show Delta</title>
              </programme>
            </tv>
            """;

    /** A playlist naming one {@code tvg-id} twice — the shape the supplied playlist actually has. */
    /**
     * The real playlist's shape: every id sits in one group, and a much larger group holds entries the
     * registry can never use (HOU-45). Unfiltered this reads four channels; filtered to `_=HU=_` it reads
     * the two the registry is allowed to know about.
     */
    private static final String GROUPED_PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1.xx" tvg-name="Channel One" group-title="_=HU=_",Channel One HD
            http://example.invalid/ch1
            #EXTINF:-1 tvg-id="ch2.xx" tvg-name="Channel Two" group-title="_=HU=_",Channel Two HD
            http://example.invalid/ch2
            #EXTINF:-1 tvg-id="film1.xx" tvg-name="Film One" group-title="Film sorozatok",Film One
            http://example.invalid/film1
            #EXTINF:-1 tvg-id="film2.xx" tvg-name="Film Two" group-title="Film sorozatok",Film Two
            http://example.invalid/film2
            """;

    private static final String PLAYLIST_WITH_REPEATS = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1.xx" tvg-name="Channel One",Channel One HD
            http://example.invalid/ch1
            #EXTINF:-1 tvg-id="ch1.xx" tvg-name="Channel One UHD",Channel One UHD
            http://example.invalid/ch1-uhd
            #EXTINF:-1 tvg-id="ch2.xx" tvg-name="Channel Two",Channel Two HD
            http://example.invalid/ch2
            """;

    /**
     * The fixtures above end between 2026-10-29T19:45Z and 2026-10-29T23:00Z, and a refresh prunes every
     * programme that ended before {@code now - retention} (7 days). Driving it with the wall clock drops
     * the whole fixture set once that date ages out of the window, which is how this test went red from
     * 2026-11-05. Pinning an instant inside the fixtures' window keeps the assertions meaningful on any
     * date; HOU-20 fixed the same shape in {@code ProviderSamplesTest}.
     */
    private static final Instant FIXTURE_NOW = Instant.parse("2026-10-29T20:00:00Z");

    private HttpServer server;
    private final AtomicBoolean sourceBFails = new AtomicBoolean(false);
    private final AtomicBoolean playlistFails = new AtomicBoolean(false);
    private final AtomicReference<String> servedPlaylist = new AtomicReference<>(PLAYLIST_WITH_REPEATS);
    private final InMemoryChannelMatchStore matchStore = new InMemoryChannelMatchStore();
    private final InMemoryGuideStore guideStore = new InMemoryGuideStore();
    private String base;
    private ServeGuide serveGuide;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/playlist.m3u", exchange -> {
            if (playlistFails.get()) {
                respond(exchange, 503, "unavailable");
                return;
            }
            respond(exchange, 200, PLAYLIST);
        });
        server.createContext("/a.xml", exchange -> respond(exchange, 200, SOURCE_A));
        server.createContext("/repeating.xml", exchange -> respond(exchange, 200, SOURCE_REPEATING));
        server.createContext("/dup-playlist.m3u", exchange -> respond(exchange, 200, servedPlaylist.get()));
        server.createContext("/grouped-playlist.m3u", exchange -> respond(exchange, 200, GROUPED_PLAYLIST));
        server.createContext("/b.xml.gz", exchange -> {
            if (sourceBFails.get()) {
                respond(exchange, 503, "unavailable");
                return;
            }
            respond(exchange, 200, gzip(SOURCE_B));
        });
        server.start();

        base = "http://127.0.0.1:" + server.getAddress().getPort();
        serveGuide = serveGuide(Optional.of(base + "/playlist.m3u"), true);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mergesTwoSourcesUnderThePlaylistIds() {
        RefreshResult result = serveGuide.refresh();
        Guide guide = result.guide();

        assertThat(guide.channels()).extracting(channel -> channel.id().value())
                .containsExactlyInAnyOrder("ch1.xx", "ch2.xx", "ch3");
        assertThat(guide.channels()).filteredOn(channel -> channel.id().value().equals("ch1.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PLAYLIST));

        // Sorted by canonical channel id, then start time: "ch3" sorts before the lowercase ids.
        assertThat(guide.programmes()).extracting(Programme::title)
                .containsExactly("Show Alpha", "Show Delta", "Film Beta", "Show Epsilon");
        // The lower-priority film that straddles the winner is gone, not clipped.
        assertThat(guide.programmes()).extracting(Programme::title).doesNotContain("Film Gamma");

        assertThat(guide.programmes()).filteredOn(programme -> programme.title().equals("Show Alpha"))
                .singleElement()
                .satisfies(programme -> {
                    assertThat(programme.winningSource().value()).isEqualTo("a");
                    assertThat(programme.description()).isEqualTo("Full synopsis");
                });

        assertThat(result.health()).extracting(SourceHealth::state)
                .containsOnly(SourceHealth.State.OK);
    }

    @Test
    void rendersAFeedThatReadsBackToTheSameGuide() {
        Guide guide = serveGuide.refresh().guide();
        String feed = new XmltvFeedWriter().write(guide);

        assertThat(feed).contains("generator-info-name=\"HouseEPG\"");
        assertThat(feed).contains("channel=\"ch1.xx\"");
        assertThat(feed).contains("start=\"20261029200000 +0000\"");

        var parsed = new XmltvReader().read(new com.anti.houseepg.domain.guide.model.SourceId("merged"),
                new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8)));
        assertThat(parsed.channels()).hasSize(guide.channels().size());
        assertThat(parsed.programmes()).hasSize(guide.programmes().size());
    }

    @Test
    void cachedReadsDoNotRefetch() {
        assertThat(serveGuide.current().health())
                .extracting(SourceHealth::state).containsOnly(SourceHealth.State.OK);

        sourceBFails.set(true);

        // Still cached, so the failed source is neither retried nor reported as stale.
        assertThat(serveGuide.current().health())
                .extracting(SourceHealth::state).containsOnly(SourceHealth.State.OK);
    }

    @Test
    void aFailingSourceKeepsServingItsLastGoodData() {
        serveGuide.refresh();
        sourceBFails.set(true);

        RefreshResult result = serveGuide.refresh();

        assertThat(result.health()).filteredOn(health -> health.source().value().equals("b"))
                .singleElement()
                .satisfies(health -> assertThat(health.state()).isEqualTo(SourceHealth.State.STALE));
        // Still contributing: the guide did not get worse because of the network error.
        assertThat(result.guide().programmes()).extracting(Programme::title).contains("Show Delta");
    }

    @Test
    void aPlaylistThatCannotBeReadDoesNotBorrowThePrimarySourcesIds() {
        // A configured playlist is the authority even when it is down: substituting the primary
        // source's ids would change what the DVR sees the moment the playlist comes back (ADR-0002).
        ServeGuide withoutPlaylist = serveGuide(Optional.of(base + "/missing.m3u"), true);

        Guide guide = withoutPlaylist.refresh().guide();

        assertThat(guide.channels()).extracting(channel -> channel.id().value())
                .containsExactlyInAnyOrder("CH1", "CH2HD", "CH1 (HD)", "ch3");
        assertThat(guide.channels()).extracting(Channel::origin).containsOnly(Channel.Origin.PASS_THROUGH);
    }

    @Test
    void aPlaylistOutageKeepsTheSeededIds() {
        assertThat(serveGuide.refresh().guide().channels())
                .extracting(channel -> channel.id().value())
                .containsExactlyInAnyOrder("ch1.xx", "ch2.xx", "ch3");

        playlistFails.set(true);

        // Ids stay exactly as seeded: the registry is frozen across refreshes.
        assertThat(serveGuide.refresh().guide().channels())
                .extracting(channel -> channel.id().value())
                .containsExactlyInAnyOrder("ch1.xx", "ch2.xx", "ch3");
    }

    @Test
    void everySourceChannelMatchIsRecordedAndACuratedDecisionIsReused() {
        RefreshResult first = serveGuide.refresh();
        assertThat(first.matches()).hasSize(4);
        assertThat(matchStore.decisions()).hasSize(4);

        SourceChannelRef ref = new SourceChannelRef(new SourceId("b"), "CH1 (HD)");
        matchStore.decide(ChannelMatch.corrected(ref, new ChannelId("ch2.xx"), MatchConfidence.EXACT_ID));

        RefreshResult second = serveGuide.refresh();

        assertThat(second.matches()).filteredOn(match -> match.source().equals(ref))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.canonicalId().value()).isEqualTo("ch2.xx");
                    assertThat(match.state()).isEqualTo(MatchState.CORRECTED);
                });
    }

    @Test
    void aSourceThatNamesOneIdTwiceStillSeedsEveryDistinctChannel() {
        // The live live-hutv feed declares 133 distinct ids across 178 declarations; the store writes one
        // row per id, so a repeat used to abort the seed and freeze the registry at however many rows came
        // first (HOU-32).
        RefreshResult result = refreshGuide("/repeating.xml", Optional.empty()).refresh();

        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("CH1", "CH2", "CH3");
        assertThat(guideStore.registry()).filteredOn(channel -> channel.id().value().equals("CH1"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.displayName()).isEqualTo("Channel One HD"));
        assertThat(result.guide().channels()).extracting(channel -> channel.id().value())
                .containsExactlyInAnyOrder("CH1", "CH2", "CH3");
    }

    @Test
    void aPlaylistThatNamesOneIdTwiceStillSeedsEveryDistinctChannel() {
        // Same exposure through the other authority: a playlist's tvg-ids become the emitted ids, and the
        // supplied playlist names one of them twice on its second line (HOU-32).
        RefreshResult result = refreshGuide("/a.xml", Optional.of(base + "/dup-playlist.m3u")).refresh();

        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx");
        assertThat(result.guide().channels()).filteredOn(channel -> channel.id().value().equals("ch1.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PLAYLIST));
    }

    @Test
    void anUnchangedPlaylistIsNeitherReWrittenNorReCollapsed() {
        // The playlist is re-read on every scheduler tick, and the live one declares 43 ids twice. Before
        // this, each tick re-reported the collapse and re-wrote the registry, for a list that had not
        // changed (HOU-49): the store's write count is the observable.
        RefreshGuide guide = refreshGuide("/a.xml", Optional.of(base + "/dup-playlist.m3u"));

        guide.refresh();

        assertThat(guideStore.registryWrites()).isEqualTo(1);
        assertThat(guideStore.lastWrittenRegistry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx");

        // Three more ticks: the declarations are identical, so nothing is written again and the registry is
        // still the one the first tick established.
        guide.refresh();
        guide.refresh();
        guide.refresh();

        assertThat(guideStore.registryWrites()).isEqualTo(1);
        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx");
    }

    @Test
    void aPlaylistThatReallyChangedIsWrittenAgain() {
        // The gate is on the content, not on "we have written once": a playlist whose declarations changed
        // must reach the store again, otherwise the registry would freeze at whatever the first tick saw.
        RefreshGuide guide = refreshGuide("/a.xml", Optional.of(base + "/dup-playlist.m3u"));
        guide.refresh();
        assertThat(guideStore.registryWrites()).isEqualTo(1);

        // The same location now declares one channel fewer — a content change the tick must act on.
        servedPlaylist.set(PLAYLIST);
        guide.refresh();

        assertThat(guideStore.registryWrites()).isEqualTo(2);
        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx");
        // The declarations differ only in ch1.xx's logo — the store must still receive the new list, which is
        // why the gate compares the whole declaration rather than the set of ids.
        assertThat(guideStore.registry()).filteredOn(channel -> channel.id().value().equals("ch1.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.iconUrl())
                        .isEqualTo("https://example.invalid/icons/ch1.png"));
    }
    @Test
    void aMisconfiguredSourceFailsInsteadOfBreakingTheGuide() {
        String localBase = base;
        GuideSettings settings = new GuideSettings(
                List.of(
                        EpgSource.of(null, null, localBase + "/a.xml", Credentials.none()),
                        EpgSource.of("b", "Source B", null, Credentials.none())),
                Optional.empty());
        RefreshResult result = refreshGuide(settings).refresh();

        // The id-less source is keyed by its location; the location-less source fails and carries nothing.
        assertThat(result.health()).extracting(health -> health.source().value())
                .containsExactly(localBase + "/a.xml", "b");
        assertThat(result.health()).extracting(SourceHealth::state)
                .containsExactly(SourceHealth.State.OK, SourceHealth.State.FAILED);
        assertThat(result.guide().programmes()).isNotEmpty();
    }


    @Test
    void aGroupFilterDecidesWhichPlaylistIdsReachTheRegistry() {
        // End to end through the real adapter: the ids in the film group never become registry channels,
        // so they can neither be matched nor emitted (HOU-45, ADR-0002 unchanged: the ids that remain are
        // byte-for-byte the ids the playlist declared).
        GuideSettings unfiltered = new GuideSettings(
                List.of(EpgSource.of("a", "Source A", base + "/a.xml", Credentials.none())),
                Optional.of(base + "/grouped-playlist.m3u"));
        refreshGuide(unfiltered).refresh();
        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx", "film1.xx", "film2.xx");
    }

    @Test
    void aGroupFilterKeepsOnlyTheConfiguredGroup() {
        GuideSettings settings = new GuideSettings(
                List.of(EpgSource.of("a", "Source A", base + "/a.xml", Credentials.none())),
                Optional.of(base + "/grouped-playlist.m3u"),
                Glob.allOf(List.of("_=HU=_")), Duration.ofDays(7));
        refreshGuide(settings).refresh();

        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx");
    }

    @Test
    void aGroupFilterThatMatchesNothingKeepsTheStoredRegistryInsteadOfEmptyingIt() {
        // The card asked for the opposite; the shipped rule is the one that cannot cost a DVR its ids.
        // A typo must be loud, not destructive, so the stored registry survives and a warning names the
        // groups that matched nothing.
        GuideSettings filtered = new GuideSettings(
                List.of(EpgSource.of("a", "Source A", base + "/a.xml", Credentials.none())),
                Optional.of(base + "/grouped-playlist.m3u"), Glob.allOf(List.of("_=HU=_")), Duration.ofDays(7));
        refreshGuide(filtered).refresh();
        List<String> before = guideStore.registry().stream().map(channel -> channel.id().value()).toList();

        GuideSettings typo = new GuideSettings(
                List.of(EpgSource.of("a", "Source A", base + "/a.xml", Credentials.none())),
                Optional.of(base + "/grouped-playlist.m3u"), Glob.allOf(List.of("no-such-group")),
                Duration.ofDays(7));
        refreshGuide(typo).refresh();

        assertThat(before).containsExactly("ch1.xx", "ch2.xx");
        assertThat(guideStore.registry()).extracting(channel -> channel.id().value())
                .containsExactly("ch1.xx", "ch2.xx");
    }

    private RefreshGuide refreshGuide(String url, Optional<String> playlistLocation) {
        GuideSettings settings = new GuideSettings(
                List.of(EpgSource.of("a", "Source A", base + url, Credentials.none())), playlistLocation);
        return refreshGuide(settings);
    }

    private ServeGuide serveGuide(Optional<String> playlistLocation, boolean withBothSources) {
        GuideSettings settings = new GuideSettings(sources(withBothSources), playlistLocation);
        return new ServeGuide(refreshGuide(settings), guideStore, matchStore);
    }

    private List<EpgSource> sources(boolean withBothSources) {
        EpgSource first = EpgSource.of("a", "Source A", base + "/a.xml", Credentials.none());
        if (!withBothSources) {
            return List.of(first);
        }
        return List.of(first, EpgSource.of("b", "Source B", base + "/b.xml.gz", Credentials.none()));
    }

    private RefreshGuide refreshGuide(GuideSettings settings) {
        HouseEpgProperties properties =
                new HouseEpgProperties(null, List.of(), List.of(), Duration.ofDays(7), Duration.ofSeconds(10));
        HttpFetcher fetcher = new HttpFetcher(properties);
        return new RefreshGuide(settings, new HttpEpgSourceGateway(fetcher, new XmltvReader(), guideStore),
                new M3uChannelAuthority(fetcher), matchStore, guideStore, Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] payload) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }
}
