package com.questioncrawler.core.classification;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds topics in the collected questions from their CONTEXT (title, tags, text and answers), without any external AI service.
 *
 * <p>Method: each question becomes a weighted bag of words (title and tags count most), words are weighted by how distinctive
 * they are (TF-IDF), and questions are grouped with deterministic spherical k-means. Every group is named from its most
 * distinctive words and turned into ordinary, readable rules (words and tags) that the normal classifier can apply, so the result
 * can be reviewed, renamed and edited like any hand-written taxonomy. The same input always gives the same topics.
 */
public final class TopicDiscovery {

    public record Sample(String id, String title) {}

    public record Topic(String id, String name, String description, int size, int matches, List<String> topTerms, List<String> topTags,
                        List<Sample> samples, List<Taxonomy.Rule> rules) {}

    public record Proposal(List<Topic> topics, int documents, int unplaced, double placedShare, String method, List<String> notes, Taxonomy taxonomy) {}

    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one", "our", "out", "has", "have", "how", "its", "may",
            "new", "now", "old", "see", "two", "way", "who", "did", "get", "got", "let", "put", "say", "she", "too", "use", "used", "using", "uses", "that", "this",
            "with", "from", "what", "when", "where", "which", "why", "will", "would", "could", "should", "there", "their", "them", "then", "than", "these", "those",
            "been", "being", "does", "doing", "done", "each", "else", "even", "ever", "just", "like", "make", "many", "more", "most", "much", "must", "need", "only",
            "other", "over", "same", "some", "such", "take", "into", "also", "about", "after", "again", "because", "before", "between", "both", "while", "here",
            "your", "yours", "mine", "very", "still", "want", "wants", "work", "works", "working", "worked", "try", "tried", "trying", "help", "question", "questions",
            "answer", "answers", "thanks", "thank", "please", "hello", "anyone", "someone", "something", "anything", "thing", "things", "issue", "problem", "problems",
            "seems", "seem", "found", "following", "below", "above", "example", "examples", "possible", "able", "know", "knows", "think", "really", "though", "without",
            "within", "whether", "every", "already", "currently", "different", "correct", "right", "wrong", "good", "best", "better", "first", "second", "last", "next");
    private static final Pattern WORD = Pattern.compile("\\p{L}{3,25}");
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```.*?```");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private TopicDiscovery() {}

    /** @param k wanted number of topics; 0 or less lets the method choose from the amount of data. */
    public static Proposal discover(List<ThreadRecord> threads, int k) {
        List<ThreadRecord> docs = threads.stream().filter(t -> t.question() != null && t.status().isRetrievable()).toList();
        int n = docs.size();
        List<String> notes = new ArrayList<>();
        if (n < 8) {
            notes.add("Only " + n + " question(s) are available. Topics need more questions to be meaningful: collect more first.");
            return new Proposal(List.of(), n, n, 0, "TF-IDF + spherical k-means", notes, new Taxonomy(1, 2.0, "uncategorized", List.of(fallback())));
        }

        // ---- bags of words
        List<Map<String, Double>> bags = new ArrayList<>();
        Map<String, Map<String, Integer>> surface = new HashMap<>();
        for (ThreadRecord t : docs) {
            bags.add(bag(t.question(), surface));
        }
        // ---- document frequency, vocabulary
        Map<String, Integer> df = new HashMap<>();
        bags.forEach(b -> b.keySet().forEach(term -> df.merge(term, 1, Integer::sum)));
        int minDf = n >= 12 ? 2 : 1;
        double maxDf = n >= 10 ? 0.5 * n : n;
        Map<String, Double> massByTerm = new HashMap<>();
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            if (e.getValue() < minDf || e.getValue() > maxDf) {
                continue;
            }
            idf.put(e.getKey(), Math.log((n + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }
        for (Map<String, Double> b : bags) {
            b.forEach((term, count) -> {
                if (idf.containsKey(term)) {
                    massByTerm.merge(term, (1 + Math.log(Math.max(1.0, count))) * idf.get(term), Double::sum);
                }
            });
        }
        List<String> vocabulary = massByTerm.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(1500).map(Map.Entry::getKey).toList();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < vocabulary.size(); i++) {
            index.put(vocabulary.get(i), i);
        }
        if (vocabulary.size() < 3) {
            notes.add("The questions share too few meaningful words to group them.");
            return new Proposal(List.of(), n, n, 0, "TF-IDF + spherical k-means", notes, new Taxonomy(1, 2.0, "uncategorized", List.of(fallback())));
        }

        // ---- sparse unit vectors
        int[][] idx = new int[n][];
        double[][] val = new double[n][];
        for (int d = 0; d < n; d++) {
            Map<Integer, Double> sparse = new HashMap<>();
            bags.get(d).forEach((term, count) -> {
                Integer ti = index.get(term);
                if (ti != null) {
                    sparse.put(ti, (1 + Math.log(Math.max(1.0, count))) * idf.get(term));
                }
            });
            int[] keys = sparse.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            double[] values = new double[keys.length];
            double norm = 0;
            for (int i = 0; i < keys.length; i++) {
                values[i] = sparse.get(keys[i]);
                norm += values[i] * values[i];
            }
            norm = Math.sqrt(norm);
            for (int i = 0; i < values.length; i++) {
                values[i] = norm == 0 ? 0 : values[i] / norm;
            }
            idx[d] = keys;
            val[d] = values;
        }

        // ---- k-means
        int clusters = k > 0 ? Math.min(k, n / 2) : Math.max(2, Math.min(10, (int) Math.round(Math.sqrt(n / 2.0))));
        clusters = Math.max(1, Math.min(clusters, n));
        int[] assignment = kmeans(idx, val, vocabulary.size(), clusters, seed(docs));

        // ---- topics from clusters
        Map<String, Integer> globalTags = new HashMap<>();
        for (ThreadRecord t : docs) {
            for (String tag : t.question().tags()) {
                globalTags.merge(tag.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        double[][] centroids = centroids(idx, val, assignment, vocabulary.size(), clusters);
        List<Topic> topics = new ArrayList<>();
        Map<Integer, String> topicOfCluster = new HashMap<>();
        Set<String> usedIds = new java.util.HashSet<>();
        for (int c = 0; c < clusters; c++) {
            List<Integer> members = new ArrayList<>();
            for (int d = 0; d < n; d++) {
                if (assignment[d] == c) {
                    members.add(d);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            List<String> terms = distinctiveTerms(centroids, c, vocabulary, 8);
            List<String> tags = dominantTags(members, docs, globalTags);
            String name = nameOf(terms, tags, surface);
            String id = uniqueId(slug(name), usedIds);
            topicOfCluster.put(c, id);
            List<String> surfaceTerms = terms.stream().map(t -> surfaceForm(t, surface)).distinct().toList();
            List<Taxonomy.Rule> rules = new ArrayList<>();
            for (String t : tags) {
                rules.add(new Taxonomy.Rule("tag", "tags", t, 2.0));
            }
            surfaceTerms.stream().limit(6).forEach(w -> rules.add(new Taxonomy.Rule("keyword", "any", w, 1.0)));
            final int cluster = c;
            List<Sample> samples = members.stream()
                    .sorted(Comparator.comparingDouble((Integer d) -> -cosine(idx[d], val[d], centroids[cluster])))
                    .limit(3).map(d -> new Sample(docs.get(d).questionId(), docs.get(d).question().title())).toList();
            topics.add(new Topic(id, name, "Questions about " + humanList(surfaceTerms.stream().limit(3).toList()) + ". Found automatically from " + members.size() + " question(s).",
                    members.size(), 0, surfaceTerms, tags, samples, rules));
        }
        topics.sort(Comparator.comparingInt(Topic::size).reversed().thenComparing(Topic::name));

        // ---- how strict should the rules be? Try 2, 1.5 and 1 points and keep the best: correct placements count, wrong ones cost.
        double minScore = 2.0;
        double bestScore = -1;
        for (double candidate : new double[] {2.0, 1.5, 1.0}) {
            Classifier trial = new Classifier(taxonomyOf(topics, candidate));
            double score = 0;
            for (int d = 0; d < n; d++) {
                List<String> got = trial.classify(docs.get(d).question()).categoryIds();
                String expected = topicOfCluster.get(assignment[d]);
                boolean hit = got.contains(expected);
                long wrong = got.stream().filter(x -> !x.equals(expected) && !x.equals("uncategorized")).count();
                score += (hit ? 1.0 : 0.0) - 0.5 * wrong;
            }
            score /= n;
            if (score > bestScore + 0.02) { // prefer the stricter rules unless the looser ones are clearly better
                bestScore = score;
                minScore = candidate;
            }
        }

        // ---- what the rules would really do (they are applied by the normal classifier)
        Taxonomy taxonomy = taxonomyOf(topics, minScore);
        Classifier classifier = new Classifier(taxonomy);
        Map<String, Integer> matches = new HashMap<>();
        int unplaced = 0;
        for (ThreadRecord t : docs) {
            var result = classifier.classify(t.question());
            if (result.categoryIds().equals(List.of(taxonomy.fallbackCategory()))) {
                unplaced++;
            }
            result.categoryIds().forEach(cid -> matches.merge(cid, 1, Integer::sum));
        }
        List<Topic> withMatches = topics.stream().map(t -> new Topic(t.id(), t.name(), t.description(), t.size(), matches.getOrDefault(t.id(), 0),
                t.topTerms(), t.topTags(), t.samples(), t.rules())).toList();
        double placed = n == 0 ? 0 : 1.0 - (double) unplaced / n;
        if (placed < 0.6) {
            notes.add("Only " + Math.round(placed * 100) + "% of the questions would be placed by these rules. Try a different number of topics, or collect more questions.");
        }
        notes.add("Topics are found from the words in your questions, so they reflect what people actually ask. Rename them and adjust the rules before saving.");
        return new Proposal(withMatches, n, unplaced, placed, "TF-IDF + spherical k-means", notes, taxonomy);
    }

    /** The topics as an ordinary taxonomy (plus the catch-all), ready to save. */
    public static Taxonomy taxonomyOf(List<Topic> topics) {
        return taxonomyOf(topics, 2.0);
    }

    /** @param minScore points a question needs to enter a topic (keyword = 1, tag = 2) */
    public static Taxonomy taxonomyOf(List<Topic> topics, double minScore) {
        List<Taxonomy.Category> categories = new ArrayList<>();
        for (Topic t : topics) {
            categories.add(new Taxonomy.Category(t.id(), t.name(), t.description(), t.rules()));
        }
        categories.add(fallback());
        return new Taxonomy(1, minScore, "uncategorized", categories);
    }

    private static Taxonomy.Category fallback() {
        return new Taxonomy.Category("uncategorized", "Uncategorized", "Questions that match none of the topics yet.", List.of());
    }

    // ------------------------------------------------------------------ text to words

    private static Map<String, Double> bag(Question q, Map<String, Map<String, Integer>> surface) {
        Map<String, Double> bag = new HashMap<>();
        add(bag, surface, q.title(), 3.0);
        for (String tag : q.tags()) {
            String t = tag.toLowerCase(Locale.ROOT);
            bag.merge("tag:" + t, 3.0, Double::sum);
            add(bag, surface, t.replace('-', ' '), 1.5);
        }
        add(bag, surface, clean(q.body()), 1.0);
        List<Answer> answers = new ArrayList<>(q.answers());
        answers.sort(Comparator.comparing((Answer a) -> !a.accepted()).thenComparing(a -> -(a.score() == null ? 0 : a.score())));
        for (Answer a : answers.stream().limit(3).toList()) {
            add(bag, surface, clean(a.body()), 0.5);
        }
        return bag;
    }

    private static String clean(String text) {
        return URL.matcher(CODE_FENCE.matcher(text == null ? "" : text).replaceAll(" ")).replaceAll(" ");
    }

    private static void add(Map<String, Double> bag, Map<String, Map<String, Integer>> surface, String text, double weight) {
        Matcher m = WORD.matcher(text.replace('_', ' '));
        while (m.find()) {
            String raw = m.group().toLowerCase(Locale.ROOT);
            if (STOP.contains(raw)) {
                continue;
            }
            String stem = stem(raw);
            if (STOP.contains(stem) || stem.length() < 3) {
                continue;
            }
            bag.merge(stem, weight, Double::sum);
            surface.computeIfAbsent(stem, k -> new HashMap<>()).merge(raw, 1, Integer::sum);
        }
    }

    private static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private static String surfaceForm(String term, Map<String, Map<String, Integer>> surface) {
        if (term.startsWith("tag:")) {
            return term.substring(4);
        }
        Map<String, Integer> forms = surface.get(term);
        if (forms == null || forms.isEmpty()) {
            return term;
        }
        return forms.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey())).get().getKey();
    }

    // ------------------------------------------------------------------ k-means

    private static long seed(List<ThreadRecord> docs) {
        long h = 1125899906842597L;
        for (ThreadRecord t : docs) {
            h = 31 * h + t.key().hashCode();
        }
        return h;
    }

    private static int[] kmeans(int[][] idx, double[][] val, int dims, int k, long seed) {
        int n = idx.length;
        Random random = new Random(seed);
        double[][] centroids = new double[k][];
        // k-means++ initialization
        int first = random.nextInt(n);
        centroids[0] = dense(idx[first], val[first], dims);
        double[] best = new double[n];
        Arrays.fill(best, Double.MAX_VALUE);
        for (int c = 1; c < k; c++) {
            double total = 0;
            for (int d = 0; d < n; d++) {
                best[d] = Math.min(best[d], 1 - cosine(idx[d], val[d], centroids[c - 1]));
                total += best[d] * best[d];
            }
            double pick = random.nextDouble() * total;
            int chosen = n - 1;
            double acc = 0;
            for (int d = 0; d < n; d++) {
                acc += best[d] * best[d];
                if (acc >= pick) {
                    chosen = d;
                    break;
                }
            }
            centroids[c] = dense(idx[chosen], val[chosen], dims);
        }
        int[] assignment = new int[n];
        Arrays.fill(assignment, -1);
        for (int iteration = 0; iteration < 40; iteration++) {
            boolean changed = false;
            for (int d = 0; d < n; d++) {
                int bestC = 0;
                double bestSim = -2;
                for (int c = 0; c < k; c++) {
                    double sim = cosine(idx[d], val[d], centroids[c]);
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestC = c;
                    }
                }
                if (assignment[d] != bestC) {
                    assignment[d] = bestC;
                    changed = true;
                }
            }
            centroids = centroids(idx, val, assignment, dims, k);
            for (int c = 0; c < k; c++) { // an emptied cluster is re-seeded with the worst-fitting question
                if (norm(centroids[c]) == 0) {
                    int worst = 0;
                    double worstSim = 2;
                    for (int d = 0; d < n; d++) {
                        double sim = cosine(idx[d], val[d], centroids[assignment[d]]);
                        if (sim < worstSim) {
                            worstSim = sim;
                            worst = d;
                        }
                    }
                    centroids[c] = dense(idx[worst], val[worst], dims);
                    assignment[worst] = c;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return assignment;
    }

    private static double[][] centroids(int[][] idx, double[][] val, int[] assignment, int dims, int k) {
        double[][] sum = new double[k][dims];
        int[] count = new int[k];
        for (int d = 0; d < idx.length; d++) {
            int c = assignment[d];
            if (c < 0) {
                continue;
            }
            count[c]++;
            for (int i = 0; i < idx[d].length; i++) {
                sum[c][idx[d][i]] += val[d][i];
            }
        }
        for (int c = 0; c < k; c++) {
            if (count[c] > 0) {
                for (int t = 0; t < dims; t++) {
                    sum[c][t] /= count[c];
                }
            }
        }
        return sum;
    }

    private static double[] dense(int[] idx, double[] val, int dims) {
        double[] v = new double[dims];
        for (int i = 0; i < idx.length; i++) {
            v[idx[i]] = val[i];
        }
        return v;
    }

    private static double cosine(int[] idx, double[] val, double[] centroid) {
        double dot = 0;
        for (int i = 0; i < idx.length; i++) {
            dot += val[i] * centroid[idx[i]];
        }
        double n = norm(centroid);
        return n == 0 ? 0 : dot / n;
    }

    private static double norm(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x * x;
        }
        return Math.sqrt(s);
    }

    // ------------------------------------------------------------------ naming

    private static List<String> distinctiveTerms(double[][] centroids, int c, List<String> vocabulary, int limit) {
        int k = centroids.length;
        double[] score = new double[vocabulary.size()];
        for (int t = 0; t < vocabulary.size(); t++) {
            double others = 0;
            for (int o = 0; o < k; o++) {
                if (o != c) {
                    others += centroids[o][t];
                }
            }
            double mean = k > 1 ? others / (k - 1) : 0;
            score[t] = centroids[c][t] - 0.6 * mean;
        }
        return java.util.stream.IntStream.range(0, vocabulary.size()).boxed()
                .filter(t -> score[t] > 0)
                .sorted(Comparator.comparingDouble((Integer t) -> -score[t]).thenComparing(t -> vocabulary.get(t)))
                .limit(limit).map(vocabulary::get).toList();
    }

    /** Tags that characterize THIS group: common inside it and clearly rarer outside (a tag on every question tells nothing). */
    private static List<String> dominantTags(List<Integer> members, List<ThreadRecord> docs, Map<String, Integer> globalTags) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int d : members) {
            for (String tag : docs.get(d).question().tags()) {
                counts.merge(tag.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(0.4 * members.size()));
        int n = docs.size();
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .filter(e -> (double) e.getValue() / members.size() >= 2.0 * globalTags.getOrDefault(e.getKey(), 0) / n)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(3).map(Map.Entry::getKey).toList();
    }

    private static String nameOf(List<String> terms, List<String> tags, Map<String, Map<String, Integer>> surface) {
        List<String> words = new ArrayList<>();
        for (String t : terms) {
            String w = surfaceForm(t, surface);
            if (w.startsWith("tag:")) {
                w = w.substring(4);
            }
            String title = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (!words.contains(title)) {
                words.add(title);
            }
            if (words.size() == 3) {
                break;
            }
        }
        if (words.isEmpty() && !tags.isEmpty()) {
            words.add(tags.get(0));
        }
        return words.isEmpty() ? "Topic" : String.join(" / ", words);
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "topic" : s;
    }

    private static String uniqueId(String base, Set<String> used) {
        String id = base;
        int n = 2;
        while (!used.add(id) || id.equals("uncategorized")) {
            id = base + "-" + n++;
        }
        return id;
    }

    private static String humanList(List<String> items) {
        if (items.isEmpty()) {
            return "various subjects";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
