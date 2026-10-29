package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.MatchState;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.infrastructure.match.InMemoryChannelMatchStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The refresh keeps what it learned: a recorded decision survives the next pass, an automatic pass
 * never overwrites a human one, and the registry never re-seeds itself (design §6.2, §6.4).
 */
class ChannelMatchPersistenceTest {

    private static final EpgSource A = EpgSource.of("a", "Source A", "fixture:a", null);
    private static final Optional<String> PLAYLIST = Optional.of("fixture:playlist.m3u");

    private final InMemoryChannelMatchStore store = new InMemoryChannelMatchStore();
    private final List<Channel> playlist = List.of(
            playlistChannel("amc.xx", "AMC"),
            playlistChannel("bravo.xx", "Bravo"));
    private final Map<SourceId, SourceEpg> documents = new HashMap<>();

    @Test
    void recordsEveryMatchWithItsEvidence() {
        documents.put(A.id(), source(A, declared("AMC (HD).hu", "AMC (HD)"), declared("zzz.xx", "Zzz")));

        RefreshResult result = guide(PLAYLIST, A).refresh();

        assertThat(result.matches()).extracting(match -> match.canonicalId().value())
                .containsExactly("amc.xx", "zzz.xx");
        assertThat(result.matches()).extracting(ChannelMatch::state)
                .containsExactly(MatchState.AUTO, MatchState.UNMATCHED);
        assertThat(store.decisions()).containsExactlyInAnyOrderElementsOf(result.matches());
    }

    @Test
    void aConfirmedDecisionGuidesTheNextRefreshAfterARename() {
        SourceChannelRef ref = new SourceChannelRef(A.id(), "amc-old.xx");
        documents.put(A.id(), source(A, declared("amc-old.xx", "Something Else")));
        RefreshGuide guide = guide(PLAYLIST, A);
        guide.refresh();

        store.decide(ChannelMatch.confirmed(ref, new ChannelId("bravo.xx"), MatchConfidence.EXACT_ID));
        documents.put(A.id(), source(A, declared("amc-old.xx", "Totally Different")));

        RefreshResult next = guide.refresh();

        assertThat(next.matches()).singleElement().satisfies(match -> {
            assertThat(match.canonicalId().value()).isEqualTo("bravo.xx");
            assertThat(match.state()).isEqualTo(MatchState.CONFIRMED);
        });
    }

    @Test
    void anAutomaticPassNeverDowngradesACuratedRow() {
        SourceChannelRef ref = new SourceChannelRef(A.id(), "amc-old.xx");
        store.decide(ChannelMatch.corrected(ref, new ChannelId("bravo.xx"), MatchConfidence.EXACT_ID));
        // This spelling would auto-match amc.xx, but the human said bravo.xx.
        documents.put(A.id(), source(A, declared("amc-old.xx", "AMC")));

        RefreshResult result = guide(PLAYLIST, A).refresh();

        assertThat(result.matches()).singleElement()
                .satisfies(match -> assertThat(match.canonicalId().value()).isEqualTo("bravo.xx"));
        assertThat(store.decisions()).singleElement().satisfies(stored -> {
            assertThat(stored.state()).isEqualTo(MatchState.CORRECTED);
            assertThat(stored.canonicalId().value()).isEqualTo("bravo.xx");
        });
    }

    @Test
    void theRegistryComesFromThePlaylistAndNotFromThePrimarySource() {
        documents.put(A.id(), source(A, declared("primary-only.xx", "Primary Only")));

        RefreshResult result = guide(PLAYLIST, A).refresh();

        assertThat(result.guide().channels()).filteredOn(channel -> channel.id().value().equals("amc.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PLAYLIST));
        assertThat(result.guide().channels()).filteredOn(channel -> channel.id().value().equals("primary-only.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PASS_THROUGH));
    }

    @Test
    void theSeededRegistryDoesNotFollowAnUpstreamRename() {
        RefreshGuide guide = guide(Optional.empty(), A);
        documents.put(A.id(), source(A, declared("x.xx", "Ex")));
        assertThat(guide.refresh().guide().channels())
                .extracting(channel -> channel.id().value()).contains("x.xx");

        // Upstream renames its channel: the emitted id must not drift with it (ADR-0002).
        documents.put(A.id(), source(A, declared("y.xx", "Why")));
        RefreshResult next = guide.refresh();

        assertThat(next.guide().channels()).filteredOn(channel -> channel.id().value().equals("x.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PRIMARY_SOURCE));
        assertThat(next.guide().channels()).filteredOn(channel -> channel.id().value().equals("y.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PASS_THROUGH));
    }

    private RefreshGuide guide(Optional<String> playlistLocation, EpgSource... sources) {
        GuideSettings settings = new GuideSettings(List.of(sources), playlistLocation);
        return new RefreshGuide(settings, source -> {
            SourceEpg document = documents.get(source.id());
            if (document == null) {
                throw new IllegalStateException("source " + source.id().value() + " is unavailable");
            }
            return document;
        }, location -> playlist, store);
    }

    private static Channel playlistChannel(String id, String displayName) {
        return new Channel(new ChannelId(id), displayName, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceChannel declared(String id, String displayName) {
        return new SourceChannel(id, displayName, null);
    }

    private static SourceEpg source(EpgSource source, SourceChannel... channels) {
        return new SourceEpg(source.id(), List.of(channels), List.of());
    }
}
