package com.questioncrawler.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the REST API end to end against the bundled synthetic fixtures (no network, no credentials). */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("questioncrawler.config-file", () -> com.questioncrawler.ApiTestSupportBridge.config(tmp));
    }

    @Autowired
    private MockMvc mvc;

    @Test
    @Order(1)
    void searchBeforeAnySyncExplainsWhatToDo() throws Exception {
        mvc.perform(get("/api/search").param("q", "timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Search index not found")));
    }

    @Test
    @Order(2)
    void syncCollectsTheFixtureSite() throws Exception {
        mvc.perform(post("/api/sync").param("wait", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETE"))
                .andExpect(jsonPath("$.added").value(10))
                .andExpect(jsonPath("$.completeness").value(org.hamcrest.Matchers.startsWith("INCREMENTAL")));
    }

    @Test
    @Order(3)
    void searchReturnsEvidenceWithSourceUrlsAndUntrustedMarker() throws Exception {
        mvc.perform(get("/api/search").param("q", "ERR_CONN_TIMEOUT connection timed out after 30 seconds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence[0].questionId").value("1001"))
                .andExpect(jsonPath("$.evidence[0].sourceUrl").value("https://qa.example.internal/questions/1001"))
                .andExpect(jsonPath("$.evidence[0].untrusted").value(true))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("untrusted")));
    }

    @Test
    @Order(3)
    void ageFiltersApplyToQuestionsAndAnswers() throws Exception {
        // The synthetic questions are from 2025, so "within 30 days" matches nothing and "older than 30 days" matches.
        mvc.perform(get("/api/search").param("q", "timeout").param("maxQuestionAge", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0))
                .andExpect(jsonPath("$.ageFilter.maxQuestionAge").value("30d"));
        mvc.perform(get("/api/search").param("q", "timeout").param("minQuestionAge", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.evidence[0].questionCreatedAt").exists());
        mvc.perform(get("/api/search").param("q", "timeout").param("maxAnswerAge", "nonsense"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/categories/installation-setup").param("maxQuestionAge", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(0));
    }

    @Test
    @Order(4)
    void noAnswerQueryReturnsNoEvidence() throws Exception {
        mvc.perform(get("/api/search").param("q", "kubernetes pod eviction policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0))
                .andExpect(jsonPath("$.confidence").value("NONE"));
    }

    @Test
    @Order(5)
    void blankQueriesAreRejected() throws Exception {
        mvc.perform(get("/api/search").param("q", "   ")).andExpect(status().isBadRequest());
    }

    @Test
    @Order(6)
    void showAndBrowseEndpointsWork() throws Exception {
        mvc.perform(get("/api/questions/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.question.answers.length()").value(3))
                .andExpect(jsonPath("$.untrusted").value(true));
        mvc.perform(get("/api/questions/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/categories/installation-setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[?(@.id=='1002')]").exists());
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threadCount").value(10));
    }

    @Test
    @Order(7)
    void manualOverrideIsReturnedAndCanBeCleared() throws Exception {
        mvc.perform(put("/api/questions/1001/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[\"performance\"],\"note\":\"tuning\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0]").value("performance"));
        mvc.perform(put("/api/questions/1001/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[\"nope\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/questions/1001/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override").doesNotExist());
    }

    @Test
    @Order(8)
    void syncStatusAndReportsAreAvailable() throws Exception {
        mvc.perform(get("/api/sync/status")).andExpect(status().isOk()).andExpect(jsonPath("$.running").value(false));
        mvc.perform(get("/api/sync/reports")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/reindex")).andExpect(status().isOk()).andExpect(jsonPath("$.threads").value(10));
    }
}
