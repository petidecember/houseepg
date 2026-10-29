package com.anti.houseepg.guide.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.config.HouseEpgProperties;
import com.anti.houseepg.epg.HttpEpgSourceGateway;
import com.anti.houseepg.epg.XmltvReader;
import com.anti.houseepg.guide.app.port.Credentials;
import com.anti.houseepg.guide.app.port.EpgSource;
import com.anti.houseepg.guide.model.Channel;
import com.anti.houseepg.guide.model.Guide;
import com.anti.houseepg.guide.model.Programme;
import com.anti.houseepg.guide.model.SourceHealth;
import com.anti.houseepg.net.HttpFetcher;
import com.anti.houseepg.playlist.M3uChannelAuthority;
import com.anti.houseepg.web.XmltvFeedWriter;
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
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://example/bbc1.png",BBC One HD
            http://example/bbc1
            #EXTINF:-1 tvg-id="itv1.uk" tvg-name="ITV1",ITV1 HD
            http://example/itv1
            """;

    private static final String SOURCE_A = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="Source A">
              <channel id="BBC1"><display-name>BBC One</display-name></channel>
              <channel id="ITV1HD"><display-name>ITV1 HD</display-name></channel>
              <programme start="20261029200000 +0000" stop="20261029203000 +0000" channel="BBC1">
                <title>EastEnders</title>
                <desc>Full synopsis</desc>
                <category>Soap</category>
              </programme>
              <programme start="20261029200000 +0000" stop="20261029210000 +0000" channel="ITV1HD">
                <title>Film X</title>
              </programme>
            </tv>
            """;

    private static final String SOURCE_B = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv source-info-name="Source B">
              <channel id="BBC1HD"><display-name>BBC One (HD)</display-name></channel>
              <channel id="Dave"><display-name>Dave</display-name></channel>
              <programme start="20261029194500 +0000" stop="20261029210000 +0000" channel="BBC1HD">
                <title>Heat</title>
              </programme>
              <programme start="20261029200000 +0000" stop="20261029203500 +0000" channel="BBC1HD">
                <title>EastEnders</title>
                <desc>Short desc</desc>
              </programme>
              <programme start="20261029210000 +0000" stop="20261029220000 +0000" channel="BBC1HD">
                <title>News</title>
              </programme>
              <programme start="20261029220000 +0000" stop="20261029230000 +0000" channel="Dave">
                <title>Panel Show</title>
              </programme>
            </tv>
            """;

    private HttpServer server;
    private final AtomicBoolean sourceBFails = new AtomicBoolean(false);
    private String base;
    private ServeGuide serveGuide;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/playlist.m3u", exchange -> respond(exchange, 200, PLAYLIST));
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
                .containsExactlyInAnyOrder("bbc1.uk", "itv1.uk", "Dave");
        assertThat(guide.channels()).filteredOn(channel -> channel.id().value().equals("bbc1.uk"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PLAYLIST));

        // Sorted by canonical channel id, then start time: "Dave" sorts before the lowercase ids.
        assertThat(guide.programmes()).extracting(Programme::title)
                .containsExactly("Panel Show", "EastEnders", "News", "Film X");
        // The lower-priority film that straddles the winner is gone, not clipped.
        assertThat(guide.programmes()).extracting(Programme::title).doesNotContain("Heat");

        assertThat(guide.programmes()).filteredOn(programme -> programme.title().equals("EastEnders"))
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
        assertThat(feed).contains("channel=\"bbc1.uk\"");
        assertThat(feed).contains("start=\"20261029200000 +0000\"");

        var parsed = new XmltvReader().read(new com.anti.houseepg.guide.model.SourceId("merged"),
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
        assertThat(result.guide().programmes()).extracting(Programme::title).contains("News");
    }

    @Test
    void anUnavailablePlaylistFallsBackToThePrimarySourceAsTheRegistry() {
        ServeGuide withoutPlaylist = serveGuide(Optional.of(base + "/missing.m3u"), true);

        Guide guide = withoutPlaylist.refresh().guide();

        assertThat(guide.channels()).extracting(channel -> channel.id().value()).contains("BBC1", "ITV1HD");
        assertThat(guide.channels()).filteredOn(channel -> channel.id().value().equals("BBC1"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PRIMARY_SOURCE));
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

    private static RefreshGuide refreshGuide(GuideSettings settings) {
        HouseEpgProperties properties =
                new HouseEpgProperties(null, List.of(), Duration.ofHours(1), Duration.ofSeconds(10));
        HttpFetcher fetcher = new HttpFetcher(properties);
        return new RefreshGuide(settings, new HttpEpgSourceGateway(fetcher, new XmltvReader()),
                new M3uChannelAuthority(fetcher));
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
