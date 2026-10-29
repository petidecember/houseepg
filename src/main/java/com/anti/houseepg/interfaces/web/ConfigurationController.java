package com.anti.houseepg.interfaces.web;

import com.anti.houseepg.application.config.DumpConfiguration;
import com.anti.houseepg.application.config.ReloadConfiguration;
import com.anti.houseepg.application.config.ReorderSources;
import com.anti.houseepg.application.config.ReseedRegistry;
import com.anti.houseepg.application.config.port.SourceOrderStore;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presentation adapter for the only configuration the UI may change (ADR-0005): the source order, a
 * configuration reload, a diagnostic dump, and the explicit registry re-seed.
 *
 * <p>There is deliberately no source CRUD: sources are declared in YAML, and credentials never leave it.
 */
@RestController
public class ConfigurationController {

    private final GuideSettings settings;
    private final ReorderSources reorderSources;
    private final ReloadConfiguration reloadConfiguration;
    private final DumpConfiguration dumpConfiguration;
    private final ReseedRegistry reseedRegistry;

    public ConfigurationController(GuideSettings settings, ReorderSources reorderSources,
            ReloadConfiguration reloadConfiguration, DumpConfiguration dumpConfiguration,
            ReseedRegistry reseedRegistry) {
        this.settings = settings;
        this.reorderSources = reorderSources;
        this.reloadConfiguration = reloadConfiguration;
        this.dumpConfiguration = dumpConfiguration;
        this.reseedRegistry = reseedRegistry;
    }

    /** The configured sources in merge order, highest priority first. Never carries credentials. */
    @GetMapping("/api/priority")
    public PriorityResponse priority() {
        return new PriorityResponse(settings.sources().stream().map(PrioritySource::from).toList());
    }

    /**
     * Reorders the sources. The new order applies to the running merge at once; {@code persisted} says
     * whether it survives a restart, and {@code detail} why it does not. This never re-seeds the
     * registry, so no emitted channel id moves (ADR-0002).
     */
    @PutMapping("/api/priority")
    public ResponseEntity<?> reorder(@RequestBody ReorderRequest request) {
        List<SourceId> ids = request.ids() == null ? List.of() : request.ids().stream().map(SourceId::new).toList();
        try {
            ReorderSources.Result result = reorderSources.reorder(ids);
            SourceOrderStore.PersistResult persist = result.persist();
            return ResponseEntity.ok(new ReorderResponse(
                    result.order().ids().stream().map(SourceId::value).toList(),
                    persist.persisted(),
                    persist.detail()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new Problem(e.getMessage()));
        }
    }

    /** Re-reads the YAML configuration; the registry stays frozen, so a new source is a pass-through. */
    @PostMapping("/api/config/reload")
    public ResponseEntity<?> reload() {
        try {
            ReloadConfiguration.Result result = reloadConfiguration.reload();
            return ResponseEntity.ok(new ReloadResponse(
                    ids(result.added()),
                    ids(result.removed()),
                    result.orderChanged(),
                    ids(result.order())));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(new Problem(e.getMessage()));
        }
    }

    /** Writes the in-memory configuration, credentials included, to the log stream. */
    @PostMapping("/api/config/dump")
    public ResponseEntity<Void> dump() {
        dumpConfiguration.dump();
        return ResponseEntity.noContent().build();
    }

    /** What an explicit re-seed would change; ids that would disappear are the ones a DVR still maps. */
    @GetMapping("/api/registry/reseed")
    public ReseedResponse reseedPreview() {
        return ReseedResponse.from(reseedRegistry.preview());
    }

    /** Performs the re-seed the preview described — the only operation that may change an emitted id. */
    @PostMapping("/api/registry/reseed")
    public ReseedResponse reseed() {
        return ReseedResponse.from(reseedRegistry.apply());
    }

    private static List<String> ids(List<SourceId> ids) {
        return ids.stream().map(SourceId::value).toList();
    }

    /** A rename the UI asks for: the new order, naming exactly the configured sources. */
    public record ReorderRequest(@JsonProperty("ids") List<String> ids) {
    }

    public record PriorityResponse(List<PrioritySource> sources) {
    }

    public record PrioritySource(String id, String name, String refreshInterval) {

        static PrioritySource from(EpgSource source) {
            return new PrioritySource(source.id().value(), source.name(), source.refreshInterval().toString());
        }
    }

    public record ReorderResponse(List<String> order, boolean persisted, String detail) {
    }

    public record ReloadResponse(List<String> added, List<String> removed, boolean orderChanged, List<String> order) {
    }

    /** @param changesAnything false when a re-seed would not move a single id */
    public record ReseedResponse(boolean changesAnything, List<String> added, List<String> removed,
            List<ReseedChannel> proposed) {

        static ReseedResponse from(ReseedRegistry.Preview preview) {
            return new ReseedResponse(
                    preview.changesAnything(),
                    preview.added().stream().map(ChannelId::value).toList(),
                    preview.removed().stream().map(ChannelId::value).toList(),
                    preview.proposed().stream().map(ReseedChannel::from).toList());
        }
    }

    public record ReseedChannel(String id, String name, String origin) {

        static ReseedChannel from(Channel channel) {
            return new ReseedChannel(channel.id().value(), channel.label(), channel.origin().name());
        }
    }

    public record Problem(String message) {
    }
}
