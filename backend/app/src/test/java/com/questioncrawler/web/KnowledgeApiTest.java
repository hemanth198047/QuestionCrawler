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

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Test
    @Order(1)
    void freshInstallationGuidesToTheNextStepAndSearchExplainsWhatToDo() throws Exception {
        mvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.nextStep").value("collect"))
                .andExpect(jsonPath("$.totals.questions").value(0));
        mvc.perform(get("/api/search").param("q", "timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    @Order(2)
    void syncCollectsTheFixtureSiteAndReportsProgressShape() throws Exception {
        mvc.perform(post("/api/sync").param("wait", "true").contentType(JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETE"))
                .andExpect(jsonPath("$.added").value(10))
                .andExpect(jsonPath("$.completeness").value(org.hamcrest.Matchers.startsWith("INCREMENTAL")));
        mvc.perform(get("/api/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.progress.phase").value("DONE"))
                .andExpect(jsonPath("$.lastReport.added").value(10));
    }

    @Test
    @Order(3)
    void overviewNowShowsTheCollectedKnowledge() throws Exception {
        mvc.perform(get("/api/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.questions").value(10))
                .andExpect(jsonPath("$.totals.answers").value(15))
                .andExpect(jsonPath("$.nextStep").value("explore"))
                .andExpect(jsonPath("$.recentRuns.length()").value(1));
    }

    @Test
    @Order(4)
    void searchReturnsEvidenceWithSourceUrlsAgesAndUntrustedMarker() throws Exception {
        mvc.perform(get("/api/search").param("q", "ERR_CONN_TIMEOUT connection timed out after 30 seconds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence[0].questionId").value("1001"))
                .andExpect(jsonPath("$.evidence[0].sourceUrl").value("https://qa.example.internal/questions/1001"))
                .andExpect(jsonPath("$.evidence[0].createdAt").exists())
                .andExpect(jsonPath("$.evidence[0].author").exists())
                .andExpect(jsonPath("$.evidence[0].untrusted").value(true));
        mvc.perform(get("/api/search").param("q", "raise the client timeout orbit.yaml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence[?(@.answerId=='2001')].author").value(org.hamcrest.Matchers.hasItem("dana")));
        mvc.perform(get("/api/search").param("q", "kubernetes pod eviction policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0))
                .andExpect(jsonPath("$.confidence").value("NONE"));
        mvc.perform(get("/api/search").param("q", "   ")).andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    void ageFiltersApplyToQuestionsAndAnswers() throws Exception {
        mvc.perform(get("/api/search").param("q", "timeout").param("maxQuestionAge", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(0))
                .andExpect(jsonPath("$.ageFilter.maxQuestionAge").value("30d"));
        mvc.perform(get("/api/search").param("q", "timeout").param("minQuestionAge", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCount").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(get("/api/search").param("q", "timeout").param("maxAnswerAge", "nonsense")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/categories/installation-setup").param("maxQuestionAge", "30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(0));
    }

    @Test
    @Order(6)
    void showAndBrowseEndpointsWork() throws Exception {
        mvc.perform(get("/api/questions/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.question.answers.length()").value(3))
                .andExpect(jsonPath("$.thread.question.answers[0].author").value("dana"))
                .andExpect(jsonPath("$.thread.question.answers[1].author").value("li"))
                .andExpect(jsonPath("$.thread.question.answers[2].author").value("omar"))
                .andExpect(jsonPath("$.untrusted").value(true));
        mvc.perform(get("/api/questions/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(12));
        mvc.perform(get("/api/categories/installation-setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[?(@.id=='1002')]").exists());
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.threadCount").value(10));
    }

    @Test
    @Order(7)
    void manualTopicOverrideIsReturnedAndCanBeCleared() throws Exception {
        mvc.perform(put("/api/questions/1001/categories").contentType(JSON).content("{\"categories\":[\"performance\"],\"note\":\"tuning\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0]").value("performance"));
        mvc.perform(put("/api/questions/1001/categories").contentType(JSON).content("{\"categories\":[\"nope\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/questions/1001/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override").doesNotExist());
    }

    @Test
    @Order(8)
    void settingsAreExplainedValidatedAndSavedFromTheUi() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(4))
                .andExpect(jsonPath("$.sections[0].fields[0].help").exists())
                .andExpect(jsonPath("$.values['sync.tags']").value("orbit"));
        mvc.perform(post("/api/config/validate").contentType(JSON).content("{\"values\":{\"sync.maxQuestionAge\":\"recently\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].level").value("error"))
                .andExpect(jsonPath("$.issues[0].fix").exists())
                .andExpect(jsonPath("$.preview").value(org.hamcrest.Matchers.containsString("sync.maxQuestionAge=recently")));
        mvc.perform(put("/api/config").contentType(JSON).content("{\"values\":{\"sync.maxQuestionAge\":\"recently\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(false));
        mvc.perform(put("/api/config").contentType(JSON).content("{\"values\":{\"sync.maxQuestionAge\":\"1y\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.view.values['sync.maxQuestionAge']").value("1y"));
        mvc.perform(get("/api/config/presets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @Order(9)
    void connectionCheckExplainsTheRouteToTheData() throws Exception {
        mvc.perform(post("/api/connection/check").contentType(JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("READY"))
                .andExpect(jsonPath("$.route.kind").value("OFFLINE_DEMO"))
                .andExpect(jsonPath("$.stages.length()").value(6));
        mvc.perform(post("/api/connection/check").contentType(JSON)
                        .content("{\"values\":{\"source.type\":\"live\",\"source.baseUrl\":\"https://this-host-does-not-exist.invalid/api\",\"source.timeoutSeconds\":\"3\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BLOCKED"))
                .andExpect(jsonPath("$.stages[0].needs[0].who").exists());
        mvc.perform(get("/api/connection/last")).andExpect(status().isOk());
    }

    @Test
    @Order(10)
    void categoryRulesCanBeTriedOutBeforeSaving() throws Exception {
        mvc.perform(get("/api/taxonomy")).andExpect(status().isOk()).andExpect(jsonPath("$.taxonomy.categories.length()").value(12));
        mvc.perform(post("/api/taxonomy/try").contentType(JSON)
                        .content("{\"title\":\"Helm chart fails to deploy\",\"body\":\"kubernetes rollout failed\",\"tags\":[\"helm\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[?(@.categoryId=='deployment')]").exists())
                .andExpect(jsonPath("$.fallback").value(false));
    }

    @Test
    @Order(11)
    void reportsAndReindexAreAvailable() throws Exception {
        mvc.perform(get("/api/sync/reports")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/reindex")).andExpect(status().isOk()).andExpect(jsonPath("$.threads").value(10));
    }

    @Test
    @Order(12)
    void topicsCanBeDiscoveredFromTheCollectedQuestions() throws Exception {
        mvc.perform(post("/api/topics/discover").contentType(JSON).content("{\"k\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(10))
                .andExpect(jsonPath("$.topics.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.taxonomy.fallbackCategory").value("uncategorized"));
    }

    @Test
    @Order(13)
    void readingTheApiDocumentationNeedsAnAddressAndNeverFailsHard() throws Exception {
        mvc.perform(post("/api/api-docs/discover").contentType(JSON).content("{\"values\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problem").value(org.hamcrest.Matchers.containsString("Swagger")));
    }

    @Test
    @Order(14)
    void aSavedCopyOfTheDocumentationIsCheckedBeforeItIsKept() throws Exception {
        mvc.perform(post("/api/api-docs/file").contentType(JSON).content("{\"content\":\"<html>Please sign in</html>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problem").value(org.hamcrest.Matchers.containsString("Swagger/OpenAPI")));
    }

    @Test
    @Order(15)
    void theRawApiAnswerNeedsAnAddressAndNeverFailsHard() throws Exception {
        mvc.perform(post("/api/api-docs/probe").contentType(JSON).content("{\"values\":{},\"path\":\"/info\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reached").value(false))
                .andExpect(jsonPath("$.problem").value(org.hamcrest.Matchers.containsString("API address")));
        mvc.perform(post("/api/api-docs/probe").contentType(JSON).content("{\"values\":{\"source.baseUrl\":\"https://x.example.com/api\"},\"path\":\"http://evil/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reached").value(false));
    }

    @Test
    @Order(16)
    void deleteByTagRequiresConfirmAndRemovesOnlyMatchingQuestions() throws Exception {
        mvc.perform(post("/api/store/delete-by-tag").contentType(JSON).content("{\"tag\":\"timeout\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/store/delete-by-tag").contentType(JSON).content("{\"tag\":\"all\",\"confirm\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/store/delete-by-tag").contentType(JSON).content("{\"tag\":\"timeout\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("timeout"))
                .andExpect(jsonPath("$.deletedThreads").value(1))
                .andExpect(jsonPath("$.deletedQuestionIds[0]").value("1001"));
        mvc.perform(get("/api/questions/1001")).andExpect(status().isNotFound());
        mvc.perform(get("/api/questions/1002")).andExpect(status().isOk());
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.threadCount").value(9));
    }
}
