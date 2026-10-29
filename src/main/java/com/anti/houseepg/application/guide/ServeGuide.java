package com.anti.houseepg.application.guide;

import java.time.Duration;
import java.time.Instant;

/**
 * Use case: answer a read of the guide, rebuilding it only when the cached result is older than the
 * configured lifetime.
 */
public class ServeGuide {

    private final RefreshGuide refreshGuide;
    private final Duration cacheTtl;
    private final Object lock = new Object();
    private volatile RefreshResult current;

    public ServeGuide(RefreshGuide refreshGuide, Duration cacheTtl) {
        this.refreshGuide = refreshGuide;
        this.cacheTtl = cacheTtl;
    }

    public RefreshResult current() {
        RefreshResult snapshot = current;
        if (snapshot != null && !expired(snapshot)) {
            return snapshot;
        }
        synchronized (lock) {
            if (current != null && !expired(current)) {
                return current;
            }
            return refresh();
        }
    }

    /** Forces a rebuild. */
    public RefreshResult refresh() {
        synchronized (lock) {
            current = refreshGuide.refresh();
            return current;
        }
    }

    private boolean expired(RefreshResult snapshot) {
        return snapshot.generatedAt().plus(cacheTtl).isBefore(Instant.now());
    }
}
