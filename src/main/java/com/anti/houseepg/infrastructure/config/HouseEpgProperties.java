package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.guide.port.EpgSource;
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
        List<SourceProperties> sources,
        Duration retention,
        Duration timeout) {

    public HouseEpgProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        retention = retention == null ? Duration.ofDays(7) : retention;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
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
