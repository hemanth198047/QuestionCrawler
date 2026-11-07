package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.HtmlQaAdapter;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Website mode: a Stack Overflow-style site (Stack Overflow Enterprise layout) whose address answers with web pages, not
 * API data. The questions and answers are read from the pages. The site is a local mock with that page layout.
 */
class WebsiteModeTest {

    @TempDir
    Path tmp;

    HttpServer server;
    String origin;
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    static String summary(String id, String title, String when, String... tags) {
        StringBuilder t = new StringBuilder();
        for (String tag : tags) {
            t.append("<a class=\"post-tag\" href=\"/questions/tagged/").append(tag).append("\">").append(tag).append("</a>");
        }
        return """
                <div id="question-summary-%1$s" class="s-post-summary js-post-summary" data-post-id="%1$s" data-post-type-id="1">
                  <div class="s-post-summary--content">
                    <h3 class="s-post-summary--content-title"><a href="/questions/%1$s/slug" class="s-link">%2$s</a></h3>
                    <div class="s-post-summary--meta"><div class="s-post-summary--meta-tags">%4$s</div>
                      <div class="s-user-card"><time class="s-user-card--time">asked <span title="%3$s" class="relativetime">2 days ago</span></time></div>
                    </div>
                  </div>
                </div>""".formatted(id, title, when, t);
    }

    static String listPage(boolean next, String... summaries) {
        return "<html><head><title>Newest Posts - Example Corp</title></head><body><div id=\"questions\">" + String.join("\n", summaries)
                + "</div>" + (next ? "<div class=\"s-pagination\"><a rel=\"next\" href=\"?page=2\">Next</a></div>" : "") + "</body></html>";
    }

    static final String QUESTION_101 = """
            <html><head><title>How do I rotate the VPN certificate? - Example Corp</title></head><body>
            <div id="question-header"><h1 itemprop="name"><a href="/questions/101/how-do-i" class="question-hyperlink">How do I rotate the VPN certificate?</a></h1></div>
            <div id="question" class="question js-question" data-questionid="101" data-score="7">
              <div class="js-vote-count" data-value="7">7</div>
              <div class="s-prose js-post-body" itemprop="text"><p>The certificate expires next week. What are the steps?</p></div>
              <div class="post-taglist"><a class="post-tag" href="/questions/tagged/vpn">vpn</a><a class="post-tag" href="/questions/tagged/security">security</a></div>
              <time itemprop="dateCreated" datetime="2026-03-01T09:00:00">asked Mar 1</time>
              <div class="post-signature owner"><div class="user-action-time">asked <span title="2026-03-01 09:00:00Z" class="relativetime">Mar 1</span></div>
                <div class="user-details"><a href="/users/5/ada">Ada</a></div></div>
            </div>
            <div id="answers">
              <div id="answer-201" class="answer js-answer accepted-answer js-accepted-answer" data-answerid="201" data-score="12" itemprop="acceptedAnswer">
                <div class="s-prose js-post-body" itemprop="text"><ol><li>Open the PKI portal.</li><li>Request a new certificate.</li></ol></div>
                <div class="post-signature"><div class="user-action-time">answered <span title="2026-03-02 10:00:00Z" class="relativetime">Mar 2</span></div>
                  <div class="user-details"><a href="/users/6/bob">Bob</a></div></div>
              </div>
              <div id="answer-202" class="answer js-answer" data-answerid="202" data-score="1">
                <div class="s-prose js-post-body"><p>Ask the network team.</p></div>
                <div class="post-signature"><div class="user-details"><a href="/users/7/cy">Cy</a></div>
                  <div class="user-action-time">answered <span title="2026-03-03 11:00:00Z" class="relativetime">Mar 3</span></div></div>
              </div>
              <div class="s-pagination"><a rel="next" href="/questions/101?page=2&tab=scoredesc#tab-top">Next</a></div>
            </div></body></html>""";

    static final String QUESTION_101_PAGE2 = """
            <html><body><div id="question" data-questionid="101"></div><div id="answers">
              <div id="answer-203" class="answer" data-answerid="203" data-score="0">
                <div class="js-post-body"><p>Third answer on the second page.</p></div>
                <div class="post-signature"><div class="user-details"><a href="/users/8/di">Di</a></div>
                  <span title="2026-03-04 12:00:00Z" class="relativetime">Mar 4</span></div>
              </div></div></body></html>""";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            requests.add(path + "?" + query);
            String html;
            if (path.equals("/questions") || path.startsWith("/questions/tagged/")) {
                html = query.contains("page=2")
                        ? listPage(false, summary("103", "Old question", "2025-01-01 08:00:00Z", "vpn"))
                        : listPage(true, summary("101", "How do I rotate the VPN certificate?", "2026-03-05 09:00:00Z", "vpn", "security"),
                                summary("102", "Proxy settings for git", "2026-02-20 09:00:00Z", "git"));
            } else if (path.equals("/questions/101")) {
                html = query.contains("page=2") ? QUESTION_101_PAGE2 : QUESTION_101;
            } else {
                html = "<html><head><title>Home - Example Corp</title></head><body>Welcome</body></html>";
            }
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
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

