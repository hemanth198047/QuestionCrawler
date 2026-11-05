package com.questioncrawler.core.service;

import com.questioncrawler.core.classification.Classifier;
import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.service.ConfigService.Issue;
import com.questioncrawler.core.service.ConfigService.SaveResult;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The running application's single point of truth: where the configuration file is, the current
 * {@link KnowledgeBase}, the sync runner, site diagnostics and category editing. Saving settings reloads
 * everything. Used by the web API (and available to any other front end).
 */
public final class Workspace {

    public record TaxonomyView(boolean custom, String file, String hash, Taxonomy taxonomy, List<String> problems) {}

    public record TryMatch(String categoryId, String name, double score, List<String> matchedRules) {}

    public record TryResult(List<TryMatch> matches, boolean fallback, String explanation) {}

    private static final String CONNECTION_STATE = "connection-check";

    private final Path configFile;
    private final Map<String, String> overrides;
    private final Function<String, String> env;
    private final Clock clock;
    private final ConfigService configService;
    private final SyncRunner runner;
    private volatile KnowledgeBase knowledgeBase;
    private volatile CoreConfig config;

    public Workspace(Path configFile, Map<String, String> overrides) {
        this(configFile, overrides, System::getenv, Clock.systemUTC());
    }

    public Workspace(Path configFile, Map<String, String> overrides, Function<String, String> env, Clock clock) {
        Path located = configFile != null ? configFile : CoreConfig.locate(null);
        this.configFile = (located != null ? located : Path.of(System.getProperty("user.home"), ".questioncrawler", "config.properties")).toAbsolutePath();
        this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        this.env = env;
        this.clock = clock;
        this.configService = new ConfigService(env);
        this.runner = new SyncRunner(this::kb);
        reload();
    }

    private volatile String loadError;

    /** Re-reads the settings. A broken file never prevents the app from opening: it falls back to defaults and says why. */
    public synchronized void reload() {
        CoreConfig loaded;
        String error = null;
        try {
            loaded = CoreConfig.load(Files.exists(configFile) ? configFile : null, overrides);
            knowledgeBase = new KnowledgeBase(loaded, null, null, clock);
        } catch (RuntimeException e) {
            error = "The settings file could not be used (" + e.getMessage() + "). Showing defaults: fix the highlighted settings and save.";
            loaded = CoreConfig.load(null, overrides);
            knowledgeBase = new KnowledgeBase(loaded, null, null, clock);
        }
        this.config = loaded;
        this.loadError = error;
    }

    public KnowledgeBase kb() { return knowledgeBase; }

    public CoreConfig config() { return config; }

    public Path configFile() { return configFile; }

    public SyncRunner runner() { return runner; }

    // ================================================================== configuration

    public ConfigService.ConfigView configView() {
        return configService.view(configFile, null);
    }

    public List<Issue> validate(Map<String, String> values) {
        Map<String, String> merged = configService.read(configFile);
        merged.putAll(values);
        return configService.validate(merged);
    }

    /** The settings file exactly as it would be written for these (unsaved) values. */
    public String previewConfig(Map<String, String> values) {
        Map<String, String> merged = configService.read(configFile);
        merged.putAll(values);
        return configService.render(merged);
    }

    public SaveResult saveConfig(Map<String, String> values) {
        SaveResult result = configService.save(configFile, values);
        if (result.saved()) {
            reload();
        }
        return result;
    }

    // ================================================================== connection check

