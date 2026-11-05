package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.ChannelMatching;
import com.anti.houseepg.domain.guide.policy.PriorityMerge;
import com.anti.houseepg.domain.guide.policy.SlotClaim;
import com.anti.houseepg.domain.guide.policy.SlotClaimable;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case: explain the merge for one channel over one window, provider by provider (design §6.6).
 *
 * <p>Every column answers "what does this provider contribute to the feed, and if nothing — why". The
 * window is an ordinary time range and an entry belongs to it when it overlaps it at all, so a
 * programme straddling an edge is shown rather than silently missing.
 *
 * <p>Nothing here decides the merge a second time. The fate of an entry comes from the same
 * {@link SlotClaim} walk the live {@link PriorityMerge#merge} runs, and the result column is that
 * merge's own output for this channel and this order. A preview that disagreed with the feed would be
 * worse than no preview, which is why the rule lives in one place (design §6.6).
 *
 * <p>Read-only: nothing is persisted and the applied order is only read, never changed.
 */
public class MergeBreakdown {

    private final GuideStore store;
    private final ChannelMatchStore matchStore;
    private final GuideSettings settings;

    public MergeBreakdown(GuideStore store, ChannelMatchStore matchStore, GuideSettings settings) {
        this.store = store;
        this.matchStore = matchStore;
        this.settings = settings;
    }

    /**
     * @param requested the draft order to walk, or empty for the applied one
     * @throws IllegalArgumentException when the requested order is not a permutation of the configured
     *     sources, because that would describe a merge that can never be applied
     */
    public Breakdown breakdown(ChannelId channelId, Slot window, Optional<List<SourceId>> requested) {
        Applied applied = appliedDay();
        List<SourceId> order = requested.orElse(applied.order());
        requirePermutation(order, applied.order());

        Optional<Channel> channel = applied.channel(channelId);
        if (channel.isEmpty()) {
            // The id is not a channel at all — not even a pass-through one — so there is nothing to
            // explain: the caller asked about a channel that does not exist, not about a quiet one.
            return new Breakdown(null, List.of(), new Result(List.of(), new Window(List.of(), List.of())));
        }

        // One column per provider, in the order being previewed: the draft order is what the panel
        // shows, so the columns have to follow it rather than the order currently applied.
        List<SourceEpg> documents = applied.inOrder(order);
        List<WindowEntry> entries = new ArrayList<>();
        for (SourceEpg document : documents) {
            Map<String, SourceChannel> declared = new LinkedHashMap<>();
            document.channels().forEach(sourceChannel -> declared.put(sourceChannel.id(), sourceChannel));
            for (SourceProgramme programme : document.programmes()) {
                if (!window.overlaps(programme.slot())
                        || !attachedTo(applied.match(), document.source(), programme.sourceChannelId(),
                                channelId)) {
                    continue;
                }
                entries.add(new WindowEntry(document.source(), programme,
                        declared.get(programme.sourceChannelId())));
            }
        }

        SlotClaim claim = SlotClaim.of(entries.stream()
                .map(entry -> new SlotClaimable(entry.source(), entry.programme().slot()))
                .toList(), order);

        // Index-aligned with entries: the walk answers per entry, a column answers per provider.
        List<Entry> fates = new ArrayList<>(entries.size());
        Map<SourceId, List<Slot>> wonBySource = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            WindowEntry entry = entries.get(index);
            SlotClaim.Outcome outcome = claim.outcomes().get(index);
            SourceId lostTo = outcome.conflictingEntry() < 0
                    ? null
                    : entries.get(outcome.conflictingEntry()).source();
            fates.add(new Entry(entry.programme(), outcome.fate(), lostTo,
                    outcome.won() ? displaced(entry, entries, claim) : List.of()));
            if (outcome.won()) {
                wonBySource.computeIfAbsent(entry.source(), key -> new ArrayList<>())
                        .add(entry.programme().slot());
            }
        }

        List<Column> columns = documents.stream()
                .map(document -> column(document, applied.match(), channelId, window, entries, fates,
                        wonBySource))
                .toList();

        List<Programme> served = PriorityMerge.merge(applied.match().channels(), documents,
                        applied.match().canonicalIds(), order)
                .programmes().stream()
                .filter(programme -> programme.channelId().equals(channelId))
                .toList();
        List<ResultEntry> result = served.stream()
                .map(programme -> new ResultEntry(programme, weakerThan(programme, entries, claim)))
                .toList();

        return new Breakdown(channel.orElseThrow(), columns,
                new Result(result, asWindow(window, served.stream().map(Programme::slot).toList())));
    }

    /** A field a programme does not carry while an entry it displaced did. */
    public record Weakness(String field, SourceId from) {
    }

    /**
     * @param channelId the source channel that matched this canonical channel, or null when the provider
     *     does not carry it
     * @param sourceChannelName that channel's own name, so the column can say which of its channels it is
     * @param carriesElsewhere the canonical channel this provider's data went to instead, so a column
     *     that contributes nothing can say where it went; null when the provider serves no other channel
     * @param entries the provider's entries inside the window, each with its fate
     * @param window what those entries fill when they win, and what stays empty either way
     */
    public record Column(SourceId source, String channelId, String sourceChannelName, String carriesElsewhere,
            List<Entry> entries, Window window) {

        public Column {
            entries = entries == null ? List.of() : List.copyOf(entries);
            window = window == null ? new Window(List.of(), List.of()) : window;
        }

        /**
         * Design §6.6: a silent column says which of the five cases it is. The order matters — a column
         * that lost every overlap is not the same as one that never carried the channel.
         */
        public String caseLabel() {
            if (channelId == null) {
                return carriesElsewhere == null ? "does not carry this channel" : "carries " + carriesElsewhere;
            }
            if (entries.isEmpty()) {
                return "nothing in the window";
            }
            return entries.stream().anyMatch(entry -> entry.fate() == SlotClaim.Fate.WON)
                    ? "won"
                    : "lost every overlap";
        }
    }

    /**
     * @param fate WON, LOST to a named overlap, or EXCLUDED because this order leaves its source out
     * @param lostTo the provider whose earlier entry had already claimed the slot, when it lost one
     * @param weaker the fields this entry carried and the entry that displaced it does not
     */
    public record Entry(SourceProgramme programme, SlotClaim.Fate fate, SourceId lostTo, List<Weakness> weaker) {

        public Entry {
            weaker = weaker == null ? List.of() : List.copyOf(weaker);
        }
    }

    /** One carried programme and the metadata the merge cost it. */
    public record ResultEntry(Programme programme, List<Weakness> weaker) {

        public ResultEntry {
            weaker = weaker == null ? List.of() : List.copyOf(weaker);
        }
    }

    public record Result(List<ResultEntry> entries, Window window) {

        public Result {
            entries = entries == null ? List.of() : List.copyOf(entries);
            window = window == null ? new Window(List.of(), List.of()) : window;
        }
    }

    /** A span inside the window a channel serves nothing for. */
    public record Gap(Slot slot) {
    }

    /**
     * What one provider (or the merge result) does with the channel over the window: the spans it fills
     * and the spans it leaves empty. Both are clipped to the window, so the two always add up to it and
     * a caller can draw them without deciding what the window is.
     *
     * @param coverage the won entries' spans, fused where they touch or overlap; empty when the window
     *     was contributed to nothing
     * @param gaps the remainder of the window; empty when it was given back in one piece
     */
    public record Window(List<Gap> coverage, List<Gap> gaps) {

        public Window {
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        public double coveredHours() {
            return hours(coverage);
        }

        public double gapHours() {
            return hours(gaps);
        }
    }

    /**
     * @param channel the canonical channel, or null when the id is not a channel at all
     * @param columns one per provider, in the applied order the walk used
     * @param result what the feed serves for this channel once the order is applied
     */
    public record Breakdown(Channel channel, List<Column> columns, Result result) {

        public Breakdown {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /**
     * One provider's part of the answer: the channel it contributed, whether its data went somewhere else
     * instead, every entry in the window with its fate, and the window those entries fill.
     */
    private static Column column(SourceEpg document, ChannelMatching.Match match, ChannelId channelId,
            Slot window, List<WindowEntry> entries, List<Entry> fates, Map<SourceId, List<Slot>> wonBySource) {
        SourceId source = document.source();
        SourceChannel carried = document.channels().stream()
                .filter(channel -> channelId.equals(match.canonicalIds().get(
                        new SourceChannelRef(source, channel.id()))))
                .findFirst()
                .orElse(null);
        String elsewhere = document.channels().stream()
                .map(channel -> match.canonicalIds().get(new SourceChannelRef(source, channel.id())))
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(channelId))
                .map(ChannelId::value)
                .findFirst()
                .orElse(null);
        List<Entry> mine = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).source().equals(source)) {
                mine.add(fates.get(index));
            }
        }
        return new Column(source, carried == null ? null : carried.id(),
                carried == null ? null : carried.displayName(), elsewhere, mine,
                asWindow(window, wonBySource.getOrDefault(source, List.of())));
    }

    /** True when this source channel took part in the merge for the channel being previewed. */
    private static boolean attachedTo(ChannelMatching.Match match, SourceId source, String sourceChannelId,
            ChannelId channelId) {
        return channelId.equals(match.canonicalIds().get(new SourceChannelRef(source, sourceChannelId)));
    }

    /** What a set of won entries fills inside the window, and what the window has left. */
    private static Window asWindow(Slot window, List<Slot> kept) {
        List<Gap> coverage = fused(kept.stream().map(slot -> clip(slot, window))
                        .filter(Objects::nonNull).toList()).stream()
                .map(Gap::new)
                .toList();
        List<Gap> gaps = new ArrayList<>();
        OffsetDateTime cursor = window.start();
        for (Gap covered : coverage) {
            if (cursor.isBefore(covered.slot().start())) {
                gaps.add(new Gap(new Slot(cursor, covered.slot().start())));
            }
            cursor = covered.slot().stop();
        }
        if (cursor.isBefore(window.stop())) {
            gaps.add(new Gap(new Slot(cursor, window.stop())));
        }
        return new Window(coverage, gaps);
    }

    /** The part of a slot inside the window; only slots overlapping it are ever passed here. */
    /**
     * The part of a span that lies inside the window, or {@code null} when none of it does. The result
     * column carries the channel's whole feed, so a programme from outside the window reaches this
     * method and must contribute nothing rather than an inverted span.
     */
    private static Slot clip(Slot slot, Slot window) {
        OffsetDateTime start = slot.start().isBefore(window.start()) ? window.start() : slot.start();
        OffsetDateTime stop = slot.stop().isAfter(window.stop()) ? window.stop() : slot.stop();
        return stop.isAfter(start) ? new Slot(start, stop) : null;
    }

    /** Sorted and fused: spans that touch or overlap become one, so a block is never drawn twice. */
    private static List<Slot> fused(List<Slot> slots) {
        List<Slot> sorted = new ArrayList<>(slots);
        sorted.sort(Comparator.comparing(Slot::start));
        List<Slot> fused = new ArrayList<>();
        for (Slot slot : sorted) {
            if (!fused.isEmpty() && !fused.get(fused.size() - 1).stop().isBefore(slot.start())) {
                Slot last = fused.remove(fused.size() - 1);
                fused.add(new Slot(last.start(), last.stop().isAfter(slot.stop()) ? last.stop() : slot.stop()));
            } else {
                fused.add(slot);
            }
        }
        return fused;
    }

    private static double hours(List<Gap> spans) {
        return spans.stream()
                .mapToLong(span -> Duration.between(span.slot().start(), span.slot().stop()).toMinutes())
                .sum() / 60.0;
    }

    /** The metadata a carried programme loses to the entry that claimed its slot. */
    private static List<Weakness> weakerThan(Programme winner, List<WindowEntry> entries, SlotClaim claim) {
        List<Weakness> weaker = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            WindowEntry other = entries.get(index);
            if (claim.outcomes().get(index).fate() != SlotClaim.Fate.LOST
                    || other.source().equals(winner.winningSource())
                    || !other.programme().slot().equals(winner.slot())) {
                continue;
            }
            Weakness weakness = lostField(winner.description(), winner.iconUrl(), winner.category(),
                    winner.episodeNumber(), other.programme(), other.source());
            if (weakness != null) {
                weaker.add(weakness);
            }
        }
        return weaker;
    }

    /** The metadata a winning entry kept, i.e. what the entries it displaced had and it does not. */
    private static List<Weakness> displaced(WindowEntry winner, List<WindowEntry> entries, SlotClaim claim) {
        List<Weakness> weaker = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            WindowEntry other = entries.get(index);
            if (claim.outcomes().get(index).fate() != SlotClaim.Fate.LOST
                    || other.source().equals(winner.source())
                    || !other.programme().slot().overlaps(winner.programme().slot())) {
                continue;
            }
            Weakness weakness = lostField(winner.programme().description(), winner.programme().iconUrl(),
                    winner.programme().category(), winner.programme().episodeNumber(), other.programme(),
                    other.source());
            if (weakness != null) {
                weaker.add(weakness);
            }
        }
        return weaker;
    }

    /**
     * The first field the feed serves that a displaced entry had and the winner does not. Blank is not
     * metadata, and the order is deliberate: the most visible loss is named first.
     */
    private static Weakness lostField(String description, String iconUrl, String category, String episode,
            SourceProgramme displaced, SourceId displacedSource) {
        if (blank(description) && !blank(displaced.description())) {
            return new Weakness("description", displacedSource);
        }
        if (blank(iconUrl) && !blank(displaced.iconUrl())) {
            return new Weakness("icon", displacedSource);
        }
        if (blank(category) && !blank(displaced.category())) {
            return new Weakness("category", displacedSource);
        }
        if (blank(episode) && !blank(displaced.episodeNumber())) {
            return new Weakness("episode", displacedSource);
        }
        return null;
    }

    /**
     * The state every preview starts from: the registry, one stored document per configured source, and
     * the match the merged feed was built with. Read once per call and deliberately not cached — the
     * preview must describe the state the feed is in now.
     */
    private Applied appliedDay() {
        List<SourceId> order = settings.sources().stream().map(EpgSource::id).toList();
        List<Channel> registry = store.registry();
        List<SourceEpg> documents = order.stream().map(store::normalized).flatMap(Optional::stream).toList();
        ChannelMatching.Match match = ChannelMatching.match(registry, documents, decisions());
        return new Applied(order, documents, match);
    }

    /** A preview order is a permutation of the configured sources, exactly as a reorder is. */
    private static void requirePermutation(List<SourceId> order, List<SourceId> configured) {
        if (order.size() != configured.size()
                || !new LinkedHashSet<>(order).equals(new LinkedHashSet<>(configured))) {
            throw new IllegalArgumentException("a preview order names exactly the configured sources; configured "
                    + configured + ", requested " + order);
        }
    }

    private Map<SourceChannelRef, ChannelMatch> decisions() {
        Map<SourceChannelRef, ChannelMatch> byRef = new LinkedHashMap<>();
        matchStore.decisions().forEach(decision -> byRef.put(decision.source(), decision));
        return byRef;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record WindowEntry(SourceId source, SourceProgramme programme, SourceChannel channel) {
    }

    /**
     * Everything the applied merge is made of, read together so the window and the columns cannot be
     * answered from two different states.
     */
    private record Applied(List<SourceId> order, List<SourceEpg> documents, ChannelMatching.Match match) {

        /** The channel being previewed: a registry one, or a pass-through one the match invented. */
        Optional<Channel> channel(ChannelId channelId) {
            return match.channels().stream().filter(channel -> channel.id().equals(channelId)).findFirst();
        }

        /** The stored documents in a given order; a source that published nothing has no column. */
        List<SourceEpg> inOrder(List<SourceId> order) {
            Map<SourceId, SourceEpg> bySource = new LinkedHashMap<>();
            documents.forEach(document -> bySource.put(document.source(), document));
            return order.stream().map(bySource::get).filter(Objects::nonNull).toList();
        }
    }
}
