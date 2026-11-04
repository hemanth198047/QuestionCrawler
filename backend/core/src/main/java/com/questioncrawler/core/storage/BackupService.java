package com.questioncrawler.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup and restore of the durable data (threads, classification, state, manifest). Derived data
 * (markdown, index) is not backed up: run 'reindex' after a restore to rebuild it.
 */
public final class BackupService {

    private static final List<String> INCLUDED = List.of("storage-manifest.json", "threads", "classification", "state");

    private final StoragePaths paths;

    public BackupService(StoragePaths paths) {
        this.paths = paths;
    }

    public Path backup(Path target) throws IOException {
        Path out = target != null ? target : paths.backupsDir().resolve("backup-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + ".zip");
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (String name : INCLUDED) {
                Path start = paths.root().resolve(name);
                if (!Files.exists(start)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(start)) {
                    for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock"))::iterator) {
                        zip.putNextEntry(new ZipEntry(paths.root().relativize(p).toString().replace('\\', '/')));
                        Files.copy(p, zip);
                        zip.closeEntry();
                    }
                }
            }
        }
        return out;
    }

    /** Replaces the durable data with the archive's contents. Guards against zip-slip. */
    public int restore(Path archive) throws IOException {
        int restored = 0;
        for (String name : INCLUDED) {
            deleteTree(paths.root().resolve(name));
        }
        try (InputStream is = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = paths.root().resolve(entry.getName()).normalize();
                if (!target.startsWith(paths.root())) {
                    throw new IOException("Unsafe path in archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                restored++;
            }
        }
        return restored;
    }

    private static void deleteTree(Path start) throws IOException {
        if (!Files.exists(start)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(start)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                if (!p.getFileName().toString().endsWith(".lock")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
