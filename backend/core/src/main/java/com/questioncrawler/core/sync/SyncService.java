package com.questioncrawler.core.sync;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.normalize.ContentNormalizer;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.Retrier;
import com.questioncrawler.core.source.SourceAdapter;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.storage.KnowledgeStore;
import com.questioncrawler.core.util.Hashes;
import com.questioncrawler.core.util.Json;
import com.questioncrawler.core.util.Times;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Collection and synchronization engine.
 *
 * <p>Key rules (see docs/SYNC.md):
 * <ul>
 *   <li>Absence from a tag-filtered listing is never treated as deletion. Only a FULL reconciliation
 *       probes missing items, and only explicit source answers change a status.</li>
 *   <li>Listing progress is persisted per page, and fetched threads are persisted one by one, so an
 *       interrupted run resumes without repeating work.</li>
 *   <li>The checkpoint's high-water mark advances only after a run with zero failures.</li>
 *   <li>Network fetches run concurrently (bounded); all persistence happens on the calling thread.</li>
 * </ul>
 */
public final class SyncService {

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final int MAX_PAGES = 100_000;

    private final KnowledgeStore store;
    private final SourceAdapter adapter;
    private final ThreadPublisher publisher;
    private final Retrier retrier;
    private final Clock clock;

    public SyncService(KnowledgeStore store, SourceAdapter adapter, ThreadPublisher publisher, Retrier retrier, Clock clock) {
        this.store = store;
        this.adapter = adapter;
        this.publisher = publisher;
        this.retrier = retrier;
        this.clock = clock;
    }

    public static String scopeKey(String systemId, SyncOptions o) {
        List<String> sorted = o.tags().stream().map(String::toLowerCase).sorted().toList();
        return "s" + Hashes.sha256(systemId + "|" + String.join(",", sorted) + "|" + o.tagMode() + "|" + o.partialTags()).substring(0, 10);
    }

    @SuppressWarnings("try")
    public SyncReport run(SyncOptions options) {
        store.ensureReady();
        if (options.tags().isEmpty()) {
            throw new IllegalArgumentException("At least one tag is required to define the collection scope.");
        }
        try (KnowledgeStore.SyncLock ignored = store.acquireSyncLock()) {
            return new Run(options).execute();
        }
    }

    // ====================================================================== one run

    private final class Run {
        private final SyncOptions options;
        private final String system = adapter.systemId();
        private final String scopeKey;
        private final TagFilter filter;
        private final Instant createdAfter;
        private final String runStateName;
        private final String checkpointName;
        private final Instant started = clock.instant();
        private final List<SyncReport.Failure> failures = new ArrayList<>();
        private final List<SyncReport.StatusChange> statusChanges = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int added;
        private int updated;
        private int unchanged;
        private int skipped;
        private int purged;
        private String abortReason;
        private boolean resumed;

        Run(SyncOptions options) {
            this.options = options;
            this.scopeKey = scopeKey(system, options);
            this.createdAfter = com.questioncrawler.core.util.AgeSpec.beforeIfPresent(options.maxQuestionAge(), started).orElse(null);
            this.filter = new TagFilter(options.tags(), options.tagMode(), options.partialTags(),
                    createdAfter == null ? null : createdAfter.toString());
            this.runStateName = "run-" + scopeKey;
            this.checkpointName = "checkpoint-" + scopeKey;
        }

        SyncReport execute() {
            Checkpoint checkpoint = store.readState(checkpointName, Checkpoint.class).orElse(null);
            RunState state = loadOrCreateRunState(checkpoint);
            try {
                listPhase(state);
                if (abortReason == null) {
                    fetchPhase(state);
                }
                if (abortReason == null && options.mode() == SyncMode.FULL && state.listingComplete) {
                    reconcile(state);
                }
                if (abortReason == null) {
                    applyRetention();
                }
            } finally {
                publisher.finish();
            }
            return finish(state, checkpoint);
        }

        // ------------------------------------------------------------ resume / start

