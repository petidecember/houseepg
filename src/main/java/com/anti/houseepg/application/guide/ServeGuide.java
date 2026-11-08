package com.anti.houseepg.application.guide;

import com.anti.houseepg.application.guide.port.ChannelMatchStore;
import com.anti.houseepg.application.guide.port.GuideStore;
import com.anti.houseepg.domain.guide.model.StoredGuide;

/**
 * Use case: answer a read of the guide from the materialized layer, never by re-merging (design §7).
 * The scheduled refresh is the only writer.
 */
public class ServeGuide {

    private final RefreshGuide refreshGuide;
    private final GuideStore store;
    private final ChannelMatchStore matchStore;

    public ServeGuide(RefreshGuide refreshGuide, GuideStore store, ChannelMatchStore matchStore) {
        this.refreshGuide = refreshGuide;
        this.store = store;
        this.matchStore = matchStore;
    }

    public RefreshResult current() {
        return store.mergedGuide().map(this::current).orElseGet(refreshGuide::refresh);
    }

    /** Forces a rebuild; used by the manual refresh path. */
    public RefreshResult refresh() {
        return refreshGuide.refresh();
    }

    private RefreshResult current(StoredGuide stored) {
        return new RefreshResult(stored.guide(), store.health(), stored.materializedAt(),
                matchStore.decisions(), store.slotLosses());
    }
}
