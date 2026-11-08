package com.anti.houseepg.support;

import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.RawSnapshot;
import com.anti.houseepg.domain.guide.model.SlotLoss;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.model.StoredGuide;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double for the three persistence layers: same contract, no database. */
public class InMemoryGuideStore implements GuideStore {

    private final Map<SourceId, RawSnapshot> rawSnapshots = new LinkedHashMap<>();
    private final Map<SourceId, SourceEpg> normalized = new LinkedHashMap<>();
    private final Map<SourceId, SourceHealth> health = new LinkedHashMap<>();
    private List<Channel> registry = List.of();
    private StoredGuide merged;
    private SlotLoss.BySource slotLosses = SlotLoss.BySource.none();

    @Override
    public Optional<RawSnapshot> rawSnapshot(SourceId source) {
        return Optional.ofNullable(rawSnapshots.get(source));
    }

    @Override
    public void saveRawSnapshot(RawSnapshot snapshot) {
        rawSnapshots.put(snapshot.source(), snapshot);
    }

    @Override
    public Optional<SourceEpg> normalized(SourceId source) {
        return Optional.ofNullable(normalized.get(source));
    }

    @Override
    public void saveNormalized(SourceEpg document, Map<SourceChannelRef, ChannelId> canonicalIds) {
        normalized.put(document.source(), document);
    }

    @Override
    public Optional<StoredGuide> mergedGuide() {
        return Optional.ofNullable(merged);
    }

    @Override
    public void saveMergedGuide(Guide guide, Instant materializedAt) {
        merged = new StoredGuide(guide, materializedAt);
    }

    @Override
    public List<Channel> registry() {
        return registry;
    }

    @Override
    public void saveRegistry(List<Channel> channels) {
        registry = List.copyOf(channels);
    }

    @Override
    public List<SourceHealth> health() {
        return List.copyOf(health.values());
    }

    @Override
    public void saveHealth(SourceHealth status) {
        health.put(status.source(), status);
    }

    @Override
    public SlotLoss.BySource slotLosses() {
        return slotLosses;
    }

    @Override
    public void saveSlotLosses(SlotLoss.BySource losses) {
        this.slotLosses = losses;
    }

    @Override
    public void prune(Instant before) {
        Map<SourceId, SourceEpg> retained = new LinkedHashMap<>();
        normalized.forEach((source, document) -> {
            List<SourceProgramme> kept = new ArrayList<>();
            for (SourceProgramme programme : document.programmes()) {
                if (!programme.slot().stop().toInstant().isBefore(before)) {
                    kept.add(programme);
                }
            }
            retained.put(source, new SourceEpg(source, document.channels(), kept));
        });
        normalized.clear();
        normalized.putAll(retained);
    }
}
