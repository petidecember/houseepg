package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** The canonical channels the feed can emit, with every match row and the source channel behind it. */
    public Registry registry() {
        Map<SourceChannelRef, SourceChannel> sourceChannels = sourceChannels(matchStore.decisions());
        return new Registry(store.registry(), stillDeclared(sourceChannels), sourceChannels);
    }

    /**
     * Keeps only the match rows whose source channel the source still declares.
     *
     * <p>A match row is written the first time a source channel is seen and never deleted, so the rows of
     * a channel that has since disappeared — an operator exclusion (HOU-33) is the usual reason — would
     * otherwise live on in Match review as a row with no source channel behind it, offering a decision
     * about something that no longer exists.
     *
     * <p>A source whose normalized document is gone entirely contributes no source channels at all, and
     * that is not evidence the channel has disappeared: the rows are then kept so a review list survives
     * a source that is merely failing, exactly as before.
     */
    private List<ChannelMatch> stillDeclared(Map<SourceChannelRef, SourceChannel> sourceChannels) {
        List<ChannelMatch> matches = matchStore.decisions();
        return matches.stream()
                .filter(match -> sourceChannels.containsKey(match.source())
                        || store.normalized(match.source().source()).isEmpty())
                .toList();
    }

    /** The source channel behind one match row, when its normalized document is still stored. */
    public Optional<SourceChannel> sourceChannel(SourceChannelRef ref) {
        return store.normalized(ref.source()).flatMap(document -> document.channels().stream()
                .filter(channel -> channel.id().equals(ref.channelId()))
                .findFirst());
    }

    /**
     * The source channels the match rows point at, so a review row can show the source's own name and
     * icon next to the canonical channel it was attached to. A source whose normalized document is gone
     * contributes nothing; the row then falls back to the id it already carries.
     */
    private Map<SourceChannelRef, SourceChannel> sourceChannels(List<ChannelMatch> matches) {
        Map<SourceChannelRef, SourceChannel> found = new LinkedHashMap<>();
        Set<SourceId> sources = new LinkedHashSet<>();
        for (ChannelMatch match : matches) {
            sources.add(match.source().source());
        }
        for (SourceId source : sources) {
            store.normalized(source).ifPresent(document -> {
                for (SourceChannel channel : document.channels()) {
                    found.put(new SourceChannelRef(source, channel.id()), channel);
                }
            });
        }
        return found;
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
     * @param sourceChannels the source channel each match row points at, for the review comparison
     */
    public record Registry(List<Channel> channels, List<ChannelMatch> matches,
            Map<SourceChannelRef, SourceChannel> sourceChannels) {

        public Registry {
            channels = channels == null ? List.of() : List.copyOf(channels);
            matches = matches == null ? List.of() : List.copyOf(matches);
            sourceChannels = sourceChannels == null ? Map.of() : Map.copyOf(sourceChannels);
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
