package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.config.DumpConfiguration;
import com.anti.houseepg.application.config.ReloadConfiguration;
import com.anti.houseepg.application.config.ReorderSources;
import com.anti.houseepg.application.config.ReseedRegistry;
import com.anti.houseepg.application.config.port.ConfigDumpWriter;
import com.anti.houseepg.application.config.port.DeclarationReader;
import com.anti.houseepg.application.config.port.SourceOrderStore;
import com.anti.houseepg.application.guide.BulkAcceptMatches;
import com.anti.houseepg.application.guide.DecideMatch;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.InspectGuide;
import com.anti.houseepg.application.guide.ReviewMatches;
import com.anti.houseepg.application.guide.RefreshGuide;
import com.anti.houseepg.application.guide.ServeGuide;
import com.anti.houseepg.application.guide.port.ChannelAuthorityGateway;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.EpgSourceGateway;
import com.anti.houseepg.application.guide.port.GuideStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
        return new GuideSettings(ConfiguredSources.toSources(properties.enabledSources()),
                Optional.ofNullable(properties.playlistUrl()), properties.retention());
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

    @Bean
    BulkAcceptMatches bulkAcceptMatches(ChannelMatchStore matchStore, RefreshGuide refreshGuide) {
        return new BulkAcceptMatches(matchStore, () -> refreshGuide.refreshDue(Instant.now()));
    }

    @Bean
    ReviewMatches reviewMatches(InspectGuide inspectGuide) {
        return new ReviewMatches(inspectGuide);
    }

    @Bean
    InspectGuide inspectGuide(GuideStore guideStore, ChannelMatchStore matchStore) {
        return new InspectGuide(guideStore, matchStore);
    }

    @Bean
    DecideMatch decideMatch(GuideStore guideStore, ChannelMatchStore matchStore, RefreshGuide refreshGuide) {
        return new DecideMatch(guideStore, matchStore, () -> refreshGuide.refreshDue(Instant.now()));
    }

    /** The YAML file the order is written back into; empty means the order is in-memory only (ADR-0005). */
    @Bean
    SourceOrderStore sourceOrderStore(@Value("${houseepg.config-file:}") String configFile) {
        return new YamlSourceOrderStore(configFile);
    }

    @Bean
    DeclarationReader declarationReader(Environment environment,
            @Value("${houseepg.config-file:}") String configFile) {
        return new YamlDeclarationReader(environment, configFile);
    }

    @Bean
    ConfigDumpWriter configDumpWriter() {
        return new LoggingConfigDumpWriter();
    }

    @Bean
    ReorderSources reorderSources(GuideSettings settings, SourceOrderStore store) {
        return new ReorderSources(settings, store);
    }

    @Bean
    ReloadConfiguration reloadConfiguration(GuideSettings settings, DeclarationReader declarations,
            RefreshGuide refreshGuide) {
        return new ReloadConfiguration(settings, declarations, () -> refreshGuide.refreshDue(Instant.now()));
    }

    @Bean
    DumpConfiguration dumpConfiguration(GuideSettings settings, ConfigDumpWriter writer) {
        return new DumpConfiguration(settings, writer);
    }

    @Bean
    ReseedRegistry reseedRegistry(GuideSettings settings, GuideStore guideStore,
            ChannelAuthorityGateway channelAuthority) {
        return new ReseedRegistry(settings, guideStore, channelAuthority);
    }
}
