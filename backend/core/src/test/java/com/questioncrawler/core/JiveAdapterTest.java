package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.JiveAdapter;
import com.questioncrawler.core.source.JiveOptions;
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

/** Jive adapter against a local mock of Core REST API v3. No live Jive calls. */
class JiveAdapterTest {

    record Req(String method, String path, Map<String, String> params, List<String> filters, String auth) {}

    @TempDir
    Path tmp;

    HttpServer server;
    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Function<Req, Object> handler = r -> Map.of("list", List.of(), "itemsPerPage", 25, "startIndex", 0);
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    String origin;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> params = new LinkedHashMap<>();
            List<String> filters = new ArrayList<>();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                        String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                        if ("filter".equals(k)) {
                            filters.add(v);
                        } else {
                            params.put(k, v);
                        }
                    }
                }
            }
            Req req = new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), params, filters,
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

    private JiveAdapter adapter(JiveOptions options, Map<String, String> env) {
        var settings = new SourceSettings("jive", origin, List.of("127.0.0.1"),
                List.of("/api/core/v3/"), "basic-env", "JIVE_PASSWORD",
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null,
                com.questioncrawler.core.source.ProxyConfig.NONE,
                new com.questioncrawler.core.source.ApiOptions("key", "query", null, "withbody", false, null, null, "off"),
                "svc-bot", null);
        return new JiveAdapter(settings, options, new HttpSourceClient(settings, env::get), env::get);
    }

    private JiveOptions placeScoped() {
        return new JiveOptions("/api/core/v3", List.of("1006"), List.of("document", "discussion", "question"),
                false, 25, true, "svc-bot", "");
    }

    @Test
    void pingUsesPlacesListingAndBasicAuth() throws Exception {
        handler = r -> Map.of("list", List.of(Map.of("placeID", "1006", "name", "Engineering")));
        var adapter = adapter(placeScoped(), Map.of("JIVE_PASSWORD", "secret-pass"));
        String ping = adapter.ping();
        assertTrue(ping.toLowerCase().contains("jive"));
        assertTrue(requests.get(0).path().endsWith("/api/core/v3/places"));
        assertTrue(lastAuth.get().startsWith("Basic "));
        String decoded = new String(java.util.Base64.getDecoder().decode(lastAuth.get().substring(6)), StandardCharsets.UTF_8);
        assertEquals("svc-bot:secret-pass", decoded);
        assertFalse(ping.contains("secret-pass"));
    }

    @Test
    void listQuestionsBuildsFiltersWithPlaceTagAndAfter() throws Exception {
        handler = r -> {
            assertTrue(r.path().endsWith("/api/core/v3/contents"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contentID", "2001");
            item.put("subject", "Runbook");
            item.put("updated", "2026-10-02T12:00:00.000+0000");
            item.put("resources", Map.of("html", Map.of("ref", origin + "/docs/2001")));
            return Map.of("list", List.of(item), "itemsPerPage", 25, "startIndex", 0);
        };
        var adapter = adapter(placeScoped(), Map.of("JIVE_PASSWORD", "x"));
        Page<QuestionSummary> page = adapter.listQuestions(
                new TagFilter(List.of("runbook"), TagMode.ANY, false),
                "2026-10-01T00:00:00Z", null);
        assertEquals(1, page.items().size());
        assertEquals("2001", page.items().get(0).id());
        List<String> filters = requests.get(0).filters();
        assertTrue(filters.stream().anyMatch(f -> f.startsWith("type(")));
        assertTrue(filters.stream().anyMatch(f -> f.contains("/places/1006")));
        assertTrue(filters.stream().anyMatch(f -> f.equals("tag(runbook)")));
        assertTrue(filters.stream().anyMatch(f -> f.startsWith("after(")));
        assertNull(page.nextCursor());
    }

    @Test
    void fetchQuestionMapsHtmlBodyTagsAndPlaceTag() throws Exception {
        handler = r -> {
            assertTrue(r.path().contains("/api/core/v3/contents/2001"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contentID", "2001");
            body.put("subject", "Runbook");
            body.put("type", "document");
            body.put("content", Map.of("type", "text/html", "text", "<p>Hello <b>jive</b></p>"));
            body.put("published", "2026-09-01T08:00:00.000+0000");
            body.put("updated", "2026-10-02T12:00:00.000+0000");
            body.put("tags", List.of("runbook"));
            body.put("author", Map.of("displayName", "Ada"));
            body.put("parent", origin + "/api/core/v3/places/1006");
            body.put("resources", Map.of("html", Map.of("ref", origin + "/docs/2001")));
            return body;
        };
        var adapter = adapter(placeScoped(), Map.of("JIVE_PASSWORD", "x"));
        Question q = adapter.fetchQuestion("2001");
        assertEquals("2001", q.id());
        assertEquals("Runbook", q.title());
        assertEquals(BodyFormat.HTML, q.bodyFormat());
        assertTrue(q.body().contains("Hello"));
        assertEquals("Ada", q.author());
        assertTrue(q.tags().contains("runbook"));
        assertTrue(q.tags().contains("place:1006"));
        assertTrue(q.tags().contains("type:document"));
    }

    @Test
    void listAnswersMapsDiscussionReplies() throws Exception {
        handler = r -> {
            assertTrue(r.path().contains("/api/core/v3/messages/2001/messages"));
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("id", "9");
            reply.put("content", Map.of("type", "text/html", "text", "<p>Try restarting</p>"));
            reply.put("author", Map.of("displayName", "Bob"));
            reply.put("answer", true);
            reply.put("likeCount", 3);
            reply.put("published", "2026-10-03T09:00:00.000+0000");
            return Map.of("list", List.of(reply), "itemsPerPage", 25, "startIndex", 0);
        };
        var adapter = adapter(placeScoped(), Map.of("JIVE_PASSWORD", "x"));
        Page<Answer> page = adapter.listAnswers("2001", null);
        assertEquals(1, page.items().size());
        Answer a = page.items().get(0);
        assertEquals("9", a.id());
        assertTrue(a.accepted());
        assertEquals("Bob", a.author());
        assertTrue(a.body().contains("restarting"));
    }

    @Test
    void coreConfigCreatesJiveAdapterAndValidatesScope() throws Exception {
        Path file = tmp.resolve("qc.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=jive
                source.baseUrl=https://community.example.com
                source.allowedHosts=community.example.com
                source.allowedPathPrefixes=/api/core/v3/
                source.jive.username=svc-bot
                source.jive.places=1006
                source.auth.method=basic-env
                source.auth.tokenEnv=JIVE_PASSWORD
                sync.tags=runbook
                sync.maxQuestionAge=30d
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertEquals("jive", cfg.sourceType());
        assertEquals(List.of(), cfg.validate());
        assertTrue(cfg.createAdapter(k -> "x") instanceof JiveAdapter);
        assertTrue(cfg.syncOptions(null, null, null, com.questioncrawler.core.sync.SyncMode.INCREMENTAL)
                .scopeExtra().contains("places=1006"));
    }

    @Test
    void unboundedAllWithoutPlaceIsRejected() throws Exception {
        Path file = tmp.resolve("bad.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=jive
                source.baseUrl=https://community.example.com
                source.allowedHosts=community.example.com
                source.jive.username=svc-bot
                source.auth.method=basic-env
                source.auth.tokenEnv=JIVE_PASSWORD
                sync.tags=all
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertTrue(cfg.validate().stream().anyMatch(p -> p.toLowerCase().contains("all")));
    }
}
