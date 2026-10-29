package com.anti.houseepg.support;

import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for the match store: same no-downgrade contract, in memory. */
public class InMemoryChannelMatchStore implements ChannelMatchStore {

    private final Map<SourceChannelRef, ChannelMatch> matches = new ConcurrentHashMap<>();

    @Override
    public List<ChannelMatch> decisions() {
        return List.copyOf(matches.values());
    }

    @Override
    public void saveAuto(List<ChannelMatch> incoming) {
        for (ChannelMatch match : incoming) {
            matches.compute(match.source(), (ref, existing) -> keepCurated(existing, match));
        }
    }

    @Override
    public void decide(ChannelMatch decision) {
        if (!decision.isCurated()) {
            throw new IllegalArgumentException("a decision must be confirmed or corrected, not " + decision.state());
        }
        matches.put(decision.source(), decision);
    }

    private static ChannelMatch keepCurated(ChannelMatch existing, ChannelMatch incoming) {
        return existing != null && existing.isCurated() ? existing : incoming;
    }
}
