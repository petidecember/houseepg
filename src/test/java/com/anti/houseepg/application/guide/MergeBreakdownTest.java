package com.anti.houseepg.application.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.PriorityMerge;
import com.anti.houseepg.domain.guide.policy.SlotClaim;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The merge preview's whole promise is that it explains the merge the feed actually runs, so the tests
 * below walk the same fixture twice: once through the breakdown and once through {@link PriorityMerge}.
 */
class MergeBreakdownTest {

    private static final OffsetDateTime WINDOW_START = at(20, 0);
    private static final Slot WINDOW = new Slot(WINDOW_START, at(24, 0));

    private final InMemoryGuideStore store = new InMemoryGuideStore();
    private final InMemoryChannelMatchStore matches = new InMemoryChannelMatchStore();

    @Test
    void everyProviderSaysWhatItWonAndWhatItLostTo() {
        fixture();
        store.saveNormalized(alpha(), alphaIds());
        store.saveNormalized(beta(), betaIds());
        matches.saveAuto(List.of(
                ChannelMatch.auto(new SourceChannelRef(new SourceId("a"), "AMC.a"), new ChannelId("amc.xx"),
                        MatchConfidence.EXACT_ID),
                ChannelMatch.auto(new SourceChannelRef(new SourceId("a"), "OTHER.a"), new ChannelId("other.xx"),
                        MatchConfidence.EXACT_NAME),
                ChannelMatch.auto(new SourceChannelRef(new SourceId("b"), "AMC.b"), new ChannelId("amc.xx"),
                        MatchConfidence.NORMALIZED_NAME)));

        MergeBreakdown.Breakdown breakdown = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW,
                Optional.empty());

