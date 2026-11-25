package com.questioncrawler.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
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

/** The Search-AI screen's API: first-run state, saving the address once, history. No network is used. */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchAiApiTest {

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
    void freshInstallAsksForTheAddressWithoutCallingTheNetwork() throws Exception {
        mvc.perform(get("/api/searchai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.signedIn").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not set")));
        mvc.perform(get("/api/searchai/history")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // the cheap check used to decide whether Search-AI can be asked as a fallback: no network, address not set yet
        mvc.perform(get("/api/searchai/configured")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @Order(2)
    void aBadAddressIsRefusedInPlainWords() throws Exception {
        mvc.perform(put("/api/searchai/settings").contentType(MediaType.APPLICATION_JSON).content("{\"baseUri\":\"http://plain.example\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("https://")));
        mvc.perform(put("/api/searchai/settings").contentType(MediaType.APPLICATION_JSON).content("{\"baseUri\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(3)
    void theAddressIsSavedOnceWithSearchapiAddedAndKeptInTheFile() throws Exception {
        // The host does not exist: the answer is "configured, not signed in" with a hint, never an error response.
        mvc.perform(put("/api/searchai/settings").contentType(MediaType.APPLICATION_JSON).content("{\"baseUri\":\"https://search-ai.invalid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.baseUri").value("https://search-ai.invalid/searchapi"))
                .andExpect(jsonPath("$.signedIn").value(false))
                .andExpect(jsonPath("$.hint").isNotEmpty());
        String file = Files.readString(tmp.resolve("questioncrawler.properties"));
        org.junit.jupiter.api.Assertions.assertTrue(file.contains("searchai.baseUri=https://search-ai.invalid/searchapi"), file);
    }
}
