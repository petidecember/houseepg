package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.ChannelAuthorityGateway;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.EpgSourceGateway;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SlotLoss;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.ChannelDeclarations;
import com.anti.houseepg.domain.guide.policy.ChannelMatching;
import com.anti.houseepg.domain.guide.policy.PriorityMerge;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: refresh the sources that are due, match their channels onto the registry, merge, and
 * materialize the result (design §7, §8).
 *
 * <p>Refreshes are serialized: a scheduled tick and a manual "refresh now" must never write the
 * layers at the same time. A source that fails keeps its last good normalized data, is marked
 * unhealthy, and the materialized guide keeps serving what it had.
 */
public class RefreshGuide {

    /**
     * A stored registry that the primary source declares past is the signature of the old seed's truncation
     * (HOU-32): the ids exist upstream but never landed, so nothing was ever attached to them. Said once per
     * process, with the remedy, because the condition persists until an operator acts and a scheduler tick
     * would otherwise repeat it every minute.
     */
    private final AtomicBoolean shortRegistryReported = new AtomicBoolean();

    private static final Logger log = LoggerFactory.getLogger(RefreshGuide.class);

    private final GuideSettings settings;
    private final EpgSourceGateway epgSources;
    private final ChannelAuthorityGateway channelAuthority;
    private final ChannelMatchStore matchStore;
    private final GuideStore store;
    private final Clock clock;
    private final Map<SourceId, SourceEpg> lastGood = new LinkedHashMap<>();

    public RefreshGuide(GuideSettings settings, EpgSourceGateway epgSources,
            ChannelAuthorityGateway channelAuthority, ChannelMatchStore matchStore, GuideStore store) {
        this(settings, epgSources, channelAuthority, matchStore, store, Clock.systemUTC());
    }

    /**
     * The clock is a parameter so a test can pin it: retention prunes everything that ended before
     * {@code now - retention}, so a test that reads the wall clock starts failing once the fixtures age
     * out of the window.
     */
    public RefreshGuide(GuideSettings settings, EpgSourceGateway epgSources,
            ChannelAuthorityGateway channelAuthority, ChannelMatchStore matchStore, GuideStore store,
            Clock clock) {
        this.settings = settings;
        this.epgSources = epgSources;
        this.channelAuthority = channelAuthority;
        this.matchStore = matchStore;
        this.store = store;
        this.clock = clock;
    }

    /** The configured sources, so the manual-refresh endpoint can recognise a path variable. */
    public List<EpgSource> sources() {
        return settings.sources();
    }

    /** Manual refresh: re-fetch every source now. */
    public synchronized RefreshResult refresh() {
        return refreshAll(settings.sources().stream().map(EpgSource::id).toList());
    }

    /** Manual refresh of one source; the others contribute their stored data. */
    public synchronized RefreshResult refreshSource(SourceId source) {
        return refreshAll(List.of(source));
    }

    /** Scheduled refresh: only the sources whose interval elapsed are fetched. */
    public synchronized RefreshResult refreshDue(Instant now) {
        List<SourceId> due = settings.sources().stream()
                .filter(source -> due(source, now))
                .map(EpgSource::id)
                .toList();
        return refreshAll(due);
    }

    private RefreshResult refreshAll(Collection<SourceId> forced) {
        Instant now = clock.instant();
        Set<SourceId> force = Set.copyOf(forced);

        List<SourceEpg> documents = new ArrayList<>();
        List<SourceHealth> health = new ArrayList<>();
        Map<SourceId, SourceHealth> storedHealth = new LinkedHashMap<>();
        store.health().forEach(entry -> storedHealth.put(entry.source(), entry));

        for (EpgSource source : settings.sources()) {
            boolean fetch = force.contains(source.id()) || due(source, now);
            SourceEpg document = fetch
                    ? fetch(source, health)
                    : fromStore(source, health, storedHealth.get(source.id()));
            if (document != null) {
                documents.add(document);
            }
        }

        Instant cutoff = now.minus(settings.retention());
        documents = retained(documents, cutoff);

        List<Channel> registry = registry(documents);
        ChannelMatching.Match match = ChannelMatching.match(registry, documents, knownDecisions());
        saveAuto(match.matches());
        documents.forEach(document -> store.saveNormalized(document, match.canonicalIds()));

        List<SourceId> priority = documents.stream().map(SourceEpg::source).toList();
        Guide guide = PriorityMerge.merge(match.channels(), documents, match.canonicalIds(), priority);

        // What the walk dropped, from the walk itself, so the reported loss cannot disagree with the
        // guide this same pass just built (design §6.3).
        SlotLoss.BySource slotLosses = PriorityMerge.slotLosses(documents, match.canonicalIds(), priority);
        store.saveSlotLosses(slotLosses);

        store.prune(cutoff);
        store.saveMergedGuide(guide, now);
        return new RefreshResult(guide, health, now, match.matches(), slotLosses);
    }

    /** Design §7 retention: a programme that ended before the cutoff is not part of the guide at all. */
    private static List<SourceEpg> retained(List<SourceEpg> documents, Instant cutoff) {
        List<SourceEpg> retained = new ArrayList<>();
        for (SourceEpg document : documents) {
            List<SourceProgramme> kept = document.programmes().stream()
                    .filter(programme -> !programme.slot().stop().toInstant().isBefore(cutoff))
                    .toList();
            retained.add(new SourceEpg(document.source(), document.channels(), kept));
        }
        return retained;
    }

