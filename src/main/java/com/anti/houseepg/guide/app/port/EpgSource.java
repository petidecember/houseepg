package com.anti.houseepg.guide.app.port;

import com.anti.houseepg.guide.model.SourceId;

/** A configured upstream source: where to read it and how to authenticate. */
public record EpgSource(SourceId id, String name, String location, Credentials credentials) {

    /**
     * Builds a source from raw configuration. A source without an id is keyed by its location, so a
     * misconfiguration surfaces as a failed source rather than as a null key.
     */
    public static EpgSource of(String id, String name, String location, Credentials credentials) {
        String key = id != null && !id.isBlank() ? id
                : location != null && !location.isBlank() ? location : "unnamed-source";
        return new EpgSource(new SourceId(key), name, location, credentials == null ? Credentials.none() : credentials);
    }

    public String displayName() {
        return name == null || name.isBlank() ? id.value() : name;
    }
}
