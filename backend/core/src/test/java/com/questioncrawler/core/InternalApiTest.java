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
    volatile String docsAuth = "none"; // none | bearer | key: what the documentation itself demands

    // ------------------------------------------------------------------ the fake internal site
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        // ---- the API: every request needs the key (query or X-Api-Key header)
        boolean keyed = KEY.equals(q.get("key")) || KEY.equals(ex.getRequestHeaders().getFirst("X-Api-Key"));
        if (!keyed) {
            sendJson(ex, 400, Map.of("error_id", 405, "error_name", "key_required", "error_message", "An API access key is required"), true);
            return;
        }
        String api = path.substring("/api/2.3".length());
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
            default -> {
                if (api.startsWith("/tags/") && api.endsWith("/info")) {
                    sendJson(ex, 200, wrap(List.of(Map.of("name", api.split("/")[2], "count", 3)), false), true);
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
                       {"name":"filter","in":"query","schema":{"type":"string"}},{"$ref":"#/components/parameters/page"},{"name":"pagesize","in":"query","schema":{"type":"integer"}}],
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
                 }}""".formatted(taggedParam);
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
        SourceException e = pingFailure("redirect");
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
            var r = workspace("p2" + e.getKey(), env()).probeApi(draft(), "/info");
            assertEquals(e.getValue(), r.kind(), e.getKey() + " -> " + r.summary());
            assertFalse(r.hints().isEmpty(), e.getKey());
            assertFalse(Json.write(r).contains(KEY), e.getKey() + ": no key in the report");
        }
        infoMode = "redirect";
        var redirect = workspace("p3", env()).probeApi(draft(), "/info");
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
}
