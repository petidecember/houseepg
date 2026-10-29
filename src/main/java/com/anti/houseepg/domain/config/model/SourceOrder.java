package com.anti.houseepg.domain.config.model;

import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one piece of user state in the system: the order sources are merged in, highest priority first
 * (ADR-0005). Everything else about a source is declared in YAML and is not the user's to edit here.
 *
 * <p>A reorder is a permutation of the sources already declared — it can never add or remove one —
 * and it never re-seeds the registry, so no emitted channel id moves because of it (ADR-0002).
 */
public record SourceOrder(List<SourceId> ids) {

    public SourceOrder {
        ids = ids == null ? List.of() : List.copyOf(ids);
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("a source order cannot repeat a source: " + ids);
        }
    }

    public static SourceOrder of(List<SourceId> ids) {
        return new SourceOrder(ids);
    }

    public static SourceOrder of(String... ids) {
        List<SourceId> sourceIds = new ArrayList<>(ids.length);
        for (String id : ids) {
            sourceIds.add(new SourceId(id));
        }
        return new SourceOrder(sourceIds);
    }

    /** The requested order, which must name exactly the declared sources. */
    public SourceOrder reordered(List<SourceId> requested) {
        if (requested == null) {
            throw new IllegalArgumentException("a reorder must name the sources in their new order");
        }
        Set<SourceId> wanted = new LinkedHashSet<>(requested);
        if (wanted.size() != requested.size()) {
            throw new IllegalArgumentException("a source order cannot repeat a source: " + requested);
        }
        if (!wanted.equals(new LinkedHashSet<>(ids))) {
            throw new IllegalArgumentException(
                    "a reorder names exactly the declared sources; declared " + ids + ", requested " + requested);
        }
        return new SourceOrder(requested);
    }

    /**
     * ADR-0005 reconciliation against the declared YAML set: a source removed from YAML leaves the
     * order, and a source added to YAML takes the position immediately after the nearest declared
     * predecessor the order already knew — else first.
     */
    public SourceOrder reconciledWith(List<SourceId> declared) {
        List<SourceId> declaration = declared == null ? List.of() : List.copyOf(declared);
        Set<SourceId> known = new LinkedHashSet<>(ids);
        List<SourceId> reconciled = new ArrayList<>(ids.stream().filter(declaration::contains).toList());
        for (int position = 0; position < declaration.size(); position++) {
            SourceId candidate = declaration.get(position);
            if (known.contains(candidate)) {
                continue;
            }
            int insertAt = 0;
            for (int predecessor = position - 1; predecessor >= 0; predecessor--) {
                int found = reconciled.indexOf(declaration.get(predecessor));
                if (found >= 0) {
                    insertAt = found + 1;
                    break;
                }
            }
            reconciled.add(insertAt, candidate);
        }
        return new SourceOrder(reconciled);
    }
}
