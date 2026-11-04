package com.questioncrawler.core.classification;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.Question;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based classifier. It is NOT semantic understanding: a thread is assigned to
 * every category whose matching rules reach the taxonomy's minimum score, and the matched rules are
 * recorded so a human can see exactly why. Threads that match nothing fall back to Uncategorized.
 */
public final class Classifier {

    public static final String METHOD = "rules-v1";

    private record CompiledRule(Taxonomy.Rule rule, Pattern pattern) {}

    private record CompiledCategory(String id, List<CompiledRule> rules) {}

    private final Taxonomy taxonomy;
    private final List<CompiledCategory> compiled;

    public Classifier(Taxonomy taxonomy) {
        List<String> problems = taxonomy.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid taxonomy: " + String.join("; ", problems));
        }
        this.taxonomy = taxonomy;
        this.compiled = taxonomy.categories().stream()
                .filter(c -> !c.id().equals(taxonomy.fallbackCategory()))
                .map(c -> new CompiledCategory(c.id(), c.rules().stream().map(Classifier::compile).toList()))
                .toList();
    }

    public Taxonomy taxonomy() {
        return taxonomy;
    }

    public Classification classify(Question q) {
        String title = q.title();
        String body = q.body();
        String answers = String.join("\n", q.answers().stream().map(Answer::body).toList());
        String tags = String.join("\n", q.tags());
        String any = title + "\n" + body + "\n" + answers + "\n" + tags;

        List<Classification.CategoryMatch> matches = new ArrayList<>();
        for (CompiledCategory category : compiled) {
            double score = 0;
            List<String> matched = new ArrayList<>();
            for (CompiledRule cr : category.rules()) {
                String haystack = switch (cr.rule().field()) {
                    case "title" -> title;
                    case "body" -> body;
                    case "answers" -> answers;
                    case "tags" -> tags;
                    default -> any;
                };
                if (cr.pattern().matcher(haystack).find()) {
                    score += cr.rule().weight();
                    matched.add(cr.rule().type() + "[" + cr.rule().field() + "]:" + cr.rule().pattern() + " (+" + cr.rule().weight() + ")");
                }
            }
            if (score >= taxonomy.minScore()) {
                matches.add(new Classification.CategoryMatch(category.id(), score, matched));
            }
        }
        matches.sort(Comparator.comparingDouble(Classification.CategoryMatch::score).reversed()
                .thenComparing(Classification.CategoryMatch::categoryId));
        if (matches.isEmpty()) {
            matches.add(new Classification.CategoryMatch(taxonomy.fallbackCategory(), 0,
                    List.of("fallback: no category reached minScore " + taxonomy.minScore())));
        }
        return new Classification(METHOD, taxonomy.hash(), Instant.now().toString(), matches);
    }

    private static CompiledRule compile(Taxonomy.Rule rule) {
        Pattern pattern = switch (rule.type()) {
            case "regex" -> Pattern.compile(rule.pattern());
            case "tag" -> Pattern.compile("(?im)^" + Pattern.quote(rule.pattern().toLowerCase(Locale.ROOT)) + "$");
            default -> Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])" + Pattern.quote(rule.pattern()) + "(?![\\p{L}\\p{N}_])");
        };
        return new CompiledRule(rule, pattern);
    }
}
