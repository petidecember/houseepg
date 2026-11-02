package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** One plausible canonical channel for a source channel, with the evidence that put it there. */
    public record Candidate(Channel channel, MatchConfidence confidence, double score) {
    }

    /** How many candidates the review list shows: the best three, no more. */
    public static final int MAX_CANDIDATES = 3;

    /**
     * The similarity a fuzzy candidate needs before it is worth showing. Below this the suggestion is
     * noise, and a wrong suggestion costs a click while a wrong automatic match costs a channel id.
     */
    public static final double FUZZY_THRESHOLD = 0.6;

    /**
     * The ranked candidates for one source channel: the ladder's own hits first, then — only when
     * {@code fuzzy} is on — name-similarity suggestions filling the remaining slots.
     *
     * <p>This is a review aid. {@link #match} never calls it, so a suggestion can never become a match
     * without an explicit decision, and emitted channel ids stay put (ADR-0002).
     */
    public static List<Candidate> candidates(List<Channel> registry, SourceChannel sourceChannel,
            boolean fuzzy) {
        return ranker(registry).candidates(sourceChannel, fuzzy);
    }

    /** Builds the registry index once, for ranking many source channels in one review pass. */
    public static Ranker ranker(List<Channel> registry) {
        return new Ranker(Index.of(registry), List.copyOf(registry));
    }

    /** Ranks candidates for many source channels against one registry. */
    public static final class Ranker {

        private final Index index;
        private final List<Channel> registry;

        private Ranker(Index index, List<Channel> registry) {
            this.index = index;
            this.registry = registry;
        }

        public List<Candidate> candidates(SourceChannel sourceChannel, boolean fuzzy) {
            Map<String, Candidate> found = new LinkedHashMap<>();
            for (Candidate candidate : index.ladder(sourceChannel)) {
                found.putIfAbsent(candidate.channel().id().value(), candidate);
            }
            if (fuzzy && found.size() < MAX_CANDIDATES) {
                List<Candidate> scored = new ArrayList<>();
                for (Channel channel : registry) {
                    if (found.containsKey(channel.id().value())) {
                        continue;
                    }
                    double score = similarity(sourceChannel, channel);
                    if (score >= FUZZY_THRESHOLD) {
                        scored.add(new Candidate(channel, MatchConfidence.FUZZY_NAME, score));
                    }
                }
                scored.sort(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparing(candidate -> candidate.channel().id().value()));
                for (Candidate candidate : scored) {
                    if (found.size() >= MAX_CANDIDATES) {
                        break;
                    }
                    found.putIfAbsent(candidate.channel().id().value(), candidate);
                }
            }
            return found.values().stream().limit(MAX_CANDIDATES).toList();
        }
    }

    /**
     * How alike two channel names are, on a 0..1 scale: half the token overlap, half the edit similarity
     * of the joined tokens. Quality tags are dropped first, because a tag is identity rather than name
     * and a tag difference is what the ladder's quality tiebreak already handles.
     */
    static double similarity(SourceChannel sourceChannel, Channel channel) {
        String source = sourceChannel.displayName() == null || sourceChannel.displayName().isBlank()
                ? sourceChannel.id()
                : sourceChannel.displayName();
        return similarity(source, channel.label());
    }

    static double similarity(String left, String right) {
        List<String> leftTokens = tokens(left);
        List<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0;
        }
        double overlap = jaccard(leftTokens, rightTokens);
        double edit = editSimilarity(String.join("", leftTokens), String.join("", rightTokens));
        return (overlap + edit) / 2;
    }

    private static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : QualityTag.strip(raw).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double jaccard(List<String> left, List<String> right) {
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static double editSimilarity(String left, String right) {
        int longest = Math.max(left.length(), right.length());
        return longest == 0 ? 1 : 1 - (double) levenshtein(left, right) / longest;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
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
            return ordered(candidates, sourceChannel).getFirst();
        }

        /**
         * The same list {@link #pick} chooses from, ordered so the channel advertising the source's own
         * quality tag comes first and registry order decides the rest. The review list shows this order,
         * so a suggestion and the automatic answer cannot disagree about which candidate is best.
         */
        private List<Channel> ordered(List<Channel> candidates, SourceChannel sourceChannel) {
            Optional<QualityTag> wanted = QualityTag.of(sourceChannel.id())
                    .or(() -> QualityTag.of(sourceChannel.displayName()));
            if (wanted.isEmpty()) {
                return candidates;
            }
            List<Channel> preferred = new ArrayList<>();
            List<Channel> rest = new ArrayList<>();
            for (Channel candidate : candidates) {
                (advertises(candidate, wanted.get()) ? preferred : rest).add(candidate);
            }
            preferred.addAll(rest);
            return preferred;
        }

        /** Every ladder hit for one source channel, strongest evidence first, one entry per channel. */
        private List<Candidate> ladder(SourceChannel sourceChannel) {
            Map<String, Candidate> found = new LinkedHashMap<>();
            Channel exactId = byId.get(sourceChannel.id());
            if (exactId != null) {
                found.putIfAbsent(exactId.id().value(),
                        new Candidate(exactId, MatchConfidence.EXACT_ID, MatchConfidence.EXACT_ID.score()));
            }
            Channel exactName = byDisplayName.get(sourceChannel.displayName());
            if (exactName != null) {
                found.putIfAbsent(exactName.id().value(),
                        new Candidate(exactName, MatchConfidence.EXACT_NAME, MatchConfidence.EXACT_NAME.score()));
            }
            addKey(found, ChannelKey.of(sourceChannel.id()), sourceChannel, MatchConfidence.NORMALIZED_ID);
            addKey(found, ChannelKey.of(sourceChannel.displayName()), sourceChannel,
                    MatchConfidence.NORMALIZED_NAME);
            return List.copyOf(found.values());
        }

        private void addKey(Map<String, Candidate> found, ChannelKey key, SourceChannel sourceChannel,
                MatchConfidence confidence) {
            if (key.isEmpty()) {
                return;
            }
            List<Channel> candidates = byKey.get(key);
            if (candidates == null) {
                return;
            }
            for (Channel candidate : ordered(candidates, sourceChannel)) {
                found.putIfAbsent(candidate.id().value(),
                        new Candidate(candidate, confidence, confidence.score()));
            }
        }

        private static boolean advertises(Channel candidate, QualityTag tag) {
            return QualityTag.of(candidate.id().value()).filter(tag::equals).isPresent()
                    || QualityTag.of(candidate.displayName()).filter(tag::equals).isPresent();
        }
    }
}
