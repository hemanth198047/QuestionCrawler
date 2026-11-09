package com.questioncrawler.core.source;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Jive without its API: for each tag, the Jive search page (site/search.jspa?q=TAG&amp;after=year) is opened in the
 * browser window, read to the very end (scrolling and "More" / "Next"), the links of its Content section are taken,
 * and every one of those pages is read and stored as a Jive post, the same way the Links source reads pages. The
 * company login is the browser's: no API key, token or password.
 */
public final class JiveWebAdapter implements SourceAdapter {

    /** Finds the content links of one search page (the browser window in the app; a stand-in in tests). */
    @FunctionalInterface
    public interface LinkFinder {
        List<String> contentLinks(URI searchPage) throws SourceException;
    }

    /** The default search address; {site}, {tag} and {after} are filled in. */
    public static final String DEFAULT_SEARCH = "{site}/search.jspa?q={tag}&after={after}";

    /** Jive content pages: documents, discussions, messages, blog posts, ideas, polls, videos, questions. */
    static final Pattern CONTENT = Pattern.compile("(?i)/(docs/DOC-\\d+|docs/[^/?#]+|thread/\\d+|message/\\d+|discussions?/\\d+|"
            + "blogs?/[^?#]+/\\d{4}/|community/[^?#]*/blog/\\d{4}/|ideas?/\\d+|polls?/\\d+|videos?/\\d+|questions?/\\d+|external-link\\.jspa)");
    /** Never content: people, places, the search itself, tags, sign-in and actions. */
    static final Pattern NOT_CONTENT = Pattern.compile("(?i)/(people|person|places?|groups?|search\\.jspa|tags?/|login|logout|sso|welcome|activity|"
            + "inbox|actions|bookmarks|create|edit|follow|like|places\\.jspa|people\\.jspa|profile)(?:[/?#.]|$)");

    /**
     * One step on the search page, run again and again: list the result links of the Content section, scroll to the end,
     * and ask for more results ("More", "Load more", "Next") when the page offers it.
     */
    static final String STEP_SCRIPT = """
            (() => {
              const abs = (h) => { try { return new URL(h, location.href).href.split('#')[0]; } catch (e) { return null; } };
              const CONTENT = /\\/(docs\\/DOC-\\d+|docs\\/[^/?#]+|thread\\/\\d+|message\\/\\d+|discussions?\\/\\d+|blogs?\\/[^?#]+\\/\\d{4}\\/|community\\/[^?#]*\\/blog\\/\\d{4}\\/|ideas?\\/\\d+|polls?\\/\\d+|videos?\\/\\d+|questions?\\/\\d+|external-link\\.jspa)/i;
              const NOT = /\\/(people|person|places?|groups?|search\\.jspa|tags?\\/|login|logout|sso|welcome|activity|inbox|actions|bookmarks|create|edit|follow|like|places\\.jspa|people\\.jspa|profile)(?:[/?#.]|$)/i;
              const pick = (root) => [...new Set([...root.querySelectorAll('a[href]')].map((a) => abs(a.getAttribute('href')))
                .filter((h) => h && h.startsWith(location.origin) && CONTENT.test(h) && !NOT.test(h)))];
              // The "Content" section of the results (not People or Places): a heading or tab named Content and what follows it.
              let scope = null;
              for (const el of document.querySelectorAll('h1,h2,h3,h4,h5,legend,[role=tab],[class*="section-header"],[class*="tab"]')) {
                if (/^\\s*content\\s*(\\(\\s*[\\d,.]+\\s*\\))?\\s*$/i.test(el.textContent || '')) {
                  if (el.getAttribute('role') === 'tab' && el.getAttribute('aria-selected') === 'false') { el.click(); }
                  scope = el.closest('section,[class*="section"],[class*="results"],[class*="content"]') || el.parentElement;
                  break;
                }
              }
              let links = scope ? pick(scope) : [];
              if (links.length === 0) {
                const results = document.querySelector('[id*="search-result"],[class*="search-result"],[class*="searchResult"],[class*="search_result"],main,[role=main],#jive-body-main');
                links = pick(results || document.body);
              }
              const el = document.scrollingElement || document.body;
              el.scrollTop = el.scrollHeight;
              window.scrollTo(0, el.scrollHeight);
              const more = [...document.querySelectorAll('a,button')].find((b) => b.offsetParent !== null && !b.disabled
                && /^(show |load |view |see )?more( results| content| items)?\\s*$|^next( page)?\\s*[»›>]?\\s*$|^[»›>]$/i.test((b.textContent || '').trim()));
              if (more) { setTimeout(() => more.click(), 50); }
              return JSON.stringify({ links: links, advanced: !!more });
            })()
            """;

