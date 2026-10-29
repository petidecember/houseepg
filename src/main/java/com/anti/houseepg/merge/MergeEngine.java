package com.anti.houseepg.merge;

import com.anti.houseepg.domain.MergedProgramme;
import com.anti.houseepg.domain.Programme;
import com.anti.houseepg.domain.SourceChannelKey;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-channel timeline claim. Sources are walked in priority order and each claims the interval of its
 * programmes; a programme that overlaps an already-claimed interval at all is dropped entirely, never
 * clipped (ADR-0001). The winner is emitted verbatim — fields are never mixed.
 */
public final class MergeEngine {

    private MergeEngine() {
    }

    public static List<MergedProgramme> merge(
            List<String> sourcePriority,
            List<Programme> programmes,
            Map<SourceChannelKey, String> canonicalIdBySourceChannel) {

        Map<String, List<Programme>> byCanonicalChannel = new LinkedHashMap<>();
        for (Programme programme : programmes) {
            String canonicalId =
                    canonicalIdBySourceChannel.get(new SourceChannelKey(programme.sourceId(), programme.sourceChannelId()));
            if (canonicalId == null) {
                continue;
            }
            byCanonicalChannel.computeIfAbsent(canonicalId, key -> new ArrayList<>()).add(programme);
        }

        List<MergedProgramme> merged = new ArrayList<>();
        for (Map.Entry<String, List<Programme>> entry : byCanonicalChannel.entrySet()) {
            List<Interval> claimed = new ArrayList<>();
            List<MergedProgramme> forChannel = new ArrayList<>();

            for (String sourceId : sourcePriority) {
                List<Programme> fromSource = entry.getValue().stream()
                        .filter(programme -> programme.sourceId().equals(sourceId))
                        .sorted(Comparator.comparing(Programme::start))
                        .toList();
                for (Programme programme : fromSource) {
                    if (overlapsAny(claimed, programme)) {
                        continue;
                    }
                    claimed.add(new Interval(programme.start(), programme.stop()));
                    forChannel.add(new MergedProgramme(entry.getKey(), programme.start(), programme.stop(), sourceId,
                            programme.title(), programme.description(), programme.category(),
                            programme.episodeNum(), programme.iconUrl()));
                }
            }

            forChannel.sort(Comparator.comparing(MergedProgramme::start));
            merged.addAll(forChannel);
        }

        merged.sort(Comparator.comparing(MergedProgramme::canonicalChannelId).thenComparing(MergedProgramme::start));
        return merged;
    }

    private static boolean overlapsAny(List<Interval> claimed, Programme programme) {
        for (Interval interval : claimed) {
            if (programme.start().isBefore(interval.stop()) && interval.start().isBefore(programme.stop())) {
                return true;
            }
        }
        return false;
    }

    private record Interval(OffsetDateTime start, OffsetDateTime stop) {
    }
}
