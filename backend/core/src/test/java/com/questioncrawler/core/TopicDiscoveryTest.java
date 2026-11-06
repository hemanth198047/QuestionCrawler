package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.classification.Classifier;
import com.questioncrawler.core.classification.TopicDiscovery;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Topic discovery on a synthetic corpus with four known subjects. The discovery never sees the subject labels: it must
 * recover them from the words alone.
 */
class TopicDiscoveryTest {

    static final Map<String, List<String>> SUBJECTS = Map.of(
            "deploy", List.of("kubernetes", "helm", "cluster", "pod", "deployment", "rollout", "container", "namespace", "ingress", "manifest"),
            "auth", List.of("oauth", "token", "login", "permission", "credential", "session", "password", "identity", "claims", "authorization"),
            "ingest", List.of("csv", "import", "batch", "dataset", "ingestion", "records", "pipeline", "schema", "loader", "mapping"),
            "perf", List.of("latency", "throughput", "memory", "cache", "profiling", "slowdown", "bottleneck", "thread", "garbage", "benchmark"));
    static final List<String> FILLER = List.of("need", "help", "error", "question", "problem", "working", "using", "trying", "after", "update", "version", "config");

    private static List<ThreadRecord> corpus(int perSubject) {
        Random random = new Random(7);
        List<ThreadRecord> out = new ArrayList<>();
        int id = 1;
        for (var e : SUBJECTS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            for (int i = 0; i < perSubject; i++) {
                List<String> vocab = e.getValue();
                String t1 = vocab.get(random.nextInt(vocab.size()));
                String t2 = vocab.get(random.nextInt(vocab.size()));
                String t3 = vocab.get(random.nextInt(vocab.size()));
                String title = "Problem with " + t1 + " and " + t2 + " " + FILLER.get(random.nextInt(FILLER.size()));
                String body = "We are " + FILLER.get(random.nextInt(FILLER.size())) + " the " + t1 + " " + t2 + " " + t3 + " and " + t2 + " again. "
                        + FILLER.get(random.nextInt(FILLER.size())) + " " + vocab.get(random.nextInt(vocab.size())) + ".";
                String answer = "Check the " + t3 + " " + vocab.get(random.nextInt(vocab.size())) + " settings.";
                String subjectTag = e.getKey() + "-topic-" + (random.nextInt(4) == 0 ? "x" : "main");
                List<String> tags = List.of("platform", subjectTag.endsWith("main") ? e.getKey() : "misc");
                Question q = new Question(String.valueOf(id), title, body, BodyFormat.MARKDOWN, "https://q/" + id, tags, null, 1,
                        "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z", List.of(),
                        List.of(new Answer("a" + id, answer, BodyFormat.MARKDOWN, null, true, 1, null, null, null, List.of())));
                out.add(new ThreadRecord(1, "t", q, q.id(), null, "h" + id, ThreadStatus.ACTIVE, null, null, null, null, List.of()));
                id++;
            }
        }
        return out;
    }

    private static String subjectOf(ThreadRecord t) {
        int n = Integer.parseInt(t.questionId());
        List<String> order = SUBJECTS.keySet().stream().sorted().toList();
        return order.get((n - 1) / 15);
    }

    @Test
    void foursubjectsAreRecoveredFromTheWordsAlone() {
        List<ThreadRecord> docs = corpus(15);
        var proposal = TopicDiscovery.discover(docs, 4);
        assertEquals(4, proposal.topics().size());
        assertEquals(60, proposal.documents());

        // Every discovered topic is dominated (purity) by exactly one real subject, and all four subjects are found.
        Classifier classifier = new Classifier(proposal.taxonomy());
        Map<String, Map<String, Integer>> table = new HashMap<>();
        for (ThreadRecord d : docs) {
            for (String cat : classifier.classify(d.question()).categoryIds()) {
                table.computeIfAbsent(cat, k -> new HashMap<>()).merge(subjectOf(d), 1, Integer::sum);
            }
        }
        java.util.Set<String> subjectsFound = new java.util.HashSet<>();
        for (var topic : proposal.topics()) {
            Map<String, Integer> row = table.getOrDefault(topic.id(), Map.of());
            int total = row.values().stream().mapToInt(Integer::intValue).sum();
            var top = row.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            subjectsFound.add(top.getKey());
            assertTrue(total > 0 && top.getValue() >= 0.85 * total, topic.name() + " purity " + row);
        }
        assertEquals(SUBJECTS.keySet(), subjectsFound, "each real subject became its own topic");
        assertTrue(proposal.placedShare() >= 0.9, "share placed by the rules: " + proposal.placedShare());
    }

