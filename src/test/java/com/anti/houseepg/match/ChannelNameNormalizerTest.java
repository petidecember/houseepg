package com.anti.houseepg.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Cases taken from the two real feeds in test_artifacts: the same real channel is spelled
 * differently per provider. See ADR-0003.
 */
class ChannelNameNormalizerTest {

    @Test
    void foldsCountryPrefixSuffixAndQualityTags() {
        assertThat(ChannelNameNormalizer.normalize("AMC.hu")).isEqualTo("amc");
        assertThat(ChannelNameNormalizer.normalize("AMC (HD).hu")).isEqualTo("amc");
        assertThat(ChannelNameNormalizer.normalize("HU - AMC")).isEqualTo("amc");

        assertThat(ChannelNameNormalizer.normalize("ApostolTV.hu")).isEqualTo("apostoltv");
        assertThat(ChannelNameNormalizer.normalize("Apostol TV.hu")).isEqualTo("apostoltv");
        assertThat(ChannelNameNormalizer.normalize("HU - Apostol TV")).isEqualTo("apostoltv");

        assertThat(ChannelNameNormalizer.normalize("ATV (HD).hu")).isEqualTo("atv");
        assertThat(ChannelNameNormalizer.normalize("HU - ATV")).isEqualTo("atv");
    }

    @Test
    void foldsWordSpacingDifferences() {
        assertThat(ChannelNameNormalizer.normalize("AnimalPlanet.hu"))
                .isEqualTo(ChannelNameNormalizer.normalize("HU - Animal Planet"));
        assertThat(ChannelNameNormalizer.normalize("Animal Planet (HD).hu")).isEqualTo("animalplanet");
    }

    @Test
    void keepsChannelNamesWhoseSuffixIsPartOfTheName() {
        // "9.Tv" must not lose "Tv" as if it were a country suffix: the remainder would have no letters.
        assertThat(ChannelNameNormalizer.normalize("9Tv.hu"))
                .isEqualTo(ChannelNameNormalizer.normalize("HU - 9.Tv"));
    }

    @Test
    void emptyAndBlankInputsNormaliseToAnEmptyKey() {
        assertThat(ChannelNameNormalizer.normalize(null)).isEmpty();
        assertThat(ChannelNameNormalizer.normalize("  ")).isEmpty();
    }
}
