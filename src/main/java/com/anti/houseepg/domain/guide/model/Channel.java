package com.anti.houseepg.domain.guide.model;

/** A canonical channel: registry entry, primary-source channel, or pass-through channel. */
public record Channel(ChannelId id, String displayName, String iconUrl, String number, Origin origin) {

    public enum Origin {
        /** From the supplied playlist; its tvg-id is the emitted id. */
        PLAYLIST,
        /** From the top-priority source, used when no playlist is configured. */
        PRIMARY_SOURCE,
        /** Matched no registry channel, so it is served under its own id. */
        PASS_THROUGH
    }

    public String label() {
        return displayName == null || displayName.isBlank() ? id.value() : displayName;
    }
}
