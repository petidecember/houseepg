package com.anti.houseepg.domain.guide.model;

import java.time.Instant;
import java.util.Objects;

/** The materialized merged guide, as the feed serves it, and when it was last written. */
public record StoredGuide(Guide guide, Instant materializedAt) {

    public StoredGuide {
        Objects.requireNonNull(guide, "guide");
        Objects.requireNonNull(materializedAt, "materializedAt");
    }
}
