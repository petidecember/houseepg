package com.anti.houseepg.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.config.HouseEpgProperties;
import com.anti.houseepg.domain.CanonicalChannel;
import com.anti.houseepg.domain.GuideSnapshot;
import com.anti.houseepg.domain.MergedProgramme;
import com.anti.houseepg.domain.SourceStatus;
import com.anti.houseepg.xmltv.XmltvDocument;
import com.anti.houseepg.xmltv.XmltvParser;
import com.anti.houseepg.xmltv.XmltvWriter;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end over real HTTP: a playlist that acts as channel authority, two XMLTV sources (one
 * gzipped), the merge, the rendered feed, and the failure policy where a broken source keeps serving
 * its last good data.
 */
class GuideServiceTest {

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
    private GuideService guideService;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/playlist.m3u", exchange -> respond(exchange, 200, PLAYLIST.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/a.xml", exchange -> respond(exchange, 200, SOURCE_A.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/b.xml.gz", exchange -> {
            if (sourceBFails.get()) {
                respond(exchange, 503, "unavailable".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, gzip(SOURCE_B));
        });
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HouseEpgProperties properties = new HouseEpgProperties(
                base + "/playlist.m3u",
                List.of(
                        new HouseEpgProperties.SourceProperties("a", "Source A", base + "/a.xml",
                                null, null, null, null, true),
                        new HouseEpgProperties.SourceProperties("b", "Source B", base + "/b.xml.gz",
                                null, null, null, null, true)),
                Duration.ofHours(1),
                Duration.ofSeconds(10));
        guideService = new GuideService(properties, new SourceFetcher(properties));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mergesTwoSourcesUnderTheThresholdPlaylistIds() {
        GuideSnapshot snapshot = guideService.refresh();

        assertThat(snapshot.channels()).extracting(CanonicalChannel::id)
                .containsExactlyInAnyOrder("bbc1.uk", "itv1.uk", "Dave");
        assertThat(snapshot.channels()).filteredOn(channel -> channel.id().equals("bbc1.uk"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(CanonicalChannel.Origin.PLAYLIST));

        assertThat(snapshot.programmes()).hasSize(4);
        // Sorted by canonical channel id, then start time: "Dave" sorts before the lowercase ids.
        assertThat(snapshot.programmes()).extracting(MergedProgramme::title)
                .containsExactly("Panel Show", "EastEnders", "News", "Film X");

        // The lower-priority film that straddles the winner is gone, not clipped.
        assertThat(snapshot.programmes()).extracting(MergedProgramme::title).doesNotContain("Heat");

        MergedProgramme eastEnders = snapshot.programmes().stream()
                .filter(programme -> programme.title().equals("EastEnders"))
                .findFirst()
                .orElseThrow();
        assertThat(eastEnders.winningSourceId()).isEqualTo("a");
        assertThat(eastEnders.description()).isEqualTo("Full synopsis");
    }

    @Test
    void servesUnmatchedChannelsUnderTheirOwnId() {
        GuideSnapshot snapshot = guideService.refresh();

        assertThat(snapshot.programmes())
                .filteredOn(programme -> programme.title().equals("Panel Show"))
                .singleElement()
                .satisfies(programme -> assertThat(programme.canonicalChannelId()).isEqualTo("Dave"));
    }

    @Test
    void rendersAFeedThatParsesBackToTheSameProgrammes() throws Exception {
        String feed = XmltvWriter.write(guideService.refresh().channels(), guideService.refresh().programmes());

        assertThat(feed).contains("generator-info-name=\"HouseEPG\"");
        assertThat(feed).contains("channel=\"bbc1.uk\"");
        assertThat(feed).contains("start=\"20261029200000 +0000\"");

        XmltvDocument parsed = XmltvParser.parse("merged",
                new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8)));
        assertThat(parsed.channels()).hasSize(3);
        assertThat(parsed.programmes()).hasSize(4);
    }

    @Test
    void aFailingSourceKeepsServingItsLastGoodData() {
        guideService.refresh();
        sourceBFails.set(true);

        GuideSnapshot snapshot = guideService.refresh();

        SourceStatus sourceB = snapshot.statuses().stream()
                .filter(status -> status.sourceId().equals("b"))
                .findFirst()
                .orElseThrow();
        assertThat(sourceB.state()).isEqualTo(SourceStatus.State.STALE);
        // Still contributing: the guide did not get worse because of the network error.
        assertThat(snapshot.programmes()).extracting(MergedProgramme::title).contains("News");
    }

    @Test
    void aFailingPlaylistFallsBackToThePrimarySourceAsTheRegistry() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HouseEpgProperties properties = new HouseEpgProperties(
                base + "/missing.m3u",
                List.of(new HouseEpgProperties.SourceProperties("a", "Source A", base + "/a.xml",
                        null, null, null, null, true)),
                Duration.ofHours(1),
                Duration.ofSeconds(10));

        GuideSnapshot snapshot = new GuideService(properties, new SourceFetcher(properties)).refresh();

        assertThat(snapshot.channels()).extracting(CanonicalChannel::id)
                .contains("BBC1", "ITV1HD");
        assertThat(snapshot.channels()).filteredOn(channel -> channel.id().equals("BBC1"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin())
                        .isEqualTo(CanonicalChannel.Origin.PRIMARY_SOURCE));
    }

    @Test
    void aMisconfiguredSourceFailsInsteadOfBreakingTheFeed() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HouseEpgProperties properties = new HouseEpgProperties(
                null,
                List.of(
                        new HouseEpgProperties.SourceProperties(null, null, base + "/a.xml",
                                null, null, null, null, true),
                        new HouseEpgProperties.SourceProperties("b", "Source B", null,
                                null, null, null, null, true)),
                Duration.ofHours(1),
                Duration.ofSeconds(10));

        GuideSnapshot snapshot = new GuideService(properties, new SourceFetcher(properties)).refresh();

        // The id-less source is keyed by its url; the url-less source fails and carries no data.
        assertThat(snapshot.statuses()).extracting(SourceStatus::sourceId).containsExactly(base + "/a.xml", "b");
        assertThat(snapshot.statuses()).extracting(SourceStatus::state)
                .containsExactly(SourceStatus.State.OK, SourceStatus.State.FAILED);
        assertThat(snapshot.programmes()).isNotEmpty();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
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
