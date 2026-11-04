package com.questioncrawler.sync;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.Checkpoint;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OPTIONAL built-in scheduler. Disabled unless sync.scheduler.enabled=true in the shared properties
 * file. It checks every minute whether the configured interval has elapsed, runs an incremental sync,
 * and runs a full reconciliation when the last one is older than seven days (incremental syncs cannot
 * detect deletions).
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    static final Duration FULL_RECONCILE_EVERY = Duration.ofDays(7);

    private final CoreConfig config;
    private final KnowledgeBase knowledgeBase;
    private final SyncJobService jobs;
    private volatile Instant lastStart = Instant.EPOCH;

    public SyncScheduler(CoreConfig config, KnowledgeBase knowledgeBase, SyncJobService jobs) {
        this.config = config;
        this.knowledgeBase = knowledgeBase;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    void tick() {
        if (!config.schedulerEnabled() || jobs.isRunning()) {
            return;
        }
        Instant now = Instant.now();
        if (Duration.between(lastStart, now).compareTo(config.schedulerInterval()) < 0) {
            return;
        }
        if (config.tags().isEmpty()) {
            log.warn("Scheduler is enabled but sync.tags is empty; nothing to synchronize.");
            lastStart = now;
            return;
        }
        lastStart = now;
        SyncMode mode = fullReconcileDue(now) ? SyncMode.FULL : SyncMode.INCREMENTAL;
        log.info("Scheduled {} synchronization starting", mode);
        jobs.start(config.syncOptions(null, null, null, mode));
    }

    private boolean fullReconcileDue(Instant now) {
        Instant lastFull = knowledgeBase.status().checkpoints().stream()
                .map(Checkpoint::lastFullReconcileAt)
                .map(Times::parse)
                .flatMap(java.util.Optional::stream)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        return Duration.between(lastFull, now).compareTo(FULL_RECONCILE_EVERY) >= 0;
    }
}
