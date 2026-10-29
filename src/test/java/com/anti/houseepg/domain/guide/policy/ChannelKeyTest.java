package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Two providers spell the same channel differently: a country prefix, a country suffix, a quality tag
 * and word spacing all have to fold away before matching can work (ADR-0003).
 */
class ChannelKeyTest {

    @Test
    void foldsCountryPrefixSuffixAndQualityTags() {
        assertThat(ChannelKey.of("alpha.xx").value()).isEqualTo("alpha");
        assertThat(ChannelKey.of("alpha (HD).xx").value()).isEqualTo("alpha");
        assertThat(ChannelKey.of("XX - Alpha").value()).isEqualTo("alpha");

        assertThat(ChannelKey.of("BravoTV.xx").value()).isEqualTo("bravotv");
        assertThat(ChannelKey.of("Bravo TV.xx").value()).isEqualTo("bravotv");
        assertThat(ChannelKey.of("XX - Bravo TV").value()).isEqualTo("bravotv");

        assertThat(ChannelKey.of("Charlie (HD).xx").value()).isEqualTo("charlie");
        assertThat(ChannelKey.of("XX - Charlie").value()).isEqualTo("charlie");
    }

    @Test
    void foldsWordSpacingDifferences() {
        assertThat(ChannelKey.of("DeltaPlanet.xx")).isEqualTo(ChannelKey.of("XX - Delta Planet"));
        assertThat(ChannelKey.of("Delta Planet (HD).xx").value()).isEqualTo("deltaplanet");
    }

    @Test
    void keepsAChannelNameWhoseSuffixIsPartOfTheName() {
        // "7.Tv" must not lose "Tv" as if it were a country suffix.
        assertThat(ChannelKey.of("7Tv.xx")).isEqualTo(ChannelKey.of("XX - 7.Tv"));
    }

    @Test
    void distinctChannelsThatOnlyDifferByAPlusStayDistinctByTheirId() {
        // The samples really do contain pairs like this; the key collides, the raw id must not.
        assertThat(ChannelKey.of("channel076.xx")).isEqualTo(ChannelKey.of("channel076+.xx"));
        assertThat(ChannelKey.of("Channel 076 (HD).xx")).isEqualTo(ChannelKey.of("Channel 076+ (HD).xx"));
    }

    @Test
    void emptyAndBlankInputsProduceAnEmptyKey() {
        assertThat(ChannelKey.of(null).isEmpty()).isTrue();
        assertThat(ChannelKey.of("  ").isEmpty()).isTrue();
    }
}
