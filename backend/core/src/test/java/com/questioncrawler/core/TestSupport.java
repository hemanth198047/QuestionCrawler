package com.questioncrawler.core;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.FixtureSourceAdapter;
import com.questioncrawler.core.source.Page;
import com.questioncrawler.core.source.QuestionSummary;
import com.questioncrawler.core.source.Retrier;
import com.questioncrawler.core.source.SourceAdapter;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

final class TestSupport {

    private TestSupport() {}

    static FixtureSourceAdapter siteV1() {
        return FixtureSourceAdapter.fromClasspath(2, 2, "fixtures/demo-site");
    }

    static FixtureSourceAdapter siteV2() {
        return FixtureSourceAdapter.fromClasspath(2, 2, "fixtures/demo-site", "fixtures/demo-site-update");
    }

    /** Retrier that never really sleeps and records the waits it would have made. */
    static Retrier fastRetrier(int attempts, List<Duration> waits) {
        return new Retrier(attempts, Duration.ofSeconds(1), Duration.ofSeconds(30), d -> waits.add(d), () -> 0.0);
    }

    static KnowledgeBase kb(Path root, SourceAdapter adapter) {
        return kb(root, adapter, fastRetrier(3, Collections.synchronizedList(new ArrayList<>())));
    }

    static KnowledgeBase kb(Path root, SourceAdapter adapter, Retrier retrier) {
        return new KnowledgeBase(CoreConfig.forStorage(root), adapter, retrier, Clock.systemUTC());
    }

    static SyncOptions opts(SyncMode mode) {
        return opts(mode, 30);
    }

    static SyncOptions opts(SyncMode mode, int retentionDays) {
        return new SyncOptions(List.of("orbit"), TagMode.ANY, false, mode, Duration.ofMinutes(60), 3, retentionDays);
    }

    /** Wraps an adapter to count calls, inject failures and rewrite pages. */
    static final class Instrumented implements SourceAdapter {

        @FunctionalInterface
        interface Hook {
            void run(String operation, String id) throws SourceException;
        }

        final SourceAdapter delegate;
        final AtomicInteger listCalls = new AtomicInteger();
        final AtomicInteger fetchCalls = new AtomicInteger();
        final AtomicInteger answerCalls = new AtomicInteger();
        final List<String> fetchedIds = Collections.synchronizedList(new ArrayList<>());
        volatile Hook hook = (op, id) -> { };
        volatile UnaryOperator<Page<QuestionSummary>> listTransform = p -> p;
        volatile UnaryOperator<Page<Answer>> answerTransform = p -> p;

        Instrumented(SourceAdapter delegate) {
            this.delegate = delegate;
        }

        @Override public String systemId() { return delegate.systemId(); }

        @Override public boolean supportsModifiedSince() { return delegate.supportsModifiedSince(); }

        @Override
        public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
            listCalls.incrementAndGet();
            hook.run("list", cursor);
            return listTransform.apply(delegate.listQuestions(filter, modifiedSince, cursor));
        }

        @Override
        public Question fetchQuestion(String id) throws SourceException {
            fetchCalls.incrementAndGet();
            fetchedIds.add(id);
            hook.run("fetch", id);
            return delegate.fetchQuestion(id);
        }

        @Override
        public Page<Answer> listAnswers(String questionId, String cursor) throws SourceException {
            answerCalls.incrementAndGet();
            hook.run("answers", questionId);
            return answerTransform.apply(delegate.listAnswers(questionId, cursor));
        }

        @Override public String ping() throws SourceException { return delegate.ping(); }
    }
}
