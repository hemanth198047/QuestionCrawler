package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.normalize.ContentNormalizer;
import org.junit.jupiter.api.Test;

/** Documentation sites (Sphinx and others): only the page's own content is kept, and names keep their underscores. */
class SphinxPageTest {

    static final String SPHINX = """
            <div class="related" role="navigation" aria-label="Related"><h3>Navigation</h3><ul><li><a href="genindex.html">index</a></li></ul></div>
            <div class="document"><div class="documentwrapper"><div class="bodywrapper"><div class="body" role="main">
            <section id="pipeline"><h1>Pipeline stages<a class="headerlink" href="#pipeline" title="Link to this heading">¶</a></h1>
            <nav class="contents local" id="on-this-page"><p class="topic-title">On this page</p><ul><li><a href="#id21">Example casc_upload_manifest stage definition</a></li></ul></nav>
            <section id="example"><h2><a class="toc-backref" href="#id21" role="doc-backlink">Example casc_upload_manifest stage definition</a><a class="headerlink" href="#example">¶</a></h2>
            <p>Define the stage like this.</p>
            <div class="highlight-yaml notranslate"><div class="highlight"><pre><span></span><span class="nt">stages</span><span class="p">:</span>
              <span class="p p-Indicator">-</span> <span class="nt">name</span><span class="p">:</span> <span class="l l-Scalar l-Scalar-Plain">casc_upload_manifest</span>
            </pre></div></div>
            </section></section></div></div></div>
            <div class="sphinxsidebar" role="navigation" aria-label="Main"><div class="sphinxsidebarwrapper"><h3>Quick search</h3><ul><li>Pipeline stages</li></ul></div></div></div>
            <div class="footer">&#169;2026. Powered by <a href="https://www.sphinx-doc.org/">Sphinx</a></div>
            """;

    @Test
    void navigationSidebarsAndFootersAreNotContent() {
        String md = ContentNormalizer.toMarkdown(SPHINX, BodyFormat.HTML);
        assertFalse(md.contains("Navigation"), md);
        assertFalse(md.contains("Quick search"), md);
        assertFalse(md.contains("Powered by"), md);
        assertFalse(md.contains("On this page"), md);
        assertFalse(md.contains("¶"), md);
        assertTrue(md.contains("## Example casc_upload_manifest stage definition\n"), "the heading is plain text, not a link back to the contents: " + md);
        assertTrue(md.contains("name: casc_upload_manifest"), md);
    }

    @Test
    void sectionNamesKeepUnderscores() {
        var sections = com.questioncrawler.core.search.PageSections.split("# Stages\n\n## Example casc_upload_manifest stage definition\n\nDefine it.");
        assertTrue(sections.stream().anyMatch(s -> "Example casc_upload_manifest stage definition".equals(s.heading())), sections.toString());
        assertEquals("Stages › Example casc_upload_manifest stage definition", sections.get(sections.size() - 1).path());
    }
}
