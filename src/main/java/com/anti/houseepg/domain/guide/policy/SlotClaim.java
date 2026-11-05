package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.SourceId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The slot rule of ADR-0001 as one pure function: given entries and an order, which entry won which
 * slot and which was dropped by which overlap.
 *
 * <p>This is the only place the rule is written down. {@link PriorityMerge#merge} uses it to build the
 * served guide and the merge preview uses it to explain the same guide, so the two cannot drift: a
 * preview that disagreed with the feed would be worse than no preview (design §6.6). The order is a
 * parameter rather than a setting, which is what lets the preview walk a draft order.
 */
public record SlotClaim(List<SlotClaim.Outcome> outcomes) {

    public enum Fate {
        /** The entry claimed its slot and is emitted. */
        WON,
        /** An already-claimed slot overlaps it, so it is dropped whole and never clipped. */
        LOST,
        /** Its source is not in the order, so it can never win however long the walk runs. */
        EXCLUDED
    }

    /**
     * @param conflictingEntry index of the entry that had already claimed the slot, or -1 when the
     *     outcome is not a loss to an overlap
     */
    public record Outcome(Fate fate, int conflictingEntry) {

        public boolean won() {
            return fate == Fate.WON;
        }
    }

    public SlotClaim {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    /**
     * Walks the entries in priority order, earliest first within a source, and lets each claim the slot
     * its programme occupies. Touching edges do not overlap, so a programme starting exactly when
     * another stops is kept.
     */
    public static SlotClaim of(List<SlotClaimable> entries, List<SourceId> order) {
        List<SlotClaimable> claims = entries == null ? List.of() : List.copyOf(entries);
        Map<SourceId, Integer> rank = new HashMap<>();
        List<SourceId> ranking = order == null ? List.of() : order;
        for (int index = 0; index < ranking.size(); index++) {
            rank.putIfAbsent(ranking.get(index), index);
        }

        List<Integer> walked = new ArrayList<>();
        for (int index = 0; index < claims.size(); index++) {
            walked.add(index);
        }
        walked.sort(Comparator
                .comparingInt((Integer index) -> rank.getOrDefault(claims.get(index).source(), Integer.MAX_VALUE))
                .thenComparing(index -> claims.get(index).slot().start()));

        List<Integer> claimed = new ArrayList<>();
        Outcome[] outcomes = new Outcome[claims.size()];
        for (int index : walked) {
            SlotClaimable entry = claims.get(index);
            if (!rank.containsKey(entry.source())) {
                outcomes[index] = new Outcome(Fate.EXCLUDED, -1);
                continue;
            }
            int conflict = -1;
            for (int winner : claimed) {
                if (claims.get(winner).slot().overlaps(entry.slot())) {
                    conflict = winner;
                    break;
                }
            }
            if (conflict >= 0) {
                outcomes[index] = new Outcome(Fate.LOST, conflict);
                continue;
            }
            claimed.add(index);
            outcomes[index] = new Outcome(Fate.WON, -1);
        }
        return new SlotClaim(List.of(outcomes));
    }

    /** The indices of the entries that won, in claim order. */
    public List<Integer> winners() {
        List<Integer> won = new ArrayList<>();
        for (int index = 0; index < outcomes.size(); index++) {
            if (outcomes.get(index).won()) {
                won.add(index);
            }
        }
        return won;
    }
}
