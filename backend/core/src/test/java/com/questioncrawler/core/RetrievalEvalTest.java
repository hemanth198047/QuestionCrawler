package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.search.Evidence;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.util.Json;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Retrieval quality gate on a small labelled set. Reports hit@k and MRR so regressions in tokenization,
 * ranking or filtering are visible. Queries with an empty expectation must return NO evidence.
 */
class RetrievalEvalTest {

    @TempDir
    Path tmp;

    @Test
    void evaluationSetMeetsQualityTargets() throws Exception {
        KnowledgeBase kb = TestSupport.kb(tmp, TestSupport.siteV1());
        kb.sync(TestSupport.opts(SyncMode.INCREMENTAL));

        JsonNode set;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("eval/retrieval-eval.json")) {
            set = Json.MAPPER.readTree(in);
        }
        int k = set.path("k").asInt(3);
        int answerable = 0;
        int hits = 0;
        double reciprocalRankSum = 0;
        List<String> misses = new ArrayList<>();
        List<String> falseEvidence = new ArrayList<>();
        int noAnswerQueries = 0;

        for (JsonNode q : set.path("queries")) {
            String query = q.path("query").asText();
            List<String> expected = new ArrayList<>();
            q.path("expectedQuestionIds").forEach(n -> expected.add(n.asText()));
            List<Evidence> evidence = kb.search(new SearchRequest(query, null, null, 10, 0, 0)).evidence();
            Set<String> rankedQuestions = new LinkedHashSet<>();
            evidence.forEach(e -> rankedQuestions.add(e.questionId()));

            if (expected.isEmpty()) {
                noAnswerQueries++;
                if (!evidence.isEmpty()) {
                    falseEvidence.add(q.path("id").asText() + " '" + query + "' -> " + rankedQuestions);
                }
                continue;
            }
            answerable++;
            int rank = 0;
            int found = -1;
            for (String id : rankedQuestions) {
                rank++;
                if (expected.contains(id)) {
                    found = rank;
                    break;
                }
            }
            if (found > 0 && found <= k) {
                hits++;
                reciprocalRankSum += 1.0 / found;
            } else {
                misses.add(q.path("id").asText() + " '" + query + "' expected " + expected + " got " + rankedQuestions);
            }
        }
        double hitAtK = (double) hits / answerable;
        double mrr = reciprocalRankSum / answerable;
        System.out.printf("Retrieval eval: %d answerable, hit@%d = %.2f, MRR = %.2f, no-answer queries = %d%n",
                answerable, k, hitAtK, mrr, noAnswerQueries);
        misses.forEach(m -> System.out.println("  MISS " + m));

        assertTrue(noAnswerQueries >= 1, "the set must contain at least one query with no answer");
        assertEquals(List.of(), falseEvidence, "queries without an answer must not return evidence");
        assertTrue(hitAtK >= 0.9, "hit@" + k + " was " + hitAtK + " misses: " + misses);
        assertTrue(mrr >= 0.8, "MRR was " + mrr);
    }
}
