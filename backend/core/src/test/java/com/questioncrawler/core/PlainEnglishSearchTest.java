package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.search.PlainEnglish;
import com.questioncrawler.core.search.SearchIndex;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.search.SearchService;
import com.questioncrawler.core.search.SymbolTokens;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plain-English lexical helpers (stems/synonyms) — no LLM, no live Confluence. */
class PlainEnglishSearchTest {

    @TempDir
    Path tmp;

    Path indexDir;
    Taxonomy taxonomy;

    @BeforeEach
    void indexSamplePage() {
        indexDir = tmp.resolve("index");
        taxonomy = Taxonomy.loadDefault();
        try (SearchIndex index = new SearchIndex(indexDir)) {
            index.resetForRebuild();
            Question page = new Question("c100", "SSO configuration runbook",
                    "This page explains configuring single sign-on for new hires during onboarding.",
                    BodyFormat.MARKDOWN, "https://wiki.example/pages/c100", List.of("runbook", "space:ENG"),
                    "Ada", null, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z",
                    List.of(new Comment("1", "Bob", "Also covers the playbook for incidents.", "2026-02-02T00:00:00Z")),
                    List.of());
            ThreadRecord thread = new ThreadRecord(ThreadRecord.SCHEMA_VERSION, "confluence", page, page.id(),
                    "2026-02-03T00:00:00Z", "hash", ThreadStatus.ACTIVE, null, null, null, "2026-02-03T00:00:00Z",
                    List.of("scope"));
            index.indexThread(thread, List.of("uncategorized"), taxonomy);
            index.commit();
        }
    }

    @Test
    void stemsBridgeConfiguringAndConfigure() {
        assertTrue(SymbolTokens.expand("configuring").stream().anyMatch(t -> t.startsWith("configur")));
        assertFalse(PlainEnglish.codeLike("configuring"));
        assertTrue(PlainEnglish.codeLike("err_conn_timeout"));
        assertTrue(PlainEnglish.synonymsOf("runbook").contains("playbook"));
    }

    @Test
    void plainEnglishQueryFindsConfluenceStylePage() {
        SearchResponse r = new SearchService(indexDir, taxonomy)
                .search(new SearchRequest("how do I configure SSO for onboarding", null, null, 5, 0, 0));
        assertFalse(r.evidence().isEmpty(), "expected a hit for plain-English SSO/onboarding query");
        assertEquals("c100", r.evidence().get(0).questionId());
    }

    @Test
    void synonymAndCommentTextAreSearchable() {
        SearchResponse r = new SearchService(indexDir, taxonomy)
                .search(new SearchRequest("incident playbook", null, null, 5, 0, 0));
        assertTrue(r.evidence().stream().anyMatch(e -> e.questionId().equals("c100")),
                "comment text mentioning playbook should be searchable");
    }

    @Test
    void indexVersionIsThree() {
        assertEquals(3, SearchIndex.INDEX_VERSION);
        assertEquals(3, SearchIndex.readVersion(indexDir));
    }
}
