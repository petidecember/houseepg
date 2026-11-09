package com.anti.houseepg.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.application.guide.port.EpgSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one translation from YAML to the application's own source type. The exclusion list is compiled
 * here, so this is also where a broken pattern has to fail (HOU-33): a source that binds cleanly but
 * silently excludes nothing is the failure mode this guards against.
 */
class ConfiguredSourcesTest {

    @Test
    void compilesTheExclusionListOfEachSource() {
        List<EpgSource> sources = ConfiguredSources.toSources(List.of(
                source("a", "Source A", List.of("channel_id", "stream_*")),
                source("b", "Source B", List.of())));

        assertThat(sources.get(0).excludedChannelIds()).extracting(glob -> glob.pattern())
                .containsExactly("channel_id", "stream_*");
        assertThat(sources.get(0).excludedChannelIds().get(1).matches("stream_display_name")).isTrue();
        assertThat(sources.get(1).excludedChannelIds()).isEmpty();
    }

    @Test
    void aSourceWithoutAnExclusionListExcludesNothing() {
        List<EpgSource> sources = ConfiguredSources.toSources(List.of(source("a", "Source A", null)));

        assertThat(sources.get(0).excludedChannelIds()).isEmpty();
    }

    @Test
    void aBlankExclusionPatternFailsInsteadOfQuietlyMatchingNothing() {
        // A blank entry is almost always a YAML slip (`- ` with nothing after it). Failing the boot is
        // the only outcome that cannot be mistaken for "the exclusion did not apply".
        assertThatThrownBy(() -> ConfiguredSources.toSources(List.of(
                source("a", "Source A", List.of("channel_id", "  ")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    private static HouseEpgProperties.SourceProperties source(String id, String name,
            List<String> excludedChannelIds) {
        return new HouseEpgProperties.SourceProperties(id, name, "http://" + id + "/epg.xml", null, null,
                null, null, true, Duration.ofHours(8), excludedChannelIds);
    }
}
