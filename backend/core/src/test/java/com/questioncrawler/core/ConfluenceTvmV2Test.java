package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.ConfluenceOptions;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.source.TvmTokenProvider;
import com.questioncrawler.core.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Confluence Cloud REST API v2 with a Token Vending Machine Bearer token, against a local mock. No live calls. */
class ConfluenceTvmV2Test {

    record Req(String path, Map<String, String> params, String auth) {}

    record Reply(int status, Object body) {}

    @TempDir
    Path tmp;

    HttpServer server;
    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Function<Req, Reply> handler = r -> new Reply(200, Map.of("results", List.of()));
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
            Req req = new Req(ex.getRequestURI().getPath(), params, ex.getRequestHeaders().getFirst("Authorization"));
            requests.add(req);
            Reply reply = handler.apply(req);
            byte[] bytes = Json.MAPPER.writeValueAsBytes(reply.body());
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(reply.status(), bytes.length);
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

    // ------------------------------------------------------------------ helpers

    private static ConfluenceOptions v2(List<String> spaces, List<String> pageIds, String ancestor) {
        return new ConfluenceOptions("cloud", "", spaces, ancestor, List.of("page"), "body.storage", false, 25, true, "", "",
                "v2", pageIds, new ConfluenceOptions.Tvm("https://tvm.example.com/exchange/token/confluence/acme.atlassian.net",
                "curl", "curl", false, "", 60));
    }

    private SourceSettings settings() {
        return new SourceSettings("confluence", origin, List.of("127.0.0.1"), List.of("/wiki/api/v2/"), "tvm", null,
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                new ApiOptions(null, null, null, null, false, null, null, "off"), null, null);
    }

    /** A TVM that hands out tok-1, tok-2, ... each valid for 900 s. */
    private static TvmTokenProvider fakeTvm(AtomicInteger counter, Clock clock) {
        return new TvmTokenProvider(v2(List.of(), List.of(), "").tvm(),
                () -> "{\"access_token\":\"tok-" + counter.incrementAndGet() + "\",\"token_type\":\"Bearer\",\"expires_in\":900}", clock);
    }

    private ConfluenceAdapter adapter(ConfluenceOptions options, TvmTokenProvider tvm) {
        SourceSettings s = settings();
        return new ConfluenceAdapter(s, options, new HttpSourceClient(s, k -> null, tvm::token), k -> null, tvm);
    }

