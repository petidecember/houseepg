package com.anti.houseepg.application.guide.port;

import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.Glob;
import java.time.Duration;
import java.util.List;

/** A configured upstream source: where to read it, how to authenticate, and how often to refresh it. */
public record EpgSource(SourceId id, String name, String location, Credentials credentials,
        Duration refreshInterval, List<Glob> excludedChannelIds) {

    /** Design §8: sources refresh on their own schedule; 8 h is the documented default. */
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofHours(8);

    public EpgSource {
        credentials = credentials == null ? Credentials.none() : credentials;
        refreshInterval = refreshInterval == null || refreshInterval.isNegative() || refreshInterval.isZero()
                ? DEFAULT_REFRESH_INTERVAL
                : refreshInterval;
        excludedChannelIds = excludedChannelIds == null ? List.of() : List.copyOf(excludedChannelIds);
    }

    public static EpgSource of(String id, String name, String location, Credentials credentials) {
        return of(id, name, location, credentials, DEFAULT_REFRESH_INTERVAL, List.of());
    }

    /**
     * Builds a source from raw configuration. A source without an id is keyed by its location, so a
     * misconfiguration surfaces as a failed source rather than as a null key.
     */
    public static EpgSource of(String id, String name, String location, Credentials credentials,
            Duration refreshInterval) {
        return of(id, name, location, credentials, refreshInterval, List.of());
    }

    /**
     * Builds a source from raw configuration. A source without an id is keyed by its location, so a
     * misconfiguration surfaces as a failed source rather than as a null key.
     */
    public static EpgSource of(String id, String name, String location, Credentials credentials,
            Duration refreshInterval, List<Glob> excludedChannelIds) {
        String key = id != null && !id.isBlank() ? id
                : location != null && !location.isBlank() ? location : "unnamed-source";
        return new EpgSource(new SourceId(key), name, location, credentials, refreshInterval, excludedChannelIds);
    }

    public String displayName() {
        return name == null || name.isBlank() ? id.value() : name;
    }
}
