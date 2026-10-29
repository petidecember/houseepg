package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.config.port.SourceOrderStore;
import com.anti.houseepg.domain.config.model.SourceOrder;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.StreamDataWriter;
import org.snakeyaml.engine.v2.composer.Composer;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the source order back into the YAML file that declares the sources (ADR-0005).
 *
 * <p>The file is hand-maintained, so this adapter edits the node tree rather than re-serialising a
 * model: comments attached to the entries travel with them, and only the order of the
 * {@code houseepg.sources} sequence changes. Whitespace and quoting elsewhere may be normalised by the
 * serializer — that is the accepted cost of the decision.
 *
 * <p>The write is atomic: a sibling temporary file is written and then moved over the target, so a
 * crash mid-write can never truncate the user's configuration.
 */
public class YamlSourceOrderStore implements SourceOrderStore {

    private static final Logger log = LoggerFactory.getLogger(YamlSourceOrderStore.class);
    private static final String ROOT_KEY = "houseepg";
    private static final String SOURCES_KEY = "sources";
    private static final String ID_KEY = "id";

    private final Path file;
    private final LoadSettings loadSettings = LoadSettings.builder()
            .setParseComments(true)
            .setLabel("houseepg configuration")
            .build();
    private final DumpSettings dumpSettings = DumpSettings.builder()
            .setDumpComments(true)
            .setSplitLines(false)
            .setIndent(2)
            .build();

    public YamlSourceOrderStore(String location) {
        this.file = pathOf(location);
    }

    @Override
    public PersistResult persist(SourceOrder order) {
        if (file == null) {
            return PersistResult.notSaved("no configuration file is configured, so the order is in-memory only");
        }
        if (!Files.isReadable(file)) {
            return PersistResult.notSaved("configuration file " + file + " is not readable");
        }
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String updated = reorder(original, order);
            writeAtomically(updated);
            return PersistResult.saved();
        } catch (IOException | RuntimeException e) {
            String detail = describe(e);
            log.warn("Could not persist the source order into {}: {}", file, detail);
            return PersistResult.notSaved(detail);
        }
    }

    /** Pure text-to-text reorder, so a test can assert what the file would look like. */
    public String reorder(String yaml, SourceOrder order) {
        Node document;
        try {
            document = compose(yaml);
        } catch (RuntimeException e) {
            throw new IllegalStateException("the configuration is not valid YAML: " + describe(e), e);
        }
        MappingNode root = mapping(document).orElseThrow(() -> new IllegalStateException("the configuration is not a mapping"));
        Node sourcesNode = value(root, ROOT_KEY).flatMap(YamlSourceOrderStore::mapping)
                .flatMap(houseepg -> value(houseepg, SOURCES_KEY))
                .orElseThrow(() -> new IllegalStateException("the configuration has no " + ROOT_KEY + "." + SOURCES_KEY + " list"));
        if (!(sourcesNode instanceof SequenceNode sequence)) {
            throw new IllegalStateException("the configuration's " + ROOT_KEY + "." + SOURCES_KEY + " is not a list");
        }

        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node entry : sequence.getValue()) {
            entryId(entry).ifPresent(id -> byId.put(id, entry));
        }
        List<SourceId> requested = order.ids();
        List<Node> reordered = new ArrayList<>(requested.size() + byId.size());
        for (SourceId id : requested) {
            Node entry = byId.remove(id.value());
            if (entry != null) {
                reordered.add(entry);
            }
        }
        if (!byId.isEmpty()) {
            log.warn("Keeping {} source(s) the order does not name at the end of the list: {}", byId.size(), byId.keySet());
            reordered.addAll(byId.values());
        }
        Node rebuiltSources = copying(sequence, reordered);
        MappingNode houseepg = value(root, ROOT_KEY).flatMap(YamlSourceOrderStore::mapping)
                .orElseThrow(() -> new IllegalStateException("the configuration has no " + ROOT_KEY + " section"));
        return render(replacing(root, ROOT_KEY, replacing(houseepg, SOURCES_KEY, rebuiltSources)));
    }

    private Node compose(String yaml) {
        ParserImpl parser = new ParserImpl(loadSettings, new StreamReader(yaml, loadSettings));
        return new Composer(loadSettings, parser).getSingleNode()
                .orElseThrow(() -> new IllegalStateException("the configuration is empty"));
    }

    private String render(Node document) {
        StringBuilder out = new StringBuilder();
        StreamDataWriter writer = new StreamDataWriter() {
            @Override
            public void write(String str) {
                out.append(str);
            }

            @Override
            public void write(String str, int offset, int length) {
                out.append(str, offset, offset + length);
            }

            @Override
            public void flush() {
                // nothing buffered: the StringBuilder is the destination
            }
        };
        new Dump(dumpSettings).dumpNode(document, writer);
        return out.toString();
    }

    private void writeAtomically(String content) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".houseepg-config", ".yaml.tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                log.warn("The file system does not support an atomic replace of {}; falling back to a plain move", file);
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Optional<String> entryId(Node entry) {
        return mapping(entry).flatMap(mapping -> value(mapping, ID_KEY)).flatMap(YamlSourceOrderStore::scalar);
    }

    private static Optional<MappingNode> mapping(Node node) {
        return node instanceof MappingNode mapping ? Optional.of(mapping) : Optional.empty();
    }

    private static Optional<Node> value(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode keyNode && key.equals(keyNode.getValue())) {
                return Optional.of(tuple.getValueNode());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> scalar(Node node) {
        return node instanceof ScalarNode scalar ? Optional.of(scalar.getValue()) : Optional.empty();
    }

    /** Nodes are immutable, so the sequence is rebuilt carrying its own comments and marks. */
    private static Node copying(SequenceNode original, List<Node> items) {
        SequenceNode rebuilt = new SequenceNode(original.getTag(), false, items, original.getFlowStyle(),
                original.getStartMark(), original.getEndMark());
        rebuilt.setBlockComments(original.getBlockComments());
        rebuilt.setInLineComments(original.getInLineComments());
        rebuilt.setEndComments(original.getEndComments());
        return rebuilt;
    }

    private static MappingNode replacing(MappingNode mapping, String key, Node replacement) {
        List<NodeTuple> tuples = new ArrayList<>(mapping.getValue().size());
        for (NodeTuple tuple : mapping.getValue()) {
            boolean matches = tuple.getKeyNode() instanceof ScalarNode keyNode && key.equals(keyNode.getValue());
            tuples.add(matches ? new NodeTuple(tuple.getKeyNode(), replacement) : tuple);
        }
        MappingNode rebuilt = new MappingNode(mapping.getTag(), tuples, mapping.getFlowStyle());
        rebuilt.setBlockComments(mapping.getBlockComments());
        rebuilt.setInLineComments(mapping.getInLineComments());
        rebuilt.setEndComments(mapping.getEndComments());
        return rebuilt;
    }

    private static Path pathOf(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String trimmed = location.trim();
        if (trimmed.startsWith("classpath:")) {
            return null;
        }
        if (trimmed.startsWith("file:")) {
            trimmed = trimmed.substring("file:".length());
        }
        return Path.of(trimmed);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
