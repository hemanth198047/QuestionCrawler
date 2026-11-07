package com.questioncrawler.core.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import com.questioncrawler.core.cli.Args.UsageException;
import com.questioncrawler.core.search.AgeFilter;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.Views;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.storage.BackupService;
import com.questioncrawler.core.storage.StoreException;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command dispatcher. Exit codes: 0 success, 1 error, 2 usage error, 3 finished with failures
 * (partial sync) or no confirmation given.
 */
public final class Cli {

    static final String USAGE = """
            QuestionCrawler - internal Q&A knowledge assistant

            Usage: questioncrawler <command> [options]

            Commands:
              init                         Create configuration, taxonomy and the storage folders
              doctor [--connect]           Validate configuration, storage and (optionally) source connectivity
              spaces                       Confluence: list the spaces this account can read (keys for source.confluence.spaces)
              sync [--full]                Synchronize. First run = initial collection, later runs = incremental.
                                           --full = full reconciliation (re-verifies everything, detects deletions)
              search <text...>             Search the stored knowledge and print evidence
              show <question-id>           Show one question with all its answers
              categories [<category-id>]   List categories, or the questions of one category
              status                       Store size, synchronization state and freshness
              reindex                      Rebuild classification, markdown exports and the search index
              delete-tag --tag <t> --yes   Permanently delete locally stored questions with that tag
              override set <id> <cat...>   Manually set categories (survives synchronization)
              override clear <id>          Remove a manual override
              backup [--out <file>]        Back up the durable data to a zip file
              restore <file> --yes         Restore a backup (then run reindex)
              demo                         Offline demonstration with synthetic data

            Common options:
              --config <file>   Configuration file (default: $QUESTIONCRAWLER_CONFIG, ./questioncrawler.properties,
                                or ~/.questioncrawler/config.properties)
              --storage <dir>   Override storage.root
              --format json     Machine-readable output (also --json)

            sync options:   --tag <t> (repeatable)  --tag-mode any|all  --partial-tags  --full
                            --max-question-age <age>   only collect questions created within this age (use "all" for no limit)
            search options: --tag <t>  --category <id>  --top <n>  --max-chars <n>  --max-passage-chars <n>
            age filters (search, categories <id>): ages look like 12h, 30d, 2w, 6m, 1y and are measured back from now
                            --max-question-age <age>   only questions created within this age (newer than)
                            --min-question-age <age>   only questions older than this age
                            --max-answer-age <age>     only answers created within this age (newer than)
                            --min-answer-age <age>     only answers older than this age
                            A question filter excludes the whole thread; an answer filter excludes only answers.
            """;

    private final PrintStream out;
    private final PrintStream err;

