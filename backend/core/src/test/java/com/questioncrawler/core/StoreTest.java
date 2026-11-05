package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.storage.AtomicFiles;
import com.questioncrawler.core.storage.BackupService;
import com.questioncrawler.core.storage.KnowledgeStore;
import com.questioncrawler.core.storage.StoragePaths;
import com.questioncrawler.core.storage.StoreException;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.util.SafeNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

    @TempDir
    Path tmp;

    @Test
    void atomicWritesLeaveNoTemporaryFilesAndReplaceContent() throws Exception {
        Path file = tmp.resolve("a/b/data.json");
        AtomicFiles.write(file, "one");
        AtomicFiles.write(file, "two");
        assertEquals("two", Files.readString(file));
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(List.of("data.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void identifiersFromTheSourceCannotEscapeTheStorageRoot() {
        StoragePaths paths = new StoragePaths(tmp);
        for (String evil : List.of("../../etc/passwd", "..\\..\\x", "a/b", "/abs", "..", "id with spaces", "")) {
            Path p = paths.threadFile("fixture", evil);
            assertTrue(p.startsWith(tmp.toAbsolutePath().normalize()), p.toString());
            assertTrue(p.getParent().equals(paths.threadsDir().resolve("fixture")), p.toString());
        }
        assertEquals("1001", SafeNames.fileName("1001"));
    }

    @Test
    void newerSchemaVersionsAreRefusedWithAnActionableMessage() throws Exception {
        KnowledgeStore store = new KnowledgeStore(tmp);
        store.init();
        Files.writeString(store.paths().manifest(), "{\"schemaVersion\": 99}");
        StoreException e = assertThrows(StoreException.class, store::ensureReady);
        assertTrue(e.getMessage().contains("newer"));
    }

    @Test
    void missingStoreGivesAnActionableMessage() {
        KnowledgeStore store = new KnowledgeStore(tmp.resolve("nothing-here"));
        StoreException e = assertThrows(StoreException.class, store::ensureReady);
        assertTrue(e.getMessage().contains("init"));
        KnowledgeBase kb = TestSupport.kb(tmp.resolve("nothing-here"), TestSupport.siteV1());
        assertThrows(StoreException.class, () -> kb.search(SearchRequest.of("x")));
        assertFalse(kb.status().initialized());
    }

    @Test
    void corruptRecordsReportTheFileAndTheRemedy() throws Exception {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        Files.writeString(kb.store().paths().threadFile("fixture", "1001"), "{ not json");
        StoreException e = assertThrows(StoreException.class, () -> kb.store().loadThread("fixture", "1001"));
        assertTrue(e.getMessage().contains("1001.json") && e.getMessage().contains("backup"));
    }

    @Test
    void backupAndRestoreRoundTripThenReindexRestoresSearch() throws Exception {
        KnowledgeBase kb = TestSupport.kb(tmp.resolve("store"), TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));
        kb.setOverride("1001", List.of("performance"), "kept across restore");
        Path zip = new BackupService(kb.store().paths()).backup(tmp.resolve("backup.zip"));
        assertTrue(Files.size(zip) > 0);

        KnowledgeBase fresh = TestSupport.kb(tmp.resolve("restored"), TestSupport.siteV1());
        fresh.init();
        int files = new BackupService(fresh.store().paths()).restore(zip);
        assertTrue(files > 10);
        assertEquals(10, fresh.store().allThreads().size());
        var result = fresh.reindex();
        assertEquals(10, result.threads());
        assertEquals("1011", fresh.search(SearchRequest.of("ManifestLoader.loadFromPath")).evidence().get(0).questionId());
        assertEquals(List.of("performance"), fresh.show("1001").orElseThrow().categories(), "manual overrides are part of the backup");
    }

    @Test
    void restoreRejectsArchivesThatTryToWriteOutsideTheStore() throws Exception {
        Path evil = tmp.resolve("evil.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) {
            out.putNextEntry(new java.util.zip.ZipEntry("../escape.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        KnowledgeStore store = new KnowledgeStore(tmp.resolve("victim"));
        store.init();
        assertThrows(java.io.IOException.class, () -> new BackupService(store.paths()).restore(evil));
        assertFalse(Files.exists(tmp.resolve("escape.txt")));
    }

    @Test
    void configurationLoadsFromPropertiesWithoutSecretsAndValidates() throws Exception {
        Path cfg = tmp.resolve("conf/questioncrawler.properties");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, CoreConfig.template(tmp.resolve("data")).replace("source.type=fixture", "source.type=live")
                .replace("source.auth.method=none", "source.auth.method=token"));
        CoreConfig c = CoreConfig.load(cfg, Map.of());
        assertEquals(tmp.resolve("data").toAbsolutePath().normalize(), c.storageRoot());
        assertEquals(List.of("orbit"), c.tags());
        List<String> problems = c.validate();
        assertTrue(problems.stream().anyMatch(p -> p.contains("source.baseUrl")), problems.toString());
        assertEquals("INTERNAL_QA_TOKEN", c.source().tokenEnv());   // only the variable NAME is configuration
        // No key may carry a secret VALUE. Keys ending in "Env" only NAME an environment variable (and may be set to such a name).
        List<String> secretLikeAssignments = Files.readAllLines(cfg).stream()
                .filter(l -> !l.stripLeading().startsWith("#") && l.contains("="))
                .filter(l -> {
                    String key = l.substring(0, l.indexOf('=')).toLowerCase();
                    String value = l.substring(l.indexOf('=') + 1);
                    boolean secretKey = key.matches(".*(password|secret|apikey|api-key|token).*") && !key.endsWith("env");
                    return secretKey && !value.isBlank();
                })
                .toList();
        assertEquals(List.of(), secretLikeAssignments);
    }
}
