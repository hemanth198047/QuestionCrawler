package com.questioncrawler.core.service;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.storage.AtomicFiles;
import com.questioncrawler.core.storage.StoragePaths;
import com.questioncrawler.core.storage.StoreException;
import com.questioncrawler.core.util.Json;
import com.questioncrawler.core.util.SafeNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Human-readable Markdown export (derived data). One file per question thread holds the full
 * content; category indexes only LINK to those files, they never duplicate thread content.
 */
public final class MarkdownExporter {

    private final StoragePaths paths;
    private final Taxonomy taxonomy;

    public MarkdownExporter(StoragePaths paths, Taxonomy taxonomy) {
        this.paths = paths;
        this.taxonomy = taxonomy;
    }

    /** Renders a thread. Also used by the 'show' command so the CLI and the files agree. */
    public static String render(ThreadRecord t, List<String> categoryNames) {
        Question q = t.question();
        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("source: ").append(Json.writeCompact(t.sourceSystem())).append('\n');
        md.append("question_id: ").append(Json.writeCompact(q.id())).append('\n');
        md.append("title: ").append(Json.writeCompact(q.title())).append('\n');
        md.append("url: ").append(Json.writeCompact(String.valueOf(q.url()))).append('\n');
        md.append("tags: ").append(Json.writeCompact(q.tags())).append('\n');
        md.append("categories: ").append(Json.writeCompact(categoryNames)).append('\n');
        md.append("status: ").append(t.status()).append('\n');
        md.append("created_at: ").append(unknown(q.createdAt())).append('\n');
        md.append("modified_at: ").append(unknown(q.modifiedAt())).append('\n');
        md.append("retrieved_at: ").append(unknown(t.retrievedAt())).append('\n');
        md.append("content_hash: ").append(t.contentHash()).append('\n');
        md.append("untrusted_content: true\n");
        md.append("---\n\n");
        md.append("# ").append(q.title()).append("\n\n");
        md.append("> Source: ").append(unknown(q.url())).append(" | score: ").append(q.score() == null ? "unknown" : q.score())
                .append(" | author: ").append(unknown(q.author())).append("\n\n");
        md.append(q.body()).append("\n");
        appendComments(md, q.comments(), "Question comments");
        md.append("\n## Answers (").append(q.answers().size()).append(")\n");
        if (q.answers().isEmpty()) {
            md.append("\n_No answers were available when this thread was collected._\n");
        }
        for (Answer a : q.answers()) {
            md.append("\n### Answer ").append(a.id()).append(a.accepted() ? " (accepted)" : "")
                    .append(" | score: ").append(a.score() == null ? "unknown" : a.score()).append('\n');
            md.append("\n> Source: ").append(unknown(a.url())).append(" | author: ").append(unknown(a.author())).append("\n\n");
            md.append(a.body()).append("\n");
            appendComments(md, a.comments(), "Answer comments");
        }
        return md.toString();
    }

    private static void appendComments(StringBuilder md, List<Comment> comments, String heading) {
        if (comments.isEmpty()) {
            return;
        }
        md.append("\n#### ").append(heading).append("\n\n");
        for (Comment c : comments) {
            md.append("- ").append(c.author() == null ? "unknown" : c.author()).append(": ")
                    .append(c.body().replace("\n", " ")).append('\n');
        }
    }

    private static String unknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public void writeQuestion(ThreadRecord t, List<String> categoryIds) {
        try {
            AtomicFiles.write(paths.markdownQuestionFile(t.sourceSystem(), t.questionId()),
                    render(t, categoryIds.stream().map(taxonomy::displayName).toList()));
        } catch (IOException e) {
            throw new StoreException("Cannot write markdown export: " + e.getMessage(), e);
        }
    }

    public void deleteQuestion(String source, String id) {
        try {
            Files.deleteIfExists(paths.markdownQuestionFile(source, id));
        } catch (IOException e) {
            throw new StoreException("Cannot delete markdown export: " + e.getMessage(), e);
        }
    }

    /**
     * Regenerates category indexes from the given threads. Every retrievable thread appears in the index
     * of each of its categories as a link to its canonical question file.
     */
    public void writeCategoryIndexes(Map<ThreadRecord, List<String>> threadCategories) {
        Map<String, List<ThreadRecord>> byCategory = new TreeMap<>();
        threadCategories.forEach((thread, cats) -> cats.forEach(c -> byCategory.computeIfAbsent(c, k -> new ArrayList<>()).add(thread)));
        try {
            Files.createDirectories(paths.markdownDir().resolve("categories"));
            Set<String> written = new HashSet<>();
            StringBuilder overview = new StringBuilder("# Knowledge overview\n\nGenerated index. Open a category to see its questions.\n\n");
            for (Map.Entry<String, List<ThreadRecord>> e : byCategory.entrySet()) {
                String id = e.getKey();
                List<ThreadRecord> threads = e.getValue();
                threads.sort(Comparator.comparing(ThreadRecord::key));
                Taxonomy.Category category = taxonomy.category(id);
                StringBuilder md = new StringBuilder("# ").append(taxonomy.displayName(id)).append("\n\n");
                if (category != null && category.description() != null) {
                    md.append(category.description()).append("\n\n");
                }
                md.append(threads.size()).append(" question(s). Classification is rule-based (see the taxonomy file), not semantic.\n\n");
                for (ThreadRecord t : threads) {
                    String rel = "../questions/" + SafeNames.fileName(t.sourceSystem()) + "/" + SafeNames.fileName(t.questionId()) + ".md";
                    md.append("- [").append(t.question().title().replace("]", "\\]")).append("](").append(rel).append(")")
                            .append(" - ").append(t.question().answers().size()).append(" answer(s)")
                            .append(t.status() == com.questioncrawler.core.model.ThreadStatus.ACTIVE ? "" : " - " + t.status())
                            .append('\n');
                }
                Path file = paths.markdownCategoryFile(id);
                AtomicFiles.write(file, md.toString());
                written.add(file.getFileName().toString());
                overview.append("- [").append(taxonomy.displayName(id)).append("](categories/").append(file.getFileName()).append(") - ")
                        .append(threads.size()).append('\n');
            }
            AtomicFiles.write(paths.markdownDir().resolve("README.md"), overview.toString());
            try (Stream<Path> existing = Files.list(paths.markdownDir().resolve("categories"))) {
                for (Path p : (Iterable<Path>) existing::iterator) {
                    if (!written.contains(p.getFileName().toString())) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            throw new StoreException("Cannot write category indexes: " + e.getMessage(), e);
        }
    }
}
