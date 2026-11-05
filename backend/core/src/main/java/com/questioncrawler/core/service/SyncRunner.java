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
            } catch (RuntimeException ignored) {
                // recorded in lastError
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
        try {
            SyncReport report = knowledgeBase.get().sync(options, p -> progress = p);
            lastReport = report;
            return report;
        } catch (RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
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
