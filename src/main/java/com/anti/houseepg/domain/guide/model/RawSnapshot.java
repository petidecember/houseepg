package com.anti.houseepg.domain.guide.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The last good payload a source returned, kept so a re-merge never needs a re-fetch and so a failed
 * fetch has nothing to overwrite: only a successful, parsed read produces one (design §7).
 *
 * <p>{@code fetchedAt} is the part the scheduler depends on, so it is always recorded. The payload text
 * is a convenience for diagnosis, and a real feed can be larger than a database document may be; when
 * that happens the snapshot keeps its timestamp and size and stores no text at all, rather than losing
 * the fetch or the source's health over a debugging aid.
 */
public record RawSnapshot(SourceId source, Instant fetchedAt, String content, long sizeBytes) {

    /** Comfortably below a 16 MB document limit, leaving room for the surrounding fields. */
    public static final long MAX_CONTENT_BYTES = 12L * 1024 * 1024;

    public RawSnapshot {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        content = content == null ? "" : content;
        sizeBytes = sizeBytes < 0 ? content.length() : sizeBytes;
    }

    /** A snapshot small enough to keep verbatim. */
    public static RawSnapshot of(SourceId source, Instant fetchedAt, String content) {
        return new RawSnapshot(source, fetchedAt, content, content == null ? 0 : content.length());
    }

    /** A snapshot too large to store: the fetch still happened, and the scheduler still needs to know. */
    public static RawSnapshot oversized(SourceId source, Instant fetchedAt, long sizeBytes) {
        return new RawSnapshot(source, fetchedAt, "", sizeBytes);
    }

    /** Whether the payload itself was kept, or only the fact that it was fetched. */
    public boolean isStored() {
        return !content.isEmpty();
    }

    /** True when the payload was fetched but deliberately not retained. */
    public boolean isOversized() {
        return content.isEmpty() && sizeBytes > 0;
    }
}
