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
            return HtmlToMarkdown.convert(text);
        }
        return cleanMarkdown(text);
    }

    /** Titles are plain text on most sites but often arrive HTML-escaped. */
    public static String plainTitle(String title) {
        if (title == null) {
            return "";
        }
        return HtmlToMarkdown.decode(title.replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").strip();
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
