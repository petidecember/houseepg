package com.anti.houseepg.domain.guide.model;

/** A programme as one upstream source published it, still attached to that source's channel id. */
public record SourceProgramme(
        String sourceChannelId,
        Slot slot,
        String title,
        String description,
        String category,
        String episodeNumber,
        String iconUrl) {
}
