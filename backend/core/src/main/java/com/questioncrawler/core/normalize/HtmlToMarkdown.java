package com.questioncrawler.core.normalize;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free HTML to Markdown converter.
 *
 * <p>Goals: keep code (indentation, language hints, fences), links, lists, tables, quotes and
 * headings; drop page chrome (scripts, styles, navigation, footers, forms, ...). It is a pragmatic
 * converter for post bodies, not a general HTML renderer.
 */
public final class HtmlToMarkdown {

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    /** Page parts marked as navigation, banner, footer or search by their role: never content. */
    private static final Set<String> CHROME_ROLES = Set.of("navigation", "banner", "contentinfo", "search", "menubar", "toolbar");
    /**
     * Class names documentation sites use for sidebars, top bars, breadcrumbs and footers (Sphinx with Alabaster, Read
     * the Docs, Furo, PyData and Book themes; MkDocs Material; Docusaurus; Confluence and SharePoint page chrome).
     */
    private static final java.util.regex.Pattern CHROME_CLASS = java.util.regex.Pattern.compile(
            "(?i)(?:^|\\s)(sphinxsidebar|sphinxsidebarwrapper|related|footer|headerlink|wy-nav-side|wy-side-scroll|wy-nav-top|rst-versions|"
                    + "rst-footer-buttons|breadcrumbs|wy-breadcrumbs|sidebar-drawer|sidebar-container|toc-drawer|bd-sidebar|bd-sidebar-primary|"
                    + "bd-sidebar-secondary|bd-header|bd-footer|bd-toc|prev-next-area|prev-next-footer|header-article|navbar|md-sidebar|md-header|"
                    + "md-footer|md-nav|theme-doc-sidebar-container|pagination-nav|table-of-contents|skip-link|skip-to-content|cookie-banner|"
                    + "page-metadata|ia-secondary-container|ia-splitter-left|"
                    + "j-header-wrap|j-globalNav|j-global-nav|jive-header|j-footer|jive-footer|j-sidebar|jive-sidebar|j-breadcrumbs|"
                    + "jive-breadcrumb|j-tabbar|j-side-panel|j-action-bar|j-content-actions|jive-content-footer-actions)(?:\\s|$)");

