package com.questioncrawler.core.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a page (normalized Markdown) into sections and passages, so a question can be answered by the one paragraph or
 * section that holds the answer instead of a whole long page.
 *
 * <ul>
 *   <li>Sections start at headings ({@code #} to {@code ######}); each keeps its heading path (Install › Windows › Proxy).</li>
 *   <li>A long section is cut at paragraph boundaries into passages of about {@value #TARGET_CHARS} characters, the last
 *       short paragraph repeated at the start of the next passage so an answer is not split in two.</li>
 *   <li>Code blocks, tables and lists are never cut in the middle (unless one alone is huge).</li>
 * </ul>
 *
 * Deterministic and stateless; the stored page is never changed (this only shapes the search index).
 */
public final class PageSections {

    /** Sections up to this size stay whole. */
    static final int MAX_CHARS = 1800;
    /** Size aimed at when a long section is cut into passages. */
    static final int TARGET_CHARS = 1200;
    /** A paragraph up to this size is repeated at the start of the next passage. */
    static final int OVERLAP_MAX = 320;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** One searchable piece of a page. {@code headings} is the path from the top heading to this section's own heading. */
    public record Section(int index, List<String> headings, String text) {
        public String path() {
            return String.join(" › ", headings);
        }

        /** The section's own (deepest) heading, or null for text before the first heading. */
        public String heading() {
            return headings.isEmpty() ? null : headings.get(headings.size() - 1);
        }
    }

    private PageSections() {}

    public static List<Section> split(String markdown) {
        List<Section> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            out.add(new Section(0, List.of(), ""));
            return out;
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        List<String> stack = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<String> current = new ArrayList<>();
        List<String> currentHeadings = List.of();
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                current.add(line);
                continue;
            }
            Matcher m = inFence ? null : HEADING.matcher(trimmed);
            if (m != null && m.matches()) {
                emit(out, currentHeadings, current);
                current = new ArrayList<>();
                int level = m.group(1).length();
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    stack.remove(stack.size() - 1);
                }
                levels.add(level);
                stack.add(clean(m.group(2)));
                currentHeadings = List.copyOf(stack);
                continue;
            }
            current.add(line);
        }
        emit(out, currentHeadings, current);
        if (out.isEmpty()) {
            out.add(new Section(0, List.of(), markdown.strip()));
        }
        return out;
    }

    private static void emit(List<Section> out, List<String> headings, List<String> lines) {
        String text = String.join("\n", lines).strip();
        if (text.isEmpty()) {
            return; // a heading directly followed by a sub-heading: its path lives on in the sub-section
        }
        if (text.length() <= MAX_CHARS) {
            out.add(new Section(out.size(), headings, text));
            return;
        }
        List<String> blocks = blocks(text);
        StringBuilder chunk = new StringBuilder();
        String last = null;
        for (String block : blocks) {
            for (String piece : block.length() > MAX_CHARS ? hardSplit(block) : List.of(block)) {
                if (chunk.length() > 0 && chunk.length() + piece.length() + 2 > TARGET_CHARS) {
                    out.add(new Section(out.size(), headings, chunk.toString().strip()));
                    chunk.setLength(0);
                    if (last != null && last.length() <= OVERLAP_MAX && !isFence(last)) {
                        chunk.append(last).append("\n\n");
                    }
                }
                chunk.append(piece).append("\n\n");
                last = piece;
            }
        }
        if (!chunk.toString().isBlank()) {
            out.add(new Section(out.size(), headings, chunk.toString().strip()));
        }
    }

    /** Paragraphs separated by blank lines; a fenced code block is one block even when it contains blank lines. */
    static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            String t = line.stripLeading();
            boolean fenceLine = t.startsWith("```") || t.startsWith("~~~");
            if (!inFence && line.isBlank()) {
                if (cur.length() > 0) {
                    blocks.add(cur.toString().stripTrailing());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(line).append('\n');
            if (fenceLine) {
                inFence = !inFence;
                if (!inFence) {
                    blocks.add(cur.toString().stripTrailing());
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() > 0) {
            blocks.add(cur.toString().stripTrailing());
        }
        return blocks;
    }

    /** A single huge block (a long table, list or code listing) is cut at line boundaries. */
    private static List<String> hardSplit(String block) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean fence = isFence(block);
        String opener = fence ? block.lines().findFirst().orElse("```") : null;
        for (String line : block.split("\n", -1)) {
            if (cur.length() + line.length() + 1 > TARGET_CHARS && cur.length() > 0) {
                parts.add(fence && !cur.toString().stripTrailing().endsWith("```") ? cur + "```" : cur.toString().stripTrailing());
                cur.setLength(0);
                if (fence) {
                    cur.append(opener.strip()).append('\n');
                }
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            parts.add(cur.toString().stripTrailing());
        }
        return parts;
    }

    private static boolean isFence(String block) {
        String t = block.stripLeading();
        return t.startsWith("```") || t.startsWith("~~~");
    }

    /** Heading text without Markdown emphasis, links and anchors. */
    static String clean(String heading) {
        return heading.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1").replaceAll("[*_`]", "").replaceAll("\\s+", " ").strip();
    }
}
