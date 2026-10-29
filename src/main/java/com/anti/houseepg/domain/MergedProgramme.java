package com.anti.houseepg.domain;

import java.time.OffsetDateTime;

/** A programme that won its slot, carrying the source that won it so the merge can be explained. */
public record MergedProgramme(
        String canonicalChannelId,
        OffsetDateTime start,
        OffsetDateTime stop,
        String winningSourceId,
        String title,
        String description,
        String category,
        String episodeNum,
        String iconUrl) {
}