        assertThat(breakdown.channel().label()).isEqualTo("AMC");
        assertThat(breakdown.columns()).extracting(MergeBreakdown.Column::source)
                .extracting(SourceId::value).containsExactly("a", "b");
        assertThat(breakdown.columns().get(0).channelId()).isEqualTo("AMC.a");
        assertThat(breakdown.columns().get(0).sourceChannelName()).isEqualTo("AMC HD");
        assertThat(breakdown.columns().get(0).entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.programme().title()).isEqualTo("Film");
                    assertThat(entry.fate()).isEqualTo(SlotClaim.Fate.WON);
                    assertThat(entry.lostTo()).isNull();
                    // The winner has no icon and the entry it displaced had one.
                    assertThat(entry.weaker()).containsExactly(
                            new MergeBreakdown.Weakness("icon", new SourceId("b")));
                });
        assertThat(breakdown.columns().get(1).entries()).extracting(entry -> entry.programme().title())
                .containsExactly("Film B", "Late B");
        assertThat(breakdown.columns().get(1).entries().get(0).fate()).isEqualTo(SlotClaim.Fate.LOST);
        assertThat(breakdown.columns().get(1).entries().get(0).lostTo()).isEqualTo(new SourceId("a"));
        assertThat(breakdown.columns().get(1).entries().get(1).fate()).isEqualTo(SlotClaim.Fate.WON);
    }

    @Test
    void aColumnCarriesTheWindowItFillsAndTheWindowItLeavesEmpty() {
        fixture();
        store.saveNormalized(alpha(), alphaIds());
        store.saveNormalized(beta(), betaIds());
        matches.saveAuto(automatic());

        MergeBreakdown.Column a = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW, Optional.empty())
                .columns().get(0);
        MergeBreakdown.Column b = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW, Optional.empty())
                .columns().get(1);

        // a serves the first hour and b the last, so a covers one hour of the window and leaves the
        // other three as stripes.
        assertThat(a.window().coverage()).extracting(gap -> gap.slot().start()).containsExactly(WINDOW_START);
        assertThat(a.window().coverage().get(0).slot().stop()).isEqualTo(at(21, 0));
        assertThat(a.window().coveredHours()).isEqualTo(1.0);
        assertThat(a.window().gapHours()).isEqualTo(3.0);
        assertThat(b.window().coverage()).singleElement()
                .satisfies(gap -> assertThat(gap.slot()).isEqualTo(new Slot(at(23, 0), at(24, 0))));
        assertThat(b.window().gapHours()).isEqualTo(3.0);
    }

    @Test
    void theSponsoredOrderDecidesTheWinnerAndTheDraftOrderFlipsIt() {
        fixture();
        store.saveNormalized(alpha(), alphaIds());
        store.saveNormalized(beta(), betaIds());
        matches.saveAuto(automatic());

        MergeBreakdown.Breakdown applied = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW, Optional.empty());
        assertThat(applied.result().entries()).extracting(entry -> entry.programme().winningSource())
                .extracting(SourceId::value).containsExactly("a", "b");

        MergeBreakdown.Breakdown drafted = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW,
                Optional.of(List.of(new SourceId("b"), new SourceId("a"))));
        assertThat(drafted.columns()).extracting(MergeBreakdown.Column::source)
                .extracting(SourceId::value).containsExactly("b", "a");
        assertThat(drafted.result().entries()).extracting(entry -> entry.programme().winningSource())
                .extracting(SourceId::value).containsExactly("b", "b");
        assertThat(drafted.result().entries()).extracting(entry -> entry.programme().title())
                .containsExactly("Film B", "Late B");
        // The entry that lost in the draft order is the one that won in the applied one.
        assertThat(drafted.columns().get(1).entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.programme().title()).isEqualTo("Film");
                    assertThat(entry.fate()).isEqualTo(SlotClaim.Fate.LOST);
                    assertThat(entry.lostTo()).isEqualTo(new SourceId("b"));
                });
    }

    @Test
    void aDraftOrderThatNamesNoRealArrangementIsRejected() {
        fixture();

        assertThatThrownBy(() -> breakdown().breakdown(new ChannelId("amc.xx"), WINDOW,
                Optional.of(List.of(new SourceId("a")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names exactly the configured sources");
        assertThatThrownBy(() -> breakdown().breakdown(new ChannelId("amc.xx"), WINDOW,
                Optional.of(List.of(new SourceId("a"), new SourceId("ghost")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProviderThatCarriesTheChannelElsewhereSaysWhereItWent() {
        fixture();
        store.saveNormalized(alpha(), alphaIds());
        store.saveNormalized(beta(), betaIds());
        matches.saveAuto(automatic());

        MergeBreakdown.Breakdown breakdown = breakdown().breakdown(new ChannelId("other.xx"), WINDOW,
                Optional.empty());

        assertThat(breakdown.channel().label()).isEqualTo("Other");
        // Beta carries only AMC, and AMC is not the channel being previewed, so its column names
        // amc.xx instead of staying silent about where its data went.
        assertThat(breakdown.columns()).extracting(MergeBreakdown.Column::source)
                .extracting(SourceId::value).containsExactly("a", "b");
        MergeBreakdown.Column beta = breakdown.columns().get(1);
        assertThat(beta.channelId()).isNull();
        assertThat(beta.carriesElsewhere()).isEqualTo("amc.xx");
        assertThat(beta.entries()).isEmpty();
        assertThat(beta.window().coveredHours()).isZero();
        assertThat(beta.caseLabel()).isEqualTo("carries amc.xx");
        // Alpha does carry it, so its column is the normal case.
        assertThat(breakdown.columns().get(0).channelId()).isEqualTo("OTHER.a");
        assertThat(breakdown.columns().get(0).caseLabel()).isEqualTo("won");
        assertThat(breakdown.result().entries()).extracting(entry -> entry.programme().title())
                .containsExactly("News Other");
    }

    @Test
    void theResultColumnIgnoresProgrammesOutsideThePreviewWindow() {
        // The result column carries the channel's whole feed, so a programme from before the window
        // reaches the window arithmetic and must contribute nothing.
        store.saveRegistry(List.of(new Channel(new ChannelId("amc.xx"), "AMC", null, "1",
                Channel.Origin.PLAYLIST)));
        store.saveNormalized(new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC.a", "AMC HD", null)),
                List.of(new SourceProgramme("AMC.a", new Slot(at(16, 0).minusDays(1),
                                at(23, 0).minusDays(1)), "Yesterday", null, null, null, null),
                        new SourceProgramme("AMC.a", new Slot(at(20, 0), at(21, 0)), "Film", null, null, null,
                                null))), alphaIds());
        matches.saveAuto(automatic());

        MergeBreakdown.Breakdown breakdown = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW,
                Optional.empty());

        // The result column carries the channel's whole feed, exactly as the live guide does — the
        // window only decides what gets drawn, so the day-before programme is still listed.
        assertThat(breakdown.result().entries()).extracting(entry -> entry.programme().title())
                .containsExactly("Yesterday", "Film");
        // One span, clipped to the window; the day before it is not folded in as an inverted gap.
        assertThat(breakdown.result().window().coverage()).hasSize(1);
        assertThat(breakdown.result().window().coverage().get(0).slot().start()).isEqualTo(WINDOW.start());
        assertThat(breakdown.result().window().coverage().get(0).slot().stop()).isEqualTo(at(21, 0));
    }

    @Test
    void aChannelNobodyServesIsNotFoundRatherThanAnEmptyPreview() {
        fixture();

        assertThat(breakdown().breakdown(new ChannelId("nope.xx"), WINDOW, Optional.empty()).channel())
                .isNull();
        assertThat(breakdown().breakdown(new ChannelId("nope.xx"), WINDOW, Optional.empty()).columns())
                .isEmpty();
    }

    /**
     * The pickers are bounded by the channel's whole published span, not by the window on screen: a
     * window-clipped span is what made every day but today unselectable (HOU-25, m03255).
     */
    @Test
    void availableSpansTheWholeGuideNotJustThePreviewWindow() {
        store.saveRegistry(List.of(new Channel(new ChannelId("amc.xx"), "AMC", null, "1",
                Channel.Origin.PLAYLIST)));
        store.saveNormalized(new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC.a", "AMC HD", null)),
                List.of(new SourceProgramme("AMC.a", new Slot(at(16, 0).minusDays(2),
                                at(17, 0).minusDays(2)), "Older", null, null, null, null),
                        new SourceProgramme("AMC.a", new Slot(at(20, 0), at(21, 0)), "Film", null, null, null,
                                null))), alphaIds());
        matches.saveAuto(automatic());

        MergeBreakdown.Breakdown breakdown = breakdown().breakdown(new ChannelId("amc.xx"), WINDOW,
                Optional.empty());

        // Two days before the window it still knows the guide reaches back that far.
        assertThat(breakdown.available().first()).isEqualTo(at(16, 0).minusDays(2));
        assertThat(breakdown.available().last()).isEqualTo(at(21, 0));
    }

    /**
     * The anti-drift test: for every arrangement of the sources and every channel, the entries the
     * preview reports as won inside the window are exactly the programmes the live merge serves there.
     */
    @Test
    void thePreviewAgreesWithTheLiveMergeOnEveryArrangement() {
        fixture();
        store.saveNormalized(alpha(), alphaIds());
        store.saveNormalized(beta(), betaIds());
        matches.saveAuto(automatic());

        List<List<SourceId>> arrangements = List.of(
                List.of(new SourceId("a"), new SourceId("b")),
                List.of(new SourceId("b"), new SourceId("a")));

        for (List<SourceId> arrangement : arrangements) {
            for (Channel channel : store.registry()) {
                MergeBreakdown.Breakdown breakdown = breakdown().breakdown(channel.id(), WINDOW,
                        Optional.of(arrangement));

                List<String> previewed = breakdown.columns().stream()
                        .flatMap(column -> column.entries().stream()
                                .filter(entry -> entry.fate() == SlotClaim.Fate.WON)
                                .map(entry -> column.source().value() + "|" + entry.programme().title()))
                        .sorted()
                        .toList();

                List<String> served = PriorityMerge.merge(store.registry(), documents(), canonicalIds(),
                                arrangement)
                        .programmes().stream()
                        .filter(programme -> programme.channelId().equals(channel.id()))
                        .filter(programme -> WINDOW.overlaps(programme.slot()))
                        .map(programme -> programme.winningSource().value() + "|" + programme.title())
                        .sorted()
                        .toList();

                assertThat(previewed)
                        .as("channel %s in arrangement %s", channel.id(), arrangement)
                        .containsExactlyElementsOf(served);
            }
        }
    }

    /** The applied order, highest priority first; a reorder would rebuild this list. */
    private MergeBreakdown breakdown() {
        return new MergeBreakdown(store, matches,
                new GuideSettings(sources(), Optional.empty(), GuideSettings.DEFAULT_RETENTION));
    }

    /** The matches the automatic pass would have written for this fixture. */
    private static List<ChannelMatch> automatic() {
        return List.of(
                ChannelMatch.auto(new SourceChannelRef(new SourceId("a"), "AMC.a"), new ChannelId("amc.xx"),
                        MatchConfidence.EXACT_ID),
                ChannelMatch.auto(new SourceChannelRef(new SourceId("a"), "OTHER.a"), new ChannelId("other.xx"),
                        MatchConfidence.EXACT_NAME),
                ChannelMatch.auto(new SourceChannelRef(new SourceId("b"), "AMC.b"), new ChannelId("amc.xx"),
                        MatchConfidence.NORMALIZED_NAME));
    }

    private static List<EpgSource> sources() {
        return List.of(EpgSource.of("a", "Alpha", "http://a/epg", null),
                EpgSource.of("b", "Beta", "http://b/epg", null));
    }

    private static List<SourceEpg> documents() {
        return List.of(alpha(), beta());
    }

    private java.util.Map<SourceChannelRef, ChannelId> canonicalIds() {
        java.util.Map<SourceChannelRef, ChannelId> ids = new java.util.LinkedHashMap<>();
        ids.put(new SourceChannelRef(new SourceId("a"), "AMC.a"), new ChannelId("amc.xx"));
        ids.put(new SourceChannelRef(new SourceId("a"), "OTHER.a"), new ChannelId("other.xx"));
        ids.put(new SourceChannelRef(new SourceId("b"), "AMC.b"), new ChannelId("amc.xx"));
        return ids;
    }

    private void fixture() {
        store.saveRegistry(List.of(
                new Channel(new ChannelId("amc.xx"), "AMC", null, "1", Channel.Origin.PLAYLIST),
                new Channel(new ChannelId("other.xx"), "Other", null, null, Channel.Origin.PLAYLIST)));
    }

    /** Alpha carries AMC and Other; its AMC entry has the description and no icon. */
    private static SourceEpg alpha() {
        return new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC.a", "AMC HD", null), new SourceChannel("OTHER.a", "Other", null)),
                List.of(new SourceProgramme("AMC.a", new Slot(at(20, 0), at(21, 0)), "Film", "A film", "movie",
                                null, null),
                        new SourceProgramme("OTHER.a", new Slot(at(20, 0), at(21, 0)), "News Other", null, "news",
                                null, null)));
    }

    /** Beta carries AMC only; its Film entry has the icon, and it serves the late hour alone. */
    private static SourceEpg beta() {
        return new SourceEpg(new SourceId("b"),
                List.of(new SourceChannel("AMC.b", "AMC", null)),
                List.of(new SourceProgramme("AMC.b", new Slot(at(20, 0), at(21, 0)), "Film B", null, "movie",
                                null, "http://b/icon.png"),
                        new SourceProgramme("AMC.b", new Slot(at(23, 0), at(24, 0)), "Late B", null, null,
                                null, null)));
    }

    private java.util.Map<SourceChannelRef, ChannelId> alphaIds() {
        java.util.Map<SourceChannelRef, ChannelId> ids = new java.util.LinkedHashMap<>();
        ids.put(new SourceChannelRef(new SourceId("a"), "AMC.a"), new ChannelId("amc.xx"));
        ids.put(new SourceChannelRef(new SourceId("a"), "OTHER.a"), new ChannelId("other.xx"));
        return ids;
    }

    private java.util.Map<SourceChannelRef, ChannelId> betaIds() {
        java.util.Map<SourceChannelRef, ChannelId> ids = new java.util.LinkedHashMap<>();
        ids.put(new SourceChannelRef(new SourceId("b"), "AMC.b"), new ChannelId("amc.xx"));
        return ids;
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(2026, 11, 5, hour % 24, minute, 0, 0, ZoneOffset.UTC)
                .plusDays(hour / 24);
    }
}
