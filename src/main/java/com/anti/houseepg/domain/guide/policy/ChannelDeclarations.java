package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a channel declaration list means (design §6.4, ADR-0002): the id is the identity, so a list that
 * names an id twice describes one channel, not two. Providers do repeat themselves — one channel shipped
 * as an HD and an H.265 variant under a single id is the common case — and the registry stores one row per
 * id, so the duplicate has to be resolved before it reaches storage rather than by a failed write.
 */
public final class ChannelDeclarations {

    private ChannelDeclarations() {
    }

    /**
     * Keeps the first declaration of every id and reports the ids that were repeated. Declaration order is
     * preserved, because the first declaration is the one an operator sees and the registry lists in order.
     */
    public static Collapse distinctById(List<Channel> declared) {
        List<Channel> channels = declared == null ? List.of() : declared;
        Map<ChannelId, Channel> unique = new LinkedHashMap<>();
        List<ChannelId> duplicates = new ArrayList<>();
        for (Channel channel : channels) {
            if (unique.putIfAbsent(channel.id(), channel) != null) {
                duplicates.add(channel.id());
            }
        }
        return new Collapse(List.copyOf(unique.values()), List.copyOf(duplicates));
    }

    /**
     * The ids the source declares that the stored registry does not hold. A registry that lost declarations —
     * an ordered write that died on a repeated id, HOU-32 — sends those channels through as pass-through and
     * can never match them, so this difference is the fact worth reporting. Both sides are declaration lists,
     * so the answer does not change when the store simply holds an id the source no longer declares.
     */
    public static List<ChannelId> missingFrom(List<Channel> stored, List<Channel> declared) {
        List<Channel> held = stored == null ? List.of() : stored;
        List<Channel> wanted = declared == null ? List.of() : declared;
        return wanted.stream()
                .map(Channel::id)
                .distinct()
                .filter(id -> held.stream().noneMatch(channel -> channel.id().equals(id)))
                .toList();
    }

    /**
     * @param channels the declaration list with one entry per id, in declaration order
     * @param duplicates every repeated declaration that was dropped, including repeats of a repeat
     */
    public record Collapse(List<Channel> channels, List<ChannelId> duplicates) {

        public Collapse {
            channels = channels == null ? List.of() : List.copyOf(channels);
            duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
        }

        public boolean collapsedAnything() {
            return !duplicates.isEmpty();
        }
    }
}
