package com.anti.houseepg;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End to end: the real Spring application, configured from properties, fetching fixtures over real HTTP
 * from a stub provider, serving the feed. This is the check that ties config binding, the adapters, the
 * application layer and the controller together and compares the result to the same golden file the
 * unit test asserts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedIntegrationTest {

    private static final HttpServer STUB = startStub();

    static {
        // Runs before the Spring context, so a previous run's materialized guide cannot be served.
        try (var client = com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:27017")) {
            client.getDatabase("houseepg_test").drop();
        }
    }
    private static final String STUB_BASE = "http://127.0.0.1:" + STUB.getAddress().getPort();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void guideProperties(DynamicPropertyRegistry registry) {
        registry.add("houseepg.playlist-url", () -> STUB_BASE + "/overlap-playlist.m3u");
        registry.add("houseepg.sources[0].id", () -> "a");
        registry.add("houseepg.sources[0].name", () -> "Source A");
        registry.add("houseepg.sources[0].url", () -> STUB_BASE + "/overlap-source-a.xml");
        registry.add("houseepg.sources[0].enabled", () -> "true");
        registry.add("houseepg.sources[1].id", () -> "b");
        registry.add("houseepg.sources[1].name", () -> "Source B");
        registry.add("houseepg.sources[1].url", () -> STUB_BASE + "/overlap-source-b.xml");
        registry.add("houseepg.sources[1].enabled", () -> "true");
        // A dedicated test database: the integration suite must never touch real guide data.
        registry.add("spring.data.mongodb.uri", () -> "mongodb://127.0.0.1:27017");
        registry.add("houseepg.mongodb.database", () -> "houseepg_test");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Test
    void servesTheGoldenFeed() throws Exception {
        HttpResponse<String> response = get("/xmltv");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/xml"));
        assertThat(response.body()).isEqualTo(fixture("/fixtures/overlap-expected.xml").trim());
    }

    @Test
    void reportsBothSourcesHealthy() throws Exception {
        HttpResponse<String> response = get("/api/sources/status");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"sourceId\":\"a\"", "\"sourceId\":\"b\"", "\"state\":\"OK\"");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Pins the fixture window for this suite. The stub fixtures above are dated 2026-10-29, and retention
     * drops everything that ended before {@code now - 7 days}, so on the wall clock this suite empties its
     * own fixtures once that date ages out of the window (HOU-29). The instant sits inside the fixtures'
     * window; {@code @Primary} is what makes it win over the application's {@code Clock.systemUTC()}
     * without colliding with the other {@code @SpringBootTest} in this source set.
     */
    @TestConfiguration
    static class FixtureClock {

        private static final Instant FIXTURE_NOW = Instant.parse("2026-10-29T20:00:00Z");

        /**
         * A clock rather than {@code Clock.systemUTC()} inline, so a test can pin the instant — the seam
         * HOU-20 introduced on {@link RefreshGuide}, applied through the Spring wiring here.
         */
        @Bean
        @Primary
        Clock fixtureClock() {
            return Clock.fixed(FIXTURE_NOW, ZoneOffset.UTC);
        }
    }

    private static String fixture(String path) {
        try (InputStream input = FeedIntegrationTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A stub provider that serves the fixture files by path, so the app resolves nothing on disk. */
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> serve(exchange));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void serve(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            InputStream input = FeedIntegrationTest.class.getResourceAsStream("/fixtures" + path);
            if (input == null) {
                respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (input) {
                respond(exchange, 200, input.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}