    /** True for page chrome: navigation, sidebars, headers, footers and "link to this heading" anchors. */
    private static boolean isChrome(String tag, String attrs) {
        if (attrs == null || attrs.isEmpty() || VOID.contains(tag)) {
            return false;
        }
        String role = attr(attrs, "role");
        if (role != null && CHROME_ROLES.contains(role.strip().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String cls = attr(attrs, "class");
        return cls != null && CHROME_CLASS.matcher(cls).find();
    }

    private static final Set<String> SKIP = Set.of("script", "style", "nav", "footer", "aside", "form", "button", "svg", "noscript", "iframe", "select", "template", "head", "title");
    private static final Set<String> VOID = Set.of("br", "hr", "img", "input", "meta", "link", "wbr", "source");
    private static final Set<String> BLOCK = Set.of("p", "div", "section", "article", "main", "header", "figure", "figcaption", "dl", "dt", "dd");

    /** A tag or a piece of text. Text from a CDATA section has the name "#cdata" (kept exactly, not decoded). */
    private record Tok(boolean text, boolean closing, String name, String attrs, String content) {
        boolean selfClosing() {
            return attrs != null && attrs.strip().endsWith("/");
        }
    }

    private static final class ListCtx {
        final boolean ordered;
        int counter;

        ListCtx(boolean ordered) {
            this.ordered = ordered;
        }
    }

    private final List<Tok> tokens = new ArrayList<>();
    private final Deque<StringBuilder> buffers = new ArrayDeque<>();
    private final Deque<ListCtx> lists = new ArrayDeque<>();
    private final Deque<String> hrefs = new ArrayDeque<>();
    private final Deque<List<List<String>>> tables = new ArrayDeque<>();
    private final Deque<List<String>> rows = new ArrayDeque<>();
    private StringBuilder pre;
    private String preLang;
    /** The code language a wrapper announced (Sphinx: div.highlight-yaml), for the next code block. */
    private String pendingLang = "";
    /** Open div elements: true for a note / warning box (rendered as a quote with a bold title). */
    private final Deque<Boolean> divs = new ArrayDeque<>();
    /** Inside the title paragraph of a note box ("Note", "Warning"). */
    private boolean inBoxTitle;
    private int skipDepth;
    private String skipName;
    private boolean inInlineCode;
    private StringBuilder inlineCode;

    private HtmlToMarkdown() {
        buffers.push(new StringBuilder());
    }

    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return new HtmlToMarkdown().run(html);
    }

    // ------------------------------------------------------------------ driver

    private String run(String html) {
        tokenize(html);
        for (int i = 0; i < tokens.size(); i++) {
            Tok t = tokens.get(i);
            if (t.text()) {
                handleText(t.content(), "#cdata".equals(t.name()));
            } else if (skipDepth > 0) {
                trackSkip(t);
            } else if (t.closing()) {
                handleClose(t.name());
            } else {
                handleOpen(t, i);
            }
        }
        while (buffers.size() > 1) { // unterminated captures: flush what we have
            String captured = buffers.pop().toString();
            cur().append(captured);
        }
        if (pre != null) {
            emitPre();
        }
        return tidy(cur().toString());
    }

    /**
     * A tag may be this long when it looks like a tag (attributes such as name="..." right after its name): Jive and
     * Confluence pages carry megabytes of inline data in attributes. Anything else that does not end soon is text.
     */
    private static final int MAX_TAG_CHARS = 8_000_000;
    /** Without attributes near its start, a "<" whose ">" is further than this is ordinary text ("a<b" in code). */
    private static final int MAX_PLAIN_TAG_CHARS = 2_000;

    /**
     * Splits the page into tags and text in one pass, without regular expressions: any length of page, any length of
     * attribute (Jive and Confluence pages carry large inline data), and any number of stray "<" signs are read without
     * deep recursion (a regular expression here overflowed the stack on long pages).
     */
    private void tokenize(String html) {
        int n = html.length();
        int textStart = 0;
        int i = 0;
        int nextGt = -1;
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int end;
            Tok tag = null;
            if (html.startsWith("<!--", lt)) {
                int close = html.indexOf("-->", lt + 4);
                end = close < 0 ? n : close + 3; // a comment: dropped
            } else if (html.startsWith("<![CDATA[", lt)) {
                int close = html.indexOf("]]>", lt + 9); // Confluence code and link text: exactly as written
                end = close < 0 ? n : close + 3;
                tag = new Tok(true, false, "#cdata", null, html.substring(lt + 9, close < 0 ? n : close));
            } else if (lt + 1 < n && (html.charAt(lt + 1) == '!' || html.charAt(lt + 1) == '?')) {
                int close = html.indexOf('>', lt + 2); // <!DOCTYPE ...>, <![CDATA[...]]>, <?xml ...?>: dropped
                if (close < 0 || close - lt > MAX_TAG_CHARS) {
                    i = lt + 1;
                    continue;
                }
                end = close + 1;
            } else {
                int p = lt + 1;
                boolean closing = p < n && html.charAt(p) == '/';
                if (closing) {
                    p++;
                }
                int nameStart = p;
                if (p >= n || !isAsciiLetter(html.charAt(p))) {
                    i = lt + 1; // "<" followed by a space, a digit, "=": text
                    continue;
                }
                while (p < n && isNameChar(html.charAt(p))) { // also Confluence's ac:structured-macro, ri:page
                    p++;
                }
                String name = html.substring(nameStart, p).toLowerCase(Locale.ROOT);
                if (nextGt < p) {
                    nextGt = html.indexOf('>', p);
                }
                if (nextGt < 0) {
                    break; // no ">" anywhere after this: the rest of the page is text
                }
                int q;
                boolean looksLikeTag = p >= n || html.charAt(p) == '>' || html.charAt(p) == '/' || Character.isWhitespace(html.charAt(p));
                if (!looksLikeTag) {
                    i = lt + 1;
                    continue;
                }
                int quoteAt = -1;
                for (int k = p; k < nextGt && k < p + MAX_PLAIN_TAG_CHARS; k++) {
                    char c = html.charAt(k);
                    if (c == '"' || c == '\'') {
                        quoteAt = k;
                        break;
                    }
                }
                int eq = html.indexOf('=', p);
                boolean attributeNearStart = eq > p && eq < Math.min(nextGt, p + 200);
                if (quoteAt < 0 && (nextGt - p <= MAX_PLAIN_TAG_CHARS || (attributeNearStart && nextGt - p <= MAX_TAG_CHARS))) {
                    // the usual case: no quotes before the ">" (also a long unquoted attribute value); but a "<" in
                    // between means this was text ("if a<b then c<d</p>"), not a tag running up to the next ">"
                    int innerLt = html.indexOf('<', p);
                    if (innerLt >= 0 && innerLt < nextGt) {
                        i = lt + 1;
                        continue;
                    }
                    q = nextGt;
                } else if (quoteAt < 0 || html.lastIndexOf('=', quoteAt) < p) {
                    i = lt + 1; // a far ">" and no attribute near the start: not a tag
                    continue;
                } else {
                    // attributes with quoted values (which may contain ">" and be very long): skip over them
                    int limit = Math.min(n, p + MAX_TAG_CHARS);
                    q = p;
                    char quote = 0;
                    while (q < limit) {
                        char c = html.charAt(q);
                        if (quote != 0) {
                            int close = html.indexOf(quote, q);
                            if (close < 0 || close >= limit) {
                                q = limit;
                                break;
                            }
                            q = close + 1;
                            quote = 0;
                            continue;
                        }
                        if (c == '"' || c == '\'') {
                            quote = c;
                        } else if (c == '>') {
                            break;
                        }
                        q++;
                    }
                    if (q >= limit) {
                        i = lt + 1; // no end of tag: the "<" is text
                        continue;
                    }
                }
                tag = new Tok(false, closing, name, html.substring(p, q), null);
                end = q + 1;
            }
            if (lt > textStart) {
                tokens.add(new Tok(true, false, null, null, html.substring(textStart, lt)));
            }
            if (tag != null) {
                tokens.add(tag);
            }
            textStart = end;
            i = end;
        }
        if (textStart < n) {
            tokens.add(new Tok(true, false, null, null, html.substring(textStart)));
        }
    }

