package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncProgress;
import com.questioncrawler.core.sync.SyncReport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No limit on how much is collected; instead the person is asked after every N questions and answers (100,000 in the
 * app; a small N here) whether to continue. Also: the sign-in window is brought to the front.
 */
class NoLimitCollectTest {

    @TempDir
    Path tmp;

    @Test
    void continuingCollectsEverythingAndAsksAtEveryInterval() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        List<Long> asked = Collections.synchronizedList(new ArrayList<>());
        List<SyncProgress> waiting = Collections.synchronizedList(new ArrayList<>());
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL), p -> { if (p.waitingForChoice()) waiting.add(p); },
                collected -> { asked.add(collected); return true; }, 3);
        assertEquals(SyncReport.COMPLETE, report.outcome(), String.valueOf(report.abortReason()));
        assertFalse(asked.isEmpty(), "asked at least once");
        for (int i = 1; i < asked.size(); i++) {
            assertTrue(asked.get(i) / 3 > asked.get(i - 1) / 3, "asked once per interval: " + asked);
        }
        assertEquals(asked.size(), waiting.size(), "the screen sees the run waiting each time");
        assertTrue(waiting.get(0).message().contains("Continue?"), waiting.get(0).message());
    }

    @Test
    void stoppingKeepsWhatWasCollectedAndSaysHowToGoOn() {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        SyncReport report = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL), null, collected -> false, 3);
        assertNotNull(report.abortReason());
        assertTrue(report.abortReason().contains("Stopped by you"), report.abortReason());
        assertTrue(report.added() > 0, "what was collected before stopping is kept");
        SyncReport again = kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        assertEquals(SyncReport.COMPLETE, again.outcome(), "starting again continues and completes");
    }

    @Test
    void theSignInWindowIsBroughtToTheFront() throws Exception {
        AtomicInteger fronted = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        BrowserLogin.Launcher launcher = start -> new BrowserLogin.Session() {
            @Override
            public List<BrowserLogin.Cookie> cookies() {
                return polls.incrementAndGet() < 40 ? List.of() : List.of(new BrowserLogin.Cookie("s", "1", "qa.example.com", "/"));
            }

            @Override
            public boolean alive() {
                return true;
            }

            @Override
            public void bringToFront() {
                fronted.incrementAndGet();
            }

            @Override
            public void close() {
            }
        };
        BrowserLogin login = new BrowserLogin("https://qa.example.com", "https://qa.example.com/", launcher, Duration.ofSeconds(20),
                Duration.ofMillis(100), Clock.systemUTC());
        login.cookieHeader(cookie -> true);
        assertTrue(fronted.get() >= 2, "brought to the front when it opened and again shortly after: " + fronted.get());
        assertFalse(BrowserLogin.anyWindowOpen(), "the window is closed once the site lets you in");
    }
}
