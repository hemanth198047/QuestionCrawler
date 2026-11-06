package com.questioncrawler.core.search;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.storage.AtomicFiles;
import com.questioncrawler.core.storage.StoreException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.Locale;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;

/**
 * Writer side of the embedded Lucene index. One document per question and one per answer, so an
 * answer can be returned as its own evidence with its own URL. The index is DERIVED data: it can be
 * deleted and rebuilt from the JSON threads with the reindex command.
 */
public final class SearchIndex implements AutoCloseable {

    /** Bumped when token expansion changes (plain-English stems) or indexed text shape changes (comments). */
    public static final int INDEX_VERSION = 3;

    static final String F_KEY = "key";
    static final String F_TYPE = "type";
    static final String F_DOCTYPE = "doctype";
    static final String F_QCREATED = "q_created";
    static final String F_ACREATED = "a_created";
    static final String F_QID = "qid";
    static final String F_AID = "aid";
    static final String F_TITLE = "title";
    static final String F_BODY = "body";
    static final String F_META = "meta";
    static final String F_TAG = "tag";
    static final String F_CAT = "cat";
    static final String S_TITLE = "s_title";
    static final String S_TEXT = "s_text";
    static final String S_URL = "s_url";
    static final String S_QURL = "s_qurl";
    static final String S_ACCEPTED = "s_accepted";
    static final String S_VOTES = "s_votes";
    static final String S_MODIFIED = "s_modified";
    static final String S_CREATED = "s_created";
    static final String S_QCREATED = "s_qcreated";
    static final String S_SYNCED = "s_synced";
    static final String S_TAGS = "s_tags";
    static final String S_CATS = "s_cats";
    static final String S_STATUS = "s_status";
    static final String S_AUTHOR = "s_author";

    private final Path dir;
    private Directory directory;
    private IndexWriter writer;

    public SearchIndex(Path dir) {
        this.dir = dir;
    }

    private IndexWriter writer() throws IOException {
        if (writer == null) {
            Files.createDirectories(dir);
            Path meta = dir.resolve("index-meta.json");
            if (!Files.exists(meta)) {
                AtomicFiles.write(meta, "{\"indexVersion\": " + INDEX_VERSION + "}");
            } else if (readVersion(dir) != INDEX_VERSION) {
                throw new StoreException("The search index at " + dir + " was built by an older version. Run 'reindex' to rebuild it.");
            }
            directory = new NIOFSDirectory(dir);
            try {
                IndexWriterConfig config = new IndexWriterConfig(new WhitespaceAnalyzer());
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                writer = new IndexWriter(directory, config);
            } catch (ServiceConfigurationError | LinkageError e) { // Lucene cannot scan its plug-ins: a conflicting Lucene is on the classpath
                throw new IllegalStateException(LuceneDoctor.explain(e), e);
            }
        }
        return writer;
    }

