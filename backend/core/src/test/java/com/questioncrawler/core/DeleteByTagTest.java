package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.Views;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hard-delete of locally stored Q&A by tag keeps unrelated tags and derived data consistent. */
class DeleteByTagTest {

    @TempDir
    Path tmp;

    @Test
    void deletesMatchingThreadsAndLeavesUnrelatedTags() throws Exception {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        // Collect orbit + payroll so a non-orbit thread is present while we delete an orbit-only tag.
        kb.sync(new SyncOptions(List.of("orbit", "payroll"), TagMode.ANY, false, SyncMode.INCREMENTAL,
                Duration.ofMinutes(60), 3, 30));
        assertTrue(kb.store().loadThread("fixture", "1001").isPresent());
        assertTrue(kb.store().loadThread("fixture", "1010").isPresent());
        assertTrue(Files.exists(kb.store().paths().markdownQuestionFile("fixture", "1001")));
        kb.setOverride("1001", List.of("performance"), "should be scrubbed with the thread");

        Views.DeleteByTagResult result = kb.deleteByTag("timeout");

        assertEquals("timeout", result.tag());
        assertEquals(1, result.deletedThreads());
        assertEquals(List.of("1001"), result.deletedQuestionIds());
        assertTrue(result.deletedAnswers() >= 1);
        assertTrue(kb.store().loadThread("fixture", "1001").isEmpty());
        assertFalse(Files.exists(kb.store().paths().threadFile("fixture", "1001")));
        assertFalse(Files.exists(kb.store().paths().classificationFile("fixture", "1001")));
        assertFalse(Files.exists(kb.store().paths().markdownQuestionFile("fixture", "1001")));
        assertFalse(kb.store().loadOverrides().containsKey("fixture:1001"));

        // Unrelated tags remain: installation (1002) and payroll (1010).
        assertTrue(kb.store().loadThread("fixture", "1002").isPresent());
        assertTrue(kb.store().loadThread("fixture", "1010").isPresent());
        assertEquals("1002", kb.search(SearchRequest.of("ORB-1042 installer")).evidence().get(0).questionId());
        assertTrue(kb.show("1001").isEmpty());
        assertTrue(kb.show("1010").isPresent());
    }

    @Test
    void deletesEveryThreadThatCarriesTheTag() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        // "ingestion" appears on both 1001 and 1007 among the orbit fixture set.
        Views.DeleteByTagResult result = kb.deleteByTag("Ingestion");
        assertEquals("ingestion", result.tag());
        assertEquals(2, result.deletedThreads());
        assertEquals(List.of("1001", "1007"), result.deletedQuestionIds());
        assertTrue(kb.store().loadThread("fixture", "1002").isPresent());
        assertEquals(8, kb.store().allThreads().size());
    }

    @Test
    void refusesAllAndBlankTags() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        assertThrows(IllegalArgumentException.class, () -> kb.deleteByTag("all"));
        assertThrows(IllegalArgumentException.class, () -> kb.deleteByTag("*"));
        assertThrows(IllegalArgumentException.class, () -> kb.deleteByTag("  "));
        assertEquals(10, kb.store().allThreads().size());
    }

    @Test
    void unknownTagDeletesNothing() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        Views.DeleteByTagResult result = kb.deleteByTag("no-such-tag-xyz");
        assertEquals(0, result.deletedThreads());
        assertEquals(0, result.deletedAnswers());
        assertEquals(List.of(), result.deletedQuestionIds());
        assertEquals(10, kb.store().allThreads().size());
    }
}