        private RunState loadOrCreateRunState(Checkpoint checkpoint) {
            Optional<RunState> existing = store.readState(runStateName, RunState.class);
            if (existing.isPresent() && existing.get().mode == options.mode()) {
                resumed = true;
                return existing.get();
            }
            RunState state = new RunState();
            state.runId = RUN_ID_TIME.format(started) + "-" + UUID.randomUUID().toString().substring(0, 6);
            state.scopeKey = scopeKey;
            state.mode = options.mode();
            state.startedAt = started.toString();
            if (options.mode() == SyncMode.INCREMENTAL && checkpoint != null && checkpoint.highWaterMark() != null) {
                if (windowWidened(checkpoint)) {
                    // No "changed since" bound: the newly included older questions must be listed too.
                    warnings.add("The collection window was widened (now " + (createdAfter == null ? "the whole history"
                            : "from " + createdAfter) + ", previously from " + checkpoint.coveredFrom()
                            + "), so this run lists the whole window again to backfill older questions.");
                } else {
                    state.since = Times.parse(checkpoint.highWaterMark())
                            .map(hwm -> hwm.minus(options.overlap()).toString()).orElse(null);
                }
            }
            return state;
        }

        /** True when this run's window reaches further back than anything collected before. */
        private boolean windowWidened(Checkpoint cp) {
            Instant previous = Times.parse(cp.coveredFrom()).orElse(null);
            if (previous == null) {
                return false; // earlier runs already covered the whole history (or predate this field)
            }
            return createdAfter == null || createdAfter.isBefore(previous);
        }

        // ------------------------------------------------------------ listing

        private void listPhase(RunState state) {
            String since = adapter.supportsModifiedSince() ? state.since : null;
            while (!state.listingComplete) {
                if (state.pagesListed >= MAX_PAGES) {
                    abort("Listing exceeded " + MAX_PAGES + " pages; stopping to avoid an endless loop.");
                    return;
                }
                final String cursor = state.listingCursor;
                Page<QuestionSummary> page;
                try {
                    page = retrier.call(() -> adapter.listQuestions(filter, since, cursor));
                } catch (SourceException e) {
                    abort("Listing failed: " + e.kind() + ": " + e.getMessage());
                    return;
                }
                state.pagesListed++;
                for (QuestionSummary s : page.items()) {
                    if (state.candidates.containsKey(s.id())) {
                        state.duplicateListings++;
                        state.candidates.merge(s.id(), s.modifiedAt(), Times::max);
                    } else {
                        state.candidates.put(s.id(), s.modifiedAt());
                    }
                }
                if (page.isLast()) {
                    state.listingComplete = true;
                    state.listingCursor = null;
                } else if (Objects.equals(page.nextCursor(), cursor)) {
                    abort("Source returned the same cursor twice; pagination is not advancing.");
                    return;
                } else {
                    state.listingCursor = page.nextCursor();
                }
                store.writeState(runStateName, state);
            }
        }

        // ------------------------------------------------------------ fetching

        private void fetchPhase(RunState state) {
            List<String> toFetch = new ArrayList<>();
            for (Map.Entry<String, String> c : state.candidates.entrySet()) {
                Optional<ThreadRecord> stored = store.loadThread(system, c.getKey());
                if (stored.isPresent() && state.runId.equals(stored.get().syncRunId())) {
                    skipped++; // already fetched by this (resumed) run
                    continue;
                }
                boolean needsFetch = options.mode() == SyncMode.FULL
                        || stored.isEmpty()
                        || stored.get().isPurged()
                        || stored.get().status() != ThreadStatus.ACTIVE
                        || c.getValue() == null
                        || !Objects.equals(stored.get().question().modifiedAt(), c.getValue());
                if (needsFetch) {
                    toFetch.add(c.getKey());
                } else {
                    unchanged++;
                    ensureScope(stored.get());
                }
            }
            fetchAll(toFetch, state, false);
        }