    private final String systemId;
    private final String site;
    private final String searchTemplate;
    private final LinkFinder finder;
    private final WebPageReader reader;
    private final Map<String, String> linkById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> tagsById = new ConcurrentHashMap<>();
    private final List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile String window;

    public JiveWebAdapter(String systemId, String site, String searchTemplate, LinkFinder finder, WebPageReader reader) {
        this.systemId = systemId == null || systemId.isBlank() ? "jive" : systemId;
        this.site = site == null ? "" : site.strip().replaceAll("/+$", "");
        this.searchTemplate = searchTemplate == null || searchTemplate.isBlank() ? DEFAULT_SEARCH : searchTemplate.strip();
        this.finder = finder;
        this.reader = reader;
    }

    /** The app's version: the search pages are read in the shared browser window. */
    public static JiveWebAdapter create(String systemId, String site, String searchTemplate, BrowserPageReader browser, WebPageReader reader) {
        LinkFinder finder = page -> {
            browser.newRun();
            return browser.collectLinks(page, STEP_SCRIPT, 20_000, null);
        };
        return new JiveWebAdapter(systemId, site, searchTemplate, finder, reader);
    }

    @Override
    public String systemId() {
        return systemId;
    }

    @Override
    public List<String> notes() {
        synchronized (notes) {
            return new ArrayList<>(new LinkedHashSet<>(notes));
        }
    }

    @Override
    public boolean supportsModifiedSince() {
        return false;
    }

    @Override
    public void collectionWindow(String maxAge) {
        this.window = maxAge;
    }

    /** Jive's "after" values for the collection window: day, week, month, quarter, year; empty for everything. */
    static String afterFor(String maxAge) {
        if (maxAge == null || maxAge.isBlank() || !com.questioncrawler.core.util.AgeSpec.isValid(maxAge)) {
            return "";
        }
        Instant now = Instant.now();
        long days = Duration.between(com.questioncrawler.core.util.AgeSpec.before(maxAge, now), now).toDays();
        return days <= 1 ? "day" : days <= 7 ? "week" : days <= 31 ? "month" : days <= 92 ? "quarter" : days <= 366 ? "year" : "";
    }

    /** The search address for one tag. An empty "after" is left out. */
    public URI searchPage(String tag) {
        String after = afterFor(window);
        String url = searchTemplate.replace("{site}", site)
                .replace("{tag}", URLEncoder.encode(tag, StandardCharsets.UTF_8))
                .replace("{after}", URLEncoder.encode(after, StandardCharsets.UTF_8));
        if (after.isEmpty()) {
            url = url.replaceAll("[?&]after=(?=&|$)", "").replace("?&", "?");
        }
        URI u = ShortLinks.webAddress(url);
        if (u == null) {
            throw new IllegalArgumentException("The Jive search address is not valid: " + url);
        }
        return u;
    }

