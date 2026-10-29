package com.anti.houseepg.application.config.port;

/**
 * Rebuilds the served guide from the sources and the stored layers, without changing the registry
 * (ADR-0005). A configuration reload needs it; the implementation is the guide refresh use case, which
 * only fetches the sources whose interval has elapsed.
 */
@FunctionalInterface
public interface GuideReMerge {

    void rematerialize();
}
