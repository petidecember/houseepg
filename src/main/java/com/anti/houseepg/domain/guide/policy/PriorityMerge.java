package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.SlotLoss;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The merge rule (ADR-0001). Per canonical channel, sources are walked in priority order and each
 * claims the slot of its programmes; a programme overlapping an already-claimed slot at all is
 * dropped entirely, never clipped. The winner is emitted verbatim: fields are never mixed.
 *
 * <p>The walk itself is {@link SlotClaim#of}: a pure function of the entries and the order, so the
 * merge preview on the Priority page explains the very force the live merge applies (design §6.6).
 */
public final class PriorityMerge {

    private PriorityMerge() {
    }

    public static Guide merge(
            List<Channel> channels,
            List<SourceEpg> sources,
            Map<SourceChannelRef, ChannelId> canonicalIds,
            List<SourceId> priority) {

        Map<ChannelId, List<Candidate>> byChannel = grouped(sources, canonicalIds);

        List<Programme> merged = new ArrayList<>();
        for (Map.Entry<ChannelId, List<Candidate>> entry : byChannel.entrySet()) {
            for (Candidate winner : walk(entry.getValue(), priority).winners()) {
                merged.add(toProgramme(entry.getKey(), winner));
            }
        }

        merged.sort(Comparator.comparing((Programme programme) -> programme.channelId().value())
                .thenComparing(programme -> programme.slot().start()));
        return new Guide(channels, merged);
    }

    /** Grouped first: a slot is only ever contested by entries of the same canonical channel. */
    private static Map<ChannelId, List<Candidate>> grouped(List<SourceEpg> sources,
            Map<SourceChannelRef, ChannelId> canonicalIds) {
        Map<ChannelId, List<Candidate>> byChannel = new LinkedHashMap<>();
        for (SourceEpg source : sources) {
            for (SourceProgramme programme : source.programmes()) {
                ChannelId canonicalId = canonicalIds.get(
                        new SourceChannelRef(source.source(), programme.sourceChannelId()));
                if (canonicalId == null) {
                    continue;
                }
                byChannel.computeIfAbsent(canonicalId, key -> new ArrayList<>())
                        .add(new Candidate(source.source(), programme));
            }
        }
        return byChannel;
    }

    /**
     * The slot walk for one group of entries, with the rule applied by {@link SlotClaim#of} and nothing
     * else. {@link #merge} emits the winners; {@link #slotLosses} counts the losers of the same shape,
     * so a reported loss can never disagree with the guide.
     */
    private static Walk walk(List<Candidate> candidates, List<SourceId> priority) {
        List<SlotClaimable> claims = candidates.stream()
                .map(candidate -> new SlotClaimable(candidate.source(), candidate.programme().slot()))
                .toList();
        SlotClaim claim = SlotClaim.of(claims, priority);
        return new Walk(claim, candidates, claims);
    }

    /**
     * How many entries the slot rule drops, per source, over the whole guide. An entry whose channel
     * matched nothing is not dropped by the rule — it never reached it — so it is not counted here.
     *
     * <p>Counted one canonical channel at a time and then summed: an outcome's {@code conflictingEntry} is
     * an index into the walk that produced it, so flattening the walks first would make every group after
     * the first resolve its conflict against another channel's entry and misreport the reason class.
     */
    public static SlotLoss.BySource slotLosses(List<SourceEpg> sources,
            Map<SourceChannelRef, ChannelId> canonicalIds, List<SourceId> priority) {
        SlotLoss.BySource total = SlotLoss.BySource.none();
        for (Map.Entry<ChannelId, List<Candidate>> entry : grouped(sources, canonicalIds).entrySet()) {
            Walk walk = walk(entry.getValue(), priority);
            total = total.plus(walk.claim.lossesBySource(walk.claims));
        }
        return total;
    }

    private static Programme toProgramme(ChannelId channelId, Candidate candidate) {
        SourceProgramme source = candidate.programme();
        return new Programme(channelId, source.slot(), candidate.source(), source.title(), source.description(),
                source.category(), source.episodeNumber(), source.iconUrl());
    }

    private record Candidate(SourceId source, SourceProgramme programme) {
    }

    /** One group's walk: the rule's verdict plus the entries it judged, in the walk's own index order. */
    private record Walk(SlotClaim claim, List<Candidate> candidates, List<SlotClaimable> claims) {

        List<Candidate> winners() {
            return claim.winners().stream().map(candidates::get).toList();
        }
    }
}
