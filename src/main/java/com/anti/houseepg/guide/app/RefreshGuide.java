package com.anti.houseepg.guide.app;

import com.anti.houseepg.guide.app.port.ChannelAuthorityGateway;
import com.anti.houseepg.guide.app.port.EpgSource;
import com.anti.houseepg.guide.app.port.EpgSourceGateway;
import com.anti.houseepg.guide.model.Channel;
import com.anti.houseepg.guide.model.ChannelId;
import com.anti.houseepg.guide.model.SourceEpg;
import com.anti.houseepg.guide.model.SourceHealth;
import com.anti.houseepg.guide.model.SourceId;
import com.anti.houseepg.guide.policy.ChannelMatching;
import com.anti.houseepg.guide.policy.PriorityMerge;
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
 */
public class RefreshGuide {

    private static final Logger log = LoggerFactory.getLogger(RefreshGuide.class);

    private final GuideSettings settings;
    private final EpgSourceGateway epgSources;
    private final ChannelAuthorityGateway channelAuthority;
    private final Map<SourceId, SourceEpg> lastGood = new LinkedHashMap<>();

    public RefreshGuide(GuideSettings settings, EpgSourceGateway epgSources,
            ChannelAuthorityGateway channelAuthority) {
        this.settings = settings;
        this.epgSources = epgSources;
        this.channelAuthority = channelAuthority;
    }

    public RefreshResult refresh() {
        List<Channel> registry = readChannelAuthority();
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

        if (registry.isEmpty()) {
            registry = seedFromPrimarySource(read);
        }

        ChannelMatching.Match match = ChannelMatching.match(registry, read);
        List<SourceId> priority = read.stream().map(SourceEpg::source).toList();

        return new RefreshResult(
                PriorityMerge.merge(match.channels(), read, match.canonicalIds(), priority),
                health,
                Instant.now());
    }

    private List<Channel> readChannelAuthority() {
        var location = settings.playlistLocation();
        if (location.isEmpty()) {
            return List.of();
        }
        try {
            return channelAuthority.channels(location.get());
        } catch (RuntimeException e) {
            log.warn("Channel authority {} is unavailable: {}", location.get(), describe(e));
            return List.of();
        }
    }

    private static List<Channel> seedFromPrimarySource(List<SourceEpg> sources) {
        for (SourceEpg source : sources) {
            if (!source.channels().isEmpty()) {
                return source.channels().stream()
                        .map(channel -> new Channel(new ChannelId(channel.id()), channel.displayName(),
                                channel.iconUrl(), null, Channel.Origin.PRIMARY_SOURCE))
                        .toList();
            }
        }
        return List.of();
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
