package com.anti.houseepg.domain.guide.policy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
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

        // Grouped first: a slot is only ever contested by entries of the same canonical channel.
        Map<ChannelId, List<Candidate>> byChannel = new LinkedHashMap<>();
        for (SourceEpg source : sources) {
            for (SourceProgramme programme : source.programmes()) {
                ChannelId canonicalId = canonicalIds.get(new SourceChannelRef(source.source(), programme.sourceChannelId()));
                if (canonicalId == null) {
                    continue;
                }
                byChannel.computeIfAbsent(canonicalId, key -> new ArrayList<>())
                        .add(new Candidate(source.source(), programme));
            }
        }

        List<Programme> merged = new ArrayList<>();
        for (Map.Entry<ChannelId, List<Candidate>> entry : byChannel.entrySet()) {
            List<Candidate> candidates = entry.getValue();
            List<SlotClaimable> claims = candidates.stream()
                    .map(candidate -> new SlotClaimable(candidate.source(), candidate.programme().slot()))
                    .toList();
            // The rule itself is SlotClaim.of, so the merge preview walks exactly this walk.
            for (int winner : SlotClaim.of(claims, priority).winners()) {
                merged.add(toProgramme(entry.getKey(), candidates.get(winner)));
            }
        }

        merged.sort(Comparator.comparing((Programme programme) -> programme.channelId().value())
                .thenComparing(programme -> programme.slot().start()));
        return new Guide(channels, merged);
    }

    private static Programme toProgramme(ChannelId channelId, Candidate candidate) {
        SourceProgramme source = candidate.programme();
        return new Programme(channelId, source.slot(), candidate.source(), source.title(), source.description(),
                source.category(), source.episodeNumber(), source.iconUrl());
    }

    private record Candidate(SourceId source, SourceProgramme programme) {
    }
}
