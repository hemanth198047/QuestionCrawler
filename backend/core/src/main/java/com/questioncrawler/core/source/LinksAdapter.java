package com.questioncrawler.core.source;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The "Links" source: short links (go/xyz, bit.ly/...) and page addresses, expanded and read as pages. The links come
 * from the settings and, optionally, from the short links found in what was already collected from other sources.
 * Pages behind a company login are read after signing in through a browser window, as for Confluence.
 */
public final class LinksAdapter implements SourceAdapter {

    private final String systemId;
    private final List<String> configured;
    private final boolean fromCollected;
    private final Path threadsDir;
    private final ShortLinks shortLinks;
    private final WebPageReader reader;
    private final Map<String, String> byId = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());

    private final LinksOptions crawl;

    public LinksAdapter(String systemId, List<String> links, boolean fromCollected, Path threadsDir, ShortLinks shortLinks, WebPageReader reader) {
        this(systemId, new LinksOptions(links, fromCollected), threadsDir, shortLinks, reader);
    }

    public LinksAdapter(String systemId, LinksOptions options, Path threadsDir, ShortLinks shortLinks, WebPageReader reader) {
        this.crawl = options == null ? LinksOptions.NONE : options;
        List<String> links = crawl.links();
        boolean fromCollected = crawl.fromCollected();
        this.systemId = systemId == null || systemId.isBlank() ? "links" : systemId;
        this.configured = links == null ? List.of() : links.stream().map(String::strip).filter(l -> !l.isEmpty()).distinct().toList();
        this.fromCollected = fromCollected;
        this.threadsDir = threadsDir;
        this.shortLinks = shortLinks;
        this.reader = reader;
    }

    @Override
    public String systemId() {
        return systemId;
    }

    @Override
    public List<String> notes() {
        synchronized (notes) {
            return new ArrayList<>(new java.util.LinkedHashSet<>(notes));
        }
    }

    @Override
    public boolean supportsModifiedSince() {
        return false;
    }

    /** Every link to read: the configured ones first, then short links found in collected items (when chosen). */
    public List<String> links() {
        List<String> all = new ArrayList<>(configured);
        if (fromCollected) {
            for (String l : linksInCollected()) {
                if (!all.contains(l)) {
                    all.add(l);
                }
            }
        }
        return all;
    }

    /** Short links written in the items already collected from the other sources. */
    List<String> linksInCollected() {
        List<String> found = new ArrayList<>();
        if (threadsDir == null || !Files.isDirectory(threadsDir) || shortLinks == null) {
            return found;
        }
        try (Stream<Path> files = Files.walk(threadsDir, 2)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.toString().endsWith(".json") || f.getParent() == null || f.getParent().getFileName().toString().equals(systemId)) {
                    continue;
                }
                String text;
                try {
                    text = Files.readString(f, StandardCharsets.UTF_8).replace("\\n", "\n").replace("\\\"", "\"");
                } catch (IOException | RuntimeException e) {
                    continue;
                }
                for (String l : shortLinks.findIn(text)) {
                    if (!found.contains(l)) {
                        found.add(l);
                    }
                }
            }
        } catch (IOException e) {
            notes.add("The collected items could not be searched for short links: " + e.getMessage());
        }
        return found;
    }

    public static String idFor(String link) {
        URI u = WebPageReader.toUri(link);
        String key = u == null ? link.strip() : u.toString();
        return "link-" + com.questioncrawler.core.util.Hashes.sha256(key.toLowerCase(Locale.ROOT)).substring(0, 16);
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        List<String> all = links();
        if (crawl.crawls() && !all.isEmpty()) {
            return crawlPage(all, cursor);
        }
        if (all.isEmpty()) {
            throw new SourceException(SourceException.Kind.NOT_CONFIGURED, "No links to read: add short links or page addresses under 'Links to read'"
                    + (fromCollected ? ", or collect another source first (its short links are read too)." : "."));
        }
        List<QuestionSummary> items = new ArrayList<>();
        for (String l : all) {
            String id = idFor(l);
            byId.put(id, l);
            items.add(new QuestionSummary(id, null, l));
        }
        return new Page<>(items, null);
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        String link = byId.get(id);
        if (link == null) {
            for (String l : links()) {
                byId.put(idFor(l), l);
            }
            link = byId.get(id);
        }
        if (link == null) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "This link is no longer in the list");
        }
        WebPageReader.WebPage page = alreadyRead.remove(id); // read while following links: not read twice
        if (page == null) {
            page = reader.read(link);
        }
        return toQuestion(id, link, page);
    }

    // ================================================================== following the links on each page

    /** Pages read while following links, kept until they are stored (each page is downloaded once per run). */
    private final Map<String, WebPageReader.WebPage> alreadyRead = new java.util.concurrent.ConcurrentHashMap<>();
    private Crawl running;

    private record Next(String link, int level, URI root, boolean listed) {}

    /** One run's walk through the pages: what is waiting, what was seen. */
    private final class Crawl {
        final java.util.ArrayDeque<Next> queue = new java.util.ArrayDeque<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        int pages;
        int skipped;
        int outOfScope;
    }

    private static final int PAGES_PER_STEP = 20;
    private static final Pattern HREF = Pattern.compile("(?is)<a\\s[^>]*?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    /** Links that change something or end the session, and files that are not pages: never followed. */
    private static final Pattern NEVER_FOLLOW = Pattern.compile("(?i)(logout|log-out|logoff|signout|sign-out|/login\\b|/signin\\b|/sso/|"
            + "[/=]delete\\b|delete\\.action|removepage|unsubscribe|/edit\\b|action=edit|editpage|createpage|copypage|movepage|"
            + "viewpreviousversions|diffpages|pageinfo\\.action|/history\\b|[?&]print=|/print/|export\\.action|/export/|/download/|download\\.action|"
            + "/attachments?/|[?&]replyto=)");
    private static final Pattern NOT_A_PAGE = Pattern.compile("(?i)\\.(pdf|zip|png|jpe?g|gif|svg|ico|bmp|webp|css|js|json|xml|docx?|xlsx?|pptx?|csv|txt|"
            + "mp4|mp3|wav|avi|mov|exe|msi|tar|gz|tgz|7z|rar|iso|dmg|jar|war|woff2?|ttf|eot)(\\?|$)");

    /**
     * Reads the listed pages and, page by page, the pages they link to (to the chosen depth, inside the chosen scope, up
     * to the page limit). Returns about {@value #PAGES_PER_STEP} pages per call so collecting shows progress.
     */
    private Page<QuestionSummary> crawlPage(List<String> start, String cursor) throws SourceException {
        if (cursor == null || running == null) {
            reader.newRun();
            running = new Crawl();
            for (String l : start) {
                running.queue.add(new Next(l, 0, null, true));
                running.seen.add(key(l));
            }
        }
        Crawl c = running;
        List<QuestionSummary> items = new ArrayList<>();
        while (!c.queue.isEmpty() && items.size() < PAGES_PER_STEP && c.pages < crawl.maxPages()) {
            Next n = c.queue.poll();
            WebPageReader.WebPage page;
            try {
                page = reader.read(n.link());
            } catch (SourceException e) {
                if (n.listed()) {
                    // a link you listed: it is reported with the reason when it is fetched
                    String id = idFor(n.link());
                    byId.put(id, n.link());
                    items.add(new QuestionSummary(id, null, n.link()));
                    c.pages++;
                } else {
                    c.skipped++;
                }
                continue;
            }
            String finalKey = key(page.finalUrl());
            if (!n.listed() && !c.seen.add(finalKey) && !finalKey.equals(key(n.link()))) {
                continue; // another link that leads to a page already read
            }
            c.seen.add(finalKey);
            String id = n.listed() ? idFor(n.link()) : idFor(page.finalUrl());
            if (byId.containsKey(id)) {
                continue;
            }
            byId.put(id, n.listed() ? n.link() : page.finalUrl());
            alreadyRead.put(id, page);
            items.add(new QuestionSummary(id, null, page.finalUrl()));
            c.pages++;
            if (n.level() < crawl.depth()) {
                URI here = WebPageReader.toUri(page.finalUrl());
                URI root = n.root() != null ? n.root() : here;
                for (String href : linksOn(page.html(), here)) {
                    String k = key(href);
                    if (c.seen.contains(k)) {
                        continue;
                    }
                    if (!inScope(href, root)) {
                        c.outOfScope++;
                        continue;
                    }
                    c.seen.add(k);
                    c.queue.add(new Next(href, n.level() + 1, root, false));
                }
            }
        }
        boolean more = !c.queue.isEmpty() && c.pages < crawl.maxPages();
        if (!more) {
            if (c.pages >= crawl.maxPages() && !c.queue.isEmpty()) {
                notes.add("Stopped at the page limit (" + crawl.maxPages() + " pages); " + c.queue.size()
                        + " more linked page(s) were not read. Raise 'At most this many pages' to read them.");
            }
            if (c.skipped > 0) {
                notes.add(c.skipped + " linked page(s) could not be read (no access, sign-in not finished, not found or not a web page) and were skipped.");
            }
            if (c.outOfScope > 0 && !"any".equals(crawl.scope())) {
                notes.add(c.outOfScope + " link(s) lead outside " + ("section".equals(crawl.scope()) ? "the start page's section" : "the start page's site")
                        + " and were not followed (setting 'Follow links to').");
            }
            running = null;
        }
        return new Page<>(items, more ? "crawl-" + c.pages : null);
    }

    /** The web pages a page links to (absolute, without #fragment), in page order, without repeats. */
    static List<String> linksOn(String html, URI base) {
        List<String> out = new ArrayList<>();
        if (html == null || base == null) {
            return out;
        }
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            href = href.strip().replace("&amp;", "&");
            if (href.isEmpty() || href.startsWith("#") || href.matches("(?i)^(mailto|javascript|tel|data|ftp|file):.*")) {
                continue;
            }
            URI u;
            try {
                u = base.resolve(href.replace(" ", "%20"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (u.getScheme() == null || !(u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https")) || u.getHost() == null) {
                continue;
            }
            String clean = u.toString().split("#", 2)[0];
            if (NEVER_FOLLOW.matcher(clean).find() || NOT_A_PAGE.matcher(u.getRawPath() == null ? "" : u.getRawPath()).find()) {
                continue;
            }
            if (!out.contains(clean)) {
                out.add(clean);
            }
        }
        return out;
    }

    /** Same site (default), the start page's section (its folder and below), or anywhere. */
    boolean inScope(String link, URI root) {
        if ("any".equals(crawl.scope()) || root == null) {
            return true;
        }
        URI u = WebPageReader.toUri(link);
        if (u == null || u.getHost() == null || !u.getHost().equalsIgnoreCase(root.getHost())) {
            return false;
        }
        if ("section".equals(crawl.scope())) {
            String rootPath = root.getRawPath() == null ? "/" : root.getRawPath();
            String folder = rootPath.endsWith("/") ? rootPath : rootPath.substring(0, rootPath.lastIndexOf('/') + 1);
            String path = u.getRawPath() == null ? "/" : u.getRawPath();
            return path.startsWith(folder);
        }
        return true;
    }

    /** The same page however its address is written: scheme and host in lower case, no #fragment, no trailing slash. */
    private static String key(String link) {
        URI u = WebPageReader.toUri(link);
        if (u == null) {
            return link.strip().toLowerCase(Locale.ROOT);
        }
        String s = u.toString().split("#", 2)[0];
        return (u.getScheme() + "://" + u.getRawAuthority()).toLowerCase(Locale.ROOT) + s.substring((u.getScheme() + "://" + u.getRawAuthority()).length()).replaceAll("/+$", "");
    }

    public static Question toQuestion(String id, String link, WebPageReader.WebPage page) {
        String html = page.html() == null ? "" : page.html();
        String title = firstText(html, "(?is)<title[^>]*>(.*?)</title>");
        if (title == null || title.isBlank()) {
            title = firstText(html, "(?is)<h1[^>]*>(.*?)</h1>");
        }
        if (title == null || title.isBlank()) {
            title = link;
        }
        String content = mainPart(html);
        URI fin = WebPageReader.toUri(page.finalUrl());
        String host = fin == null || fin.getHost() == null ? "" : fin.getHost().toLowerCase(Locale.ROOT);
        boolean expanded = !sameAddress(link, page.finalUrl());
        String header = "<p><em>Read from <a href=\"" + escape(page.finalUrl()) + "\">" + escape(page.finalUrl()) + "</a>"
                + (expanded ? " (link: " + escape(link) + ")" : "") + ".</em></p>\n";
        List<String> tags = new ArrayList<>();
        tags.add("link");
        if (!host.isEmpty()) {
            tags.add("site:" + host);
        }
        if (expanded) {
            tags.add("short-link");
        }
        return new Question(id, com.questioncrawler.core.normalize.ContentNormalizer.plainTitle(title), header + content,
                BodyFormat.HTML, page.finalUrl(), tags, null, null, null, null, List.of(), List.of());
    }

    /** The page's main content when it marks it (main, article, role=main); otherwise the body. */
    static String mainPart(String html) {
        for (String re : new String[] {"(?is)<main[^>]*>(.*)</main>", "(?is)<article[^>]*>(.*)</article>",
                "(?is)<div[^>]+role\\s*=\\s*[\"']main[\"'][^>]*>(.*)</div>", "(?is)<body[^>]*>(.*)</body>"}) {
            String part = firstText(html, re);
            if (part != null && part.replaceAll("<[^>]+>", "").strip().length() > 40) {
                return part;
            }
        }
        return html;
    }

    private static String firstText(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static boolean sameAddress(String a, String b) {
        URI x = WebPageReader.toUri(a);
        URI y = WebPageReader.toUri(b);
        return x != null && y != null && x.toString().replaceAll("/+$", "").equalsIgnoreCase(y.toString().replaceAll("/+$", ""));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) {
        return new Page<>(List.of(), null);
    }

    @Override
    public String ping() throws SourceException {
        List<String> all = links();
        if (all.isEmpty()) {
            throw new SourceException(SourceException.Kind.NOT_CONFIGURED, "No links to read yet: add short links or page addresses under 'Links to read'.");
        }
        WebPageReader.WebPage page = reader.read(all.get(0));
        return all.size() + " link(s) to read; " + all.get(0) + " leads to " + page.finalUrl()
                + (page.signIn() == null ? "" : " (" + page.signIn() + ")");
    }

    /** Reads up to {@code max} links now, for the Check access screen: link -> what happened. */
    public Map<String, String> tryLinks(int max) {
        Map<String, String> out = new LinkedHashMap<>();
        reader.newRun();
        // only the pages you listed (not links found in collected items, not the pages they link to)
        for (String l : (configured.isEmpty() ? links() : configured).stream().limit(max).toList()) {
            try {
                WebPageReader.WebPage page = reader.read(l);
                Question q = toQuestion(idFor(l), l, page);
                out.put(l, "OK: read \"" + q.title() + "\" (" + page.finalUrl() + ")" + (page.signIn() == null ? "" : ", " + page.signIn()));
            } catch (SourceException e) {
                out.put(l, "NOT READ: " + e.getMessage());
            }
        }
        return out;
    }
}
