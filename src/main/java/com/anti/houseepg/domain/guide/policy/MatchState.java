package com.anti.houseepg.domain.guide.policy;

/**
 * Where a channel match came from, and whether a human has looked at it (design §5).
 *
 * <p>{@link #AUTO} and {@link #UNMATCHED} are produced by every refresh. {@link #CONFIRMED} and
 * {@link #CORRECTED} are written only by an explicit decision and are never overwritten by a refresh.
 */
public enum MatchState {

    /** Matched by name, not yet reviewed. */
    AUTO,

    /** A human accepted the automatic match. */
    CONFIRMED,

    /** A human chose a different canonical channel. */
    CORRECTED,

    /** Matched nothing, so the channel is served as a pass-through under its own id. */
    UNMATCHED;

    /** True for decisions a refresh must not silently replace. */
    public boolean isCurated() {
        return this == CONFIRMED || this == CORRECTED;
    }
}
