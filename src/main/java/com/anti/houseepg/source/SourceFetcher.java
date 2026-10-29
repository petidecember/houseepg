package com.anti.houseepg.source;

import com.anti.houseepg.config.HouseEpgProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * Fetches a source over HTTP. Handles plain XML, gzipped payloads (detected by magic bytes, so it does
 * not depend on the provider setting a correct header), basic auth, and an optional custom header.
 */
@Component
public class SourceFetcher {

    private static final String USER_AGENT = "HouseEPG/0.0.1";

    private final HttpClient client;
    private final Duration timeout;

    public SourceFetcher(HouseEpgProperties properties) {
        this.timeout = properties.timeout();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public byte[] fetch(HouseEpgProperties.SourceProperties source) {
        return fetch(source.url(), source.username(), source.password(), source.headerName(), source.headerValue());
    }

    public String fetchText(String url) {
        return new String(fetch(url, null, null, null, null), StandardCharsets.UTF_8);
    }

    private byte[] fetch(String url, String username, String password, String headerName, String headerValue) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .GET();

        if (username != null && !username.isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }
        if (headerName != null && !headerName.isBlank()) {
            request.header(headerName, headerValue == null ? "" : headerValue);
        }

        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new UncheckedIOException(new IOException(
                        "HTTP " + response.statusCode() + " from " + url));
            }
            return gunzipIfNeeded(response.body(), url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted while fetching " + url, e));
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("Could not fetch " + url + ": " + e.getMessage(), e));
        }
    }

    private static byte[] gunzipIfNeeded(byte[] body, String url) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("Empty response from " + url);
        }
        if (body.length > 1 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return gzip.readAllBytes();
            }
        }
        return body;
    }
}