    /** Fetches one source; on failure its last good normalized data still contributes (design §8). */
    private SourceEpg fetch(EpgSource source, List<SourceHealth> health) {
        try {
            SourceEpg document = epgSources.read(source);
            if (document.isEmpty()) {
                throw new IllegalStateException("source carries no channels and no programmes");
            }
            SourceHealth ok = SourceHealth.ok(source.id(), source.displayName(), document.programmes().size());
            health.add(ok);
            store.saveHealth(ok);
            return document;
        } catch (RuntimeException e) {
            String error = describe(e);
            Optional<SourceEpg> fallback = store.normalized(source.id());
            SourceHealth status = fallback
                    .map(document -> SourceHealth.stale(source.id(), source.displayName(),
                            document.programmes().size(), error))
                    .orElseGet(() -> SourceHealth.failed(source.id(), source.displayName(), error));
            health.add(status);
            store.saveHealth(status);
            log.warn("Source {} is unavailable: {}", source.id(), error);
            return fallback.orElse(null);
        }
    }

    private SourceEpg fromStore(EpgSource source, List<SourceHealth> health, SourceHealth previous) {
        Optional<SourceEpg> document = store.normalized(source.id());
        if (document.isEmpty()) {
            health.add(previous != null ? previous
                    : SourceHealth.failed(source.id(), source.displayName(), "not refreshed yet"));
            return null;
        }
        health.add(previous != null ? previous
                : SourceHealth.stale(source.id(), source.displayName(), document.get().programmes().size(),
                        "not refreshed in this pass"));
        return document.get();
    }

    private boolean due(EpgSource source, Instant now) {
        return store.rawSnapshot(source.id())
                .map(snapshot -> !snapshot.fetchedAt().plus(source.refreshInterval()).isAfter(now))
                .orElse(true);
    }

    /**
     * The playlist is the authority when one is configured. Its id set is persisted, so a failed read
     * keeps the ids the DVR already knows; without a playlist the registry is seeded once from the
     * top-priority source and then frozen (design §6.4, ADR-0002).
     */
    private List<Channel> registry(List<SourceEpg> documents) {
        List<Channel> stored = store.registry();
        var location = settings.playlistLocation();
        if (location.isPresent()) {
            List<Channel> fromPlaylist = readPlaylist(location.get());
            if (!fromPlaylist.isEmpty()) {
                List<Channel> authoritative = collapsed(fromPlaylist, "the playlist");
                store.saveRegistry(authoritative);
                return authoritative;
            }
            return stored;
        }
        if (!stored.isEmpty()) {
            reportShortRegistry(stored, documents);
            return stored;
        }
        List<Channel> seeded = collapsed(seedFromPrimarySource(documents), "the primary source");
        if (!seeded.isEmpty()) {
            store.saveRegistry(seeded);
        }
        return seeded;
    }

    /**
     * Names a stored registry that the primary source declares past, once per process. Ids missing from the
     * registry are emitted only as pass-through and can never be matched, so the review list fills with rows
     * that describe a truncated seed rather than a matcher failure (HOU-32).
     */
    private void reportShortRegistry(List<Channel> stored, List<SourceEpg> documents) {
        if (shortRegistryReported.getAndSet(true)) {
            return;
        }
        List<Channel> declared = collapsed(seedFromPrimarySource(documents), "the primary source");
        List<ChannelId> missing = ChannelDeclarations.missingFrom(stored, declared);
        if (missing.isEmpty()) {
            return;
        }
        log.warn("The stored registry holds {} channel(s) while the primary source declares {}; {} id(s) "
                + "cannot be matched until it is re-seeded: {}. Re-seed with POST /api/registry/reseed "
                + "(preview it with GET first): emitted ids may change and a DVR mapping may need redoing",
                stored.size(), declared.size(), missing.size(), missing);
    }

    /**
     * Resolves the declaration list to one entry per id, and says out loud what it dropped: a provider that
     * repeats itself used to shrink the registry silently, because the store's write failed on the repeat.
     */
    private static List<Channel> collapsed(List<Channel> declared, String authority) {
        ChannelDeclarations.Collapse collapse = ChannelDeclarations.distinctById(declared);
        if (collapse.collapsedAnything()) {
            log.warn("{} declares {} channel id(s) more than once ({}); keeping the first declaration of each, "
                    + "so {} declaration(s) become {} channel(s)", authority, distinctIds(collapse.duplicates()),
                    collapse.duplicates(), declared.size(), collapse.channels().size());
        }
        return collapse.channels();
    }

    private static List<ChannelId> distinctIds(List<ChannelId> ids) {
        return ids.stream().distinct().toList();
    }

    private List<Channel> readPlaylist(String location) {
        try {
            List<Channel> channels = channelAuthority.channels(location);
            if (channels.isEmpty()) {
                log.warn("Channel authority {} returned no channels; keeping the stored registry", location);
            }
            return channels;
        } catch (RuntimeException e) {
            log.warn("Channel authority {} is unavailable: {}", location, describe(e));
            return List.of();
        }
    }

    /** Only the configured top-priority source may seed the registry, never whichever one happens to be up. */
    private List<Channel> seedFromPrimarySource(List<SourceEpg> documents) {
        SourceId primary = settings.sources().isEmpty() ? null : settings.sources().getFirst().id();
        return documents.stream()
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

    private static String describe(RuntimeException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
