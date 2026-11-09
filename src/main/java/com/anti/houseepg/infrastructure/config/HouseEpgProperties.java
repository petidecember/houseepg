package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.policy.Glob;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root configuration. The walking skeleton is YAML-configured: the playlist (channel authority) and
 * the XMLTV sources in priority order, highest priority first.
 */
@ConfigurationProperties("houseepg")
public record HouseEpgProperties(
        String playlistUrl,
        List<String> playlistGroups,
        List<SourceProperties> sources,
        Duration retention,
        Duration timeout) {

    public HouseEpgProperties {
        playlistGroups = playlistGroups == null ? List.of() : List.copyOf(playlistGroups);
        sources = sources == null ? List.of() : List.copyOf(sources);
        retention = retention == null ? Duration.ofDays(7) : retention;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    /**
     * The {@code group-title} groups the playlist may be read from (HOU-45). Empty means every group, so an
     * existing configuration keeps behaving exactly as it did. A blank pattern is a boot failure rather
     * than a silent no-match, the same rule the per-source exclusion list follows.
     */
    public List<Glob> playlistGroupsOr() {
        return Glob.allOf(playlistGroups);
    }

    /** Sources that take part in the merge, in priority order. */
    public List<SourceProperties> enabledSources() {
        return sources.stream().filter(SourceProperties::isEnabled).toList();
    }

    public record SourceProperties(
            String id,
            String name,
            String url,
            String username,
            String password,
            String headerName,
            String headerValue,
            Boolean enabled,
            Duration refreshInterval,
            List<String> excludedChannelIds) {

        /** Design §8: 8 h unless configured otherwise. */
        public Duration refreshIntervalOr() {
            return refreshInterval == null ? EpgSource.DEFAULT_REFRESH_INTERVAL : refreshInterval;
        }

        public boolean isEnabled() {
            return enabled == null || enabled;
        }
        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }

        /**
         * Glob patterns for source channel ids this source declares and the operator does not want
         * (design §6.2, HOU-33). Empty by default: nothing is excluded unless it is asked for.
         */
        public List<String> excludedChannelIdsOr() {
            return excludedChannelIds == null ? List.of() : List.copyOf(excludedChannelIds);
        }
    }
}
