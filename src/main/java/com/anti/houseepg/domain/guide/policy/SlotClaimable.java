package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceId;

import java.util.Objects;

/**
 * One entry taking part in a slot-claim walk: who published it and the time it wants.
 *
 * <p>Deliberately narrower than a programme. The walk from ADR-0001 decides on a slot and an owner
 * only, so the live merge (which has programmes) and the merge preview (which has them too, but also
 * wants the same answer per provider) can both hand it their entries.
 */
public record SlotClaimable(SourceId source, Slot slot) {

    public SlotClaimable {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(slot, "slot");
    }
}
