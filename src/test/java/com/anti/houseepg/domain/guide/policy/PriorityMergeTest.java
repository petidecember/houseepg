package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The slot rule from ADR-0001: any overlap is a conflict, the highest-priority programme wins
 * wholesale, the loser is dropped and never clipped, and uncovered stretches are filled by the next
 * source down.
 */
class PriorityMergeTest {

    private static final List<Channel> REGISTRY = List.of(
            new Channel(new ChannelId("ch1.xx"), "Channel One", null, null, Channel.Origin.PLAYLIST),
            new Channel(new ChannelId("ch2.xx"), "Channel Two", null, null, Channel.Origin.PLAYLIST));

    private static final SourceId A = new SourceId("a");
    private static final SourceId B = new SourceId("b");

    @Test
    void dropsAnOverlappingLowerPriorityProgrammeWholesale() {
        Guide guide = merge(List.of(A, B),
                source(A, "CH1",
                        programme("CH1", "20:00", "20:30", "Show Alpha", "Full synopsis")),
                source(B, "CH1 (HD)",
                        // Overlaps the winner by five minutes: dropped whole, not clipped.
                        programme("CH1 (HD)", "20:00", "20:35", "Show Alpha", "Short synopsis"),
                        // Straddles the winner: also dropped whole, leaving its uncovered head empty.
                        programme("CH1 (HD)", "19:45", "21:00", "Film Gamma", "Film")));

        assertThat(guide.programmes()).hasSize(1);
        Programme winner = guide.programmes().getFirst();
        assertThat(winner.title()).isEqualTo("Show Alpha");
        assertThat(winner.winningSource()).isEqualTo(A);
        assertThat(winner.description()).isEqualTo("Full synopsis");
        // The hole between 19:45 and 20:00 is not filled with anything.
        assertThat(winner.slot().start()).isEqualTo(at("20:00"));
    }

    @Test
    void fillsUncoveredStretchesFromTheNextSourceDown() {
        Guide guide = merge(List.of(A, B),
                source(A, "CH1", programme("CH1", "20:00", "20:30", "Show Alpha", null)),
                source(B, "CH1 (HD)", programme("CH1 (HD)", "21:00", "22:00", "Show Delta", null)));

        assertThat(guide.programmes()).extracting(Programme::title).containsExactly("Show Alpha", "Show Delta");
        assertThat(guide.programmes()).extracting(Programme::winningSource).containsExactly(A, B);
    }

    @Test
    void neverMixesFieldsWithinOneProgramme() {
        Guide guide = merge(List.of(A, B),
                source(A, "CH1", programme("CH1", "20:00", "20:30", "Show Alpha", "Full synopsis")),
                source(B, "CH1 (HD)", programme("CH1 (HD)", "20:00", "20:30", "Show Alpha", "Short synopsis")));

        assertThat(guide.programmes()).singleElement()
                .satisfies(programme -> assertThat(programme.description()).isEqualTo("Full synopsis"));
    }

    @Test
    void priorityOrderDecidesWhichSourceWins() {
        List<SourceEpg> sources = List.of(
                source(A, "CH1", programme("CH1", "20:00", "20:30", "From A", null)),
                source(B, "CH1 (HD)", programme("CH1 (HD)", "20:00", "20:30", "From B", null)));

        assertThat(mergeFrom(List.of(A, B), sources).programmes())
                .extracting(Programme::title).containsExactly("From A");
        assertThat(mergeFrom(List.of(B, A), sources).programmes())
                .extracting(Programme::title).containsExactly("From B");
    }

    @Test
    void keepsAnUnmatchedChannelAsAPassThroughUnderItsOwnId() {
        Guide guide = merge(List.of(A, B),
                source(B, "ch3", programme("ch3", "22:00", "23:00", "Show Epsilon", null)));

        assertThat(guide.channels()).extracting(channel -> channel.id().value()).contains("ch3");
        assertThat(guide.programmes()).singleElement()
                .satisfies(programme -> assertThat(programme.channelId().value()).isEqualTo("ch3"));
    }

    @Test
    void dropsProgrammesForChannelsTheSourceNeverDeclared() {
        // "ghost" is not in the source's channel list, so nothing can be matched for it.
        Guide guide = merge(List.of(B), source(B, "ch3", programme("ghost", "22:00", "23:00", "Orphan", null)));

        assertThat(guide.programmes()).isEmpty();
    }

    private static Guide merge(List<SourceId> priority, SourceEpg... sources) {
        return mergeFrom(priority, List.of(sources));
    }

    private static Guide mergeFrom(List<SourceId> priority, List<SourceEpg> sources) {
        ChannelMatching.Match match = ChannelMatching.match(REGISTRY, sources);
        return PriorityMerge.merge(match.channels(), sources, match.canonicalIds(), priority);
    }

    private static SourceEpg source(SourceId id, String channelId, SourceProgramme... programmes) {
        return new SourceEpg(id, List.of(new SourceChannel(channelId, displayName(channelId), null)),
                List.of(programmes));
    }

    private static String displayName(String channelId) {
        return switch (channelId) {
            case "CH1", "CH1 (HD)" -> "Channel One";
            case "CH2HD" -> "Channel Two";
            case "ch3" -> "Channel Three";
            default -> channelId;
        };
    }

    private static SourceProgramme programme(String channelId, String start, String stop, String title,
            String description) {
        return new SourceProgramme(channelId, new Slot(at(start), at(stop)), title, description, null, null, null);
    }

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse("2026-10-29T" + time + ":00Z");
    }
}
