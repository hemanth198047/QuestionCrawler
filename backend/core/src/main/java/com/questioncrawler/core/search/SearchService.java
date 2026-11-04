package com.questioncrawler.core.search;

import com.questioncrawler.core.classification.Taxonomy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import com.questioncrawler.core.storage.StoreException;

/**
 * Local evidence retrieval over the Lucene index.
 *
 * <p>User text is DATA: it is tokenized and turned into TermQuery/PhraseQuery objects directly, never
 * passed to a query parser, so operators and special characters in the input cannot change the query
 * structure. Ranking is BM25 with title and phrase boosts, then a small, documented adjustment for
 * accepted status and votes (signals, not proof of correctness).
 */
public final class SearchService {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,300})\"");
    private static final float TITLE_BOOST = 3f;
    private static final float META_BOOST = 1.5f;
    private static final float TITLE_PHRASE_BOOST = 6f;
    private static final float BODY_PHRASE_BOOST = 4f;
    static final double MIN_COVERAGE = 0.34;
    private static final int MAX_PER_THREAD = 2;

    private final Path indexDir;
    private final Taxonomy taxonomy;

    public SearchService(Path indexDir, Taxonomy taxonomy) {
        this.indexDir = indexDir;
        this.taxonomy = taxonomy;
    }

    public SearchResponse search(SearchRequest request) {
        if (!SearchIndex.exists(indexDir)) {
            throw new StoreException("Search index not found at " + indexDir + ". Run 'sync' (or 'demo'), or 'reindex' if threads exist.");
        }
        String queryText = request.query() == null ? "" : request.query();
        List<SymbolTokens.Word> words = SymbolTokens.contentWords(queryText);
        if (words.isEmpty()) {
            return empty(request);
        }
        try (Directory directory = new NIOFSDirectory(indexDir); DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = buildQuery(queryText, words, request);
            int candidates = Math.max(60, request.topK() * 12);
            ScoreDoc[] hits = searcher.search(query, candidates).scoreDocs;

            String normalizedQuery = normalizeForExact(queryText);
            List<Evidence> scored = new ArrayList<>();
            for (ScoreDoc hit : hits) {
                var doc = searcher.storedFields().document(hit.doc);
                String text = doc.get(SearchIndex.S_TEXT);
                String title = doc.get(SearchIndex.S_TITLE);
                double coverage = coverage(words, SymbolTokens.expand(title + "\n" + text));
                boolean exact = normalizedQuery.length() >= 6
                        && (normalizeForExact(text).contains(normalizedQuery) || normalizeForExact(title).contains(normalizedQuery));
                if (exact) {
                    coverage = 1.0;
                }
                if (coverage < MIN_COVERAGE) {
                    continue; // a weak lexical overlap is not evidence
                }
                boolean accepted = "1".equals(doc.get(SearchIndex.S_ACCEPTED));
                Integer votes = parseInt(doc.get(SearchIndex.S_VOTES));
                double adjusted = hit.score
                        * (0.5 + 0.5 * coverage)
                        * (accepted ? 1.15 : 1.0)
                        * (1.0 + 0.01 * Math.min(20, Math.max(0, votes == null ? 0 : votes)));
                List<String> cats = split(doc.get(SearchIndex.S_CATS));
                String passage = PassageExtractor.extract(text, words, request.maxPassageChars());
                List<String> flags = InjectionHeuristics.suspicious(text) || InjectionHeuristics.suspicious(title)
                        ? List.of(InjectionHeuristics.FLAG) : List.of();
                scored.add(new Evidence(0, doc.get(SearchIndex.F_TYPE), doc.get(SearchIndex.F_QID),
                        emptyToNull(doc.get(SearchIndex.F_AID)), title, passage,
                        emptyToNull(doc.get(SearchIndex.S_URL)), emptyToNull(doc.get(SearchIndex.S_QURL)),
                        cats, cats.stream().map(taxonomy::displayName).toList(), split(doc.get(SearchIndex.S_TAGS)),
                        accepted, votes, emptyToNull(doc.get(SearchIndex.S_MODIFIED)), emptyToNull(doc.get(SearchIndex.S_SYNCED)),
                        round(adjusted), round(coverage), doc.get(SearchIndex.S_STATUS), flags, true));
            }
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            return assemble(request, scored);
        } catch (IOException e) {
            throw new StoreException("Cannot read search index: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ query building

    private Query buildQuery(String queryText, List<SymbolTokens.Word> words, SearchRequest request) {
        BooleanQuery.Builder text = new BooleanQuery.Builder();
        Set<String> seen = new HashSet<>();
        for (SymbolTokens.Word w : words) {
            List<String> terms = new ArrayList<>();
            terms.add(w.whole());
            terms.addAll(w.parts());
            for (String t : terms) {
                if (SymbolTokens.STOPWORDS.contains(t) || !seen.add(t)) {
                    continue;
                }
                BooleanQuery.Builder perTerm = new BooleanQuery.Builder();
                perTerm.add(new BoostQuery(new TermQuery(new Term(SearchIndex.F_TITLE, t)), TITLE_BOOST), BooleanClause.Occur.SHOULD);
                perTerm.add(new TermQuery(new Term(SearchIndex.F_BODY, t)), BooleanClause.Occur.SHOULD);
                perTerm.add(new BoostQuery(new TermQuery(new Term(SearchIndex.F_META, t)), META_BOOST), BooleanClause.Occur.SHOULD);
                text.add(perTerm.build(), BooleanClause.Occur.SHOULD);
            }
        }
        // Exact phrase boosts: the whole query (pasted error messages) and any "quoted" segments.
        List<String> phrases = new ArrayList<>();
        phrases.add(queryText);
        Matcher quoted = QUOTED.matcher(queryText);
        while (quoted.find()) {
            phrases.add(quoted.group(1));
        }
        for (String phrase : phrases) {
            List<String> tokens = SymbolTokens.expand(phrase);
            if (tokens.size() < 2 || tokens.size() > 40) {
                continue;
            }
            text.add(new BoostQuery(phraseQuery(SearchIndex.F_TITLE, tokens), TITLE_PHRASE_BOOST), BooleanClause.Occur.SHOULD);
            text.add(new BoostQuery(phraseQuery(SearchIndex.F_BODY, tokens), BODY_PHRASE_BOOST), BooleanClause.Occur.SHOULD);
        }
        text.setMinimumNumberShouldMatch(1);

        BooleanQuery.Builder all = new BooleanQuery.Builder();
        all.add(text.build(), BooleanClause.Occur.MUST);
        addFilter(all, SearchIndex.F_TAG, request.tags(), true);
        addFilter(all, SearchIndex.F_CAT, request.categories(), false);
        return all.build();
    }

    private static Query phraseQuery(String field, List<String> tokens) {
        PhraseQuery.Builder b = new PhraseQuery.Builder();
        for (String t : tokens) {
            b.add(new Term(field, t));
        }
        return b.build();
    }

    /** Several values of one filter are alternatives (OR); different filters combine with AND. */
    private static void addFilter(BooleanQuery.Builder all, String field, List<String> values, boolean lowercase) {
        if (values.isEmpty()) {
            return;
        }
        BooleanQuery.Builder any = new BooleanQuery.Builder();
        for (String v : values) {
            any.add(new TermQuery(new Term(field, lowercase ? v.toLowerCase(Locale.ROOT).strip() : v.strip())), BooleanClause.Occur.SHOULD);
        }
        all.add(any.build(), BooleanClause.Occur.FILTER);
    }

    // ------------------------------------------------------------------ scoring helpers

    /**
     * Fraction of query words present in the text. A word found as a whole token counts fully; a word
     * that is only reconstructable from its parts (camelCase / separators) counts half, because the
     * parts may be scattered across unrelated places.
     */
    static double coverage(List<SymbolTokens.Word> words, List<String> docTokens) {
        if (words.isEmpty()) {
            return 0;
        }
        Set<String> tokens = new HashSet<>(docTokens);
        double matched = 0;
        for (SymbolTokens.Word w : words) {
            if (tokens.contains(w.whole())) {
                matched += 1.0;
            } else if (!w.parts().isEmpty() && tokens.containsAll(w.parts())) {
                matched += 0.5;
            }
        }
        return matched / words.size();
    }

    private static String normalizeForExact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    // ------------------------------------------------------------------ assembling the response

    private SearchResponse assemble(SearchRequest request, List<Evidence> ranked) {
        Map<String, Integer> perThread = new HashMap<>();
        List<Evidence> chosen = new ArrayList<>();
        int budget = 0;
        int omitted = 0;
        boolean truncated = false;
        for (Evidence e : ranked) {
            if (chosen.size() >= request.topK()) {
                break;
            }
            int used = perThread.merge(e.questionId(), 1, Integer::sum);
            if (used > MAX_PER_THREAD) {
                continue;
            }
            int cost = e.passage().length();
            if (!chosen.isEmpty() && budget + cost > request.maxTotalChars()) {
                omitted++;
                truncated = true;
                continue;
            }
            budget += cost;
            chosen.add(withRank(e, chosen.size() + 1));
        }
        return new SearchResponse(request.query(), request.tags(), request.categories(), chosen.size(),
                confidence(chosen), truncated, omitted, null, SearchResponse.NOTICE, chosen);
    }

    private static Evidence withRank(Evidence e, int rank) {
        return new Evidence(rank, e.type(), e.questionId(), e.answerId(), e.title(), e.passage(), e.sourceUrl(), e.questionUrl(),
                e.categories(), e.categoryNames(), e.tags(), e.accepted(), e.votes(), e.sourceModifiedAt(), e.lastSyncedAt(),
                e.score(), e.termCoverage(), e.threadStatus(), e.flags(), e.untrusted());
    }

    /** Heuristic label of how well the best evidence covers the query terms. Not a probability. */
    private static String confidence(List<Evidence> chosen) {
        if (chosen.isEmpty()) {
            return "NONE";
        }
        double top = chosen.stream().mapToDouble(Evidence::termCoverage).max().orElse(0);
        return top >= 0.8 ? "HIGH" : top >= 0.5 ? "MEDIUM" : "LOW";
    }

    private SearchResponse empty(SearchRequest request) {
        return new SearchResponse(request.query(), request.tags(), request.categories(), 0, "NONE", false, 0, null,
                SearchResponse.NOTICE, List.of());
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(joined.split("\u001f"));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Integer parseInt(String s) {
        try {
            return s == null || s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
