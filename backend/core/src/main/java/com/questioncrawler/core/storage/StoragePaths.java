package com.questioncrawler.core.storage;

import com.questioncrawler.core.util.SafeNames;
import java.nio.file.Path;

/**
 * Layout of the storage root. JSON under threads/, classification/ and state/ is the source of truth;
 * markdown/ and index/ are derived and can be rebuilt with the reindex command.
 */
public record StoragePaths(Path root) {

    public StoragePaths {
        root = root.toAbsolutePath().normalize();
    }

    public Path manifest() { return root.resolve("storage-manifest.json"); }
    public Path threadsDir() { return root.resolve("threads"); }
    public Path classificationDir() { return root.resolve("classification"); }
    public Path overridesFile() { return root.resolve("classification").resolve("overrides.json"); }
    public Path markdownDir() { return root.resolve("markdown"); }
    public Path indexDir() { return root.resolve("index"); }
    public Path stateDir() { return root.resolve("state"); }
    public Path reportsDir() { return root.resolve("state").resolve("reports"); }
    public Path backupsDir() { return root.resolve("backups"); }
    public Path lockFile() { return root.resolve("state").resolve("sync.lock"); }

    public Path threadFile(String source, String id) {
        return threadsDir().resolve(SafeNames.fileName(source)).resolve(SafeNames.fileName(id) + ".json");
    }

    public Path classificationFile(String source, String id) {
        return classificationDir().resolve(SafeNames.fileName(source)).resolve(SafeNames.fileName(id) + ".json");
    }

    public Path markdownQuestionFile(String source, String id) {
        return markdownDir().resolve("questions").resolve(SafeNames.fileName(source)).resolve(SafeNames.fileName(id) + ".md");
    }

    public Path markdownCategoryFile(String categoryId) {
        return markdownDir().resolve("categories").resolve(SafeNames.fileName(categoryId) + ".md");
    }
}
