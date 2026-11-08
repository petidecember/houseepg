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
    void aPlusMarksADifferentChannelAndTheKeyKeepsThemApart() {
        // RTL+ is the streaming service and M4 Sport+ is not M4 Sport. The samples contain exactly this
        // shape (channel076 against channel076+), and folding the plus away merged them (HOU-46).
        assertThat(ChannelKey.of("channel076.xx")).isNotEqualTo(ChannelKey.of("channel076+.xx"));
        assertThat(ChannelKey.of("Channel 076 (HD).xx")).isNotEqualTo(ChannelKey.of("Channel 076+ (HD).xx"));

        assertThat(ChannelKey.of("RTL+.hu").value()).isEqualTo("rtlplus");
        assertThat(ChannelKey.of("M4 Sport+ (HD).hu").value()).isEqualTo("m4sportplus");
    }

    @Test
    void aPlusJoinsItsOwnSpellingsEvenAcrossSeparators() {
        // The plus must not become a hard boundary: these are one channel spelled two ways.
        assertThat(ChannelKey.of("Canal+ Action.hu")).isEqualTo(ChannelKey.of("Canal+.Action.hu"));
        assertThat(ChannelKey.of("Mozi+ (HD).hu")).isEqualTo(ChannelKey.of("Mozi+.hu"));
        assertThat(ChannelKey.of("Film+ (HD).hu")).isEqualTo(ChannelKey.of("Film+.hu"));
        assertThat(ChannelKey.of("Filmbox+ One.hu")).isEqualTo(ChannelKey.of("Filmbox+ One (HD).hu"));
    }

    @Test
    void aPlusSpelledOutAsAWordIsTheSameChannel() {
        assertThat(ChannelKey.of("RTL+")).isEqualTo(ChannelKey.of("RTL Plus"));
    }

    @Test
    void doesNotFoldAccentedCharacters() {
        // Accent folding is not implemented: non-ASCII letters are dropped, so an ASCII id and an
        // accented display name do not agree. The provider samples contain exactly that divergence,
        // which is why matching also tries the display name. Adding accent folding means revisiting
        // this test and that fixture case together.
        assertThat(ChannelKey.of("DunaTelevizio.xx").value()).isEqualTo("dunatelevizio");
        assertThat(ChannelKey.of("XX - Dúna Televízió").value()).isEqualTo("dnatelevzi");
    }

    @Test
    void emptyAndBlankInputsProduceAnEmptyKey() {
        assertThat(ChannelKey.of(null).isEmpty()).isTrue();
        assertThat(ChannelKey.of("  ").isEmpty()).isTrue();
    }
}