    /** Checks the saved settings, or the settings being edited (draft values are NOT saved). */
    public ConnectionReport checkConnection(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        String type = v.getOrDefault("source.type", "fixture");
        List<String> tags = Arrays.stream(v.getOrDefault("sync.tags", "").split(",")).map(TagFilter::normalize).filter(t -> !t.isEmpty()).toList();
        String target;
        String site = "";
        if (type.equals("stackexchange")) {
            target = "https://api.stackexchange.com/2.3";
            site = CoreConfig.siteFromUrl(v.getOrDefault("source.siteUrl", "https://stackoverflow.com/"));
        } else {
            target = v.getOrDefault("source.baseUrl", "");
        }
        String ca = v.getOrDefault("source.caCertFile", "").strip();
        int timeout = parseInt(v.get("source.timeoutSeconds"), 15);
        SiteDiagnostics.Input input = new SiteDiagnostics.Input(type, target, site, tags, v.getOrDefault("source.auth.method", "none"),
                v.getOrDefault("source.auth.tokenEnv", ""), v.getOrDefault("source.stackexchange.keyEnv", ""),
                ca.isEmpty() ? null : Path.of(ca), Boolean.parseBoolean(v.getOrDefault("source.allowInsecureHttp", "false")), Math.min(timeout, 30));
        ConnectionReport report = new SiteDiagnostics(env, clock).run(input);
        try {
            knowledgeBase.store().init();
            knowledgeBase.store().writeState(CONNECTION_STATE, report);
        } catch (RuntimeException ignored) {
            // the report is still returned even if it cannot be remembered
        }
        return report;
    }

    public Optional<ConnectionReport> lastConnection() {
        try {
            return knowledgeBase.store().isInitialized() ? knowledgeBase.store().readState(CONNECTION_STATE, ConnectionReport.class) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ================================================================== categories (taxonomy)

    public TaxonomyView taxonomyView() {
        Taxonomy t = knowledgeBase.taxonomy();
        Path file = taxonomyPath();
        boolean custom = config.taxonomyFile() != null && Files.isReadable(config.taxonomyFile());
        return new TaxonomyView(custom, file.toString(), t.hash(), t, t.validate());
    }

    public TaxonomyView saveTaxonomy(Taxonomy taxonomy) {
        List<String> problems = taxonomy.validate();
        if (!problems.isEmpty()) {
            return new TaxonomyView(false, taxonomyPath().toString(), taxonomy.hash(), taxonomy, problems);
        }
        Path file = taxonomyPath();
        try {
            Files.createDirectories(file.getParent());
            com.questioncrawler.core.storage.AtomicFiles.write(file, Json.write(taxonomy));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot write " + file + ": " + e.getMessage(), e);
        }
        if (config.taxonomyFile() == null) {
            configService.save(configFile, Map.of("taxonomy.file", file.getFileName().toString()));
        }
        reload();
        return taxonomyView();
    }

    public TaxonomyView resetTaxonomy() {
        Path file = taxonomyPath();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot remove " + file + ": " + e.getMessage(), e);
        }
        configService.save(configFile, Map.of("taxonomy.file", ""));
        reload();
        return taxonomyView();
    }

    private Path taxonomyPath() {
        return config.taxonomyFile() != null ? config.taxonomyFile() : configFile.getParent().resolve("taxonomy.json");
    }

    /** Shows which categories a sample question would get, and exactly which rules matched. */
    public TryResult tryClassify(Taxonomy draft, String title, String body, List<String> tags) {
        Taxonomy taxonomy = draft != null ? draft : knowledgeBase.taxonomy();
        List<String> problems = taxonomy.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("These category settings have problems: " + String.join("; ", problems));
        }
        Question q = new Question("sample", title == null ? "" : title, body == null ? "" : body, BodyFormat.MARKDOWN, null,
                tags == null ? List.of() : tags, null, null, null, null, List.of(), List.of());
        Classification c = new Classifier(taxonomy).classify(q);
        List<TryMatch> matches = c.categories().stream()
                .map(m -> new TryMatch(m.categoryId(), taxonomy.displayName(m.categoryId()), m.score(), m.matchedRules())).toList();
        boolean fallback = matches.size() == 1 && matches.get(0).categoryId().equals(taxonomy.fallbackCategory());
        return new TryResult(matches, fallback, fallback
                ? "No category reached the minimum score of " + taxonomy.minScore() + ", so it falls back to '" + taxonomy.displayName(taxonomy.fallbackCategory()) + "'."
                : "A category is chosen when its matching rules add up to at least " + taxonomy.minScore() + " points.");
    }

    // ================================================================== overview

