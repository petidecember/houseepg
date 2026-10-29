package com.anti.houseepg.merge;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.MergedProgramme;
import com.anti.houseepg.domain.Programme;
import com.anti.houseepg.domain.SourceChannelKey;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The slot rule from ADR-0001: any overlap is a conflict, the highest-priority programme wins
 * wholesale, the loser is dropped and never clipped, and uncovered stretches are filled by the next
 * source down.
 */
class MergeEngineTest {

    private static final Map<SourceChannelKey, String> MATCHED = Map.of(
            new SourceChannelKey("a", "BBC1"), "bbc1.uk",
            new SourceChannelKey("b", "BBC1HD"), "bbc1.uk",
            new SourceChannelKey("b", "Dave"), "Dave");

    @Test
    void dropsAnOverlappingLowerPriorityProgrammeWholesale() {
        List<Programme> programmes = List.of(
                programme("a", "BBC1", "2026-10-29T20:00:00Z", "2026-10-29T20:30:00Z", "EastEnders", "Full synopsis"),
                // Overlaps the winner by five minutes: dropped whole, not clipped.
                programme("b", "BBC1HD", "2026-10-29T20:00:00Z", "2026-10-29T20:35:00Z", "EastEnders", "Short"),
                // Straddles the winner: also dropped whole, leaving its uncovered head empty.
                programme("b", "BBC1HD", "2026-10-29T19:45:00Z", "2026-10-29T21:00:00Z", "Heat", "Film"));

        List<MergedProgramme> merged = MergeEngine.merge(List.of("a", "b"), programmes, MATCHED);

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().title()).isEqualTo("EastEnders");
        assertThat(merged.getFirst().winningSourceId()).isEqualTo("a");
        // The hole between 19:45 and 20:00 is not filled with anything.
        assertThat(merged.getFirst().start()).isEqualTo(OffsetDateTime.parse("2026-10-29T20:00:00Z"));
    }

    @Test
    void fillsUncoveredStretchesFromTheNextSourceDown() {
        List<Programme> programmes = List.of(
                programme("a", "BBC1", "2026-10-29T20:00:00Z", "2026-10-29T20:30:00Z", "EastEnders", null),
                programme("b", "BBC1HD", "2026-10-29T21:00:00Z", "2026-10-29T22:00:00Z", "News", null));

        List<MergedProgramme> merged = MergeEngine.merge(List.of("a", "b"), programmes, MATCHED);

        assertThat(merged).extracting(MergedProgramme::title).containsExactly("EastEnders", "News");
        assertThat(merged).extracting(MergedProgramme::winningSourceId).containsExactly("a", "b");
    }

    @Test
    void neverMixesFieldsWithinOneProgramme() {
        List<Programme> programmes = List.of(
                programme("a", "BBC1", "2026-10-29T20:00:00Z", "2026-10-29T20:30:00Z", "EastEnders", "Full synopsis"),
                programme("b", "BBC1HD", "2026-10-29T20:00:00Z", "2026-10-29T20:30:00Z", "EastEnders", "Short desc"));

        List<MergedProgramme> merged = MergeEngine.merge(List.of("a", "b"), programmes, MATCHED);

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().description()).isEqualTo("Full synopsis");
    }

    @Test
    void priorityOrderDecidesWhichSourceWins() {
        List<Programme> programmes = List.of(
                programme("a", "BBC1", "2026-10-29T20:00:00Z", "2026-10-29T20:30:00Z", "From A", null),
                programme("b", "BBC1HD", "2026-10-29T20:00:00Z", "2026-10-29T20:30:00Z", "From B", null));

        assertThat(MergeEngine.merge(List.of("a", "b"), programmes, MATCHED))
                .extracting(MergedProgramme::title).containsExactly("From A");
        assertThat(MergeEngine.merge(List.of("b", "a"), programmes, MATCHED))
                .extracting(MergedProgramme::title).containsExactly("From B");
    }

    @Test
    void keepsPassThroughChannelsUnderTheirOwnId() {
        List<Programme> programmes = List.of(
                programme("b", "Dave", "2026-10-29T22:00:00Z", "2026-10-29T23:00:00Z", "Panel Show", null));

        List<MergedProgramme> merged = MergeEngine.merge(List.of("a", "b"), programmes, MATCHED);

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().canonicalChannelId()).isEqualTo("Dave");
    }

    @Test
    void ignoresProgrammesWhoseChannelWasNeverMatched() {
        List<Programme> programmes = List.of(
                programme("b", "Unknown", "2026-10-29T22:00:00Z", "2026-10-29T23:00:00Z", "Orphan", null));

        assertThat(MergeEngine.merge(List.of("a", "b"), programmes, MATCHED)).isEmpty();
    }

    private static Programme programme(String sourceId, String channelId, String start, String stop, String title,
            String description) {
        return new Programme(sourceId, channelId, OffsetDateTime.parse(start), OffsetDateTime.parse(stop), title,
                description, null, null, null);
    }
}
