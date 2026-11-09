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

    private static final Pattern TOKEN = Pattern.compile("<!--.*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>", Pattern.DOTALL);
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

    private static final Set<String> SKIP = Set.of("script", "style", "nav", "footer", "aside", "form", "button", "svg", "noscript", "iframe", "select", "template");
    private static final Set<String> VOID = Set.of("br", "hr", "img", "input", "meta", "link", "wbr", "source");
    private static final Set<String> BLOCK = Set.of("p", "div", "section", "article", "main", "header", "figure", "figcaption", "dl", "dt", "dd");

    private record Tok(boolean text, boolean closing, String name, String attrs, String content) {}

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
                handleText(t.content());
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

    private void tokenize(String html) {
        Matcher m = TOKEN.matcher(html);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                tokens.add(new Tok(true, false, null, null, html.substring(last, m.start())));
            }
            last = m.end();
            if (m.group(2) == null) {
                continue; // comment
            }
            tokens.add(new Tok(false, !m.group(1).isEmpty(), m.group(2).toLowerCase(Locale.ROOT), m.group(3), null));
        }
        if (last < html.length()) {
            tokens.add(new Tok(true, false, null, null, html.substring(last)));
        }
    }

    private StringBuilder cur() {
        return buffers.peek();
    }

    // ------------------------------------------------------------------ skipping chrome

    private void trackSkip(Tok t) {
        if (t.name().equals(skipName) && !VOID.contains(t.name())) {
            skipDepth += t.closing() ? -1 : 1;
        }
    }

    // ------------------------------------------------------------------ text

    private void handleText(String raw) {
        if (skipDepth > 0) {
            return;
        }
        String text = decode(raw);
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
            skipName = name;
            skipDepth = 1;
            return;
        }
        if (pre != null) {
            if (name.equals("br")) {
                pre.append('\n');
            }
            return; // markup inside <pre> is irrelevant; only its text matters
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
            case "p", "div", "section", "article", "main", "header", "figure", "figcaption", "dl", "dt", "dd" -> {
                if (lists.isEmpty()) {
                    blankLine();
                } else {
                    newline();
                }
            }
            case "br" -> cur().append('\n');
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
                lists.push(new ListCtx(name.equals("ol")));
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

    // ------------------------------------------------------------------ close tags

    private void handleClose(String name) {
        if (pre != null) {
            if (name.equals("pre")) {
                emitPre();
            }
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
            case "p", "div", "section", "article", "main", "header", "figure", "figcaption", "dl", "dt", "dd" -> {
                if (lists.isEmpty()) {
                    blankLine();
                } else {
                    newline();
                }
            }
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
        preLang = "";
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
