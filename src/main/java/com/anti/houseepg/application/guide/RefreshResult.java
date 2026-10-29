package com.anti.houseepg.application.guide;

import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import java.time.Instant;
import java.util.List;

/** The outcome of one refresh: the merged guide, what happened to each source, and when. */
public record RefreshResult(Guide guide, List<SourceHealth> health, Instant generatedAt) {

    public RefreshResult {
        health = health == null ? List.of() : List.copyOf(health);
    }
}
