package com.questioncrawler.core.cli;

import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.FixtureSourceAdapter;
import com.questioncrawler.core.sync.SyncMode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * Offline end-to-end demonstration using the bundled synthetic fixtures: collection, classification,
 * storage, search, and then a "the site changed" update followed by a full reconciliation.
 * Needs no network, credentials or AI service.
 */
final class DemoRunner {

    private static final List<String> TAGS = List.of("orbit");

    private final PrintStream out;

    DemoRunner(PrintStream out) {
        this.out = out;
    }

    int run(Path storage) {
        try {
            Path root = storage != null ? storage.toAbsolutePath() : Files.createTempDirectory("questioncrawler-demo-");
            out.println("== QuestionCrawler offline demo (synthetic data, no network, no credentials) ==");
            out.println("Storage: " + root);
            CoreConfig config = CoreConfig.forStorage(root);

            // Phase 1: the site as it was.
            var siteV1 = FixtureSourceAdapter.fromClasspath(2, 2, "fixtures/demo-site");
            KnowledgeBase kb = new KnowledgeBase(config, siteV1, null, Clock.systemUTC());
            kb.init();

            step("1. Initial collection (tag: orbit, questions paginated 2 per page, answers 2 per page)");
            out.print(TextFormat.syncReport(kb.sync(config.syncOptions(TAGS, null, null, SyncMode.INCREMENTAL))));

            step("2. Functional categories (rule-based classification)");
            out.print(TextFormat.categories(kb.categories().stream().filter(c -> c.questionCount() > 0).toList()));

            step("3. Searching: exact error message");
            out.print(TextFormat.search(kb.search(new SearchRequest("ERR_CONN_TIMEOUT: connection timed out after 30 seconds", null, null, 3, 0, 500))));

            step("4. Searching: natural-language question");
            out.print(TextFormat.search(kb.search(new SearchRequest("how do I configure SSO login", null, null, 3, 0, 500))));

            step("5. Searching: code identifier");
            out.print(TextFormat.search(kb.search(new SearchRequest("OrbitClient setBatchSize", null, null, 2, 0, 400))));

            step("6. Searching: a question the store cannot answer");
            out.print(TextFormat.search(kb.search(SearchRequest.of("kubernetes pod eviction policy"))));

            // Phase 2: the site changed (edit, new answer, new post, deletion, revoked access, tag change).
            var siteV2 = FixtureSourceAdapter.fromClasspath(2, 2, "fixtures/demo-site", "fixtures/demo-site-update");
            KnowledgeBase kb2 = new KnowledgeBase(config, siteV2, null, Clock.systemUTC());

            step("7. Incremental sync after the site changed (re-reads only changed questions)");
            out.print(TextFormat.syncReport(kb2.sync(config.syncOptions(TAGS, null, null, SyncMode.INCREMENTAL))));
            out.println("Note: an incremental sync cannot see deletions, revoked access or tag changes.");
            out.println("      Question 1007 (deleted on the site) is therefore still searchable at this point.");

            step("8. Full reconciliation (probes stored questions missing from the listing)");
            out.print(TextFormat.syncReport(kb2.sync(config.syncOptions(TAGS, null, null, SyncMode.FULL))));

            step("9. The deleted question no longer appears in search");
            out.print(TextFormat.search(kb2.search(new SearchRequest("OrbitClient setBatchSize", null, null, 2, 0, 400))));

            step("10. Store status");
            out.print(TextFormat.status(kb2.status()));

            out.println();
            out.println("Browse the human-readable export in: " + root.resolve("markdown"));
            out.println("Try:  questioncrawler --storage \"" + root + "\" search \"connection timeout\"");
            return 0;
        } catch (IOException e) {
            out.println("Demo failed: " + e.getMessage());
            return 1;
        }
    }

    private void step(String title) {
        out.println();
        out.println("---- " + title + " ----");
    }
}
