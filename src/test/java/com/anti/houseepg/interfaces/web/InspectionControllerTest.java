package com.anti.houseepg.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anti.houseepg.application.guide.DecideMatch;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.MergeBreakdown;
import com.anti.houseepg.application.guide.AcceptMatches;
import com.anti.houseepg.application.guide.InspectGuide;
import com.anti.houseepg.application.guide.ReviewMatches;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceChannel;
import com.anti.houseepg.domain.guide.model.SourceEpg;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.domain.guide.model.SourceProgramme;
import com.anti.houseepg.domain.guide.policy.ChannelMatch;
import com.anti.houseepg.domain.guide.policy.MatchConfidence;
import com.anti.houseepg.domain.guide.policy.SourceChannelRef;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The inspection endpoints the UI's review, preview and guide panels use. */
class InspectionControllerTest {

    private final InMemoryGuideStore store = new InMemoryGuideStore();
    private final InMemoryChannelMatchStore matches = new InMemoryChannelMatchStore();

    @Test
    void theChannelEndpointServesTheRegistryAndItsMatches() throws Exception {
        registry();

        mvc().perform(get("/api/channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].id").value("amc.xx"))
                .andExpect(jsonPath("$.channels[0].name").value("AMC"))
                .andExpect(jsonPath("$.matches").isArray());
    }

