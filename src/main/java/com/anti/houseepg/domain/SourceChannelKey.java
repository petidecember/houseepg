package com.anti.houseepg.domain;

/** Identifies one source's channel, so a programme can be resolved to its canonical channel. */
public record SourceChannelKey(String sourceId, String channelId) {
}