    /** Lists the content found for each tag, one tag per call (so collecting shows progress tag by tag). */
    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        List<String> tags = filter == null ? List.of() : filter.tags().stream().filter(t -> !TagFilter.asksForAll(List.of(t))).toList();
        if (tags.isEmpty()) {
            throw new SourceException(SourceException.Kind.NOT_CONFIGURED,
                    "Name the tags to search Jive for (for example casc): reading Jive's web pages works tag by tag, 'all' is not possible.");
        }
        int index = cursor == null ? 0 : Integer.parseInt(cursor);
        String tag = tags.get(index);
        URI search = searchPage(tag);
        List<String> links = finder.contentLinks(search);
        if (links.isEmpty()) {
            notes.add("The Jive search for '" + tag + "' (" + Redirects.show(search) + ") showed no content.");
        }
        List<QuestionSummary> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String found : links) {
            String link = withoutTracking(found);
            String id = idFor(link);
            if (!seen.add(id)) {
                continue;
            }
            linkById.putIfAbsent(id, link);
            tagsById.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(tag.toLowerCase(Locale.ROOT));
            items.add(new QuestionSummary(id, null, link));
        }
        return new Page<>(items, index + 1 < tags.size() ? String.valueOf(index + 1) : null);
    }

    /** The same Jive page however it is linked: without #fragment and the tracking parameters of search results. */
    static String idFor(String link) {
        return "jive-web-" + com.questioncrawler.core.util.Hashes.sha256(withoutTracking(link).toLowerCase(Locale.ROOT)).substring(0, 16);
    }

    private static final Pattern TRACKING = Pattern.compile("(?i)^(sr|ru|q|query|searchid|start|context|tstart|refresh|referrer|ref)$");

    /** A result link without what the search adds to it (?sr=search&searchId=42) and without #fragment. */
    static String withoutTracking(String link) {
        String noFragment = link.split("#", 2)[0];
        int q = noFragment.indexOf('?');
        if (q < 0) {
            return noFragment;
        }
        List<String> keep = new ArrayList<>();
        for (String part : noFragment.substring(q + 1).split("&")) {
            String name = part.split("=", 2)[0];
            if (!part.isEmpty() && !TRACKING.matcher(name).matches()) {
                keep.add(part);
            }
        }
        return noFragment.substring(0, q) + (keep.isEmpty() ? "" : "?" + String.join("&", keep));
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        String link = linkById.get(id);
        if (link == null) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "This Jive page was not in the search results of this run");
        }
        WebPageReader.WebPage page = reader.read(link);
        Question q = LinksAdapter.toQuestion(id, link, page);
        List<String> tags = new ArrayList<>(tagsById.getOrDefault(id, Set.of()));
        tags.add("jive");
        for (String t : q.tags()) {
            if (t.startsWith("site:")) {
                tags.add(t);
            }
        }
        String kind = kindOf(page.finalUrl());
        if (kind != null) {
            tags.add("type:" + kind);
        }
        return new Question(q.id(), q.title().replaceAll("\\s*[|–-]\\s*[^|–-]*(Jive|Community)[^|–-]*$", "").strip(), q.body(), q.bodyFormat(), q.url(),
                tags, q.author(), q.score(), q.createdAt(), q.modifiedAt(), q.comments(), q.answers());
    }

    static String kindOf(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return u.contains("/docs/") ? "document" : u.contains("/thread/") || u.contains("/message/") || u.contains("/discussion") ? "discussion"
                : u.contains("/blog") ? "blogpost" : u.contains("/idea") ? "idea" : u.contains("/poll") ? "poll" : u.contains("/video") ? "video"
                : u.contains("/question") ? "question" : null;
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) {
        return new Page<>(List.of(), null); // the replies are part of the page that was read
    }

    @Override
    public String ping() throws SourceException {
        return "Jive web pages at " + site + " (searched tag by tag in your browser)";
    }

    /** For Check access: the search for one tag, and how many content pages it lists. */
    public Map<String, String> trySearch(String tag) {
        Map<String, String> out = new LinkedHashMap<>();
        URI search = searchPage(tag);
        out.put("Search page", search.toString());
        try {
            List<String> links = finder.contentLinks(search);
            out.put("Content found", links.size() + " page(s)");
            for (int i = 0; i < Math.min(3, links.size()); i++) {
                out.put("Example " + (i + 1), links.get(i));
            }
            if (!links.isEmpty()) {
                try {
                    WebPageReader.WebPage page = reader.read(links.get(0));
                    out.put("First page read", "OK: \"" + LinksAdapter.toQuestion("x", links.get(0), page).title() + "\"");
                } catch (SourceException e) {
                    out.put("First page read", "NOT READ: " + e.getMessage());
                }
            }
        } catch (SourceException | RuntimeException e) {
            out.put("Problem", e.getMessage());
        }
        return out;
    }
}
