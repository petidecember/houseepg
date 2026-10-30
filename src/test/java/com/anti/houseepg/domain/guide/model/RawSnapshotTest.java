package com.anti.houseepg.domain.guide.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A feed larger than a document may be keeps its timestamp and size, and loses only the text. */
class RawSnapshotTest {

    private static final SourceId SOURCE = new SourceId("provider-a");
    private static final Instant FETCHED_AT = Instant.parse("2026-10-30T01:00:00Z");

    @Test
    void aPayloadThatFitsIsKeptVerbatim() {
        RawSnapshot snapshot = RawSnapshot.of(SOURCE, FETCHED_AT, "<tv/>");

        assertThat(snapshot.isStored()).isTrue();
        assertThat(snapshot.isOversized()).isFalse();
        assertThat(snapshot.content()).isEqualTo("<tv/>");
        assertThat(snapshot.fetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void aPayloadAboveTheLimitKeepsOnlyTheFactThatItWasFetched() {
        RawSnapshot snapshot = RawSnapshot.oversized(SOURCE, FETCHED_AT, 40L * 1024 * 1024);

        assertThat(snapshot.isStored()).isFalse();
        assertThat(snapshot.isOversized()).isTrue();
        assertThat(snapshot.content()).isEmpty();
        assertThat(snapshot.sizeBytes()).isEqualTo(40L * 1024 * 1024);
    }

    @Test
    void theSchedulerStillSeesAFetchTimeForAnOversizedPayload() {
        RawSnapshot snapshot = RawSnapshot.oversized(SOURCE, FETCHED_AT, 20L * 1024 * 1024);

        assertThat(snapshot.fetchedAt()).isEqualTo(FETCHED_AT);
    }

    @Test
    void theStoredLimitStaysWellBelowTheDocumentSizeADatabaseAccepts() {
        assertThat(RawSnapshot.MAX_CONTENT_BYTES).isLessThan(16L * 1024 * 1024);
    }
}
