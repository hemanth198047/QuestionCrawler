package com.questioncrawler.core.searchai;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.storage.AtomicFiles;
import com.questioncrawler.core.util.Hashes;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Plain-file store for search-ai answers, next to the rest of the knowledge store:
 * <pre>&lt;storage-root&gt;/searchai/&lt;kind&gt;/&lt;timestamp&gt;-&lt;hash&gt;.json</pre>
 * Every call is kept as its own file (a history, never overwritten); writes are temp-file + atomic rename.
 */
public final class SearchAiStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern ID = Pattern.compile("^(search|answer|summary|usecases)/[0-9]{8}T[0-9]{9}Z-[0-9a-f]{12}$");

    private final Path dir;
    private final Clock clock;

    public SearchAiStore(Path dir) {
        this(dir, Clock.systemUTC());
    }

    public SearchAiStore(Path dir, Clock clock) {
        this.dir = dir.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public Path dir() {
        return dir;
    }

    /** Stores one answer and returns it with its id. */
    public StoredAnswer save(String kind, String baseUri, SearchAiResponse response) {
        Instant now = clock.instant();
        String hash = Hashes.sha256(kind + "|" + Json.writeCompact(response.params())).substring(0, 12);
        String id = kind + "/" + STAMP.format(now) + "-" + hash;
        StoredAnswer answer = new StoredAnswer(id, kind, now.toString(), baseUri, response.endpoint(), response.method(), response.params(),
                response.httpStatus(), response.json(), response.text());
        try {
            AtomicFiles.write(file(id), Json.write(answer));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store the search-ai answer under " + dir + ": " + e.getMessage(), e);
        }
        return answer;
    }

    public Optional<StoredAnswer> read(String id) {
        if (id == null || !ID.matcher(id.strip()).matches()) {
            throw new IllegalArgumentException("Not a stored answer id: " + id + " (expected kind/timestamp-hash, see 'searchai list').");
        }
        Path f = file(id.strip());
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.MAPPER.readValue(Files.readString(f), StoredAnswer.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + f + ": " + e.getMessage(), e);
        }
    }

    /** Newest first. {@code kind} null = every kind. */
    public List<StoredAnswer> list(String kind, int limit) {
        List<StoredAnswer> all = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return all;
        }
        try (Stream<Path> files = Files.walk(dir, 2)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json") && f.getParent().getParent().equals(dir))
                    .filter(f -> kind == null || f.getParent().getFileName().toString().equals(kind))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                    .forEach(f -> {
                        try {
                            all.add(Json.MAPPER.readValue(Files.readString(f), StoredAnswer.class));
                        } catch (IOException e) {
                            // A damaged file is skipped, not fatal; it stays on disk for inspection.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir + ": " + e.getMessage(), e);
        }
        return all;
    }

    private Path file(String id) {
        return dir.resolve(id + ".json").normalize();
    }
}
