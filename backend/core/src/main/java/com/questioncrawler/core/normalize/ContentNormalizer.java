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
            return cleanText(HtmlToMarkdown.convert(text));
        }
        return cleanMarkdown(cleanText(text));
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
