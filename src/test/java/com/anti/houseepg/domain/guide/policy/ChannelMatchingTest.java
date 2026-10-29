package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How a source channel finds its canonical channel, and what evidence the match records. */
class ChannelMatchingTest {

    private static final List<Channel> PLAYLIST = List.of(
            playlistChannel("amc.xx", "AMC"),
            playlistChannel("bravo.xx", "Bravo"));

    @Test
    void theSameChannelSpelledByTwoProvidersResolvesToOneCanonical() {
        ChannelMatching.Match match = match(source("a", declared("AMC.xx", "AMC")),
                source("b", declared("AMC (HD).xx", "AMC (HD)")));

        assertThat(match.canonicalIds().values()).containsOnly(new ChannelId("amc.xx"));
        assertThat(match.matches()).extracting(ChannelMatch::state).containsOnly(MatchState.AUTO);
        // "AMC.xx" differs from the playlist id only by case, so its display name is the exact hit;
        // the second provider needs the normalized key.
        assertThat(match.matches()).extracting(ChannelMatch::confidence)
                .containsExactly(MatchConfidence.EXACT_NAME, MatchConfidence.NORMALIZED_ID);
    }

    @Test
    void anExactSourceChannelIdBeatsAConflictingDisplayName() {
        ChannelMatching.Match match = match(source("a", declared("amc.xx", "Bravo")));

        assertThat(match.matches()).singleElement().satisfies(found -> {
            assertThat(found.canonicalId().value()).isEqualTo("amc.xx");
            assertThat(found.confidence()).isEqualTo(MatchConfidence.EXACT_ID);
        });
    }

    @Test
    void anExactDisplayNameIsUsedWhenTheIdIsUnknown() {
        ChannelMatching.Match match = match(source("a", declared("unknown.xx", "Bravo")));

        assertThat(match.matches()).singleElement().satisfies(found -> {
            assertThat(found.canonicalId().value()).isEqualTo("bravo.xx");
            assertThat(found.confidence()).isEqualTo(MatchConfidence.EXACT_NAME);
        });
    }

    @Test
    void distinctTvgIdsStayDistinctWhenOnlyQualityTagsTellThemApart() {
        // The playlist keeps HD and SD apart with two ids; the comparison key strips exactly that tag.
        List<Channel> registry = List.of(
                playlistChannel("amc-hd.xx", "AMC HD"),
                playlistChannel("amc-sd.xx", "AMC SD"));

        ChannelMatching.Match match = ChannelMatching.match(registry,
                List.of(source("a", declared("AMC (HD).xx", "AMC (HD)"), declared("AMC (SD).xx", "AMC (SD)"))));

        assertThat(match.matches()).extracting(found -> found.canonicalId().value())
                .containsExactly("amc-hd.xx", "amc-sd.xx");
        assertThat(match.channels()).extracting(channel -> channel.id().value())
                .containsExactly("amc-hd.xx", "amc-sd.xx");
    }

    @Test
    void aChannelThatMatchesNothingIsKeptAsAPassThrough() {
        ChannelMatching.Match match = match(source("a", declared("zzz.xx", "Zzz")));

        assertThat(match.matches()).singleElement().satisfies(found -> {
            assertThat(found.canonicalId().value()).isEqualTo("zzz.xx");
            assertThat(found.state()).isEqualTo(MatchState.UNMATCHED);
            assertThat(found.confidence()).isEqualTo(MatchConfidence.NONE);
        });
        assertThat(match.channels()).filteredOn(channel -> channel.id().value().equals("zzz.xx"))
                .singleElement()
                .satisfies(channel -> assertThat(channel.origin()).isEqualTo(Channel.Origin.PASS_THROUGH));
    }

    @Test
    void aCuratedDecisionBeatsTheEvidence() {
        SourceChannelRef ref = new SourceChannelRef(new SourceId("a"), "amc.xx");
        Map<SourceChannelRef, ChannelMatch> decisions = Map.of(ref,
                ChannelMatch.corrected(ref, new ChannelId("bravo.xx"), MatchConfidence.EXACT_ID));

        ChannelMatching.Match match = ChannelMatching.match(PLAYLIST,
                List.of(source("a", declared("amc.xx", "AMC"))), decisions);

        assertThat(match.matches()).singleElement().satisfies(found -> {
            assertThat(found.canonicalId().value()).isEqualTo("bravo.xx");
            assertThat(found.state()).isEqualTo(MatchState.CORRECTED);
        });
    }

    @Test
    void aStaleCuratedDecisionFallsBackToNameLookup() {
        SourceChannelRef ref = new SourceChannelRef(new SourceId("a"), "amc (hd).xx");
        Map<SourceChannelRef, ChannelMatch> decisions = Map.of(ref,
                ChannelMatch.confirmed(ref, new ChannelId("gone.xx"), MatchConfidence.EXACT_ID));

        ChannelMatching.Match match = ChannelMatching.match(PLAYLIST,
                List.of(source("a", declared("amc (hd).xx", "AMC (HD)"))), decisions);

        assertThat(match.matches()).singleElement().satisfies(found -> {
            assertThat(found.canonicalId().value()).isEqualTo("amc.xx");
            assertThat(found.state()).isEqualTo(MatchState.AUTO);
        });
    }

    @Test
    void everySourceChannelGetsExactlyOneMatch() {
        ChannelMatching.Match match = match(
                source("a", declared("amc.xx", "AMC"), declared("aaa.xx", "Aaa")),
                source("b", declared("bravo.xx", "Bravo"), declared("bbb.xx", "Bbb")));

        assertThat(match.matches()).hasSize(4);
        assertThat(match.matches()).extracting(ChannelMatch::source).doesNotHaveDuplicates();
        assertThat(match.canonicalIds()).hasSize(4);
    }

    private static ChannelMatching.Match match(SourceEpg... sources) {
        return ChannelMatching.match(PLAYLIST, List.of(sources));
    }

    private static Channel playlistChannel(String id, String displayName) {
        return new Channel(new ChannelId(id), displayName, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceChannel declared(String id, String displayName) {
        return new SourceChannel(id, displayName, null);
    }

    private static SourceEpg source(String id, SourceChannel... channels) {
        return new SourceEpg(new SourceId(id), List.of(channels), List.of());
    }
}
