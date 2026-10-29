package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.ChannelAuthorityGateway;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.EpgSourceGateway;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.ChannelMatching;
import com.anti.houseepg.domain.guide.policy.PriorityMerge;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: read every source in priority order, match its channels onto the registry, and merge. A
 * source that fails keeps contributing its last good document, so the served guide never gets worse
 * because of a network error (design §8).
 *
 * <p>The registry is the authority and is kept across refreshes: a routine refresh never re-seeds it,
 * because that would change emitted ids under the DVR (design §6.4, ADR-0002).
 */
public class RefreshGuide {

    private static final Logger log = LoggerFactory.getLogger(RefreshGuide.class);

    private final GuideSettings settings;
    private final EpgSourceGateway epgSources;
    private final ChannelAuthorityGateway channelAuthority;
    private final ChannelMatchStore matchStore;
    private final Map<SourceId, SourceEpg> lastGood = new LinkedHashMap<>();
    private List<Channel> registry = List.of();

    public RefreshGuide(GuideSettings settings, EpgSourceGateway epgSources,
            ChannelAuthorityGateway channelAuthority, ChannelMatchStore matchStore) {
        this.settings = settings;
        this.epgSources = epgSources;
        this.channelAuthority = channelAuthority;
        this.matchStore = matchStore;
    }

    public RefreshResult refresh() {
        List<SourceEpg> read = new ArrayList<>();
        List<SourceHealth> health = new ArrayList<>();

        for (EpgSource source : settings.sources()) {
            try {
                SourceEpg document = epgSources.read(source);
                if (document.isEmpty()) {
                    throw new IllegalStateException("source carries no channels and no programmes");
                }
                rememberLastGood(document);
                read.add(document);
                health.add(SourceHealth.ok(source.id(), source.displayName(), document.programmes().size()));
            } catch (RuntimeException e) {
                SourceEpg fallback = lastGood.get(source.id());
                if (fallback == null) {
                    health.add(SourceHealth.failed(source.id(), source.displayName(), describe(e)));
                } else {
                    read.add(fallback);
                    health.add(SourceHealth.stale(source.id(), source.displayName(),
                            fallback.programmes().size(), describe(e)));
                }
                log.warn("Source {} is unavailable: {}", source.id(), describe(e));
            }
        }

        ChannelMatching.Match match = ChannelMatching.match(seededRegistry(read), read, knownDecisions());
        saveAuto(match.matches());

        List<SourceId> priority = read.stream().map(SourceEpg::source).toList();
        return new RefreshResult(
                PriorityMerge.merge(match.channels(), read, match.canonicalIds(), priority),
                health,
                Instant.now(),
                match.matches());
    }

    /**
     * The playlist is the authority when one is configured; when it is not, the registry is seeded
     * once from the top-priority source and then frozen. Either way a failed read keeps the registry
     * the previous refresh used, so ids cannot drift because of a transient outage.
     */
    private List<Channel> seededRegistry(List<SourceEpg> read) {
        var location = settings.playlistLocation();
        if (location.isPresent()) {
            List<Channel> fromPlaylist = readPlaylist(location.get());
            if (!fromPlaylist.isEmpty()) {
                registry = fromPlaylist;
            }
            return registry;
        }
        if (registry.isEmpty()) {
            registry = seedFromPrimarySource(read);
        }
        return registry;
    }

    private List<Channel> readPlaylist(String location) {
        try {
            List<Channel> channels = channelAuthority.channels(location);
            if (channels.isEmpty()) {
                log.warn("Channel authority {} returned no channels; keeping the current registry", location);
            }
            return channels;
        } catch (RuntimeException e) {
            log.warn("Channel authority {} is unavailable: {}", location, describe(e));
            return List.of();
        }
    }

    /** Only the configured top-priority source may seed the registry, never whichever one happens to be up. */
    private List<Channel> seedFromPrimarySource(List<SourceEpg> read) {
        SourceId primary = settings.sources().isEmpty() ? null : settings.sources().getFirst().id();
        return read.stream()
                .filter(document -> document.source().equals(primary))
                .findFirst()
                .map(document -> document.channels().stream()
                        .map(channel -> new Channel(new ChannelId(channel.id()), channel.displayName(),
                                channel.iconUrl(), null, Channel.Origin.PRIMARY_SOURCE))
                        .toList())
                .orElse(List.of());
    }

    private Map<SourceChannelRef, ChannelMatch> knownDecisions() {
        Map<SourceChannelRef, ChannelMatch> decisions = new LinkedHashMap<>();
        for (ChannelMatch match : matchStore.decisions()) {
            decisions.put(match.source(), match);
        }
        return decisions;
    }

    /** An automatic pass never rewrites a curated row; only an explicit decision may do that. */
    private void saveAuto(List<ChannelMatch> matches) {
        List<ChannelMatch> automatic = matches.stream().filter(match -> !match.isCurated()).toList();
        if (!automatic.isEmpty()) {
            matchStore.saveAuto(automatic);
        }
    }

    private void rememberLastGood(SourceEpg document) {
        lastGood.put(document.source(), document);
    }

    private static String describe(RuntimeException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
