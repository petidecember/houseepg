package com.anti.houseepg.net;

import com.anti.houseepg.config.HouseEpgProperties;
import com.anti.houseepg.guide.app.port.Credentials;
import com.anti.houseepg.guide.app.port.SourceUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
 * Shared infrastructure: fetch a payload over HTTP. Handles a gzipped body (detected by magic bytes,
 * so a provider that mislabels {@code Content-Encoding} still works), basic auth and one optional
 * custom header.
 */
@Component
public class HttpFetcher {

    private static final String USER_AGENT = "HouseEPG/0.0.1";

    private final HttpClient client;
    private final Duration timeout;

    public HttpFetcher(HouseEpgProperties properties) {
        this.timeout = properties.timeout();
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String fetchText(String location, Credentials credentials) {
        return new String(fetch(location, credentials), StandardCharsets.UTF_8);
    }

    public byte[] fetch(String location, Credentials credentials) {
        if (location == null || location.isBlank()) {
            throw new SourceUnavailableException("no location configured");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(location))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .GET();

        if (credentials != null && credentials.hasBasicAuth()) {
            String password = credentials.password() == null ? "" : credentials.password();
            String token = Base64.getEncoder()
                    .encodeToString((credentials.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }
        if (credentials != null && credentials.hasHeader()) {
            request.header(credentials.headerName(), credentials.headerValue() == null ? "" : credentials.headerValue());
        }

        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new SourceUnavailableException("HTTP " + response.statusCode() + " from " + location);
            }
            return gunzipIfNeeded(response.body(), location);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException("interrupted while fetching " + location, e);
        } catch (IOException e) {
            throw new SourceUnavailableException("could not fetch " + location + ": " + e.getMessage(), e);
        }
    }

    private static byte[] gunzipIfNeeded(byte[] body, String location) {
        if (body == null || body.length == 0) {
            throw new SourceUnavailableException("empty response from " + location);
        }
        if (body.length > 1 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return gzip.readAllBytes();
            } catch (IOException e) {
                throw new SourceUnavailableException("gzipped payload from " + location + " is unreadable", e);
            }
        }
        return body;
    }
}
