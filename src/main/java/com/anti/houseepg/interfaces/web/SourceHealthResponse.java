package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.domain.guide.model.SourceHealth;

/** Presentation view of a source's health, so the wire shape does not depend on the domain's types. */
public record SourceHealthResponse(String sourceId, String name, String state, int programmeCount, String error) {

    public static SourceHealthResponse from(SourceHealth health) {
        return new SourceHealthResponse(health.source().value(), health.name(), health.state().name(),
                health.programmeCount(), health.error());
    }
}
