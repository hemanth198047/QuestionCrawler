package com.questioncrawler.sync;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.sync.Checkpoint;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OPTIONAL built-in scheduler. Disabled unless "Refresh automatically" is on in Settings. It checks every minute
 * whether the configured interval has elapsed, runs a quick update, and runs a full check when the last one is older
 * than seven days (quick updates cannot notice deleted questions).
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    static final Duration FULL_CHECK_EVERY = Duration.ofDays(7);

    private final Workspace workspace;
    private volatile Instant lastStart = Instant.EPOCH;

    public SyncScheduler(Workspace workspace) {
        this.workspace = workspace;
    }

    @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    void tick() {
        CoreConfig config = workspace.config();
        if (!config.schedulerEnabled() || workspace.runner().isRunning()) {
            return;
        }
        Instant now = Instant.now();
        if (Duration.between(lastStart, now).compareTo(config.schedulerInterval()) < 0) {
            return;
        }
        lastStart = now;
        if (config.tags().isEmpty()) {
            log.warn("Automatic refresh is on but no tags are chosen; nothing to do.");
            return;
        }
        SyncMode mode = fullCheckDue(now) ? SyncMode.FULL : SyncMode.INCREMENTAL;
        log.info("Scheduled {} synchronization starting", mode);
        workspace.runner().start(config.syncOptions(null, null, null, mode));
    }

    private boolean fullCheckDue(Instant now) {
        Instant lastFull = workspace.kb().status().checkpoints().stream()
                .map(Checkpoint::lastFullReconcileAt)
                .map(Times::parse)
                .flatMap(Optional::stream)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        return Duration.between(lastFull, now).compareTo(FULL_CHECK_EVERY) >= 0;
    }
}
