package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.normalize.ContentNormalizer;
import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

/** No special characters on screen: HTML codes, ¶ heading marks, invisible and garbled characters are cleaned. */
class CleanTextTest {

    static String md(String html) {
        return ContentNormalizer.toMarkdown(html, BodyFormat.HTML);
    }

    @Test
    void htmlCharacterCodesBecomeTheCharacters() {
        assertEquals("It’s the team’s “guide” • one → two ™ €5 × 3 °C … — ©",
                md("<p>It&rsquo;s the team&#8217;s &ldquo;guide&rdquo; &bull; one &rarr; two &trade; &euro;5 &times; 3 &deg;C &hellip; &mdash; &copy;</p>"));
        assertEquals("Don’t", md("<p>Don&#146;t</p>"), "old pages write Windows-1252 numbers");
    }

    @Test
    void headingLinkMarksAreDropped() {
        String out = md("<h1>Getting started<a class=\"headerlink\" href=\"#getting-started\" title=\"Permalink\">¶</a></h1>"
                + "<h2>Install<a class=\"anchor\" href=\"#install\">#</a></h2><h3>Run<a href=\"#run\"></a></h3><p>Body</p>");
        assertEquals("# Getting started\n\n## Install\n\n### Run\n\nBody", out);
    }

    @Test
    void invisibleAndGarbledCharactersAreCleaned() {
        assertEquals("Zerowidth soft-hyphen gone, and a plain space", ContentNormalizer.cleanText("Zero​width soft-hy­phen gone, and a plain space"));
        assertEquals("don’t “quote” café", ContentNormalizer.cleanText("donâ€™t â€œquoteâ€\u009d cafÃ©"));
        String utf8ReadAsCp1252 = new String("naïve – ok".getBytes(Charset.forName("UTF-8")), Charset.forName("windows-1252"));
        assertEquals("naïve – ok", ContentNormalizer.cleanText(utf8ReadAsCp1252));
        assertEquals("Ångström and Ü stay", ContentNormalizer.cleanText("Ångström and Ü stay"), "real accented text is untouched");
    }

    @Test
    void titlesAreCleanToo() {
        assertEquals("Team’s guide", ContentNormalizer.plainTitle("Team&rsquo;s guide ¶"));
        assertFalse(ContentNormalizer.plainTitle("A​&nbsp;B").contains("​"));
        assertTrue(md("<table><tr><td>a|b</td></tr></table>").contains("a\\|b"), "a pipe in a table cell stays escaped in Markdown");
    }
}
