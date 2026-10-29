package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a guide refresh needs to know: the sources in priority order (highest first), the optional
 * playlist that acts as the channel authority, and how long ended programmes are retained.
 *
 * <p>ADR-0005 makes the configuration authoritative for the lifetime of the process, so the source
 * list is mutable here: a reorder and a configuration reload both change the running merge through
 * {@link #reorder(List)} and {@link #replaceSources(List)}, while the playlist and the retention stay
 * as they were booted.
 */
public final class GuideSettings {

    /** Design §7: programmes that ended more than a week ago are pruned. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    private final Optional<String> playlistLocation;
    private final Duration retention;
    private volatile List<EpgSource> sources;

    public GuideSettings(List<EpgSource> sources, Optional<String> playlistLocation, Duration retention) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.playlistLocation = playlistLocation == null ? Optional.empty() : playlistLocation;
        this.retention = retention == null || retention.isNegative() ? DEFAULT_RETENTION : retention;
    }

    public GuideSettings(List<EpgSource> sources, Optional<String> playlistLocation) {
        this(sources, playlistLocation, DEFAULT_RETENTION);
    }

    /** The sources that take part in the merge, in priority order, highest priority first. */
    public List<EpgSource> sources() {
        return sources;
    }

    public Optional<String> playlistLocation() {
        return playlistLocation;
    }

    public Duration retention() {
        return retention;
    }

    /**
     * Puts the already-configured sources in a new merge order (ADR-0005). The order must name exactly
     * them — a reorder never adds or removes, that is YAML's job.
     */
    public synchronized void reorder(List<SourceId> order) {
        Map<SourceId, EpgSource> byId = new LinkedHashMap<>();
        sources.forEach(source -> byId.put(source.id(), source));
        List<EpgSource> reordered = order.stream().map(byId::get).filter(source -> source != null).toList();
        if (reordered.size() != sources.size() || order.size() != sources.size()) {
            throw new IllegalArgumentException(
                    "a reorder names exactly the configured sources; configured " + byId.keySet()
                            + ", requested " + order);
        }
        this.sources = List.copyOf(reordered);
    }

    /** Rebinds the whole source set, which is what a configuration reload does. */
    public synchronized void replaceSources(List<EpgSource> replacement) {
        this.sources = replacement == null ? List.of() : List.copyOf(replacement);
    }
}
