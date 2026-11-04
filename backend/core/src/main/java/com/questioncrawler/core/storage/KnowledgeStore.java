package com.questioncrawler.core.storage;

import com.questioncrawler.core.model.CategoryOverride;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * File-system knowledge store. There is no database: every record is a JSON file written atomically.
 * Thread JSON, classification JSON and overrides are the durable truth; markdown and the search index
 * are derived and can always be rebuilt.
 */
public final class KnowledgeStore {

    public static final int SCHEMA_VERSION = 1;

    public record Manifest(int schemaVersion, String createdAt, String createdBy) {}

    /** Held for the duration of a synchronization so two syncs can never interleave. */
    public static final class SyncLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private SyncLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                lock.release();
                channel.close();
            } catch (IOException ignored) {
                // releasing a lock during shutdown is best effort
            }
        }
    }

    private final StoragePaths paths;

    public KnowledgeStore(Path root) {
        this.paths = new StoragePaths(root);
    }

    public StoragePaths paths() {
        return paths;
    }

    // ------------------------------------------------------------------ lifecycle / schema

    public void init() {
        try {
            for (Path dir : List.of(paths.threadsDir(), paths.classificationDir(), paths.markdownDir(),
                    paths.indexDir(), paths.stateDir(), paths.reportsDir(), paths.backupsDir())) {
                Files.createDirectories(dir);
            }
            if (!Files.exists(paths.manifest())) {
                AtomicFiles.write(paths.manifest(), Json.write(new Manifest(SCHEMA_VERSION, Instant.now().toString(), "questioncrawler")));
            }
        } catch (IOException e) {
            throw new StoreException("Cannot initialize storage at " + paths.root() + ": " + e.getMessage(), e);
        }
        ensureReady();
    }

    public boolean isInitialized() {
        return Files.exists(paths.manifest());
    }

    /** Fails with an actionable message unless the store exists and its schema is supported. */
    public Manifest ensureReady() {
        if (!isInitialized()) {
            throw new StoreException("Knowledge store not found at " + paths.root()
                    + ". Run 'init' (and 'sync' or 'demo') first, or check storage.root in your configuration.");
        }
        try {
            Manifest manifest = Json.MAPPER.readValue(Files.readString(paths.manifest()), Manifest.class);
            if (manifest.schemaVersion() > SCHEMA_VERSION) {
                throw new StoreException("Storage schema version " + manifest.schemaVersion()
                        + " is newer than this software supports (" + SCHEMA_VERSION + "). Upgrade the software.");
            }
            return manifest;
        } catch (IOException e) {
            throw new StoreException("Cannot read " + paths.manifest() + ": " + e.getMessage(), e);
        }
    }

    public SyncLock acquireSyncLock() {
        try {
            Files.createDirectories(paths.stateDir());
            FileChannel channel = FileChannel.open(paths.lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new StoreException("Another synchronization is already running for " + paths.root() + ".");
            }
            return new SyncLock(channel, lock);
        } catch (IOException e) {
            throw new StoreException("Cannot lock the store: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ threads

    public void saveThread(ThreadRecord record) {
        writeJson(paths.threadFile(record.sourceSystem(), record.questionId()), record);
    }

    public Optional<ThreadRecord> loadThread(String source, String id) {
        return readJson(paths.threadFile(source, id), ThreadRecord.class);
    }

    public List<ThreadRecord> allThreads() {
        List<ThreadRecord> result = new ArrayList<>();
        if (!Files.isDirectory(paths.threadsDir())) {
            return result;
        }
        try (Stream<Path> files = Files.walk(paths.threadsDir(), 2)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p ->
                    readJson(p, ThreadRecord.class).ifPresent(result::add));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(ThreadRecord::key));
        return result;
    }

    /** Accepts either "source:id" or a bare id; a bare id must be unambiguous. */
    public Optional<ThreadRecord> findThread(String idOrKey) {
        int colon = idOrKey.indexOf(':');
        if (colon > 0) {
            return loadThread(idOrKey.substring(0, colon), idOrKey.substring(colon + 1));
        }
        List<ThreadRecord> matches = allThreads().stream().filter(t -> t.questionId().equals(idOrKey)).toList();
        if (matches.size() > 1) {
            throw new StoreException("Question id '" + idOrKey + "' exists in several sources; use source:id.");
        }
        return matches.stream().findFirst();
    }

    public void deleteThreadFile(String source, String id) {
        deleteQuietly(paths.threadFile(source, id));
    }

    // ------------------------------------------------------------------ classification

    public void saveClassification(String source, String id, Classification classification) {
        writeJson(paths.classificationFile(source, id), classification);
    }

    public Optional<Classification> loadClassification(String source, String id) {
        return readJson(paths.classificationFile(source, id), Classification.class);
    }

    public void deleteClassification(String source, String id) {
        deleteQuietly(paths.classificationFile(source, id));
    }

    public Map<String, CategoryOverride> loadOverrides() {
        Optional<Map<String, CategoryOverride>> loaded = readJsonMap(paths.overridesFile());
        return loaded.orElseGet(TreeMap::new);
    }

    public void saveOverrides(Map<String, CategoryOverride> overrides) {
        writeJson(paths.overridesFile(), new TreeMap<>(overrides));
    }

    // ------------------------------------------------------------------ state (checkpoints, run state, reports)

    public <T> Optional<T> readState(String name, Class<T> type) {
        return readJson(stateFile(name), type);
    }

    public void writeState(String name, Object value) {
        writeJson(stateFile(name), value);
    }

    public void deleteState(String name) {
        deleteQuietly(stateFile(name));
    }

    private Path stateFile(String name) {
        if (!name.matches("[A-Za-z0-9._-]+") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid state name: " + name);
        }
        return paths.stateDir().resolve(name + ".json");
    }

    /** Names (without .json) of state files starting with the prefix, for example "checkpoint-". */
    public List<String> listStateNames(String prefix) {
        if (!Files.isDirectory(paths.stateDir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(paths.stateDir())) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && n.startsWith(prefix))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeReport(String runId, Object report) {
        writeJson(paths.reportsDir().resolve(runId + ".json"), report);
    }

    /** Report file names, newest first (run ids start with a sortable UTC timestamp). */
    public List<String> listReportIds() {
        if (!Files.isDirectory(paths.reportsDir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(paths.reportsDir())) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public <T> Optional<T> readReport(String runId, Class<T> type) {
        return readJson(paths.reportsDir().resolve(runId + ".json"), type);
    }

    // ------------------------------------------------------------------ helpers

    private void writeJson(Path file, Object value) {
        try {
            AtomicFiles.write(file, Json.write(value));
        } catch (IOException e) {
            throw new StoreException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private <T> Optional<T> readJson(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.MAPPER.readValue(Files.readString(file), type));
        } catch (IOException e) {
            throw new StoreException("Cannot read " + file + " (corrupt?): " + e.getMessage()
                    + ". Restore from a backup or delete the file and re-sync.", e);
        }
    }

    private Optional<Map<String, CategoryOverride>> readJsonMap(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.MAPPER.readValue(Files.readString(file),
                    Json.MAPPER.getTypeFactory().constructMapType(TreeMap.class, String.class, CategoryOverride.class)));
        } catch (IOException e) {
            throw new StoreException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StoreException("Cannot delete " + file + ": " + e.getMessage(), e);
        }
    }
}
