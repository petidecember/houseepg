package com.anti.houseepg.domain.guide.model;

import java.time.OffsetDateTime;

/**
 * The half-open interval a programme occupies. Overlap is the only relationship the merge rule cares
 * about, so it lives here rather than in the policy (ADR-0001).
 */
public record Slot(OffsetDateTime start, OffsetDateTime stop) {

    public Slot {
        if (start == null || stop == null) {
            throw new IllegalArgumentException("a slot needs both a start and a stop");
        }
        if (!stop.isAfter(start)) {
            throw new IllegalArgumentException("a slot must stop after it starts: " + start + " .. " + stop);
        }
    }

    /** True when the two intervals share any instant at all. Touching edges do not overlap. */
    public boolean overlaps(Slot other) {
        return start.isBefore(other.stop) && other.start.isBefore(stop);
    }
}
