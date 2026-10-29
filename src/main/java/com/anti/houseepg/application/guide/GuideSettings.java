package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.EpgSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What guide refresh needs to know: sources in priority order (highest first), optionally the location
 * the playlist that acts as channel authority, and how long ended programmes are retained.
 */
public record GuideSettings(List<EpgSource> sources, Optional<String> playlistLocation, Duration retention) {

    /** Design §7: programmes that ended more than about a week ago are pruned. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    public GuideSettings {
        sources = sources == null ? List.of() : List.copyOf(sources);
        playlistLocation = playlistLocation == null ? Optional.empty() : playlistLocation;
        retention = retention == null || retention.isNegative() ? DEFAULT_RETENTION : retention;
    }

    public GuideSettings(List<EpgSource> sources, Optional<String> playlistLocation) {
        this(sources, playlistLocation, DEFAULT_RETENTION);
    }
}
