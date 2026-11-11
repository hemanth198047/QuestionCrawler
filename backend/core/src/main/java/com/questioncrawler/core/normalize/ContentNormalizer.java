package com.questioncrawler.core.normalize;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import java.util.List;

/**
 * Converts raw source bodies (HTML, Markdown or plain text) into clean Markdown. The result is the
 * "original normalized content": code blocks, indentation, links, lists and tables are kept;
 * navigation and page chrome are removed. Nothing is summarized or rewritten.
 */
public final class ContentNormalizer {

    private ContentNormalizer() {}

    public static String toMarkdown(String body, BodyFormat format) {
        if (body == null) {
            return "";
        }
        String text = body.replace("\uFEFF", "");
        if (format == BodyFormat.HTML) {
            return cleanText(convertSafely(text));
        }
        return cleanMarkdown(cleanText(text));
    }

    /** Pieces of a very large or unusual page, converted one by one when the page as a whole cannot be. */
    static final int CHUNK_CHARS = 100_000;

    /**
     * Converts a page; a page that cannot be converted in one go (exceptionally deep or unusual markup) is converted in
     * chunks of about {@value #CHUNK_CHARS} characters, cut where a tag starts; and if even that fails, its plain text is
     * kept. A single page never stops a collection.
     */
    static String convertSafely(String html) {
        try {
            return HtmlToMarkdown.convert(html);
        } catch (StackOverflowError | RuntimeException whole) {
            try {
                StringBuilder out = new StringBuilder();
                for (String piece : chunks(html, CHUNK_CHARS)) {
                    String md = HtmlToMarkdown.convert(piece);
                    if (!md.isBlank()) {
                        out.append(md.strip()).append("\n\n");
                    }
                }
                return out.toString().strip();
            } catch (StackOverflowError | RuntimeException inChunks) {
                return plainText(html);
            }
        }
    }