    @Test
    void thePreviewEndpointServesTheMergedSlotsWithTheWinningSource() throws Exception {
        registry();

        mvc().perform(get("/api/preview/amc.xx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("amc.xx"))
                .andExpect(jsonPath("$.programmes[0].title").value("Film"))
                .andExpect(jsonPath("$.programmes[0].source").value("a"));
    }

    @Test
    void previewingAChannelTheGuideDoesNotServeIsNotFound() throws Exception {
        registry();

        mvc().perform(get("/api/preview/nope")).andExpect(status().isNotFound());
    }

    @Test
    void aCorrectionIsAcceptedAndReportedAsCurated() throws Exception {
        registry();

        mvc().perform(put("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"a\",\"sourceChannelId\":\"AMC.hu\",\"canonicalId\":\"amc.xx\","
                                + "\"decision\":\"corrected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalId").value("amc.xx"))
                .andExpect(jsonPath("$.curated").value(true));
    }

    @Test
    void aDecisionNamingAnUnknownChannelIsRejected() throws Exception {
        registry();

        mvc().perform(put("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"a\",\"sourceChannelId\":\"AMC.hu\",\"canonicalId\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void aDecisionWithAMissingSourceIsRejected() throws Exception {
        registry();

        mvc().perform(put("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canonicalId\":\"amc.xx\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptingASubsetWritesOnlyThatSubset() throws Exception {
        bothChannels();

        mvc().perform(post("/api/matches/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":false,\"decisions\":[{\"source\":\"a\","
                                + "\"sourceChannelId\":\"BRAVO.hu\",\"canonicalId\":\"bravo.xx\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.rows[0].sourceChannelId").value("BRAVO.hu"));

        assertThat(matches.decisions())
                .filteredOn(ChannelMatch::isCurated)
                .extracting(match -> match.source().channelId())
                .containsExactly("BRAVO.hu");
    }

    @Test
    void aDryRunReportsTheCountWithoutWriting() throws Exception {
        bothChannels();

        mvc().perform(post("/api/matches/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true,\"decisions\":[{\"source\":\"a\","
                                + "\"sourceChannelId\":\"BRAVO.hu\",\"canonicalId\":\"bravo.xx\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.dryRun").value(true));

        assertThat(matches.decisions()).noneMatch(ChannelMatch::isCurated);
    }

    @Test
    void theMergeEndpointExplainsTheProviderAndTheFeedThatResults() throws Exception {
        mergeFixture();

        mvc().perform(get("/api/merge/amc.xx").param("day", "2026-11-05").param("from", "00:00")
                        .param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("amc.xx"))
                .andExpect(jsonPath("$.name").value("AMC"))
                .andExpect(jsonPath("$.columns[0].source").value("a"))
                .andExpect(jsonPath("$.columns[0].channelId").value("AMC.hu"))
                .andExpect(jsonPath("$.columns[0].caseLabel").value("won"))
                .andExpect(jsonPath("$.columns[0].entries[0].title").value("Film"))
                .andExpect(jsonPath("$.columns[0].entries[0].fate").value("WON"))
                .andExpect(jsonPath("$.columns[0].window.gaps").isArray())
                .andExpect(jsonPath("$.result.entries[0].title").value("Film"))
                .andExpect(jsonPath("$.result.entries[0].source").value("a"));
    }

    @Test
    void aPreviewOrderNamingUnknownSourcesIsRejected() throws Exception {
        mergeFixture();

        mvc().perform(get("/api/merge/amc.xx").param("day", "2026-11-05").param("from", "00:00")
                        .param("hours", "24").param("order", "a", "ghost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void previewingAChannelNobodyServesIsNotFound() throws Exception {
        mergeFixture();

        mvc().perform(get("/api/merge/nope.xx").param("day", "2026-11-05").param("from", "00:00")
                        .param("hours", "24"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPreviewWithoutAnOrderUsesTheAppliedOne() throws Exception {
        mergeFixture();

        // The window is read in the running zone, so a whole day is what keeps this test zone-agnostic.
        mvc().perform(get("/api/merge/amc.xx").param("day", "2026-11-05").param("from", "00:00")
                        .param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].caseLabel").value("won"))
                .andExpect(jsonPath("$.result.entries[0].source").value("a"));
    }

    @Test
    void aPreviewWindowOfNoHoursIsRejected() throws Exception {
        mergeFixture();

        mvc().perform(get("/api/merge/amc.xx").param("day", "2026-11-05").param("from", "00:00")
                        .param("hours", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void aDecisionWithABlankCanonicalIdIsRejected() throws Exception {
        bothChannels();

        mvc().perform(post("/api/matches/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":false,\"decisions\":[{\"source\":\"a\","
                                + "\"sourceChannelId\":\"BRAVO.hu\",\"canonicalId\":\"\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    /** Both channels in the registry, one automatic match each. */
    private void bothChannels() {
        Channel amc = new Channel(new ChannelId("amc.xx"), "AMC", null, null, Channel.Origin.PLAYLIST);
        Channel bravo = new Channel(new ChannelId("bravo.xx"), "Bravo", null, null, Channel.Origin.PLAYLIST);
        store.saveRegistry(List.of(amc, bravo));
        matches.saveAuto(List.of(
                ChannelMatch.auto(new SourceChannelRef(new SourceId("a"), "AMC.hu"), new ChannelId("amc.xx"),
                        MatchConfidence.EXACT_ID),
                ChannelMatch.auto(new SourceChannelRef(new SourceId("a"), "BRAVO.hu"), new ChannelId("bravo.xx"),
                        MatchConfidence.NORMALIZED_NAME)));
    }

    private GuideSettings settings = new GuideSettings(List.of(), Optional.empty());

    /** One provider carrying one programme on the day the preview tests ask about. */
    private void mergeFixture() {
        Channel amc = new Channel(new ChannelId("amc.xx"), "AMC", null, null, Channel.Origin.PLAYLIST);
        store.saveRegistry(List.of(amc));
        OffsetDateTime start = OffsetDateTime.of(2026, 11, 5, 20, 0, 0, 0, ZoneOffset.UTC);
        store.saveNormalized(new SourceEpg(new SourceId("a"),
                List.of(new SourceChannel("AMC.hu", "AMC", null)),
                List.of(new SourceProgramme("AMC.hu", new Slot(start, start.plusHours(1)), "Film",
                        "A film", "movie", null, null))),
                Map.of());
        settings = new GuideSettings(List.of(EpgSource.of("a", "Alpha", "http://alpha/epg.xml", null)),
                Optional.empty());
    }

    private void registry() {
        Channel amc = new Channel(new ChannelId("amc.xx"), "AMC", null, null, Channel.Origin.PLAYLIST);
        store.saveRegistry(List.of(amc));
        OffsetDateTime start = OffsetDateTime.of(2026, 10, 29, 20, 0, 0, 0, ZoneOffset.UTC);
        store.saveMergedGuide(new Guide(List.of(amc), List.of(new Programme(new ChannelId("amc.xx"),
                new Slot(start, start.plusHours(2)), new SourceId("a"), "Film", null, "movie", null, null))),
                Instant.parse("2026-10-29T18:00:00Z"));
    }

    private MockMvc mvc() {
        InspectGuide inspect = new InspectGuide(store, matches);
        DecideMatch decide = new DecideMatch(store, matches, () -> {
        });
        return MockMvcBuilders
                .standaloneSetup(new InspectionController(inspect, decide, new ReviewMatches(inspect),
                        new AcceptMatches(matches, store, () -> { }),
                        new MergeBreakdown(store, matches, settings)))
                .build();
    }
}
