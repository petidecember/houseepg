package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.config.port.GuideReMerge;
import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.util.Optional;

/**
 * Use case: a human confirms or corrects the channel one source channel was attached to (design §10).
 *
 * <p>A curated row survives every later automatic pass, so this is the only way a wrong match is fixed
 * for good. The guide is re-merged immediately, from the stored layers, so the review list and the feed
 * agree before the next scheduled refresh.
 */
public class DecideMatch {

    private final GuideStore guideStore;
    private final ChannelMatchStore matchStore;
    private final GuideReMerge reMerge;

    public DecideMatch(GuideStore guideStore, ChannelMatchStore matchStore, GuideReMerge reMerge) {
        this.guideStore = guideStore;
        this.matchStore = matchStore;
        this.reMerge = reMerge;
    }

    /**
     * @param corrected true when the human overrules the automatic match, false when they confirm it
     * @throws IllegalArgumentException when the registry has no such canonical channel, or the ref is
     *     incomplete; a decision always points at a channel the feed can actually emit
     */
    public ChannelMatch decide(SourceChannelRef source, ChannelId canonicalId, boolean corrected) {
        boolean known = guideStore.registry().stream().anyMatch(channel -> channel.id().equals(canonicalId));
        if (!known) {
            throw new IllegalArgumentException("the registry has no channel with id " + canonicalId.value());
        }
        MatchConfidence confidence = confidence(source, canonicalId);
        ChannelMatch decision = corrected
                ? ChannelMatch.corrected(source, canonicalId, confidence)
                : ChannelMatch.confirmed(source, canonicalId, confidence);
        matchStore.decide(decision);
        reMerge.rematerialize();
        return decision;
    }

    /**
     * A human decision carries no matching evidence of its own: it keeps the confidence of the row it
     * confirms, and falls back to the weakest non-zero score when it points somewhere new. Matching never
     * branches on the score, so this only affects how the review list is ordered.
     */
    private MatchConfidence confidence(SourceChannelRef source, ChannelId canonicalId) {
        Optional<ChannelMatch> previous = matchStore.decisions().stream()
                .filter(match -> match.source().equals(source))
                .findFirst();
        return previous.filter(match -> match.canonicalId().equals(canonicalId))
                .map(ChannelMatch::confidence)
                .orElse(MatchConfidence.NORMALIZED_NAME);
    }
}
