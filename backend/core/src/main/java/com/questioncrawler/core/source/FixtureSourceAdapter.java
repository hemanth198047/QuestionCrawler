package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.util.Json;
import com.questioncrawler.core.util.Times;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline adapter backed by synthetic JSON fixtures. It behaves like a paginating remote source
 * (separate question and answer pagination, deleted and forbidden posts) so the whole pipeline can
 * be demonstrated and tested without internal-site access or credentials.
 *
 * <p>A fixture set is a directory with a {@code manifest.json} ({@code {"files":["questions/a.json"]}}).
 * Several sets can be layered: a later set replaces questions with the same id, which is how the
 * "site changed since last sync" scenario is modelled.
 */
public final class FixtureSourceAdapter implements SourceAdapter {

    public static final String SYSTEM_ID = "fixture";

    record FixtureQuestion(
            String id, String title, String body, String bodyFormat, String url, List<String> tags,
            String author, Integer score, String createdAt, String modifiedAt, List<Comment> comments,
            List<Answer> answers, String access) {

        boolean available() {
            return access == null || access.equalsIgnoreCase("available");
        }
    }

    private final Map<String, FixtureQuestion> questions = new LinkedHashMap<>();
    private final int questionPageSize;
    private final int answerPageSize;
    private final int setCount;

    private FixtureSourceAdapter(List<Map<String, FixtureQuestion>> sets, int questionPageSize, int answerPageSize) {
        for (Map<String, FixtureQuestion> set : sets) {
            questions.putAll(set);
        }
        this.questionPageSize = Math.max(1, questionPageSize);
        this.answerPageSize = Math.max(1, answerPageSize);
        this.setCount = sets.size();
    }

    /** Loads fixture sets bundled in the jar, for example {@code "fixtures/demo-site"}. */
    public static FixtureSourceAdapter fromClasspath(int questionPageSize, int answerPageSize, String... resourceDirs) {
        List<Map<String, FixtureQuestion>> sets = new ArrayList<>();
        for (String dir : resourceDirs) {
            sets.add(loadSet(new ClasspathReader(dir)));
        }
        return new FixtureSourceAdapter(sets, questionPageSize, answerPageSize);
    }

    /** Loads fixture sets from directories on disk. */
    public static FixtureSourceAdapter fromDirectories(int questionPageSize, int answerPageSize, Path... dirs) {
        List<Map<String, FixtureQuestion>> sets = new ArrayList<>();
        for (Path dir : dirs) {
            sets.add(loadSet(new FileReader(dir)));
        }
        return new FixtureSourceAdapter(sets, questionPageSize, answerPageSize);
    }

    // ---------------------------------------------------------------- loading

    private interface SetReader {
        InputStream open(String relativePath) throws IOException;
    }

    private record ClasspathReader(String base) implements SetReader {
        @Override
        public InputStream open(String relativePath) throws IOException {
            String path = base + "/" + relativePath;
            InputStream in = FixtureSourceAdapter.class.getClassLoader().getResourceAsStream(path);
            if (in == null) {
                throw new IOException("Fixture resource not found: " + path);
            }
            return in;
        }
    }

    private record FileReader(Path base) implements SetReader {
        @Override
        public InputStream open(String relativePath) throws IOException {
            Path resolved = base.resolve(relativePath).normalize();
            if (!resolved.startsWith(base.normalize())) {
                throw new IOException("Fixture path escapes fixture directory: " + relativePath);
            }
            return Files.newInputStream(resolved);
        }
    }

    private static Map<String, FixtureQuestion> loadSet(SetReader reader) {
        Map<String, FixtureQuestion> loaded = new LinkedHashMap<>();
        try (InputStream manifestIn = reader.open("manifest.json")) {
            JsonNode manifest = Json.MAPPER.readTree(manifestIn);
            for (JsonNode file : manifest.path("files")) {
                try (InputStream in = reader.open(file.asText())) {
                    FixtureQuestion q = Json.MAPPER.readValue(in, FixtureQuestion.class);
                    loaded.put(q.id(), q);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load fixture set: " + e.getMessage(), e);
        }
        return loaded;
    }

    // ---------------------------------------------------------------- SourceAdapter

    @Override
    public String systemId() {
        return SYSTEM_ID;
    }

    @Override
    public boolean supportsModifiedSince() {
        return true;
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        var since = Times.parse(modifiedSince);
        var createdAfter = Times.parse(filter.createdAfter());
        List<FixtureQuestion> matching = questions.values().stream()
                .filter(FixtureQuestion::available)
                .filter(q -> filter.matches(q.tags() == null ? List.of() : q.tags()))
                .filter(q -> createdAfter.isEmpty() || Times.parse(q.createdAt()).map(c -> !c.isBefore(createdAfter.get())).orElse(false))
                .filter(q -> since.isEmpty() || Times.parse(q.modifiedAt()).map(m -> !m.isBefore(since.get())).orElse(true))
                .sorted(Comparator.comparing((FixtureQuestion q) -> Times.parse(q.modifiedAt()).orElse(java.time.Instant.EPOCH))
                        .thenComparing(FixtureQuestion::id))
                .toList();
        int offset = parseCursor(cursor);
        int end = Math.min(matching.size(), offset + questionPageSize);
        List<QuestionSummary> items = matching.subList(Math.min(offset, matching.size()), end).stream()
                .map(q -> new QuestionSummary(q.id(), q.modifiedAt(), q.url()))
                .toList();
        return new Page<>(items, end < matching.size() ? "o:" + end : null);
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        FixtureQuestion q = questions.get(id);
        if (q == null) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "No question with id " + id);
        }
        if ("deleted".equalsIgnoreCase(q.access())) {
            throw new SourceException(SourceException.Kind.GONE, "Question " + id + " was deleted");
        }
        if ("forbidden".equalsIgnoreCase(q.access())) {
            throw new SourceException(SourceException.Kind.FORBIDDEN, "Access to question " + id + " is no longer permitted");
        }
        return new Question(q.id(), q.title(), q.body(), parseFormat(q.bodyFormat()), q.url(), q.tags(), q.author(),
                q.score(), q.createdAt(), q.modifiedAt(), q.comments(), List.of());
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) throws SourceException {
        FixtureQuestion q = questions.get(questionId);
        if (q == null || !q.available()) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "No question with id " + questionId);
        }
        List<Answer> all = q.answers() == null ? List.of() : q.answers();
        int offset = parseCursor(cursor);
        int end = Math.min(all.size(), offset + answerPageSize);
        return new Page<>(all.subList(Math.min(offset, all.size()), end), end < all.size() ? "o:" + end : null);
    }

    @Override
    public String ping() {
        return "fixture adapter: " + questions.size() + " questions in " + setCount + " fixture set(s)";
    }

    private static int parseCursor(String cursor) throws SourceException {
        if (cursor == null) {
            return 0;
        }
        try {
            if (cursor.startsWith("o:")) {
                return Math.max(0, Integer.parseInt(cursor.substring(2)));
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new SourceException(SourceException.Kind.FATAL, "Invalid cursor: " + cursor);
    }

    private static com.questioncrawler.core.model.BodyFormat parseFormat(String value) {
        if (value == null) {
            return com.questioncrawler.core.model.BodyFormat.MARKDOWN;
        }
        return com.questioncrawler.core.model.BodyFormat.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
