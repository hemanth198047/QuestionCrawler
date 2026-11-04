package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Stack Exchange adapter against a local mock that imitates the documented API (gzip JSON,
 * has_more / backoff / quota fields, errors as HTTP 400 + error_id). No network is used. This verifies our
 * handling of the documented behaviour; it does NOT prove the live service behaves identically.
 */
class StackExchangeAdapterTest {

    record Req(String path, Map<String, String> params) {}

    record Resp(int status, Object body) {
        static Resp ok(Object body) { return new Resp(200, body); }
    }

    @TempDir
    Path tmp;

    HttpServer server;
    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Function<Req, Resp> handler = r -> Resp.ok(Map.of("items", List.of(), "has_more", false));
    final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2.3", ex -> {
            Map<String, String> params = new LinkedHashMap<>();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) {
                for (String pair : raw.split("&")) {
                    int eq = pair.indexOf('=');
                    params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            Req req = new Req(ex.getRequestURI().getPath(), params);
            requests.add(req);
            Resp resp = handler.apply(req);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(Json.MAPPER.writeValueAsBytes(resp.body()));
            }
            byte[] bytes = bos.toByteArray();
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(resp.status(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/2.3";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private StackExchangeAdapter adapter(Map<String, String> env) {
        var settings = new SourceSettings("stackoverflow", base, List.of("127.0.0.1"), List.of("/2.3/"), "none", null, null, null, null,
                Duration.ofSeconds(5), true, "stackoverflow", "SE_KEY");
        return new StackExchangeAdapter(settings, new HttpSourceClient(settings), env::get, d -> sleeps.add(d));
    }

    private StackExchangeAdapter adapter() {
        return adapter(Map.of());
    }

    // ---------------------------------------------------------------- payload builders (documented field names)

    static Map<String, Object> question(long id, String title, String body, List<String> tags, long created, long activity, int answers) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("question_id", id);
        q.put("title", title);
        q.put("body", body);
        q.put("tags", tags);
        q.put("link", "https://stackoverflow.com/questions/" + id + "/slug");
        q.put("score", 7);
        q.put("creation_date", created);
        q.put("last_activity_date", activity);
        q.put("answer_count", answers);
        q.put("owner", Map.of("display_name", "alice"));
        return q;
    }

    static Map<String, Object> answer(long id, long qid, String body, boolean accepted, long created) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("answer_id", id);
        a.put("question_id", qid);
        a.put("body", body);
        a.put("is_accepted", accepted);
        a.put("score", 3);
        a.put("creation_date", created);
        a.put("last_activity_date", created + 60);
        a.put("owner", Map.of("display_name", "bob"));
        return a;
    }

    static Map<String, Object> page(List<?> items, boolean hasMore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("has_more", hasMore);
        m.put("quota_max", 300);
        m.put("quota_remaining", 250);
        return m;
    }

    static final long T0 = Instant.parse("2026-10-01T00:00:00Z").getEpochSecond();

    // ---------------------------------------------------------------- tests

    @Test
    void listingUsesTheDocumentedParametersAndNormalizesTheTag() throws Exception {
        handler = r -> Resp.ok(page(List.of(question(101, "Q", "<p>b</p>", List.of("artificial-intelligence"), T0, T0 + 10, 0)), false));
        var filter = new TagFilter(List.of("Artificial Intelligence"), TagMode.ANY, false, "2026-10-01T00:00:00Z");
        var adapter = adapter();
        Page<QuestionSummary> result = adapter.listQuestions(filter, "2026-10-20T00:00:00Z", null);

        Req r = requests.get(0);
        assertEquals("/2.3/questions", r.path());
        assertEquals("stackoverflow", r.params().get("site"));
        assertEquals("artificial-intelligence", r.params().get("tagged"));
        assertEquals("activity", r.params().get("sort"));
        assertEquals("asc", r.params().get("order"));
        assertEquals("withbody", r.params().get("filter"));
        assertEquals("100", r.params().get("pagesize"));
        assertEquals("1", r.params().get("page"));
        assertEquals(String.valueOf(Instant.parse("2026-10-01T00:00:00Z").getEpochSecond()), r.params().get("fromdate"));
        assertEquals(String.valueOf(Instant.parse("2026-10-20T00:00:00Z").getEpochSecond()), r.params().get("min"));
        assertEquals(1, result.items().size());
        assertEquals("101", result.items().get(0).id());
        assertEquals(Instant.ofEpochSecond(T0 + 10).toString(), result.items().get(0).modifiedAt(), "modified = last activity date");
        assertNull(result.nextCursor());
        assertEquals(250, adapter.quotaRemaining(), "quota reported by the API is tracked");
    }

    @Test
    void paginationAndTagCombination() throws Exception {
        handler = r -> Resp.ok(page(List.of(), r.params().get("page").equals("1") && r.params().get("tagged").equals("a")));
        var any = new TagFilter(List.of("a", "b"), TagMode.ANY, false);
        var adapter = adapter();
        Page<QuestionSummary> p1 = adapter.listQuestions(any, null, null);
        assertEquals("0:2", p1.nextCursor());
        Page<QuestionSummary> p2 = adapter.listQuestions(any, null, p1.nextCursor());
        assertEquals("1:1", p2.nextCursor(), "ANY runs one pass per tag");
        Page<QuestionSummary> p3 = adapter.listQuestions(any, null, p2.nextCursor());
        assertNull(p3.nextCursor());
        assertEquals(List.of("a", "a", "b"), requests.stream().map(r -> r.params().get("tagged")).toList());

        requests.clear();
        adapter.listQuestions(new TagFilter(List.of("a", "b"), TagMode.ALL, false), null, null);
        assertEquals("a;b", requests.get(0).params().get("tagged"), "ALL uses the API's AND list");
    }

    @Test
    void unsupportedScopesAreRefusedClearly() {
        var adapter = adapter();
        SourceException partial = assertThrows(SourceException.class, () -> adapter.listQuestions(new TagFilter(List.of("a"), TagMode.ANY, true), null, null));
        assertTrue(partial.getMessage().contains("Partial tag matching"));
        SourceException many = assertThrows(SourceException.class, () -> adapter.listQuestions(
                new TagFilter(List.of("a", "b", "c", "d", "e", "f"), TagMode.ALL, false), null, null));
        assertTrue(many.getMessage().contains("at most 5"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void answersAreBatchedPerListingPageAndQuestionsComeFromTheListing() throws Exception {
        handler = r -> {
            if (r.path().equals("/2.3/questions")) {
                return Resp.ok(page(List.of(
                        question(101, "Q one", "<p>one</p>", List.of("ai"), T0, T0 + 5, 2),
                        question(102, "Q two", "<p>two</p>", List.of("ai"), T0 + 1, T0 + 6, 0),
                        question(103, "Q three", "<p>three</p>", List.of("ai"), T0 + 2, T0 + 7, 1)), false));
            }
            boolean second = r.params().get("page").equals("2");
            return Resp.ok(page(second
                    ? List.of(answer(9003, 103, "<p>c</p>", false, T0 + 30))
                    : List.of(answer(9001, 101, "<p>a</p>", true, T0 + 10), answer(9002, 101, "<p>b</p>", false, T0 + 20)), !second));
        };
        var adapter = adapter();
        Page<QuestionSummary> listing = adapter.listQuestions(new TagFilter(List.of("ai"), TagMode.ANY, false), null, null);

        assertEquals(3, requests.size(), "1 listing + 2 pages of ONE batched answers call");
        assertEquals("/2.3/questions/101;103/answers", requests.get(1).path(), "only questions that have answers are included");
        requests.clear();

        Question q = adapter.fetchQuestion("101");
        assertEquals("Q one", q.title());
        assertEquals(BodyFormat.HTML, q.bodyFormat());
        assertEquals("alice", q.author());
        assertEquals(Instant.ofEpochSecond(T0).toString(), q.createdAt());
        assertEquals("https://stackoverflow.com/questions/101/slug", q.url());
        List<Answer> a101 = adapter.listAnswers("101", null).items();
        assertEquals(List.of("9001", "9002"), a101.stream().map(Answer::id).toList());
        assertTrue(a101.get(0).accepted());
        assertFalse(a101.get(1).accepted());
        assertEquals("https://stackoverflow.com/questions/101#9001", a101.get(0).url());
        assertEquals(Instant.ofEpochSecond(T0 + 10).toString(), a101.get(0).createdAt());
        assertTrue(adapter.listAnswers("102", null).items().isEmpty());
        assertEquals(List.of("9003"), adapter.listAnswers("103", null).items().stream().map(Answer::id).toList());
        assertTrue(requests.isEmpty(), "no extra API calls (quota is precious)");
        assertEquals(3, listing.items().size());
    }

    @Test
    void cacheMissesFallBackToDirectCallsAndMissingQuestionsAreNotFound() throws Exception {
        handler = r -> {
            if (r.path().equals("/2.3/questions/555")) {
                return Resp.ok(page(List.of(), false));      // the API returns 200 with no items for deleted/unknown ids
            }
            if (r.path().equals("/2.3/questions/7/answers")) {
                boolean first = r.params().get("page").equals("1");
                return Resp.ok(page(List.of(answer(first ? 1 : 2, 7, "x", false, T0)), first));
            }
            return Resp.ok(page(List.of(question(7, "t", "b", List.of("ai"), T0, T0, 2)), false));
        };
        var adapter = adapter();
        SourceException e = assertThrows(SourceException.class, () -> adapter.fetchQuestion("555"));
        assertEquals(SourceException.Kind.NOT_FOUND, e.kind(), "not provably deleted, so the sync engine will mark it UNKNOWN, not DELETED");
        assertEquals("7", adapter.fetchQuestion("7").id());
        Page<Answer> p1 = adapter.listAnswers("7", null);
        assertEquals("p:2", p1.nextCursor());
        assertNull(adapter.listAnswers("7", p1.nextCursor()).nextCursor());
        assertThrows(SourceException.class, () -> adapter.fetchQuestion("../../users"));
    }

    @Test
    void backoffRequestedByTheApiIsHonouredBeforeTheNextCall() throws Exception {
        handler = r -> {
            Map<String, Object> body = page(List.of(), false);
            if (requests.size() == 1) {
                body.put("backoff", 3);
            }
            return Resp.ok(body);
        };
        var adapter = adapter();
        var filter = new TagFilter(List.of("ai"), TagMode.ANY, false);
        adapter.listQuestions(filter, null, null);
        assertTrue(sleeps.isEmpty());
        adapter.listQuestions(filter, null, null);
        assertEquals(1, sleeps.size());
        assertTrue(sleeps.get(0).toMillis() > 1500 && sleeps.get(0).toMillis() <= 3000, sleeps.toString());
    }

    @Test
    void apiErrorsAreClassified() {
        var filter = new TagFilter(List.of("ai"), TagMode.ANY, false);
        handler = r -> new Resp(400, Map.of("error_id", 502, "error_name", "throttle_violation",
                "error_message", "too many requests from this IP, more requests available in 30 seconds"));
        SourceException throttled = assertThrows(SourceException.class, () -> adapter().listQuestions(filter, null, null));
        assertEquals(SourceException.Kind.RATE_LIMITED, throttled.kind());
        assertEquals(Duration.ofSeconds(30), throttled.retryAfter());

        handler = r -> new Resp(400, Map.of("error_id", 502, "error_name", "throttle_violation",
                "error_message", "too many requests from this IP, more requests available in 80000 seconds"));
        SourceException quota = assertThrows(SourceException.class, () -> adapter().listQuestions(filter, null, null));
        assertEquals(SourceException.Kind.QUOTA_EXHAUSTED, quota.kind());
        assertTrue(quota.getMessage().contains("application key"));
        assertFalse(SourceException.Kind.QUOTA_EXHAUSTED.isRetryable());

        handler = r -> new Resp(400, Map.of("error_id", 400, "error_name", "bad_parameter", "error_message", "tagged is invalid"));
        SourceException bad = assertThrows(SourceException.class, () -> adapter().listQuestions(filter, null, null));
        assertEquals(SourceException.Kind.FATAL, bad.kind());
        assertTrue(bad.getMessage().contains("tagged is invalid"));

        handler = r -> new Resp(500, Map.of());
        assertEquals(SourceException.Kind.TRANSIENT, assertThrows(SourceException.class, () -> adapter().listQuestions(filter, null, null)).kind());
    }

    @Test
    void applicationKeyComesFromTheEnvironmentAndNeverAppearsInMessages() throws Exception {
        handler = r -> Resp.ok(page(List.of(), false));
        adapter(Map.of("SE_KEY", "k3y-secret")).listQuestions(new TagFilter(List.of("ai"), TagMode.ANY, false), null, null);
        assertEquals("k3y-secret", requests.get(0).params().get("key"));
        requests.clear();
        adapter().listQuestions(new TagFilter(List.of("ai"), TagMode.ANY, false), null, null);
        assertFalse(requests.get(0).params().containsKey("key"), "anonymous when the variable is unset");

        handler = r -> new Resp(400, Map.of("error_id", 400, "error_message", "bad"));
        SourceException e = assertThrows(SourceException.class,
                () -> adapter(Map.of("SE_KEY", "k3y-secret")).listQuestions(new TagFilter(List.of("ai"), TagMode.ANY, false), null, null));
        assertFalse(e.getMessage().contains("k3y-secret"));
    }

    @Test
    void pingReportsQuota() throws Exception {
        handler = r -> Resp.ok(page(List.of(Map.of("total_questions", 24000000)), false));
        String text = adapter().ping();
        assertTrue(text.contains("stackoverflow") && text.contains("250") && text.contains("anonymous"), text);
    }

    // ---------------------------------------------------------------- end to end through the sync engine

    @Test
    void endToEndSyncOfAnArtificialIntelligenceTagWithAgeWindow() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        long created = Instant.parse("2026-10-25T10:00:00Z").getEpochSecond();
        handler = r -> {
            if (r.path().equals("/2.3/questions")) {
                return Resp.ok(page(List.of(
                        question(201, "Why doesn&#39;t my transformer &amp; BERT agree?",
                                "<p>Loss does not drop:</p><pre><code class=\"language-python\">model = Net()\nfor x in data:\n    loss = model(x)\n</code></pre>",
                                List.of("artificial-intelligence", "python"), created, created + 100, 1),
                        question(202, "What is the difference between AI and machine learning?", "<p>Plain question</p>",
                                List.of("artificial-intelligence"), created + 5000, created + 5000, 0)), false));
            }
            return Resp.ok(page(List.of(answer(8001, 201, "<p>Lower the learning rate.</p>", true, created + 500)), false));
        };
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.forStorage(tmp.resolve("se")), adapter(), null, Clock.fixed(now, ZoneOffset.UTC));
        SyncOptions options = new SyncOptions(List.of("Artificial Intelligence"), TagMode.ANY, false, SyncMode.INCREMENTAL, null, 2, 30, "30d");
        SyncReport report = kb.sync(options);

        assertEquals(SyncReport.COMPLETE, report.outcome(), report.failures().toString());
        assertEquals(2, report.added());
        Req listing = requests.get(0);
        assertEquals("artificial-intelligence", listing.params().get("tagged"));
        assertEquals(String.valueOf(now.minusSeconds(30 * 86_400L).getEpochSecond()), listing.params().get("fromdate"), "collection window becomes fromdate");
        assertEquals(2, requests.size(), "1 listing + 1 batched answers call; no per-question calls");

        var thread = kb.store().loadThread("stackoverflow", "201").orElseThrow();
        assertEquals("Why doesn't my transformer & BERT agree?", thread.question().title());
        assertTrue(thread.question().body().contains("```python\nmodel = Net()\nfor x in data:\n    loss = model(x)\n```"), thread.question().body());
        assertTrue(thread.question().answers().get(0).accepted());
        assertEquals("https://stackoverflow.com/questions/201/slug", thread.question().url());

        var hits = kb.search(new SearchRequest("learning rate loss does not drop", null, null, 5, 0, 0)).evidence();
        assertEquals("201", hits.get(0).questionId());
        assertEquals("https://stackoverflow.com/questions/201#8001", hits.stream().filter(e -> "8001".equals(e.answerId())).findFirst().orElseThrow().sourceUrl());

        // Incremental: only changes since the checkpoint are requested (min = high-water mark minus overlap).
        requests.clear();
        kb.sync(options);
        assertTrue(requests.get(0).params().containsKey("min"), "incremental sync sends min=<last activity>");
        assertEquals(String.valueOf(Instant.ofEpochSecond(created + 5000).minus(Duration.ofMinutes(60)).getEpochSecond()), requests.get(0).params().get("min"));
    }
}