    private static Map<String, Object> page(String id, String modified, String created) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", id);
        p.put("title", "Page " + id);
        p.put("spaceId", "77");
        p.put("createdAt", created);
        p.put("version", Map.of("createdAt", modified, "number", 2));
        p.put("_links", Map.of("webui", "/spaces/ENG/pages/" + id + "/Page"));
        return p;
    }

    // ------------------------------------------------------------------ token provider

    @Test
    void tvmTokenIsCachedAndRenewedBeforeExpiry() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Instant[] now = {Instant.parse("2026-11-07T10:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        TvmTokenProvider tvm = fakeTvm(counter, clock);
        assertEquals("tok-1", tvm.token());
        now[0] = now[0].plusSeconds(800);
        assertEquals("tok-1", tvm.token(), "still valid (expires in 100 s, renewal 60 s before)");
        now[0] = now[0].plusSeconds(50);
        assertEquals("tok-2", tvm.token(), "renewed 60 s before the 900 s expiry");
        tvm.invalidate();
        assertEquals("tok-3", tvm.token());
    }

    @Test
    void tvmAnswerWithoutTokenIsAnAuthFailure() {
        TvmTokenProvider tvm = new TvmTokenProvider(v2(List.of(), List.of(), "").tvm(),
                () -> "{\"error\":\"invalid_request\",\"error_description\":\"no kerberos ticket\"}", Clock.systemUTC());
        SourceException e = assertThrows(SourceException.class, tvm::token);
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("no kerberos ticket"));
        TvmTokenProvider html = new TvmTokenProvider(v2(List.of(), List.of(), "").tvm(), () -> "<html>login</html>", Clock.systemUTC());
        assertEquals(SourceException.Kind.AUTH_FAILED, assertThrows(SourceException.class, html::token).kind());
    }

    @Test
    void curlCommandMatchesTheDocumentedNegotiateCall() {
        ConfluenceOptions.Tvm t = v2(List.of(), List.of(), "").tvm();
        List<String> cmd = TvmTokenProvider.curlCommand(t, ProxyConfig.parse("webproxy.example.com", "8080", "", "", ""), Duration.ofSeconds(20));
        assertEquals("curl", cmd.get(0));
        assertTrue(cmd.contains("--negotiate"));
        int u = cmd.indexOf("-u");
        assertEquals(":", cmd.get(u + 1));
        assertTrue(cmd.contains("--noproxy"), "the TVM is internal: no proxy by default");
        assertEquals(t.url(), cmd.get(cmd.size() - 1));
        ConfluenceOptions.Tvm viaProxy = new ConfluenceOptions.Tvm(t.url(), "curl", "curl", true, "", 60);
        List<String> proxied = TvmTokenProvider.curlCommand(viaProxy, ProxyConfig.parse("webproxy.example.com", "8080", "", "", ""), Duration.ofSeconds(20));
        assertEquals("http://webproxy.example.com:8080", proxied.get(proxied.indexOf("--proxy") + 1));
    }

    // ------------------------------------------------------------------ REST API v2

    @Test
    void pingUsesV2SpacesWithBearerToken() throws Exception {
        handler = r -> new Reply(200, Map.of("results", List.of(Map.of("id", "77", "key", "ENG"))));
        var adapter = adapter(v2(List.of(), List.of(), ""), fakeTvm(new AtomicInteger(), Clock.systemUTC()));
        String ping = adapter.ping();
        assertTrue(ping.contains("v2"));
        assertEquals("/wiki/api/v2/spaces", requests.get(0).path());
        assertEquals("Bearer tok-1", requests.get(0).auth());
        assertFalse(ping.contains("tok-1"));
    }

    @Test
    void expiredTokenIsRenewedOnceAfter401() throws Exception {
        handler = r -> "Bearer tok-1".equals(r.auth())
                ? new Reply(401, Map.of("message", "token expired"))
                : new Reply(200, Map.of("results", List.of()));
        AtomicInteger counter = new AtomicInteger();
        var adapter = adapter(v2(List.of(), List.of(), ""), fakeTvm(counter, Clock.systemUTC()));
        adapter.ping();
        assertEquals(2, requests.size());
        assertEquals("Bearer tok-2", requests.get(1).auth());
        assertEquals(2, counter.get());
    }

    @Test
    void listsPagesInSpacesNewestFirstAndFollowsNextLinks() throws Exception {
        handler = r -> {
            if (r.path().equals("/wiki/api/v2/spaces")) {
                assertEquals("ENG", r.params().get("keys"));
                return new Reply(200, Map.of("results", List.of(Map.of("id", "77", "key", "ENG"))));
            }
            assertEquals("/wiki/api/v2/pages", r.path());
            if (r.params().containsKey("cursor")) {
                return new Reply(200, Map.of("results", List.of(page("3", "2026-08-01T00:00:00Z", "2026-01-01T00:00:00Z")),
                        "_links", Map.of()));
            }
            assertEquals("77", r.params().get("space-id"));
            assertEquals("-modified-date", r.params().get("sort"));
            return new Reply(200, Map.of("results", List.of(
                            page("1", "2026-10-02T00:00:00Z", "2026-01-01T00:00:00Z"),
                            page("2", "2026-09-02T00:00:00Z", "2026-01-01T00:00:00Z")),
                    "_links", Map.of("next", "/wiki/api/v2/pages?cursor=abc&space-id=77", "base", origin + "/wiki")));
        };
        var adapter = adapter(v2(List.of("ENG"), List.of(), ""), fakeTvm(new AtomicInteger(), Clock.systemUTC()));
        TagFilter all = new TagFilter(List.of("all"), TagMode.ANY, false);
        Page<QuestionSummary> first = adapter.listQuestions(all, null, null);
        assertEquals(List.of("1", "2"), first.items().stream().map(QuestionSummary::id).toList());
        assertEquals(origin + "/wiki/spaces/ENG/pages/1/Page", first.items().get(0).url());
        Page<QuestionSummary> second = adapter.listQuestions(all, null, first.nextCursor());
        assertEquals(List.of("3"), second.items().stream().map(QuestionSummary::id).toList());
        assertNull(second.nextCursor());

        // Incremental: stops at the first page older than the checkpoint (results are newest first).
        requests.clear();
        Page<QuestionSummary> incremental = adapter.listQuestions(all, "2026-09-15T00:00:00Z", null);
        assertEquals(List.of("1"), incremental.items().stream().map(QuestionSummary::id).toList());
        assertNull(incremental.nextCursor());
    }

    @Test
    void labelFilterChecksEachPagesLabels() throws Exception {
        handler = r -> {
            if (r.path().equals("/wiki/api/v2/pages")) {
                return new Reply(200, Map.of("results", List.of(
                        page("1", "2026-10-02T00:00:00Z", "2026-01-01T00:00:00Z"),
                        page("2", "2026-10-01T00:00:00Z", "2026-01-01T00:00:00Z"))));
            }
            if (r.path().equals("/wiki/api/v2/pages/1/labels")) {
                return new Reply(200, Map.of("results", List.of(Map.of("name", "runbook"))));
            }
            return new Reply(200, Map.of("results", List.of(Map.of("name", "other"))));
        };
        var adapter = adapter(v2(List.of(), List.of(), ""), fakeTvm(new AtomicInteger(), Clock.systemUTC()));
        Page<QuestionSummary> page = adapter.listQuestions(new TagFilter(List.of("runbook"), TagMode.ANY, false), null, null);
        assertEquals(List.of("1"), page.items().stream().map(QuestionSummary::id).toList());
    }

    @Test
    void pageIdsAndAncestorSubtreeAreListed() throws Exception {
        handler = r -> switch (r.path()) {
            case "/wiki/api/v2/pages/500/direct-children" -> new Reply(200, Map.of("results", List.of(
                    Map.of("id", "501", "type", "page"), Map.of("id", "600", "type", "folder"))));
            case "/wiki/api/v2/pages/600/direct-children" -> new Reply(200, Map.of("results", List.of(Map.of("id", "601", "type", "page"))));
            default -> new Reply(200, Map.of("results", List.of()));
        };
        var adapter = adapter(v2(List.of(), List.of("123"), "500"), fakeTvm(new AtomicInteger(), Clock.systemUTC()));
        Page<QuestionSummary> page = adapter.listQuestions(new TagFilter(List.of("all"), TagMode.ANY, false), null, null);
        assertEquals(List.of("123", "501", "601"), page.items().stream().map(QuestionSummary::id).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void fetchesStorageBodyLabelsAndSpaceTag() throws Exception {
        handler = r -> {
            if (r.path().equals("/wiki/api/v2/spaces/77")) {
                return new Reply(200, Map.of("id", "77", "key", "ENG"));
            }
            assertEquals("/wiki/api/v2/pages/1001", r.path());
            assertEquals("storage", r.params().get("body-format"));
            Map<String, Object> p = page("1001", "2026-10-02T12:00:00Z", "2026-09-01T08:00:00Z");
            p.put("authorId", "acc-42");
            p.put("body", Map.of("storage", Map.of("value", "<p>Hello <b>wiki</b></p>", "representation", "storage")));
            p.put("labels", Map.of("results", List.of(Map.of("name", "runbook"))));
            return new Reply(200, p);
        };
        var adapter = adapter(v2(List.of(), List.of(), ""), fakeTvm(new AtomicInteger(), Clock.systemUTC()));
        Question q = adapter.fetchQuestion("1001");
        assertEquals("Page 1001", q.title());
        assertTrue(q.body().contains("Hello"));
        assertTrue(q.tags().contains("runbook"));
        assertTrue(q.tags().contains("space:ENG"));
        assertEquals("acc-42", q.author());
        assertEquals("2026-10-02T12:00:00Z", q.modifiedAt());
        assertEquals(origin + "/wiki/spaces/ENG/pages/1001/Page", q.url());
    }

        // ------------------------------------------------------------------ spaces

    @Test
    void listsEverySpaceAcrossPagesV2() throws Exception {
        handler = r -> {
            assertEquals("/wiki/api/v2/spaces", r.path());
            if (r.params().containsKey("cursor")) {
                return new Reply(200, Map.of("results", List.of(Map.of("id", "3", "key", "HR", "name", "People", "type", "global", "status", "current"))));
            }
            return new Reply(200, Map.of("results", List.of(
                            Map.of("id", "1", "key", "ENG", "name", "Engineering", "type", "global", "status", "current",
                                    "_links", Map.of("webui", "/spaces/ENG")),
                            Map.of("id", "2", "key", "~ada", "name", "Ada", "type", "personal", "status", "current")),
                    "_links", Map.of("next", "/wiki/api/v2/spaces?cursor=xyz", "base", origin + "/wiki")));
        };
        var adapter = adapter(v2(List.of(), List.of(), ""), fakeTvm(new AtomicInteger(), Clock.systemUTC()));
        var listing = adapter.listSpaces(1000);
        assertTrue(listing.complete());
        assertEquals(List.of("ENG", "HR", "~ada"), listing.spaces().stream().map(ConfluenceAdapter.SpaceInfo::key).toList());
        assertEquals(origin + "/wiki/spaces/ENG", listing.spaces().get(0).url());
        assertEquals("personal", listing.spaces().get(2).type());
    }

    @Test
    void spaceAddressesArePastedAsKeys() {
        assertEquals("ENG", ConfluenceOptions.spaceKey("https://acme.atlassian.net/wiki/spaces/ENG/overview"));
        assertEquals("ENG", ConfluenceOptions.spaceKey("https://acme.atlassian.net/wiki/spaces/ENG/pages/123/Title"));
        assertEquals("OPS", ConfluenceOptions.spaceKey("https://wiki.corp.com/display/OPS/Home"));
        assertEquals("~ada", ConfluenceOptions.spaceKey("https://acme.atlassian.net/wiki/spaces/~ada"));
        assertEquals("HR", ConfluenceOptions.spaceKey(" HR "));
        var options = v2(List.of("https://acme.atlassian.net/wiki/spaces/ENG/overview", "ENG", "HR"), List.of(), "");
        assertEquals(List.of("ENG", "HR"), options.spaces());
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void coreConfigReadsTvmSettingsAndValidates() throws Exception {
        Path file = tmp.resolve("qc.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=confluence
                source.baseUrl=https://acme.atlassian.net
                source.confluence.edition=cloud
                source.confluence.apiVersion=v2
                source.confluence.spaces=ENG
                source.auth.method=tvm
                source.confluence.tvm.url=https://atlassian-tvm.example.com/exchange/token/confluence/acme.atlassian.net
                source.allowedPathPrefixes=/wiki/api/v2/
                network.proxyHost=webproxy.example.com
                network.proxyPort=8080
                sync.tags=all
                sync.maxQuestionAge=1y
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertEquals(List.of(), cfg.validate());
        assertTrue(cfg.confluence().v2());
        assertEquals("curl", cfg.confluence().tvm().client());
        assertTrue(cfg.createAdapter(k -> null) instanceof ConfluenceAdapter);

        CoreConfig missing = CoreConfig.load(file, Map.of("source.confluence.tvm.url", ""));
        assertTrue(missing.validate().stream().anyMatch(p -> p.contains("tvm.url")));
        CoreConfig server = CoreConfig.load(file, Map.of("source.confluence.edition", "server"));
        assertTrue(server.validate().stream().anyMatch(p -> p.contains("apiVersion=v2")));
    }
}
