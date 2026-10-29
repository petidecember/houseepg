package com.anti.houseepg.guide.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The aggregate invariant: a feed can never reference a channel it does not declare. */
class GuideTest {

    private static final Channel BBC_ONE =
            new Channel(new ChannelId("bbc1.uk"), "BBC One", null, null, Channel.Origin.PLAYLIST);

    @Test
    void rejectsAProgrammeForAnUndeclaredChannel() {
        Programme orphan = programme("itv1.uk");

        assertThatThrownBy(() -> new Guide(List.of(BBC_ONE), List.of(orphan)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itv1.uk");
    }

    @Test
    void acceptsAProgrammeForADeclaredChannel() {
        Guide guide = new Guide(List.of(BBC_ONE), List.of(programme("bbc1.uk")));

        assertThat(guide.programmes()).hasSize(1);
        assertThat(Guide.empty().channels()).isEmpty();
    }

    private static Programme programme(String channelId) {
        return new Programme(new ChannelId(channelId),
                new Slot(OffsetDateTime.parse("2026-10-29T20:00:00Z"), OffsetDateTime.parse("2026-10-29T20:30:00Z")),
                new SourceId("a"), "EastEnders", null, null, null, null);
    }
}
