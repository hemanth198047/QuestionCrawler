package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.api.ApiCapabilities;
import com.questioncrawler.core.api.ApiCatalog;
import com.questioncrawler.core.api.OpenApiParser;
import com.questioncrawler.core.api.SwaggerDiscovery;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.ConnectionReport;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An internal Q&A site as described by its owners: a Swashbuckle-style Swagger page, REST endpoints in the Stack Exchange 2.3
 * style, an API access key on every request, no 'site' parameter, a filter named 'withbody' that does NOT exist (a custom one
 * must be created), and compressed responses where some proxies strip the Content-Encoding header.
 */
class InternalApiTest {

    static final String KEY = "internal-secret-key";

    @TempDir
    Path tmp;

    HttpServer server;
    String origin;
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    volatile boolean specHasTagged = true;
    volatile String swaggerIndex = "html-direct";
    volatile String infoMode = "json"; // json | html-login | redirect | docs-page | blocked | bom | empty | text
    volatile List<Integer> allowedSizes = null; // when set, any other 'pagesize' is refused like ASP.NET validation does
    volatile boolean specPageEnum = false; // the Swagger document lists the allowed page sizes
    volatile boolean rejectTagged = false;
    final java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
    volatile String redirectMode = "off"; // off | same-host | no-query | cross-host | loop | login | login-keycloak
    volatile boolean tagInfoBroken = false; // /tags/{t}/info answers 404
    volatile boolean tagsBroken = false; // /tags answers 404
    volatile boolean manyTags = false; // the site has 250 tags (6 + 244), served in pages
    volatile int extraTags = 0; // any number of additional tags (overrides manyTags)
    volatile boolean tagsLyingHasMore = false; // has_more is always false, even when there are more pages
    volatile boolean tagsIgnorePaging = false; // the site sends the first page whatever page is asked for
    volatile int tagDelayMs = 0; // slows every tag page down
    static final Map<Integer, List<Map.Entry<String, Integer>>> CATALOGUES = new java.util.concurrent.ConcurrentHashMap<>();
    volatile int tagsFailFromPage = 0; // /tags answers 404 from this page on (0 = never)
    static final Map<String, Integer> TAGS = new LinkedHashMap<>(Map.of("ai", 2, "ml", 1, "gpu", 1, "finance", 1, "CASC", 7, "casc-prod", 3));
    volatile boolean redirectRootOnly = false; // only the bare API address redirects (like a website behind SSO whose API is open)
    volatile String needBearer = "off"; // off | text | json: the API also wants "Authorization: Bearer API-TOKEN"
    final List<String> authSeen = Collections.synchronizedList(new ArrayList<>());
    volatile String docsAuth = "none"; // none | bearer | key: what the documentation itself demands

