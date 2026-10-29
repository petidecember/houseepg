package com.anti.houseepg.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Cases taken from the two real feeds in test_artifacts, where the same real channel is spelled
 * differently per provider (ADR-0003).
 */
class ChannelKeyTest {

    @Test
    void foldsCountryPrefixSuffixAndQualityTags() {
        assertThat(ChannelKey.of("AMC.hu").value()).isEqualTo("amc");
        assertThat(ChannelKey.of("AMC (HD).hu").value()).isEqualTo("amc");
        assertThat(ChannelKey.of("HU - AMC").value()).isEqualTo("amc");

        assertThat(ChannelKey.of("ApostolTV.hu").value()).isEqualTo("apostoltv");
        assertThat(ChannelKey.of("Apostol TV.hu").value()).isEqualTo("apostoltv");
        assertThat(ChannelKey.of("HU - Apostol TV").value()).isEqualTo("apostoltv");

        assertThat(ChannelKey.of("ATV (HD).hu").value()).isEqualTo("atv");
        assertThat(ChannelKey.of("HU - ATV").value()).isEqualTo("atv");
    }

    @Test
    void foldsWordSpacingDifferences() {
        assertThat(ChannelKey.of("AnimalPlanet.hu")).isEqualTo(ChannelKey.of("HU - Animal Planet"));
        assertThat(ChannelKey.of("Animal Planet (HD).hu").value()).isEqualTo("animalplanet");
    }

    @Test
    void keepsAChannelNameWhoseSuffixIsPartOfTheName() {
        // "9.Tv" must not lose "Tv" as if it were a country suffix.
        assertThat(ChannelKey.of("9Tv.hu")).isEqualTo(ChannelKey.of("HU - 9.Tv"));
    }

    @Test
    void emptyAndBlankInputsProduceAnEmptyKey() {
        assertThat(ChannelKey.of(null).isEmpty()).isTrue();
        assertThat(ChannelKey.of("  ").isEmpty()).isTrue();
    }
}
