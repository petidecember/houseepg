package com.anti.houseepg.domain.guide.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The last good payload a source returned, decoded to XMLTV text. Kept so a re-merge never needs a
 * re-fetch, and so a failed fetch has nothing to overwrite: only a successful read produces one.
 */
public record RawSnapshot(SourceId source, Instant fetchedAt, String content) {

    public RawSnapshot {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        content = content == null ? "" : content;
    }
}
