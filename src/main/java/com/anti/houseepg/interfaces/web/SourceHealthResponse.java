package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.domain.guide.model.SlotLoss;
import com.anti.houseepg.domain.guide.model.SourceHealth;

/**
 * Presentation view of a source's health, so the wire shape does not depend on the domain's types.
 *
 * <p>{@code dropped} and its three reason classes are the slot rule's losses for this source in the last
 * refresh (design §6.3). They answer the one question a bare {@code programmeCount} cannot: is this
 * source publishing more than the feed serves, and if so, why. A source that lost nothing reports zeroes.
 */
public record SourceHealthResponse(String sourceId, String name, String state, int programmeCount,
        int dropped, int duplicateSlots, int partialOverlaps, int lostToHigherPriority, String error) {

    public static SourceHealthResponse from(SourceHealth health, SlotLoss loss) {
        return new SourceHealthResponse(health.source().value(), health.name(), health.state().name(),
                health.programmeCount(), loss.dropped(), loss.duplicateSlots(), loss.partialOverlaps(),
                loss.lostToHigherPriority(), health.error());
    }
}
