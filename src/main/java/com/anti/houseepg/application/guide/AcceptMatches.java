package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.config.port.GuideReMerge;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Decision;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: accept the decisions the review list is showing, in one write and one re-merge.
 *
 * <p>The set comes from the caller, because only the caller knows what the user can see. A button that
 * computed its own set from the stored matches — as its predecessor did — writes rows that are not on
 * screen. What stays here is the validation, so a request cannot invent a channel the feed cannot emit,
 * cannot answer the same row twice, and cannot overwrite a decision the user already made.
 *
 * <p>Each accepted row is written exactly as a single "Save decision" writes it: the state is derived from
 * the row's current target, and the confidence is the one the automatic match already carried, so the two
 * paths cannot disagree about what a decision means.
 */
public class AcceptMatches {

    private static final Logger log = LoggerFactory.getLogger(AcceptMatches.class);

    private final ChannelMatchStore matchStore;
    private final GuideStore guideStore;
    private final GuideReMerge reMerge;

    public AcceptMatches(ChannelMatchStore matchStore, GuideStore guideStore, GuideReMerge reMerge) {
        this.matchStore = matchStore;
        this.guideStore = guideStore;
        this.reMerge = reMerge;
    }

    /**
     * @param wanted the rows the user saw, in their order; duplicates for one source channel collapse, the
     *     last one winning, so one request cannot write two answers to the same row
     * @param dryRun true to report the same set without writing anything
     * @throws IllegalArgumentException when a wanted channel is not in the registry
     */
    public Result accept(List<Decision> wanted, boolean dryRun) {
        List<ChannelMatch> accepted = new ArrayList<>();
        for (Decision wanted1 : deduplicated(wanted)) {
            Optional<ChannelMatch> stored = current(wanted1.source(), wanted1.sourceChannelId());
            if (stored.filter(ChannelMatch::isCurated).isPresent()) {
                continue;
            }
            requireKnown(wanted1.canonicalId());
            accepted.add(asMatch(wanted1, stored));
        }

        if (!dryRun && !accepted.isEmpty()) {
            matchStore.decideAll(accepted);
            reMerge.rematerialize();
        }
        log.info("accept matches: {} row(s) {}, {} re-merge", accepted.size(),
                dryRun ? "would be curated" : "curated", dryRun || accepted.isEmpty() ? "no" : "one");
        return new Result(accepted.size(), accepted, dryRun);
    }

    /** One answer per source channel: naming a row twice must not write twice. */
    private static List<Decision> deduplicated(List<Decision> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return List.of();
        }
        Map<String, Decision> byRow = new LinkedHashMap<>();
        wanted.forEach(decision -> byRow.put(decision.source() + "|" + decision.sourceChannelId(), decision));
        return List.copyOf(byRow.values());
    }

    /**
     * A bulk accept carries no matching evidence of its own: it keeps the confidence of the row it confirms
     * and falls back to the weakest non-zero score when it points somewhere new — the rule a single decision
     * already follows.
     */
    private static ChannelMatch asMatch(Decision wanted, Optional<ChannelMatch> stored) {
        SourceChannelRef source = new SourceChannelRef(wanted.source(), wanted.sourceChannelId());
        boolean sameAsCurrent = stored
                .map(match -> match.canonicalId().equals(wanted.canonicalId()))
                .orElse(false);
        if (!sameAsCurrent) {
            return ChannelMatch.corrected(source, wanted.canonicalId(), MatchConfidence.NORMALIZED_NAME);
        }
        return ChannelMatch.confirmed(source, wanted.canonicalId(), stored.get().confidence());
    }

    private Optional<ChannelMatch> current(SourceId source, String sourceChannelId) {
        SourceChannelRef ref = new SourceChannelRef(source, sourceChannelId);
        return matchStore.decisions().stream().filter(match -> match.source().equals(ref)).findFirst();
    }

    /** A decision always points at a channel the feed can actually emit. */
    private void requireKnown(ChannelId canonicalId) {
        boolean known = guideStore.registry().stream().anyMatch(channel -> channel.id().equals(canonicalId));
        if (!known) {
            throw new IllegalArgumentException("the registry has no channel with id " + canonicalId.value());
        }
    }

    /**
     * @param count how many rows the call accepted, or would accept on a dry run
     * @param rows the rows themselves, so the preview can name them
     * @param dryRun whether anything was written
     */
    public record Result(int count, List<ChannelMatch> rows, boolean dryRun) {

        public Result {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
