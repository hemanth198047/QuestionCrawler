package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.classification.Classifier;
import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.SyncMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassifierTest {

    @TempDir
    Path tmp;

    private static Question question(String title, String body, List<String> tags) {
        return new Question("x1", title, body, BodyFormat.MARKDOWN, "https://x/q/x1", tags, null, null, null, null, List.of(), List.of());
    }

    @Test
    void multipleCategoriesAreAssignedWithExplanations() {
        Classifier c = new Classifier(Taxonomy.loadDefault());
        Classification result = c.classify(question("Deploy with docker fails with timeout error",
                "The kubernetes rollout of our docker image fails. Error: timeout while pulling.", List.of("deployment", "docker")));
        List<String> ids = result.categoryIds();
        assertTrue(ids.contains("deployment"), ids.toString());
        assertTrue(ids.contains("troubleshooting-errors"), ids.toString());
        assertEquals(Classifier.METHOD, result.method());
        assertTrue(result.categories().get(0).matchedRules().stream().anyMatch(r -> r.startsWith("keyword") || r.startsWith("tag")));
        assertFalse(result.categoryIds().contains("uncategorized"));
    }

    @Test
    void fallsBackToUncategorizedWithAnExplanation() {
        Classifier c = new Classifier(Taxonomy.loadDefault());
        Classification result = c.classify(question("Lunch options", "Where should the team eat on Friday?", List.of("social")));
        assertEquals(List.of("uncategorized"), result.categoryIds());
        assertTrue(result.categories().get(0).matchedRules().get(0).contains("no category reached minScore"));
    }

    @Test
    void taxonomyIsConfigurableNotHardCoded() {
        var rule = new Taxonomy.Rule("keyword", "title", "payroll", 2.0);
        var taxonomy = new Taxonomy(1, 2.0, "other", List.of(
                new Taxonomy.Category("finance", "Finance", "Money topics", List.of(rule)),
                new Taxonomy.Category("other", "Other", "Fallback", List.of())));
        Classifier c = new Classifier(taxonomy);
        assertEquals(List.of("finance"), c.classify(question("Payroll totals", "x", List.of())).categoryIds());
        assertEquals(List.of("other"), c.classify(question("Orbit timeouts", "x", List.of())).categoryIds());
    }

    @Test
    void invalidTaxonomiesAreRejectedWithClearMessages() {
        var badRegex = new Taxonomy(1, 1.0, "u", List.of(
                new Taxonomy.Category("a", "A", "", List.of(new Taxonomy.Rule("regex", "any", "([unclosed", 1.0))),
                new Taxonomy.Category("u", "U", "", List.of())));
        assertTrue(badRegex.validate().get(0).contains("invalid regex"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new Classifier(badRegex));
        assertTrue(e.getMessage().contains("Invalid taxonomy"));

        var noFallback = new Taxonomy(1, 1.0, "missing", List.of(new Taxonomy.Category("a", "A", "", List.of())));
        assertTrue(noFallback.validate().stream().anyMatch(p -> p.contains("fallbackCategory")));
        var duplicate = new Taxonomy(1, 1.0, "a", List.of(new Taxonomy.Category("a", "A", "", List.of()), new Taxonomy.Category("a", "B", "", List.of())));
        assertTrue(duplicate.validate().stream().anyMatch(p -> p.contains("Duplicate")));
    }

    @Test
    void defaultTaxonomyIsValid() {
        assertEquals(List.of(), Taxonomy.loadDefault().validate());
    }

    @Test
    void manualOverridesSurviveSynchronizationAndReindex() {
        KnowledgeBase v1 = TestSupport.kb(tmp, TestSupport.siteV1());
        v1.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        v1.setOverride("1001", List.of("performance"), "this is really a tuning question");
        assertEquals(List.of("performance"), v1.show("1001").orElseThrow().categories());
        assertTrue(v1.search(new SearchRequest("timeout", null, List.of("performance"), 10, 0, 0)).evidence().stream()
                .anyMatch(e -> e.questionId().equals("1001")), "override is reflected in the search index");

        // 1001 is edited on the site and re-collected: generated classification is recomputed, the override stays.
        KnowledgeBase v2 = TestSupport.kb(tmp, TestSupport.siteV2());
        v2.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        var view = v2.show("1001").orElseThrow();
        assertEquals(List.of("performance"), view.categories());
        assertTrue(view.override() != null && view.override().note().contains("tuning"));
        assertFalse(view.classification().categoryIds().isEmpty(), "generated classification is kept as provenance next to the override");

        v2.reindex();
        assertEquals(List.of("performance"), v2.show("1001").orElseThrow().categories());

        v2.clearOverride("1001");
        assertTrue(v2.show("1001").orElseThrow().override() == null);
    }

    @Test
    void overridesRejectUnknownCategories() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> kb.setOverride("1001", List.of("no-such-category"), null));
        assertTrue(e.getMessage().contains("Unknown category"));
    }

    @Test
    void categoryBrowsingListsQuestionsWithoutDuplicatingContent() throws Exception {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        var listing = kb.category("installation-setup").orElseThrow();
        assertTrue(listing.questions().stream().anyMatch(q -> q.id().equals("1002")));
        assertTrue(kb.category("nope").isEmpty());

        Path index = kb.store().paths().markdownCategoryFile("installation-setup");
        String md = java.nio.file.Files.readString(index);
        assertTrue(md.contains("../questions/fixture/1002.md"), md);
        assertFalse(md.contains("JAVA_HOME"), "category index links to threads, it does not copy their content");
        assertTrue(java.nio.file.Files.readString(kb.store().paths().markdownQuestionFile("fixture", "1002")).contains("JAVA_HOME"));
    }
}
