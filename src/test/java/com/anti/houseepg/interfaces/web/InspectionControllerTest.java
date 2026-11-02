package com.anti.houseepg.interfaces.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anti.houseepg.application.guide.DecideMatch;
import com.anti.houseepg.application.guide.BulkAcceptMatches;
import com.anti.houseepg.application.guide.InspectGuide;
import com.anti.houseepg.application.guide.ReviewMatches;
import com.anti.houseepg.domain.guide.model.Channel;
import com.anti.houseepg.domain.guide.model.ChannelId;
import com.anti.houseepg.domain.guide.model.Guide;
import com.anti.houseepg.domain.guide.model.Programme;
import com.anti.houseepg.domain.guide.model.Slot;
import com.anti.houseepg.domain.guide.model.SourceId;
import com.anti.houseepg.support.InMemoryChannelMatchStore;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
                        new BulkAcceptMatches(matches, () -> { })))
                .build();
    }
}
