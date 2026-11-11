package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.normalize.ContentNormalizer;
import org.junit.jupiter.api.Test;

/** Confluence's storage format: macros, parameters, CDATA and links become readable text, never their settings. */
class ConfluenceStorageFormatTest {

    static String md(String html) {
        return ContentNormalizer.toMarkdown(html, BodyFormat.HTML);
    }

    @Test
    void headingAnchorsAreNotPartOfTheHeading() {
        String out = md("<h2><ac:structured-macro ac:name=\"anchor\" ac:schema-version=\"1\"><ac:parameter ac:name=\"\">id1CasCCasCusingTrainDeploy-id1csc-manifest"
                + "</ac:parameter></ac:structured-macro><ac:structured-macro ac:name=\"anchor\"><ac:parameter ac:name=\"\">CasC-label</ac:parameter>"
                + "</ac:structured-macro>Enabling Casc Manifest uploads using train-deploy.yaml</h2>");
        assertEquals("## Enabling Casc Manifest uploads using train-deploy.yaml", out);
    }

    @Test
    void codeMacrosBecomeCodeBlocksWithTheirLanguage() {
        String out = md("<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">yaml</ac:parameter><ac:parameter ac:name=\"linenumbers\">false"
                + "</ac:parameter><ac:plain-text-body><![CDATA[casc_upload_manifest: $casc_upload_manifest\nwhen: \"a > b && c < d\"]]></ac:plain-text-body>"
                + "</ac:structured-macro>");
        assertEquals("```yaml\ncasc_upload_manifest: $casc_upload_manifest\nwhen: \"a > b && c < d\"\n```", out);
        assertFalse(out.contains("CDATA") || out.contains("false"), out);
    }

    @Test
    void panelsExpandLinksTasksAndDates() {
        String out = md("<ac:structured-macro ac:name=\"warning\"><ac:rich-text-body><p>Never twice.</p></ac:rich-text-body></ac:structured-macro>"
                + "<ac:structured-macro ac:name=\"expand\"><ac:parameter ac:name=\"title\">More</ac:parameter><ac:rich-text-body><p>Hidden text.</p>"
                + "</ac:rich-text-body></ac:structured-macro>"
                + "<p>See <ac:link><ri:page ri:content-title=\"Train guide\" /></ac:link> and <ac:link><ri:page ri:content-title=\"X\" />"
                + "<ac:plain-text-link-body><![CDATA[the FAQ]]></ac:plain-text-link-body></ac:link> since <time datetime=\"2026-11-01\" />.</p>"
                + "<ac:structured-macro ac:name=\"toc\" /><ac:task-list><ac:task><ac:task-id>1</ac:task-id><ac:task-status>complete</ac:task-status>"
                + "<ac:task-body>Validate</ac:task-body></ac:task></ac:task-list>");
        assertTrue(out.contains("> **Warning:**\n>\n> Never twice."), out);
        assertTrue(out.contains("**More**\n\nHidden text."), out);
        assertTrue(out.contains("See Train guide and the FAQ since 2026-11-01."), out);
        assertTrue(out.contains("- Validate"), out);
        assertFalse(out.contains("complete") || out.contains("ac:") || out.contains("ri:"), out);
    }

    @Test
    void selfClosingSkippedElementsDoNotSwallowThePage() {
        assertEquals("Before after.", md("<p>Before <ac:emoticon ac:name=\"smile\" /><ac:image><ri:attachment ri:filename=\"a.png\" /></ac:image>after.</p>"));
        assertEquals("Text", md("<p><svg/>Text</p>"));
    }
}
