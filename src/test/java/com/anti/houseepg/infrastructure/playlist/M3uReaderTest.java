package com.anti.houseepg.infrastructure.playlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.policy.Glob;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The playlist is read for one reason: its {@code tvg-id}s become the emitted channel ids (ADR-0002).
 * A provider that publishes one group of real channels and sixty of films gives the reader a hundred
 * thousand entries the registry can never use, so the group filter lives here — before an entry is
 * built, matching the XMLTV exclusion list's "filter at the reader" rule (HOU-33, HOU-45).
 *
 * <p>{@code M3uReader} is a pure function, so every case below is a string and a list.
 */
class M3uReaderTest {

    /** The shape of the real feed: one group of channels, one of films, and one with no group at all. */
    private static final String PLAYLIST = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ch1.xx" tvg-name="Channel One" group-title="_=HU=_",Channel One
            http://example.invalid/ch1
            #EXTINF:-1 tvg-id="ch2.xx" tvg-name="Channel Two" group-title="_=HU=_",Channel Two
            http://example.invalid/ch2
            #EXTINF:-1 tvg-id="film1.xx" tvg-name="Film One" group-title="Film sorozatok",Film One
            http://example.invalid/film1
            #EXTINF:-1 tvg-id="film2.xx" tvg-name="Film Two" group-title="Film sorozatok",Film Two
            http://example.invalid/film2
            #EXTINF:-1 tvg-id="groupless.xx" tvg-name="No Group",No Group
            http://example.invalid/groupless
            """;

    @Test
    void withNoGroupsConfiguredEveryEntryIsRead() {
        // The old behaviour, and the one an unconfigured playlist must keep: no filter, no surprise.
        assertThat(ids(M3uReader.read(PLAYLIST)))
                .containsExactly("ch1.xx", "ch2.xx", "film1.xx", "film2.xx", "groupless.xx");
        assertThat(ids(M3uReader.read(PLAYLIST, List.of())))
                .containsExactly("ch1.xx", "ch2.xx", "film1.xx", "film2.xx", "groupless.xx");
    }

    @Test
    void onlyTheConfiguredGroupSurvives() {
        assertThat(ids(M3uReader.read(PLAYLIST, Glob.allOf(List.of("_=HU=_")))))
                .containsExactly("ch1.xx", "ch2.xx");
    }

    @Test
    void aGlobCanSelectAGroupFamily() {
        assertThat(ids(M3uReader.read(PLAYLIST, Glob.allOf(List.of("*sorozatok")))))
                .containsExactly("film1.xx", "film2.xx");
    }

    @Test
    void severalGroupsAreAWhitelistNotAnExclusion() {
        assertThat(ids(M3uReader.read(PLAYLIST, Glob.allOf(List.of("_=HU=_", "*sorozatok")))))
                .containsExactly("ch1.xx", "ch2.xx", "film1.xx", "film2.xx");
    }

    @Test
    void anEntryInNoGroupIsDroppedOnceAGroupIsConfigured() {
        // `groupless.xx` has no group-title, so it cannot be shown to belong to a group that was asked
        // for. Keeping it would make the whitelist "these groups plus everything else", which is not
        // what a whitelist means.
        assertThat(ids(M3uReader.read(PLAYLIST, Glob.allOf(List.of("_=HU=_")))))
                .doesNotContain("groupless.xx");
    }

    @Test
    void anEmptyOrUnquotedGroupIsNoGroupEither() {
        String playlist = """
                #EXTM3U
                #EXTINF:-1 tvg-id="empty.xx" group-title="",Empty
                http://example.invalid/empty
                #EXTINF:-1 tvg-id="bare.xx" group-title=HU,Bare
                http://example.invalid/bare
                #EXTINF:-1 tvg-id="kept.xx" group-title="HU",Kept
                http://example.invalid/kept
                """;

        assertThat(ids(M3uReader.read(playlist, Glob.allOf(List.of("HU"))))).containsExactly("kept.xx");
    }

    @Test
    void aGroupsWhitelistThatMatchesNothingYieldsNoChannelAtAll() {
        // Not "keep what we had": the reader's answer is the truth about the playlist as configured, and
        // the caller decides that an empty list means "keep the stored registry". Logged, never silent.
        assertThat(M3uReader.read(PLAYLIST, Glob.allOf(List.of("no-such-group")))).isEmpty();
    }

    @Test
    void theGroupFilterDoesNotChangeWhatAKeptEntrySays() {
        // Filtering is selection only: the id, name, logo, number and origin of a surviving entry have to
        // be exactly what they were without a filter, otherwise the registry would change for a reason
        // nobody asked for.
        Channel unfiltered = M3uReader.read(PLAYLIST).stream()
                .filter(channel -> channel.id().value().equals("ch1.xx"))
                .findFirst()
                .orElseThrow();
        Channel filtered = M3uReader.read(PLAYLIST, Glob.allOf(List.of("_=HU=_"))).stream()
                .filter(channel -> channel.id().value().equals("ch1.xx"))
                .findFirst()
                .orElseThrow();

        assertThat(filtered).isEqualTo(unfiltered);
    }

    @Test
    void anEntryWithoutATvgIdIsStillSkippedWithAGroupConfigured() {
        String playlist = """
                #EXTM3U
                #EXTINF:-1 tvg-name="No Id" group-title="HU",No Id
                http://example.invalid/noid
                #EXTINF:-1 tvg-id="kept.xx" group-title="HU",Kept
                http://example.invalid/kept
                """;

        assertThat(ids(M3uReader.read(playlist, Glob.allOf(List.of("HU"))))).containsExactly("kept.xx");
    }

    /**
     * The playlist's own set of ids is the registry, and ADR-0002 freezes it. This is the real-shape case:
     * one group holds every id, the rest hold none, and the filter must land the same set the unfiltered
     * read produced.
     */
    @Test
    void filteringToOneGroupKeepsEveryIdThatGroupDeclares() {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n");
        for (int index = 0; index < 50; index++) {
            playlist.append("#EXTINF:-1 tvg-id=\"ch").append(index)
                    .append(".xx\" group-title=\"_=HU=_\",Channel ").append(index).append('\n');
        }
        for (int index = 0; index < 50; index++) {
            playlist.append("#EXTINF:-1 tvg-id=\"film").append(index)
                    .append(".xx\" group-title=\"Film sorozatok\",Film ").append(index).append('\n');
        }

        String raw = playlist.toString();
        List<String> unfiltered = ids(M3uReader.read(raw)).stream()
                .filter(id -> id.startsWith("ch"))
                .toList();

        assertThat(ids(M3uReader.read(raw, Glob.allOf(List.of("_=HU=_"))))).isEqualTo(unfiltered);
    }

    private static List<String> ids(List<Channel> channels) {
        return channels.stream().map(channel -> channel.id().value()).toList();
    }
}
