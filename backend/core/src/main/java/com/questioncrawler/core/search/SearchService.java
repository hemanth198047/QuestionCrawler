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
 *
 * <p>Plain-English feel without an LLM: additive stems ({@link SymbolTokens}/{@link PlainEnglish}),
 * curated synonym SHOULD clauses, softer coverage for chatty questions, and a title-match bonus.
 */
public final class SearchService {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,300})\"");
    private static final float TITLE_BOOST = 3f;
    private static final float META_BOOST = 1.5f;
    private static final float HEADING_BOOST = 2f;
    private static final float HEADING_PHRASE_BOOST = 4f;
    /** How many top-ranked passages (before one-per-page collapsing) the direct answer may quote from. */
    private static final int ANSWER_CANDIDATES = 8;
    private static final float TITLE_PHRASE_BOOST = 6f;
    private static final float BODY_PHRASE_BOOST = 4f;
    private static final float SYNONYM_BOOST = 0.85f;
    static final double MIN_COVERAGE = 0.34;
    /** At most one question passage per thread; every matching answer for that thread may appear (until topK). */
    private static final int MAX_QUESTION_HITS_PER_THREAD = 1;

    private final Path indexDir;
    private final Taxonomy taxonomy;
    private final java.time.Clock clock;

    public SearchService(Path indexDir, Taxonomy taxonomy) {
        this(indexDir, taxonomy, java.time.Clock.systemUTC());
    }

    public SearchService(Path indexDir, Taxonomy taxonomy, java.time.Clock clock) {
        this.indexDir = indexDir;
        this.taxonomy = taxonomy;
        this.clock = clock;
    }

    /** Longest question taken (a pasted log or stack trace is cut, not refused). */
    public static final int MAX_QUERY_CHARS = 500;
    /** Words of the question used for matching; more would only slow the search and exceed the query limits. */
    static final int MAX_QUERY_WORDS = 32;
    /** One search may take at most this long; then it answers with what it found so far. */
    static final long TIME_BUDGET_MS = 6000;
    /** Searches running at the same time; more wait their turn instead of piling up. */
    private static final java.util.concurrent.Semaphore RUNNING = new java.util.concurrent.Semaphore(2, true);

    public SearchResponse search(SearchRequest request) {
        boolean admitted;
        try {
            admitted = RUNNING.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("The search was interrupted.", e);
        }
        if (!admitted) {
            throw new StoreException("Several searches are still running. Try again in a moment.");
        }
        try {
            return searchNow(request);
        } finally {
            RUNNING.release();
        }
    }

    private SearchResponse searchNow(SearchRequest request) {
        if (!SearchIndex.exists(indexDir)) {
            throw new StoreException("Search index not found at " + indexDir + ". Run 'sync' (or 'demo'), or 'reindex' if threads exist.");
        }
        if (SearchIndex.readVersion(indexDir) != SearchIndex.INDEX_VERSION) {
            throw new StoreException("The search index was built by an older version (plain-English stems need a rebuild). Run 'reindex'.");
        }
        String rawQuery = request.query() == null ? "" : request.query().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
        if (rawQuery.length() > MAX_QUERY_CHARS) {
            rawQuery = rawQuery.substring(0, MAX_QUERY_CHARS);
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(TIME_BUDGET_MS);
        String queryText = PlainEnglish.stripChattyPrefix(PlainEnglish.expandContractions(rawQuery));
        List<SymbolTokens.Word> words = SymbolTokens.contentWords(queryText);
        if (words.isEmpty()) {
            queryText = rawQuery;
            words = SymbolTokens.contentWords(queryText);
        }
        if (words.size() > MAX_QUERY_WORDS) {
            words = words.subList(0, MAX_QUERY_WORDS);
        }
        if (words.isEmpty() || mostlySymbols(rawQuery)) {
            // "_", "$", ".*", "->", "#{x}": search the text exactly as typed
            SearchResponse literal = literalSearch(request, rawQuery, deadline);
            if (literal.resultCount() > 0 || words.isEmpty()) {
                return literal;
            }
        }
        try (Directory directory = new NIOFSDirectory(indexDir); DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setTimeout(new org.apache.lucene.index.QueryTimeoutImpl(TIME_BUDGET_MS));
            Query query;
            ScoreDoc[] hits;
            try {
                query = buildQuery(queryText, words, request);
                int candidates = Math.max(60, request.topK() * 12);
                hits = searcher.search(query, candidates).scoreDocs;
            } catch (IndexSearcher.TooManyClauses e) {
                // a very long question: search with its first words only
                words = words.subList(0, Math.min(8, words.size()));
                query = buildQuery(String.join(" ", words.stream().map(SymbolTokens.Word::whole).toList()), words, request);
                hits = searcher.search(query, Math.max(60, request.topK() * 12)).scoreDocs;
            }

            String normalizedQuery = normalizeForExact(queryText);
            List<Evidence> scored = new ArrayList<>();
            Map<Evidence, String> fullText = new java.util.IdentityHashMap<>();
            for (ScoreDoc hit : hits) {
                if (System.nanoTime() > deadline && !scored.isEmpty()) {
                    break; // out of time: answer with what was found so far
                }
                var doc = searcher.storedFields().document(hit.doc);
                String text = doc.get(SearchIndex.S_TEXT);
                String title = doc.get(SearchIndex.S_TITLE);
                String section = emptyToNull(doc.get(SearchIndex.S_SECTION));
                List<String> docTags = split(doc.get(SearchIndex.S_TAGS));
                List<String> docCategoryNames = split(doc.get(SearchIndex.S_CATS)).stream().map(taxonomy::displayName).toList();
                // Tags and category names are searchable metadata, so they count as covered terms too.
                List<String> docTokens = SymbolTokens.expand(title + "\n" + (section == null ? "" : section) + "\n" + text + "\n"
                        + String.join(" ", docTags) + "\n" + String.join(" ", docCategoryNames));
                double coverage = coverage(words, docTokens);
                double titleCoverage = coverage(words, SymbolTokens.expand(title == null ? "" : title));
                double headingCoverage = section == null ? 0 : coverage(words, SymbolTokens.expand(section));
                boolean exact = normalizedQuery.length() >= 6
                        && (normalizeForExact(text).contains(normalizedQuery) || normalizeForExact(title).contains(normalizedQuery));
                if (exact) {
                    coverage = 1.0;
                }
                if (coverage < minCoverage(words)) {
                    continue; // a weak lexical overlap is not evidence
                }
                boolean accepted = "1".equals(doc.get(SearchIndex.S_ACCEPTED));
                Integer votes = parseInt(doc.get(SearchIndex.S_VOTES));
                double adjusted = hit.score
                        * (0.5 + 0.5 * coverage)
                        * (1.0 + 0.25 * titleCoverage) // wiki page titles are strong relevance signals
                        * (1.0 + 0.30 * headingCoverage) // so are the headings above a section
                        * (accepted ? 1.15 : 1.0)
                        * (1.0 + 0.01 * Math.min(20, Math.max(0, votes == null ? 0 : votes)));
                List<String> cats = split(doc.get(SearchIndex.S_CATS));
                String passage = PassageExtractor.extract(text, words, request.maxPassageChars());
                List<String> flags = InjectionHeuristics.suspicious(text) || InjectionHeuristics.suspicious(title)
                        ? List.of(InjectionHeuristics.FLAG) : List.of();
                String sourceUrl = emptyToNull(doc.get(SearchIndex.S_URL));
                Evidence ev = new Evidence(0, doc.get(SearchIndex.F_TYPE), doc.get(SearchIndex.F_QID),
                        emptyToNull(doc.get(SearchIndex.F_AID)), title, passage,
                        emptyToNull(doc.get(SearchIndex.S_URL)), emptyToNull(doc.get(SearchIndex.S_QURL)),
                        cats, cats.stream().map(taxonomy::displayName).toList(), split(doc.get(SearchIndex.S_TAGS)),
                        accepted, votes, emptyToNull(doc.get(SearchIndex.S_AUTHOR)),
                        emptyToNull(doc.get(SearchIndex.S_CREATED)), emptyToNull(doc.get(SearchIndex.S_QCREATED)),
                        emptyToNull(doc.get(SearchIndex.S_MODIFIED)), emptyToNull(doc.get(SearchIndex.S_SYNCED)),
                        round(adjusted), round(coverage), doc.get(SearchIndex.S_STATUS), flags, true,
                        section, sectionUrl(sourceUrl, emptyToNull(doc.get(SearchIndex.S_SECTION_HEADING))), sectionIndex(doc),
                        flags.isEmpty() ? AnswerComposer.relevantParts(words, text, 3) : List.of()); // suspicious text is never quoted
                scored.add(ev);
                fullText.put(ev, text);
            }
            if (scored.isEmpty()) {
                // nothing by words: the exact text may still be there (symbols, odd spellings, very long identifiers)
                SearchResponse literal = literalSearch(request, rawQuery, deadline);
                if (literal.resultCount() > 0) {
                    return literal;
                }
            }
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));
            SearchResponse response = assemble(request, scored);
            List<AnswerComposer.Candidate> answerFrom = new ArrayList<>();
            for (Evidence e : scored.subList(0, Math.min(ANSWER_CANDIDATES, scored.size()))) {
                answerFrom.add(new AnswerComposer.Candidate(rankIn(response.evidence(), e), fullText.get(e)));
            }
            return withAnswer(response, AnswerComposer.compose(rawQuery, words, answerFrom));
        } catch (IOException e) {
            throw new StoreException("Cannot read search index: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ exact text ("search anything")

    /** True when the question is mostly symbols ("_", "->", "$PATH" is not): words alone cannot find it. */
    static boolean mostlySymbols(String q) {
        String t = q.strip();
        if (t.isEmpty()) {
            return false;
        }
        long letters = t.codePoints().filter(Character::isLetterOrDigit).count();
        return letters * 2 < t.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    }

    /**
     * Finds the question exactly as typed (any characters, case ignored) in the collected text: for symbols, odd
     * identifiers, very long tokens and anything words cannot match. Filters (tags, topics, age) still apply.
     */
    SearchResponse literalSearch(SearchRequest request, String rawQuery, long deadline) {
        String needle = rawQuery.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return empty(request);
        }
        try (Directory directory = new NIOFSDirectory(indexDir); DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            BooleanQuery.Builder all = new BooleanQuery.Builder();
            all.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            addFilter(all, SearchIndex.F_TAG, request.tags(), true);
            addFilter(all, SearchIndex.F_CAT, request.categories(), false);
            addAgeFilters(all, request.age());
            ScoreDoc[] docs = searcher.search(all.build(), Math.max(1, reader.maxDoc())).scoreDocs;
            List<Evidence> found = new ArrayList<>();
            for (ScoreDoc d : docs) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                var doc = searcher.storedFields().document(d.doc);
                String text = doc.get(SearchIndex.S_TEXT);
                String title = doc.get(SearchIndex.S_TITLE);
                String lowerText = text == null ? "" : text.toLowerCase(Locale.ROOT);
                String lowerTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
                int at = lowerText.indexOf(needle);
                boolean inTitle = lowerTitle.contains(needle);
                if (at < 0 && !inTitle) {
                    continue;
                }
                int count = 0;
                for (int i = at; i >= 0 && count < 50; i = lowerText.indexOf(needle, i + Math.max(1, needle.length()))) {
                    count++;
                }
                String section = emptyToNull(doc.get(SearchIndex.S_SECTION));
                String sourceUrl = emptyToNull(doc.get(SearchIndex.S_URL));
                List<String> cats = split(doc.get(SearchIndex.S_CATS));
                List<String> flags = InjectionHeuristics.suspicious(text) || InjectionHeuristics.suspicious(title)
                        ? List.of(InjectionHeuristics.FLAG) : List.of();
                found.add(new Evidence(0, doc.get(SearchIndex.F_TYPE), doc.get(SearchIndex.F_QID),
                        emptyToNull(doc.get(SearchIndex.F_AID)), title, around(text, at, needle.length(), request.maxPassageChars()),
                        sourceUrl, emptyToNull(doc.get(SearchIndex.S_QURL)),
                        cats, cats.stream().map(taxonomy::displayName).toList(), split(doc.get(SearchIndex.S_TAGS)),
                        "1".equals(doc.get(SearchIndex.S_ACCEPTED)), parseInt(doc.get(SearchIndex.S_VOTES)), emptyToNull(doc.get(SearchIndex.S_AUTHOR)),
                        emptyToNull(doc.get(SearchIndex.S_CREATED)), emptyToNull(doc.get(SearchIndex.S_QCREATED)),
                        emptyToNull(doc.get(SearchIndex.S_MODIFIED)), emptyToNull(doc.get(SearchIndex.S_SYNCED)),
                        round(count + (inTitle ? 5 : 0)), 1.0, doc.get(SearchIndex.S_STATUS), flags, true,
                        section, sectionUrl(sourceUrl, emptyToNull(doc.get(SearchIndex.S_SECTION_HEADING))), sectionIndex(doc), List.of()));
            }
            found.sort((a, b) -> Double.compare(b.score(), a.score()));
            SearchResponse response = assemble(request, found);
            return withAnswer(response, AnswerComposer.compose(rawQuery, List.of(), List.of()));
        } catch (IOException e) {
            throw new StoreException("Cannot read search index: " + e.getMessage(), e);
        }
    }

    /** The lines around the first place the text was found (whole lines, so code is not cut mid-line). */
    static String around(String text, int at, int length, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (at < 0 || text.length() <= maxChars) {
            return text.length() <= maxChars ? text.strip() : text.substring(0, maxChars) + "...";
        }
        int start = Math.max(0, at - maxChars / 3);
        int lineStart = text.lastIndexOf('\n', start);
        start = lineStart >= 0 && at - lineStart < maxChars / 2 ? lineStart + 1 : start;
        int end = Math.min(text.length(), start + maxChars);
        int lineEnd = text.indexOf('\n', Math.max(at + length, end - 1));
        if (lineEnd > 0 && lineEnd - start <= maxChars + 200) {
            end = lineEnd;
        }
        return (start > 0 ? "...\n" : "") + text.substring(start, end).strip() + (end < text.length() ? "\n..." : "");
    }

    // ------------------------------------------------------------------ query building

    private Query buildQuery(String queryText, List<SymbolTokens.Word> words, SearchRequest request) {
        BooleanQuery.Builder text = new BooleanQuery.Builder();
        Set<String> seen = new HashSet<>();
        for (SymbolTokens.Word w : words) {
            List<String> terms = new ArrayList<>();
            terms.add(w.whole());
            terms.addAll(w.parts());
            for (String syn : PlainEnglish.synonymsOf(w.whole())) {
                terms.add(syn);
                terms.addAll(PlainEnglish.morphology(syn));
                for (String piece : syn.split("[-_]")) {
                    if (!piece.isBlank()) {
                        terms.add(piece);
                    }
                }
            }
            for (String t : terms) {
                if (SymbolTokens.STOPWORDS.contains(t) || !seen.add(t)) {
                    continue;
                }
                boolean synonym = !t.equals(w.whole()) && !w.parts().contains(t);
                float titleBoost = synonym ? TITLE_BOOST * SYNONYM_BOOST : TITLE_BOOST;
                float metaBoost = synonym ? META_BOOST * SYNONYM_BOOST : META_BOOST;
                BooleanQuery.Builder perTerm = new BooleanQuery.Builder();
                perTerm.add(new BoostQuery(new TermQuery(new Term(SearchIndex.F_TITLE, t)), titleBoost), BooleanClause.Occur.SHOULD);
                perTerm.add(new BoostQuery(new TermQuery(new Term(SearchIndex.F_BODY, t)), synonym ? SYNONYM_BOOST : 1f), BooleanClause.Occur.SHOULD);
                perTerm.add(new BoostQuery(new TermQuery(new Term(SearchIndex.F_HEADING, t)), synonym ? HEADING_BOOST * SYNONYM_BOOST : HEADING_BOOST), BooleanClause.Occur.SHOULD);
                perTerm.add(new BoostQuery(new TermQuery(new Term(SearchIndex.F_META, t)), metaBoost), BooleanClause.Occur.SHOULD);
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
            text.add(new BoostQuery(phraseQuery(SearchIndex.F_HEADING, tokens), HEADING_PHRASE_BOOST), BooleanClause.Occur.SHOULD);
        }
        text.setMinimumNumberShouldMatch(1);

        BooleanQuery.Builder all = new BooleanQuery.Builder();
        all.add(text.build(), BooleanClause.Occur.MUST);
        addFilter(all, SearchIndex.F_TAG, request.tags(), true);
        addFilter(all, SearchIndex.F_CAT, request.categories(), false);
        addAgeFilters(all, request.age());
        return all.build();
    }

    /**
     * Question age applies to every document of a thread. Answer age applies to answer documents only:
     * question documents always pass it. Documents with an unknown creation time never match an active range.
     */
    private void addAgeFilters(BooleanQuery.Builder all, AgeFilter age) {
        if (age.isEmpty()) {
            return;
        }
        java.time.Instant now = clock.instant();
        if (age.hasQuestionFilter()) {
            all.add(org.apache.lucene.document.LongPoint.newRangeQuery(SearchIndex.F_QCREATED, age.questionFrom(now), age.questionTo(now)),
                    BooleanClause.Occur.FILTER);
        }
        if (age.hasAnswerFilter()) {
            BooleanQuery.Builder answerRule = new BooleanQuery.Builder();
            answerRule.add(new TermQuery(new Term(SearchIndex.F_DOCTYPE, "question")), BooleanClause.Occur.SHOULD);
            answerRule.add(org.apache.lucene.document.LongPoint.newRangeQuery(SearchIndex.F_ACREATED, age.answerFrom(now), age.answerTo(now)),
                    BooleanClause.Occur.SHOULD);
            answerRule.setMinimumNumberShouldMatch(1);
            all.add(answerRule.build(), BooleanClause.Occur.FILTER);
        }
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
     * Fraction of query words present in the text. A word found as a whole token counts fully; a stem
     * or synonym hit counts almost fully; camelCase / separator parts alone count half.
     * Soft chatty words ({@link PlainEnglish#softForCoverage}) are ignored in the denominator.
     */
    static double coverage(List<SymbolTokens.Word> words, List<String> docTokens) {
        List<SymbolTokens.Word> scored = words.stream().filter(w -> !PlainEnglish.softForCoverage(w.whole())).toList();
        if (scored.isEmpty()) {
            scored = words;
        }
        if (scored.isEmpty()) {
            return 0;
        }
        Set<String> tokens = new HashSet<>(docTokens);
        double matched = 0;
        for (SymbolTokens.Word w : scored) {
            if (tokens.contains(w.whole())) {
                matched += 1.0;
                continue;
            }
            String stem = PlainEnglish.lightStem(w.whole());
            if (!stem.equals(w.whole()) && tokens.contains(stem)) {
                matched += 0.9;
                continue;
            }
            boolean synonymHit = PlainEnglish.synonymsOf(w.whole()).stream().anyMatch(tokens::contains)
                    || PlainEnglish.synonymsOf(w.whole()).stream()
                    .flatMap(s -> PlainEnglish.morphology(s).stream())
                    .anyMatch(tokens::contains);
            if (synonymHit) {
                matched += 0.85;
                continue;
            }
            if (!w.parts().isEmpty() && w.parts().stream().anyMatch(tokens::contains)) {
                // morphology / camel parts live in parts — any hit is useful for wiki English
                long hits = w.parts().stream().filter(tokens::contains).count();
                matched += Math.min(0.9, 0.45 + 0.15 * hits);
            }
        }
        return matched / scored.size();
    }

    /** Long plain-English questions need a slightly softer gate so useful pages are not dropped. */
    static double minCoverage(List<SymbolTokens.Word> words) {
        int content = (int) words.stream().filter(w -> !PlainEnglish.softForCoverage(w.whole())).count();
        if (content >= 8) {
            return 0.22;
        }
        if (content >= 5) {
            return 0.28;
        }
        return MIN_COVERAGE;
    }

    private static String normalizeForExact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    // ------------------------------------------------------------------ assembling the response

    private SearchResponse assemble(SearchRequest request, List<Evidence> ranked) {
        Map<String, Integer> questionHits = new HashMap<>();
        List<Evidence> chosen = new ArrayList<>();
        int budget = 0;
        int omitted = 0;
        boolean truncated = false;
        for (Evidence e : ranked) {
            if (chosen.size() >= request.topK()) {
                break;
            }
            // Keep every matching answer for a question; only collapse repeated question passages.
            if ("question".equals(e.type())) {
                int used = questionHits.merge(e.questionId(), 1, Integer::sum);
                if (used > MAX_QUESTION_HITS_PER_THREAD) {
                    continue;
                }
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
        return new SearchResponse(request.query(), request.tags(), request.categories(), request.age(), chosen.size(),
                confidence(chosen), truncated, omitted, null, SearchResponse.NOTICE, chosen, null);
    }

    private static SearchResponse withAnswer(SearchResponse r, AnswerComposer.DirectAnswer answer) {
        return new SearchResponse(r.query(), r.tagFilter(), r.categoryFilter(), r.ageFilter(), r.resultCount(), r.confidence(),
                r.truncated(), r.omittedForBudget(), r.freshness(), r.notice(), r.evidence(), answer);
    }

    /** The same passage as shown in the result list (with its rank), or rank 0 when it was collapsed away. */
    private static Evidence rankIn(List<Evidence> shown, Evidence e) {
        for (Evidence s : shown) {
            if (s.questionId().equals(e.questionId()) && java.util.Objects.equals(s.answerId(), e.answerId())
                    && s.sectionIndex() == e.sectionIndex()) {
                return s;
            }
        }
        return e;
    }

    private static int sectionIndex(org.apache.lucene.document.Document doc) {
        var f = doc.getField(SearchIndex.S_SECTION_INDEX);
        return f == null || f.numericValue() == null ? -1 : f.numericValue().intValue();
    }

    /**
     * The original page opened at the section: a text fragment link ({@code #:~:text=Heading}) that Edge and Chrome
     * scroll to and highlight; other browsers simply open the page.
     */
    static String sectionUrl(String sourceUrl, String heading) {
        if (sourceUrl == null || heading == null || heading.isBlank() || sourceUrl.contains("#")) {
            return sourceUrl;
        }
        String encoded = java.net.URLEncoder.encode(heading.strip(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20").replace("-", "%2D").replace(",", "%2C").replace("&", "%26");
        return sourceUrl + "#:~:text=" + encoded;
    }

    private static Evidence withRank(Evidence e, int rank) {
        return new Evidence(rank, e.type(), e.questionId(), e.answerId(), e.title(), e.passage(), e.sourceUrl(), e.questionUrl(),
                e.categories(), e.categoryNames(), e.tags(), e.accepted(), e.votes(), e.author(), e.createdAt(), e.questionCreatedAt(),
                e.sourceModifiedAt(), e.lastSyncedAt(),
                e.score(), e.termCoverage(), e.threadStatus(), e.flags(), e.untrusted(), e.section(), e.sectionUrl(), e.sectionIndex(), e.relevant());
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
        return new SearchResponse(request.query(), request.tags(), request.categories(), request.age(), 0, "NONE", false, 0, null,
                SearchResponse.NOTICE, List.of(), AnswerComposer.compose(request.query(), List.of(), List.of()));
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
