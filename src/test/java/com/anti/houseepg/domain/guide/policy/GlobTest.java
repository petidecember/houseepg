package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GlobTest {

    @Test
    void aPatternWithoutAWildcardMatchesOnlyItself() {
        Glob glob = Glob.of("channel_id");

        assertThat(glob.matches("channel_id")).isTrue();
        assertThat(glob.matches("channel_ids")).isFalse();
        assertThat(glob.matches("the_channel_id")).isFalse();
    }

    @Test
    void aStarMatchesAnyRunIncludingTheSeparators() {
        // The provider's placeholder family is what the operator wants gone, and it crosses the country
        // suffix, so the star must not stop at a dot.
        Glob glob = Glob.of("stream_*");

        assertThat(glob.matches("stream_display_name")).isTrue();
        assertThat(glob.matches("stream_icon")).isTrue();
        assertThat(glob.matches("stream_")).isTrue();
        assertThat(glob.matches("mystream_icon")).isFalse();
        assertThat(glob.matches("Some.HD.hu")).isFalse();
    }

    @Test
    void aStarCrossesTheCountrySuffixSoAFamilyCanBeCaught() {
        assertThat(Glob.of("*+*").matches("M4 Sport+ (HD).hu")).isTrue();
        assertThat(Glob.of("Channel 0*").matches("Channel 047+ (HD).xx")).isTrue();
    }

    @Test
    void aPatternAnchorsAtBothEnds() {
        // A trailing star is what makes a prefix match; without it the value must be exactly the pattern.
        assertThat(Glob.of("Channel 076+ (HD).xx").matches("Channel 076+ (HD).xx")).isTrue();
        assertThat(Glob.of("Channel 076+ (HD).xx").matches("Channel 076+ (HD).xx.extra")).isFalse();
        assertThat(Glob.of("*.hu").matches("hirtv.hu")).isTrue();
        assertThat(Glob.of(".hu").matches("hirtv.hu")).isFalse();
    }

    @Test
    void aQuestionMarkMatchesExactlyOneCharacter() {
        Glob glob = Glob.of("Channel 07?.xx");

        assertThat(glob.matches("Channel 076.xx")).isTrue();
        assertThat(glob.matches("Channel 07.xx")).isFalse();
        assertThat(glob.matches("Channel 0765.xx")).isFalse();
    }

    @Test
    void regexMetacharactersInAPatternAreLiteral() {
        // `+`, `.`, `(` and `$` are ordinary characters in a channel id; only * and ? are wildcards.
        assertThat(Glob.of("M4 Sport+ (HD).hu").matches("M4 Sport+ (HD).hu")).isTrue();
        assertThat(Glob.of("M4 SportX (HD).hu").matches("M4 Sport+ (HD).hu")).isFalse();
        assertThat(Glob.of("a$b").matches("a$b")).isTrue();
        assertThat(Glob.of("a$").matches("a")).isFalse();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(Glob.of("rtl+.hu").matches("RTL+.hu")).isTrue();
        assertThat(Glob.of("CHANNEL_ID").matches("channel_id")).isTrue();
    }

    @Test
    void aBlankPatternIsAConfigurationError() {
        assertThatThrownBy(() -> Glob.of("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
        assertThatThrownBy(() -> Glob.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aListOfPatternsCompilesInOrder() {
        List<Glob> globs = Glob.allOf(List.of("channel_id", "stream_*"));

        assertThat(globs).extracting(Glob::pattern).containsExactly("channel_id", "stream_*");
        assertThat(globs.get(1).matches("stream_display_name")).isTrue();
        assertThat(Glob.allOf(null)).isEmpty();
    }
}
