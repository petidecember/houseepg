package com.anti.houseepg.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.config.model.SourceOrder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A reorder edits the hand-maintained file in place: comments survive, and the write is atomic. */
class YamlSourceOrderStoreTest {

    private static final String CONFIGURATION = """
            ---
            spring:
              application:
                name: houseepg

            houseepg:
              # Channel authority. Its tvg-ids become the emitted XMLTV channel ids.
              playlist-url: ""
              timeout: 60s
              # Priority order, highest first: the first enabled source is the primary source.
              sources:
                # Provider A is the one we have used for years.
                - id: provider-a
                  name: Provider A
                  url: ${HOUSEEPG_SOURCE_A_URL:}
                  # Each source refreshes on its own interval (default 8h).
                  refresh-interval: 8h
                - id: provider-b
                  name: Provider B
                  url: ${HOUSEEPG_SOURCE_B_URL:}
            """;

    @Test
    void reorderingMovesTheEntriesAndNothingElse() {
        YamlSourceOrderStore store = new YamlSourceOrderStore(null);

        String updated = store.reorder(CONFIGURATION, SourceOrder.of("provider-b", "provider-a"));

        assertThat(updated.indexOf("provider-b")).isLessThan(updated.indexOf("provider-a"));
        assertThat(updated).contains("# Channel authority. Its tvg-ids become the emitted XMLTV channel ids.");
        assertThat(updated).contains("# Priority order, highest first: the first enabled source is the primary source.");
        assertThat(updated).contains("# Each source refreshes on its own interval (default 8h).");
        assertThat(updated).contains("playlist-url:");
        assertThat(updated).contains("refresh-interval: 8h");
        assertThat(updated).contains("url: ${HOUSEEPG_SOURCE_A_URL:}");
    }

    @Test
    void theCommentAboveAnEntryTravelsWithIt() {
        YamlSourceOrderStore store = new YamlSourceOrderStore(null);

        String updated = store.reorder(CONFIGURATION, SourceOrder.of("provider-b", "provider-a"));

        int providerAComment = updated.indexOf("# Provider A is the one we have used for years.");
        assertThat(providerAComment).isGreaterThan(updated.indexOf("provider-b"));
        assertThat(providerAComment).isLessThan(updated.indexOf("name: Provider A"));
    }

    @Test
    void aReorderIsIdempotent() {
        YamlSourceOrderStore store = new YamlSourceOrderStore(null);

        String once = store.reorder(CONFIGURATION, SourceOrder.of("provider-b", "provider-a"));
        String twice = store.reorder(once, SourceOrder.of("provider-b", "provider-a"));

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void persistingRewritesTheFileAndLeavesNoTemporaryFileBehind(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("application.yaml");
        Files.writeString(file, CONFIGURATION, StandardCharsets.UTF_8);
        YamlSourceOrderStore store = new YamlSourceOrderStore(file.toString());

        YamlSourceOrderStore.PersistResult result = store.persist(SourceOrder.of("provider-b", "provider-a"));

        assertThat(result.persisted()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8).indexOf("provider-b"))
                .isLessThan(Files.readString(file, StandardCharsets.UTF_8).indexOf("provider-a"));
        try (Stream<Path> leftovers = Files.list(directory)) {
            assertThat(leftovers.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("application.yaml");
        }
    }

    @Test
    void aMissingConfigurationFileIsReportedInsteadOfThrown() {
        YamlSourceOrderStore store = new YamlSourceOrderStore("build/definitely-not-here.yaml");

        YamlSourceOrderStore.PersistResult result = store.persist(SourceOrder.of("provider-a"));

        assertThat(result.persisted()).isFalse();
        assertThat(result.detail()).contains("not readable");
    }

    @Test
    void anUnconfiguredFileKeepsTheOrderInMemoryOnly() {
        YamlSourceOrderStore store = new YamlSourceOrderStore("");

        assertThat(store.persist(SourceOrder.of("provider-a")).persisted()).isFalse();
    }

    @Test
    void aConfigurationWithoutASourceListFailsTheReorderRatherThanBlankingTheFile(@TempDir Path directory)
            throws IOException {
        String withoutSources = """
                houseepg:
                  timeout: 60s
                """;
        Path file = directory.resolve("application.yaml");
        Files.writeString(file, withoutSources, StandardCharsets.UTF_8);
        YamlSourceOrderStore store = new YamlSourceOrderStore(file.toString());

        YamlSourceOrderStore.PersistResult result = store.persist(SourceOrder.of("provider-a"));

        assertThat(result.persisted()).isFalse();
        assertThat(result.detail()).contains("sources");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(withoutSources);
    }
}
