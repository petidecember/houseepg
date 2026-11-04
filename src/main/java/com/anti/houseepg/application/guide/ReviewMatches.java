package com.anti.houseepg.application.guide;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.ChannelMatching;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: the review list a human works through — one row per source channel that still needs a
 * decision, each carrying the ranked candidates the domain suggests (design §10).
 *
 * <p>The default filter is the point of the card: a row that is already curated, that matched on an
 * exact id or name, or that the matcher could suggest nothing for, is not worth a human's attention, so
 * it is not in the list. A row with nothing to choose between is not a decision — it is a source or
 * registry signal, and it is listed under {@code unmatched} instead. The list therefore shrinks as the
 * work gets done.
 */
public class ReviewMatches {

    private static final Logger log = LoggerFactory.getLogger(ReviewMatches.class);

    /** How far ahead of the runner-up a fuzzy suggestion must be before it counts as unambiguous. */
    public static final double FUZZY_MARGIN = 0.1;

    private final InspectGuide inspectGuide;

    public ReviewMatches(InspectGuide inspectGuide) {
        this.inspectGuide = inspectGuide;
    }

    public Review review(boolean fuzzy, Filter filter) {
        InspectGuide.Registry registry = inspectGuide.registry();
        ChannelMatching.Ranker ranker = ChannelMatching.ranker(registry.channels());

        List<Row> rows = new ArrayList<>();
        int autoResolvable = 0;
        int ambiguous = 0;
        for (ChannelMatch match : registry.matches()) {
            if (!filter.accepts(match)) {
                continue;
            }
            SourceChannel sourceChannel = registry.sourceChannels().get(match.source());
            List<ChannelMatching.Candidate> candidates = ranker.candidates(
                    sourceChannel == null
                            ? new SourceChannel(match.source().channelId(), null, null)
                            : sourceChannel,
                    fuzzy);
            if (filter.requiresCandidates() && candidates.isEmpty()) {
                continue;
            }
            rows.add(new Row(match, sourceChannel, candidates));
            if (fuzzy && !isExact(match.confidence())) {
                if (isUnambiguous(candidates)) {
                    autoResolvable++;
                } else if (isAmbiguous(candidates)) {
                    ambiguous++;
                }
            }
        }

        // The measurement the fuzzy-promotion decision depends on: how many rows a fuzzy tier would
        // have taken off this list, and how many of those it would have had to guess between.
        log.info("review: {} rows, {} would be auto-resolved by fuzzy, {} ambiguous",
                rows.size(), autoResolvable, ambiguous);
        return new Review(rows, registry.channels(), autoResolvable, ambiguous);
    }

    private static boolean isExact(MatchConfidence confidence) {
        return confidence == MatchConfidence.EXACT_ID || confidence == MatchConfidence.EXACT_NAME;
    }

    private static boolean isUnambiguous(List<ChannelMatching.Candidate> candidates) {
        if (candidates.isEmpty() || candidates.getFirst().confidence() != MatchConfidence.FUZZY_NAME) {
            return false;
        }
        return candidates.size() == 1
                || candidates.getFirst().score() - candidates.get(1).score() >= FUZZY_MARGIN;
    }

    private static boolean isAmbiguous(List<ChannelMatching.Candidate> candidates) {
        return candidates.size() > 1
                && candidates.getFirst().score() - candidates.get(1).score() < FUZZY_MARGIN;
    }

    /** Which rows the list shows. */
    public enum Filter {

        /**
         * Not curated, not an exact id or name match, and carrying at least one candidate: the rows
         * where there is a value to choose between.
         */
        NEEDS_DECISION,
        ALL,
        CURATED,
        UNMATCHED;

        public static Filter parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NEEDS_DECISION;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "curated" -> CURATED;
                case "unmatched" -> UNMATCHED;
                case "needs-decision", "needsdecision" -> NEEDS_DECISION;
                default -> throw new IllegalArgumentException("unknown filter: " + raw);
            };
        }

        boolean accepts(ChannelMatch match) {
            return switch (this) {
                case ALL -> true;
                case CURATED -> match.isCurated();
                case UNMATCHED -> match.state() == MatchState.UNMATCHED;
                case NEEDS_DECISION -> !match.isCurated() && !isExact(match.confidence());
            };
        }

        /**
         * True when a row that passed {@link #accepts} is only worth showing once it has a candidate.
         *
         * <p>A row the matcher could suggest nothing for cannot be decided from this list: there is no
         * value to confirm and none to correct. It belongs under {@code unmatched}, so this filter drops
         * it — but only the ranking knows whether there is a candidate, so the question is asked after
         * the candidates are ranked, and the filters that do not ask it never rank a row they would drop.
         *
         * <p>With {@code fuzzy} on, a suggestion above the similarity threshold counts as that candidate,
         * which is exactly what the switch is for: it turns rows that could not be decided into rows that
         * can.
         */
        boolean requiresCandidates() {
            return this == NEEDS_DECISION;
        }
    }

    /**
     * @param match the decision the merge is using for this source channel
     * @param sourceChannel the source's own declaration, null when its normalized document is gone
     * @param candidates the ranked suggestions, at most {@link ChannelMatching#MAX_CANDIDATES}
     */
    public record Row(ChannelMatch match, SourceChannel sourceChannel,
            List<ChannelMatching.Candidate> candidates) {

        public Row {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * @param rows the filtered rows, in the order the match store returned them
     * @param channels the whole registry, for the "all channels" group of the dropdown
     * @param autoResolvable rows a fuzzy tier would have taken off this list unambiguously
     * @param ambiguous rows where two candidates are within {@link #FUZZY_MARGIN} of each other
     */
    public record Review(List<Row> rows, List<Channel> channels, int autoResolvable, int ambiguous) {

        public Review {
            rows = rows == null ? List.of() : List.copyOf(rows);
            channels = channels == null ? List.of() : List.copyOf(channels);
        }
    }
}
