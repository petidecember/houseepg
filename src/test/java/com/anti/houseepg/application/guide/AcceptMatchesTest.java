package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Decision;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Accepting a supplied set: the server writes exactly the rows it is handed, validates them against the
 * registry, never overwrites a decision already stored, and pays for the whole batch with one re-merge.
 */
class AcceptMatchesTest {

    private static final SourceId A = new SourceId("a");

    private final InMemoryChannelMatchStore matchStore = new InMemoryChannelMatchStore();
    private final InMemoryGuideStore guideStore = new InMemoryGuideStore();
    private final AtomicInteger reMerges = new AtomicInteger();
    private final AcceptMatches accept = new AcceptMatches(matchStore, guideStore, reMerges::incrementAndGet);

    @BeforeEach
    void setUp() {
        guideStore.saveRegistry(List.of(
                channel("amc.xx"), channel("bravo.xx"), channel("charlie.xx"), channel("delta.xx"),
                channel("echo.xx"), channel("foxtrot.xx")));
        matchStore.saveAuto(List.of(
                ChannelMatch.auto(ref("amc.xx"), new ChannelId("amc.xx"), MatchConfidence.EXACT_ID),
                ChannelMatch.auto(ref("bravo.xx"), new ChannelId("bravo.xx"), MatchConfidence.EXACT_NAME),
                ChannelMatch.auto(ref("charlie.xx"), new ChannelId("charlie.xx"), MatchConfidence.NORMALIZED_NAME),
                ChannelMatch.auto(ref("delta.xx"), new ChannelId("delta.xx"), MatchConfidence.FUZZY_NAME),
                ChannelMatch.unmatched(ref("zzz.xx"), new ChannelId("zzz.xx"))));
        matchStore.decide(ChannelMatch.confirmed(ref("echo.xx"), new ChannelId("amc.xx"),
                MatchConfidence.EXACT_ID));
    }

    @Test
    void writesExactlyTheRowsItIsHanded() {
        accept.accept(List.of(
                decision("amc.xx", "amc.xx"), decision("bravo.xx", "bravo.xx"), decision("charlie.xx", "charlie.xx")),
                false);

        assertThat(curated()).containsExactlyInAnyOrder("echo.xx", "amc.xx", "bravo.xx", "charlie.xx");
    }

    @Test
    void neverWidensTheSetTheCallerSent() {
        accept.accept(List.of(decision("bravo.xx", "bravo.xx")), false);

        assertThat(curated()).containsExactlyInAnyOrder("echo.xx", "bravo.xx");
    }

    @Test
    void duplicateRowsCollapseAndTheLastOneWins() {
        AcceptMatches.Result result = accept.accept(List.of(
                decision("amc.xx", "amc.xx"), decision("amc.xx", "charlie.xx")), false);

        assertThat(result.count()).isEqualTo(1);
        assertThat(target("amc.xx")).isEqualTo("charlie.xx");
    }

    @Test
    void aChannelOutsideTheRegistryIsRejectedByName() {
        assertThatThrownBy(() -> accept.accept(List.of(decision("amc.xx", "nowhere.xx")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nowhere.xx");
        assertThat(reMerges).hasValue(0);
    }

    @Test
    void aRowWhoseDecisionIsAlreadyStoredIsSkippedAndNotCounted() {
        AcceptMatches.Result result = accept.accept(List.of(decision("echo.xx", "charlie.xx")), false);

        assertThat(result.count()).isZero();
        assertThat(target("echo.xx")).isEqualTo("amc.xx");
        assertThat(reMerges).hasValue(0);
    }

    @Test
    void aDryRunReportsTheSameSetWithoutWriting() {
        List<Decision> wanted = List.of(decision("amc.xx", "amc.xx"), decision("charlie.xx", "charlie.xx"));

        AcceptMatches.Result preview = accept.accept(wanted, true);

        assertThat(preview.count()).isEqualTo(2);
        assertThat(preview.rows()).extracting(row -> row.source().channelId())
                .containsExactlyInAnyOrder("amc.xx", "charlie.xx");
        assertThat(preview.dryRun()).isTrue();
        assertThat(reMerges).hasValue(0);
        assertThat(curated()).containsExactly("echo.xx");
    }

    @Test
    void theWholeBatchCostsOneReMerge() {
        accept.accept(List.of(
                decision("amc.xx", "amc.xx"), decision("bravo.xx", "bravo.xx"), decision("charlie.xx",
                        "charlie.xx")),
                false);

        assertThat(reMerges).hasValue(1);
    }

    @Test
    void anEmptyRequestWritesNothingAndDoesNotReMerge() {
        AcceptMatches.Result result = accept.accept(List.of(), false);

        assertThat(result.count()).isZero();
        assertThat(reMerges).hasValue(0);
        assertThat(curated()).containsExactly("echo.xx");
    }

    @Test
    void aSecondApplyOverTheSameRowsAcceptsNothing() {
        List<Decision> wanted = List.of(decision("amc.xx", "amc.xx"), decision("charlie.xx", "charlie.xx"));
        accept.accept(wanted, false);

        assertThat(accept.accept(wanted, false).count()).isZero();
        assertThat(reMerges).hasValue(1);
    }

    @Test
    void aConfirmedRowKeepsTheConfidenceTheAutomaticMatchAlreadyCarried() {
        accept.accept(List.of(decision("delta.xx", "delta.xx")), false);

        assertThat(matchStore.decisions())
                .filteredOn(match -> match.source().channelId().equals("delta.xx"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.state()).isEqualTo(MatchState.CONFIRMED);
                    assertThat(match.confidence()).isEqualTo(MatchConfidence.FUZZY_NAME);
                });
    }

    @Test
    void aRowPointedSomewhereNewIsRecordedAsACorrection() {
        accept.accept(List.of(decision("charlie.xx", "bravo.xx")), false);

        assertThat(matchStore.decisions())
                .filteredOn(match -> match.source().channelId().equals("charlie.xx"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.state()).isEqualTo(MatchState.CORRECTED);
                    assertThat(match.confidence()).isEqualTo(MatchConfidence.NORMALIZED_NAME);
                });
    }

    @Test
    void anAlreadyCuratedRowKeepsItsOwnAnswer() {
        accept.accept(List.of(decision("echo.xx", "amc.xx")), false);

        assertThat(matchStore.decisions())
                .filteredOn(match -> match.source().channelId().equals("echo.xx"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.state()).isEqualTo(MatchState.CONFIRMED);
                    assertThat(match.canonicalId().value()).isEqualTo("amc.xx");
                });
    }

    private List<String> curated() {
        return matchStore.decisions().stream()
                .filter(ChannelMatch::isCurated)
                .map(match -> match.source().channelId())
                .toList();
    }

    private String target(String sourceChannelId) {
        return matchStore.decisions().stream()
                .filter(match -> match.source().channelId().equals(sourceChannelId))
                .findFirst()
                .map(match -> match.canonicalId().value())
                .orElseThrow();
    }

    private static Decision decision(String sourceChannelId, String canonicalId) {
        return new Decision(A, sourceChannelId, new ChannelId(canonicalId));
    }

    private static Channel channel(String id) {
        return new Channel(new ChannelId(id), id, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceChannelRef ref(String channelId) {
        return new SourceChannelRef(A, channelId);
    }
}
