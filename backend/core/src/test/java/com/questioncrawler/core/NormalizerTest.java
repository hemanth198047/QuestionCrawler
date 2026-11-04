package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.normalize.ContentNormalizer;
import com.questioncrawler.core.normalize.HtmlToMarkdown;
import org.junit.jupiter.api.Test;

class NormalizerTest {

    @Test
    void preservesCodeIndentationAndLanguage() {
        String html = "<p>Use this:</p><pre><code class=\"language-java\">if (x) {\n    run();\n\tother();\n}</code></pre>";
        String md = HtmlToMarkdown.convert(html);
        assertEquals("Use this:\n\n```java\nif (x) {\n    run();\n\tother();\n}\n```", md);
    }

    @Test
    void usesLongerFenceWhenCodeContainsBackticks() {
        String md = HtmlToMarkdown.convert("<pre><code>echo ```inner```</code></pre>");
        assertTrue(md.startsWith("````"), md);
        assertTrue(md.endsWith("````"), md);
    }

    @Test
    void keepsLinksListsHeadingsTablesAndInlineCode() {
        String html = "<h2>Steps</h2><ol><li>Open <a href=\"https://a.example/x\">the page</a></li><li>Run <code>make</code></li></ol>"
                + "<ul><li>one</li><li>two</li></ul>"
                + "<table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>x|y</td></tr></table>";
        String md = HtmlToMarkdown.convert(html);
        assertTrue(md.contains("## Steps"), md);
        assertTrue(md.contains("1. Open [the page](https://a.example/x)"), md);
        assertTrue(md.contains("2. Run `make`"), md);
        assertTrue(md.contains("- one\n- two"), md);
        assertTrue(md.contains("| a | b |\n| --- | --- |\n| 1 | x\\|y |"), md);
    }

    @Test
    void removesNavigationScriptsStylesAndFooters() {
        String md = HtmlToMarkdown.convert("<nav>Home | Questions</nav><style>.x{}</style><script>alert(1)</script><p>Real content</p><footer>(c) site</footer>");
        assertEquals("Real content", md);
    }

    @Test
    void dropsDangerousLinkSchemesButKeepsTheText() {
        String md = HtmlToMarkdown.convert("<a href=\"javascript:alert(1)\">click</a> and <a href=\"/relative/path\">rel</a>");
        assertFalse(md.contains("javascript"), md);
        assertTrue(md.contains("click"), md);
        assertTrue(md.contains("[rel](/relative/path)"), md);
    }

    @Test
    void decodesEntitiesAndHandlesBlockquotes() {
        String md = HtmlToMarkdown.convert("<blockquote><p>a &lt; b &amp;&amp; c</p></blockquote>");
        assertEquals("> a < b && c", md);
    }

    @Test
    void markdownInputKeepsFencedCodeExactlyAndTrimsProseNoise() {
        String in = "Intro   \r\n\r\n\r\n\r\n```py\r\ndef f():\r\n    return 1   \r\n\r\n\r\n\r\n    pass\r\n```\r\n";
        String out = ContentNormalizer.toMarkdown(in, BodyFormat.MARKDOWN);
        assertTrue(out.startsWith("Intro\n\n```py"), out);
        assertTrue(out.contains("    return 1   \n\n\n\n    pass\n```"), "code content, including trailing spaces and blank lines, is untouched: " + out);
    }

    @Test
    void titlesAreDecodedToPlainText() {
        assertEquals("Why does a < b?", ContentNormalizer.plainTitle("Why does <b>a &lt; b</b>?"));
    }
}
