package com.questioncrawler.core.service;

import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.source.StackExchangeAdapter.TagHit;
import com.questioncrawler.core.source.SourceException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads every tag of the site in the background. A site can have a hundred thousand tags (about a thousand pages), which takes minutes, so
 * the screen polls for progress and for the tags read so far, and can stop the job. Only one listing runs at a time.
 */
public final class TagListingJob {

    /** A safety net against runaway memory use, far above any real site. */
    static final int SAFETY_MAX_TAGS = 5_000_000;

    public record State(String id, boolean running, boolean finished, int loaded, int pages, int pageSize, boolean complete, String stopReason,
                        String problem, boolean hasMoreUnreliable, long siteQuestions) {}

    /** The state and the tags added since position {@code from}. */
    public record Chunk(State state, int from, List<TagHit> tags) {}

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final List<TagHit> tags = new ArrayList<>();
        volatile boolean cancelled;
        volatile boolean running = true;
        volatile boolean finished;
        volatile int pages;
        volatile int pageSize;
        volatile boolean complete;
        volatile String stopReason;
        volatile String problem;
        volatile boolean unreliable;
        volatile long siteQuestions = -1;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tag-listing");
        t.setDaemon(true);
        return t;
    });
    private volatile Run current;

    /** Starts reading all tags (any listing still running is stopped first). */
    public State start(Callable<StackExchangeAdapter> adapterFactory) {
        Run previous = current;
        if (previous != null) {
            previous.cancelled = true;
        }
        Run run = new Run();
        current = run;
        executor.submit(() -> read(run, adapterFactory));
        return state(run);
    }

    private void read(Run run, Callable<StackExchangeAdapter> adapterFactory) {
        try {
            StackExchangeAdapter adapter = adapterFactory.call();
            var result = adapter.readAllTags((page, no) -> {
                synchronized (run.tags) {
                    run.tags.addAll(page);
                    run.pages = no;
                    if (run.tags.size() > SAFETY_MAX_TAGS) {
                        run.stopReason = "the safety limit of " + String.format("%,d", SAFETY_MAX_TAGS) + " tags was reached";
                        return false;
                    }
                }
                return !run.cancelled;
            }, Integer.MAX_VALUE, Long.MAX_VALUE);
            run.pages = result.pagesRead();
            run.pageSize = result.pageSize();
            run.unreliable = result.hasMoreUnreliable();
            run.complete = result.complete();
            if (run.stopReason == null) {
                run.stopReason = result.stopReason();
            } else {
                run.complete = false;
            }
            try {
                run.siteQuestions = adapter.siteQuestionCount(); // nice to have
            } catch (SourceException ignored) {
                run.siteQuestions = -1;
            }
        } catch (SourceException | IllegalStateException | IllegalArgumentException e) {
            run.problem = e.getMessage();
        } catch (Exception e) {
            run.problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            run.running = false;
            run.finished = true;
        }
    }

    /** Stops the listing that is running (what was read so far stays available). */
    public void cancel() {
        Run run = current;
        if (run != null) {
            run.cancelled = true;
        }
    }

    /** The current state, and the tags read since {@code from} (0 for all). */
    public Chunk chunk(int from) {
        Run run = current;
        if (run == null) {
            return new Chunk(new State(null, false, false, 0, 0, 0, false, null, null, false, -1), 0, List.of());
        }
        List<TagHit> fresh;
        int loaded;
        synchronized (run.tags) {
            loaded = run.tags.size();
            int start = Math.max(0, Math.min(from, loaded));
            fresh = new ArrayList<>(run.tags.subList(start, loaded));
        }
        return new Chunk(state(run), Math.max(0, Math.min(from, loaded)), fresh);
    }

    private static State state(Run run) {
        int loaded;
        synchronized (run.tags) {
            loaded = run.tags.size();
        }
        return new State(run.id, run.running, run.finished, loaded, run.pages, run.pageSize, run.complete, run.stopReason, run.problem, run.unreliable, run.siteQuestions);
    }
}
