package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "all" (or "*") as a tag means every tag: every question on the site. */
class AllTagsTest {

    @TempDir
    Path tmp;

    @Test
    void allIsAReservedTagThatOverridesTheOthersAndMatchesEverything() {
        for (String typed : new String[] {"all", "ALL", " All ", "*", "all-tags", "All Tags"}) {
            assertEquals(TagFilter.ALL, TagFilter.normalize(typed), typed);
            assertTrue(new TagFilter(List.of(typed), TagMode.ANY, false).isAll(), typed);
        }
        TagFilter f = new TagFilter(List.of("python", "all", "java"), TagMode.ALL, false);
        assertEquals(List.of("*"), f.tags(), "the other tags add nothing");
        assertTrue(f.matches(List.of()), "even a question without tags");
        assertTrue(f.matches(List.of("anything")));
        assertFalse(new TagFilter(List.of("python"), TagMode.ANY, false).isAll());
        assertFalse(new TagFilter(List.of("python"), TagMode.ANY, false).matches(List.of("java")), "ordinary filters are unchanged");
        assertTrue(TagFilter.asksForAll(List.of("ai", "ALL")));
        assertFalse(TagFilter.asksForAll(List.of("ai", "ml")));
        assertFalse(TagFilter.asksForAll(null));
    }

    private CoreConfig config(String tags, String extra) throws Exception {
        Path file = tmp.resolve("c.properties");
        Files.writeString(file, "storage.root=" + tmp.resolve("data").toString().replace('\\', '/') + "\nsource.type=fixture\nsync.tags=" + tags + "\n" + extra);
        return CoreConfig.load(file, Map.of());
    }

    private int collected(String name, String tags) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        Path file = dir.resolve("c.properties");
        Files.writeString(file, "storage.root=" + dir.resolve("data").toString().replace('\\', '/') + "\nsource.type=fixture\nsync.tags=" + tags + "\n");
        CoreConfig c = CoreConfig.load(file, Map.of());
        SyncReport r = new KnowledgeBase(c, c.createAdapter(), null, Clock.systemUTC()).sync(c.syncOptions(null, null, null, SyncMode.FULL));
        if (tags.equals("all")) {
            assertTrue(r.warnings().stream().noneMatch(w -> w.startsWith("No questions were returned")));
        }
        return r.added();
    }

    @Test
    void allCollectsEveryQuestionWhateverItsTagsWhileANamedTagCollectsOnlyItsOwn() throws Exception {
        int orbit = collected("orbit", "orbit");
        int all = collected("all", "all");
        assertEquals(10, orbit, "the tag orbit");
        assertEquals(11, all, "all reaches the question that does not carry the tag orbit");
        assertTrue(all > orbit);
        assertEquals(all, collected("star", "*"));
        assertEquals(all, collected("mixed", "orbit,all"), "'all' wins over other tags");
        assertEquals(0, collected("none", "nosuchtag"));
    }

    @Test
    void everyTagOnAPublicSiteIsRefusedWithoutATimeWindowAndExplained() throws Exception {
        CoreConfig c = config("all", "source.type=stackexchange\nsource.siteUrl=https://stackoverflow.com/\n");
        // an adapter that never touches the network stands in for the real one: only the guard is under test
        var offline = com.questioncrawler.core.source.FixtureSourceAdapter.fromClasspath(2, 2, "fixtures/demo-site");
        KnowledgeBase kb = new KnowledgeBase(c, offline, null, Clock.systemUTC());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> kb.sync(c.syncOptions(null, null, null, SyncMode.INCREMENTAL)));
        assertTrue(e.getMessage().contains("millions of questions") && e.getMessage().contains("time window"), e.getMessage());
        SyncReport ok = kb.sync(c.syncOptions(null, null, null, SyncMode.INCREMENTAL, "7d"));
        assertTrue(ok.outcome().equals(SyncReport.COMPLETE) || ok.outcome().equals(SyncReport.PARTIAL), "with a window it is allowed");
    }

    @Test
    void theSettingsExplainAllAndWarnWhenItIsMixedWithOtherTagsOrUsedOnAPublicSite() throws Exception {
        var service = new ConfigService(k -> null);
        var field = ConfigService.sections().stream().flatMap(sec -> sec.fields().stream()).filter(f -> f.key().equals("sync.tags")).findFirst().orElseThrow();
        assertTrue(field.help().contains("Type all (or *)"), field.help());
        var mixed = service.validate(new java.util.LinkedHashMap<>(Map.of("source.type", "fixture", "sync.tags", "ai,all")));
        assertTrue(mixed.stream().anyMatch(i -> i.key().equals("sync.tags") && i.message().contains("'all' already collects every question")), mixed.toString());
        var publicSite = service.validate(new java.util.LinkedHashMap<>(Map.of("source.type", "stackexchange", "source.siteUrl", "https://stackoverflow.com/", "sync.tags", "all")));
        assertTrue(publicSite.stream().anyMatch(i -> i.key().equals("sync.maxQuestionAge") && i.message().contains("millions of questions")), publicSite.toString());
        var fine = service.validate(new java.util.LinkedHashMap<>(Map.of("source.type", "fixture", "sync.tags", "all")));
        assertTrue(fine.stream().noneMatch(i -> i.level().equals("error") || i.key().equals("sync.tags")), fine.toString());
    }
}
