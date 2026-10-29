package com.anti.houseepg.application.guide;

import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import java.time.Instant;
import java.util.List;

/**
 * The outcome of one refresh: the merged guide, what happened to each source, which channel each
 * source channel was attached to, and when.
 */
public record RefreshResult(Guide guide, List<SourceHealth> health, Instant generatedAt,
        List<ChannelMatch> matches) {

    public RefreshResult {
        health = health == null ? List.of() : List.copyOf(health);
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}
