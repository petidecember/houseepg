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
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.infrastructure.net.HttpFetcher;
import com.anti.houseepg.infrastructure.match.InMemoryChannelMatchStore;
import com.anti.houseepg.infrastructure.playlist.M3uChannelAuthority;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private HttpServer server;
    private final AtomicBoolean sourceBFails = new AtomicBoolean(false);
    private final AtomicBoolean playlistFails = new AtomicBoolean(false);
    private final InMemoryChannelMatchStore matchStore = new InMemoryChannelMatchStore();
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

    private ServeGuide serveGuide(Optional<String> playlistLocation, boolean withBothSources) {
        GuideSettings settings = new GuideSettings(sources(withBothSources), playlistLocation);
        return new ServeGuide(refreshGuide(settings), Duration.ofHours(1));
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
                new HouseEpgProperties(null, List.of(), Duration.ofHours(1), Duration.ofSeconds(10));
        HttpFetcher fetcher = new HttpFetcher(properties);
        return new RefreshGuide(settings, new HttpEpgSourceGateway(fetcher, new XmltvReader()),
                new M3uChannelAuthority(fetcher), matchStore);
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
