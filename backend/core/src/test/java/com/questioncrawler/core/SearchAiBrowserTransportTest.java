package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.searchai.CurlRunner;
import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiException;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.source.ConfluenceOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Search-AI calls made from the signed-in browser window instead of curl (the transport is faked). */
class SearchAiBrowserTransportTest {

    private static final String BASE = "https://search-ai.example.test/searchapi";

    @TempDir
    Path tmp;

    private SearchAiOptions options(String transport) {
        return new SearchAiOptions(BASE, tmp.resolve("jar"), "curl", 30, "", 10, tmp.resolve("store"), ConfluenceOptions.Browser.DEFAULT, transport);
    }

    private static final class Recorder implements SearchAiClient.Transport {
        final List<String> calls = new ArrayList<>();
        int status = 200;

        @Override
        public SearchAiClient.Reply send(String method, String url, String body) {
            calls.add(method + " " + url + (body == null ? "" : " body=" + body));
            return new SearchAiClient.Reply(status, "{\"ok\":true}");
        }
    }

    private static CurlRunner refused() {
        return (command, timeout) -> new CurlRunner.Result(0, "no\n__QC_HTTP_STATUS__:401", "");
    }

    @Test
    void autoFallsBackToTheBrowserWhenCurlIsRefusedAndKeepsUsingIt() {
        Recorder browser = new Recorder();
        SearchAiClient client = new SearchAiClient(options("auto"), refused()).withBrowser(browser);
        assertEquals(200, client.whoami().httpStatus());
        assertTrue(client.readsThroughBrowser());
        client.genAiResponse("how to deploy", "tech");
        assertEquals("GET " + BASE + "/whoami", browser.calls.get(0));
        assertTrue(browser.calls.get(1).startsWith("POST " + BASE + "/genairesponse?query=how+to+deploy&use_case=tech"));
        assertTrue(browser.calls.get(1).endsWith("body={}"));
    }

    @Test
    void browserModeNeverStartsCurl() {
        Recorder browser = new Recorder();
        CurlRunner mustNotRun = (command, timeout) -> {
            throw new AssertionError("curl must not run");
        };
        SearchAiClient client = new SearchAiClient(options("browser"), mustNotRun).withBrowser(browser);
        assertEquals(200, client.search("rest endpoints", 5, null, null, null).httpStatus());
        assertEquals(1, browser.calls.size());
        assertTrue(browser.calls.get(0).startsWith("POST " + BASE + "/search?query=rest+endpoints&rows=5"));
    }

    @Test
    void curlModeDoesNotFallBack() {
        Recorder browser = new Recorder();
        SearchAiClient client = new SearchAiClient(options("curl"), refused()).withBrowser(browser);
        assertEquals(401, assertThrows(SearchAiException.class, client::whoami).httpStatus());
        assertTrue(browser.calls.isEmpty());
        assertFalse(client.readsThroughBrowser());
    }

    @Test
    void aBrowserFailureIsReportedAndCurlIsTriedFirstNextTime() {
        SearchAiClient.Transport signedOut = (method, url, body) -> {
            throw new SearchAiException("Sign in in the browser window and try again.", 401);
        };
        SearchAiClient client = new SearchAiClient(options("auto"), refused()).withBrowser(signedOut);
        assertEquals(401, assertThrows(SearchAiException.class, client::whoami).httpStatus());
        assertFalse(client.readsThroughBrowser());
    }

    @Test
    void anErrorStatusFromTheBrowserBecomesAnException() {
        Recorder browser = new Recorder();
        browser.status = 422;
        SearchAiClient client = new SearchAiClient(options("browser"), refused()).withBrowser(browser);
        assertEquals(422, assertThrows(SearchAiException.class, () -> client.genAiResponse("q", null)).httpStatus());
    }
}
