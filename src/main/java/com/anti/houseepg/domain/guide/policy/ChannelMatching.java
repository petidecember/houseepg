package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches every source channel to a canonical channel by its {@link ChannelKey}. A source channel
 * that matches nothing becomes a pass-through channel under its own id, so nothing is silently
 * dropped (ADR-0003).
 */
public final class ChannelMatching {

    private ChannelMatching() {
    }

    public record Match(List<Channel> channels, Map<SourceChannelRef, ChannelId> canonicalIds) {

        public Match {
            channels = List.copyOf(channels);
            canonicalIds = Map.copyOf(canonicalIds);
        }
    }

    public static Match match(List<Channel> registry, List<SourceEpg> sources) {
        Map<ChannelKey, Channel> index = indexRegistry(registry);

        Map<String, Channel> channels = new LinkedHashMap<>();
        for (Channel channel : registry) {
            channels.putIfAbsent(channel.id().value(), channel);
        }

        Map<SourceChannelRef, ChannelId> canonicalIds = new LinkedHashMap<>();
        for (SourceEpg source : sources) {
            for (SourceChannel sourceChannel : source.channels()) {
                SourceChannelRef ref = new SourceChannelRef(source.source(), sourceChannel.id());
                Channel matched = lookup(index, sourceChannel.id());
                if (matched == null) {
                    matched = lookup(index, sourceChannel.displayName());
                }
                if (matched != null) {
                    canonicalIds.put(ref, matched.id());
                } else {
                    Channel passThrough = new Channel(new ChannelId(sourceChannel.id()), sourceChannel.displayName(),
                            sourceChannel.iconUrl(), null, Channel.Origin.PASS_THROUGH);
                    channels.putIfAbsent(passThrough.id().value(), passThrough);
                    canonicalIds.put(ref, passThrough.id());
                }
            }
        }

        return new Match(List.copyOf(channels.values()), canonicalIds);
    }

    private static Map<ChannelKey, Channel> indexRegistry(List<Channel> registry) {
        Map<ChannelKey, Channel> index = new LinkedHashMap<>();
        for (Channel channel : registry) {
            index.putIfAbsent(ChannelKey.of(channel.id().value()), channel);
            index.putIfAbsent(ChannelKey.of(channel.displayName()), channel);
        }
        return index;
    }

    private static Channel lookup(Map<ChannelKey, Channel> index, String raw) {
        ChannelKey key = ChannelKey.of(raw);
        return key.isEmpty() ? null : index.get(key);
    }
}
