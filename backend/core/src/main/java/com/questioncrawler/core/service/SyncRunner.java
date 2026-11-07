package com.questioncrawler.core.service;

import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncProgress;
import com.questioncrawler.core.sync.SyncReport;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runs one synchronization at a time on a background thread and exposes live progress. */
public final class SyncRunner {

    private static final System.Logger LOG = System.getLogger("questioncrawler.sync");

    public record State(boolean running, String startedAt, String mode, SyncProgress progress, SyncReport lastReport, String lastError) {}

    private final Supplier<KnowledgeBase> knowledgeBase;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sync-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String startedAt;
    private volatile String mode;
    private volatile SyncProgress progress;
    private volatile SyncReport lastReport;
    private volatile String lastError;
    /** Set while a run waits for the person to say whether to continue after another 100,000. */
    private volatile java.util.concurrent.CompletableFuture<Boolean> pendingChoice;

    /** How long an unanswered "continue?" waits before the run stops by itself (everything collected so far is kept). */
    static final java.time.Duration CHOICE_TIMEOUT = java.time.Duration.ofHours(24);

    /** The person's answer to "continue after another 100,000?". False when nothing is waiting. */
    public boolean answer(boolean proceed) {
        var waiting = pendingChoice;
        return waiting != null && waiting.complete(proceed);
    }

    private boolean askToContinue(long collected) throws InterruptedException {
        var choice = new java.util.concurrent.CompletableFuture<Boolean>();
        pendingChoice = choice;
        try {
            return choice.get(CHOICE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            return false;
        } finally {
            pendingChoice = null;
        }
    }

    public SyncRunner(Supplier<KnowledgeBase> knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /** Starts in the background; false when a run is already in progress. */
    public boolean start(SyncOptions options) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        begin(options);
        executor.submit(() -> {
            try {
                execute(options);
            } catch (RuntimeException | Error ignored) {
                // recorded in lastError (and logged)
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public SyncReport runBlocking(SyncOptions options) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A synchronization is already running.");
        }
        begin(options);
        try {
            return execute(options);
        } finally {
            running.set(false);
        }
    }

    private void begin(SyncOptions options) {
        startedAt = Instant.now().toString();
        mode = options.mode().name();
        progress = null;
        lastError = null;
    }

    private SyncReport execute(SyncOptions options) {
        LOG.log(System.Logger.Level.INFO, "Collection started: mode={0}, tags={1}, window={2}", options.mode(), options.tags(),
                options.maxQuestionAge() == null ? "everything" : options.maxQuestionAge());
        try {
            SyncReport report = knowledgeBase.get().sync(options, p -> progress = p, this::askToContinue);
            lastReport = report;
            LOG.log(System.Logger.Level.INFO, "Collection ended: outcome={0}, found={1}, new={2}, changed={3}, problems={4}{5}", report.outcome(), report.questionsListed(),
                    report.added(), report.updated(), report.failures().size(), report.abortReason() == null ? "" : ", reason: " + report.abortReason());
            return report;
        } catch (RuntimeException | Error e) {
            lastError = com.questioncrawler.core.search.LuceneDoctor.isLuceneProblem(e) ? com.questioncrawler.core.search.LuceneDoctor.explain(e)
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.log(System.Logger.Level.ERROR, "Collection failed: " + lastError, e);
            throw e;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public State state() {
        return new State(running.get(), startedAt, mode, progress, lastReport, lastError);
    }
}
