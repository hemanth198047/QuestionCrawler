package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Anything can be searched: underscores, symbols, long identifiers, pasted logs; nothing hangs or fails. */
class SpecialCharacterSearchTest {

    @TempDir
    static Path tmp;
    static HttpServer server;
    static KnowledgeBase kb;

    @BeforeAll
    static void collect() throws Exception {
        StringBuilder longToken = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longToken.append("seg").append(i).append('_');
        }
        String page = "<html><head><title>Symbols reference</title></head><body><main><h1>Symbols reference</h1>"
                + "<p>Set the variable my_var_name before calling __init__ in the edap_fdc_automation_job_12 module.</p>"
                + "<p>Use C++ or c# for the adapters; the cache hit rate should stay above 50% at all times.</p>"
                + "<p>The path separator is $PATH and the arrow -> points to the next step; wildcard .* matches all.</p>"
                + "<p>A line of underscores follows. " + "_".repeat(300) + "</p>"
                + "<p>" + longToken + "</p>"
                + "<pre><code>grep '^err_1$' /var/log/app_1.log | sort -u</code></pre></main></body></html>";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = page.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("d").toString(), "source.type", "links",
                "source.links.list", "http://127.0.0.1:" + server.getAddress().getPort() + "/symbols", "source.links.followLinks", "false",
                "source.auth.method", "skip"));
        kb = new KnowledgeBase(cfg);
        kb.sync(new SyncOptions(List.of("all"), null, false, SyncMode.FULL, null, 1, 30));
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static SearchResponse search(String q) {
        long t = System.nanoTime();
        SearchResponse r = kb.search(new SearchRequest(q, List.of(), List.of(), 5, 20000, 1200));
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 5000, "searching " + q + " took " + ms + " ms");
        return r;
    }

    @Test
    void underscoresAndIdentifiersAreFound() {
        for (String q : List.of("my_var_name", "__init__", "edap_fdc_automation_job_12", "seg250", "seg10_seg11", "app_1.log")) {
            assertTrue(search(q).resultCount() > 0, q + " was not found");
        }
    }

    @Test
    void symbolsAreFoundExactlyAsTyped() {
        for (String q : List.of("C++", "c#", "50%", "$PATH", "->", ".*", "_", "___", "'^err_1$'", "| sort -u")) {
            SearchResponse r = search(q);
            assertTrue(r.resultCount() > 0, q + " was not found");
        }
    }

    @Test
    void nothingBreaksOnOddOrHugeQuestions() {
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            trace.append("at com.corp.edap.fdc_automation.job_").append(i).append(".run_step(JobRunner_").append(i).append(".java:").append(i).append(") ");
        }
        for (String q : List.of(trace.toString(), "~!@#$%^&*()_+{}|:\"<>?", "(a|b)+$", "\\", "\"unclosed", "[", "%E0%A4", "a_".repeat(300),
                "<script>alert(1)</script>", "日本語", "🚀", "x".repeat(5000))) {
            search(q); // answers (possibly with nothing), never throws or hangs
        }
    }
}
