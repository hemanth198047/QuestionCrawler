package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.normalize.ContentNormalizer;
import org.junit.jupiter.api.Test;

/** Very large or unusual pages (Jive pages with inline data, code full of "<") are read without a StackOverflowError. */
class LargePageTest {

    static String md(String html) {
        long t = System.nanoTime();
        String out = ContentNormalizer.toMarkdown(html, BodyFormat.HTML);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 10_000, "converting took " + ms + " ms");
        return out;
    }

    @Test
    void hugeAttributesAreSkippedNotRead() {
        // real HTML writes quotes inside an attribute as &quot; (and may contain ">")
        assertEquals("Text", md("<div data-json=\"" + "{&quot;k&quot;:&quot;v>w&quot;}".repeat(100_000) + "\"><p>Text</p></div>"));
        assertEquals("Text", md("<div data-x=" + "y".repeat(200_000) + "><p>Text</p></div>"));
    }

    @Test
    void strayLessThanSignsAndUnclosedTagsAreText() {
        assertEquals("if a<b then c<d", md("<p>if a<b then c<d</p>"));
        String many = md("<p>" + "if a<b then c<d ".repeat(15_000) + "</p>");
        assertTrue(many.startsWith("if a<b then c<d if a<b"), many.substring(0, 40));
        String unclosed = md("<p>start</p><a " + "x".repeat(200_000));
        assertTrue(unclosed.startsWith("start"), unclosed.substring(0, 20));
        assertEquals("1 < 2 and 3 > 2", md("<p>1 &lt; 2 and 3 &gt; 2</p>"));
    }

    @Test
    void deepAndLongPagesConvert() {
        assertEquals("deep", md("<div>".repeat(20_000) + "deep" + "</div>".repeat(20_000)));
        String big = md("<html><body>" + "<p>Paragraph with <b>bold</b> and <a href=\"/thread/1\">a link</a>.</p>".repeat(40_000) + "</body></html>");
        assertTrue(big.length() > 1_000_000 && big.startsWith("Paragraph with **bold**"), big.substring(0, 60));
    }

    @Test
    void quotedAttributesMayContainGreaterThan() {
        assertEquals("[go](https://x.test/a?b=c>d)", md("<a title=\"a > b\" href=\"https://x.test/a?b=c>d\">go</a>"));
    }

    @Test
    void thePlainTextIsTheLastResort() throws Exception {
        var plain = ContentNormalizer.class.getDeclaredMethod("plainText", String.class);
        plain.setAccessible(true);
        String text = (String) plain.invoke(null, "<h1>Title</h1><p>One &amp; two</p><div>Three</div>");
        assertTrue(text.contains("Title") && text.contains("One & two") && text.contains("Three"), text);
        assertFalse(text.contains("<"), text);
        var chunks = ContentNormalizer.class.getDeclaredMethod("chunks", String.class, int.class);
        chunks.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> pieces = (java.util.List<String>) chunks.invoke(null, "<p>a</p>".repeat(100_000), 100_000);
        assertTrue(pieces.size() > 5 && pieces.stream().allMatch(p -> p.startsWith("<p")), pieces.size() + " pieces");
        assertEquals("<p>a</p>".repeat(100_000), String.join("", pieces), "nothing is lost when cutting into chunks");
    }
}
