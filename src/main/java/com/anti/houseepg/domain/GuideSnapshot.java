package com.anti.houseepg.domain;

import java.time.Instant;
import java.util.List;

/** The merged guide as it stands after one refresh: what the feed serves and what the UI shows. */
public record GuideSnapshot(
        List<CanonicalChannel> channels,
        List<MergedProgramme> programmes,
        List<SourceStatus> statuses,
        Instant generatedAt) {

    public GuideSnapshot {
        channels = channels == null ? List.of() : List.copyOf(channels);
        programmes = programmes == null ? List.of() : List.copyOf(programmes);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
