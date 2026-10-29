package com.anti.houseepg.application.config;

import com.anti.houseepg.application.config.port.SourceOrderStore;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.config.model.SourceOrder;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use case: change the source priority order (ADR-0005).
 *
 * <p>The running merge takes the new order at once; persistence into the YAML file is best-effort and
 * never turns a successful reorder into an error. The result carries both, so the caller can tell
 * "applied and saved" from "applied but a restart would forget it". It never re-seeds the registry,
 * so no emitted channel id moves (ADR-0002).
 */
public class ReorderSources {

    private static final Logger log = LoggerFactory.getLogger(ReorderSources.class);

    private final GuideSettings settings;
    private final SourceOrderStore store;

    public ReorderSources(GuideSettings settings, SourceOrderStore store) {
        this.settings = settings;
        this.store = store;
    }

    public SourceOrder current() {
        return SourceOrder.of(settings.sources().stream().map(EpgSource::id).toList());
    }

    /** @throws IllegalArgumentException when the request is not a permutation of the declared sources */
    public synchronized Result reorder(List<SourceId> requested) {
        SourceOrder next = current().reordered(requested);
        settings.reorder(next.ids());
        SourceOrderStore.PersistResult persist;
        try {
            persist = store.persist(next);
        } catch (RuntimeException e) {
            persist = SourceOrderStore.PersistResult.notSaved(describe(e));
        }
        if (!persist.persisted()) {
            log.warn("Source order applied but not persisted: {}", persist.detail());
        }
        return new Result(next, persist);
    }

    private static String describe(RuntimeException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * @param order the order now in effect
     * @param persist whether it survives a restart, and why not when it does not
     */
    public record Result(SourceOrder order, SourceOrderStore.PersistResult persist) {
    }
}
