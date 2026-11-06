package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.SharePointAdapter;
import com.questioncrawler.core.source.SharePointOptions;
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

/** SharePoint adapter against a local mock of Microsoft Graph. No live Graph/SharePoint calls. */
class SharePointAdapterTest {

    record Req(String method, String path, Map<String, String> params, String auth) {}

    @TempDir
    Path tmp;

    HttpServer server;
    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Function<Req, Object> handler = r -> Map.of("value", List.of());
    final AtomicReference<String> lastAuth = new AtomicReference<>();
    String origin;
    String graphBase;

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
                        String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                        String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                        params.put(k, v);
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
        graphBase = origin + "/v1.0";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SharePointAdapter adapter(SharePointOptions options, Map<String, String> env) {
        var settings = new SourceSettings("sharepoint",
                "https://contoso.sharepoint.com/sites/Engineering",
                List.of("127.0.0.1", "contoso.sharepoint.com"),
                List.of("/v1.0/", "/"), "token", "SHAREPOINT_ACCESS_TOKEN",
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null,
                com.questioncrawler.core.source.ProxyConfig.NONE,
                new com.questioncrawler.core.source.ApiOptions("key", "query", null, "withbody", false, null, null, "off"),
                null, null);
        SharePointOptions withGraph = new SharePointOptions(
                options.tenantId(), options.clientId(), options.clientSecretEnv(), options.siteId(),
                options.listIds(), options.contentMode(), graphBase,
                options.collectComments(), options.pageSize(), options.includeSiteTag());
        return new SharePointAdapter(settings, withGraph, new HttpSourceClient(settings, env::get), env::get);
    }

    private SharePointOptions siteScoped() {
        return new SharePointOptions("", "", "", "site-guid", List.of(), "pages",
                graphBase, false, 25, true);
    }

    @Test
    void pingUsesSiteLookupAndBearerAuth() throws Exception {
        handler = r -> {
            assertTrue(r.path().contains("/sites/site-guid"));
            return Map.of("id", "site-guid", "displayName", "Engineering", "webUrl",
                    "https://contoso.sharepoint.com/sites/Engineering");
        };
        var adapter = adapter(siteScoped(), Map.of("SHAREPOINT_ACCESS_TOKEN", "tok-abc"));
        String ping = adapter.ping();
        assertTrue(ping.toLowerCase().contains("sharepoint"));
        assertTrue(ping.contains("Engineering"));
        assertEquals("Bearer tok-abc", lastAuth.get());
        assertFalse(ping.contains("tok-abc"));
    }

    @Test
    void listQuestionsFiltersByModifiedAndTitleTag() throws Exception {
        handler = r -> {
            assertTrue(r.path().contains("/pages/microsoft.graph.sitePage"));
            assertTrue(r.params().getOrDefault("$filter", "").contains("lastModifiedDateTime ge"));
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", "page-1");
            page.put("title", "Incident runbook");
            page.put("name", "runbook.aspx");
            page.put("description", "How to respond");
            page.put("lastModifiedDateTime", "2026-10-02T12:00:00Z");
            page.put("webUrl", "https://contoso.sharepoint.com/sites/Engineering/SitePages/runbook.aspx");
            return Map.of("value", List.of(page));
        };
        var adapter = adapter(siteScoped(), Map.of("SHAREPOINT_ACCESS_TOKEN", "x"));
        Page<QuestionSummary> page = adapter.listQuestions(
                new TagFilter(List.of("runbook"), TagMode.ANY, false),
                "2026-10-01T00:00:00Z", null);
        assertEquals(1, page.items().size());
        assertEquals("page-1", page.items().get(0).id());
        assertNull(page.nextCursor());
    }

    @Test
    void fetchQuestionMapsCanvasHtmlAndSiteTag() throws Exception {
        handler = r -> {
            assertTrue(r.path().contains("/pages/page-1/microsoft.graph.sitePage"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", "page-1");
            body.put("title", "Runbook");
            body.put("webUrl", "https://contoso.sharepoint.com/sites/Engineering/SitePages/runbook.aspx");
            body.put("createdDateTime", "2026-09-01T08:00:00Z");
            body.put("lastModifiedDateTime", "2026-10-02T12:00:00Z");
            body.put("createdBy", Map.of("user", Map.of("displayName", "Ada")));
            body.put("canvasLayout", Map.of("horizontalSections", List.of(
                    Map.of("columns", List.of(Map.of("webparts", List.of(
                            Map.of("innerHtml", "<p>Hello <b>sharepoint</b></p>"))))))));
            return body;
        };
        var adapter = adapter(siteScoped(), Map.of("SHAREPOINT_ACCESS_TOKEN", "x"));
        Question q = adapter.fetchQuestion("page-1");
        assertEquals("page-1", q.id());
        assertEquals("Runbook", q.title());
        assertEquals(BodyFormat.HTML, q.bodyFormat());
        assertTrue(q.body().contains("Hello"));
        assertEquals("Ada", q.author());
        assertTrue(q.tags().stream().anyMatch(t -> t.startsWith("site:")));
    }

    @Test
    void listAnswersAlwaysEmpty() throws Exception {
        var adapter = adapter(siteScoped(), Map.of("SHAREPOINT_ACCESS_TOKEN", "x"));
        assertTrue(adapter.listAnswers("page-1", null).items().isEmpty());
    }

    @Test
    void coreConfigCreatesSharePointAdapterAndValidatesScope() throws Exception {
        Path file = tmp.resolve("qc.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=sharepoint
                source.baseUrl=https://contoso.sharepoint.com/sites/Engineering
                source.allowedHosts=contoso.sharepoint.com,graph.microsoft.com
                source.allowedPathPrefixes=/v1.0/
                source.sharepoint.contentMode=pages
                source.auth.method=token
                source.auth.tokenEnv=SHAREPOINT_ACCESS_TOKEN
                sync.tags=all
                sync.maxQuestionAge=30d
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertEquals("sharepoint", cfg.sourceType());
        assertEquals(List.of(), cfg.validate());
        assertTrue(cfg.createAdapter(k -> "x") instanceof SharePointAdapter);
        assertTrue(cfg.syncOptions(null, null, null, com.questioncrawler.core.sync.SyncMode.INCREMENTAL)
                .scopeExtra().contains("mode=pages"));
    }

    @Test
    void unboundedAllWithoutSiteIsRejected() throws Exception {
        Path file = tmp.resolve("bad.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=sharepoint
                source.allowedHosts=graph.microsoft.com
                source.auth.method=token
                source.auth.tokenEnv=SHAREPOINT_ACCESS_TOKEN
                sync.tags=all
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertTrue(cfg.validate().stream().anyMatch(p -> p.toLowerCase().contains("all") || p.toLowerCase().contains("site")));
    }
}
