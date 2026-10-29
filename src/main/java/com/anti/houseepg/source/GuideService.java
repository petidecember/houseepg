package com.anti.houseepg.source;

import com.anti.houseepg.config.HouseEpgProperties;
import com.anti.houseepg.domain.CanonicalChannel;
import com.anti.houseepg.domain.GuideSnapshot;
import com.anti.houseepg.domain.MergedProgramme;
import com.anti.houseepg.domain.Programme;
import com.anti.houseepg.domain.SourceChannel;
import com.anti.houseepg.domain.SourceStatus;
import com.anti.houseepg.m3u.M3uParser;
import com.anti.houseepg.match.ChannelMatcher;
import com.anti.houseepg.merge.MergeEngine;
import com.anti.houseepg.xmltv.XmltvDocument;
import com.anti.houseepg.xmltv.XmltvParser;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches every source in priority order, matches its channels onto the registry, merges the
 * programmes, and keeps the result in memory. A source that fails keeps contributing its last good
 * document, so the served guide never gets worse because of a network error.
 */
@Service
public class GuideService {

    private static final Logger log = LoggerFactory.getLogger(GuideService.class);

    private final HouseEpgProperties properties;
    private final SourceFetcher fetcher;
    private final Object refreshLock = new Object();
    private final Map<String, XmltvDocument> lastGoodBySource = new ConcurrentHashMap<>();
    private volatile GuideSnapshot snapshot;

    public GuideService(HouseEpgProperties properties, SourceFetcher fetcher) {
        this.properties = properties;
        this.fetcher = fetcher;
    }

    /** The current guide, refetched only when the cached one is older than the configured TTL. */
    public GuideSnapshot snapshot() {
        GuideSnapshot current = snapshot;
        if (current != null && !expired(current)) {
            return current;
        }
        return refresh();
    }

    /** Forces a refresh. */
    public GuideSnapshot refresh() {
        synchronized (refreshLock) {
            List<CanonicalChannel> registry = loadPlaylist();
            Map<String, XmltvDocument> documents = new LinkedHashMap<>();
            List<SourceStatus> statuses = new ArrayList<>();

            for (HouseEpgProperties.SourceProperties source : properties.enabledSources()) {
                String sourceId = sourceKey(source);
                String name = source.displayName();
                try {
                    if (source.url() == null || source.url().isBlank()) {
                        throw new IllegalStateException("no url configured");
                    }
                    byte[] payload = fetcher.fetch(source);
                    XmltvDocument document = XmltvParser.parse(sourceId, new ByteArrayInputStream(payload));
                    if (document.channels().isEmpty() && document.programmes().isEmpty()) {
                        throw new IllegalStateException("document carries no channels and no programmes");
                    }
                    lastGoodBySource.put(sourceId, document);
                    documents.put(sourceId, document);
                    statuses.add(SourceStatus.ok(sourceId, name, document.programmes().size()));
                } catch (Exception e) {
                    String error = describe(e);
                    XmltvDocument fallback = lastGoodBySource.get(sourceId);
                    if (fallback == null) {
                        statuses.add(SourceStatus.failed(sourceId, name, error));
                    } else {
                        documents.put(sourceId, fallback);
                        statuses.add(SourceStatus.stale(sourceId, name, fallback.programmes().size(), error));
                    }
                    log.warn("Source {} is unavailable: {}", sourceId, error);
                }
            }

            if (registry.isEmpty()) {
                registry = seedFromPrimarySource(documents);
            }

            Map<String, List<SourceChannel>> channelsBySource = new LinkedHashMap<>();
            documents.forEach((sourceId, document) -> channelsBySource.put(sourceId, document.channels()));

            ChannelMatcher.Match match = ChannelMatcher.match(registry, channelsBySource);

            List<Programme> programmes = documents.values().stream()
                    .flatMap(document -> document.programmes().stream())
                    .toList();
            List<MergedProgramme> merged =
                    MergeEngine.merge(List.copyOf(documents.keySet()), programmes, match.canonicalIdBySourceChannel());

            GuideSnapshot built = new GuideSnapshot(match.channels(), merged, statuses, Instant.now());
            snapshot = built;
            return built;
        }
    }

    private List<CanonicalChannel> loadPlaylist() {
        String url = properties.playlistUrl();
        if (url == null || url.isBlank()) {
            return List.of();
        }
        try {
            return M3uParser.parse(fetcher.fetchText(url));
        } catch (Exception e) {
            log.warn("Playlist {} is unavailable: {}", url, describe(e));
            return List.of();
        }
    }

    private static List<CanonicalChannel> seedFromPrimarySource(Map<String, XmltvDocument> documents) {
        for (XmltvDocument document : documents.values()) {
            if (!document.channels().isEmpty()) {
                return document.channels().stream()
                        .map(channel -> new CanonicalChannel(channel.id(), channel.displayName(), channel.iconUrl(),
                                null, CanonicalChannel.Origin.PRIMARY_SOURCE))
                        .toList();
            }
        }
        return List.of();
    }

    private boolean expired(GuideSnapshot current) {
        return current.generatedAt().plus(properties.cacheTtl()).isBefore(Instant.now());
    }

    /**
     * A configured source may omit its id; fall back to its url so a bad configuration surfaces as a
     * FAILED status instead of taking a request down with it.
     */
    private static String sourceKey(HouseEpgProperties.SourceProperties source) {
        if (source.id() != null && !source.id().isBlank()) {
            return source.id();
        }
        if (source.url() != null && !source.url().isBlank()) {
            return source.url();
        }
        return "unnamed-source";
    }

    private static String describe(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
