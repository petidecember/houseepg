package com.anti.houseepg.guide.model;

/**
 * A programme that won its slot, emitted verbatim from the winning source. Fields are never mixed
 * across sources (design §6.3).
 */
public record Programme(
        ChannelId channelId,
        Slot slot,
        SourceId winningSource,
        String title,
        String description,
        String category,
        String episodeNumber,
        String iconUrl) {
}
