package com.questioncrawler.sync;

import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncReport;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs synchronizations on a single background thread so a request returns immediately. Only one
 * run at a time is allowed inside this process; the core's file lock additionally protects against a
 * CLI sync started in another process.
 */
@Service
public class SyncJobService {

    private static final Logger log = LoggerFactory.getLogger(SyncJobService.class);

    /** Snapshot returned by GET /api/sync/status. */
    public record State(boolean running, String startedAt, String mode, SyncReport lastReport, String lastError) {}

    private final KnowledgeBase knowledgeBase;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sync-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String startedAt;
    private volatile String mode;
    private volatile SyncReport lastReport;
    private volatile String lastError;

    public SyncJobService(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /** Starts a background run. Returns false when one is already running. */
    public boolean start(SyncOptions options) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        executor.submit(() -> {
            try {
                execute(options);
            } catch (RuntimeException e) {
                log.warn("Background synchronization failed: {}", e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** Runs in the calling thread (used by POST /api/sync?wait=true and by tests). */
    public SyncReport runBlocking(SyncOptions options) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A synchronization is already running.");
        }
        try {
            return execute(options);
        } finally {
            running.set(false);
        }
    }

    private SyncReport execute(SyncOptions options) {
        startedAt = Instant.now().toString();
        mode = options.mode().name();
        lastError = null;
        try {
            SyncReport report = knowledgeBase.sync(options);
            lastReport = report;
            return report;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            throw e;
        }
    }

    public State state() {
        return new State(running.get(), startedAt, mode, lastReport, lastError);
    }

    public boolean isRunning() {
        return running.get();
    }
}
