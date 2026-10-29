package com.anti.houseepg.domain.guide.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The aggregate invariant: a feed can never reference a channel it does not declare. */
class GuideTest {

    private static final Channel CHANNEL_ONE =
            new Channel(new ChannelId("ch1.xx"), "Channel One", null, null, Channel.Origin.PLAYLIST);

    @Test
    void rejectsAProgrammeForAnUndeclaredChannel() {
        Programme orphan = programme("ch2.xx");

        assertThatThrownBy(() -> new Guide(List.of(CHANNEL_ONE), List.of(orphan)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ch2.xx");
    }

    @Test
    void acceptsAProgrammeForADeclaredChannel() {
        Guide guide = new Guide(List.of(CHANNEL_ONE), List.of(programme("ch1.xx")));

        assertThat(guide.programmes()).hasSize(1);
        assertThat(Guide.empty().channels()).isEmpty();
    }

    private static Programme programme(String channelId) {
        return new Programme(new ChannelId(channelId),
                new Slot(OffsetDateTime.parse("2026-10-29T20:00:00Z"), OffsetDateTime.parse("2026-10-29T20:30:00Z")),
                new SourceId("a"), "Show Alpha", null, null, null, null);
    }
}