    private SourceSettings settings(String base, String mode) {
        return new SourceSettings("internal-qa", base, List.of("127.0.0.1"), List.of("/"), "none", null, "Authorization", "Bearer", null,
                Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                new ApiOptions("key", "query", null, "withbody", false, null, null, "same-host", mode), null, null);
    }

    private HtmlQaAdapter website(String base) {
        SourceSettings s = settings(base, "website");
        return new HtmlQaAdapter(s, new HttpSourceClient(s, k -> null), base, d -> { });
    }

    @Test
    void readsTheQuestionListPageByPageWithTheTagsInTheAddress() throws Exception {
        HtmlQaAdapter web = website(origin);
        TagFilter vpn = new TagFilter(List.of("vpn", "git"), TagMode.ANY, false);
        Page<QuestionSummary> first = web.listQuestions(vpn, null, null);
        assertEquals(List.of("101", "102"), first.items().stream().map(QuestionSummary::id).toList());
        assertEquals("2", first.nextCursor());
        assertTrue(requests.get(0).startsWith("/questions/tagged/vpn+or+git?"), requests.get(0));
        Page<QuestionSummary> second = web.listQuestions(vpn, null, first.nextCursor());
        assertEquals(List.of("103"), second.items().stream().map(QuestionSummary::id).toList());
        assertNull(second.nextCursor());
    }

    @Test
    void aTimeWindowStopsAtTheFirstOlderQuestion() throws Exception {
        TagFilter window = new TagFilter(List.of("all"), TagMode.ANY, false, "2026-03-01T00:00:00Z");
        Page<QuestionSummary> page = website(origin).listQuestions(window, null, null);
        assertEquals(List.of("101"), page.items().stream().map(QuestionSummary::id).toList());
        assertNull(page.nextCursor());
        assertTrue(requests.get(0).contains("tab=Newest"));
    }

    @Test
    void readsAQuestionWithEveryAnswerAcrossAnswerPages() throws Exception {
        Question q = website(origin).fetchQuestion("101");
        assertEquals("How do I rotate the VPN certificate?", q.title());
        assertTrue(q.body().contains("certificate expires next week"));
        assertEquals(List.of("vpn", "security"), q.tags());
        assertEquals("Ada", q.author());
        assertEquals(7, q.score());
        assertEquals("2026-03-01T09:00:00Z", q.createdAt());
        assertEquals(List.of("201", "202", "203"), q.answers().stream().map(Answer::id).toList());
        Answer accepted = q.answers().get(0);
        assertTrue(accepted.accepted());
        assertEquals(12, accepted.score());
        assertEquals("Bob", accepted.author());
        assertEquals("2026-03-02T10:00:00Z", accepted.createdAt());
        assertTrue(accepted.body().contains("Open the PKI portal"));
        assertFalse(q.answers().get(1).accepted());
        assertEquals("Cy", q.answers().get(1).author());
    }

    @Test
    void automaticModeSwitchesToTheWebsiteWhenTheApiAddressAnswersWithIt() throws Exception {
        // The person entered the website address as the API address (it answers "Newest Posts" pages, not JSON).
        SourceSettings s = settings(origin, "auto");
        HttpSourceClient client = new HttpSourceClient(s, k -> null);
        StackExchangeAdapter adapter = new StackExchangeAdapter(s, client, k -> null, d -> { }, null)
                .withWebsite(new HtmlQaAdapter(s, client, origin, d -> { }), "auto");
        String ping = adapter.ping();
        assertTrue(ping.contains("Website mode"), ping);
        assertTrue(adapter.usingWebsite());
        assertTrue(adapter.notes().get(0).contains("website mode"), adapter.notes().toString());
        Question q = adapter.fetchQuestion("101");
        assertEquals(3, q.answers().size());
    }

    @Test
    void apiOnlyModeKeepsTheApiError() {
        SourceSettings s = settings(origin, "api");
        HttpSourceClient client = new HttpSourceClient(s, k -> null);
        StackExchangeAdapter adapter = new StackExchangeAdapter(s, client, k -> null, d -> { }, null)
                .withWebsite(new HtmlQaAdapter(s, client, origin, d -> { }), "api");
        var e = org.junit.jupiter.api.Assertions.assertThrows(com.questioncrawler.core.source.SourceException.class, adapter::ping);
        assertTrue(e.getMessage().contains("did not answer with JSON"), e.getMessage());
        assertFalse(adapter.usingWebsite());
    }

    @Test
    void siteRootAndSettings() {
        assertEquals("https://qa.corp.com", HtmlQaAdapter.siteRoot("https://qa.corp.com/api/2.3"));
        assertEquals("https://qa.corp.com", HtmlQaAdapter.siteRoot("https://qa.corp.com/questions/12/slug"));
        assertEquals("https://corp.com/qa", HtmlQaAdapter.siteRoot("https://corp.com/qa/questions"));
        CoreConfig website = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("d").toString(), "source.type", "live",
                "source.baseUrl", "https://qa.corp.com", "api.mode", "website", "source.auth.method", "browser", "sync.tags", "all"));
        assertEquals(List.of(), website.validate());
        assertEquals("website", website.source().api().mode());
        CoreConfig bad = CoreConfig.load(null, Map.of("source.type", "live", "source.baseUrl", "https://qa.corp.com", "api.mode", "scrape", "sync.tags", "all"));
        assertTrue(bad.validate().stream().anyMatch(p -> p.contains("api.mode")));
    }
}
