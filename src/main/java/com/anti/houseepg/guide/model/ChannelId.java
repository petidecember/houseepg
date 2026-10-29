package com.anti.houseepg.guide.model;

/**
 * The channel identity the feed emits. It is always inherited from the channel authority — the
 * playlist's {@code tvg-id} or the primary source's channel id — and never generated (ADR-0002).
 */
public record ChannelId(String value) {

    public ChannelId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a channel id is inherited from the channel authority and cannot be blank");
        }
        value = value.trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