    private static boolean isNameChar(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == ':' || c == '-' || c == '_' || c == '.';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private StringBuilder cur() {
        return buffers.peek();
    }

    // ------------------------------------------------------------------ skipping chrome

    private void trackSkip(Tok t) {
        if (t.name().equals(skipName) && !VOID.contains(t.name()) && !(!t.closing() && t.selfClosing())) {
            skipDepth += t.closing() ? -1 : 1;
        }
    }

    // ------------------------------------------------------------------ text

    private void handleText(String raw, boolean exact) {
        if (skipDepth > 0) {
            return;
        }
        String text = exact ? raw : decode(raw);
        if (pre != null) {
            pre.append(text);
            return;
        }
        if (inInlineCode) {
            inlineCode.append(text);
            return;
        }
        String collapsed = text.replaceAll("\\s+", " ");
        StringBuilder out = cur();
        if (collapsed.equals(" ") && (out.length() == 0 || endsWithNewline(out))) {
            return;
        }
        if (out.length() == 0 || endsWithNewline(out)) {
            collapsed = collapsed.stripLeading();
        }
        out.append(collapsed);
    }

    // ------------------------------------------------------------------ open tags

    private void handleOpen(Tok t, int index) {
        String name = t.name();
        if (SKIP.contains(name) || isChrome(name, t.attrs())) {
            if (!t.selfClosing()) {
                skipName = name;
                skipDepth = 1;
            }
            return;
        }
        if (pre != null) {
            if (name.equals("br")) {
                pre.append('\n');
            }
            return; // markup inside <pre> is irrelevant; only its text matters
        }
        if (name.indexOf(':') > 0) {
            confluenceOpen(t, index);
            return;
        }
        switch (name) {
            case "pre" -> startPre(index);
            case "code", "kbd", "samp", "tt" -> {
                inInlineCode = true;
                inlineCode = new StringBuilder();
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blankLine();
                buffers.push(new StringBuilder());
                cur().append("#".repeat(name.charAt(1) - '0')).append(' ');
            }
            case "div" -> {
                String cls = attr(t.attrs(), "class");
                Matcher lang = CODE_LANG.matcher(cls);
                if (lang.find()) {
                    pendingLang = lang.group(1).toLowerCase(Locale.ROOT);
                }
                boolean box = NOTE_BOX.matcher(cls).find();
                divs.push(box);
                if (box) {
                    blankLine();
                    buffers.push(new StringBuilder()); // collected, then written as a quote
                } else {
                    blockBreak();
                }
            }
            case "p" -> {
                if (divs.contains(Boolean.TRUE) && ADMONITION_TITLE.matcher(attr(t.attrs(), "class")).find()) {
                    blockBreak();
                    inBoxTitle = true;
                    cur().append("**");
                } else {
                    blockBreak();
                }
            }
            case "dt" -> {
                blankLine();
                cur().append("**");
            }
            case "section", "article", "main", "header", "figure", "figcaption", "dl", "dd" -> blockBreak();
            case "br" -> {
                if (lists.isEmpty()) {
                    cur().append('\n');
                } else {
                    cur().append(' '); // a list item stays on its line
                }
            }
            case "hr" -> {
                blankLine();
                cur().append("---");
                blankLine();
            }
            case "ul", "ol" -> {
                if (lists.isEmpty()) {
                    blankLine();
                } else {
                    newline();
                }
                ListCtx ctx = new ListCtx(name.equals("ol"));
                try { // <ol start="5">: numbering continues from there
                    String start = attr(t.attrs(), "start");
                    if (ctx.ordered && start != null && !start.isBlank()) {
                        ctx.counter = Integer.parseInt(start.strip()) - 1;
                    }
                } catch (NumberFormatException ignored) {
                    // numbered from 1
                }
                lists.push(ctx);
            }
            case "li" -> {
                newline();
                ListCtx ctx = lists.peek();
                String indent = "  ".repeat(Math.max(0, lists.size() - 1));
                if (ctx != null && ctx.ordered) {
                    cur().append(indent).append(++ctx.counter).append(". ");
                } else {
                    cur().append(indent).append("- ");
                }
            }
            case "blockquote" -> {
                blankLine();
                buffers.push(new StringBuilder());
            }
            case "strong", "b" -> cur().append("**");
            case "em", "i" -> cur().append('*');
            case "del", "s", "strike" -> cur().append("~~");
            case "a" -> {
                hrefs.push(safeHref(attr(t.attrs(), "href")));
                buffers.push(new StringBuilder());
            }
            case "time" -> {
                String when = attr(t.attrs(), "datetime");
                if (!when.isBlank()) {
                    cur().append(when.strip());
                }
            }
            case "img" -> {
                String src = safeHref(attr(t.attrs(), "src"));
                String alt = decode(attr(t.attrs(), "alt"));
                if (!src.isEmpty()) {
                    cur().append("![").append(alt).append("](").append(src).append(')');
                }
            }
            case "table" -> {
                blankLine();
                tables.push(new ArrayList<>());
            }
            case "tr" -> rows.push(new ArrayList<>());
            case "td", "th" -> buffers.push(new StringBuilder());
            default -> { /* unknown tags: keep their text, drop the markup */ }
        }
    }

    // ------------------------------------------------------------------ Confluence storage format (ac:, ri:)

    /** A Confluence macro being read: its name and its parameters (language, title, ...). */
    private static final class Macro {
        final String name;
        final java.util.Map<String, String> params = new java.util.HashMap<>();

        Macro(String name) {
            this.name = name;
        }
    }

    /** A Confluence link being read: the page, file or address it points to. */
    private static final class Link {
        String title = "";
        String url = "";
    }

    /** Macros that are not content: anchors, tables of contents, page trees, Jira boxes, ... */
    private static final Set<String> SKIP_MACROS = Set.of("anchor", "toc", "toc-zone", "children", "pagetree", "pagetreesearch", "jira",
            "jiraissues", "attachments", "recently-updated", "livesearch", "contentbylabel", "content-report-table", "excerpt-include",
            "include", "gallery", "profile", "page-index", "space-details", "blog-posts", "related-labels", "popular-labels", "viewfile",
            "view-file", "multimedia", "widget", "iframe", "html", "roadmap", "chart", "create-from-template", "create-space-button");
    private static final Set<String> CODE_MACROS = Set.of("code", "noformat", "code-block", "sourcecode");
    private static final Set<String> BOX_MACROS = Set.of("info", "note", "warning", "tip", "panel", "tip-panel", "note-panel", "warning-panel", "callout");
    /** Confluence elements that carry no readable content. */
    private static final Set<String> SKIP_CONFLUENCE = Set.of("ac:image", "ac:emoticon", "ac:placeholder", "ac:task-id", "ac:task-uuid",
            "ac:task-status", "ri:user", "ac:adf-attribute", "ac:adf-fallback", "ac:inline-comment-marker-ref");

    private final Deque<Macro> macros = new ArrayDeque<>();
    private final Deque<String> paramNames = new ArrayDeque<>();
    private final Deque<Link> links = new ArrayDeque<>();

    private void confluenceOpen(Tok t, int index) {
        String name = t.name();
        boolean self = t.selfClosing();
        if (SKIP_CONFLUENCE.contains(name)) {
            if (!self) {
                skipName = name;
                skipDepth = 1;
            }
            return;
        }
        switch (name) {
            case "ac:structured-macro", "ac:macro" -> {
                String macro = attr(t.attrs(), "ac:name").strip().toLowerCase(Locale.ROOT);
                if (self) {
                    return; // a macro without content (toc, children, ...)
                }
                if (SKIP_MACROS.contains(macro)) {
                    skipName = name;
                    skipDepth = 1;
                    return;
                }
                macros.push(new Macro(macro));
                if (BOX_MACROS.contains(macro) || macro.equals("expand")) {
                    blankLine();
                    buffers.push(new StringBuilder());
                }
            }
            case "ac:parameter" -> {
                if (!self) {
                    paramNames.push(attr(t.attrs(), "ac:name").strip().toLowerCase(Locale.ROOT));
                    buffers.push(new StringBuilder()); // a setting of the macro, never page text
                }
            }
            case "ac:plain-text-body" -> {
                Macro m = macros.peek();
                if (m != null && CODE_MACROS.contains(m.name)) {
                    pre = new StringBuilder();
                    String lang = m.params.getOrDefault("language", "").strip().toLowerCase(Locale.ROOT);
                    preLang = lang.equals("none") || lang.equals("text") ? "" : lang;
                } else if (!self) {
                    skipName = name; // the body of an unknown macro (html, ...): not shown
                    skipDepth = 1;
                }
            }
            case "ac:link" -> {
                if (!self) {
                    links.push(new Link());
                    buffers.push(new StringBuilder());
                }
            }
            case "ri:page", "ri:blog-post" -> {
                Link l = links.peek();
                if (l != null) {
                    l.title = attr(t.attrs(), "ri:content-title");
                }
            }
            case "ri:attachment" -> {
                Link l = links.peek();
                if (l != null) {
                    l.title = attr(t.attrs(), "ri:filename");
                }
            }
            case "ri:url" -> {
                Link l = links.peek();
                if (l != null) {
                    l.url = safeHref(attr(t.attrs(), "ri:value"));
                }
            }
            case "ri:space" -> {
                Link l = links.peek();
                if (l != null && l.title.isEmpty()) {
                    l.title = attr(t.attrs(), "ri:space-key");
                }
            }
            case "ac:task-list" -> handleOpen(new Tok(false, false, "ul", "", null), index);
            case "ac:task" -> handleOpen(new Tok(false, false, "li", "", null), index);
            case "ac:rich-text-body", "ac:layout", "ac:layout-section", "ac:layout-cell" -> blockBreak();
            default -> { /* ac:task-body, ac:link-body, ac:plain-text-link-body, ac:inline-comment-marker, ...: their text is kept */ }
        }
    }

    private void confluenceClose(String name) {
        switch (name) {
            case "ac:structured-macro", "ac:macro" -> {
                Macro m = macros.poll();
                if (m == null) {
                    return;
                }
                if ((BOX_MACROS.contains(m.name) || m.name.equals("expand")) && buffers.size() > 1) {
                    String body = tidy(buffers.pop().toString());
                    String title = m.params.getOrDefault("title", "").strip();
                    StringBuilder out = cur();
                    blankLine();
                    if (m.name.equals("expand")) {
                        out.append("**").append(title.isEmpty() ? "Details" : title).append("**\n\n").append(body);
                    } else {
                        String label = !title.isEmpty() ? title : switch (m.name) {
                            case "info" -> "Info";
                            case "note", "note-panel" -> "Note";
                            case "warning", "warning-panel" -> "Warning";
                            case "tip", "tip-panel" -> "Tip";
                            default -> "";
                        };
                        String quoted = label.isEmpty() ? body : "**" + label + ":**\n\n" + body;
                        for (String line : quoted.split("\n", -1)) {
                            out.append(line.isBlank() ? ">" : "> " + line).append('\n');
                        }
                    }
                    blankLine();
                } else if (m.name.equals("status")) {
                    String label = m.params.getOrDefault("title", "").strip();
                    if (!label.isEmpty()) {
                        cur().append("**[").append(label).append("]**");
                    }
                }
            }
            case "ac:parameter" -> {
                if (buffers.size() > 1 && !paramNames.isEmpty()) {
                    String value = buffers.pop().toString();
                    String param = paramNames.pop();
                    Macro m = macros.peek();
                    if (m != null) {
                        m.params.put(param, value.strip());
                    }
                }
            }
            case "ac:link" -> {
                if (buffers.size() > 1 && !links.isEmpty()) {
                    String body = buffers.pop().toString().strip();
                    Link l = links.pop();
                    String text = !body.isEmpty() ? body : l.title;
                    if (!l.url.isEmpty() && !text.isEmpty()) {
                        cur().append('[').append(text.replace("[", "(").replace("]", ")")).append("](").append(l.url).append(')');
                    } else {
                        cur().append(text);
                    }
                }
            }
            case "ac:task-list" -> handleClose("ul");
            case "ac:task" -> handleClose("li");
            case "ac:rich-text-body", "ac:layout", "ac:layout-section", "ac:layout-cell" -> blockBreak();
            default -> { }
        }
    }

    // ------------------------------------------------------------------ close tags

    private void handleClose(String name) {
        if (pre != null) {
            if (name.equals("pre") || name.equals("ac:plain-text-body")) {
                emitPre();
            }
            return;
        }
        if (name.indexOf(':') > 0) {
            confluenceClose(name);
            return;
        }
        switch (name) {
            case "code", "kbd", "samp", "tt" -> {
                if (inInlineCode) {
                    inInlineCode = false;
                    emitInlineCode(inlineCode.toString());
                }
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                if (buffers.size() > 1) {
                    String heading = buffers.pop().toString().replaceAll("\\s+", " ").stripTrailing();
                    cur().append(heading);
                    blankLine();
                }
            }
            case "div" -> {
                boolean box = !divs.isEmpty() && divs.pop();
                if (box && buffers.size() > 1) {
                    String quoted = tidy(buffers.pop().toString());
                    StringBuilder out = cur();
                    for (String line : quoted.split("\n", -1)) {
                        out.append(line.isBlank() ? ">" : "> " + line).append('\n');
                    }
                    blankLine();
                } else {
                    blockBreak();
                }
            }
            case "p" -> {
                if (inBoxTitle) {
                    inBoxTitle = false;
                    StringBuilder out = cur();
                    stripTrailingSpaces(out);
                    out.append(":** "); // "**Note:** the text follows"
                } else {
                    blockBreak();
                }
            }
            case "dt" -> {
                StringBuilder out = cur();
                stripTrailingSpaces(out);
                if (out.length() >= 2 && out.substring(out.length() - 2).equals("**")) {
                    out.setLength(out.length() - 2); // an empty term
                } else {
                    out.append("**");
                }
                newline();
            }
            case "section", "article", "main", "header", "figure", "figcaption", "dl", "dd" -> blockBreak();
            case "ul", "ol" -> {
                if (!lists.isEmpty()) {
                    lists.pop();
                }
                if (lists.isEmpty()) {
                    blankLine();
                } else {
                    newline();
                }
            }
            case "li" -> newline();
            case "blockquote" -> {
                if (buffers.size() > 1) {
                    String quoted = buffers.pop().toString();
                    StringBuilder out = cur();
                    for (String line : tidy(quoted).split("\n", -1)) {
                        out.append("> ").append(line).append('\n');
                    }
                    blankLine();
                }
            }
            case "strong", "b" -> cur().append("**");
            case "em", "i" -> cur().append('*');
            case "del", "s", "strike" -> cur().append("~~");
            case "a" -> {
                if (buffers.size() > 1 && !hrefs.isEmpty()) {
                    String text = buffers.pop().toString();
                    String href = hrefs.pop();
                    String label = text.strip();
                    // The ¶ / # / § "link to this heading" marks of documentation sites are not content.
                    boolean markOnly = label.isEmpty() || PERMALINK_MARK.matcher(label).matches();
                    if (markOnly && (href.isEmpty() || href.startsWith("#"))) {
                        // dropped
                    } else if (href.isEmpty() || href.startsWith("#")) {
                        // no address, or a jump within the page (Sphinx headings link back to the contents): just the text
                        cur().append(text);
                    } else if (label.isEmpty()) {
                        cur().append('<').append(href).append('>');
                    } else {
                        cur().append('[').append(label.replace("[", "(").replace("]", ")")).append("](").append(href).append(')');
                    }
                }
            }
            case "td", "th" -> {
                if (buffers.size() > 1 && !rows.isEmpty()) {
                    String cell = buffers.pop().toString().replaceAll("\\s+", " ").strip().replace("|", "\\|");
                    rows.peek().add(cell);
                }
            }
            case "tr" -> {
                if (!rows.isEmpty() && !tables.isEmpty()) {
                    tables.peek().add(rows.pop());
                }
            }
            case "table" -> {
                if (!tables.isEmpty()) {
                    emitTable(tables.pop());
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ emitters

    private void startPre(int index) {
        pre = new StringBuilder();
        preLang = pendingLang.equals("default") || pendingLang.equals("none") || pendingLang.equals("text") ? "" : pendingLang;
        pendingLang = "";
        for (int j = index + 1; j < tokens.size(); j++) {
            Tok next = tokens.get(j);
            if (next.text() && next.content().isBlank()) {
                continue;
            }
            if (!next.text() && !next.closing() && next.name().equals("code")) {
                String cls = attr(next.attrs(), "class");
                Matcher m = Pattern.compile("(?:language|lang)-([A-Za-z0-9+#_.-]+)").matcher(cls);
                if (m.find()) {
                    preLang = m.group(1);
                }
            }
            break;
        }
    }

    private void emitPre() {
        String code = pre.toString().replace("\r\n", "\n");
        pre = null;
        if (code.startsWith("\n")) {
            code = code.substring(1); // HTML drops a newline directly after <pre>
        }
        code = code.stripTrailing();
        int longestRun = 0;
        Matcher m = Pattern.compile("`+").matcher(code);
        while (m.find()) {
            longestRun = Math.max(longestRun, m.group().length());
        }
        String fence = "`".repeat(Math.max(3, longestRun + 1));
        blankLine();
        cur().append(fence).append(preLang).append('\n').append(code).append('\n').append(fence);
        blankLine();
    }

    private void emitInlineCode(String code) {
        if (code.isEmpty()) {
            return;
        }
        if (code.contains("\n")) { // multi-line <code> outside <pre>: treat as a block
            blankLine();
            cur().append("```\n").append(code.strip()).append("\n```");
            blankLine();
            return;
        }
        int longestRun = 0;
        Matcher m = Pattern.compile("`+").matcher(code);
        while (m.find()) {
            longestRun = Math.max(longestRun, m.group().length());
        }
        String tick = "`".repeat(longestRun + 1);
        String pad = code.startsWith("`") || code.endsWith("`") ? " " : "";
        cur().append(tick).append(pad).append(code).append(pad).append(tick);
    }

    private void emitTable(List<List<String>> table) {
        if (table.isEmpty()) {
            return;
        }
        int columns = table.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return;
        }
        StringBuilder out = cur();
        blankLine();
        for (int r = 0; r < table.size(); r++) {
            List<String> row = new ArrayList<>(table.get(r));
            while (row.size() < columns) {
                row.add("");
            }
            out.append("| ").append(String.join(" | ", row)).append(" |\n");
            if (r == 0) {
                out.append("|").append(" --- |".repeat(columns)).append('\n');
            }
        }
        blankLine();
    }

    // ------------------------------------------------------------------ spacing helpers

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n';
    }

    /**
     * A paragraph or block boundary. Outside lists: a blank line. Inside a list item: nothing right after the bullet or
     * number, otherwise a space, so the item stays one line under its bullet (Sphinx, Confluence and Jive put
     * paragraphs inside list items; a line break there would separate the text from its number).
     */
    private void blockBreak() {
        if (lists.isEmpty()) {
            blankLine();
            return;
        }
        StringBuilder out = cur();
        stripTrailingSpaces(out);
        if (out.length() == 0 || endsWithNewline(out) || LIST_MARKER_END.matcher(out.subSequence(Math.max(0, out.length() - 12), out.length())).find()) {
            if (out.length() > 0 && !endsWithNewline(out)) {
                out.append(' ');
            }
            return;
        }
        out.append(' ');
    }

    private static final Pattern LIST_MARKER_END = Pattern.compile("(?:^|\\s)(?:-|\\d+\\.)$");
    private static final Pattern CODE_LANG = Pattern.compile("(?:^|\\s)(?:highlight|language|lang)-([A-Za-z0-9+#_.-]+)");
    private static final Pattern NOTE_BOX = Pattern.compile("(?i)(?:^|\\s)(admonition|confluence-information-macro|aui-message|"
            + "panel-note|callout|alert)(?:\\s|$)");
    private static final Pattern ADMONITION_TITLE = Pattern.compile("(?i)(?:^|\\s)(admonition-title|confluence-information-macro-title|title)(?:\\s|$)");

    private void newline() {
        StringBuilder out = cur();
        stripTrailingSpaces(out);
        if (out.length() > 0 && !endsWithNewline(out)) {
            out.append('\n');
        }
    }

    private void blankLine() {
        StringBuilder out = cur();
        stripTrailingSpaces(out);
        if (out.length() == 0) {
            return;
        }
        while (!(out.length() >= 2 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n')) {
            out.append('\n');
        }
    }

    private static void stripTrailingSpaces(StringBuilder out) {
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == ' ' || out.charAt(end - 1) == '\t')) {
            end--;
        }
        out.setLength(end);
    }

    /** Collapses runs of blank lines (never inside fenced code) and trims the result. */
    static String tidy(String markdown) {
        StringBuilder result = new StringBuilder();
        boolean inFence = false;
        String fenceMarker = "";
        int blank = 0;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (!inFence && trimmed.startsWith("```")) {
                inFence = true;
                fenceMarker = trimmed.substring(0, trimmed.length() - trimmed.replace("`", "").length());
                if (fenceMarker.length() < 3) {
                    fenceMarker = "```";
                }
            } else if (inFence && trimmed.startsWith(fenceMarker) && trimmed.stripTrailing().replace("`", "").isEmpty()) {
                inFence = false;
            }
            if (inFence || trimmed.startsWith("```")) {
                result.append(line).append('\n');
                blank = 0;
                continue;
            }
            String stripped = line.stripTrailing();
            if (stripped.isEmpty()) {
                if (++blank <= 1) {
                    result.append('\n');
                }
            } else {
                blank = 0;
                result.append(stripped).append('\n');
            }
        }
        return result.toString().strip();
    }

    // ------------------------------------------------------------------ attribute / entity helpers

    static String attr(String attrs, String wanted) {
        if (attrs == null) {
            return "";
        }
        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(wanted)) {
                String v = m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
                return v == null ? "" : v;
            }
        }
        return "";
    }

    /** Only http(s), mailto and relative links survive; javascript:/data: and friends are dropped. */
    private static String safeHref(String raw) {
        String href = decode(raw).strip();
        if (href.isEmpty()) {
            return "";
        }
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")
                || lower.startsWith("/") || lower.startsWith("#") || !lower.contains(":")) {
            return href.replace(" ", "%20").replace(")", "%29");
        }
        return "";
    }

    private static final Pattern PERMALINK_MARK = Pattern.compile("[\\s¶§#🔗⚓\u00a7\u00b6]+|link|permalink|anchor", Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    static String decode(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            out.append(entity(m.group(1), m.group()));
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String entity(String body, String original) {
        try {
            if (body.startsWith("#x") || body.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(body.substring(2), 16)));
            }
            if (body.startsWith("#")) {
                int cp = Integer.parseInt(body.substring(1));
                if (cp >= 0x80 && cp <= 0x9F) { // old pages write Windows-1252 numbers: &#146; is ’
                    return new String(new byte[] {(byte) cp}, java.nio.charset.Charset.forName("windows-1252"));
                }
                return new String(Character.toChars(cp));
            }
        } catch (IllegalArgumentException e) {
            return original;
        }
        if (body.equals("nbsp")) {
            return " ";
        }
        String named = HtmlEntities.BY_NAME.get(body);
        if (named == null) {
            named = HtmlEntities.BY_NAME.get(body.toLowerCase(java.util.Locale.ROOT));
        }
        return named == null ? original : named;
    }
}
