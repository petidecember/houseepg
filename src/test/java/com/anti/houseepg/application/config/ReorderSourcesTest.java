package com.anti.houseepg.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.application.config.port.SourceOrderStore;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.config.model.SourceOrder;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A reorder takes effect at once, persists best-effort, and never turns a write failure into an error. */
class ReorderSourcesTest {

    @Test
    void theRunningMergeTakesTheNewOrderAndTheOrderIsPersisted() {
        GuideSettings settings = settings("a", "b");
        List<SourceOrder> written = new ArrayList<>();
        ReorderSources reorder = new ReorderSources(settings, order -> {
            written.add(order);
            return SourceOrderStore.PersistResult.saved();
        });

        ReorderSources.Result result = reorder.reorder(ids("b", "a"));

        assertThat(result.persist().persisted()).isTrue();
        assertThat(settings.sources().stream().map(EpgSource::id).map(SourceId::value).toList())
                .containsExactly("b", "a");
        assertThat(written).containsExactly(SourceOrder.of("b", "a"));
    }

    @Test
    void aFailedWriteStillAppliesTheOrderButSaysItWillNotSurviveARestart() {
        GuideSettings settings = settings("a", "b");
        ReorderSources reorder = new ReorderSources(settings,
                order -> SourceOrderStore.PersistResult.notSaved("disk is read-only"));

        ReorderSources.Result result = reorder.reorder(ids("b", "a"));

        assertThat(result.persist().persisted()).isFalse();
        assertThat(result.persist().detail()).isEqualTo("disk is read-only");
        assertThat(settings.sources().getFirst().id()).isEqualTo(new SourceId("b"));
    }

    @Test
    void aStoreThatThrowsIsReportedInsteadOfFailingTheRequest() {
        GuideSettings settings = settings("a", "b");
        ReorderSources reorder = new ReorderSources(settings, order -> {
            throw new IllegalStateException("boom");
        });

        ReorderSources.Result result = reorder.reorder(ids("b", "a"));

        assertThat(result.persist().persisted()).isFalse();
        assertThat(result.persist().detail()).contains("boom");
    }

    @Test
    void anOrderThatIsNotAPermutationOfTheConfiguredSourcesIsRejected() {
        GuideSettings settings = settings("a", "b");
        ReorderSources reorder = new ReorderSources(settings, order -> SourceOrderStore.PersistResult.saved());

        assertThatThrownBy(() -> reorder.reorder(ids("a", "b", "c")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.sources().stream().map(EpgSource::id).map(SourceId::value).toList())
                .containsExactly("a", "b");
    }

    @Test
    void aReorderNeverReSeedsSoTheOrderIsAllItReports() {
        GuideSettings settings = settings("a", "b", "c");
        ReorderSources reorder = new ReorderSources(settings, order -> SourceOrderStore.PersistResult.saved());

        ReorderSources.Result result = reorder.reorder(ids("c", "b", "a"));

        assertThat(result.order().ids()).containsExactlyElementsOf(ids("c", "b", "a"));
        assertThat(reorder.current().ids()).containsExactlyElementsOf(ids("c", "b", "a"));
    }

    private static List<SourceId> ids(String... ids) {
        return SourceOrder.of(ids).ids();
    }

    private static GuideSettings settings(String... ids) {
        List<EpgSource> sources = SourceOrder.of(ids).ids().stream()
                .map(id -> EpgSource.of(id.value(), id.value(), "http://" + id.value(), Credentials.none()))
                .toList();
        return new GuideSettings(sources, Optional.empty());
    }
}
