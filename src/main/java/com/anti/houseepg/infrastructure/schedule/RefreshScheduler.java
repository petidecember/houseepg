package com.anti.houseepg.infrastructure.schedule;

import com.anti.houseepg.application.guide.RefreshGuide;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adapter: the timed side of design §8. It only decides *when* to look; each source's own interval
 * decides whether it is actually re-fetched, so a slow source never delays a fresh one beyond a tick.
 */
@Component
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshGuide refreshGuide;

    public RefreshScheduler(RefreshGuide refreshGuide) {
        this.refreshGuide = refreshGuide;
    }

    @Scheduled(fixedDelayString = "${houseepg.scheduler.tick:60s}",
            initialDelayString = "${houseepg.scheduler.initial-delay:5s}")
    public void tick() {
        try {
            refreshGuide.refreshDue(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Scheduled refresh failed: {}", e.toString());
        }
    }
}
