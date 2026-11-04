package com.questioncrawler.core.service;

import com.questioncrawler.core.classification.Classifier;
import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.CategoryOverride;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.search.SearchIndex;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.search.SearchService;
import com.questioncrawler.core.service.Views.*;
import com.questioncrawler.core.source.Retrier;
import com.questioncrawler.core.source.SourceAdapter;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.storage.KnowledgeStore;
import com.questioncrawler.core.storage.StoreException;
import com.questioncrawler.core.sync.Checkpoint;
import com.questioncrawler.core.sync.RunState;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.sync.SyncService;
import com.questioncrawler.core.sync.ThreadPublisher;
import com.questioncrawler.core.util.Times;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Facade over storage, sync, classification, markdown export and search. The CLI and the web
 * application both talk to this class, so they always behave identically.
 */
public final class KnowledgeBase {

    private final CoreConfig config;
    private final KnowledgeStore store;
    private final Taxonomy taxonomy;
    private final SourceAdapter adapterOverride;
    private final Retrier retrier;
    private final Clock clock;

    public KnowledgeBase(CoreConfig config) {
        this(config, null, null, Clock.systemUTC());
    }

    /** Test/demo constructor: inject a specific adapter, retry policy and clock. */
    public KnowledgeBase(CoreConfig config, SourceAdapter adapterOverride, Retrier retrier, Clock clock) {
        this.config = config;
        this.store = new KnowledgeStore(config.storageRoot());
        this.taxonomy = config.loadTaxonomy();
        List<String> problems = taxonomy.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid taxonomy: " + String.join("; ", problems));
        }
        this.adapterOverride = adapterOverride;
        this.retrier = retrier != null ? retrier : Retrier.standard(config.maxAttempts());
        this.clock = clock;
    }

    public CoreConfig config() {
        return config;
    }

    public KnowledgeStore store() {
        return store;
    }

    public Taxonomy taxonomy() {
        return taxonomy;
    }

    public void init() {
        store.init();
    }

    // ================================================================== synchronization

    public SyncReport sync(SyncOptions options) {
        store.init();
        SourceAdapter adapter = adapterOverride != null ? adapterOverride : config.createAdapter();
        Pipeline pipeline = new Pipeline();
        try {
            return new SyncService(store, adapter, pipeline, retrier, clock).run(options);
        } finally {
            pipeline.close();
        }
    }

    // ================================================================== search / show / browse

    public SearchResponse search(SearchRequest request) {
        store.ensureReady();
        SearchResponse response = new SearchService(store.paths().indexDir(), taxonomy).search(request);
        return response.withFreshness(freshness());
    }

    public Optional<ThreadView> show(String idOrKey) {
        store.ensureReady();
        Optional<ThreadRecord> found = store.findThread(idOrKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ThreadRecord t = found.get();
        Optional<Classification> classification = store.loadClassification(t.sourceSystem(), t.questionId());
        CategoryOverride override = store.loadOverrides().get(t.key());
        List<String> cats = effectiveCategories(t.key(), classification.orElse(null), override);
        return Optional.of(new ThreadView(t, cats, cats.stream().map(taxonomy::displayName).toList(), classification.orElse(null),
                override, true, SearchResponse.NOTICE));
    }

    public List<CategorySummary> categories() {
        store.ensureReady();
        Map<String, Integer> counts = new TreeMap<>();
        for (ThreadRecord t : retrievableThreads()) {
            for (String c : categoriesOf(t, store.loadOverrides())) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        List<CategorySummary> result = new ArrayList<>();
        for (Taxonomy.Category c : taxonomy.categories()) {
            result.add(new CategorySummary(c.id(), c.name(), c.description(), counts.getOrDefault(c.id(), 0)));
        }
        return result;
    }

    public Optional<CategoryListing> category(String id) {
        store.ensureReady();
        Taxonomy.Category c = taxonomy.category(id);
        if (c == null) {
            return Optional.empty();
        }
        Map<String, CategoryOverride> overrides = store.loadOverrides();
        List<QuestionRef> refs = new ArrayList<>();
        for (ThreadRecord t : retrievableThreads()) {
            if (categoriesOf(t, overrides).contains(id)) {
                var q = t.question();
                refs.add(new QuestionRef(t.sourceSystem(), q.id(), q.title(), q.url(), t.status().name(), q.answers().size(), q.hasAcceptedAnswer(), q.tags()));
            }
        }
        refs.sort(Comparator.comparing(QuestionRef::id));
        return Optional.of(new CategoryListing(new CategorySummary(c.id(), c.name(), c.description(), refs.size()), refs));
    }

    // ================================================================== manual overrides

    public void setOverride(String idOrKey, List<String> categories, String note) {
        store.ensureReady();
        ThreadRecord t = store.findThread(idOrKey).orElseThrow(() -> new StoreException("No such question: " + idOrKey));
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("At least one category id is required.");
        }
        for (String c : categories) {
            if (taxonomy.category(c) == null) {
                throw new IllegalArgumentException("Unknown category '" + c + "'. Known: "
                        + String.join(", ", taxonomy.categories().stream().map(Taxonomy.Category::id).toList()));
            }
        }
        Map<String, CategoryOverride> overrides = new TreeMap<>(store.loadOverrides());
        overrides.put(t.key(), new CategoryOverride(categories, note, clock.instant().toString()));
        store.saveOverrides(overrides);
        republish(t);
    }

    public void clearOverride(String idOrKey) {
        store.ensureReady();
        ThreadRecord t = store.findThread(idOrKey).orElseThrow(() -> new StoreException("No such question: " + idOrKey));
        Map<String, CategoryOverride> overrides = new TreeMap<>(store.loadOverrides());
        if (overrides.remove(t.key()) != null) {
            store.saveOverrides(overrides);
            republish(t);
        }
    }

    private void republish(ThreadRecord t) {
        if (t.isPurged() || !t.status().isRetrievable()) {
            return;
        }
        Pipeline p = new Pipeline();
        try {
            p.published(t);
            p.finish();
        } finally {
            p.close();
        }
    }

    // ================================================================== reindex

    /** Rebuilds all derived data (classification, markdown, search index) from the stored threads. */
    public ReindexResult reindex() {
        store.ensureReady();
        Pipeline p = new Pipeline();
        int threads = 0;
        int skipped = 0;
        try {
            p.index().clear();
            for (ThreadRecord t : store.allThreads()) {
                if (t.isPurged() || !t.status().isRetrievable()) {
                    skipped++;
                    continue;
                }
                p.published(t);
                threads++;
            }
            p.finish();
        } finally {
            p.close();
        }
        return new ReindexResult(threads, SearchIndex.readDocCount(store.paths().indexDir()), skipped);
    }

    // ================================================================== status / freshness

    public SearchResponse.Freshness freshness() {
        String last = null;
        for (String name : store.listStateNames("checkpoint-")) {
            Optional<Checkpoint> cp = store.readState(name, Checkpoint.class);
            if (cp.isPresent()) {
                last = Times.max(last, cp.get().lastSuccessfulRunAt());
            }
        }
        if (last == null) {
            return new SearchResponse.Freshness(null, null, true, "No successful synchronization is recorded yet.");
        }
        Optional<Instant> parsed = Times.parse(last);
        long hours = parsed.map(i -> Duration.between(i, clock.instant()).toHours()).orElse(0L);
        boolean stale = hours > config.staleAfterHours();
        return new SearchResponse.Freshness(last, hours, stale,
                stale ? "Last successful sync is older than " + config.staleAfterHours() + "h; content may be outdated." : "Within the freshness window.");
    }

    public StatusReport status() {
        boolean initialized = store.isInitialized();
        if (!initialized) {
            return new StatusReport(store.paths().root().toString(), false, null, 0, Map.of(), 0, null, null, List.of(), false,
                    freshnessSafe(), 0, List.of("Store not initialized. Run 'init', then 'sync' or 'demo'."));
        }
        KnowledgeStore.Manifest manifest = store.ensureReady();
        Map<String, Integer> byStatus = new TreeMap<>();
        int answers = 0;
        int olderTaxonomy = 0;
        List<ThreadRecord> all = store.allThreads();
        for (ThreadRecord t : all) {
            byStatus.merge(t.status().name(), 1, Integer::sum);
            if (!t.isPurged()) {
                answers += t.question().answers().size();
                Optional<Classification> c = store.loadClassification(t.sourceSystem(), t.questionId());
                if (c.isPresent() && !taxonomy.hash().equals(c.get().taxonomyHash()) && t.status().isRetrievable()) {
                    olderTaxonomy++;
                }
            }
        }
        List<Checkpoint> checkpoints = new ArrayList<>();
        boolean pending = false;
        for (String name : store.listStateNames("checkpoint-")) {
            store.readState(name, Checkpoint.class).ifPresent(checkpoints::add);
        }
        for (String name : store.listStateNames("run-")) {
            if (store.readState(name, RunState.class).isPresent()) {
                pending = true;
            }
        }
        SyncSummary last = null;
        List<String> reports = store.listReportIds();
        if (!reports.isEmpty()) {
            Optional<SyncReport> r = store.readReport(reports.get(0), SyncReport.class);
            if (r.isPresent()) {
                SyncReport s = r.get();
                last = new SyncSummary(s.runId(), s.mode(), s.outcome(), s.finishedAt(), s.added(), s.updated(), s.unchanged(),
                        s.failures().size(), s.statusChanged(), s.completeness());
            }
        }
        List<String> notes = new ArrayList<>();
        if (pending) {
            notes.add("An interrupted synchronization is pending; the next 'sync' resumes it.");
        }
        if (olderTaxonomy > 0) {
            notes.add(olderTaxonomy + " thread(s) were classified with an older taxonomy; run 'reindex' to reclassify.");
        }
        int docs = SearchIndex.readDocCount(store.paths().indexDir());
        if (docs < 0 && !all.isEmpty()) {
            notes.add("The search index is missing; run 'reindex'.");
        }
        return new StatusReport(store.paths().root().toString(), true, manifest.schemaVersion(), all.size(), byStatus, answers,
                docs < 0 ? null : docs, last, checkpoints, pending, freshness(), olderTaxonomy, notes);
    }

    private SearchResponse.Freshness freshnessSafe() {
        return new SearchResponse.Freshness(null, null, true, "No successful synchronization is recorded yet.");
    }

    // ================================================================== doctor

    public DoctorReport doctor(boolean checkConnectivity) {
        List<Check> checks = new ArrayList<>();
        int java = Runtime.version().feature();
        checks.add(new Check("java", java >= 21 ? "OK" : "FAIL", "Java " + Runtime.version() + (java >= 21 ? "" : " (21 or newer required)")));
        checks.add(new Check("config", config.configFile() == null ? "WARN" : "OK",
                config.configFile() == null ? "No configuration file found; using defaults. Run 'init'." : config.configFile().toString()));
        for (String p : config.validate()) {
            checks.add(new Check("config-validation", p.startsWith("sync.tags is empty") ? "WARN" : "FAIL", p));
        }
        checks.add(storageWritable());
        if (store.isInitialized()) {
            try {
                checks.add(new Check("storage-schema", "OK", "schema version " + store.ensureReady().schemaVersion()));
            } catch (StoreException e) {
                checks.add(new Check("storage-schema", "FAIL", e.getMessage()));
            }
        } else {
            checks.add(new Check("storage-schema", "WARN", "Store not initialized yet (run 'init')."));
        }
        checks.add(new Check("taxonomy", "OK", taxonomy.categories().size() + " categories, hash " + taxonomy.hash()
                + (config.taxonomyFile() == null ? " (bundled starter taxonomy)" : " (" + config.taxonomyFile() + ")")));
        int docs = SearchIndex.readDocCount(store.paths().indexDir());
        checks.add(new Check("search-index", docs >= 0 ? "OK" : "WARN", docs >= 0 ? docs + " indexed documents" : "No index yet (created by sync/demo/reindex)."));
        if (config.sourceType().equals("live")) {
            String env = config.source().tokenEnv();
            if (config.source().authMethod().equalsIgnoreCase("token")) {
                boolean present = env != null && !env.isBlank() && System.getenv(env) != null && !System.getenv(env).isBlank();
                checks.add(new Check("credentials", present ? "OK" : "FAIL",
                        present ? "Environment variable " + env + " is set (value not shown)." : "Environment variable " + env + " is not set."));
            }
            checks.add(new Check("live-adapter", "WARN", "Live endpoint integration is not implemented (see docs/LIVE_INTEGRATION.md)."));
        }
        if (checkConnectivity) {
            try {
                SourceAdapter adapter = adapterOverride != null ? adapterOverride : config.createAdapter();
                checks.add(new Check("connectivity", "OK", adapter.ping()));
            } catch (SourceException e) {
                checks.add(new Check("connectivity", "FAIL", e.kind() + ": " + e.getMessage()));
            } catch (RuntimeException e) {
                checks.add(new Check("connectivity", "FAIL", e.getMessage()));
            }
        } else {
            checks.add(new Check("connectivity", "SKIP", "Not checked. Run 'doctor --connect' to test the source."));
        }
        return new DoctorReport(checks);
    }

    private Check storageWritable() {
        try {
            Files.createDirectories(store.paths().root());
            Path probe = Files.createTempFile(store.paths().root(), ".write-test", ".tmp");
            Files.delete(probe);
            return new Check("storage-writable", "OK", store.paths().root().toString());
        } catch (IOException e) {
            return new Check("storage-writable", "FAIL", "Cannot write to " + store.paths().root() + ": " + e.getMessage());
        }
    }

    // ================================================================== helpers

    private List<ThreadRecord> retrievableThreads() {
        return store.allThreads().stream().filter(t -> !t.isPurged() && t.status().isRetrievable()).toList();
    }

    private List<String> categoriesOf(ThreadRecord t, Map<String, CategoryOverride> overrides) {
        return effectiveCategories(t.key(), store.loadClassification(t.sourceSystem(), t.questionId()).orElse(null), overrides.get(t.key()));
    }

    /** A manual override always wins over generated classification; otherwise the fallback is Uncategorized. */
    private List<String> effectiveCategories(String key, Classification generated, CategoryOverride override) {
        if (override != null && !override.categories().isEmpty()) {
            return override.categories();
        }
        if (generated != null && !generated.categories().isEmpty()) {
            return generated.categoryIds();
        }
        return List.of(taxonomy.fallbackCategory());
    }

    // ================================================================== derived-data pipeline

    /** Classifies, exports markdown and indexes threads as the sync engine publishes them. */
    private final class Pipeline implements ThreadPublisher, AutoCloseable {
        private final Classifier classifier = new Classifier(taxonomy);
        private final MarkdownExporter markdown = new MarkdownExporter(store.paths(), taxonomy);
        private final SearchIndex index = new SearchIndex(store.paths().indexDir());
        private final Map<String, CategoryOverride> overrides = store.loadOverrides();

        SearchIndex index() {
            return index;
        }

        @Override
        public void published(ThreadRecord t) {
            Classification c = classifier.classify(t.question());
            store.saveClassification(t.sourceSystem(), t.questionId(), c);
            List<String> cats = effectiveCategories(t.key(), c, overrides.get(t.key()));
            markdown.writeQuestion(t, cats);
            index.indexThread(t, cats, taxonomy);
        }

        @Override
        public void removed(ThreadRecord t) {
            index.deleteThread(t.key());
            markdown.deleteQuestion(t.sourceSystem(), t.questionId());
        }

        @Override
        public void purged(ThreadRecord t) {
            removed(t);
            store.deleteClassification(t.sourceSystem(), t.questionId());
        }

        @Override
        public void finish() {
            index.commit();
            Map<ThreadRecord, List<String>> all = new LinkedHashMap<>();
            for (ThreadRecord t : retrievableThreads()) {
                all.put(t, categoriesOf(t, overrides));
            }
            markdown.writeCategoryIndexes(all);
        }

        @Override
        public void close() {
            index.close();
        }
    }
}
