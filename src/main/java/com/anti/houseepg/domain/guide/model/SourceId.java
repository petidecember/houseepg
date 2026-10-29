package com.anti.houseepg.domain.guide.model;

/** Identity of one upstream EPG source. */
public record SourceId(String value) {

    public SourceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a source id cannot be blank");
        }
        value = value.trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
