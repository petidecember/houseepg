package com.anti.houseepg.domain.guide.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A declaration list is the registry's input (design §6.4, ADR-0002), and the id is its identity: a feed
 * that names one id twice names one channel. The store keeps one row per id, so a repeated declaration has
 * to be resolved here — otherwise the write fails on the repeat and the registry is left truncated.
 */
class ChannelDeclarationsTest {

    @Test
    void aRepeatedIdBecomesOneChannelAndTheRepeatIsReported() {
        ChannelDeclarations.Collapse collapse = ChannelDeclarations.distinctById(List.of(
                channel("rtl2.hu", "RTL KETTŐ HD"),
                channel("hirtv.hu", "Hír TV"),
                channel("rtl2.hu", "RTL KETTŐ H.265")));

        assertThat(collapse.channels()).extracting(Channel::id)
                .containsExactly(new ChannelId("rtl2.hu"), new ChannelId("hirtv.hu"));
        assertThat(collapse.duplicates()).containsExactly(new ChannelId("rtl2.hu"));
        assertThat(collapse.collapsedAnything()).isTrue();
    }

    @Test
    void theFirstDeclarationSurvivesSoTheRegistryKeepsTheNameTheOperatorSawFirst() {
        ChannelDeclarations.Collapse collapse = ChannelDeclarations.distinctById(List.of(
                channel("rtl2.hu", "RTL KETTŐ HD"),
                channel("rtl2.hu", "RTL KETTŐ H.265")));

        assertThat(collapse.channels()).singleElement()
                .satisfies(channel -> assertThat(channel.displayName()).isEqualTo("RTL KETTŐ HD"));
    }

    @Test
    void declarationOrderIsPreservedBecauseTheRegistryListsInThatOrder() {
        ChannelDeclarations.Collapse collapse = ChannelDeclarations.distinctById(List.of(
                channel("z.hu", "Zed"),
                channel("a.hu", "Ay"),
                channel("z.hu", "Zed again")));

        assertThat(collapse.channels()).extracting(Channel::id)
                .containsExactly(new ChannelId("z.hu"), new ChannelId("a.hu"));
    }

    @Test
    void anIdDeclaredThreeTimesIsReportedTwice() {
        ChannelDeclarations.Collapse collapse = ChannelDeclarations.distinctById(List.of(
                channel("rtl2.hu", "one"), channel("rtl2.hu", "two"), channel("rtl2.hu", "three")));

        assertThat(collapse.channels()).hasSize(1);
        assertThat(collapse.duplicates()).hasSize(2);
    }

    @Test
    void aListWithoutRepeatsIsReturnedUnchangedAndReportsNothing() {
        List<Channel> declared = List.of(channel("a.hu", "Ay"), channel("b.hu", "Bee"));

        ChannelDeclarations.Collapse collapse = ChannelDeclarations.distinctById(declared);

        assertThat(collapse.channels()).isEqualTo(declared);
        assertThat(collapse.duplicates()).isEmpty();
        assertThat(collapse.collapsedAnything()).isFalse();
    }

    @Test
    void noDeclarationsAtAllIsNotAFailure() {
        assertThat(ChannelDeclarations.distinctById(List.of()).channels()).isEmpty();
        assertThat(ChannelDeclarations.distinctById(null).channels()).isEmpty();
    }

    @Test
    void aStoredRegistryMissingDeclaredIdsReportsExactlyThose() {
        List<Channel> stored = List.of(channel("atv.hu", "ATV"), channel("tv2.hu", "TV2"));

        assertThat(ChannelDeclarations.missingFrom(stored,
                List.of(channel("atv.hu", "ATV"), channel("tv2.hu", "TV2"), channel("hirtv.hu", "Hír TV"))))
                .extracting(ChannelId::value)
                .containsExactly("hirtv.hu");
    }

    @Test
    void aStoredRegistryThatCoversTheDeclarationsReportsNothing() {
        List<Channel> stored = List.of(channel("atv.hu", "ATV"), channel("tv2.hu", "TV2"));

        assertThat(ChannelDeclarations.missingFrom(stored,
                List.of(channel("atv.hu", "ATV"), channel("tv2.hu", "TV2")))).isEmpty();
        // A shorter declaration list is not a short registry: an id the source dropped is not missing.
        assertThat(ChannelDeclarations.missingFrom(stored, List.of(channel("atv.hu", "ATV")))).isEmpty();
        assertThat(ChannelDeclarations.missingFrom(stored, List.of())).isEmpty();
    }

    @Test
    void theMissingIdsAreReportedOnceEachInDeclarationOrder() {
        List<Channel> stored = List.of(channel("atv.hu", "ATV"));

        assertThat(ChannelDeclarations.missingFrom(stored,
                List.of(channel("tv2.hu", "TV2"), channel("hirtv.hu", "Hír TV"), channel("tv2.hu", "TV2 HD"))))
                .extracting(ChannelId::value)
                .containsExactly("tv2.hu", "hirtv.hu");
    }

    @Test
    void aMissingRegistryIsNotAFailure() {
        assertThat(ChannelDeclarations.missingFrom(List.of(), List.of(channel("atv.hu", "ATV"))))
                .extracting(ChannelId::value).containsExactly("atv.hu");
        assertThat(ChannelDeclarations.missingFrom(null, null)).isEmpty();
        assertThat(ChannelDeclarations.missingFrom(null, List.of())).isEmpty();
    }

    private static Channel channel(String id, String displayName) {
        return new Channel(new ChannelId(id), displayName, null, null, Channel.Origin.PRIMARY_SOURCE);
    }
}
