package com.questioncrawler.core.classification;

import com.questioncrawler.core.util.Hashes;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configurable functional taxonomy. Nothing about the business categories is hard-coded: the starter
 * taxonomy ships as a JSON resource that organizations edit.
 *
 * <p>Rule types: {@code keyword} (case-insensitive whole-word/phrase), {@code regex}, {@code tag}
 * (exact tag match, case-insensitive). Fields: title, body, answers, tags, any.
 */
public record Taxonomy(int version, Double minScore, String fallbackCategory, List<Category> categories) {

    public record Rule(String type, String field, String pattern, Double weight) {
        public Rule {
            type = type == null ? "keyword" : type.toLowerCase(java.util.Locale.ROOT);
            field = field == null ? "any" : field.toLowerCase(java.util.Locale.ROOT);
            weight = weight == null ? 1.0 : weight;
        }
    }

    public record Category(String id, String name, String description, List<Rule> rules) {
        public Category {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public Taxonomy {
        minScore = minScore == null ? 2.0 : minScore;
        fallbackCategory = fallbackCategory == null || fallbackCategory.isBlank() ? "uncategorized" : fallbackCategory;
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static Taxonomy loadDefault() {
        try (InputStream in = Taxonomy.class.getClassLoader().getResourceAsStream("defaults/taxonomy.json")) {
            if (in == null) {
                throw new IllegalStateException("Bundled default taxonomy is missing");
            }
            return Json.MAPPER.readValue(in, Taxonomy.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Taxonomy load(Path file) {
        try {
            return Json.MAPPER.readValue(Files.readString(file), Taxonomy.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read taxonomy " + file + ": " + e.getMessage(), e);
        }
    }

    /** Human-readable problems; empty list means the taxonomy is usable. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Category c : categories) {
            if (c.id() == null || !c.id().matches("[a-z0-9][a-z0-9-]*")) {
                problems.add("Category id must be lowercase letters, digits and dashes: " + c.id());
                continue;
            }
            if (!ids.add(c.id())) {
                problems.add("Duplicate category id: " + c.id());
            }
            for (Rule r : c.rules()) {
                if (r.pattern() == null || r.pattern().isBlank()) {
                    problems.add("Category " + c.id() + " has a rule with an empty pattern");
                } else if (r.type().equals("regex")) {
                    try {
                        Pattern.compile(r.pattern());
                    } catch (PatternSyntaxException e) {
                        problems.add("Category " + c.id() + " has an invalid regex '" + r.pattern() + "': " + e.getDescription());
                    }
                } else if (!r.type().equals("keyword") && !r.type().equals("tag")) {
                    problems.add("Category " + c.id() + " has unknown rule type '" + r.type() + "'");
                }
                if (!List.of("title", "body", "answers", "tags", "any").contains(r.field())) {
                    problems.add("Category " + c.id() + " has unknown rule field '" + r.field() + "'");
                }
            }
        }
        if (!ids.contains(fallbackCategory)) {
            problems.add("fallbackCategory '" + fallbackCategory + "' is not a defined category");
        }
        return problems;
    }

    public String hash() {
        return Hashes.sha256(Json.writeCompact(this)).substring(0, 16);
    }

    public Category category(String id) {
        return categories.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    public String displayName(String id) {
        Category c = category(id);
        return c == null ? id : c.name();
    }
}
