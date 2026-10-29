package com.anti.houseepg.domain;

import java.time.OffsetDateTime;

/**
 * A programme exactly as one source published it. Fields are never mixed across sources: whichever
 * programme wins a slot is emitted from this record verbatim.
 */
public record Programme(
        String sourceId,
        String sourceChannelId,
        OffsetDateTime start,
        OffsetDateTime stop,
        String title,
        String description,
        String category,
        String episodeNum,
        String iconUrl) {
}