    /** Cuts HTML into pieces of about {@code size} characters, each starting at a block tag (or at least a tag). */
    static java.util.List<String> chunks(String html, int size) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        while (start < html.length()) {
            int target = start + size;
            if (target >= html.length()) {
                out.add(html.substring(start));
                break;
            }
            int cut = -1;
            for (String block : new String[] {"<p", "<div", "<li", "<h1", "<h2", "<h3", "<h4", "<tr", "<pre", "<table", "<section"}) {
                int at = html.indexOf(block, target);
                if (at > 0 && at < target + size / 2 && (cut < 0 || at < cut)) {
                    cut = at;
                }
            }
            if (cut < 0) {
                cut = html.indexOf('<', target);
            }
            if (cut < 0 || cut <= start) {
                cut = Math.min(html.length(), target);
            }
            out.add(html.substring(start, cut));
            start = cut;
        }
        return out;
    }

    /** The text of a page without any markup: the last resort. */
    static String plainText(String html) {
        StringBuilder out = new StringBuilder(Math.min(html.length(), 1 << 20));
        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c == '<') {
                int close = html.indexOf('>', i);
                if (close > 0 && close - i < 100_000) {
                    String tag = html.substring(i + 1, Math.min(close, i + 12)).toLowerCase(java.util.Locale.ROOT);
                    if (tag.startsWith("p") || tag.startsWith("/p") || tag.startsWith("br") || tag.startsWith("div") || tag.startsWith("/div")
                            || tag.startsWith("li") || tag.startsWith("h") || tag.startsWith("/h") || tag.startsWith("tr")) {
                        out.append('\n');
                    }
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return cleanMarkdown(HtmlToMarkdown.decode(out.toString()).replaceAll("[ \t\u00A0]+", " ").replaceAll("\n\s*\n+", "\n\n"));
    }

    private static final java.util.regex.Pattern INVISIBLE = java.util.regex.Pattern.compile("[\u200B-\u200D\u2060\uFEFF\u00AD\u2028\u2029\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]");
    private static final java.util.regex.Pattern HEADING_MARK = java.util.regex.Pattern.compile("(?m)^(#{1,6} .*?)[ \t]*[¶§⚓🔗]+[ \t]*$");
    /** Text that was UTF-8 but read as Windows-1252 ("donâ€™t", "Ã©"): one lead character and 1-3 continuation characters. */
    private static final java.util.regex.Pattern MOJIBAKE = java.util.regex.Pattern.compile(
            "[\u00C2-\u00F4][\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178\u017D\u017E\u0192\u02C6\u02DC\u2013\u2014\u2018-\u201A\u201C-\u201E\u2020-\u2022\u2026\u2030\u2039\u203A\u20AC\u2122]{1,3}");

    /**
     * Removes what should never be shown: invisible characters (zero-width spaces, soft hyphens, control characters),
     * non-breaking spaces (become plain spaces), garbled characters from a wrong character set ("donâ€™t" becomes
     * "don’t"), and the ¶ / § marks documentation sites put after headings.
     */
    public static String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String t = repairMojibake(text);
        t = INVISIBLE.matcher(t).replaceAll("").replace('\u00A0', ' ').replace('\u202F', ' ').replace("\uFFFD", "");
        return HEADING_MARK.matcher(t).replaceAll("$1");
    }

    static String repairMojibake(String text) {
        java.util.regex.Matcher m = MOJIBAKE.matcher(text);
        if (!m.find()) {
            return text;
        }
        m.reset();
        StringBuilder out = new StringBuilder();
        int last = 0;
        java.nio.charset.Charset cp1252 = java.nio.charset.Charset.forName("windows-1252");
        while (m.find()) {
            out.append(text, last, m.start());
            String bad = m.group();
            String fixed = null;
            byte[] bytes = new byte[bad.length()];
            boolean ok = true;
            for (int i = 0; i < bad.length() && ok; i++) {
                char c = bad.charAt(i);
                if (c <= 0xFF) {
                    bytes[i] = (byte) c;
                } else {
                    byte[] b = String.valueOf(c).getBytes(cp1252);
                    ok = b.length == 1 && b[0] != '?';
                    bytes[i] = ok ? b[0] : 0;
                }
            }
            if (ok) {
                try {
                    fixed = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                } catch (java.nio.charset.CharacterCodingException e) {
                    fixed = null;
                }
            }
            out.append(fixed != null && !fixed.isEmpty() ? fixed : bad);
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** Titles are plain text on most sites but often arrive HTML-escaped. */
    public static String plainTitle(String title) {
        if (title == null) {
            return "";
        }
        return cleanText(HtmlToMarkdown.decode(title.replaceAll("<[^>]+>", ""))).replaceAll("[¶§]+\\s*$", "").replaceAll("\\s+", " ").strip();
    }

    public static Question normalize(Question raw) {
        BodyFormat format = raw.bodyFormat();
        List<Comment> comments = normalizeComments(raw.comments(), format);
        List<Answer> answers = raw.answers().stream()
                .map(a -> a.withBody(toMarkdown(a.body(), a.bodyFormat()), BodyFormat.MARKDOWN, normalizeComments(a.comments(), a.bodyFormat())))
                .toList();
        Question normalized = raw.withContent(toMarkdown(raw.body(), format), BodyFormat.MARKDOWN, comments, answers);
        return new Question(normalized.id(), plainTitle(normalized.title()), normalized.body(), BodyFormat.MARKDOWN,
                normalized.url(), normalized.tags(), normalized.author(), normalized.score(), normalized.createdAt(),
                normalized.modifiedAt(), normalized.comments(), normalized.answers());
    }

    private static List<Comment> normalizeComments(List<Comment> comments, BodyFormat format) {
        return comments.stream()
                .map(c -> new Comment(c.id(), c.author(), toMarkdown(c.body(), format), c.createdAt()))
                .toList();
    }

    /** Normalizes line endings and strips trailing spaces outside code fences; indentation is kept. */
    static String cleanMarkdown(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        int blank = 0;
        for (String line : unified.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                blank = 0;
                out.append(line.stripTrailing()).append('\n');
            } else if (inFence) {
                out.append(line).append('\n');
            } else if (line.isBlank()) {
                if (++blank <= 1) {
                    out.append('\n');
                }
            } else {
                blank = 0;
                out.append(line.stripTrailing()).append('\n');
            }
        }
        return out.toString().strip();
    }
}
