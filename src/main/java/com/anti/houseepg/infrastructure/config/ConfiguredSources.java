package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import java.util.List;

/**
 * One translation from binding properties to the application's own source type, so a boot and a reload
 * can never disagree about what a declared source means.
 */
final class ConfiguredSources {

    private ConfiguredSources() {
    }

    static List<EpgSource> toSources(List<HouseEpgProperties.SourceProperties> sources) {
        return sources.stream()
                .map(source -> EpgSource.of(source.id(), source.name(), source.url(), credentials(source),
                        source.refreshIntervalOr()))
                .toList();
    }

    static Credentials credentials(HouseEpgProperties.SourceProperties source) {
        boolean hasBasicAuth = source.username() != null && !source.username().isBlank();
        boolean hasHeader = source.headerName() != null && !source.headerName().isBlank();
        if (!hasBasicAuth && !hasHeader) {
            return Credentials.none();
        }
        return new Credentials(source.username(), source.password(), source.headerName(), source.headerValue());
    }
}
