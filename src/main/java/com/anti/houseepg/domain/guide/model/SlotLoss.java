package com.anti.houseepg.domain.guide.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many of one source's entries the slot rule dropped in the last refresh, and why.
 *
 * <p>Nothing about a dropped entry is emitted, so this is the only record that the source published
 * more than the feed serves. It is counted by the same {@code SlotClaim} walk that decides the loss
 * (design §6.3), so the numbers cannot drift from the served guide.
 *
 * @param dropped entries the walk dropped, which is not the same as "published minus served": an entry
 *     can also be dropped because its channel matched nothing and is not in the guide at all
 * @param duplicateSlots dropped entries whose slot was already claimed byte-for-byte by an earlier entry
 * @param partialOverlaps dropped entries that overlapped an earlier claim without matching it exactly
 * @param lostToHigherPriority dropped entries beaten by an entry of a different source; the remainder of
 *     {@code dropped} once the two classes above are taken out
 */
public record SlotLoss(int dropped, int duplicateSlots, int partialOverlaps, int lostToHigherPriority) {

    public SlotLoss {
        if (dropped < 0 || duplicateSlots < 0 || partialOverlaps < 0 || lostToHigherPriority < 0) {
            throw new IllegalArgumentException("slot losses cannot be negative");
        }
        if (duplicateSlots + partialOverlaps + lostToHigherPriority != dropped) {
            throw new IllegalArgumentException("the reason classes must add up to the total: "
                    + duplicateSlots + " + " + partialOverlaps + " + " + lostToHigherPriority
                    + " is not " + dropped);
        }
    }

    public static SlotLoss none() {
        return new SlotLoss(0, 0, 0, 0);
    }

    /** True when this source competes with itself: the shape that has no tie-break available. */
    public boolean selfOverlap() {
        return duplicateSlots > 0 || partialOverlaps > 0;
    }

    /** Element-wise sum, for folding a walk's losses one canonical channel at a time. */
    public SlotLoss plus(SlotLoss other) {
        return new SlotLoss(dropped + other.dropped, duplicateSlots + other.duplicateSlots,
                partialOverlaps + other.partialOverlaps,
                lostToHigherPriority + other.lostToHigherPriority);
    }

    /** The losses per source id. A source that lost nothing may be absent from the map. */
    public record BySource(Map<SourceId, SlotLoss> losses) {

        public BySource {
            losses = losses == null ? Map.of() : Map.copyOf(losses);
        }

        public static BySource none() {
            return new BySource(Map.of());
        }

        public SlotLoss of(SourceId source) {
            return losses.getOrDefault(source, SlotLoss.none());
        }

        /**
         * Adds another fold into this one. Losses are counted per canonical channel and the counts are
         * then summed, because a {@code conflictingEntry} index only means anything inside the walk that
         * produced it.
         */
        public BySource plus(BySource other) {
            Map<SourceId, SlotLoss> summed = new LinkedHashMap<>(losses);
            other.losses.forEach((source, loss) -> summed.merge(source, loss, SlotLoss::plus));
            return new BySource(summed);
        }
    }
}
