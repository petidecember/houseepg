package com.anti.houseepg.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anti.houseepg.application.config.DumpConfiguration;
import com.anti.houseepg.application.config.ReloadConfiguration;
import com.anti.houseepg.application.config.ReorderSources;
import com.anti.houseepg.application.config.ReseedRegistry;
import com.anti.houseepg.application.config.port.SourceOrderStore;
import com.anti.houseepg.application.guide.GuideSettings;
import com.anti.houseepg.application.guide.port.Credentials;
import com.anti.houseepg.application.guide.port.EpgSource;
import com.anti.houseepg.support.InMemoryGuideStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The HTTP surface the UI may use: order, reload, dump and the explicit re-seed (ADR-0005). */
class ConfigurationControllerTest {

    private final GuideSettings settings = settings("a", "b");
    private final List<String> dumped = new ArrayList<>();
    private final InMemoryGuideStore guideStore = new InMemoryGuideStore();

    @Test
    void thePriorityEndpointListsTheSourcesInMergeOrderWithoutCredentials() throws Exception {
        mvc().perform(get("/api/priority"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].id").value("a"))
                .andExpect(jsonPath("$.sources[1].id").value("b"));
    }

    @Test
    void reorderingReportsThatItWasPersisted() throws Exception {
        String body = mvc().perform(put("/api/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"b\",\"a\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0]").value("b"))
                .andExpect(jsonPath("$.persisted").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("credentials");
        assertThat(settings.sources().getFirst().id().value()).isEqualTo("b");
    }

    @Test
    void reorderingReportsAPersistFailureWithoutFailingTheRequest() throws Exception {
        mvc(SourceOrderStore.PersistResult.notSaved("disk is read-only"))
                .perform(put("/api/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"b\",\"a\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.detail").value("disk is read-only"));
    }

    @Test
    void anOrderThatNamesAnUnknownSourceIsRejected() throws Exception {
        mvc().perform(put("/api/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"a\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void aReloadReportsWhatTheConfigurationGainedAndLost() throws Exception {
        ReloadConfiguration reload = new ReloadConfiguration(settings,
                () -> List.of(EpgSource.of("b", "b", "http://b", Credentials.none()),
                        EpgSource.of("c", "c", "http://c", Credentials.none())),
                () -> {
                });

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(reload, SourceOrderStore.PersistResult.saved())).build();

        mvc.perform(post("/api/config/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added[0]").value("c"))
                .andExpect(jsonPath("$.removed[0]").value("a"))
                .andExpect(jsonPath("$.order[0]").value("b"));
    }

    @Test
    void aReloadThatCannotReadTheConfigurationIsAConflict() throws Exception {
        ReloadConfiguration reload = new ReloadConfiguration(settings, () -> {
            throw new IllegalStateException("the configuration file is not readable");
        }, () -> {
        });

        MockMvcBuilders.standaloneSetup(controller(reload, SourceOrderStore.PersistResult.saved()))
                .build()
                .perform(post("/api/config/reload"))
                .andExpect(status().isConflict());
    }

    @Test
    void dumpingWritesToTheLogStreamAndReturnsNoContent() throws Exception {
        mvc().perform(post("/api/config/dump"))
                .andExpect(status().isNoContent());

        assertThat(dumped).isNotEmpty();
        assertThat(dumped.getFirst()).contains("configuration dump");
    }

    private MockMvc mvc() {
        return mvc(SourceOrderStore.PersistResult.saved());
    }

    private MockMvc mvc(SourceOrderStore.PersistResult persistResult) {
        return MockMvcBuilders.standaloneSetup(controller(
                new ReloadConfiguration(settings, () -> List.of(), () -> {
                }), persistResult)).build();
    }

    private ConfigurationController controller(ReloadConfiguration reload, SourceOrderStore.PersistResult result) {
        ReorderSources reorder = new ReorderSources(settings, order -> result);
        DumpConfiguration dump = new DumpConfiguration(settings, dumped::addAll);
        ReseedRegistry reseed = new ReseedRegistry(settings, guideStore, location -> List.of());
        return new ConfigurationController(settings, reorder, reload, dump, reseed);
    }

    private static GuideSettings settings(String... ids) {
        List<EpgSource> sources = java.util.Arrays.stream(ids)
                .map(id -> EpgSource.of(id, id, "http://" + id, Credentials.none()))
                .toList();
        return new GuideSettings(sources, Optional.empty());
    }
}