        /** Fetches in bounded chunks; only this thread writes to disk. */
        private void fetchAll(List<String> ids, RunState state, boolean reconciling) {
            int pool = options.concurrency();
            ExecutorService executor = Executors.newFixedThreadPool(pool);
            try {
                int chunk = pool * 4;
                for (int i = 0; i < ids.size() && abortReason == null; i += chunk) {
                    List<String> batch = ids.subList(i, Math.min(ids.size(), i + chunk));
                    List<Future<Question>> futures = new ArrayList<>();
                    for (String id : batch) {
                        Callable<Question> task = () -> fetchThread(id);
                        futures.add(executor.submit(task));
                    }
                    for (int j = 0; j < batch.size(); j++) {
                        String id = batch.get(j);
                        Question fetched = null;
                        SourceException failure = null;
                        try {
                            fetched = futures.get(j).get();
                        } catch (ExecutionException e) {
                            if (e.getCause() instanceof SourceException se) {
                                failure = se;
                            } else if (e.getCause() instanceof RuntimeException re) {
                                throw re; // programming error or simulated crash: let it propagate, run state is on disk
                            } else {
                                throw new IllegalStateException(e.getCause());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            abort("Interrupted");
                            return;
                        }
                        if (abortReason != null) {
                            break;
                        }
                        if (failure != null) {
                            handleFailure(id, failure, reconciling);
                        } else {
                            storeFetched(id, fetched, state, reconciling);
                        }
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }

        /** Question metadata plus ALL answer pages (answers paginate separately from questions). */
        private Question fetchThread(String id) throws SourceException {
            Question q = retrier.call(() -> adapter.fetchQuestion(id));
            List<Answer> answers = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String cursor = null;
            int pages = 0;
            do {
                final String c = cursor;
                Page<Answer> page = retrier.call(() -> adapter.listAnswers(id, c));
                for (Answer a : page.items()) {
                    if (seen.add(a.id())) {
                        answers.add(a);
                    }
                }
                String next = page.nextCursor();
                if (next != null && (next.equals(cursor) || ++pages > 10_000)) {
                    throw new SourceException(SourceException.Kind.FATAL, "Answer pagination is not advancing for question " + id);
                }
                cursor = next;
            } while (cursor != null);
            return q.withAnswers(answers);
        }

        private void storeFetched(String id, Question raw, RunState state, boolean reconciling) {
            Question normalized = ContentNormalizer.normalize(raw);
            String hash = Hashes.sha256(Json.writeCompact(normalized));
            Optional<ThreadRecord> existing = store.loadThread(system, id);
            String now = clock.instant().toString();
            // Items returned by the source's own tag-filtered listing are trusted as matching (the server may
            // apply tag synonyms). Only an item that was ABSENT from the listing and whose tags no longer
            // match is reported as NO_LONGER_MATCHING.
            boolean matches = !reconciling || filter.matches(normalized.tags());
            if (!reconciling && !filter.matches(normalized.tags())) {
                warnings.add("Question " + id + " was listed by the source but its tags do not match the local filter; kept as ACTIVE.");
            }

            ThreadStatus status = matches ? ThreadStatus.ACTIVE : ThreadStatus.NO_LONGER_MATCHING;
            Set<String> scopes = new LinkedHashSet<>(existing.map(ThreadRecord::scopes).orElse(List.of()));
            if (matches) {
                scopes.add(scopeKey);
            } else {
                scopes.remove(scopeKey);
            }
            ThreadStatus previous = existing.map(ThreadRecord::status).orElse(null);
            boolean statusDiffers = previous != status;
            boolean contentChanged = existing.isEmpty() || existing.get().isPurged() || !hash.equals(existing.get().contentHash());

            ThreadRecord record = new ThreadRecord(ThreadRecord.SCHEMA_VERSION, system, normalized, id, now, hash, status,
                    statusDiffers ? now : existing.map(ThreadRecord::statusChangedAt).orElse(now),
                    status == ThreadStatus.NO_LONGER_MATCHING ? "tags no longer match the collection scope" : null,
                    state.runId, existing.map(ThreadRecord::firstSeenAt).orElse(now), new ArrayList<>(scopes));
            store.saveThread(record);

            if (existing.isEmpty()) {
                added++;
            } else if (contentChanged) {
                updated++;
            } else {
                unchanged++;
            }
            if (previous != null && statusDiffers) {
                statusChanges.add(new SyncReport.StatusChange(id, previous.name(), status.name(),
                        status == ThreadStatus.NO_LONGER_MATCHING ? "tags no longer match the scope" : "available again"));
            }
            if (contentChanged || statusDiffers) {
                publisher.published(record);
            }
        }

        private void ensureScope(ThreadRecord thread) {
            if (!thread.scopes().contains(scopeKey) && thread.status() == ThreadStatus.ACTIVE) {
                List<String> scopes = new ArrayList<>(thread.scopes());
                scopes.add(scopeKey);
                store.saveThread(thread.withScopes(scopes));
            }
        }

        // ------------------------------------------------------------ per-item failures

        private void handleFailure(String id, SourceException e, boolean reconciling) {
            switch (e.kind()) {
                case AUTH_FAILED, NOT_CONFIGURED, QUOTA_EXHAUSTED -> abort(e.kind() + ": " + e.getMessage());
                case GONE -> markStatus(id, ThreadStatus.DELETED, "source reported the post as deleted: " + e.getMessage(), e);
                case FORBIDDEN -> markStatus(id, ThreadStatus.INACCESSIBLE, "source refused access: " + e.getMessage(), e);
                case NOT_FOUND -> markStatus(id, ThreadStatus.UNKNOWN, "source says not found (deleted or hidden is not distinguishable)", e);
                default -> {
                    if (reconciling) {
                        markStatus(id, ThreadStatus.UNAVAILABLE, "temporarily unavailable: " + e.getMessage(), e);
                    } else {
                        failures.add(new SyncReport.Failure(id, e.kind().name(), e.getMessage()));
                    }
                }
            }
        }

        private void markStatus(String id, ThreadStatus newStatus, String reason, SourceException cause) {
            Optional<ThreadRecord> existing = store.loadThread(system, id);
            if (existing.isEmpty()) {
                failures.add(new SyncReport.Failure(id, cause.kind().name(), cause.getMessage()));
                return;
            }
            ThreadRecord current = existing.get();
            if (current.status() == newStatus) {
                if (newStatus == ThreadStatus.UNAVAILABLE || newStatus == ThreadStatus.UNKNOWN) {
                    failures.add(new SyncReport.Failure(id, cause.kind().name(), reason));
                }
                return;
            }
            String now = clock.instant().toString();
            ThreadRecord changed = current.withStatus(newStatus, now, reason);
            store.saveThread(changed);
            statusChanges.add(new SyncReport.StatusChange(id, current.status().name(), newStatus.name(), reason));
            if (newStatus.isRetrievable()) {
                publisher.published(changed);
            } else {
                publisher.removed(changed);
            }
            if (newStatus == ThreadStatus.UNAVAILABLE || newStatus == ThreadStatus.UNKNOWN) {
                failures.add(new SyncReport.Failure(id, cause.kind().name(), reason));
            }
        }

        // ------------------------------------------------------------ reconciliation (FULL only)

        private void reconcile(RunState state) {
            Set<String> listed = state.candidates.keySet();
            List<String> missing = new ArrayList<>();
            for (ThreadRecord t : store.allThreads()) {
                if (t.sourceSystem().equals(system) && !t.isPurged() && t.scopes().contains(scopeKey)
                        && t.status().isRetrievable() && !listed.contains(t.questionId())
                        && !createdBeforeWindow(t)) {
                    missing.add(t.questionId());
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            warnings.add(missing.size() + " stored question(s) were absent from the listing and were probed individually; "
                    + "only explicit source answers (gone / forbidden / tags changed) changed their status.");
            fetchAll(missing, state, true);
        }

        /** With a collection-age window, stored questions older than the window are not expected in the listing. */
        private boolean createdBeforeWindow(ThreadRecord t) {
            if (createdAfter == null || t.question() == null) {
                return false;
            }
            return Times.parse(t.question().createdAt()).map(c -> c.isBefore(createdAfter)).orElse(false);
        }

        // ------------------------------------------------------------ retention

        private void applyRetention() {
            Instant now = clock.instant();
            for (ThreadRecord t : store.allThreads()) {
                if (t.isPurged() || t.status().isRetrievable() || !t.sourceSystem().equals(system)) {
                    continue;
                }
                Instant changedAt = Times.parse(t.statusChangedAt()).orElse(now);
                if (!changedAt.plus(Duration.ofDays(options.retentionDays())).isAfter(now)) {
                    ThreadRecord tombstone = t.purged(now.toString());
                    store.saveThread(tombstone);
                    publisher.purged(tombstone);
                    purged++;
                }
            }
        }

        // ------------------------------------------------------------ finishing

        private void abort(String reason) {
            if (abortReason == null) {
                abortReason = reason;
            }
        }

        private SyncReport finish(RunState state, Checkpoint previous) {
            Instant finished = clock.instant();
            boolean aborted = abortReason != null;
            String outcome = aborted ? SyncReport.FAILED : failures.isEmpty() ? SyncReport.COMPLETE : SyncReport.PARTIAL;

            if (!aborted) {
                store.deleteState(runStateName);
                store.writeState(checkpointName, nextCheckpoint(state, previous, outcome, finished));
            }
            List<String> limitations = new ArrayList<>();
            limitations.add("Only content visible to the configured credentials is collected; hidden or restricted items are not counted.");
            limitations.add("A tag-filtered listing cannot prove deletion. Status changes come only from explicit source answers during a full reconciliation.");
            if (createdAfter != null) {
                limitations.add("Collection window: only questions created on or after " + createdAfter + " (" + options.maxQuestionAge()
                        + " ago) were collected; older questions are out of scope, so this is not the complete history.");
            }
            if (options.mode() == SyncMode.INCREMENTAL) {
                limitations.add("Incremental sync cannot detect deletions, tag changes or access revocation, and may miss answers added without a question timestamp change. Run a full reconciliation periodically.");
            }

            SyncReport report = new SyncReport(state.runId, scopeKey, system, options.tags(), options.tagMode().name(),
                    options.partialTags(), options.mode().name(), started.toString(), finished.toString(), outcome, resumed,
                    state.since, state.listingComplete, state.pagesListed, state.candidates.size(), state.duplicateListings,
                    added, updated, unchanged, skipped, statusChanges.size(), purged, List.copyOf(failures),
                    List.copyOf(statusChanges), List.copyOf(warnings), completeness(state, outcome), List.copyOf(limitations), abortReason);
            store.writeReport(state.runId, report);
            return report;
        }

        private String completeness(RunState state, String outcome) {
            if (abortReason != null) {
                return "NOT_COMPLETE: the run was aborted (" + abortReason + "). Re-run to resume.";
            }
            if (options.mode() == SyncMode.FULL && state.listingComplete && failures.isEmpty()) {
                return "COMPLETE_FOR_ACCESSIBLE_SCOPE: all " + state.pagesListed + " listing page(s) were read ("
                        + state.candidates.size() + " questions) and every question was re-fetched and verified. "
                        + "Content hidden from the collecting account is not visible and not counted.";
            }
            if (options.mode() == SyncMode.FULL) {
                return "PARTIAL: the full reconciliation finished with " + failures.size() + " failed item(s); see failures.";
            }
            return "INCREMENTAL: only items changed since " + (state.since == null ? "the beginning (initial collection)" : state.since)
                    + " were examined. This is not a verification that every accessible question is stored; run 'sync --full'.";
        }

        private Checkpoint nextCheckpoint(RunState state, Checkpoint previous, String outcome, Instant finished) {
            String hwm = previous == null ? null : previous.highWaterMark();
            if (failures.isEmpty()) {
                for (String modified : state.candidates.values()) {
                    hwm = Times.max(hwm, modified);
                }
                if (hwm == null) {
                    hwm = started.toString();
                }
            }
            boolean success = failures.isEmpty();
            String lastSuccess = success ? finished.toString() : previous == null ? null : previous.lastSuccessfulRunAt();
            String lastFull = success && options.mode() == SyncMode.FULL ? finished.toString()
                    : previous == null ? null : previous.lastFullReconcileAt();
            String covered;
            if (previous == null) {
                covered = createdAfter == null ? null : createdAfter.toString();
            } else if (previous.coveredFrom() == null || createdAfter == null) {
                covered = null;
            } else {
                Instant prev = Times.parse(previous.coveredFrom()).orElse(createdAfter);
                covered = (createdAfter.isBefore(prev) ? createdAfter : prev).toString();
            }
            return new Checkpoint(scopeKey, system, options.tags(), options.tagMode().name(), options.partialTags(), hwm,
                    finished.toString(), lastSuccess, lastFull, state.runId, outcome, covered);
        }
    }
}
