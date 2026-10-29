package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.RefreshGuide;
import com.anti.houseepg.application.guide.ServeGuide;
import com.anti.houseepg.application.guide.port.ChannelAuthorityGateway;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.EpgSourceGateway;
import com.anti.houseepg.application.guide.port.GuideStore;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the framework-free application layer: configuration is translated into the application's own
 * settings type here, so {@code application.guide} never depends on Spring or on binding details.
 */
@Configuration
@EnableScheduling
public class HouseEpgConfiguration {

    @Bean
    GuideSettings guideSettings(HouseEpgProperties properties) {
        List<EpgSource> sources = properties.enabledSources().stream()
                .map(source -> EpgSource.of(source.id(), source.name(), source.url(), credentials(source),
                        source.refreshIntervalOr()))
                .toList();
        return new GuideSettings(sources, Optional.ofNullable(properties.playlistUrl()), properties.retention());
    }

    @Bean
    RefreshGuide refreshGuide(GuideSettings settings, EpgSourceGateway epgSources,
            ChannelAuthorityGateway channelAuthority, ChannelMatchStore matchStore, GuideStore guideStore) {
        return new RefreshGuide(settings, epgSources, channelAuthority, matchStore, guideStore);
    }

    @Bean
    ServeGuide serveGuide(RefreshGuide refreshGuide, GuideStore guideStore, ChannelMatchStore matchStore) {
        return new ServeGuide(refreshGuide, guideStore, matchStore);
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
