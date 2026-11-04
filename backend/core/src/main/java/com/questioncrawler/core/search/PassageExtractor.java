package com.questioncrawler.core.search;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks a bounded passage from a long post: the lines that mention the most query terms plus
 * surrounding context. Works line by line so code is never cut mid-line, and repairs an unbalanced
 * code fence so the passage still renders correctly.
 */
public final class PassageExtractor {

    private PassageExtractor() {}

    public static String extract(String text, List<SymbolTokens.Word> queryWords, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text.strip();
        }
        String[] lines = text.split("\n", -1);
        Set<String> terms = new HashSet<>();
        for (SymbolTokens.Word w : queryWords) {
            terms.add(w.whole());
            terms.addAll(w.parts());
        }
        int bestLine = 0;
        int bestScore = -1;
        for (int i = 0; i < lines.length; i++) {
            int score = 0;
            Set<String> lineTokens = new HashSet<>(SymbolTokens.expand(lines[i]));
            for (String t : terms) {
                if (lineTokens.contains(t)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestLine = i;
            }
        }
        int start = bestLine;
        int end = bestLine; // inclusive
        int used = Math.min(lines[bestLine].length(), maxChars);
        boolean grew = true;
        while (grew) {
            grew = false;
            if (start > 0 && used + lines[start - 1].length() + 1 <= maxChars) {
                start--;
                used += lines[start].length() + 1;
                grew = true;
            }
            if (end < lines.length - 1 && used + lines[end + 1].length() + 1 <= maxChars) {
                end++;
                used += lines[end].length() + 1;
                grew = true;
            }
        }
        StringBuilder out = new StringBuilder();
        if (start > 0) {
            out.append("...\n");
        }
        StringBuilder body = new StringBuilder();
        for (int i = start; i <= end; i++) {
            body.append(i == bestLine && lines[i].length() > maxChars ? lines[i].substring(0, maxChars) + "..." : lines[i]);
            if (i < end) {
                body.append('\n');
            }
        }
        String window = body.toString();
        long fences = window.lines().filter(l -> l.stripLeading().startsWith("```")).count();
        if (fences % 2 == 1) {
            // Decide whether the window opened inside a block (needs an opening fence) or ended inside one.
            long before = java.util.Arrays.stream(lines, 0, start).filter(l -> l.stripLeading().startsWith("```")).count();
            if (before % 2 == 1) {
                window = "```\n" + window;
            } else {
                window = window + "\n```";
            }
        }
        out.append(window);
        if (end < lines.length - 1) {
            out.append("\n...");
        }
        return out.toString().strip();
    }
}
