package com.anti.houseepg.guide.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The merged guide: the aggregate the feed serves. Its invariant is that every programme belongs to a
 * declared channel, so a feed can never reference a channel it does not describe.
 */
public record Guide(List<Channel> channels, List<Programme> programmes) {

    public Guide {
        channels = channels == null ? List.of() : List.copyOf(channels);
        programmes = programmes == null ? List.of() : List.copyOf(programmes);

        Set<ChannelId> declared = new HashSet<>();
        for (Channel channel : channels) {
            declared.add(channel.id());
        }
        for (Programme programme : programmes) {
            if (!declared.contains(programme.channelId())) {
                throw new IllegalArgumentException(
                        "programme references a channel the guide does not declare: " + programme.channelId());
            }
        }
    }

    public static Guide empty() {
        return new Guide(List.of(), List.of());
    }
}
