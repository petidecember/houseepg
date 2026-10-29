package com.anti.houseepg.domain;

/** A channel as declared by one source, before it is matched onto the canonical registry. */
public record SourceChannel(String sourceId, String id, String displayName, String iconUrl) {
}
