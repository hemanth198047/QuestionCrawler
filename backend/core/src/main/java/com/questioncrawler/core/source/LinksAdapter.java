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

    public LinksAdapter(String systemId, List<String> links, boolean fromCollected, Path threadsDir, ShortLinks shortLinks, WebPageReader reader) {
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
        WebPageReader.WebPage page = reader.read(link);
        return toQuestion(id, link, page);
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
        for (String l : links().stream().limit(max).toList()) {
            try {
                WebPageReader.WebPage page = reader.read(l);
                Question q = toQuestion(idFor(l), l, page);
                out.put(l, "OK: leads to " + page.finalUrl() + " - \"" + q.title() + "\"" + (page.signIn() == null ? "" : " (" + page.signIn() + ")"));
            } catch (SourceException e) {
                out.put(l, "FAILED: " + e.getMessage());
            }
        }
        return out;
    }
}
