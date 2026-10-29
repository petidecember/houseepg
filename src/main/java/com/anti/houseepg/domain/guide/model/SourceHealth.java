package com.anti.houseepg.domain.guide.model;

/** What happened to one source in the last refresh, as the UI and the status endpoint report it. */
public record SourceHealth(SourceId source, String name, State state, int programmeCount, String error) {

    public enum State {
        /** Fetched and read in this cycle. */
        OK,
        /** The fetch failed, so the last good data is still being served. */
        STALE,
        /** The fetch failed and there is no last good data to fall back on. */
        FAILED
    }

    public static SourceHealth ok(SourceId source, String name, int programmeCount) {
        return new SourceHealth(source, name, State.OK, programmeCount, null);
    }

    public static SourceHealth stale(SourceId source, String name, int programmeCount, String error) {
        return new SourceHealth(source, name, State.STALE, programmeCount, error);
    }

    public static SourceHealth failed(SourceId source, String name, String error) {
        return new SourceHealth(source, name, State.FAILED, 0, error);
    }
}
