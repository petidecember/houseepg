package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bulk accept: only exact id and exact name matches, the preview is the same computation as the apply,
 * and the whole batch costs one re-merge.
 */
class BulkAcceptMatchesTest {

    private static final SourceId A = new SourceId("a");

    private final InMemoryChannelMatchStore matchStore = new InMemoryChannelMatchStore();
    private final AtomicInteger reMerges = new AtomicInteger();
    private final BulkAcceptMatches bulkAccept = new BulkAcceptMatches(matchStore, reMerges::incrementAndGet);

    @BeforeEach
    void setUp() {
        matchStore.saveAuto(List.of(
                ChannelMatch.auto(ref("amc.xx"), new ChannelId("amc.xx"), MatchConfidence.EXACT_ID),
                ChannelMatch.auto(ref("bravo.xx"), new ChannelId("bravo.xx"), MatchConfidence.EXACT_NAME),
                ChannelMatch.auto(ref("charlie.xx"), new ChannelId("charlie.xx"), MatchConfidence.NORMALIZED_NAME),
                ChannelMatch.unmatched(ref("zzz.xx"), new ChannelId("zzz.xx"))));
        matchStore.decide(ChannelMatch.confirmed(ref("curated.xx"), new ChannelId("amc.xx"),
                MatchConfidence.EXACT_ID));
    }

    @Test
    void aDryRunReportsTheSetWithoutWriting() {
        BulkAcceptMatches.Result result = bulkAccept.accept(true);

        assertThat(result.count()).isEqualTo(2);
        assertThat(result.rows()).extracting(row -> row.source().channelId())
                .containsExactlyInAnyOrder("amc.xx", "bravo.xx");
        assertThat(result.dryRun()).isTrue();
        assertThat(reMerges).hasValue(0);
        assertThat(curated()).doesNotContain("amc.xx", "bravo.xx");
    }

    @Test
    void theApplyWritesExactlyTheRowsTheDryRunListed() {
        List<String> previewed = bulkAccept.accept(true).rows().stream()
                .map(row -> row.source().channelId())
                .toList();

        BulkAcceptMatches.Result applied = bulkAccept.accept(false);

        assertThat(applied.rows()).extracting(row -> row.source().channelId())
                .containsExactlyInAnyOrderElementsOf(previewed);
        assertThat(curated()).containsAll(previewed);
    }

    @Test
    void theWholeBatchCostsOneReMerge() {
        bulkAccept.accept(false);

        assertThat(reMerges).hasValue(1);
    }

    @Test
    void aSecondApplyAcceptsNothing() {
        bulkAccept.accept(false);

        assertThat(bulkAccept.accept(false).count()).isZero();
        assertThat(reMerges).hasValue(1);
    }

    @Test
    void normalizedAndUnmatchedRowsAreNeverIncluded() {
        bulkAccept.accept(false);

        assertThat(curated()).doesNotContain("charlie.xx", "zzz.xx");
    }

    @Test
    void anAlreadyCuratedRowIsNotOverwritten() {
        bulkAccept.accept(false);

        assertThat(matchStore.decisions())
                .filteredOn(match -> match.source().channelId().equals("curated.xx"))
                .singleElement()
                .satisfies(match -> assertThat(match.state()).isEqualTo(MatchState.CONFIRMED));
    }

    @Test
    void nothingToAcceptMeansNoWriteAndNoReMerge() {
        InMemoryChannelMatchStore empty = new InMemoryChannelMatchStore();
        AtomicInteger merges = new AtomicInteger();

        BulkAcceptMatches.Result result = new BulkAcceptMatches(empty, merges::incrementAndGet).accept(false);

        assertThat(result.count()).isZero();
        assertThat(merges).hasValue(0);
    }

    private List<String> curated() {
        return matchStore.decisions().stream()
                .filter(ChannelMatch::isCurated)
                .map(match -> match.source().channelId())
                .toList();
    }

    private static SourceChannelRef ref(String channelId) {
        return new SourceChannelRef(A, channelId);
    }
}