    /** Index format version recorded next to the index, or -1 when unknown. */
    public static int readVersion(Path dir) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = com.questioncrawler.core.util.Json.MAPPER.readTree(Files.readString(dir.resolve("index-meta.json")));
            return n.path("indexVersion").asInt(-1);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Deletes the whole index (all formats) and starts a fresh one at the current version. Used by 'reindex'. */
    public void resetForRebuild() {
        close();
        try {
            if (Files.isDirectory(dir)) {
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    for (Path p : (Iterable<Path>) files::iterator) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            Files.createDirectories(dir);
            AtomicFiles.write(dir.resolve("index-meta.json"), "{\"indexVersion\": " + INDEX_VERSION + "}");
        } catch (IOException e) {
            throw new StoreException("Cannot reset the search index: " + e.getMessage(), e);
        }
    }

    /** Replaces every document of the thread atomically (old answers disappear with the update). */
    public void indexThread(ThreadRecord thread, List<String> categoryIds, Taxonomy taxonomy) {
        Question q = thread.question();
        if (q == null || !thread.status().isRetrievable()) {
            deleteThread(thread.key());
            return;
        }
        List<String> categoryNames = categoryIds.stream().map(taxonomy::displayName).toList();
        List<Document> docs = new ArrayList<>();
        docs.add(document(thread, q, null, categoryIds, categoryNames));
        for (Answer a : q.answers()) {
            docs.add(document(thread, q, a, categoryIds, categoryNames));
        }
        try {
            writer().updateDocuments(new Term(F_KEY, thread.key()), docs);
        } catch (IOException e) {
            throw new StoreException("Cannot update search index: " + e.getMessage(), e);
        }
    }

    private static Document document(ThreadRecord thread, Question q, Answer answer, List<String> categoryIds, List<String> categoryNames) {
        Document d = new Document();
        boolean isAnswer = answer != null;
        String text = isAnswer ? answer.body() : questionSearchText(q);
        String meta = String.join(" ", q.tags()) + " " + String.join(" ", categoryIds) + " " + String.join(" ", categoryNames);

        d.add(new StringField(F_KEY, thread.key(), Field.Store.NO));
        d.add(new StoredField(F_TYPE, isAnswer ? "answer" : "question"));
        d.add(new StringField(F_DOCTYPE, isAnswer ? "answer" : "question", Field.Store.NO));
        // Creation times as numeric points so age filters run inside the query (no post-filtering of a truncated list).
        com.questioncrawler.core.util.Times.parse(q.createdAt()).ifPresent(t -> d.add(new LongPoint(F_QCREATED, t.toEpochMilli())));
        if (isAnswer) {
            com.questioncrawler.core.util.Times.parse(answer.createdAt()).ifPresent(t -> d.add(new LongPoint(F_ACREATED, t.toEpochMilli())));
        }
        d.add(new StoredField(F_QID, q.id()));
        d.add(new StoredField(F_AID, isAnswer ? answer.id() : ""));
        // Titles are only indexed on the question document so a title hit is not repeated for every answer.
        d.add(new TextField(F_TITLE, isAnswer ? "" : SymbolTokens.expandToString(q.title()), Field.Store.NO));
        d.add(new TextField(F_BODY, SymbolTokens.expandToString(text), Field.Store.NO));
        d.add(new TextField(F_META, SymbolTokens.expandToString(meta), Field.Store.NO));
        for (String tag : q.tags()) {
            d.add(new StringField(F_TAG, tag.toLowerCase(Locale.ROOT), Field.Store.NO));
        }
        for (String cat : categoryIds) {
            d.add(new StringField(F_CAT, cat, Field.Store.NO));
        }
        d.add(new StoredField(S_TITLE, q.title()));
        d.add(new StoredField(S_TEXT, text));
        d.add(new StoredField(S_URL, (isAnswer ? answer.url() : q.url()) == null ? "" : (isAnswer ? answer.url() : q.url())));
        d.add(new StoredField(S_QURL, q.url() == null ? "" : q.url()));
        d.add(new StoredField(S_ACCEPTED, isAnswer && answer.accepted() ? 1 : 0));
        Integer votes = isAnswer ? answer.score() : q.score();
        d.add(new StoredField(S_VOTES, votes == null ? "" : String.valueOf(votes)));
        String modified = isAnswer && answer.modifiedAt() != null ? answer.modifiedAt() : q.modifiedAt();
        d.add(new StoredField(S_MODIFIED, modified == null ? "" : modified));
        String created = isAnswer ? answer.createdAt() : q.createdAt();
        d.add(new StoredField(S_CREATED, created == null ? "" : created));
        d.add(new StoredField(S_QCREATED, q.createdAt() == null ? "" : q.createdAt()));
        d.add(new StoredField(S_SYNCED, thread.retrievedAt() == null ? "" : thread.retrievedAt()));
        d.add(new StoredField(S_TAGS, String.join("\u001f", q.tags())));
        d.add(new StoredField(S_CATS, String.join("\u001f", categoryIds)));
        d.add(new StoredField(S_STATUS, thread.status().name()));
        String author = isAnswer ? answer.author() : q.author();
        d.add(new StoredField(S_AUTHOR, author == null ? "" : author));
        return d;
    }

    /** Question body plus page/question comments so Confluence discussion is searchable. */
    private static String questionSearchText(Question q) {
        if (q.comments() == null || q.comments().isEmpty()) {
            return q.body() == null ? "" : q.body();
        }
        StringBuilder sb = new StringBuilder(q.body() == null ? "" : q.body());
        for (var c : q.comments()) {
            if (c == null || c.body() == null || c.body().isBlank()) {
                continue;
            }
            sb.append('\n').append(c.body());
        }
        return sb.toString();
    }

    public void deleteThread(String key) {
        try {
            writer().deleteDocuments(new Term(F_KEY, key));
        } catch (IOException e) {
            throw new StoreException("Cannot update search index: " + e.getMessage(), e);
        }
    }

    public void clear() {
        try {
            writer().deleteAll();
        } catch (IOException e) {
            throw new StoreException("Cannot clear search index: " + e.getMessage(), e);
        }
    }

    public void commit() {
        try {
            writer().commit();
        } catch (IOException e) {
            throw new StoreException("Cannot commit search index: " + e.getMessage(), e);
        }
    }

    public int documentCount() {
        try {
            writer().commit();
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                return reader.numDocs();
            }
        } catch (IOException e) {
            throw new StoreException("Cannot read search index: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (IOException e) {
            throw new StoreException("Cannot close search index: " + e.getMessage(), e);
        } finally {
            writer = null;
            directory = null;
        }
    }

    /** Document count of an existing index, or -1 when none exists. Read-only. */
    public static int readDocCount(Path dir) {
        if (!exists(dir)) {
            return -1;
        }
        try (Directory d = new NIOFSDirectory(dir); DirectoryReader reader = DirectoryReader.open(d)) {
            return reader.numDocs();
        } catch (IOException | ServiceConfigurationError | LinkageError e) { // the reason for a broken Lucene is reported by LuceneDoctor
            return -1;
        }
    }

    /** True when a readable index exists at the path. */
    public static boolean exists(Path dir) {
        try (Directory d = new NIOFSDirectory(dir)) {
            return Files.isDirectory(dir) && DirectoryReader.indexExists(d);
        } catch (IOException | ServiceConfigurationError | LinkageError e) {
            return false;
        }
    }
}
