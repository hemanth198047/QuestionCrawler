package com.questioncrawler.core.searchai;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.LinksAdapter;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.WebPageReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the pages that Search-AI results lead to and stores their content in the knowledge base, on demand (only when the
 * person asks). Saved pages are searched together with everything else the app has collected, also when Search-AI is
 * unreachable. Pages behind the company login are read through the browser window, as for the Links source.
 */
public final class SearchAiPageSaver {

    /** The source name saved pages are stored under. */
    public static final String SYSTEM = "searchai-pages";

    /** One request: the link, what Search-AI called it, and the search that found it. */
    public record Request(String url, String title, String query) {}

    /** What happened to one link. {@code error} is set when the page could not be read or stored. */
    public record Saved(String url, String key, String title, int characters, boolean updated, String error) {}

    /** A page saved earlier. */
    public record SavedPage(String key, String url, String title, String savedAt, int characters) {}

    /** Reads a page. Replaceable in tests. */
    @FunctionalInterface
    public interface Reader {
        WebPageReader.WebPage read(String link) throws SourceException;
    }

    private static final int MAX_PER_CALL = 25;

    private final KnowledgeBase kb;
    private final Reader reader;

    public SearchAiPageSaver(KnowledgeBase kb, Reader reader) {
        this.kb = kb;
        this.reader = reader;
    }

    public List<Saved> save(List<Request> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one page to save.");
        }
        if (requests.size() > MAX_PER_CALL) {
            throw new IllegalArgumentException("Save at most " + MAX_PER_CALL + " pages at a time.");
        }
        List<Saved> out = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Request r : requests) {
            String url = r == null || r.url() == null ? "" : r.url().strip();
            if (!done.add(url.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(saveOne(url, r == null ? null : r.title(), r == null ? null : r.query()));
        }
        return out;
    }

    private Saved saveOne(String url, String title, String query) {
        if (!url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8)) {
            return new Saved(url, null, title, 0, false, "Only web addresses (http or https) can be saved.");
        }
        try {
            WebPageReader.WebPage page = reader.read(url);
            String id = LinksAdapter.idFor(url);
            Question q = LinksAdapter.toQuestion(id, url, page);
            List<String> tags = new ArrayList<>(q.tags());
            tags.add("search-ai");
            String note = query == null || query.isBlank() ? "" : "<p><em>Saved from a Search-AI result for: " + escape(query.strip()) + ".</em></p>\n";
            String name = q.title().isBlank() || q.title().equals(url) ? (title == null ? "" : title.strip()) : q.title();
            Question withNote = new Question(id, name, note + q.body(), BodyFormat.HTML, q.url(), tags, null, null, null, null, List.of(), List.of());
            boolean existed = kb.store().loadThread(SYSTEM, id).isPresent();
            ThreadRecord saved = kb.saveItem(SYSTEM, withNote, "search-ai");
            Question stored = saved.question();
            return new Saved(url, saved.key(), stored.title(), stored.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip().length(), existed, null);
        } catch (SourceException e) {
            return new Saved(url, null, title, 0, false, e.getMessage());
        } catch (RuntimeException e) {
            return new Saved(url, null, title, 0, false, "The page could not be stored: " + e.getMessage());
        }
    }

    /** Pages saved so far, newest first. */
    public List<SavedPage> saved() {
        List<SavedPage> out = new ArrayList<>();
        for (ThreadRecord t : kb.store().allThreads()) {
            if (SYSTEM.equals(t.sourceSystem()) && !t.isPurged()) {
                Question q = t.question();
                out.add(new SavedPage(t.key(), q.url(), q.title(), t.retrievedAt(), q.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip().length()));
            }
        }
        out.sort((a, b) -> String.valueOf(b.savedAt()).compareTo(String.valueOf(a.savedAt())));
        return out;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
