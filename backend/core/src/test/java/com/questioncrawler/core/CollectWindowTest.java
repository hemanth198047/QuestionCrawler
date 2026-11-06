package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.SyncRunner;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.AgeSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Collect page's "Everything" must mean everything even when the saved settings carry a time window, and a run that collects
 * nothing must say so.
 */
class CollectWindowTest {

    @TempDir
    Path tmp;

    private CoreConfig config(String extra) throws Exception {
        Path file = tmp.resolve("c.properties");
        Files.writeString(file, "storage.root=" + tmp.resolve("data").toString().replace('\\', '/') + "\nsource.type=fixture\nsync.tags=orbit\n" + extra);
        return CoreConfig.load(file, Map.of());
    }

    @Test
    void anExplicitEverythingOverridesTheSavedWindow() throws Exception {
        CoreConfig c = config("sync.maxQuestionAge=30d\n");
        assertEquals("30d", c.syncOptions(null, null, null, SyncMode.FULL).maxQuestionAge(), "nothing chosen: the saved window applies");
        assertEquals("30d", c.syncOptions(null, null, null, SyncMode.FULL, null).maxQuestionAge());
        assertEquals("7d", c.syncOptions(null, null, null, SyncMode.FULL, "7d").maxQuestionAge(), "a chosen window wins");
        for (String everything : new String[] {"all", "ALL", " everything ", "none", "unlimited"}) {
            assertNull(c.syncOptions(null, null, null, SyncMode.FULL, everything).maxQuestionAge(), everything);
            assertTrue(AgeSpec.isEverything(everything));
        }
        assertFalse(AgeSpec.isEverything("30d") || AgeSpec.isEverything(null) || AgeSpec.isEverything(""));
    }

    @Test
    void everythingWithAFullCheckCollectsOldQuestionsThatTheSavedWindowHides() throws Exception {
        CoreConfig c = config("sync.maxQuestionAge=30d\n");
        // what the Collect page used to do: the saved 30-day window silently applied
        SyncReport before = new KnowledgeBase(c, c.createAdapter(), null, Clock.systemUTC()).sync(c.syncOptions(null, null, null, SyncMode.FULL, null));
        assertEquals(SyncReport.COMPLETE, before.outcome());
        assertEquals(0, before.added(), "the demo questions are older than 30 days");
        assertTrue(before.warnings().stream().anyMatch(w -> w.startsWith("No questions were returned for the tag(s) orbit") && w.contains("last 30d") && w.contains("Everything")), before.warnings().toString());

        CoreConfig c2 = config("sync.maxQuestionAge=30d\n");
        SyncReport after = new KnowledgeBase(c2, c2.createAdapter(), null, Clock.systemUTC()).sync(c2.syncOptions(null, null, null, SyncMode.FULL, "all"));
        assertEquals(SyncReport.COMPLETE, after.outcome());
        assertEquals(10, after.added(), "Everything now really is everything");
        assertEquals(10, after.questionsListed());
        assertTrue(after.warnings().stream().noneMatch(w -> w.startsWith("No questions were returned")));
        assertTrue(after.limitations().stream().noneMatch(l -> l.startsWith("Collection window")), "no window note when there is no window");
    }

    @Test
    void aRunThatFindsNothingSaysSoAndNamesTheTags() throws Exception {
        CoreConfig c = config("");
        SyncReport r = new KnowledgeBase(c, c.createAdapter(), null, Clock.systemUTC()).sync(c.syncOptions(java.util.List.of("nosuchtag"), null, null, SyncMode.INCREMENTAL));
        assertEquals(0, r.questionsListed());
        String warning = r.warnings().stream().filter(w -> w.startsWith("No questions were returned")).findFirst().orElseThrow();
        assertTrue(warning.contains("nosuchtag") && warning.contains("no time limit") && warning.contains("See what the API really answers"), warning);
    }

    @Test
    void aFailureThatIsNotAnOrdinaryExceptionIsStillRecordedAndTheRunnerIsFreeAgain() throws Exception {
        SyncRunner runner = new SyncRunner(() -> { throw new NoClassDefFoundError("com/example/Missing"); });
        assertTrue(runner.start(config("").syncOptions(null, null, null, SyncMode.INCREMENTAL)));
        long end = System.currentTimeMillis() + 3000;
        while (runner.isRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertFalse(runner.isRunning());
        assertEquals("com/example/Missing", runner.state().lastError(), "the screen can show why the run ended");
        assertTrue(runner.start(config("").syncOptions(null, null, null, SyncMode.INCREMENTAL)), "a new run can start");
    }
}
