package com.anti.houseepg.guide.app;

import com.anti.houseepg.guide.model.Guide;
import com.anti.houseepg.guide.model.SourceHealth;
import java.time.Instant;
import java.util.List;

/** The outcome of one refresh: the merged guide, what happened to each source, and when. */
public record RefreshResult(Guide guide, List<SourceHealth> health, Instant generatedAt) {

    public RefreshResult {
        health = health == null ? List.of() : List.copyOf(health);
    }
}
