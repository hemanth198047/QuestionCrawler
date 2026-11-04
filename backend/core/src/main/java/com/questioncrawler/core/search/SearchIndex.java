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
import java.util.Locale;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
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

    public static final int INDEX_VERSION = 1;

    static final String F_KEY = "key";
    static final String F_TYPE = "type";
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
    static final String S_SYNCED = "s_synced";
    static final String S_TAGS = "s_tags";
    static final String S_CATS = "s_cats";
    static final String S_STATUS = "s_status";

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
            }
            directory = new NIOFSDirectory(dir);
            IndexWriterConfig config = new IndexWriterConfig(new WhitespaceAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            writer = new IndexWriter(directory, config);
        }
        return writer;
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
        String text = isAnswer ? answer.body() : q.body();
        String meta = String.join(" ", q.tags()) + " " + String.join(" ", categoryIds) + " " + String.join(" ", categoryNames);

        d.add(new StringField(F_KEY, thread.key(), Field.Store.NO));
        d.add(new StoredField(F_TYPE, isAnswer ? "answer" : "question"));
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
        d.add(new StoredField(S_SYNCED, thread.retrievedAt() == null ? "" : thread.retrievedAt()));
        d.add(new StoredField(S_TAGS, String.join("\u001f", q.tags())));
        d.add(new StoredField(S_CATS, String.join("\u001f", categoryIds)));
        d.add(new StoredField(S_STATUS, thread.status().name()));
        return d;
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
        } catch (IOException e) {
            return -1;
        }
    }

    /** True when a readable index exists at the path. */
    public static boolean exists(Path dir) {
        try (Directory d = new NIOFSDirectory(dir)) {
            return Files.isDirectory(dir) && DirectoryReader.indexExists(d);
        } catch (IOException e) {
            return false;
        }
    }
}
