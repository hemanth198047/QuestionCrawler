package com.questioncrawler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes an isolated configuration (temp storage, bundled synthetic fixtures) so tests never touch real data. */
final class ApiTestSupport {

    private ApiTestSupport() {}

    static Path writeConfig(Path dir) throws IOException {
        Path storage = dir.resolve("storage");
        Path config = dir.resolve("questioncrawler.properties");
        Files.writeString(config, String.join("\n",
                "storage.root=" + storage.toString().replace('\\', '/'),
                "source.type=fixture",
                "sync.tags=orbit",
                "sync.concurrency=2",
                ""));
        return config;
    }
}
