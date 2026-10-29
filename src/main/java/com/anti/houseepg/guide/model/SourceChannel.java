package com.anti.houseepg.guide.model;

/** A channel as one upstream source declared it, before it is matched onto the registry. */
public record SourceChannel(String id, String displayName, String iconUrl) {
}
