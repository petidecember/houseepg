package com.anti.houseepg.domain.guide.model;

/**
 * A decision about matching a source channel to a canonical channel.
 */
public record Decision(SourceId source, String sourceChannelId, ChannelId canonicalId) {

    public Decision {
        if (source == null) {
            throw new IllegalArgumentException("source cannot be null");
        }
        if (sourceChannelId == null || sourceChannelId.isBlank()) {
            throw new IllegalArgumentException("sourceChannelId cannot be blank");
        }
        if (canonicalId == null) {
            throw new IllegalArgumentException("canonicalId cannot be null");
        }
    }
}