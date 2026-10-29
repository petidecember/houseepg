package com.anti.houseepg.guide.policy;

import com.anti.houseepg.guide.model.Channel;
import com.anti.houseepg.guide.model.ChannelId;
import com.anti.houseepg.guide.model.Guide;
import com.anti.houseepg.guide.model.Programme;
import com.anti.houseepg.guide.model.Slot;
import com.anti.houseepg.guide.model.SourceEpg;
import com.anti.houseepg.guide.model.SourceId;
import com.anti.houseepg.guide.model.SourceProgramme;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The merge rule (ADR-0001). Per canonical channel, sources are walked in priority order and each
 * claims the slot of its programmes; a programme overlapping an already-claimed slot at all is
 * dropped entirely, never clipped. The winner is emitted verbatim: fields are never mixed.
 */
public final class PriorityMerge {

    private PriorityMerge() {
    }

    public static Guide merge(
            List<Channel> channels,
            List<SourceEpg> sources,
            Map<SourceChannelRef, ChannelId> canonicalIds,
            List<SourceId> priority) {

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
            List<Slot> claimed = new ArrayList<>();
            for (SourceId sourceId : priority) {
                List<Candidate> fromSource = entry.getValue().stream()
                        .filter(candidate -> candidate.source().equals(sourceId))
                        .sorted(Comparator.comparing(candidate -> candidate.programme().slot().start()))
                        .toList();
                for (Candidate candidate : fromSource) {
                    Slot slot = candidate.programme().slot();
                    if (claimed.stream().anyMatch(slot::overlaps)) {
                        continue;
                    }
                    claimed.add(slot);
                    merged.add(toProgramme(entry.getKey(), candidate));
                }
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
