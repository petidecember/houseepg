package com.anti.houseepg.domain;

/** A channel in the canonical registry, or a pass-through channel that matched nothing. */
public record CanonicalChannel(String id, String displayName, String iconUrl, String number, Origin origin)
        implements Comparable<CanonicalChannel> {

    public enum Origin {
        /** Seeded from the supplied M3U playlist; its tvg-id is the emitted id. */
        PLAYLIST,
        /** Seeded from the top-priority XMLTV source when no playlist is configured. */
        PRIMARY_SOURCE,
        /** No registry channel matched; emitted under its own source id. */
        PASS_THROUGH
    }

    @Override
    public int compareTo(CanonicalChannel other) {
        return id.compareTo(other.id);
    }
}
