package com.anti.houseepg.match;

import com.anti.houseepg.domain.CanonicalChannel;
import com.anti.houseepg.domain.SourceChannel;
import com.anti.houseepg.domain.SourceChannelKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches each source channel to a canonical channel by normalized name. A source channel that
 * matches nothing becomes a pass-through channel under its own id — nothing is silently dropped.
 */
public final class ChannelMatcher {

    private ChannelMatcher() {
    }

    public record Match(
            List<CanonicalChannel> channels,
            Map<SourceChannelKey, String> canonicalIdBySourceChannel,
            List<Row> rows) {

        public Match {
            channels = List.copyOf(channels);
            canonicalIdBySourceChannel = Map.copyOf(canonicalIdBySourceChannel);
            rows = List.copyOf(rows);
        }
    }

    public record Row(String sourceId, String sourceChannelId, String displayName, String canonicalId, State state) {
        public enum State {
            MATCHED, PASS_THROUGH
        }
    }

    public static Match match(List<CanonicalChannel> registry, Map<String, List<SourceChannel>> channelsBySource) {
        Map<String, CanonicalChannel> index = new LinkedHashMap<>();
        for (CanonicalChannel channel : registry) {
            index.putIfAbsent(ChannelNameNormalizer.normalize(channel.id()), channel);
            index.putIfAbsent(ChannelNameNormalizer.normalize(channel.displayName()), channel);
        }

        Map<String, CanonicalChannel> channels = new LinkedHashMap<>();
        for (CanonicalChannel channel : registry) {
            channels.putIfAbsent(channel.id(), channel);
        }

        Map<SourceChannelKey, String> canonicalIdBySourceChannel = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();

        for (Map.Entry<String, List<SourceChannel>> entry : channelsBySource.entrySet()) {
            String sourceId = entry.getKey();
            for (SourceChannel sourceChannel : entry.getValue()) {
                CanonicalChannel match = findMatch(index, sourceChannel);
                SourceChannelKey key = new SourceChannelKey(sourceId, sourceChannel.id());
                if (match != null) {
                    canonicalIdBySourceChannel.put(key, match.id());
                    rows.add(new Row(sourceId, sourceChannel.id(), sourceChannel.displayName(), match.id(),
                            Row.State.MATCHED));
                } else {
                    channels.putIfAbsent(sourceChannel.id(), new CanonicalChannel(sourceChannel.id(),
                            sourceChannel.displayName(), sourceChannel.iconUrl(), null,
                            CanonicalChannel.Origin.PASS_THROUGH));
                    canonicalIdBySourceChannel.put(key, sourceChannel.id());
                    rows.add(new Row(sourceId, sourceChannel.id(), sourceChannel.displayName(), sourceChannel.id(),
                            Row.State.PASS_THROUGH));
                }
            }
        }

        return new Match(List.copyOf(channels.values()), canonicalIdBySourceChannel, rows);
    }

    private static CanonicalChannel findMatch(Map<String, CanonicalChannel> index, SourceChannel sourceChannel) {
        CanonicalChannel byId = lookup(index, sourceChannel.id());
        if (byId != null) {
            return byId;
        }
        return lookup(index, sourceChannel.displayName());
    }

    private static CanonicalChannel lookup(Map<String, CanonicalChannel> index, String raw) {
        String key = ChannelNameNormalizer.normalize(raw);
        return key.isEmpty() ? null : index.get(key);
    }
}
