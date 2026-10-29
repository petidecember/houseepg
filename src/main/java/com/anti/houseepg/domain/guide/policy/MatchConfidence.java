package com.anti.houseepg.domain.guide.policy;

/**
 * How strong the evidence behind a match is. The score is for reporting and for ordering the review
 * list; matching itself never branches on it, so the numbers can be tuned without changing behaviour.
 */
public enum MatchConfidence {

    /** The source channel id is a registry channel id, character for character. */
    EXACT_ID(1.0),

    /** The display name is a registry channel display name, character for character. */
    EXACT_NAME(0.9),

    /** The normalized source channel id equals a registry channel's normalized name. */
    NORMALIZED_ID(0.8),

    /** Only the normalized display name matched. */
    NORMALIZED_NAME(0.6),

    /** No evidence: the channel is a pass-through. */
    NONE(0.0);

    private final double score;

    MatchConfidence(double score) {
        this.score = score;
    }

    public double score() {
        return score;
    }
}
