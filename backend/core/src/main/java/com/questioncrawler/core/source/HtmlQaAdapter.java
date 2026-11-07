package com.questioncrawler.core.source;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.util.Sleeper;
import com.questioncrawler.core.util.Times;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Website mode: reads questions and answers from the pages of a Stack Overflow-style Q&A website (Stack Overflow
 * Enterprise / for Teams, or any site with the same page layout) when its REST API cannot be used, for example when the
 * only address that works is the website behind the company login. Read-only: it opens the same pages a person opens in
 * the browser, with that person's browser session, one page at a time with a short pause.
 *
 * <ul>
 *   <li>Listing: {@code /questions?tab=Newest} (or {@code /questions/tagged/a+or+b}), page by page; for a quick update the
 *       "Active" tab, stopping at the last collected change.</li>
 *   <li>Each question: {@code /questions/{id}} with its title, text, tags, author, dates, votes and every answer
 *       (accepted, votes, author, date), following the answer pages.</li>
 * </ul>
 */
public final class HtmlQaAdapter implements SourceAdapter, BrowserSignIn {

    static final int PAGE_SIZE = 50;
    private static final Duration PAUSE = Duration.ofMillis(250);
    private static final Pattern QUESTION_LINK = Pattern.compile("/questions/(\\d+)(?:/|$|\\?|#)");

    private final SourceSettings settings;
    private final HttpSourceClient client;
    private final String root;
    private final Sleeper sleeper;
    private final List<String> notes = new ArrayList<>();
    private BrowserLogin browser;
    private BrowserLogin.Prober browserProbe;
    private List<String> lastPageIds = List.of();

    /** @param siteRoot the website address, for example https://stackoverflow.mycompany.com (an API or page address is reduced to it) */
    public HtmlQaAdapter(SourceSettings settings, HttpSourceClient client, String siteRoot, Sleeper sleeper) {
        this.settings = settings;
        this.client = client;
        this.root = siteRoot(siteRoot);
        this.sleeper = sleeper == null ? Sleeper.REAL : sleeper;
    }

    public HtmlQaAdapter withBrowser(BrowserLogin login, BrowserLogin.Prober probe) {
        this.browser = login;
        this.browserProbe = probe;
        return this;
    }

    /** The website root from any address on it: https://qa.corp.com/api/2.3 or /questions/12/x become https://qa.corp.com. */
    public static String siteRoot(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        URI u = URI.create(address.strip());
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        Matcher m = Pattern.compile("/(api|questions|posts|articles|users|tags|search|swagger)(/|$)").matcher(path);
        String context = m.find() ? path.substring(0, m.start()) : path;
        while (context.endsWith("/")) {
            context = context.substring(0, context.length() - 1);
        }
        return u.getScheme() + "://" + u.getRawAuthority() + context;
    }

    /** True when an HTML page is a Stack Overflow-style question list or question page (so website mode can read it). */
    public static boolean looksLikeQaSite(String html) {
        if (html == null) {
            return false;
        }
        return html.contains("s-post-summary") || html.contains("question-summary") || html.contains("js-post-body")
                || QUESTION_LINK.matcher(html).find() && html.contains("post-tag");
    }

    @Override
    public String systemId() {
        return settings.systemId();
    }

    @Override
    public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override
    public boolean supportsModifiedSince() {
        return true; // the "Active" tab is ordered by last activity
    }