    public Cli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public int run(String[] args) {
        if (args.length == 0 || args[0].equals("help")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            // Options may appear before or after the command: "--storage X search ..." and "search ... --storage X".
            Args a = Args.parse(args, 0);
            if (a.flag("--help") || a.flag("-h")) {
                out.print(USAGE);
                return 0;
            }
            if (a.positional.isEmpty()) {
                throw new UsageException("No command given");
            }
            String command = a.positional.remove(0);
            return switch (command) {
                case "init" -> init(a);
                case "doctor" -> doctor(a);
                case "spaces" -> spaces(a);
                case "sync" -> sync(a);
                case "search" -> search(a);
                case "show" -> show(a);
                case "categories" -> categories(a);
                case "status" -> status(a);
                case "reindex" -> reindex(a);
                case "delete-tag" -> deleteTag(a);
                case "override" -> override(a);
                case "backup" -> backup(a);
                case "restore" -> restore(a);
                case "demo" -> new DemoRunner(out).run(a.value("--storage") == null ? null : Path.of(a.value("--storage")));
                default -> throw new UsageException("Unknown command: " + command);
            };
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run 'questioncrawler help' for usage.");
            return 2;
        } catch (StoreException | IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return 1;
        } catch (java.io.UncheckedIOException e) {
            err.println("I/O error: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------ helpers

    private KnowledgeBase open(Args a) {
        Path file = CoreConfig.locate(a.value("--config"));
        Map<String, String> overrides = new HashMap<>();
        if (a.value("--storage") != null) {
            overrides.put("storage.root", Path.of(a.value("--storage")).toAbsolutePath().toString());
        }
        return new KnowledgeBase(CoreConfig.load(file, overrides));
    }

    private void json(Object value) {
        try {
            out.println(Json.MAPPER.copy().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true).writeValueAsString(value));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static AgeFilter ageFilter(Args a) {
        return new AgeFilter(a.value("--max-question-age"), a.value("--min-question-age"),
                a.value("--max-answer-age"), a.value("--min-answer-age"));
    }

    // ------------------------------------------------------------------ commands

    private int init(Args a) throws IOException {
        Path configFile = a.value("--config") != null ? Path.of(a.value("--config")).toAbsolutePath()
                : Path.of(System.getProperty("user.home"), ".questioncrawler", "config.properties");
        Path storage = a.value("--storage") != null ? Path.of(a.value("--storage")).toAbsolutePath() : CoreConfig.defaultStorageRoot();
        Files.createDirectories(configFile.getParent());
        if (Files.exists(configFile) && !a.flag("--force")) {
            out.println("Configuration already exists, left unchanged: " + configFile + " (use --force to overwrite)");
        } else {
            Files.writeString(configFile, CoreConfig.template(storage));
            out.println("Wrote configuration: " + configFile);
        }
        Path taxonomy = configFile.getParent().resolve("taxonomy.json");
        if (!Files.exists(taxonomy)) {
            try (var in = CoreConfig.class.getClassLoader().getResourceAsStream("defaults/taxonomy.json")) {
                Files.write(taxonomy, in.readAllBytes());
            }
            out.println("Wrote starter taxonomy (edit it to match your organization): " + taxonomy);
        }
        KnowledgeBase kb = new KnowledgeBase(CoreConfig.load(configFile, Map.of()));
        kb.init();
        out.println("Initialized storage: " + kb.store().paths().root());
        out.println();
        out.println("Next steps:");
        out.println("  1. Edit " + configFile + " (storage.root, sync.tags, source settings).");
        out.println("  2. questioncrawler --config \"" + configFile + "\" doctor");
        out.println("  3. questioncrawler --config \"" + configFile + "\" demo      (offline demo, no credentials needed)");
        return 0;
    }

    private int doctor(Args a) {
        KnowledgeBase kb = open(a);
        Views.DoctorReport report = kb.doctor(a.flag("--connect"));
        if (a.json()) json(report); else out.print(TextFormat.doctor(report));
        return report.ok() ? 0 : 1;
    }

    /** Lists the Confluence spaces the configured account can read (to choose source.confluence.spaces). */
    private int spaces(Args a) {
        KnowledgeBase kb = open(a);
        if (!"confluence".equals(kb.config().sourceType())) {
            throw new UsageException("'spaces' needs source.type=confluence");
        }
        try {
            var adapter = (com.questioncrawler.core.source.ConfluenceAdapter) kb.config().createAdapter();
            var listing = adapter.listSpaces(10_000);
            if (a.json()) {
                json(listing);
            } else {
                for (var sp : listing.spaces()) {
                    out.println(String.format("%-20s %-10s %s", sp.key(), sp.type() == null ? "" : sp.type(), sp.name() == null ? "" : sp.name()));
                }
                out.println(listing.spaces().size() + " space(s)" + (listing.complete() ? "" : " (stopped early: more exist)")
                        + ". Put the keys you want in source.confluence.spaces (comma-separated).");
            }
            return 0;
        } catch (com.questioncrawler.core.source.SourceException e) {
            err.println("Could not list Confluence spaces: " + e.getMessage());
            return 1;
        }
    }

    private int sync(Args a) {
        KnowledgeBase kb = open(a);
        TagMode mode = a.value("--tag-mode") == null ? null : TagMode.valueOf(a.value("--tag-mode").toUpperCase(Locale.ROOT));
        Boolean partial = a.flag("--partial-tags") ? Boolean.TRUE : null;
        var options = kb.config().syncOptions(a.all("--tag"), mode, partial, a.flag("--full") ? SyncMode.FULL : SyncMode.INCREMENTAL,
                a.value("--max-question-age"));
        if (options.tags().isEmpty()) {
            throw new UsageException("No tags configured. Pass --tag <name> or set sync.tags in the configuration.");
        }
        SyncReport report = kb.sync(options);
        if (a.json()) json(report); else out.print(TextFormat.syncReport(report));
        return switch (report.outcome()) {
            case SyncReport.COMPLETE -> 0;
            case SyncReport.PARTIAL -> 3;
            default -> 1;
        };
    }

    private int search(Args a) {
        if (a.positional.isEmpty()) {
            throw new UsageException("search needs a query, for example: search \"connection timeout\"");
        }
        KnowledgeBase kb = open(a);
        String query = String.join(" ", a.positional);
        SearchResponse response = kb.search(new SearchRequest(query, a.all("--tag"), a.all("--category"),
                a.intValue("--top", 0), a.intValue("--max-chars", 0), a.intValue("--max-passage-chars", 0), ageFilter(a)));
        if (a.json()) json(response); else out.print(TextFormat.search(response));
        return 0;
    }

    private int show(Args a) {
        if (a.positional.size() != 1) {
            throw new UsageException("show needs exactly one question id (or source:id)");
        }
        var view = open(a).show(a.positional.get(0));
        if (view.isEmpty()) {
            err.println("No question with id '" + a.positional.get(0) + "' in the knowledge store.");
            return 1;
        }
        if (a.json()) json(view.get()); else out.print(TextFormat.show(view.get()));
        return 0;
    }

    private int categories(Args a) {
        KnowledgeBase kb = open(a);
        if (a.positional.isEmpty()) {
            var list = kb.categories();
            if (a.json()) json(list); else out.print(TextFormat.categories(list));
            return 0;
        }
        var listing = kb.category(a.positional.get(0), ageFilter(a));
        if (listing.isEmpty()) {
            err.println("Unknown category '" + a.positional.get(0) + "'. Run 'categories' to list them.");
            return 1;
        }
        if (a.json()) json(listing.get()); else out.print(TextFormat.categoryListing(listing.get()));
        return 0;
    }

    private int status(Args a) {
        Views.StatusReport s = open(a).status();
        if (a.json()) json(s); else out.print(TextFormat.status(s));
        return 0;
    }

    private int reindex(Args a) {
        var r = open(a).reindex();
        if (a.json()) json(r);
        else out.println("Reindexed " + r.threads() + " thread(s) into " + r.indexedDocuments() + " search document(s); skipped " + r.skippedNonRetrievable() + " non-retrievable.");
        return 0;
    }

    private int deleteTag(Args a) {
        List<String> tags = a.all("--tag");
        if (tags.size() != 1) {
            throw new UsageException("usage: delete-tag --tag <name> --yes");
        }
        if (!a.flag("--yes")) {
            err.println("Delete-tag permanently removes locally stored questions (and answers) that carry tag '"
                    + tags.get(0) + "'. Re-run with --yes to confirm.");
            return 3;
        }
        Views.DeleteByTagResult r = open(a).deleteByTag(tags.get(0));
        if (a.json()) json(r);
        else {
            out.println("Deleted " + r.deletedThreads() + " question(s) and " + r.deletedAnswers()
                    + " answer(s) for tag '" + r.tag() + "'."
                    + (r.deletedQuestionIds().isEmpty() ? "" : " Ids: " + String.join(", ", r.deletedQuestionIds()) + "."));
        }
        return 0;
    }

    private int override(Args a) {
        if (a.positional.size() < 2) {
            throw new UsageException("usage: override set <question-id> <category-id>...   |   override clear <question-id>");
        }
        KnowledgeBase kb = open(a);
        String action = a.positional.get(0);
        String id = a.positional.get(1);
        if (action.equals("set")) {
            List<String> cats = a.positional.subList(2, a.positional.size());
            kb.setOverride(id, cats, a.value("--note"));
            out.println("Override set for " + id + ": " + String.join(", ", cats));
        } else if (action.equals("clear")) {
            kb.clearOverride(id);
            out.println("Override cleared for " + id);
        } else {
            throw new UsageException("Unknown override action: " + action);
        }
        return 0;
    }

    private int backup(Args a) throws IOException {
        KnowledgeBase kb = open(a);
        kb.store().ensureReady();
        Path target = a.value("--out") == null ? null : Path.of(a.value("--out"));
        Path written = new BackupService(kb.store().paths()).backup(target);
        out.println("Backup written: " + written);
        return 0;
    }

    private int restore(Args a) throws IOException {
        if (a.positional.size() != 1) {
            throw new UsageException("usage: restore <backup.zip> --yes");
        }
        if (!a.flag("--yes")) {
            err.println("Restore REPLACES threads, classification and state in the storage root. Re-run with --yes to confirm.");
            return 3;
        }
        KnowledgeBase kb = open(a);
        kb.init();
        try (var lock = kb.store().acquireSyncLock()) {
            int files = new BackupService(kb.store().paths()).restore(Path.of(a.positional.get(0)));
            out.println("Restored " + files + " file(s). Run 'reindex' to rebuild markdown exports and the search index.");
        }
        return 0;
    }
}
