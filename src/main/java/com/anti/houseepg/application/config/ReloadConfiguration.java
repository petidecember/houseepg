package com.anti.houseepg.application.config;

import com.anti.houseepg.application.config.port.DeclarationReader;
import com.anti.houseepg.application.config.port.GuideReMerge;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.config.model.SourceOrder;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Use case: re-read the YAML configuration and rebind the source set without restarting (ADR-0005).
 *
 * <p>Reload re-merges but leaves the registry alone, so emitted channel ids never move because of it:
 * a source added here contributes only pass-through channels until the explicit re-seed is run
 * (ADR-0002). The playlist and the retention stay as they were booted.
 */
public class ReloadConfiguration {

    private final GuideSettings settings;
    private final DeclarationReader declarations;
    private final GuideReMerge reMerge;

    public ReloadConfiguration(GuideSettings settings, DeclarationReader declarations, GuideReMerge reMerge) {
        this.settings = settings;
        this.declarations = declarations;
        this.reMerge = reMerge;
    }

    /** @throws IllegalStateException when the configuration cannot be read; a reload never half-applies */
    public synchronized Result reload() {
        List<EpgSource> declared = declarations.read();
        List<SourceId> before = settings.sources().stream().map(EpgSource::id).toList();
        List<SourceId> declaredIds = declared.stream().map(EpgSource::id).toList();

        Map<SourceId, EpgSource> byId = new LinkedHashMap<>();
        declared.forEach(source -> byId.put(source.id(), source));
        SourceOrder reconciled = SourceOrder.of(before).reconciledWith(declaredIds);
        List<EpgSource> ordered = reconciled.ids().stream().map(byId::get).toList();

        settings.replaceSources(ordered);
        reMerge.rematerialize();

        List<SourceId> added = declaredIds.stream().filter(id -> !before.contains(id)).toList();
        List<SourceId> removed = before.stream().filter(id -> !declaredIds.contains(id)).toList();
        List<SourceId> order = reconciled.ids();
        return new Result(added, removed, !order.equals(before), order);
    }

    /**
     * @param added sources the configuration gained; they serve as pass-through until a re-seed
     * @param removed sources the configuration lost
     * @param orderChanged whether the merge order differs from the one in effect
     * @param order the order in effect after the reload
     */
    public record Result(List<SourceId> added, List<SourceId> removed, boolean orderChanged, List<SourceId> order) {
    }
}
