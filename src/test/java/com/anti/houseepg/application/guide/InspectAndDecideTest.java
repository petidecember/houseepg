package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Looking at the merge and fixing a wrong match: what the review and preview panels are built on. */
class InspectAndDecideTest {

    private final InMemoryGuideStore store = new InMemoryGuideStore();
    private final InMemoryChannelMatchStore matches = new InMemoryChannelMatchStore();

    @Test
    void theRegistryIsServedWithTheMatchesBehindIt() {
        store.saveRegistry(List.of(channel("amc.xx", "AMC")));
        matches.saveAuto(List.of(ChannelMatch.auto(ref("a", "AMC.hu"), new ChannelId("amc.xx"), MatchConfidence.EXACT_NAME)));

        InspectGuide.Registry registry = new InspectGuide(store, matches).registry();

        assertThat(registry.channels()).extracting(Channel::id).containsExactly(new ChannelId("amc.xx"));
        assertThat(registry.matches()).extracting(ChannelMatch::state).containsExactly(MatchState.AUTO);
    }

    @Test
    void aPreviewCarriesTheWinningSourcePerSlotInStartOrder() {
        store.saveRegistry(List.of(channel("amc.xx", "AMC")));
        store.saveMergedGuide(new Guide(List.of(channel("amc.xx", "AMC")), List.of(
                programme("amc.xx", "b", "Second", 12),
                programme("amc.xx", "a", "First", 10))), Instant.parse("2026-10-29T18:00:00Z"));

        InspectGuide.Timeline timeline = new InspectGuide(store, matches).preview(new ChannelId("amc.xx")).orElseThrow();

        assertThat(timeline.programmes()).extracting(Programme::title).containsExactly("First", "Second");
        assertThat(timeline.programmes()).extracting(Programme::winningSource)
                .containsExactly(new SourceId("a"), new SourceId("b"));
    }

    @Test
    void aChannelTheGuideDoesNotDeclareHasNoPreview() {
        assertThat(new InspectGuide(store, matches).preview(new ChannelId("nope"))).isEmpty();
    }

    @Test
    void confirmingAMatchRecordsACuratedRowAndReMerges() {
        store.saveRegistry(List.of(channel("amc.xx", "AMC")));
        matches.saveAuto(List.of(ChannelMatch.auto(ref("a", "AMC.hu"), new ChannelId("amc.xx"), MatchConfidence.EXACT_NAME)));
        AtomicInteger merges = new AtomicInteger();

        ChannelMatch decision = new DecideMatch(store, matches, merges::incrementAndGet)
                .decide(ref("a", "AMC.hu"), new ChannelId("amc.xx"), false);

        assertThat(decision.state()).isEqualTo(MatchState.CONFIRMED);
        assertThat(decision.confidence()).isEqualTo(MatchConfidence.EXACT_NAME);
        assertThat(matches.decisions()).containsExactly(decision);
        assertThat(merges).hasValue(1);
    }

    @Test
    void correctingAMatchPointsItAtAnotherRegistryChannel() {
        store.saveRegistry(List.of(channel("amc.xx", "AMC"), channel("amc.uk", "AMC UK")));

        ChannelMatch decision = new DecideMatch(store, matches, () -> {
        }).decide(ref("a", "AMC.hu"), new ChannelId("amc.uk"), true);

        assertThat(decision.state()).isEqualTo(MatchState.CORRECTED);
        assertThat(decision.canonicalId()).isEqualTo(new ChannelId("amc.uk"));
        assertThat(decision.confidence()).isEqualTo(MatchConfidence.NORMALIZED_NAME);
    }

    @Test
    void aDecisionAboutAChannelTheRegistryDoesNotEmitIsRefused() {
        store.saveRegistry(List.of(channel("amc.xx", "AMC")));

        assertThatThrownBy(() -> new DecideMatch(store, matches, () -> {
        }).decide(ref("a", "AMC.hu"), new ChannelId("nope"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no channel with id nope");
        assertThat(matches.decisions()).isEmpty();
    }

    private static Channel channel(String id, String name) {
        return new Channel(new ChannelId(id), name, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceChannelRef ref(String source, String channelId) {
        return new SourceChannelRef(new SourceId(source), channelId);
    }

    private static Programme programme(String channelId, String source, String title, int hour) {
        OffsetDateTime start = OffsetDateTime.of(2026, 10, 29, hour, 0, 0, 0, ZoneOffset.UTC);
        return new Programme(new ChannelId(channelId), new Slot(start, start.plusHours(1)), new SourceId(source),
                title, null, null, null, null);
    }
}