    // ------------------------------------------------------------------ the fake internal site
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0); // all addresses, so 127.0.0.2 can stand in for "another host"
        server.createContext("/", this::route);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void route(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        requests.add(path + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
        boolean viaGateway = path.startsWith("/gw/");
        if (viaGateway) {
            path = path.substring(3); // the gateway forwards to the real API
        }
        String rawQuery = ex.getRequestURI().getRawQuery();
        if (path.equals("/landing")) {
            send(ex, 200, "text/html", "<html><head><title>Welcome</title></head><body>Questions and answers</body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (path.equals("/sso/login")) {
            send(ex, 200, "text/html", "<html><head><title>Sign in</title></head><body><form><input type=\"password\"></form></body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (path.startsWith("/auth/realms/")) {
            send(ex, 200, "text/html", "<html><head><title>Sign in to acme</title></head><body><form><input type=\"password\"></form></body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (!viaGateway && !redirectMode.equals("off") && ((redirectRootOnly ? path.equals("/api/2.3") : path.startsWith("/api/2.3")) || path.equals("/start"))) {
            String suffix = rawQuery == null ? "" : "?" + rawQuery;
            String target = switch (redirectMode) {
                case "no-query" -> "/gw" + path;
                case "cross-host" -> "http://127.0.0.2:" + server.getAddress().getPort() + "/gw" + path + suffix;
                case "loop" -> path + suffix;
                case "login" -> "/sso/login?returnUrl=%2Fstart";
                case "login-keycloak" -> "/auth/realms/acme/protocol/openid-connect/auth?client_id=web&response_type=code";
                default -> path.equals("/start") ? "/landing" : "/gw" + path + suffix;
            };
            ex.getResponseHeaders().add("Location", target);
            ex.sendResponseHeaders(307, -1);
            ex.close();
            return;
        }
        if (path.equals("/start")) {
            send(ex, 200, "text/html", "<html><head><title>Welcome</title></head><body>No redirect here</body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (path.startsWith("/swagger/") && !docsAuth.equals("none")) {
            boolean allowed = docsAuth.equals("bearer") ? "Bearer DOCS-TOKEN".equals(ex.getRequestHeaders().getFirst("Authorization")) : KEY.equals(q.get("key"));
            if (!allowed) {
                send(ex, 401, "text/plain", "sign in".getBytes(), false, true);
                return;
            }
        }
        if (path.equals("/swagger/index.html")) {
            String html = switch (swaggerIndex) {
                case "html-direct" -> "<html><body><div id=\"swagger-ui\"></div><script>window.ui = SwaggerUIBundle({ url: \"/swagger/v1/swagger.json\", dom_id: '#swagger-ui' })</script></body></html>";
                case "initializer" -> "<html><body><div id=\"swagger-ui\"></div><script src=\"./swagger-initializer.js\"></script></body></html>";
                default -> "<html><body>Swagger UI (spec link not in the page)</body></html>";
            };
            send(ex, 200, "text/html", html.getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (path.equals("/swagger/swagger-initializer.js")) {
            send(ex, 200, "application/javascript", "window.onload = function() { SwaggerUIBundle({ urls: [{url: \"/swagger/v1/swagger.json\", name: \"v1\"}], dom_id: '#swagger-ui' }) }".getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (path.equals("/swagger/v1/swagger.json")) {
            send(ex, 200, "application/json", spec().getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (!path.startsWith("/api/2.3/")) {
            send(ex, 404, "text/plain", "no".getBytes(), false, true);
            return;
        }
        // ---- a gateway in front of the API that wants a Bearer token
        if (!needBearer.equals("off")) {
            String given = ex.getRequestHeaders().getFirst("Authorization");
            authSeen.add(given == null ? "(none)" : given);
            if (!"Bearer API-TOKEN".equals(given)) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"acme\"");
                if (needBearer.equals("json")) {
                    send(ex, 401, "application/json", "{\"message\":\"Missing bearer token\"}".getBytes(StandardCharsets.UTF_8), false, true);
                } else {
                    send(ex, 401, "text/plain", "Missing bearer token".getBytes(StandardCharsets.UTF_8), false, true);
                }
                return;
            }
        }
        // ---- the API: every request needs the key (query or X-Api-Key header)
        boolean keyed = KEY.equals(q.get("key")) || KEY.equals(ex.getRequestHeaders().getFirst("X-Api-Key"));
        if (!keyed) {
            sendJson(ex, 400, Map.of("error_id", 405, "error_name", "key_required", "error_message", "An API access key is required"), true);
            return;
        }
        String api = path.substring("/api/2.3".length());
        if (allowedSizes != null && q.containsKey("pagesize") && !allowedSizes.contains(Integer.parseInt(q.get("pagesize")))) {
            rejected.incrementAndGet();
            List<String> words = allowedSizes.stream().map(String::valueOf).toList();
            String list = String.join(", ", words.subList(0, words.size() - 1)) + " or " + words.get(words.size() - 1);
            send(ex, 400, "application/problem+json", ("{\"errors\":{\"PageSize\":[\"Invalid page size, use " + list + ".\"]},\"type\":\"https://tools.ietf.org/html/rfc9110#section-15.5.1\","
                    + "\"title\":\"One or more validation errors occured.\",\"status\":400,\"tradeId\":\"00-6aed988600000000cef75b58a0fe95c5\"}").getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        if (rejectTagged && q.containsKey("tagged")) {
            send(ex, 400, "application/problem+json", "{\"errors\":{\"Tagged\":[\"Tag 'ai' does not exist.\"]},\"title\":\"One or more validation errors occured.\",\"status\":400}".getBytes(StandardCharsets.UTF_8), false, true);
            return;
        }
        String filter = q.getOrDefault("filter", "default");
        if (filter.equals("withbody")) {
            sendJson(ex, 400, Map.of("error_id", 400, "error_name", "bad_parameter", "error_message", "filter 'withbody' is not a known filter"), true);
            return;
        }
        boolean withBody = filter.equals("!custom");
        switch (api) {
            case "/filters/create" -> sendJson(ex, 200, Map.of("items", List.of(Map.of("filter", "!custom", "filter_type", "safe")), "has_more", false), true);
            case "/info" -> sendInfo(ex);
            case "/questions" -> sendJson(ex, 200, wrap(questions(q, withBody), false), true);
            case "/questions/101;102;103/answers", "/questions/101;102/answers", "/questions/101/answers", "/questions/102/answers" ->
                    sendJson(ex, 200, wrap(answers(withBody), false), false); // compressed WITHOUT the Content-Encoding header
            case "/questions/101/comments", "/questions/101;102;103/comments", "/questions/101;102/comments" ->
                    sendJson(ex, 200, wrap(List.of(Map.of("comment_id", 9001, "post_id", 101, "body", "Which version are you on?", "creation_date", 1_700_000_100L, "owner", Map.of("display_name", "sam"))), false), true);
            case "/answers/201/comments", "/answers/201;202/comments", "/answers/202;201/comments" ->
                    sendJson(ex, 200, wrap(List.of(Map.of("comment_id", 9002, "post_id", 201, "body", "Thanks, that fixed it", "creation_date", 1_700_000_200L, "owner", Map.of("display_name", "li"))), false), true);
            case "/tags" -> {
                if (tagsBroken) {
                    sendJson(ex, 404, Map.of("error_id", 404, "error_name", "no_method", "error_message", "no such method"), true);
                    return;
                }
                String inname = q.getOrDefault("inname", "").toLowerCase();
                int size = Integer.parseInt(q.getOrDefault("pagesize", "100"));
                int pageNo = tagsIgnorePaging ? 1 : Integer.parseInt(q.getOrDefault("page", "1"));
                if (tagDelayMs > 0) {
                    try { Thread.sleep(tagDelayMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                if (tagsFailFromPage > 0 && pageNo >= tagsFailFromPage) {
                    sendJson(ex, 404, Map.of("error_id", 404, "error_name", "no_method", "error_message", "no such page"), true);
                    return;
                }
                int extra = extraTags > 0 ? extraTags : manyTags ? 244 : 0;
                List<Map.Entry<String, Integer>> catalogue = CATALOGUES.computeIfAbsent(extra, n -> {
                    List<Map.Entry<String, Integer>> all = new ArrayList<>(new LinkedHashMap<>(TAGS).entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList());
                    for (int i = 1; i <= n; i++) all.add(Map.entry(String.format("tag-%06d", i), (i % 5) + 1));
                    return all;
                });
                List<Map.Entry<String, Integer>> matching = inname.isEmpty() ? catalogue : catalogue.stream().filter(e -> e.getKey().toLowerCase().contains(inname)).toList();
                int from = Math.min(matching.size(), (pageNo - 1) * size);
                int to = Math.min(matching.size(), from + size);
                List<Map<String, Object>> tags = new ArrayList<>();
                for (int i = from; i < to; i++) tags.add(Map.of("name", matching.get(i).getKey(), "count", matching.get(i).getValue()));
                sendJson(ex, 200, wrap(tags, !tagsLyingHasMore && to < matching.size()), true);
            }
            default -> {
                if (api.startsWith("/tags/") && api.endsWith("/info")) {
                    if (tagInfoBroken) {
                        sendJson(ex, 404, Map.of("error_id", 404, "error_name", "no_method", "error_message", "no such method"), true);
                        return;
                    }
                    String name = api.split("/")[2]; // case-sensitive, like a site that stores tags as typed
                    sendJson(ex, 200, wrap(TAGS.containsKey(name) ? List.of(Map.of("name", name, "count", TAGS.get(name))) : List.of(), false), true);
                } else {
                    sendJson(ex, 404, Map.of("error_id", 404, "error_name", "no_method", "error_message", "no such method"), true);
                }
            }
        }
    }

    private void sendInfo(HttpExchange ex) throws java.io.IOException {
        switch (infoMode) {
            case "html-login" -> send(ex, 200, "text/html; charset=utf-8", "<html><head><title>Sign in to Acme</title></head><body><form>Please sign in with single sign-on</form></body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            case "redirect" -> {
                ex.getResponseHeaders().add("Location", "https://sso.example.com/login?returnUrl=%2Fapi%2F2.3%2Finfo&key=" + KEY);
                ex.sendResponseHeaders(302, -1);
                ex.close();
            }
            case "docs-page" -> send(ex, 200, "text/html", "<html><head><title>Swagger UI</title></head><body><div id=\"swagger-ui\"></div></body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            case "blocked" -> send(ex, 403, "text/html", "<html><head><title>Access Denied</title></head><body>Your request was blocked by the web filter policy (category: uncategorized).</body></html>".getBytes(StandardCharsets.UTF_8), false, true);
            case "bom" -> send(ex, 200, "application/json", ("\uFEFF)]}',\n" + Json.write(wrap(List.of(Map.of("total_questions", 3)), false))).getBytes(StandardCharsets.UTF_8), false, true);
            case "empty" -> send(ex, 200, "application/json", new byte[0], false, true);
            case "text" -> send(ex, 200, "text/plain", "OK".getBytes(StandardCharsets.UTF_8), false, true);
            default -> sendJson(ex, 200, wrap(List.of(Map.of("total_questions", 3)), false), true);
        }
    }

    private List<Map<String, Object>> questions(Map<String, String> q, boolean withBody) {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(question(101, "How do I fine-tune a transformer model?", List.of("ai", "ml"), 1_700_000_000L, 1_700_000_500L, 1, 1, withBody, "I want to fine-tune a <b>transformer</b>; loss does not drop.<pre><code>trainer.train()</code></pre>"));
        all.add(question(102, "GPU memory error while training", List.of("ai", "gpu"), 1_700_100_000L, 1_700_100_500L, 1, 0, withBody, "CUDA out of memory when training the network."));
        all.add(question(104, "How does CASC token refresh work?", List.of("CASC", "platform"), 1_700_300_000L, 1_700_300_500L, 0, 0, withBody, "Question about the CASC service."));
        all.add(question(103, "Quarterly budget template", List.of("finance"), 1_700_200_000L, 1_700_200_500L, 0, 0, withBody, "Where is the budget spreadsheet?"));
        String tagged = q.get("tagged");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : all) {
            @SuppressWarnings("unchecked") List<String> tags = (List<String>) m.get("tags");
            if (tagged != null && !tags.containsAll(List.of(tagged.split(";")))) continue;
            if (q.containsKey("fromdate") && ((Long) m.get("creation_date")) < Long.parseLong(q.get("fromdate"))) continue;
            if (q.containsKey("min") && ((Long) m.get("last_activity_date")) < Long.parseLong(q.get("min"))) continue;
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> question(int id, String title, List<String> tags, long created, long activity, int answers, int comments, boolean body, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("question_id", id);
        m.put("title", title);
        m.put("tags", tags);
        m.put("link", "https://so.internal.example/questions/" + id);
        m.put("score", 4);
        m.put("creation_date", created);
        m.put("last_activity_date", activity);
        m.put("answer_count", answers);
        m.put("comment_count", comments);
        m.put("owner", Map.of("display_name", "alice"));
        if (body) m.put("body", text);
        return m;
    }

    private List<Map<String, Object>> answers(boolean withBody) {
        List<Map<String, Object>> a = new ArrayList<>();
        a.add(answer(201, 101, true, withBody, "Lower the learning rate and use a warm-up schedule."));
        a.add(answer(202, 102, false, withBody, "Reduce the batch size."));
        return a;
    }

    private static Map<String, Object> answer(int id, int qid, boolean accepted, boolean body, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answer_id", id);
        m.put("question_id", qid);
        m.put("is_accepted", accepted);
        m.put("score", 2);
        m.put("creation_date", 1_700_000_300L);
        m.put("last_activity_date", 1_700_000_300L);
        m.put("comment_count", id == 201 ? 1 : 0);
        m.put("owner", Map.of("display_name", "bob"));
        if (body) m.put("body", text);
        return m;
    }

    private static Map<String, Object> wrap(Object items, boolean more) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("has_more", more);
        m.put("quota_max", 10000);
        m.put("quota_remaining", 9990);
        return m;
    }

    private String spec() {
        String taggedParam = specHasTagged ? "{\"name\":\"tagged\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}," : "";
        return """
                {"openapi":"3.0.1","info":{"title":"Internal Q&A API","version":"2.3"},
                 "servers":[{"url":"/api/2.3"}],
                 "components":{"securitySchemes":{"ApiKey":{"type":"apiKey","in":"query","name":"key","description":"API access key"}},
                   "parameters":{"page":{"name":"page","in":"query","schema":{"type":"integer"}}}},
                 "security":[{"ApiKey":[]}],
                 "paths":{
                  "/questions":{"get":{"tags":["Questions"],"summary":"Questions","operationId":"listQuestions","parameters":[
                       %s {"name":"fromdate","in":"query","schema":{"type":"integer"}},{"name":"min","in":"query","schema":{"type":"integer"}},
                       {"name":"filter","in":"query","schema":{"type":"string"}},{"$ref":"#/components/parameters/page"},{"name":"pagesize","in":"query","schema":{"type":"integer"%s}}],
                       "responses":{"200":{"description":"ok"},"400":{"description":"bad"}}}},
                  "/questions/{ids}":{"get":{"tags":["Questions"],"summary":"Questions by id","parameters":[{"name":"ids","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}},
                  "/questions/{ids}/answers":{"get":{"tags":["Answers"],"summary":"Answers of questions","parameters":[{"name":"ids","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}},
                  "/questions/{ids}/comments":{"get":{"tags":["Comments"],"summary":"Comments on questions","parameters":[{"name":"ids","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}},
                  "/answers/{ids}/comments":{"get":{"tags":["Comments"],"summary":"Comments on answers","parameters":[{"name":"ids","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}},
                  "/tags":{"get":{"tags":["Tags"],"summary":"Tags","responses":{"200":{"description":"ok"}}}},
                  "/tags/{tags}/info":{"get":{"tags":["Tags"],"summary":"Tag info","parameters":[{"name":"tags","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}},
                  "/info":{"get":{"tags":["Info"],"summary":"Site info","responses":{"200":{"description":"ok"}}}},
                  "/filters/create":{"get":{"tags":["Filters"],"summary":"Create filter","responses":{"200":{"description":"ok"}}}},
                  "/users/{ids}/questions":{"get":{"tags":["Users"],"summary":"Questions by users","parameters":[{"name":"ids","in":"path","required":true,"schema":{"type":"string"}}],"responses":{"200":{"description":"ok"}}}},
                  "/me/questions":{"get":{"tags":["Users"],"summary":"My questions","responses":{"200":{"description":"ok"}}}},
                  "/users":{"get":{"tags":["Users"],"summary":"Users","responses":{"200":{"description":"ok"}}}}
                 }}""".formatted(taggedParam, specPageEnum ? ",\"enum\":[15,30]" : "");
    }

    // ------------------------------------------------------------------ http helpers
    private static void sendJson(HttpExchange ex, int status, Object body, boolean contentEncodingHeader) throws java.io.IOException {
        send(ex, status, "application/json", Json.write(body).getBytes(StandardCharsets.UTF_8), true, contentEncodingHeader);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] bytes, boolean gzip, boolean header) throws java.io.IOException {
        byte[] out = bytes;
        if (gzip) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(bytes); }
            out = bos.toByteArray();
            if (header) ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw != null) for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private SourceSettings settings() {
        return new SourceSettings("internal", origin, List.of("127.0.0.1"), List.of("/"), "none", null, null, null, null, Duration.ofSeconds(5), true);
    }

    private SwaggerDiscovery discovery() {
        var client = new HttpSourceClient(settings(), k -> null);
        return new SwaggerDiscovery(url -> client.get(url).body());
    }

    // ------------------------------------------------------------------ reading the documentation
    @Test
    void theSpecIsFoundWhenThePageNamesItDirectly() throws Exception {
        var d = discovery().discover(origin + "/swagger/index.html");
        assertEquals("Internal Q&A API", d.catalog().title());
        assertEquals(12, d.catalog().operations().size());
        assertEquals(List.of(origin + "/swagger/v1/swagger.json"), d.catalog().specUrls());
        assertEquals("/api/2.3", d.catalog().suggestedBaseUrl());
        assertEquals("apiKey", d.catalog().security().get(0).type());
        assertEquals("key", d.catalog().security().get(0).name());
        assertEquals("query", d.catalog().security().get(0).in());
    }

    @Test
    void theSpecIsFoundThroughTheSmallInitializerScript() throws Exception {
        swaggerIndex = "initializer";
        var d = discovery().discover(origin + "/swagger/index.html");
        assertEquals(12, d.catalog().operations().size());
        assertTrue(requests.stream().anyMatch(r -> r.startsWith("/swagger/swagger-initializer.js")));
    }

    @Test
    void theSpecIsFoundAtTheUsualLocationWhenThePageDoesNotSayWhere() throws Exception {
        swaggerIndex = "bare";
        var d = discovery().discover(origin + "/swagger/index.html");
        assertEquals(12, d.catalog().operations().size());
    }

    @Test
    void aDirectLinkToTheJsonWorksAndAnUnknownAddressExplainsWhatToDo() throws Exception {
        assertEquals(12, discovery().discover(origin + "/swagger/v1/swagger.json").catalog().operations().size());
        // A page elsewhere on the same host still finds the document at the usual location.
        assertEquals(12, discovery().discover(origin + "/nothing/index.html").catalog().operations().size());
        // A host without any documentation explains what to do and lists what was tried.
        String dead = "http://127.0.0.1:1/nothing/index.html";
        var client = new HttpSourceClient(new SourceSettings("x", dead, List.of("127.0.0.1"), List.of("/"), "none", null, null, null, null, Duration.ofSeconds(2), true), k -> null);
        SourceException e = assertThrows(SourceException.class, () -> new SwaggerDiscovery(url -> client.get(url).body()).discover(dead));
        assertTrue(e.getMessage().contains("click the link right under the title"), e.getMessage());
        assertTrue(e.getMessage().contains("/nothing/"), "the addresses that were tried are listed");
    }

    @Test
    void everyEndpointIsListedAndTheRightOnesAreChosenForEachPurpose() throws Exception {
        var d = discovery().discover(origin + "/swagger/index.html");
        ApiCapabilities c = d.capabilities();
        assertTrue(c.ready());
        assertEquals("/questions", c.capabilities().stream().filter(x -> x.id().equals("questions")).findFirst().orElseThrow().path(),
                "'/questions' is not confused with '/users/{ids}/questions' or '/me/questions'");
        assertEquals("/questions/{ids}/answers", c.capabilities().stream().filter(x -> x.id().equals("answers")).findFirst().orElseThrow().path());
        assertTrue(c.capabilities().stream().filter(x -> List.of("questionComments", "answerComments", "tags", "tagInfo", "filterCreate", "info", "question").contains(x.id())).allMatch(x -> x.found()));
        assertTrue(c.taggedFilter() && c.fromDate() && c.minActivity() && c.customFilter() && c.createFilter());
        assertEquals(3, c.otherEndpoints(), "users endpoints are listed but not used");
        assertEquals(List.of("tagged", "fromdate", "min", "filter", "page", "pagesize"),
                d.catalog().find("GET", "/questions").orElseThrow().parameters().stream().map(ApiCatalog.Parameter::name).toList(), "a $ref parameter is resolved");
    }

    @Test
    void missingRequiredEndpointsAndMissingFeaturesAreReported() throws Exception {
        String thin = "{\"swagger\":\"2.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"host\":\"so.example.com\",\"basePath\":\"/api\",\"schemes\":[\"https\"],"
                + "\"securityDefinitions\":{\"k\":{\"type\":\"apiKey\",\"in\":\"header\",\"name\":\"X-Api-Key\"}},"
                + "\"paths\":{\"/questions\":{\"get\":{\"parameters\":[{\"name\":\"page\",\"in\":\"query\",\"type\":\"integer\"}],\"responses\":{\"200\":{}}}}}}";
        ApiCatalog catalog = OpenApiParser.parse(thin, "x");
        assertEquals("https://so.example.com/api", catalog.suggestedBaseUrl());
        assertEquals("header", catalog.security().get(0).in());
        ApiCapabilities c = ApiCapabilities.analyze(catalog);
        assertFalse(c.ready());
        assertTrue(c.problems().stream().anyMatch(p -> p.contains("/questions/{ids}/answers")));
        assertTrue(c.problems().stream().anyMatch(p -> p.contains("'tagged'")));
        assertFalse(c.taggedFilter());
    }

    // ------------------------------------------------------------------ using the API for real
    private CoreConfig config(String extra) throws Exception {
        Path file = tmp.resolve("c.properties");
        Files.writeString(file, "storage.root=" + tmp.resolve("data").toString().replace('\\', '/') + "\nsource.type=live\nsource.allowInsecureHttp=true\n"
                + "api.swaggerUrl=" + origin + "/swagger/index.html\napi.keyEnv=INTERNAL_KEY\nsync.tags=ai\nsync.concurrency=2\n" + extra);
        return CoreConfig.load(file, Map.of());
    }

    private static Function<String, String> env() {
        return k -> k.equals("INTERNAL_KEY") ? KEY : null;
    }

    private SyncOptions options(CoreConfig c) {
        return c.syncOptions(null, TagMode.ANY, false, SyncMode.INCREMENTAL);
    }

    @Test
    void collectingUsesTheDocumentedEndpointsAndLearnsEverythingFromSwagger() throws Exception {
        CoreConfig c = config("api.collectComments=true\n");
        assertEquals(List.of(), c.validate());
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));

        assertEquals(SyncReport.COMPLETE, r.outcome(), r.failures() + " " + r.abortReason());
        assertEquals(2, r.added(), "only the questions tagged 'ai'");
        var t = kb.store().loadThread("internal-qa", "101").orElseThrow();
        assertEquals("How do I fine-tune a transformer model?", t.question().title());
        assertTrue(t.question().body().contains("```\ntrainer.train()\n```") || t.question().body().contains("trainer.train()"), t.question().body());
        assertEquals(1, t.question().answers().size());
        assertTrue(t.question().answers().get(0).accepted());
        assertEquals("Which version are you on?", t.question().comments().get(0).body(), "comments on the question");
        assertEquals("Thanks, that fixed it", t.question().answers().get(0).comments().get(0).body(), "comments on the answer");
        assertEquals("https://so.internal.example/questions/101", t.question().url());

        // How the API was called.
        assertTrue(requests.stream().noneMatch(x -> x.startsWith("/api/") && x.contains("site=")), "no 'site' parameter: this API has none");
        assertTrue(requests.stream().filter(x -> x.startsWith("/api/")).allMatch(x -> x.contains("key=" + KEY)), "the key is on every request");
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/api/2.3/filters/create")), "withbody is unknown, so a filter was created");
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/api/2.3/questions?") && x.contains("tagged=ai") && x.contains("filter=%21custom")));
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/api/2.3/questions/101;102/answers")), "answers are fetched in one batched call");
        assertTrue(kb.search(new SearchRequest("fine-tune transformer learning rate", null, null, 5, 0, 0)).resultCount() > 0);
    }

    @Test
    void theKeyCanBeSentInAHeaderWhenTheDocumentationSaysSo() throws Exception {
        CoreConfig c = config("api.keyIn=header\napi.keyName=X-Api-Key\n");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertTrue(requests.stream().filter(x -> x.startsWith("/api/")).noneMatch(x -> x.contains("key=")), "not in the address");
    }

    @Test
    void aMissingOrWrongKeyIsReportedAsAnAuthenticationProblemNotRetried() throws Exception {
        CoreConfig c = config("");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(k -> null), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.FAILED, r.outcome());
        assertTrue(r.abortReason().contains("AUTH_FAILED") && r.abortReason().toLowerCase().contains("access key"), r.abortReason());
        assertEquals(1, requests.stream().filter(x -> x.startsWith("/api/2.3/questions")).count(), "not retried");
    }

    @Test
    void featuresTheDocumentationDoesNotOfferAreHandledOnOurSide() throws Exception {
        specHasTagged = false;
        CoreConfig c = config("");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertTrue(requests.stream().filter(x -> x.startsWith("/api/2.3/questions?")).noneMatch(x -> x.contains("tagged=")), "no 'tagged' parameter was sent");
        assertEquals(2, kb.store().allThreads().size(), "the finance question was filtered out after download");
    }

    @Test
    void theApiAddressIsTakenFromTheDocumentationAndAnUnreadableOneGivesAClearError() throws Exception {
        CoreConfig c = config("source.baseUrl=\n");
        assertEquals("", c.source().baseUrl());
        assertEquals("internal-qa", c.source().systemId());
        swaggerIndex = "bare";
        assertTrue(c.createAdapter(env()).ping().contains("API reachable"));
        CoreConfig dead = config("api.swaggerUrl=http://127.0.0.1:1/swagger/index.html\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> dead.createAdapter(env()));
        assertTrue(e.getMessage().contains("API address is not known"));
    }

    // ------------------------------------------------------------------ the connection check
    @Test
    void connectionCheckReadsTheDocumentationAndConfirmsATestCall() throws Exception {
        Workspace w = new Workspace(tmp.resolve("w/config.properties"), Map.of("storage.root", tmp.resolve("d").toString()), env(), Clock.systemUTC());
        ConnectionReport r = w.checkConnection(Map.of("source.type", "live", "api.swaggerUrl", origin + "/swagger/index.html", "api.keyEnv", "INTERNAL_KEY",
                "source.allowInsecureHttp", "true", "sync.tags", "ai", "source.timeoutSeconds", "5"));
        var data = r.stages().stream().filter(s -> s.id().equals("data")).findFirst().orElseThrow();
        assertEquals("PASS", data.status(), data.summary() + data.facts());
        assertTrue(data.facts().stream().anyMatch(f -> f.label().equals("API documentation") && f.value().contains("Internal Q&A API")));
        assertTrue(data.facts().stream().anyMatch(f -> f.label().startsWith("Will use: List questions") && f.value().equals("GET /questions")));
        assertTrue(data.facts().stream().anyMatch(f -> f.label().equals("Test call")));
        assertTrue(data.facts().stream().anyMatch(f -> f.label().contains("Access key is sent") && f.value().contains("'key'")));
        assertEquals("REST_API", r.route().kind());
        assertTrue(r.route().supported());
        assertEquals("PASS", r.stages().stream().filter(s -> s.id().equals("scope")).findFirst().orElseThrow().status());
        assertFalse(Json.write(r).contains(KEY), "the key never appears in a report");
    }

    @Test
    void connectionCheckExplainsAMissingKey() throws Exception {
        Workspace w = new Workspace(tmp.resolve("w2/config.properties"), Map.of("storage.root", tmp.resolve("d2").toString()), k -> null, Clock.systemUTC());
        ConnectionReport r = w.checkConnection(Map.of("source.type", "live", "api.swaggerUrl", origin + "/swagger/index.html", "api.keyEnv", "INTERNAL_KEY",
                "source.allowInsecureHttp", "true", "source.timeoutSeconds", "5"));
        var data = r.stages().stream().filter(s -> s.id().equals("data")).findFirst().orElseThrow();
        assertEquals("WARN", data.status());
        assertTrue(data.needs().stream().anyMatch(n -> n.what().contains("API access key") && n.who().contains("site owners")));
        assertTrue(data.facts().stream().anyMatch(f -> f.label().contains("INTERNAL_KEY") && f.value().contains("NOT set")));
        assertEquals("REST_API_INCOMPLETE", r.route().kind());
    }

    @Test
    void theUiCanReadTheDocumentationWithoutSavingAnything() throws Exception {
        Workspace w = new Workspace(tmp.resolve("w3/config.properties"), Map.of("storage.root", tmp.resolve("d3").toString()), k -> null, Clock.systemUTC());
        var result = w.discoverApi(Map.of("api.swaggerUrl", origin + "/swagger/index.html", "source.allowInsecureHttp", "true"));
        assertTrue(result.ok(), result.problem());
        assertEquals(12, result.catalog().operations().size());
        assertEquals(origin + "/api/2.3", result.suggestedBaseUrl());
        assertEquals("key", result.suggestedKeyName());
        assertEquals("query", result.suggestedKeyIn());
        assertFalse(Files.exists(tmp.resolve("w3/config.properties")), "reading documentation never writes settings");
        var bad = w.discoverApi(Map.of("api.swaggerUrl", "http://127.0.0.1:1/swagger/index.html", "source.allowInsecureHttp", "true"));
        assertFalse(bad.ok());
        assertFalse(w.discoverApi(Map.of()).ok());
    }

    @Test
    void settingsForTheInternalApiAreExplainedAndValidated() throws Exception {
        var service = new com.questioncrawler.core.service.ConfigService(k -> null);
        var keys = com.questioncrawler.core.service.ConfigService.sections().stream().flatMap(s -> s.fields().stream()).map(f -> f.key()).toList();
        assertTrue(keys.containsAll(List.of("api.swaggerUrl", "source.baseUrl", "api.keyEnv", "api.keyName", "api.keyIn", "api.collectComments")));
        var none = service.validate(new LinkedHashMap<>(Map.of("source.type", "live")));
        assertTrue(none.stream().anyMatch(i -> i.key().equals("api.swaggerUrl") && i.level().equals("error")));
        var ok = service.validate(new LinkedHashMap<>(Map.of("source.type", "live", "api.swaggerUrl", "https://so.example.com/swagger/index.html", "api.keyEnv", "MY_KEY")));
        assertFalse(ok.stream().anyMatch(i -> i.level().equals("error")), ok.toString());
        assertTrue(ok.stream().anyMatch(i -> i.key().equals("api.keyEnv") && i.level().equals("warning")), "a missing key variable is explained");
    }

    // ------------------------------------------------------------------ documentation that needs a sign-in (SSO)
    @Test
    void documentationBehindASignInExplainsTheOptionsInsteadOfJustFailing() throws Exception {
        docsAuth = "bearer";
        Workspace w = new Workspace(tmp.resolve("s1/config.properties"), Map.of("storage.root", tmp.resolve("s1d").toString()), k -> null, Clock.systemUTC());
        var r = w.discoverApi(Map.of("api.swaggerUrl", origin + "/swagger/v1/swagger.json", "source.allowInsecureHttp", "true"));
        assertFalse(r.ok());
        assertTrue(r.needsSignIn(), "the screen can offer the upload and token options");
        assertTrue(r.problem().contains("single sign-on") && r.problem().contains("upload"), r.problem());
    }

    @Test
    void aBearerTokenFromTheEnvironmentOpensTheDocumentation() throws Exception {
        docsAuth = "bearer";
        Workspace w = new Workspace(tmp.resolve("s2/config.properties"), Map.of("storage.root", tmp.resolve("s2d").toString()),
                k -> k.equals("DOCS_TOKEN") ? "DOCS-TOKEN" : null, Clock.systemUTC());
        var r = w.discoverApi(Map.of("api.swaggerUrl", origin + "/swagger/index.html", "source.allowInsecureHttp", "true",
                "source.auth.method", "token", "source.auth.tokenEnv", "DOCS_TOKEN"));
        assertTrue(r.ok(), r.problem());
        assertEquals(12, r.catalog().operations().size());
    }

    @Test
    void theApiKeyIsTriedOnTheDocumentationWhenItIsRefused() throws Exception {
        docsAuth = "key";
        Workspace w = new Workspace(tmp.resolve("s3/config.properties"), Map.of("storage.root", tmp.resolve("s3d").toString()), env(), Clock.systemUTC());
        var r = w.discoverApi(Map.of("api.swaggerUrl", origin + "/swagger/index.html", "source.allowInsecureHttp", "true", "api.keyEnv", "INTERNAL_KEY"));
        assertTrue(r.ok(), r.problem());
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/swagger/") && x.contains("key=" + KEY)), "retried with the key");
    }

    @Test
    void aSavedCopyOfTheDocumentationWorksWithoutAnySignInAndDrivesACollection() throws Exception {
        docsAuth = "bearer"; // the documentation can no longer be fetched at all
        Workspace w = new Workspace(tmp.resolve("s4/config.properties"), Map.of("storage.root", tmp.resolve("s4d").toString()), env(), Clock.systemUTC());
        var saved = w.saveApiDocs(spec());
        assertTrue(saved.ok(), saved.problem());
        assertEquals(12, saved.operations());
        assertTrue(Files.exists(Path.of(saved.path())));

        var read = w.discoverApi(Map.of("api.swaggerFile", saved.path()));
        assertTrue(read.ok(), read.problem());
        assertEquals(null, read.suggestedBaseUrl(), "a relative server path cannot be turned into an address without the site");
        assertEquals("/api/2.3", read.suggestedBasePath());

        // The connection check: documentation read from the file, address still unknown.
        var check = w.checkConnection(Map.of("source.type", "live", "api.swaggerFile", saved.path(), "api.keyEnv", "INTERNAL_KEY"));
        var data = check.stages().stream().filter(x -> x.id().equals("data")).findFirst().orElseThrow();
        assertEquals("WARN", data.status());
        assertTrue(data.facts().stream().anyMatch(f -> f.label().equals("API documentation") && f.value().contains("saved file")));
        assertTrue(data.facts().stream().anyMatch(f -> f.label().startsWith("API path")), data.facts().toString());
        assertEquals("SKIPPED", check.stages().get(0).status());

        // With the address given, collecting works from the saved file alone.
        CoreConfig c = config("api.swaggerUrl=\napi.swaggerFile=" + saved.path() + "\nsource.baseUrl=" + origin + "/api/2.3\n");
        assertEquals(List.of(), c.validate());
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertEquals(2, r.added());
        assertTrue(requests.stream().noneMatch(x -> x.startsWith("/swagger/")), "no documentation request was needed");

        // Without the address the error says what the documentation gives.
        CoreConfig noBase = config("api.swaggerUrl=\napi.swaggerFile=" + saved.path() + "\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> noBase.createAdapter(env()));
        assertTrue(e.getMessage().contains("/api/2.3"), e.getMessage());
    }

    @Test
    void somethingThatIsNotSwaggerIsRefusedWithAHelpfulMessage() {
        Workspace w = new Workspace(tmp.resolve("s5/config.properties"), Map.of("storage.root", tmp.resolve("s5d").toString()), k -> null, Clock.systemUTC());
        var html = w.saveApiDocs("<html><body>Please sign in</body></html>");
        assertFalse(html.ok());
        assertTrue(html.problem().contains("not a Swagger/OpenAPI JSON document"), html.problem());
        assertTrue(w.saveApiDocs("openapi: 3.0.0\ninfo: {}").problem().contains("YAML"));
        assertFalse(Files.exists(w.apiDocsFile()), "nothing is kept when the file is not documentation");
    }

    // ------------------------------------------------------------------ "the answer is not JSON": say what it is
    private SourceException pingFailure(String mode) throws Exception {
        infoMode = mode;
        CoreConfig c = config("");
        return assertThrows(SourceException.class, () -> c.createAdapter(env()).ping());
    }

    @Test
    void aSignInPageIsRecognisedAndExplained() throws Exception {
        SourceException e = pingFailure("html-login");
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("sign-in page") && e.getMessage().contains("Sign in to Acme"), e.getMessage());
        assertTrue(e.getMessage().contains("See what the API really answers"), e.getMessage());
        assertFalse(e.getMessage().contains("not valid JSON"));
    }

    @Test
    void aRedirectToSingleSignOnShowsWhereItGoesWithoutLeakingTheKey() throws Exception {
        infoMode = "redirect";
        SourceException e = assertThrows(SourceException.class, () -> config("source.followRedirects=off\n").createAdapter(env()).ping());
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("redirected") && e.getMessage().contains("https://sso.example.com/login"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY) || e.getMessage().contains("returnUrl"), "the query string is not shown: " + e.getMessage());
    }

    @Test
    void theDocumentationPageAsApiAddressIsRecognised() throws Exception {
        SourceException e = pingFailure("docs-page");
        assertTrue(e.getMessage().contains("documentation page, not the API") || e.getMessage().contains("looks like the API documentation"), e.getMessage());
    }

    @Test
    void aProxyBlockPageIsRecognised() throws Exception {
        SourceException e = pingFailure("blocked");
        assertTrue(e.getMessage().contains("proxy or firewall"), e.getMessage());
    }

    @Test
    void emptyAndPlainTextAnswersAreNamedForWhatTheyAre() throws Exception {
        assertTrue(pingFailure("empty").getMessage().contains("answer was empty"));
        assertTrue(pingFailure("text").getMessage().contains("plain text"));
    }

    @Test
    void aByteOrderMarkAndASecurityPrefixDoNotBreakJson() throws Exception {
        infoMode = "bom";
        CoreConfig c = config("");
        assertTrue(c.createAdapter(env()).ping().contains("has 3 questions"));
    }

    // ------------------------------------------------------------------ see the raw answer
    private Workspace workspace(String name, Function<String, String> environment) {
        return new Workspace(tmp.resolve(name + "/config.properties"), Map.of("storage.root", tmp.resolve(name + "d").toString()), environment, Clock.systemUTC());
    }

    private Map<String, String> draft() {
        return Map.of("source.type", "live", "source.baseUrl", origin + "/api/2.3", "api.keyEnv", "INTERNAL_KEY", "source.allowInsecureHttp", "true", "source.timeoutSeconds", "5");
    }

    @Test
    void theRawAnswerShowsStatusTypeAndBodyButNeverTheKey() throws Exception {
        var ok = workspace("p1", env()).probeApi(draft(), "/info");
        assertTrue(ok.reached());
        assertEquals(200, ok.status());
        assertEquals("JSON", ok.kind());
        assertTrue(ok.preview().contains("total_questions"));
        assertTrue(ok.keySent());
        assertEquals("in the address as 'key'", ok.keyPlacement());
        assertFalse(ok.url().contains(KEY) || Json.write(ok).contains(KEY), "the key is hidden: " + ok.url());
        assertTrue(ok.url().contains("key=%E2%80%A2%E2%80%A2%E2%80%A2") || ok.url().contains("key=•••"), ok.url());
    }

    @Test
    void theRawAnswerExplainsEachKindOfWrongAnswer() throws Exception {
        for (var e : Map.of("html-login", "HTML_LOGIN", "redirect", "REDIRECT", "docs-page", "HTML_DOCS", "blocked", "HTML_BLOCKED", "empty", "EMPTY", "text", "TEXT").entrySet()) {
            infoMode = e.getKey();
            Map<String, String> asked = new LinkedHashMap<>(draft());
            asked.put("source.followRedirects", "off"); // so the redirect itself can be looked at
            var r = workspace("p2" + e.getKey(), env()).probeApi(asked, "/info");
            assertEquals(e.getValue(), r.kind(), e.getKey() + " -> " + r.summary());
            assertFalse(r.hints().isEmpty(), e.getKey());
            assertFalse(Json.write(r).contains(KEY), e.getKey() + ": no key in the report");
        }
        infoMode = "redirect";
        Map<String, String> noFollow = new LinkedHashMap<>(draft());
        noFollow.put("source.followRedirects", "off");
        var redirect = workspace("p3", env()).probeApi(noFollow, "/info");
        assertEquals(302, redirect.status());
        assertTrue(redirect.summary().contains("https://sso.example.com/login") && !redirect.summary().contains("returnUrl"));
    }

    @Test
    void aMissingKeyIsPointedOutInTheRawAnswer() throws Exception {
        var r = workspace("p4", k -> null).probeApi(draft(), "/questions?pagesize=1");
        assertEquals("JSON", r.kind());
        assertEquals(400, r.status());
        assertFalse(r.keySent());
        assertTrue(r.hints().get(0).contains("No API access key was sent"), r.hints().toString());
        assertTrue(r.preview().contains("key_required"), "the site's own error message is shown: " + r.preview());
    }

    @Test
    void theRawAnswerChecksWhatYouTypeAndTakesTheAddressFromTheDocumentation() throws Exception {
        assertFalse(workspace("p5", env()).probeApi(draft(), "info").reached());
        assertFalse(workspace("p6", env()).probeApi(draft(), "http://evil.example/").reached());
        assertFalse(workspace("p7", env()).probeApi(Map.of("source.type", "live"), "/info").reached());
        var viaDocs = workspace("p8", env()).probeApi(Map.of("source.type", "live", "api.swaggerUrl", origin + "/swagger/index.html", "api.keyEnv", "INTERNAL_KEY",
                "source.allowInsecureHttp", "true"), "/info");
        assertTrue(viaDocs.reached(), viaDocs.problem());
        assertEquals("JSON", viaDocs.kind());
    }

    // ------------------------------------------------------------------ the API also wants a Bearer token
    private static Function<String, String> envWithToken() {
        return k -> k.equals("INTERNAL_KEY") ? KEY : k.equals("API_TOKEN") ? "API-TOKEN" : k.equals("WRONG_TOKEN") ? "expired-token-123" : null;
    }

    @Test
    void aMissingBearerTokenIsRecognisedFromTheHeaderAndTheText() throws Exception {
        needBearer = "text";
        var r = workspace("b1", env()).probeApi(draft(), "/info");
        assertEquals(401, r.status());
        assertEquals("TEXT", r.kind());
        assertEquals("bearer", r.authNeeded());
        assertTrue(r.wwwAuthenticate().contains("Bearer"));
        assertEquals("Missing bearer token", r.preview());
        assertTrue(r.hints().get(0).contains("Bearer token") && r.hints().get(0).contains("environment variable"), r.hints().toString());
        assertFalse(r.tokenSent());

        infoMode = "json";
        CoreConfig c = config("");
        SourceException e = assertThrows(SourceException.class, () -> c.createAdapter(env()).ping());
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("Bearer token") && e.getMessage().contains("Missing bearer token"), e.getMessage());
    }

    @Test
    void aJsonRefusalThatNamesTheBearerTokenIsRecognisedToo() throws Exception {
        needBearer = "json";
        var r = workspace("b2", env()).probeApi(draft(), "/info");
        assertEquals("JSON", r.kind());
        assertEquals("bearer", r.authNeeded());
        assertEquals("The API says: Missing bearer token", r.hints().get(0), "the site's own words come first");
        assertTrue(r.hints().stream().anyMatch(h -> h.contains("Bearer token") && h.contains("environment variable")), r.hints().toString());
        CoreConfig c = config("");
        SourceException e = assertThrows(SourceException.class, () -> c.createAdapter(env()).ping());
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("Bearer token"), e.getMessage());
    }

    @Test
    void withTheTokenConfiguredTheCallWorksAndTheCollectionCompletes() throws Exception {
        needBearer = "text";
        Map<String, String> withToken = new LinkedHashMap<>(draft());
        withToken.put("source.auth.method", "token");
        withToken.put("source.auth.tokenEnv", "API_TOKEN");
        var r = workspace("b3", envWithToken()).probeApi(withToken, "/info");
        assertEquals(200, r.status());
        assertEquals("JSON", r.kind());
        assertTrue(r.tokenSent() && r.keySent());
        assertFalse(Json.write(r).contains("API-TOKEN") || Json.write(r).contains(KEY), "neither the token nor the key is ever shown");

        CoreConfig c = config("source.auth.method=token\nsource.auth.tokenEnv=API_TOKEN\n");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(envWithToken()), null, Clock.systemUTC());
        SyncReport report = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, report.outcome(), report.abortReason());
        assertEquals(2, report.added());
        assertTrue(authSeen.stream().allMatch("Bearer API-TOKEN"::equals), "every API call carried the token: " + authSeen);
    }

    @Test
    void aTokenThatIsSentButRefusedIsSaidToBeSent() throws Exception {
        needBearer = "text";
        Map<String, String> wrong = new LinkedHashMap<>(draft());
        wrong.put("source.auth.method", "token");
        wrong.put("source.auth.tokenEnv", "WRONG_TOKEN");
        var r = workspace("b4", envWithToken()).probeApi(wrong, "/info");
        assertEquals(401, r.status());
        assertTrue(r.tokenSent());
        assertTrue(r.hints().get(0).contains("WAS sent but was not accepted"), r.hints().toString());
        assertFalse(Json.write(r).contains("expired-token-123"));
    }

    @Test
    void aBearerSchemeInTheDocumentationIsFlaggedAndTheConnectionCheckNamesWhatIsNeeded() throws Exception {
        String bearerSpec = spec().replace("\"ApiKey\":{\"type\":\"apiKey\",\"in\":\"query\",\"name\":\"key\",\"description\":\"API access key\"}",
                "\"ApiKey\":{\"type\":\"apiKey\",\"in\":\"query\",\"name\":\"key\"},\"Bearer\":{\"type\":\"http\",\"scheme\":\"bearer\"}");
        Workspace w = workspace("b5", env());
        var saved = w.saveApiDocs(bearerSpec);
        assertTrue(saved.ok(), saved.problem());
        assertTrue(w.discoverApi(Map.of("api.swaggerFile", saved.path())).bearerRequired());
        assertFalse(w.saveApiDocs(spec()).path() == null);
        assertFalse(w.discoverApi(Map.of("api.swaggerFile", w.apiDocsFile().toString())).bearerRequired(), "no bearer scheme documented");

        needBearer = "text";
        var check = workspace("b6", env()).checkConnection(Map.of("source.type", "live", "api.swaggerUrl", origin + "/swagger/index.html", "api.keyEnv", "INTERNAL_KEY",
                "source.allowInsecureHttp", "true", "source.timeoutSeconds", "5"));
        var data = check.stages().stream().filter(x -> x.id().equals("data")).findFirst().orElseThrow();
        assertEquals("FAIL", data.status());
        assertTrue(data.needs().stream().anyMatch(n -> n.what().contains("Bearer token")), data.needs().toString());
    }

    // ------------------------------------------------------------------ page sizes and readable validation errors
    @Test
    void theErrorTextIsReadableAndTheAllowedPageSizesAreUnderstood() {
        String problem = "{\"errors\":{\"PageSize\":[\"Invalid page size, use 15, 30, 50 or 100.\"]},\"type\":\"https://tools.ietf.org/html/rfc9110#section-15.5.1\","
                + "\"title\":\"One or more validation errors occured.\",\"status\":400,\"tradeId\":\"00-6aed988600000000cef75b58a0fe95c5\"}";
        assertEquals("PageSize: Invalid page size, use 15, 30, 50 or 100.", com.questioncrawler.core.source.ResponseInspector.errorSummary(problem));
        assertEquals(List.of(15, 30, 50, 100), com.questioncrawler.core.source.ResponseInspector.allowedPageSizes("PageSize: Invalid page size, use 15, 30, 50 or 100."));
        assertEquals(List.of(), com.questioncrawler.core.source.ResponseInspector.allowedPageSizes("Tag 'ai' does not exist."), "only page-size messages count");
        assertEquals("Missing bearer token", com.questioncrawler.core.source.ResponseInspector.errorSummary("{\"message\":\"Missing bearer token\"}"));
        assertEquals("", com.questioncrawler.core.source.ResponseInspector.errorSummary("<html>"));
    }

    @Test
    void thePageSizeIsLearnedFromTheApisOwnErrorAndRemembered() throws Exception {
        allowedSizes = List.of(15, 30);
        CoreConfig c = config("");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertEquals(2, r.added());
        assertEquals(1, rejected.get(), "refused once, then the largest allowed size (30) was used for everything");
        var withSize = requests.stream().filter(x -> x.startsWith("/api/2.3/") && x.contains("pagesize=")).toList();
        assertTrue(withSize.get(0).contains("pagesize=100"));
        assertTrue(withSize.stream().skip(1).allMatch(x -> x.contains("pagesize=30")), withSize.toString());
    }

    @Test
    void theAllowedPageSizesAreTakenFromTheDocumentationWhenItListsThem() throws Exception {
        allowedSizes = List.of(15, 30);
        specPageEnum = true;
        var docs = discovery().discover(origin + "/swagger/index.html");
        assertEquals(List.of(15, 30), docs.capabilities().pageSizes());
        CoreConfig c = config("");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertEquals(0, rejected.get(), "no failing request at all");
        assertTrue(requests.stream().filter(x -> x.startsWith("/api/2.3/") && x.contains("pagesize=")).allMatch(x -> x.contains("pagesize=30")));
    }

    @Test
    void aRejectedPageSizeIsExplainedInWordsAndACorrectedRequestIsOffered() throws Exception {
        allowedSizes = List.of(15, 30, 50, 100);
        var r = workspace("ps1", env()).probeApi(draft(), "/questions?pagesize=1");
        assertEquals(400, r.status());
        assertEquals("JSON", r.kind());
        assertEquals("The API says: PageSize: Invalid page size, use 15, 30, 50 or 100.", r.hints().get(0));
        assertTrue(r.hints().get(1).contains("pagesize=15") && r.hints().get(1).contains("adapt"), r.hints().toString());
        assertEquals("/questions?pagesize=15", r.suggestedPath());
        var fixed = workspace("ps2", env()).probeApi(draft(), r.suggestedPath());
        assertEquals(200, fixed.status());
        assertEquals(null, fixed.suggestedPath());
    }

    @Test
    void aValidationErrorThatCannotBeAdaptedToIsShownWithTheSitesOwnWords() throws Exception {
        rejectTagged = true;
        CoreConfig c = config("");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.FAILED, r.outcome());
        assertTrue(r.abortReason().contains("The API rejected the request (HTTP 400): Tagged: Tag 'ai' does not exist."), r.abortReason());
    }

    // ------------------------------------------------------------------ redirects
    private int gatewayRequests() {
        return (int) requests.stream().filter(x -> x.startsWith("/gw/api/2.3/")).count();
    }

    @Test
    void theRedirectRulesAreSensible() throws Exception {
        java.net.URI a = java.net.URI.create("https://so.corp.com/api/2.3/info?key=SECRET");
        var all = com.questioncrawler.core.source.Redirects.Mode.ALL;
        var none = new java.util.HashSet<String>();
        var step = com.questioncrawler.core.source.Redirects.next(a, "/gw/api/2.3/info", a, all, none, 0);
        assertTrue(step.follow());
        assertEquals("https://so.corp.com/gw/api/2.3/info?key=SECRET", step.target().toString(), "the query string (the key) is carried over on the same host");
        assertEquals("https://other.corp.com/x", com.questioncrawler.core.source.Redirects.next(a, "https://other.corp.com/x", a, all, none, 0).target().toString(),
                "no query is added when the host changes");
        assertTrue(com.questioncrawler.core.source.Redirects.next(a, "http://so.corp.com/x", a, all, none, 0).stopReason().contains("never followed"), "https down to http");
        assertTrue(com.questioncrawler.core.source.Redirects.next(a, "/x", a, com.questioncrawler.core.source.Redirects.Mode.OFF, none, 0).stopReason().contains("switched off"));
        assertTrue(com.questioncrawler.core.source.Redirects.next(a, "https://other.corp.com/x", a, com.questioncrawler.core.source.Redirects.Mode.SAME_HOST, none, 0).stopReason().contains("same host only"));
        assertTrue(com.questioncrawler.core.source.Redirects.next(a, "/x", a, all, none, 5).stopReason().contains("more than 5"));
        none.add("https://so.corp.com/loop?key=SECRET");
        assertTrue(com.questioncrawler.core.source.Redirects.next(java.net.URI.create("https://so.corp.com/loop?key=SECRET"), "/loop", a, all, none, 1).stopReason().contains("circle"));
        assertTrue(com.questioncrawler.core.source.Redirects.next(a, "ftp://x/y", a, all, new java.util.HashSet<>(), 0).stopReason().contains("not a web address"));
        assertEquals("https://so.corp.com/api/2.3/info", com.questioncrawler.core.source.Redirects.show(a), "shown without the query string");
        assertEquals(com.questioncrawler.core.source.Redirects.Mode.ALL, com.questioncrawler.core.source.Redirects.Mode.parse(null));
        assertEquals(com.questioncrawler.core.source.Redirects.Mode.OFF, com.questioncrawler.core.source.Redirects.Mode.parse("No"));
    }

    @Test
    void aRedirectToTheGatewayIsFollowedAndTheNewAddressIsUsedDirectlyAfterwards() throws Exception {
        redirectMode = "same-host";
        CoreConfig c = config("");
        var adapter = c.createAdapter(env());
        String ping = adapter.ping();
        assertTrue(ping.contains("API reachable; the site redirects") && ping.contains("/gw/api/2.3") && ping.contains("used directly"), ping);
        requests.clear();
        KnowledgeBase kb = new KnowledgeBase(c, adapter, null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertEquals(2, r.added());
        assertTrue(gatewayRequests() > 3);
        assertEquals(0, requests.stream().filter(x -> x.startsWith("/api/2.3/")).count(), "no request went to the old address again: " + requests);
    }

    @Test
    void aRedirectThatDropsTheQueryStringKeepsTheKeyOnTheSameHost() throws Exception {
        redirectMode = "no-query";
        CoreConfig c = config("");
        assertTrue(c.createAdapter(env()).ping().contains("has 3 questions"));
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/gw/api/2.3/info?") && x.contains("key=" + KEY)), "the key was carried over: " + requests);
    }

    @Test
    void aRedirectToAnotherHostFollowsTheSettingsChoice() throws Exception {
        redirectMode = "cross-host";
        assertTrue(config("").createAdapter(env()).ping().contains("API reachable"), "followed by default");

        SourceException sameHost = assertThrows(SourceException.class, () -> config("source.followRedirects=same-host\n").createAdapter(env()).ping());
        assertTrue(sameHost.getMessage().contains("another host") && sameHost.getMessage().contains("same host only"), sameHost.getMessage());

        SourceException off = assertThrows(SourceException.class, () -> config("source.followRedirects=off\n").createAdapter(env()).ping());
        assertTrue(off.getMessage().contains("switched off") && off.getMessage().contains("not followed"), off.getMessage());
    }

    @Test
    void redirectsInACircleAreStoppedAndExplained() throws Exception {
        redirectMode = "loop";
        SourceException e = assertThrows(SourceException.class, () -> config("").createAdapter(env()).ping());
        assertTrue(e.getMessage().contains("circle"), e.getMessage());
    }

    @Test
    void aRedirectToASignInPageShowsTheChainAndSaysSignInIsNeeded() throws Exception {
        redirectMode = "login";
        SourceException e = assertThrows(SourceException.class, () -> config("").createAdapter(env()).ping());
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("sign-in page") && e.getMessage().contains("after redirects: 307 to http://127.0.0.1:") && e.getMessage().contains("/sso/login"), e.getMessage());
        assertFalse(e.getMessage().contains("returnUrl") || e.getMessage().contains(KEY));
    }

    @Test
    void theRawAnswerShowsTheRedirectsThatWereFollowed() throws Exception {
        redirectMode = "same-host";
        var r = workspace("rd1", env()).probeApi(draft(), "/info");
        assertEquals(200, r.status());
        assertEquals("JSON", r.kind());
        assertEquals(1, r.redirects().size());
        assertTrue(r.redirects().get(0).startsWith("307 to http://127.0.0.1:") && r.redirects().get(0).endsWith("/gw/api/2.3/info"), r.redirects().toString());
        assertTrue(r.hints().get(0).contains("redirected 1 time"));
        assertFalse(Json.write(r).contains(KEY));
    }

    @Test
    void theSignInStepFollowsRedirectsAndReportsWhereTheyEnd() throws Exception {
        Map<String, String> base = new LinkedHashMap<>(Map.of("source.type", "live", "source.baseUrl", origin + "/start", "source.allowInsecureHttp", "true", "source.timeoutSeconds", "5"));
        redirectMode = "same-host";
        var ok = workspace("rd2", env()).checkConnection(base).stages().stream().filter(x -> x.id().equals("access")).findFirst().orElseThrow();
        assertEquals("PASS", ok.status(), ok.summary());
        assertTrue(ok.summary().contains("after 1 redirect"), ok.summary());
        assertTrue(ok.facts().stream().anyMatch(f -> f.label().equals("Redirected") && f.value().contains("307 to http://127.0.0.1:") && f.value().endsWith("/landing")), ok.facts().toString());

        redirectMode = "login";
        var login = workspace("rd3", env()).checkConnection(base).stages().stream().filter(x -> x.id().equals("access")).findFirst().orElseThrow();
        assertEquals("WARN", login.status());
        assertTrue(login.summary().contains("sign-in page") && login.summary().contains("1 redirect"), login.summary());

        redirectMode = "same-host";
        Map<String, String> off = new LinkedHashMap<>(base);
        off.put("source.followRedirects", "off");
        var notFollowed = workspace("rd4", env()).checkConnection(off).stages().stream().filter(x -> x.id().equals("access")).findFirst().orElseThrow();
        assertEquals("INFO", notFollowed.status());
        assertTrue(notFollowed.summary().contains("not followed") && notFollowed.summary().contains("switched off"), notFollowed.summary());
    }

    @Test
    void theRedirectSettingIsExplainedAndSaved() throws Exception {
        var service = new com.questioncrawler.core.service.ConfigService(k -> null);
        var field = com.questioncrawler.core.service.ConfigService.sections().stream().flatMap(sec -> sec.fields().stream())
                .filter(f -> f.key().equals("source.followRedirects")).findFirst().orElseThrow();
        assertFalse(field.advanced(), "visible, not hidden");
        assertEquals(3, field.options().size());
        Path file = tmp.resolve("rd.properties");
        var saved = service.save(file, Map.of("source.type", "live", "api.swaggerUrl", "https://so.example.com/swagger/index.html", "source.followRedirects", "same-host",
                "storage.root", tmp.resolve("rdd").toString().replace('\\', '/')));
        assertTrue(saved.saved(), saved.issues().toString());
        assertEquals("same-host", CoreConfig.load(file, Map.of()).source().api().redirects());
    }

    @Test
    void aRedirectToAnAddressThatCannotBeReachedSaysWhichRedirectLedThere() throws Exception {
        infoMode = "redirect"; // leads to https://sso.example.com/login, which does not exist here
        SourceException e = assertThrows(SourceException.class, () -> config("").createAdapter(env()).ping());
        assertTrue(e.getMessage().contains("redirected the request (302 to https://sso.example.com/login)") && e.getMessage().contains("could not be used"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY));
    }

    // ------------------------------------------------------------------ which sign-in system, and what to ask for
    @Test
    void theSignInSystemIsRecognisedFromWhereTheRedirectLeads() {
        var id = (java.util.function.Function<String, String>) u -> com.questioncrawler.core.source.SignInSystem.identify(u).map(x -> x.name()).orElse("?");
        assertEquals("Microsoft Entra ID (Azure AD)", id.apply("https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize?client_id=x"));
        assertEquals("Okta", id.apply("https://acme.okta.com/oauth2/v1/authorize?x=1"));
        assertEquals("Keycloak", id.apply("https://sso.acme.com/auth/realms/acme/protocol/openid-connect/auth"));
        assertEquals("Active Directory Federation Services (AD FS)", id.apply("https://fs.acme.com/adfs/ls/?wa=wsignin1.0"));
        assertEquals("SAML single sign-on", id.apply("https://idp.acme.com/sso?SAMLRequest=abc"));
        assertEquals("The site's own sign-in page", id.apply("https://so.acme.com/users/login?returnurl=%2f"));
        assertEquals("?", id.apply("https://so.acme.com/api/2.3/questions"));
        var entra = com.questioncrawler.core.source.SignInSystem.identify("https://login.microsoftonline.com/t/oauth2/v2.0/authorize").orElseThrow();
        assertTrue(entra.forPrograms().contains("client-credentials") && entra.ask().get(0).contains("client ID"));
        assertTrue(entra.sentence().contains("cannot complete a browser sign-in"));
    }

    @Test
    void theRawAnswerNamesTheSignInSystemAndSaysWhatToAskFor() throws Exception {
        redirectMode = "login-keycloak";
        var r = workspace("si1", env()).probeApi(draft(), "/info");
        assertEquals("HTML_LOGIN", r.kind());
        assertEquals("Keycloak", r.signInSystem());
        assertEquals(2, r.redirects().size() + 1, "one redirect to the identity provider");
        assertTrue(r.hints().get(0).contains("looks like Keycloak") && r.hints().get(0).contains("cannot complete a browser sign-in"), r.hints().toString());
        assertTrue(r.hints().get(1).startsWith("Ask your IT identity team or the site owners for:") && r.hints().get(1).contains("service account"), r.hints().toString());
        assertFalse(Json.write(r).contains(KEY));
    }

    @Test
    void anApiCallThatEndsAtASignInPageNamesTheSystemInTheError() throws Exception {
        redirectMode = "login-keycloak";
        SourceException e = assertThrows(SourceException.class, () -> config("").createAdapter(env()).ping());
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("Keycloak") && e.getMessage().contains("cannot complete a browser sign-in"), e.getMessage());
    }

    @Test
    void theSignInStepNamesTheSystemAndTheExactAsk() throws Exception {
        redirectMode = "login-keycloak";
        var access = workspace("si2", env()).checkConnection(Map.of("source.type", "live", "source.baseUrl", origin + "/start", "source.allowInsecureHttp", "true", "source.timeoutSeconds", "5"))
                .stages().stream().filter(x -> x.id().equals("access")).findFirst().orElseThrow();
        assertEquals("WARN", access.status());
        assertTrue(access.facts().stream().anyMatch(f -> f.label().equals("Sign-in system") && f.value().equals("Keycloak")), access.facts().toString());
        assertTrue(access.needs().stream().anyMatch(n -> n.what().contains("A way for a program to sign in to Keycloak") && n.who().contains("IT identity team")), access.needs().toString());
        assertTrue(access.needs().stream().anyMatch(n -> n.what().contains("address of the API that accepts a token")));
        assertTrue(access.needs().stream().anyMatch(n -> n.what().contains("What to ask for") && n.why().contains("ID and secret")), access.needs().toString());
    }

    @Test
    void aWebsiteBehindSsoDoesNotRaiseAFalseAlarmWhenTheRestApiAcceptsOurKey() throws Exception {
        redirectMode = "login-keycloak";
        redirectRootOnly = true; // the bare address goes to SSO, the API endpoints answer
        var report = workspace("si3", env()).checkConnection(Map.of("source.type", "live", "api.swaggerUrl", origin + "/swagger/index.html", "source.baseUrl", origin + "/api/2.3",
                "api.keyEnv", "INTERNAL_KEY", "source.allowInsecureHttp", "true", "source.timeoutSeconds", "5"));
        var data = report.stages().stream().filter(x -> x.id().equals("data")).findFirst().orElseThrow();
        var access = report.stages().stream().filter(x -> x.id().equals("access")).findFirst().orElseThrow();
        assertEquals("PASS", data.status(), data.summary());
        assertEquals("PASS", access.status(), access.summary());
        assertTrue(access.summary().contains("REST API accepts our access key"), access.summary());
        assertTrue(access.facts().stream().anyMatch(f -> f.label().equals("Sign-in system") && f.value().equals("Keycloak")), "what the website uses is still shown");
        assertEquals("REST_API", report.route().kind());
    }

    // ------------------------------------------------------------------ finding the tag that really exists
    private com.questioncrawler.core.source.StackExchangeAdapter adapter() throws Exception {
        return (com.questioncrawler.core.source.StackExchangeAdapter) config("").createAdapter(env());
    }

    @Test
    void aTagTheSiteSpellsInCapitalsIsFoundWithItsExactNameAndCount() throws Exception {
        var check = adapter().checkTag("casc");
        assertTrue(check.exists());
        assertEquals("CASC", check.exactName());
        assertEquals(7, check.count());
        assertEquals("tag info", check.how());
    }

    @Test
    void theTagIsStillFoundWhenTheTagInfoEndpointDoesNotHelp() throws Exception {
        tagInfoBroken = true;
        var viaList = adapter().checkTag("casc");
        assertTrue(viaList.exists());
        assertEquals("CASC", viaList.exactName());
        assertEquals("tag list", viaList.how());

        tagsBroken = true; // neither tag endpoint works: a real question search still tells
        var viaSearch = adapter().checkTag("casc");
        assertTrue(viaSearch.exists(), "found by asking for questions with that tag, like a collection does");
        assertEquals("CASC", viaSearch.exactName());
        assertEquals("question search", viaSearch.how());
    }

    @Test
    void aTagThatIsNotThereOffersSimilarOnes() throws Exception {
        var check = adapter().checkTag("cas");
        assertFalse(check.exists());
        assertEquals(List.of("CASC", "casc-prod"), check.similar().stream().map(h -> h.name()).sorted().toList());
        assertTrue(check.similar().stream().anyMatch(h -> h.name().equals("CASC") && h.count() == 7));
    }

    @Test
    void collectingUsesTheSitesOwnSpellingWhenTheTypedTagFindsNothing() throws Exception {
        CoreConfig c = config("sync.tags=casc\n");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertEquals(1, r.added(), "question 104 carries the tag CASC");
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/api/2.3/questions?") && x.contains("tagged=casc")), "first asked as typed");
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/api/2.3/questions?") && x.contains("tagged=CASC")), "then with the site's spelling: " + requests);
        assertTrue(kb.store().loadThread("internal-qa", "104").isPresent());
    }

    @Test
    void theTagFinderOnTheConnectScreenReturnsTheSitesOwnNames() throws Exception {
        var found = workspace("t1", env()).searchTags(draft(), "casc");
        assertTrue(found.ok(), found.problem());
        assertEquals("CASC", found.exact());
        assertTrue(found.spellingDiffers());
        assertEquals("CASC", found.tags().get(0).name(), "the exact match first");
        assertEquals(7, found.tags().get(0).count());
        assertTrue(found.tags().stream().anyMatch(t -> t.name().equals("casc-prod")));
        var browse = workspace("t2", env()).searchTags(draft(), "  ");
        assertTrue(browse.ok() && browse.allTags(), "an empty search lists all tags");
        assertFalse(workspace("t3", env()).searchTags(Map.of("source.type", "fixture"), "casc").ok());
        var none = workspace("t4", env()).searchTags(draft(), "zzzz");
        assertTrue(none.ok() && none.tags().isEmpty() && none.problem().contains("No tag on the site looks like"), none.toString());
    }

    @Test
    void theTopicStepShowsTheExactNameOrSimilarTags() throws Exception {
        Map<String, String> found = new LinkedHashMap<>(draft());
        found.put("sync.tags", "casc");
        found.put("api.swaggerUrl", origin + "/swagger/index.html");
        var ok = workspace("t5", env()).checkConnection(found).stages().stream().filter(x -> x.id().equals("scope")).findFirst().orElseThrow();
        assertEquals("PASS", ok.status(), ok.summary());
        assertTrue(ok.summary().contains("the site writes it 'CASC'"), ok.summary());
        assertTrue(ok.facts().stream().anyMatch(f -> f.label().equals("Exact name on the site") && f.value().equals("CASC")));

        found.put("sync.tags", "cas");
        var missing = workspace("t6", env()).checkConnection(found).stages().stream().filter(x -> x.id().equals("scope")).findFirst().orElseThrow();
        assertEquals("WARN", missing.status());
        assertTrue(missing.facts().stream().anyMatch(f -> f.label().equals("Similar tags on the site") && f.value().contains("CASC (7)")), missing.facts().toString());
        assertTrue(missing.needs().get(0).why().contains("Find tags on the site"));
    }

    // ------------------------------------------------------------------ the tag "all": every question on the site
    @Test
    void theTagAllCollectsEveryQuestionWithoutAnyTagFilter() throws Exception {
        CoreConfig c = config("sync.tags=all\n");
        KnowledgeBase kb = new KnowledgeBase(c, c.createAdapter(env()), null, Clock.systemUTC());
        SyncReport r = kb.sync(options(c));
        assertEquals(SyncReport.COMPLETE, r.outcome(), r.abortReason());
        assertEquals(4, r.added(), "the ai, gpu, finance and CASC questions: tags do not matter");
        assertTrue(requests.stream().filter(x -> x.startsWith("/api/2.3/questions?")).noneMatch(x -> x.contains("tagged=")), "no tag filter was sent: " + requests);
        assertTrue(kb.store().loadThread("internal-qa", "104").isPresent());
        assertTrue(kb.store().loadThread("internal-qa", "103").isPresent());
        assertTrue(r.warnings().stream().noneMatch(w -> w.startsWith("No questions were returned")));
    }

    @Test
    void theTagFinderListsEveryTagMostUsedFirstAndTheSiteSize() throws Exception {
        for (String typed : new String[] {"", "all", "*", "  "}) {
            var all = workspace("ta" + typed.length() + typed.hashCode(), env()).searchTags(draft(), typed);
            assertTrue(all.ok() && all.allTags(), typed);
            assertEquals(List.of("CASC", "casc-prod", "ai"), all.tags().stream().limit(3).map(t -> t.name()).toList(), "most used first");
            assertEquals(7, all.tags().get(0).count());
            assertEquals(6, all.tags().size());
            assertEquals(3, all.siteQuestions(), "the number of questions the site reports");
        }
    }

    @Test
    void theConnectionCheckExplainsWhatAllTagsMeans() throws Exception {
        Map<String, String> v = new LinkedHashMap<>(draft());
        v.put("sync.tags", "all");
        v.put("api.swaggerUrl", origin + "/swagger/index.html");
        var scope = workspace("ta9", env()).checkConnection(v).stages().stream().filter(x -> x.id().equals("scope")).findFirst().orElseThrow();
        assertEquals("PASS", scope.status(), scope.summary());
        assertTrue(scope.summary().startsWith("Every question on the site will be collected (all tags)"), scope.summary());
        assertTrue(scope.facts().stream().anyMatch(f -> f.label().equals("Questions on the site") && f.value().equals("3")), scope.facts().toString());
    }

    @Test
    void aFailingSiteSizeLookupDoesNotHideTheTagList() throws Exception {
        needBearer = "off";
        infoMode = "html-login"; // /info answers a sign-in page: the size is unknown, the tag list is still fine
        var all = workspace("tz", env()).searchTags(draft(), "all");
        assertTrue(all.ok() && all.allTags(), all.problem());
        assertEquals(6, all.tags().size());
        assertEquals(-1, all.siteQuestions());
    }

    @Test
    void aRealProblemIsShownInsteadOfAnEmptyTagList() throws Exception {
        // plain http without allowing it: the request is refused, and the tag finder must say so
        var refused = workspace("tr", env()).searchTags(Map.of("source.type", "live", "source.baseUrl", origin + "/api/2.3", "api.keyEnv", "INTERNAL_KEY"), "");
        assertFalse(refused.ok(), "not an empty list: " + refused);
        assertTrue(refused.problem().toLowerCase().contains("https") || refused.problem().toLowerCase().contains("unencrypted") || refused.problem().toLowerCase().contains("not allowed"), refused.problem());
        var searched = workspace("tr2", env()).searchTags(Map.of("source.type", "live", "source.baseUrl", origin + "/api/2.3", "api.keyEnv", "INTERNAL_KEY"), "casc");
        assertFalse(searched.ok(), searched.toString());
    }

    // ------------------------------------------------------------------ every tag, not just the first hundred
    private long tagRequests() {
        return requests.stream().filter(x -> x.startsWith("/api/2.3/tags?") && !x.contains("inname")).count();
    }

    @Test
    void everyTagOfALargeSiteIsListedPageByPage() throws Exception {
        manyTags = true;
        var all = workspace("tl1", env()).searchTags(draft(), "");
        assertTrue(all.ok() && all.allTags(), all.problem());
        assertEquals(250, all.tags().size(), "all 250 tags, not 100");
        assertFalse(all.truncated());
        assertEquals("CASC", all.tags().get(0).name(), "most used first");
        assertTrue(all.tags().stream().anyMatch(t -> t.name().equals("tag-000244")), "even the last page");
        assertEquals(250, all.tags().stream().map(t -> t.name()).distinct().count(), "no tag twice");
        assertEquals(3, tagRequests(), "100 + 100 + 50");
    }

    @Test
    void theSitesOwnPageSizeRuleIsRespectedWhileReadingAllTags() throws Exception {
        manyTags = true;
        allowedSizes = List.of(15, 30);
        var all = workspace("tl2", env()).searchTags(draft(), "all");
        assertTrue(all.ok(), all.problem());
        assertEquals(250, all.tags().size());
        assertTrue(requests.stream().anyMatch(x -> x.startsWith("/api/2.3/tags?") && x.contains("pagesize=30")), "learned page size 30");
    }

    @Test
    void thePageLimitAndATimeBudgetStopEarlyAndSaySo() throws Exception {
        manyTags = true;
        var adapter = adapter();
        var two = adapter.listAllTags(2, 60_000);
        assertEquals(200, two.tags().size());
        assertFalse(two.complete(), "the site has more");
        var none = adapter.listAllTags(500, -1);
        assertTrue(none.tags().isEmpty() && !none.complete(), "no time left: nothing was asked");
    }

    @Test
    void aPageThatFailsLaterKeepsWhatWasReadButTheFirstPageFailingIsAnError() throws Exception {
        manyTags = true;
        tagsFailFromPage = 3;
        var partial = workspace("tl3", env()).searchTags(draft(), "");
        assertTrue(partial.ok(), partial.problem());
        assertEquals(200, partial.tags().size());
        assertTrue(partial.truncated(), "marked incomplete so the screen can say so");

        tagsFailFromPage = 0;
        tagsBroken = true;
        var broken = workspace("tl4", env()).searchTags(draft(), "");
        assertFalse(broken.ok(), "nothing could be read: a problem, not an empty list");
    }

    // ------------------------------------------------------------------ no fixed limit, a lying has_more, a site that ignores paging
    @Test
    void thereIsNoFixedLimitOnTheNumberOfTagsOrPages() throws Exception {
        extraTags = 50_700; // 50,706 tags: more than the old limit of 500 pages x 100
        var all = adapter().readAllTags((page, no) -> true, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(all.complete(), all.stopReason());
        assertEquals("the site has no more tags", all.stopReason());
        assertEquals(508, all.pagesRead(), "50,706 tags at 100 per page");
        assertEquals(100, all.pageSize());
        assertEquals(508, tagRequests());
        var counted = new java.util.concurrent.atomic.AtomicInteger();
        adapter().readAllTags((page, no) -> counted.addAndGet(page.size()) >= 0, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(50_706, counted.get());
    }

    @Test
    void aSiteWhoseHasMoreFlagIsWrongIsStillReadToTheEnd() throws Exception {
        manyTags = true;
        tagsLyingHasMore = true;
        var listing = adapter().listAllTags(Integer.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(250, listing.tags().size(), "all of them, although has_more said no after the first page");
        assertTrue(listing.complete());
        var r = adapter().readAllTags((p, n) -> true, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(r.hasMoreUnreliable(), "the screen can mention that the site's has_more cannot be trusted");
    }

    @Test
    void aSiteThatIgnoresThePageNumberIsReportedInsteadOfLoopingOrSilentlyStoppingAtOnePage() throws Exception {
        manyTags = true;
        tagsIgnorePaging = true;
        var tags = new ArrayList<String>();
        var r = adapter().readAllTags((page, no) -> tags.addAll(page.stream().map(t -> t.name()).toList()), Integer.MAX_VALUE, Long.MAX_VALUE);
        assertFalse(r.complete());
        assertTrue(r.stopReason().contains("ignore the page number"), r.stopReason());
        assertEquals(100, tags.size(), "exactly the first page, and the app says why that is all there is");
        assertEquals(2, r.pagesRead());
    }

    // ------------------------------------------------------------------ the background listing
    private static com.questioncrawler.core.service.TagListingJob.Chunk waitUntilFinished(Workspace w) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        com.questioncrawler.core.service.TagListingJob.Chunk c;
        do {
            Thread.sleep(25);
            c = w.tagListing(0);
        } while (!c.state().finished() && System.currentTimeMillis() < end);
        return c;
    }

    @Test
    void theBackgroundListingReportsProgressAndDeliversEveryTagInChunks() throws Exception {
        extraTags = 1_200;
        tagDelayMs = 15;
        Workspace w = workspace("job1", env());
        var started = w.startTagListing(draft());
        assertTrue(started.running() && !started.finished());
        // read it the way the screen does: chunk by chunk
        int from = 0;
        var received = new ArrayList<String>();
        var progress = new ArrayList<Integer>();
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            var chunk = w.tagListing(from);
            chunk.tags().forEach(t -> received.add(t.name()));
            from += chunk.tags().size();
            progress.add(chunk.state().loaded());
            if (chunk.state().finished()) break;
            Thread.sleep(20);
        }
        var done = w.tagListing(0).state();
        assertTrue(done.finished() && !done.running() && done.complete(), done.toString());
        assertEquals(1_206, done.loaded());
        assertEquals(1_206, received.size());
        assertEquals(1_206, received.stream().distinct().count(), "each tag delivered once");
        assertEquals(13, done.pages(), "1,206 tags at 100 per page");
        assertEquals(3, done.siteQuestions());
        assertEquals("the site has no more tags", done.stopReason());
        assertTrue(progress.stream().distinct().count() > 2, "progress was visible while it ran: " + progress);
        assertEquals(1_206, w.tagListing(0).tags().size(), "the whole list is available at the end");
        assertEquals(6, w.tagListing(1_200).tags().size(), "only what is new since position 1200");
    }

    @Test
    void theListingCanBeStoppedAndKeepsWhatWasRead() throws Exception {
        extraTags = 3_000;
        allowedSizes = List.of(15); // 15 per page: 200+ pages
        tagDelayMs = 8;
        Workspace w = workspace("job2", env());
        w.startTagListing(draft());
        long end = System.currentTimeMillis() + 30_000;
        while (w.tagListing(0).state().loaded() < 100 && System.currentTimeMillis() < end) Thread.sleep(10);
        w.cancelTagListing();
        var c = waitUntilFinished(w);
        assertTrue(c.state().finished() && !c.state().complete(), c.state().toString());
        assertEquals("stopped", c.state().stopReason());
        assertTrue(c.state().loaded() >= 100 && c.state().loaded() < 3_006, "stopped part-way: " + c.state().loaded());
        assertEquals(c.state().loaded(), c.tags().size(), "what was read stays available");
    }

    @Test
    void aListingThatCannotStartReportsTheProblem() throws Exception {
        Workspace w = workspace("job3", env());
        w.startTagListing(Map.of("source.type", "fixture"));
        var c = waitUntilFinished(w);
        assertTrue(c.state().finished() && c.state().problem().contains("real site"), c.state().toString());
        Workspace blank = workspace("job4", env());
        assertEquals(null, blank.tagListing(0).state().id(), "nothing started yet");
    }

    @Test
    void anExactMultipleOfThePageSizeDoesNotMakeAnHonestSiteLookUnreliable() throws Exception {
        extraTags = 94; // 6 + 94 = exactly 100: the first page is the last and is full
        var r = adapter().readAllTags((p, n) -> true, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(r.complete(), r.stopReason());
        assertFalse(r.hasMoreUnreliable(), "has_more=false on the last page was right");
        assertEquals(2, r.pagesRead(), "one extra request to be sure, which came back empty");
    }
}
