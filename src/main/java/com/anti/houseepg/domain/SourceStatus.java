package com.anti.houseepg.domain;

/** What happened to one source in the last refresh. Surfaced in the UI and by the status endpoint. */
public record SourceStatus(String sourceId, String name, State state, int programmeCount, String error) {

    public enum State {
        /** Fetched and parsed in this cycle. */
        OK,
        /** The fetch failed, so the last good data is still being served. */
        STALE,
        /** The fetch failed and there is no last good data to fall back on. */
        FAILED,
        /** Not part of the merge. */
        DISABLED
    }

    public static SourceStatus ok(String sourceId, String name, int programmeCount) {
        return new SourceStatus(sourceId, name, State.OK, programmeCount, null);
    }

    public static SourceStatus stale(String sourceId, String name, int programmeCount, String error) {
        return new SourceStatus(sourceId, name, State.STALE, programmeCount, error);
    }

    public static SourceStatus failed(String sourceId, String name, String error) {
        return new SourceStatus(sourceId, name, State.FAILED, 0, error);
    }
}
