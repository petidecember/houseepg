package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.ChannelId;
import java.util.Objects;

/**
 * What one source channel was attached to, and on what evidence.
 *
 * <p>Matches are keyed by {@link SourceChannelRef}, i.e. one row per source and source channel id; a
 * source that declares the same id twice therefore has one row, which is the honest reading of such
 * upstream data.
 *
 * <p>A persisted {@link MatchState#UNMATCHED} row is bookkeeping for the review list only: the next
 * refresh still tries to match that channel from scratch, so a registry channel that appears later
 * can pick it up.
 */
public record ChannelMatch(SourceChannelRef source, ChannelId canonicalId, MatchConfidence confidence,
        MatchState state) {

    public ChannelMatch {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(canonicalId, "canonicalId");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(state, "state");
    }

    public static ChannelMatch auto(SourceChannelRef source, ChannelId canonicalId, MatchConfidence confidence) {
        return new ChannelMatch(source, canonicalId, confidence, MatchState.AUTO);
    }

    public static ChannelMatch unmatched(SourceChannelRef source, ChannelId passThroughId) {
        return new ChannelMatch(source, passThroughId, MatchConfidence.NONE, MatchState.UNMATCHED);
    }

    public static ChannelMatch confirmed(SourceChannelRef source, ChannelId canonicalId, MatchConfidence confidence) {
        return new ChannelMatch(source, canonicalId, confidence, MatchState.CONFIRMED);
    }

    public static ChannelMatch corrected(SourceChannelRef source, ChannelId canonicalId, MatchConfidence confidence) {
        return new ChannelMatch(source, canonicalId, confidence, MatchState.CORRECTED);
    }

    public boolean isCurated() {
        return state.isCurated();
    }
}