    @Override
    public String ping() throws SourceException {
        Document doc = page(root + "/questions?tab=Newest&pagesize=15");
        int n = summaries(doc).size();
        if (n == 0 && !looksLikeQaSite(doc.outerHtml())) {
            throw new SourceException(SourceException.Kind.FATAL, "The page " + root + "/questions is not a Stack Overflow-style question list, "
                    + "so website mode cannot read it. Check the site address.");
        }
        return "Website mode: read the question list at " + root + "/questions (" + n + " question(s) on the first page). "
                + "Questions and answers are read from the site's pages with your browser session.";
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        int pageNo = cursor == null || cursor.isBlank() ? 1 : Integer.parseInt(cursor);
        boolean activeTab = modifiedSince != null;
        String path = filter.isAll() ? "/questions" : "/questions/tagged/" + tagPath(filter);
        Document doc = page(root + path + "?tab=" + (activeTab ? "Active" : "Newest") + "&page=" + pageNo + "&pagesize=" + PAGE_SIZE);
        Instant since = activeTab ? Times.parse(modifiedSince).orElse(null) : null;
        Instant createdAfter = filter.createdAfter() == null ? null : Times.parse(filter.createdAfter()).orElse(null);
        List<QuestionSummary> items = new ArrayList<>();
        boolean reachedOld = false;
        List<Summary> found = summaries(doc);
        for (Summary s : found) {
            Instant when = s.time() == null ? null : Times.parse(s.time()).orElse(null);
            if (since != null && when != null && when.isBefore(since)) {
                reachedOld = true; // the Active tab is newest first
                break;
            }
            if (!activeTab && createdAfter != null && when != null && when.isBefore(createdAfter)) {
                reachedOld = true; // the Newest tab is ordered by creation
                break;
            }
            items.add(new QuestionSummary(s.id(), activeTab ? s.time() : null, root + "/questions/" + s.id()));
        }
        // No page limit; a site that ignores the page number (same questions again) ends the listing instead of looping.
        List<String> ids = found.stream().map(Summary::id).toList();
        boolean repeated = !ids.isEmpty() && ids.equals(lastPageIds);
        lastPageIds = ids;
        if (repeated) {
            notes.add("The website returned the same questions for page " + pageNo + " as for the page before, so listing ended there.");
            return new Page<>(List.of(), null);
        }
        boolean more = !reachedOld && !found.isEmpty() && hasNextPage(doc, found.size());
        return new Page<>(items, more ? String.valueOf(pageNo + 1) : null);
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        String url = root + "/questions/" + enc(id);
        Document doc = page(url);
        Element q = first(doc, "#question", "[data-questionid]", ".question");
        if (q == null) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "Question " + id + " was not found on the website (no question on its page).");
        }
        String title = text(first(doc, "#question-header h1 a", "#question-header h1", "h1[itemprop=name]", "h1"));
        Element body = first(q, ".js-post-body", ".s-prose", ".post-text", "[itemprop=text]");
        List<String> tags = new ArrayList<>(new LinkedHashSet<>(doc.select("#question .post-tag, .question .post-tag, #question .js-post-tag-list-item a, .post-taglist .post-tag")
                .eachText()));
        Signature asker = signature(q, true);
        String created = attr(first(q, "time[itemprop=dateCreated]"), "datetime");
        if (created == null) {
            created = asker.time();
        }
        String modified = latestTime(q);
        Integer score = score(q);
        List<Answer> answers = new ArrayList<>();
        Document current = doc;
        Set<String> seen = new LinkedHashSet<>();
        Set<String> visited = new java.util.HashSet<>();
        visited.add(url);
        while (current != null) { // every answer page, however many; stops when there is no new "next" page
            for (Element a : current.select("div.answer[data-answerid], div.answer[id^=answer-]")) {
                String aid = a.hasAttr("data-answerid") ? a.attr("data-answerid") : a.id().replace("answer-", "");
                if (aid.isBlank() || !seen.add(aid)) {
                    continue;
                }
                Element aBody = first(a, ".js-post-body", ".s-prose", ".post-text", "[itemprop=text]");
                Signature who = signature(a, false);
                boolean accepted = a.hasClass("accepted-answer") || a.hasClass("js-accepted-answer") || "acceptedAnswer".equals(a.attr("itemprop"));
                String aCreated = attr(first(a, "time[itemprop=dateCreated]"), "datetime");
                answers.add(new Answer(aid, aBody == null ? "" : aBody.html(), BodyFormat.HTML, url + "#answer-" + aid, accepted, score(a),
                        who.author(), iso(aCreated != null ? aCreated : who.time()), latestTime(a), List.of()));
            }
            Element next = first(current, "#answers .s-pagination a[rel=next]", "#answers a[rel=next]", ".pager-answers a[rel=next]");
            String nextHref = next == null ? null : next.absUrl("href");
            current = nextHref == null || nextHref.isBlank() || !visited.add(nextHref) ? null : page(nextHref);
        }
        return new Question(id, title == null ? "" : title, body == null ? "" : body.html(), BodyFormat.HTML, url, tags,
                asker.author(), score, iso(created), iso(modified != null ? modified : created), List.of(), answers);
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) {
        return new Page<>(List.of(), null); // answers come with the question page (fetchQuestion)
    }

    @Override
    public String signIn() throws SourceException {
        if (browser == null) {
            return "this sign-in method does not use the browser";
        }
        browser.cookieHeader(browserProbe);
        return browser.status();
    }

    @Override
    public String sessionCookie() {
        return browser == null ? null : browser.current();
    }

    // ---------------------------------------------------------------- reading pages

    record Summary(String id, String time) {}

    /** The questions of a list page, with the time shown next to each (asked or last active, depending on the tab). */
    static List<Summary> summaries(Document doc) {
        Map<String, Summary> out = new LinkedHashMap<>();
        for (Element e : doc.select(".s-post-summary[data-post-id], .js-post-summary[data-post-id], .question-summary[id^=question-summary-]")) {
            String id = e.hasAttr("data-post-id") ? e.attr("data-post-id") : e.id().replace("question-summary-", "");
            Element link = first(e, ".s-post-summary--content-title a", "h3 a", "a.question-hyperlink");
            if (link != null && !link.attr("href").contains("/questions/")) {
                continue; // articles and other post types
            }
            Element time = first(e, ".s-user-card--time .relativetime", ".relativetime", "time[datetime]");
            String when = time == null ? null : time.hasAttr("title") ? time.attr("title") : time.attr("datetime");
            if (!id.isBlank()) {
                out.putIfAbsent(id, new Summary(id, iso(when)));
            }
        }
        if (out.isEmpty()) { // a layout we do not know: fall back to the question links on the page
            for (Element a : doc.select("a[href]")) {
                Matcher m = QUESTION_LINK.matcher(a.attr("href"));
                if (m.find()) {
                    out.putIfAbsent(m.group(1), new Summary(m.group(1), null));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static boolean hasNextPage(Document doc, int found) {
        return doc.selectFirst(".s-pagination a[rel=next], a[rel=next], .pager a[rel=next]") != null || found >= PAGE_SIZE;
    }

    record Signature(String author, String time) {}

    /** Who wrote a post and when: the asker's ("owner") signature for a question, the last (answering) one for an answer. */
    private static Signature signature(Element post, boolean question) {
        List<Element> sigs = post.select(".post-signature");
        Element sig = null;
        if (question) {
            sig = post.selectFirst(".post-signature.owner");
        }
        if (sig == null && !sigs.isEmpty()) {
            sig = sigs.get(sigs.size() - 1);
        }
        if (sig == null) {
            return new Signature(null, null);
        }
        Element name = first(sig, ".user-details a", ".user-details [itemprop=name]", ".s-user-card--link a", ".user-details");
        Element time = first(sig, ".relativetime", "time[datetime]");
        String when = time == null ? null : time.hasAttr("title") ? time.attr("title") : time.attr("datetime");
        return new Signature(name == null ? null : name.text().strip(), when);
    }

    /** The latest time on a post (edits included), as its last-modified time. */
    private static String latestTime(Element post) {
        Instant latest = null;
        for (Element t : post.select(".post-signature .relativetime[title], time[datetime]")) {
            String raw = t.hasAttr("title") ? t.attr("title") : t.attr("datetime");
            Instant at = Times.parse(iso(raw)).orElse(null);
            if (at != null && (latest == null || at.isAfter(latest))) {
                latest = at;
            }
        }
        return latest == null ? null : latest.toString();
    }

    private static Integer score(Element post) {
        String raw = post.hasAttr("data-score") ? post.attr("data-score") : null;
        if (raw == null) {
            Element v = first(post, ".js-vote-count[data-value]", ".js-vote-count", "[itemprop=upvoteCount]");
            raw = v == null ? null : v.hasAttr("data-value") ? v.attr("data-value") : v.text();
        }
        try {
            return raw == null || raw.isBlank() ? null : Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One website page as HTML, with the browser session; a page that sends us to the login signs in again once. */
    private Document page(String url) throws SourceException {
        try {
            sleeper.sleep(PAUSE); // be gentle with the website: one page at a time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.FATAL, "Interrupted", null, e);
        }
        HttpSourceClient.Response r = exchange(url);
        if (browser != null && BrowserLogin.needsSignIn(r)) {
            browser.invalidate(browser.current());
            r = exchange(url);
        }
        if (BrowserLogin.needsSignIn(r)) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED, "The website sends the app to its sign-in page"
                    + (browser == null ? ". Choose 'Sign in with my browser' on the Connect screen." : " even after signing in again in the browser."));
        }
        if (r.status() == 404) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "Not found on the website: " + withoutQuery(url));
        }
        if (r.status() < 200 || r.status() >= 300) {
            throw client.mapStatus(r.status(), r.retryAfter(), URI.create(url));
        }
        return Jsoup.parse(r.body() == null ? "" : r.body(), url);
    }

    private HttpSourceClient.Response exchange(String url) throws SourceException {
        try {
            return client.exchange(url, Map.of("Accept", "text/html,application/xhtml+xml"));
        } catch (SourceException e) {
            if (browser != null && e.getMessage() != null && e.getMessage().contains("redirected")) {
                browser.invalidate(browser.current()); // redirected to the login host: sign in again
                return client.exchange(url, Map.of("Accept", "text/html,application/xhtml+xml"));
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String tagPath(TagFilter filter) {
        List<String> parts = new ArrayList<>();
        for (String t : filter.tags()) {
            parts.add(URLEncoder.encode(TagFilter.normalize(t), StandardCharsets.UTF_8));
        }
        return String.join(filter.mode() == TagMode.ALL ? "+" : "+or+", parts);
    }

    private static Element first(Element scope, String... selectors) {
        for (String s : selectors) {
            Element e = scope.selectFirst(s);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element e) {
        return e == null ? null : e.text().strip();
    }

    private static String attr(Element e, String name) {
        return e == null || !e.hasAttr(name) ? null : e.attr(name);
    }

    /** "2024-05-14 10:17:47Z" (the sites' title format) or ISO, as an ISO instant; null when unknown. */
    static String iso(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.strip().replace(' ', 'T');
        if (t.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) {
            t += "Z";
        }
        return Times.parse(t).map(Instant::toString).orElse(null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
