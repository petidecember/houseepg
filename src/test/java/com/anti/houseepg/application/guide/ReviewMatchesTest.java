package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The review list: which rows it shows by default, and what the fuzzy switch changes. The default is
 * the point — a curated row or an exact match is not worth a human's attention.
 */
class ReviewMatchesTest {

    private static final SourceId A = new SourceId("a");

    private final InMemoryGuideStore guideStore = new InMemoryGuideStore();
    private final InMemoryChannelMatchStore matchStore = new InMemoryChannelMatchStore();
    private final ReviewMatches review = new ReviewMatches(new InspectGuide(guideStore, matchStore));

    @BeforeEach
    void setUp() {
        guideStore.saveRegistry(List.of(
                channel("amc.xx", "AMC"),
                channel("bravo.xx", "Bravo"),
                channel("sky-sports-f1.xx", "Sky Sports F1")));
        guideStore.saveNormalized(new SourceEpg(A, List.of(
                sourceChannel("amc.xx", "AMC"),
                sourceChannel("bravo.xx", "Bravo"),
                sourceChannel("sky.xx", "Sky Sport F1"),
                sourceChannel("zzz.xx", "Zzz"),
                sourceChannel("curated.xx", "Curated")), List.of()), Map.of());
        matchStore.saveAuto(List.of(
                ChannelMatch.auto(ref("amc.xx"), new ChannelId("amc.xx"), MatchConfidence.EXACT_ID),
                ChannelMatch.auto(ref("bravo.xx"), new ChannelId("bravo.xx"), MatchConfidence.NORMALIZED_NAME),
                ChannelMatch.unmatched(ref("sky.xx"), new ChannelId("sky.xx")),
                ChannelMatch.unmatched(ref("zzz.xx"), new ChannelId("zzz.xx"))));
        matchStore.decide(ChannelMatch.confirmed(ref("curated.xx"), new ChannelId("amc.xx"),
                MatchConfidence.EXACT_ID));
    }

    @Test
    void theDefaultListHidesCuratedRows() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.NEEDS_DECISION)))
                .doesNotContain("curated.xx");
    }

    @Test
    void theDefaultListHidesExactIdAndNameMatches() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.NEEDS_DECISION)))
                .doesNotContain("amc.xx");
    }

    @Test
    void theDefaultListKeepsRowsWithACandidate() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.NEEDS_DECISION)))
                .containsExactly("bravo.xx");
    }

    @Test
    void theDefaultListDropsRowsTheMatcherCouldSuggestNothingFor() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.NEEDS_DECISION)))
                .doesNotContain("sky.xx", "zzz.xx");
    }

    @Test
    void withFuzzyARowWhoseOnlyValueIsASuggestionIsWorthDeciding() {
        assertThat(sources(review.review(true, ReviewMatches.Filter.NEEDS_DECISION)))
                .containsExactlyInAnyOrder("bravo.xx", "sky.xx");
    }

    @Test
    void theAllFilterShowsEveryRow() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.ALL)))
                .containsExactlyInAnyOrder("amc.xx", "bravo.xx", "sky.xx", "zzz.xx", "curated.xx");
    }

    @Test
    void theCuratedFilterShowsOnlyCuratedRows() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.CURATED)))
                .containsExactly("curated.xx");
    }

    @Test
    void theUnmatchedFilterShowsOnlyUnmatchedRows() {
        assertThat(sources(review.review(false, ReviewMatches.Filter.UNMATCHED)))
                .containsExactlyInAnyOrder("sky.xx", "zzz.xx");
    }

    @Test
    void withoutFuzzyTheCandidatesAreTheLadderHitsOnly() {
        ReviewMatches.Review result = review.review(false, ReviewMatches.Filter.ALL);

        assertThat(row(result, "bravo.xx").candidates())
                .extracting(candidate -> candidate.channel().id().value())
                .containsExactly("bravo.xx");
        assertThat(row(result, "sky.xx").candidates()).isEmpty();
    }

    @Test
    void withFuzzyAnUnmatchedRowCarriesSuggestions() {
        ReviewMatches.Review result = review.review(true, ReviewMatches.Filter.ALL);

        assertThat(row(result, "sky.xx").candidates())
                .extracting(candidate -> candidate.channel().id().value())
                .contains("sky-sports-f1.xx");
        assertThat(row(result, "sky.xx").candidates()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void theCounterCountsWhatFuzzyWouldResolveAndWhatItWouldGuess() {
        ReviewMatches.Review result = review.review(true, ReviewMatches.Filter.NEEDS_DECISION);

        // sky.xx has one clear suggestion; zzz.xx has none; bravo.xx is a ladder hit, not a fuzzy one.
        assertThat(result.autoResolvable()).isEqualTo(1);
        assertThat(result.ambiguous()).isZero();
    }

    @Test
    void withoutFuzzyTheCounterIsZero() {
        ReviewMatches.Review result = review.review(false, ReviewMatches.Filter.NEEDS_DECISION);

        assertThat(result.autoResolvable()).isZero();
        assertThat(result.ambiguous()).isZero();
    }

    @Test
    void anEmptyRegistryHasNoRows() {
        InMemoryGuideStore empty = new InMemoryGuideStore();
        ReviewMatches.Review result = new ReviewMatches(new InspectGuide(empty, new InMemoryChannelMatchStore()))
                .review(true, ReviewMatches.Filter.NEEDS_DECISION);

        assertThat(result.rows()).isEmpty();
        assertThat(result.channels()).isEmpty();
    }

    @Test
    void anUnknownFilterIsRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> ReviewMatches.Filter.parse("nonsense")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> sources(ReviewMatches.Review review) {
        return review.rows().stream().map(row -> row.match().source().channelId()).toList();
    }

    private static ReviewMatches.Row row(ReviewMatches.Review review, String sourceChannelId) {
        return review.rows().stream()
                .filter(row -> row.match().source().channelId().equals(sourceChannelId))
                .findFirst()
                .orElseThrow();
    }

    private static SourceChannelRef ref(String channelId) {
        return new SourceChannelRef(A, channelId);
    }

    private static Channel channel(String id, String name) {
        return new Channel(new ChannelId(id), name, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceChannel sourceChannel(String id, String name) {
        return new SourceChannel(id, name, null);
    }
}
