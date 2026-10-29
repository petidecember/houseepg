package com.anti.houseepg.config;

import com.anti.houseepg.guide.app.GuideSettings;
import com.anti.houseepg.guide.app.RefreshGuide;
import com.anti.houseepg.guide.app.ServeGuide;
import com.anti.houseepg.guide.app.port.ChannelAuthorityGateway;
import com.anti.houseepg.guide.app.port.Credentials;
import com.anti.houseepg.guide.app.port.EpgSource;
import com.anti.houseepg.guide.app.port.EpgSourceGateway;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free application layer: configuration is translated into the application's own
 * settings type here, so {@code guide.app} never depends on Spring or on binding details.
 */
@Configuration
public class HouseEpgConfiguration {

    @Bean
    GuideSettings guideSettings(HouseEpgProperties properties) {
        List<EpgSource> sources = properties.enabledSources().stream()
                .map(source -> EpgSource.of(source.id(), source.name(), source.url(), credentials(source)))
                .toList();
        return new GuideSettings(sources, Optional.ofNullable(properties.playlistUrl()));
    }

    @Bean
    RefreshGuide refreshGuide(GuideSettings settings, EpgSourceGateway epgSources,
            ChannelAuthorityGateway channelAuthority) {
        return new RefreshGuide(settings, epgSources, channelAuthority);
    }

    @Bean
    ServeGuide serveGuide(RefreshGuide refreshGuide, HouseEpgProperties properties) {
        return new ServeGuide(refreshGuide, properties.cacheTtl());
    }

    private static Credentials credentials(HouseEpgProperties.SourceProperties source) {
        boolean hasBasicAuth = source.username() != null && !source.username().isBlank();
        boolean hasHeader = source.headerName() != null && !source.headerName().isBlank();
        if (!hasBasicAuth && !hasHeader) {
            return Credentials.none();
        }
        return new Credentials(source.username(), source.password(), source.headerName(), source.headerValue());
    }
}
