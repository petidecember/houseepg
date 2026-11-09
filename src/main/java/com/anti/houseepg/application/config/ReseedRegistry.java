package com.anti.houseepg.application.config;

import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.ChannelAuthorityGateway;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: the explicit re-seed of the channel registry — the only operation that may change an
 * emitted channel id (ADR-0002, ADR-0005).
 *
 * <p>It is deliberately two steps: {@link #preview()} reports the ids a re-seed would add and remove,
 * and {@link #apply()} performs it. Reordering sources never re-seeds, and a configuration reload
 * never re-seeds, so a DVR mapping only breaks when someone confirms this.
 */
public class ReseedRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReseedRegistry.class);

    private final GuideSettings settings;
    private final GuideStore store;
    private final ChannelAuthorityGateway channelAuthority;

    public ReseedRegistry(GuideSettings settings, GuideStore store, ChannelAuthorityGateway channelAuthority) {
        this.settings = settings;
        this.store = store;
        this.channelAuthority = channelAuthority;
    }

    public Preview preview() {
        List<Channel> stored = store.registry();
        List<Channel> proposed = proposed();
        Set<ChannelId> storedIds = ids(stored);
        List<ChannelId> added = proposed.stream().map(Channel::id).filter(id -> !storedIds.contains(id)).toList();
        List<ChannelId> removed = stored.stream().map(Channel::id)
                .filter(id -> proposed.stream().noneMatch(channel -> channel.id().equals(id))).toList();
        return new Preview(proposed, added, removed);
    }

    /** Applies exactly what {@link #preview()} reported a moment ago. */
    public synchronized Preview apply() {
        Preview preview = preview();
        store.saveRegistry(preview.proposed());
        log.info("Registry re-seeded: {} channel(s), {} id(s) added, {} removed",
                preview.proposed().size(), preview.added().size(), preview.removed().size());
        return preview;
    }

    private List<Channel> proposed() {
        Optional<String> playlist = settings.playlistLocation();
        if (playlist.isPresent()) {
            try {
                List<Channel> channels = channelAuthority.channels(playlist.get(), settings.playlistGroups());
                if (channels.isEmpty()) {
                    log.warn("Channel authority {} returned no channels; nothing to re-seed from", playlist.get());
                    return store.registry();
                }
                return channels;
            } catch (RuntimeException e) {
                log.warn("Channel authority {} is unavailable; nothing to re-seed from", playlist.get());
                return store.registry();
            }
        }
        Optional<SourceId> primary = settings.sources().stream().findFirst().map(EpgSource::id);
        if (primary.isEmpty()) {
            return List.of();
        }
        Optional<SourceEpg> document = store.normalized(primary.get());
        if (document.isEmpty()) {
            log.warn("The primary source {} has no stored data yet; nothing to re-seed from", primary.get());
            return List.of();
        }
        List<Channel> seeded = new ArrayList<>();
        for (SourceChannel channel : document.get().channels()) {
            seeded.add(new Channel(new ChannelId(channel.id()), channel.displayName(), channel.iconUrl(), null,
                    Channel.Origin.PRIMARY_SOURCE));
        }
        return seeded;
    }

    private static Set<ChannelId> ids(List<Channel> channels) {
        Set<ChannelId> ids = new LinkedHashSet<>();
        channels.forEach(channel -> ids.add(channel.id()));
        return ids;
    }

    /**
     * @param proposed the registry a re-seed would install
     * @param added ids the feed would start emitting
     * @param removed ids the feed would stop emitting, which breaks a DVR mapping
     */
    public record Preview(List<Channel> proposed, List<ChannelId> added, List<ChannelId> removed) {

        public Preview {
            proposed = proposed == null ? List.of() : List.copyOf(proposed);
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }

        /** A re-seed that would move no id is not worth confirming. */
        public boolean changesAnything() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }
}