    public Overview overview() {
        KnowledgeBase kb = knowledgeBase;
        boolean configExists = Files.exists(configFile);
        String type = config.sourceType();
        String label = switch (type) {
            case "fixture" -> "Demo data";
            case "stackexchange" -> "Stack Exchange: " + config.source().site();
            default -> "Internal site";
        };
        int questions = 0;
        int answers = 0;
        int accepted = 0;
        int unanswered = 0;
        int hidden = 0;
        Map<String, Integer> byStatus = new TreeMap<>();
        List<Overview.CategorySlice> categories = new ArrayList<>();
        List<Overview.RunBrief> runs = new ArrayList<>();
        if (kb.store().isInitialized()) {
            for (ThreadRecord t : kb.store().allThreads()) {
                byStatus.merge(t.status().name(), 1, Integer::sum);
                if (t.isPurged() || !t.status().isRetrievable()) {
                    hidden++;
                    continue;
                }
                questions++;
                answers += t.question().answers().size();
                if (t.question().hasAcceptedAnswer()) accepted++;
                if (t.question().answers().isEmpty()) unanswered++;
            }
            kb.categories().stream().filter(c -> c.questionCount() > 0)
                    .forEach(c -> categories.add(new Overview.CategorySlice(c.id(), c.name(), c.questionCount())));
            kb.store().listReportIds().stream().limit(6).forEach(id -> kb.store().readReport(id, SyncReport.class).ifPresent(r ->
                    runs.add(new Overview.RunBrief(r.runId(), r.mode(), r.outcome(), r.finishedAt(), r.added(), r.updated(), r.failures().size()))));
        }
        Optional<ConnectionReport> conn = lastConnection();
        List<Overview.SetupStep> steps = new ArrayList<>();
        steps.add(new Overview.SetupStep("choose", "Choose where questions come from",
                configExists ? "Source: " + label + (config.tags().isEmpty() ? "" : " · tags: " + String.join(", ", config.tags())) : "Pick a source and the tags you care about.",
                configExists && (!config.tags().isEmpty() || type.equals("fixture")) ? "DONE" : "TODO", "connect"));
        String checkStatus;
        String checkText;
        if (type.equals("fixture")) {
            checkStatus = configExists ? "DONE" : "TODO";
            checkText = "Demo data needs no connection.";
        } else if (conn.isEmpty()) {
            checkStatus = "TODO";
            checkText = "See whether this computer can reach the site and what is needed.";
        } else {
            String verdict = conn.get().verdict();
            checkStatus = verdict.equals("READY") || verdict.equals("NEEDS_ATTENTION") ? "DONE" : "ATTENTION";
            checkText = conn.get().headline();
        }
        steps.add(new Overview.SetupStep("check", "Check we can reach it", checkText, checkStatus, "connect"));
        steps.add(new Overview.SetupStep("collect", "Collect questions and answers",
                questions > 0 ? questions + " questions and " + answers + " answers saved." : "Download the questions into your knowledge folder.",
                questions > 0 ? "DONE" : steps.get(0).status().equals("DONE") ? "TODO" : "LOCKED", "collect"));
        steps.add(new Overview.SetupStep("explore", "Ask questions of your knowledge",
                questions > 0 ? "Search by words, error messages, tags and age." : "Available after the first collection.",
                questions > 0 ? "READY" : "LOCKED", "explore"));
        String next = steps.stream().filter(s -> s.status().equals("TODO") || s.status().equals("ATTENTION")).map(Overview.SetupStep::id).findFirst().orElse("explore");
        List<String> notes = new ArrayList<>();
        if (loadError != null) {
            notes.add(loadError);
        }
        if (kb.store().isInitialized()) {
            // Plain-language versions of the store's technical notes (the CLI keeps its own wording).
            Views.StatusReport status = kb.status();
            if (status.interruptedRunPending()) {
                notes.add("A collection was interrupted. Start a new one and it continues where it stopped.");
            }
            if (status.threadsClassifiedWithOlderTaxonomy() > 0) {
                notes.add(status.threadsClassifiedWithOlderTaxonomy() + " question(s) were sorted with older topic rules. Re-sort them to apply your current rules.");
            }
            if (status.indexedDocuments() == null && status.threadCount() > 0) {
                notes.add("The search index is missing. Re-sort your questions to rebuild it.");
            }
        }
        String window = config.maxQuestionAge();
        return new Overview(configFile.toString(), configExists, type, label, config.tags(), window, steps, next,
                new Overview.Totals(questions, answers, accepted, unanswered, hidden, byStatus), categories, runs, kb.freshness(), runner.isRunning(), notes);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
