package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.config.port.GuideReMerge;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: accept every match whose evidence is not in doubt, in one write and one re-merge.
 *
 * <p>Only an exact id or an exact name qualifies. A normalized match is where the quality-tag tiebreak
 * already chose for the user, and a fuzzy suggestion is a guess; accepting either in bulk would turn a
 * click into a permanent wrong mapping, because a curated row survives every later automatic pass.
 *
 * <p>The service computes the set itself rather than trusting a list from the UI, so the preview and the
 * apply are the same computation and the definition of "exact" stays in the domain.
 */
public class BulkAcceptMatches {

    private static final Logger log = LoggerFactory.getLogger(BulkAcceptMatches.class);

    private final ChannelMatchStore matchStore;
    private final GuideReMerge reMerge;

    public BulkAcceptMatches(ChannelMatchStore matchStore, GuideReMerge reMerge) {
        this.matchStore = matchStore;
        this.reMerge = reMerge;
    }

    /**
     * @param dryRun true to report what would be accepted without writing anything
     */
    public Result accept(boolean dryRun) {
        List<ChannelMatch> accepted = matchStore.decisions().stream()
                .filter(match -> !match.isCurated())
                .filter(match -> isExact(match.confidence()))
                .map(match -> ChannelMatch.confirmed(match.source(), match.canonicalId(), match.confidence()))
                .toList();

        if (!dryRun && !accepted.isEmpty()) {
            matchStore.decideAll(accepted);
            reMerge.rematerialize();
        }
        log.info("bulk accept: {} row(s) {}, {} re-merge", accepted.size(),
                dryRun ? "would be curated" : "curated", dryRun || accepted.isEmpty() ? "no" : "one");
        return new Result(accepted.size(), accepted, dryRun);
    }

    private static boolean isExact(MatchConfidence confidence) {
        return confidence == MatchConfidence.EXACT_ID || confidence == MatchConfidence.EXACT_NAME;
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
