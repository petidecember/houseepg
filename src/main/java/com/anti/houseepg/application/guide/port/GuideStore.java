package com.anti.houseepg.application.guide.port;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.RawSnapshot;
import com.anti.houseepg.domain.guide.model.SlotLoss;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceHealth;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.StoredGuide;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three persistence layers of design §7, plus the registry and per-source health that the feed
 * and the status endpoint must keep across restarts.
 *
 * <ol>
 *   <li><b>Raw snapshot</b> — the last good payload per source; a failed fetch never writes one.</li>
 *   <li><b>Normalized programmes</b> — per source, already attached to canonical channel ids.</li>
 *   <li><b>Materialized merged guide</b> — exactly what {@code GET /xmltv} serves.</li>
 * </ol>
 */
public interface GuideStore {

    Optional<RawSnapshot> rawSnapshot(SourceId source);

    void saveRawSnapshot(RawSnapshot snapshot);

    Optional<SourceEpg> normalized(SourceId source);

    /** Replaces one source's normalized document; the canonical ids are the ones matching just chose. */
    void saveNormalized(SourceEpg document, Map<SourceChannelRef, ChannelId> canonicalIds);

    Optional<StoredGuide> mergedGuide();

    void saveMergedGuide(Guide guide, Instant materializedAt);

    List<Channel> registry();

    void saveRegistry(List<Channel> channels);

    List<SourceHealth> health();

    void saveHealth(SourceHealth health);

    /**
     * The slot rule's losses from the last refresh, per source. Reported by {@code /api/sources/status}
     * (design §6.3), so an operator can see a source being eaten instead of only a low programme count.
     */
    SlotLoss.BySource slotLosses();

    void saveSlotLosses(SlotLoss.BySource losses);

    /** Retention: drops normalized programmes that ended before {@code before}. */
    void prune(Instant before);
}