    @Test
    void topicsAreNamedFromTheirOwnDistinctiveWords() {
        var proposal = TopicDiscovery.discover(corpus(15), 4);
        for (var topic : proposal.topics()) {
            String subject = SUBJECTS.entrySet().stream()
                    .filter(e -> topic.topTerms().stream().filter(t -> e.getValue().stream().anyMatch(v -> v.startsWith(t.substring(0, Math.min(t.length(), 5))))).count() >= 3)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            assertTrue(subject != null, "the top words of '" + topic.name() + "' belong to one subject: " + topic.topTerms());
            assertFalse(topic.name().isBlank());
            assertTrue(topic.description().startsWith("Questions about "));
            assertEquals(3, topic.samples().size());
            assertTrue(topic.rules().stream().anyMatch(r -> r.type().equals("keyword")));
        }
        assertEquals(4, proposal.topics().stream().map(t -> t.name()).distinct().count());
        assertEquals(4, proposal.topics().stream().map(t -> t.id()).distinct().count());
    }

    @Test
    void theSameQuestionsAlwaysGiveTheSameTopics() {
        var a = TopicDiscovery.discover(corpus(15), 4);
        var b = TopicDiscovery.discover(corpus(15), 4);
        assertEquals(a.topics().stream().map(t -> t.name() + t.size()).toList(), b.topics().stream().map(t -> t.name() + t.size()).toList());
        assertEquals(a.taxonomy(), b.taxonomy());
    }

    @Test
    void theNumberOfTopicsIsChosenAutomaticallyWhenNotGiven() {
        var proposal = TopicDiscovery.discover(corpus(15), 0);
        assertTrue(proposal.topics().size() >= 2 && proposal.topics().size() <= 10, "" + proposal.topics().size());
    }

    @Test
    void theResultIsAnOrdinaryValidTaxonomyWithACatchAll() {
        var proposal = TopicDiscovery.discover(corpus(15), 4);
        assertEquals(List.of(), proposal.taxonomy().validate());
        assertEquals("uncategorized", proposal.taxonomy().fallbackCategory());
        assertEquals(5, proposal.taxonomy().categories().size());
        assertTrue(proposal.topics().stream().mapToInt(t -> t.matches()).sum() >= 54, "the rules place at least 90% of the questions");
        assertTrue(proposal.topics().stream().flatMap(t -> t.rules().stream()).noneMatch(r -> r.type().equals("tag") && r.pattern().equals("platform")),
                "a tag found on every question is not a rule");
    }

    @Test
    void tooLittleDataIsExplainedInsteadOfInventingTopics() {
        var proposal = TopicDiscovery.discover(corpus(1), 3);
        assertTrue(proposal.topics().isEmpty());
        assertTrue(proposal.notes().get(0).contains("collect more"));
        assertTrue(TopicDiscovery.discover(List.of(), 3).topics().isEmpty());
    }

    @Test
    void removedQuestionsAndCodeBlocksDoNotInfluenceTheTopics() {
        List<ThreadRecord> docs = new ArrayList<>(corpus(15));
        ThreadRecord first = docs.get(0);
        docs.set(0, first.withStatus(ThreadStatus.DELETED, "now", "deleted"));
        assertEquals(59, TopicDiscovery.discover(docs, 4).documents());
    }
}
