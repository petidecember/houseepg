package com.anti.houseepg.domain.config.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The order is a permutation of the declared sources, and YAML decides which sources that is (ADR-0005). */
class SourceOrderTest {

    @Test
    void aReorderPutsTheConfiguredSourcesInTheRequestedOrder() {
        SourceOrder order = SourceOrder.of("a", "b", "c");

        assertThat(order.reordered(ids("c", "a", "b")).ids()).containsExactly(ids("c", "a", "b").toArray(new SourceId[0]));
    }

    @Test
    void aReorderCannotAddASourceThatTheConfigurationDoesNotDeclare() {
        SourceOrder order = SourceOrder.of("a", "b");

        assertThatThrownBy(() -> order.reordered(ids("a", "b", "c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly the declared sources");
    }

    @Test
    void aReorderCannotDropASourceEvenByOmittingIt() {
        SourceOrder order = SourceOrder.of("a", "b");

        assertThatThrownBy(() -> order.reordered(ids("a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOrderCannotNameTheSameSourceTwice() {
        assertThatThrownBy(() -> SourceOrder.of("a", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot repeat");
    }

    @Test
    void aSourceRemovedFromTheConfigurationLeavesTheOrder() {
        SourceOrder order = SourceOrder.of("a", "b", "c");

        assertThat(order.reconciledWith(ids("a", "c")).ids()).containsExactly(ids("a", "c").toArray(new SourceId[0]));
    }

    @Test
    void aSourceAddedToTheConfigurationTakesThePositionAfterItsNearestPredecessor() {
        SourceOrder order = SourceOrder.of("a", "c");

        // "b" is declared between "a" and "c", so it lands directly after "a".
        assertThat(order.reconciledWith(ids("a", "b", "c")).ids())
                .containsExactly(ids("a", "b", "c").toArray(new SourceId[0]));
    }

    @Test
    void aSourceAddedAtTheTopOfTheDeclarationGoesFirst() {
        SourceOrder order = SourceOrder.of("b", "c");

        assertThat(order.reconciledWith(ids("a", "b", "c")).ids())
                .containsExactly(ids("a", "b", "c").toArray(new SourceId[0]));
    }

    @Test
    void aSecondNewSourceFollowsThePredecessorItSharesWithTheFirstOne() {
        SourceOrder order = SourceOrder.of("a", "d");

        // "b" and "c" were both declared right after "a", so they keep their declared relative order.
        assertThat(order.reconciledWith(ids("a", "b", "c", "d")).ids())
                .containsExactly(ids("a", "b", "c", "d").toArray(new SourceId[0]));
    }

    private static List<SourceId> ids(String... ids) {
        return SourceOrder.of(ids).ids();
    }
}
