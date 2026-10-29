package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Use case: let a human look at what the merge produced — the registry with the matches behind it, and
 * one channel's timeline — so a wrong match can be spotted and corrected (design §10).
 */
public class InspectGuide {

    private final GuideStore store;
    private final ChannelMatchStore matchStore;

    public InspectGuide(GuideStore store, ChannelMatchStore matchStore) {
        this.store = store;
        this.matchStore = matchStore;
    }

    /** The canonical channels the feed can emit, with every match row known about them. */
    public Registry registry() {
        return new Registry(store.registry(), matchStore.decisions());
    }

    /** One canonical channel and the programmes the merge gave it, in start order. */
    public Optional<Timeline> preview(ChannelId channelId) {
        return store.mergedGuide().flatMap(stored -> stored.guide().channels().stream()
                .filter(channel -> channel.id().equals(channelId))
                .findFirst()
                .map(channel -> new Timeline(channel, stored.guide().programmes().stream()
                        .filter(programme -> programme.channelId().equals(channelId))
                        .sorted(Comparator.comparing(programme -> programme.slot().start()))
                        .toList(), stored.materializedAt())));
    }

    /**
     * @param channels the registry, which is the channel authority's answer, not the merge's
     * @param matches one row per source channel, curated rows included
     */
    public record Registry(List<Channel> channels, List<ChannelMatch> matches) {

        public Registry {
            channels = channels == null ? List.of() : List.copyOf(channels);
            matches = matches == null ? List.of() : List.copyOf(matches);
        }
    }

    /**
     * @param programmes the channel's slots, each emitted verbatim from the source that won it
     * @param materializedAt when the served guide was last rebuilt
     */
    public record Timeline(Channel channel, List<Programme> programmes, Instant materializedAt) {

        public Timeline {
            programmes = programmes == null ? List.of() : List.copyOf(programmes);
        }
    }
}
