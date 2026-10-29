package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Attaches every source channel to a canonical channel, recording the evidence it used.
 *
 * <p>Evidence is tried strongest first: an exact source channel id, an exact display name, then the
 * normalized {@link ChannelKey} of each. A source channel that matches nothing becomes a pass-through
 * channel under its own id, so nothing is silently dropped (ADR-0003).
 *
 * <p>A recorded {@link MatchState#isCurated() curated} decision wins over all of that, so a refresh
 * cannot undo what a human confirmed or corrected.
 */
public final class ChannelMatching {

    private ChannelMatching() {
    }

    public record Match(List<Channel> channels, Map<SourceChannelRef, ChannelId> canonicalIds,
            List<ChannelMatch> matches) {

        public Match {
            channels = List.copyOf(channels);
            canonicalIds = Map.copyOf(canonicalIds);
            matches = List.copyOf(matches);
        }
    }

    public static Match match(List<Channel> registry, List<SourceEpg> sources) {
        return match(registry, sources, Map.of());
    }

    public static Match match(List<Channel> registry, List<SourceEpg> sources,
            Map<SourceChannelRef, ChannelMatch> decisions) {
        Map<String, Channel> channels = new LinkedHashMap<>();
        for (Channel channel : registry) {
            channels.putIfAbsent(channel.id().value(), channel);
        }
        Index index = Index.of(registry);

        Map<SourceChannelRef, ChannelId> canonicalIds = new LinkedHashMap<>();
        List<ChannelMatch> matches = new ArrayList<>();
        for (SourceEpg source : sources) {
            for (SourceChannel sourceChannel : source.channels()) {
                SourceChannelRef ref = new SourceChannelRef(source.source(), sourceChannel.id());
                ChannelMatch curated = curated(decisions.get(ref), channels);
                if (curated != null) {
                    canonicalIds.put(ref, curated.canonicalId());
                    matches.add(curated);
                    continue;
                }
                Resolution resolution = index.resolve(sourceChannel);
                if (resolution != null) {
                    canonicalIds.put(ref, resolution.channel().id());
                    matches.add(ChannelMatch.auto(ref, resolution.channel().id(), resolution.confidence()));
                } else {
                    Channel passThrough = new Channel(new ChannelId(sourceChannel.id()),
                            sourceChannel.displayName(), sourceChannel.iconUrl(), null,
                            Channel.Origin.PASS_THROUGH);
                    channels.putIfAbsent(passThrough.id().value(), passThrough);
                    canonicalIds.put(ref, passThrough.id());
                    matches.add(ChannelMatch.unmatched(ref, passThrough.id()));
                }
            }
        }

        return new Match(List.copyOf(channels.values()), canonicalIds, matches);
    }

    /**
     * A human decision steers matching only while the canonical channel it names still exists. A
     * registry edit that removed that channel makes the decision stale, and matching falls back to
     * name lookup rather than attaching programmes to a channel that is not emitted.
     */
    private static ChannelMatch curated(ChannelMatch decision, Map<String, Channel> channels) {
        if (decision == null || !decision.isCurated()) {
            return null;
        }
        return channels.containsKey(decision.canonicalId().value()) ? decision : null;
    }

    private record Resolution(Channel channel, MatchConfidence confidence) {
    }

    /** The registry seen four ways: by raw id, by raw display name, and by comparison key. */
    private static final class Index {

        private final Map<String, Channel> byId = new LinkedHashMap<>();
        private final Map<String, Channel> byDisplayName = new LinkedHashMap<>();
        private final Map<ChannelKey, List<Channel>> byKey = new LinkedHashMap<>();

        static Index of(List<Channel> registry) {
            Index index = new Index();
            for (Channel channel : registry) {
                index.byId.putIfAbsent(channel.id().value(), channel);
                if (channel.displayName() != null) {
                    index.byDisplayName.putIfAbsent(channel.displayName(), channel);
                }
                ChannelKey idKey = ChannelKey.of(channel.id().value());
                index.byKey.computeIfAbsent(idKey, key -> new ArrayList<>()).add(channel);
                ChannelKey displayKey = ChannelKey.of(channel.displayName());
                if (!displayKey.isEmpty() && !displayKey.equals(idKey)) {
                    index.byKey.computeIfAbsent(displayKey, key -> new ArrayList<>()).add(channel);
                }
            }
            return index;
        }

        Resolution resolve(SourceChannel sourceChannel) {
            Channel exactId = byId.get(sourceChannel.id());
            if (exactId != null) {
                return new Resolution(exactId, MatchConfidence.EXACT_ID);
            }
            Channel exactName = byDisplayName.get(sourceChannel.displayName());
            if (exactName != null) {
                return new Resolution(exactName, MatchConfidence.EXACT_NAME);
            }
            Channel byIdKey = pick(ChannelKey.of(sourceChannel.id()), sourceChannel);
            if (byIdKey != null) {
                return new Resolution(byIdKey, MatchConfidence.NORMALIZED_ID);
            }
            Channel byDisplayKey = pick(ChannelKey.of(sourceChannel.displayName()), sourceChannel);
            return byDisplayKey == null ? null : new Resolution(byDisplayKey, MatchConfidence.NORMALIZED_NAME);
        }

        /**
         * One key can cover several registry channels, because the key strips exactly the quality tags
         * the playlist uses to keep HD and SD apart. Prefer the candidate advertising the same
         * quality, then fall back to registry order so the choice is deterministic.
         */
        private Channel pick(ChannelKey key, SourceChannel sourceChannel) {
            List<Channel> candidates = key.isEmpty() ? null : byKey.get(key);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            Optional<QualityTag> wanted = QualityTag.of(sourceChannel.id())
                    .or(() -> QualityTag.of(sourceChannel.displayName()));
            if (wanted.isPresent()) {
                for (Channel candidate : candidates) {
                    if (advertises(candidate, wanted.get())) {
                        return candidate;
                    }
                }
            }
            return candidates.getFirst();
        }

        private static boolean advertises(Channel candidate, QualityTag tag) {
            return QualityTag.of(candidate.id().value()).filter(tag::equals).isPresent()
                    || QualityTag.of(candidate.displayName()).filter(tag::equals).isPresent();
        }
    }
}
