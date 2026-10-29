package com.anti.houseepg.config;

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
        Duration cacheTtl,
        Duration timeout) {

    public HouseEpgProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        cacheTtl = cacheTtl == null ? Duration.ofHours(1) : cacheTtl;
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
            Boolean enabled) {

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }
    }
}
