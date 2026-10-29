package com.anti.houseepg.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.application.config.port.DeclarationReader;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A reload rebinds the source set and re-merges, while leaving ids to the explicit re-seed (ADR-0005). */
class ReloadConfigurationTest {

    @Test
    void aReloadRebindsTheSourceSetAndReMerges() {
        GuideSettings settings = settings("a", "b");
        AtomicInteger merges = new AtomicInteger();
        ReloadConfiguration reload = new ReloadConfiguration(settings,
                () -> List.of(source("b"), source("c")), merges::incrementAndGet);

        ReloadConfiguration.Result result = reload.reload();

        assertThat(ids(settings.sources())).containsExactly("b", "c");
        assertThat(result.added()).containsExactly(new SourceId("c"));
        assertThat(result.removed()).containsExactly(new SourceId("a"));
        assertThat(result.orderChanged()).isTrue();
        assertThat(result.order()).containsExactly(new SourceId("b"), new SourceId("c"));
        assertThat(merges).hasValue(1);
    }

    @Test
    void aSourceAddedAtTheFrontOfTheDeclarationGoesFirst() {
        GuideSettings settings = settings("a", "b");
        ReloadConfiguration reload = new ReloadConfiguration(settings,
                () -> List.of(source("n"), source("a"), source("b")), () -> {
                });

        reload.reload();

        assertThat(ids(settings.sources())).containsExactly("n", "a", "b");
    }

    @Test
    void aReloadOfTheSameConfigurationReportsNoChange() {
        GuideSettings settings = settings("a", "b");
        ReloadConfiguration reload = new ReloadConfiguration(settings,
                () -> List.of(source("a"), source("b")), () -> {
                });

        ReloadConfiguration.Result result = reload.reload();

        assertThat(result.added()).isEmpty();
        assertThat(result.removed()).isEmpty();
        assertThat(result.orderChanged()).isFalse();
    }

    @Test
    void anUnreadableConfigurationIsReportedAndNothingIsHalfApplied() {
        GuideSettings settings = settings("a", "b");
        AtomicInteger merges = new AtomicInteger();
        DeclarationReader unreadable = () -> {
            throw new IllegalStateException("the configuration file is not readable");
        };
        ReloadConfiguration reload = new ReloadConfiguration(settings, unreadable, merges::incrementAndGet);

        assertThatThrownBy(reload::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable");
        assertThat(ids(settings.sources())).containsExactly("a", "b");
        assertThat(merges).hasValue(0);
    }

    private static List<String> ids(List<EpgSource> sources) {
        return sources.stream().map(EpgSource::id).map(SourceId::value).toList();
    }

    private static EpgSource source(String id) {
        return EpgSource.of(id, id, "http://" + id, Credentials.none());
    }

    private static GuideSettings settings(String... ids) {
        List<EpgSource> sources = java.util.Arrays.stream(ids).map(ReloadConfigurationTest::source).toList();
        return new GuideSettings(sources, Optional.empty());
    }
}
