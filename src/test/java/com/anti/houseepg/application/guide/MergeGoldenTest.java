package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.policy.ChannelMatching;
import com.anti.houseepg.domain.guide.policy.PriorityMerge;
import com.anti.houseepg.infrastructure.epg.XmltvReader;
import com.anti.houseepg.infrastructure.playlist.M3uReader;
import com.anti.houseepg.interfaces.web.XmltvFeedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The merge rules are the risky part of HouseEPG, so their exact output is the contract: this asserts
 * the whole rendered feed byte for byte against a golden file (design §11).
 *
 * <p>The fixture pair deliberately contains every interesting case: an overlapping programme that
 * must be dropped whole, a programme that straddles the winner and must also go, a gap the second
 * source fills, a field that must not be mixed in from the loser, and a channel no registry entry
 * matches that must survive as a pass-through.
 */
class MergeGoldenTest {

    @Test
    void renderedFeedMatchesTheGoldenFile() {
        List<Channel> registry = M3uReader.read(read("/fixtures/overlap-playlist.m3u"));
        List<SourceEpg> sources = List.of(
                epg("a", "/fixtures/overlap-source-a.xml"),
                epg("b", "/fixtures/overlap-source-b.xml"));

        ChannelMatching.Match match = ChannelMatching.match(registry, sources);
        Guide guide = PriorityMerge.merge(match.channels(), sources, match.canonicalIds(),
                List.of(new SourceId("a"), new SourceId("b")));

        assertThat(new XmltvFeedWriter().write(guide)).isEqualTo(read("/fixtures/overlap-expected.xml").trim());
    }

    @Test
    void goldenFileHoldsTheRulesTheDesignPromises() {
        String golden = read("/fixtures/overlap-expected.xml");

        // An overlap drops the loser whole: neither of source B's two BBC One programmes survives.
        assertThat(golden).doesNotContain("<title>Heat</title>");
        // The winner is emitted verbatim, so source A's description is kept rather than source B's.
        assertThat(golden).contains("<title>EastEnders</title><desc>Full synopsis</desc>");
        assertThat(golden).doesNotContain("Short desc");
        // The gap source A leaves is filled by source B.
        assertThat(golden).contains("<title>News</title>");
        // An unmatched channel keeps its own id rather than being dropped or renamed.
        assertThat(golden).contains("channel=\"Dave\"");
        // Every channel id in the feed is the playlist's tvg-id, never an invented one.
        assertThat(golden).contains("id=\"bbc1.uk\"", "id=\"itv1.uk\"");
    }

    private static SourceEpg epg(String sourceId, String resource) {
        try (InputStream input = resource(resource)) {
            return new XmltvReader().read(new SourceId(sourceId), input);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(String resource) {
        try (InputStream input = resource(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String path) {
        InputStream input = MergeGoldenTest.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("missing test resource " + path);
        }
        return input;
    }
}
