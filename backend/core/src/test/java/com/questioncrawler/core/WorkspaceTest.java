package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.service.Overview;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncProgress;
import com.questioncrawler.core.sync.SyncReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path tmp;

    private Workspace workspace() {
        return new Workspace(tmp.resolve("cfg/config.properties"), Map.of(), k -> null, Clock.systemUTC());
    }

    private Workspace demoWorkspace() {
        Workspace w = workspace();
        var saved = w.saveConfig(Map.of("source.type", "fixture", "sync.tags", "orbit", "storage.root", tmp.resolve("data").toString().replace('\\', '/')));
        assertTrue(saved.saved(), saved.issues().toString());
        return w;
    }

    private static Overview.SetupStep step(Overview o, String id) {
        return o.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aBrandNewInstallationGuidesToTheFirstStep() {
        Workspace w = new Workspace(tmp.resolve("none/config.properties"), Map.of("storage.root", tmp.resolve("d").toString()), k -> null, Clock.systemUTC());
        Overview o = w.overview();
        assertFalse(o.configExists());
        assertEquals("choose", o.nextStep());
        assertEquals("TODO", step(o, "choose").status());
        assertEquals("LOCKED", step(o, "explore").status());
        assertEquals(0, o.totals().questions());
        assertEquals(4, o.steps().size());
        assertTrue(o.notes().isEmpty(), "a fresh install shows no technical warnings: " + o.notes());
    }

    @Test
    void settingsCanBeSavedFromTheUiAndTheWholeFlowThenWorks() {
        Workspace w = demoWorkspace();
        Overview before = w.overview();
        assertEquals("DONE", step(before, "choose").status());
        assertEquals("collect", before.nextStep());

        SyncReport report = w.runner().runBlocking(w.config().syncOptions(null, null, null, SyncMode.INCREMENTAL));
        assertEquals(SyncReport.COMPLETE, report.outcome());

        Overview after = w.overview();
        assertEquals(10, after.totals().questions());
        assertEquals(15, after.totals().answers());
        assertEquals(9, after.totals().accepted());
        assertEquals(1, after.totals().unanswered());
        assertEquals("DONE", step(after, "collect").status());
        assertEquals("READY", step(after, "explore").status());
        assertEquals("explore", after.nextStep());
        assertFalse(after.categories().isEmpty());
        assertTrue(after.categories().stream().mapToInt(Overview.CategorySlice::count).sum() >= 10, "every question belongs to at least one category");
        assertEquals(1, after.recentRuns().size());
        assertEquals("Demo data", after.sourceLabel());
        assertTrue(w.kb().search(new com.questioncrawler.core.search.SearchRequest("ERR_CONN_TIMEOUT", null, null, 3, 0, 0)).resultCount() > 0);
    }

    @Test
    void progressIsReportedPhaseByPhaseWithGrowingCounters() {
        Workspace w = demoWorkspace();
        List<SyncProgress> events = new ArrayList<>();
        w.kb().sync(w.config().syncOptions(null, null, null, SyncMode.INCREMENTAL), events::add);

        assertFalse(events.isEmpty());
        assertEquals(SyncProgress.LISTING, events.get(0).phase());
        assertEquals(SyncProgress.DONE, events.get(events.size() - 1).phase());
        List<String> phases = events.stream().map(SyncProgress::phase).distinct().toList();
        assertEquals(List.of(SyncProgress.LISTING, SyncProgress.FETCHING, SyncProgress.FINISHING, SyncProgress.DONE), phases);
        int last = 0;
        for (SyncProgress p : events) {
            assertTrue(p.fetched() >= last, "fetched never goes backwards");
            last = p.fetched();
        }
        SyncProgress end = events.get(events.size() - 1);
        assertEquals(10, end.toFetch());
        assertEquals(10, end.fetched());
        assertEquals(10, end.added());
        assertTrue(events.stream().anyMatch(p -> p.phase().equals(SyncProgress.LISTING) && p.pagesListed() >= 2));
    }

    @Test
    void backgroundRunExposesLiveStateAndTheFinalReport() throws Exception {
        Workspace w = demoWorkspace();
        assertTrue(w.runner().start(w.config().syncOptions(null, null, null, SyncMode.INCREMENTAL)));
        long deadline = System.currentTimeMillis() + 20_000;
        while (w.runner().isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        var state = w.runner().state();
        assertFalse(state.running());
        assertNotNull(state.lastReport());
        assertEquals(SyncProgress.DONE, state.progress().phase());
        assertEquals(null, state.lastError());
    }

    @Test
    void connectionCheckIsRememberedAndTicksTheSetupStep() {
        Workspace w = demoWorkspace();
        assertTrue(w.lastConnection().isEmpty());
        var report = w.checkConnection(Map.of());
        assertEquals("READY", report.verdict());
        assertTrue(w.lastConnection().isPresent());
        assertEquals("DONE", step(w.overview(), "check").status());
    }

    @Test
    void draftSettingsCanBeCheckedWithoutSavingThem() {
        Workspace w = demoWorkspace();
        var report = w.checkConnection(Map.of("source.type", "live", "source.baseUrl", "https://this-host-does-not-exist.invalid/api", "source.timeoutSeconds", "3"));
        assertEquals("BLOCKED", report.verdict());
        assertEquals("fixture", w.config().sourceType(), "the saved settings are untouched");
        assertEquals("fixture", w.configView().values().get("source.type"));
    }

    @Test
    void categoriesCanBeEditedTriedOutAndReset() throws Exception {
        Workspace w = demoWorkspace();
        var view = w.taxonomyView();
        assertFalse(view.custom());
        assertEquals(12, view.taxonomy().categories().size());

        // Try a sample before saving anything.
        var result = w.tryClassify(null, "Helm chart fails to deploy", "The kubernetes rollout failed with an error", List.of("helm"));
        assertTrue(result.matches().stream().anyMatch(m -> m.categoryId().equals("deployment")));
        assertTrue(result.matches().stream().flatMap(m -> m.matchedRules().stream()).anyMatch(r -> r.contains("helm") || r.contains("kubernetes")));
        assertFalse(result.fallback());
        var fallback = w.tryClassify(null, "Lunch menu", "Pizza on Friday", List.of());
        assertTrue(fallback.fallback());
        assertTrue(fallback.explanation().contains("minimum score"));

        // Add a custom category and save it.
        var rules = new ArrayList<>(List.of(new Taxonomy.Rule("keyword", "any", "gpu", 2.0)));
        var categories = new ArrayList<>(view.taxonomy().categories());
        categories.add(0, new Taxonomy.Category("hardware", "Hardware and GPUs", "Anything about devices", rules));
        var saved = w.saveTaxonomy(new Taxonomy(1, 2.0, "uncategorized", categories));
        assertTrue(saved.problems().isEmpty(), saved.problems().toString());
        assertTrue(saved.custom());
        assertTrue(Files.exists(Path.of(saved.file())));
        assertTrue(w.tryClassify(null, "GPU memory", "out of memory on the gpu", List.of()).matches().get(0).categoryId().equals("hardware"));
        assertTrue(Files.readString(w.configFile()).contains("taxonomy.file=taxonomy.json"));

        // Invalid edits are refused with readable problems and nothing changes.
        var broken = new ArrayList<>(saved.taxonomy().categories());
        broken.add(new Taxonomy.Category("bad", "Bad", "", List.of(new Taxonomy.Rule("regex", "any", "([", 1.0))));
        var refused = w.saveTaxonomy(new Taxonomy(1, 2.0, "uncategorized", broken));
        assertFalse(refused.problems().isEmpty());
        assertEquals(13, w.taxonomyView().taxonomy().categories().size());

        var reset = w.resetTaxonomy();
        assertFalse(reset.custom());
        assertEquals(12, reset.taxonomy().categories().size());
    }

    @Test
    void reorganizingAfterEditingCategoriesMovesExistingQuestions() {
        Workspace w = demoWorkspace();
        w.runner().runBlocking(w.config().syncOptions(null, null, null, SyncMode.INCREMENTAL));
        var view = w.taxonomyView();
        var categories = new ArrayList<>(view.taxonomy().categories());
        categories.add(0, new Taxonomy.Category("timeouts", "Timeouts", "Anything that times out",
                List.of(new Taxonomy.Rule("keyword", "any", "timeout", 3.0))));
        w.saveTaxonomy(new Taxonomy(1, 2.0, "uncategorized", categories));
        assertEquals(0, w.kb().categories().stream().filter(c -> c.id().equals("timeouts")).findFirst().orElseThrow().questionCount(), "existing questions are not moved until reorganized");
        assertTrue(w.overview().notes().stream().anyMatch(n -> n.contains("older topic rules")), w.overview().notes().toString());

        w.kb().reindex();
        assertTrue(w.kb().categories().stream().filter(c -> c.id().equals("timeouts")).findFirst().orElseThrow().questionCount() >= 1);
    }

    @Test
    void aBrokenSettingsFileDoesNotPreventStartup() throws Exception {
        Path file = tmp.resolve("broken/config.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "sync.concurrency=not-a-number\n");
        Workspace w = new Workspace(file, Map.of("storage.root", tmp.resolve("b").toString()), k -> null, Clock.systemUTC());
        Overview o = w.overview();
        assertTrue(o.notes().stream().anyMatch(n -> n.contains("could not be used")), o.notes().toString());
        assertTrue(w.configView().issues().stream().anyMatch(i -> i.key().equals("sync.concurrency")));
        var fixed = w.saveConfig(Map.of("sync.concurrency", "3"));
        assertTrue(fixed.saved());
        assertFalse(w.overview().notes().stream().anyMatch(n -> n.contains("could not be used")));
    }
}
