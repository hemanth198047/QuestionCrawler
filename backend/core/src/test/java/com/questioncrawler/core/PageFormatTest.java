package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.normalize.ContentNormalizer;
import org.junit.jupiter.api.Test;

/** The page keeps its format: lists with paragraphs and sub-lists, notes, definitions, code languages, no page header. */
class PageFormatTest {

    static String md(String html) {
        return ContentNormalizer.toMarkdown(html, BodyFormat.HTML);
    }

    @Test
    void listItemsWithParagraphsStayOnTheirBulletAndKeepTheirSubLists() {
        String out = md("<ol><li><p>Prepare the manifest:</p><ul><li><p>list every file</p></li><li><p>keep the order</p></li></ul></li>"
                + "<li><p>Validate it:</p><div class=\"highlight-default\"><div class=\"highlight\"><pre>fdc-cli validate</pre></div></div></li>"
                + "<li><p>Run the stage.</p><p>Then check the logs.</p></li></ol>");
        assertEquals("1. Prepare the manifest:\n  - list every file\n  - keep the order\n2. Validate it:\n\n```\nfdc-cli validate\n```\n\n"
                + "3. Run the stage. Then check the logs.", out);
    }

    @Test
    void numberingContinuesFromStart() {
        assertEquals("5. five\n6. six", md("<ol start=\"5\"><li>five</li><li>six</li></ol>"));
    }

    @Test
    void notesAndWarningsBecomeQuotesWithABoldTitle() {
        String out = md("<div class=\"admonition note\"><p class=\"admonition-title\">Note</p><p>Needs write access.</p></div>"
                + "<div class=\"confluence-information-macro confluence-information-macro-warning\"><p class=\"title\">Careful</p><p>Never twice.</p></div>");
        assertTrue(out.contains("> **Note:**\n>\n> Needs write access."), out);
        assertTrue(out.contains("> **Careful:**\n>\n> Never twice."), out);
    }

    @Test
    void definitionTermsAreBoldAndCodeKeepsItsLanguage() {
        String out = md("<dl><dt>retries</dt><dd><p>How often to retry.</p></dd></dl>"
                + "<div class=\"highlight-yaml notranslate\"><div class=\"highlight\"><pre><span class=\"nt\">retries</span>: 3</pre></div></div>");
        assertTrue(out.contains("**retries**\n\nHow often to retry."), out);
        assertTrue(out.contains("```yaml\nretries: 3\n```"), out);
    }

    @Test
    void theDocumentHeaderIsNotContent() {
        String out = md("<!DOCTYPE html><?xml version=\"1.0\"?><html><head><title>Guide — CASC docs</title><meta charset=\"utf-8\"></head>"
                + "<body><h1>Guide</h1><p>Text.</p></body></html>");
        assertEquals("# Guide\n\nText.", out);
        assertFalse(out.contains("DOCTYPE"));
    }

    @Test
    void anOrdinaryTitleParagraphOutsideANoteIsNotChanged() {
        assertEquals("Release title\n\nBody", md("<p class=\"title\">Release title</p><p>Body</p>"));
    }
}
