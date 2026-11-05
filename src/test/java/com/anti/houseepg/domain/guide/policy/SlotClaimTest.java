package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0001's rule, on its own: the first source in the order claims the slot and the rest lose. */
class SlotClaimTest {

    private static final SourceId A = new SourceId("a");
    private static final SourceId B = new SourceId("b");
    private static final SourceId C = new SourceId("c");

    @Test
    void theEarlierSourceInTheOrderWinsAnOverlap() {
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 20, 21), entry(B, 20, 21)), List.of(A, B));

        assertThat(claim.outcomes()).extracting(SlotClaim.Outcome::fate)
                .containsExactly(SlotClaim.Fate.WON, SlotClaim.Fate.LOST);
        assertThat(claim.outcomes().get(1).conflictingEntry()).isZero();
        assertThat(claim.winners()).containsExactly(0);
    }

    @Test
    void reversingTheOrderSwapsTheWinner() {
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 20, 21), entry(B, 20, 21)), List.of(B, A));

        assertThat(claim.winners()).containsExactly(1);
        assertThat(claim.outcomes().get(0).conflictingEntry()).isEqualTo(1);
    }

    @Test
    void aSourceOutsideTheOrderIsExcludedRatherThanOutranked() {
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 20, 21), entry(B, 20, 21)), List.of(B));

        assertThat(claim.outcomes()).extracting(SlotClaim.Outcome::fate)
                .containsExactly(SlotClaim.Fate.EXCLUDED, SlotClaim.Fate.WON);
        assertThat(claim.winners()).containsExactly(1);
    }

    @Test
    void touchingEdgesDoNotOverlap() {
        // 20:00-21:00 and 21:00-22:00 share an instant, and ADR-0001 merges nothing across it.
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 20, 21), entry(B, 21, 22)), List.of(A, B));

        assertThat(claim.outcomes()).extracting(SlotClaim.Outcome::fate)
                .containsExactly(SlotClaim.Fate.WON, SlotClaim.Fate.WON);
    }

    @Test
    void aContainedSlotLosesToTheEntryThatAlreadyCoversIt() {
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 20, 23), entry(B, 21, 22)), List.of(A, B));

        assertThat(claim.outcomes().get(1).fate()).isEqualTo(SlotClaim.Fate.LOST);
        assertThat(claim.outcomes().get(1).conflictingEntry()).isZero();
    }

    @Test
    void theFirstOverlappingClaimIsTheOneNamedAsTheConflict() {
        // C covers 20:00-24:00, so both A and B overlap it; the conflict named is the first winner.
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 20, 21), entry(B, 22, 23), entry(C, 20, 24)),
                List.of(C, A, B));

        assertThat(claim.outcomes()).extracting(SlotClaim.Outcome::fate)
                .containsExactly(SlotClaim.Fate.LOST, SlotClaim.Fate.LOST, SlotClaim.Fate.WON);
        assertThat(claim.outcomes().get(0).conflictingEntry()).isEqualTo(2);
        assertThat(claim.outcomes().get(1).conflictingEntry()).isEqualTo(2);
    }

    @Test
    void withinOneSourceTheEarlierSlotIsClaimedFirst() {
        SlotClaim claim = SlotClaim.of(List.of(entry(A, 21, 22), entry(A, 20, 22)), List.of(A));

        assertThat(claim.winners()).containsExactly(1);
        assertThat(claim.outcomes().get(0).conflictingEntry()).isEqualTo(1);
    }

    private static SlotClaimable entry(SourceId source, int startHour, int stopHour) {
        return new SlotClaimable(source, new Slot(at(startHour), at(stopHour)));
    }

    private static OffsetDateTime at(int hour) {
        return OffsetDateTime.of(2026, 11, 5, hour % 24, 0, 0, 0, ZoneOffset.UTC).plusDays(hour / 24);
    }
}
