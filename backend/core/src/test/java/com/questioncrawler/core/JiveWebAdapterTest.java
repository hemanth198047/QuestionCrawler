package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.JiveWebAdapter;
import com.questioncrawler.core.source.ShortLinks;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.source.WebPageReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jive by tag through its search page: the search address, the content links, and the pages stored as posts. */
class JiveWebAdapterTest {

    static final String PAGE = "<html><head><title>CASC runbook | CASC Community</title></head><body><main><h1>CASC runbook</h1>"
            + "<p>Run the casc_upload_manifest stage after validating it with fdc-cli; it uploads the manifest.</p></main></body></html>";

    private JiveWebAdapter adapter(List<URI> searched) {
        WebPageReader reader = new WebPageReader((uri, cookie) -> new ShortLinks.Hop(200, null, PAGE), null);
        return new JiveWebAdapter("jive", "https://jive.xx.com/", null, page -> {
            searched.add(page);
            return page.getQuery().contains("q=casc")
                    ? List.of("https://jive.xx.com/docs/DOC-1?sr=search&searchId=4", "https://jive.xx.com/thread/7#reply", "https://jive.xx.com/docs/DOC-1?sr=other")
                    : List.of("https://jive.xx.com/docs/DOC-1?sr=search", "https://jive.xx.com/docs/DOC-2");
        }, reader);
    }

    @Test
    void theSearchPageIsOpenedPerTagWithTheCollectionWindow() throws Exception {
        List<URI> searched = new ArrayList<>();
        JiveWebAdapter a = adapter(searched);
        a.collectionWindow("1y");
        assertEquals("https://jive.xx.com/search.jspa?q=casc&after=year", a.searchPage("casc").toString());
        a.collectionWindow("30d");
        assertEquals("https://jive.xx.com/search.jspa?q=casc+team&after=month", a.searchPage("casc team").toString());
        a.collectionWindow(null);
        assertEquals("https://jive.xx.com/search.jspa?q=casc", a.searchPage("casc").toString(), "no window: no after");
        a.collectionWindow("7d");
        var p1 = a.listQuestions(new TagFilter(List.of("casc", "fdc"), TagMode.ANY, false), null, null);
        assertEquals(2, p1.items().size(), "the same document linked twice is one item");
        assertEquals("https://jive.xx.com/docs/DOC-1", p1.items().get(0).url(), "search tracking is removed");
        assertEquals("1", p1.nextCursor());
        var p2 = a.listQuestions(new TagFilter(List.of("casc", "fdc"), TagMode.ANY, false), null, "1");
        assertNull(p2.nextCursor());
        assertEquals("https://jive.xx.com/search.jspa?q=fdc&after=week", searched.get(1).toString());
        var q = a.fetchQuestion(p1.items().get(0).id());
        assertEquals("CASC runbook", q.title(), "the community name is removed from the title");
        assertTrue(q.tags().containsAll(List.of("casc", "fdc", "jive", "type:document")), q.tags().toString());
        assertTrue(q.body().contains("casc_upload_manifest"));
    }

    @Test
    void allIsNotATagToSearchFor() {
        JiveWebAdapter a = adapter(new ArrayList<>());
        SourceException e = assertThrows(SourceException.class, () -> a.listQuestions(new TagFilter(List.of("all"), TagMode.ANY, false), null, null));
        assertTrue(e.getMessage().contains("tag by tag"), e.getMessage());
    }
}
