package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StackExchangeConfigTest {

    @TempDir
    Path tmp;

    private CoreConfig load(String extra) throws Exception {
        Path file = tmp.resolve("c.properties");
        Files.writeString(file, "storage.root=" + tmp.resolve("data").toString().replace('\\', '/') + "\nsource.type=stackexchange\n" + extra);
        return CoreConfig.load(file, Map.of());
    }

    @Test
    void stackOverflowUrlDerivesSiteHostAndPathAllowList() throws Exception {
        CoreConfig c = load("source.siteUrl=https://stackoverflow.com/\nsync.tags=Artificial Intelligence\nsync.maxQuestionAge=30d\n");
        assertEquals("stackoverflow", c.source().site());
        assertEquals("stackoverflow", c.source().systemId());
        assertEquals("https://api.stackexchange.com/2.3", c.source().baseUrl());
        assertEquals(List.of("api.stackexchange.com"), c.source().allowedHosts());
        assertEquals(List.of("/2.3/"), c.source().allowedPathPrefixes());
        assertEquals("STACKEXCHANGE_KEY", c.source().apiKeyEnv());
        assertEquals(List.of("Artificial Intelligence"), c.tags());
        assertEquals("30d", c.maxQuestionAge());
        assertEquals(List.of(), c.validate());
        assertInstanceOf(StackExchangeAdapter.class, c.createAdapter());
    }

    @Test
    void otherStackExchangeSitesAreRecognised() throws Exception {
        assertEquals("unix", load("source.siteUrl=https://unix.stackexchange.com/\nsync.tags=x\n").source().site());
        assertEquals("serverfault", load("source.siteUrl=https://serverfault.com/\nsync.tags=x\n").source().site());
        assertEquals("askubuntu", load("source.siteUrl=https://www.askubuntu.com\nsync.tags=x\n").source().site());
    }

    @Test
    void invalidSettingsAreReported() throws Exception {
        CoreConfig c = load("sync.tags=x\nsync.maxQuestionAge=recently\nsource.stackexchange.apiBaseUrl=http://api.example.com/2.3\n");
        List<String> problems = c.validate();
        assertTrue(problems.stream().anyMatch(p -> p.contains("sync.maxQuestionAge")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("https")), problems.toString());
    }

    @Test
    void collectionWindowOverrideAndTagNormalization() throws Exception {
        CoreConfig c = load("sync.tags=Artificial Intelligence\n");
        var options = c.syncOptions(null, TagMode.ANY, false, SyncMode.INCREMENTAL, "7d");
        assertEquals("7d", options.maxQuestionAge());
        assertEquals(null, c.syncOptions(null, null, null, SyncMode.INCREMENTAL).maxQuestionAge());
        var filter = new com.questioncrawler.core.source.TagFilter(List.of("Artificial Intelligence"), TagMode.ANY, false);
        assertEquals(List.of("artificial-intelligence"), filter.tags());
        assertTrue(filter.matches(List.of("python", "artificial-intelligence")));
        assertFalse(filter.matches(List.of("artificial", "intelligence")));
    }
}
