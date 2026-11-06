package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.ConfluenceOptions;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Confluence adapter against a local mock of the classic Content REST API. No live Atlassian calls. */
class ConfluenceAdapterTest {

    record Req(String method, String path, Map<String, String> params, String auth) {}

    @TempDir
    Path tmp;

    HttpServer server;
    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Function<Req, Object> handler = r -> Map.of("results", List.of(), "size", 0, "limit", 25, "start", 0);
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    String origin;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> params = new LinkedHashMap<>();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                    }
                }
            }
            Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), params,
                    ex.getRequestHeaders().getFirst("Authorization"));
            requests.add(req);
            lastAuth.set(req.auth());
            Object body = handler.apply(req);
            byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ConfluenceAdapter adapter(ConfluenceOptions options, Map<String, String> env) {
        var settings = new SourceSettings("confluence", origin, List.of("127.0.0.1"),
                List.of("/wiki/rest/api/", "/rest/api/"), "basic-env", "CONFLUENCE_API_TOKEN",
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null,
                com.questioncrawler.core.source.ProxyConfig.NONE,
                new com.questioncrawler.core.source.ApiOptions("key", "query", null, "withbody", false, null, null, "off"),
                "user@example.com", null);
        return new ConfluenceAdapter(settings, options, new HttpSourceClient(settings, env::get), env::get);
    }

    private ConfluenceOptions cloudEng() {
        return new ConfluenceOptions("cloud", "/wiki/rest/api", List.of("ENG"), "", List.of("page"),
                "body.storage", false, 25, true, "user@example.com", "");
    }

    @Test
    void pingUsesSpaceListingAndBasicAuth() throws Exception {
        handler = r -> Map.of("results", List.of(Map.of("key", "ENG")), "size", 1);
        var adapter = adapter(cloudEng(), Map.of("CONFLUENCE_API_TOKEN", "tok-secret"));
        String ping = adapter.ping();
        assertTrue(ping.toLowerCase().contains("confluence"));
        assertTrue(requests.get(0).path().endsWith("/wiki/rest/api/space"));
        assertTrue(lastAuth.get().startsWith("Basic "));
        String decoded = new String(java.util.Base64.getDecoder().decode(lastAuth.get().substring(6)), StandardCharsets.UTF_8);
        assertEquals("user@example.com:tok-secret", decoded);
        assertFalse(ping.contains("tok-secret"));
    }

    @Test
    void listQuestionsBuildsCqlWithSpaceLabelAndLastmodified() throws Exception {
        handler = r -> {
            assertTrue(r.path().endsWith("/wiki/rest/api/content/search"));
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", "1001");
            page.put("title", "Runbook");
            page.put("version", Map.of("when", "2026-10-02T12:00:00.000Z"));
            page.put("_links", Map.of("webui", "/spaces/ENG/pages/1001/Runbook", "base", origin + "/wiki"));
            return Map.of("results", List.of(page), "size", 1, "limit", 25, "start", 0);
        };
        var adapter = adapter(cloudEng(), Map.of("CONFLUENCE_API_TOKEN", "tok"));
        Page<QuestionSummary> page = adapter.listQuestions(
                new TagFilter(List.of("runbook"), TagMode.ANY, false),
                "2026-10-01T00:00:00Z", null);
        assertEquals(1, page.items().size());
        assertEquals("1001", page.items().get(0).id());
        String cql = requests.get(0).params().get("cql");
        assertTrue(cql.contains("type = \"page\""));
        assertTrue(cql.contains("space = \"ENG\""));
        assertTrue(cql.contains("label = \"runbook\""));
        assertTrue(cql.contains("lastmodified >="));
        assertNull(page.nextCursor());
    }

    @Test
    void fetchQuestionMapsStorageBodyLabelsAndSpaceTag() throws Exception {
        handler = r -> {
            assertTrue(r.path().contains("/wiki/rest/api/content/1001"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "1001");
            body.put("title", "Runbook");
            body.put("body", Map.of("storage", Map.of("value", "<p>Hello <b>wiki</b></p>")));
            body.put("version", Map.of("when", "2026-10-02T12:00:00.000Z", "number", 3));
            body.put("space", Map.of("key", "ENG"));
            body.put("history", Map.of(
                    "createdDate", "2026-09-01T08:00:00.000Z",
                    "createdBy", Map.of("displayName", "Ada")));
            body.put("metadata", Map.of("labels", Map.of("results", List.of(Map.of("name", "runbook")))));
            body.put("_links", Map.of("webui", "/spaces/ENG/pages/1001", "base", origin + "/wiki"));
            return body;
        };
        var adapter = adapter(cloudEng(), Map.of("CONFLUENCE_API_TOKEN", "tok"));
        Question q = adapter.fetchQuestion("1001");
        assertEquals("1001", q.id());
        assertEquals("Runbook", q.title());
        assertEquals(BodyFormat.HTML, q.bodyFormat());
        assertTrue(q.body().contains("Hello"));
        assertEquals("Ada", q.author());
        assertTrue(q.tags().contains("runbook"));
        assertTrue(q.tags().contains("space:ENG"));
        assertTrue(q.answers().isEmpty());
        assertTrue(adapter.listAnswers("1001", null).items().isEmpty());
    }

    @Test
    void coreConfigCreatesConfluenceAdapterAndValidatesScope() throws Exception {
        Path file = tmp.resolve("qc.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=confluence
                source.baseUrl=https://acme.atlassian.net
                source.allowedHosts=acme.atlassian.net
                source.allowedPathPrefixes=/wiki/rest/api/
                source.confluence.edition=cloud
                source.confluence.email=you@example.com
                source.confluence.spaces=ENG
                source.auth.method=basic-env
                source.auth.tokenEnv=CONFLUENCE_API_TOKEN
                sync.tags=runbook
                sync.maxQuestionAge=30d
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertEquals("confluence", cfg.sourceType());
        assertEquals(List.of(), cfg.validate());
        assertTrue(cfg.createAdapter(k -> "x") instanceof ConfluenceAdapter);
        assertTrue(cfg.syncOptions(null, null, null, com.questioncrawler.core.sync.SyncMode.INCREMENTAL)
                .scopeExtra().contains("spaces=eng"));
    }

    @Test
    void unboundedAllWithoutSpaceIsRejected() throws Exception {
        Path file = tmp.resolve("bad.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=confluence
                source.baseUrl=https://acme.atlassian.net
                source.allowedHosts=acme.atlassian.net
                source.confluence.edition=cloud
                source.confluence.email=you@example.com
                source.auth.method=basic-env
                source.auth.tokenEnv=CONFLUENCE_API_TOKEN
                sync.tags=all
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertTrue(cfg.validate().stream().anyMatch(p -> p.toLowerCase().contains("all")));
    }
}
