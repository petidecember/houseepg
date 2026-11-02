package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ranked candidate list the review shows. It is a review aid: the ladder decides the order, fuzzy
 * only fills the slots the ladder left empty, and {@link ChannelMatching#match} never consults it.
 */
class ChannelMatchingCandidatesTest {

    private static final List<Channel> REGISTRY = List.of(
            playlistChannel("amc.xx", "AMC"),
            playlistChannel("bravo.xx", "Bravo"),
            playlistChannel("amc-hd.xx", "AMC HD"),
            playlistChannel("amc-sd.xx", "AMC SD"),
            playlistChannel("sky-sports-f1.xx", "Sky Sports F1"),
            playlistChannel("bbc-one.xx", "BBC One"),
            playlistChannel("bbc-one-hd.xx", "BBC One HD"));

    @Test
    void anExactIdIsTheFirstCandidate() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("amc.xx", "Something Else"), false);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().channel().id().value()).isEqualTo("amc.xx");
        assertThat(candidates.getFirst().confidence()).isEqualTo(MatchConfidence.EXACT_ID);
    }

    @Test
    void anExactNameIsTheFirstCandidate() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("zzz.xx", "Bravo"), false);

        assertThat(candidates.getFirst().channel().id().value()).isEqualTo("bravo.xx");
        assertThat(candidates.getFirst().confidence()).isEqualTo(MatchConfidence.EXACT_NAME);
    }

    @Test
    void aNormalizedIdIsTheFirstCandidate() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("AMC.xx", "Nope"), false);

        assertThat(candidates.getFirst().channel().id().value()).isEqualTo("amc.xx");
        assertThat(candidates.getFirst().confidence()).isEqualTo(MatchConfidence.NORMALIZED_ID);
    }

    @Test
    void aNormalizedNameIsTheFirstCandidate() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("zzz.xx", "AMC (HD)"), false);

        assertThat(candidates.getFirst().channel().id().value()).isEqualTo("amc-hd.xx");
        assertThat(candidates.getFirst().confidence()).isEqualTo(MatchConfidence.NORMALIZED_NAME);
    }

    @Test
    void theCandidateAdvertisingTheSameQualityTagComesFirst() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("AMC (HD).xx", "AMC (HD)"), false);

        assertThat(candidates).extracting(candidate -> candidate.channel().id().value())
                .startsWith("amc-hd.xx");
    }

    @Test
    void withoutAQualityTagRegistryOrderDecidesTheRest() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("AMC.xx", "AMC"), false);

        assertThat(candidates).extracting(candidate -> candidate.channel().id().value())
                .containsExactly("amc.xx", "amc-hd.xx", "amc-sd.xx");
    }

    @Test
    void fuzzyFillsTheSlotsTheLadderLeftEmpty() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("zzz.xx", "Sky Sport F1"), true);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().channel().id().value()).isEqualTo("sky-sports-f1.xx");
        assertThat(candidates.getFirst().confidence()).isEqualTo(MatchConfidence.FUZZY_NAME);
        assertThat(candidates.getFirst().score()).isGreaterThanOrEqualTo(ChannelMatching.FUZZY_THRESHOLD);
    }

    @Test
    void withoutFuzzyAnUnmatchedChannelHasNoCandidates() {
        assertThat(ChannelMatching.candidates(REGISTRY, declared("zzz.xx", "Sky Sport F1"), false))
                .isEmpty();
    }

    @Test
    void fuzzyNeverReturnsMoreThanThreeDistinctChannels() {
        List<Channel> many = List.of(
                playlistChannel("bbc-one.xx", "BBC One"),
                playlistChannel("bbc-one-hd.xx", "BBC One HD"),
                playlistChannel("bbc-one-sd.xx", "BBC One SD"),
                playlistChannel("bbc-one-plus.xx", "BBC One +1"),
                playlistChannel("bbc-one-extra.xx", "BBC One Extra"));

        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(many, declared("zzz.xx", "BBC One Extra"), true);

        assertThat(candidates).hasSizeLessThanOrEqualTo(ChannelMatching.MAX_CANDIDATES);
        assertThat(candidates).extracting(candidate -> candidate.channel().id().value())
                .doesNotHaveDuplicates();
    }

    @Test
    void anEmptyRegistryHasNoCandidates() {
        assertThat(ChannelMatching.candidates(List.of(), declared("zzz.xx", "Anything"), true)).isEmpty();
    }

    @Test
    void theLadderHitStaysFirstWhenFuzzyAlsoHasSuggestions() {
        List<ChannelMatching.Candidate> candidates =
                ChannelMatching.candidates(REGISTRY, declared("amc.xx", "AMC"), true);

        assertThat(candidates.getFirst().channel().id().value()).isEqualTo("amc.xx");
        assertThat(candidates.getFirst().confidence()).isEqualTo(MatchConfidence.EXACT_ID);
        assertThat(candidates).hasSize(ChannelMatching.MAX_CANDIDATES);
    }

    @Test
    void aFuzzySuggestionNeverBecomesAnAutomaticMatch() {
        SourceEpg source = new SourceEpg(new SourceId("a"),
                List.of(declared("zzz.xx", "Sky Sport F1")), List.of());

        ChannelMatching.Match match = ChannelMatching.match(REGISTRY, List.of(source));

        assertThat(match.matches()).singleElement().satisfies(found -> {
            assertThat(found.state()).isEqualTo(MatchState.UNMATCHED);
            assertThat(found.confidence()).isEqualTo(MatchConfidence.NONE);
            assertThat(found.canonicalId().value()).isEqualTo("zzz.xx");
        });
    }

    private static Channel playlistChannel(String id, String name) {
        return new Channel(new ChannelId(id), name, null, null, Channel.Origin.PLAYLIST);
    }

    private static SourceChannel declared(String id, String name) {
        return new SourceChannel(id, name, null);
    }
}
