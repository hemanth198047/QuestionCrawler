package com.questioncrawler.core.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Light plain-English helpers for lexical search — morphology and a small curated synonym map.
 * No LLM and no vectors: these only add alternate tokens so everyday wording can still hit wiki
 * and Q&amp;A text that uses a related form.
 *
 * <p>Technical identifiers (digits, separators, camelCase pieces that look like codes) are left alone.
 */
public final class PlainEnglish {

    /** Chatty words that should not dilute term-coverage for long natural-language questions. */
    static final Set<String> COVERAGE_SOFT = Set.of(
            "please", "help", "someone", "somehow", "anybody", "anyone", "need", "needs", "needed", "needing",
            "trying", "try", "tried", "looking", "look", "find", "finding", "get", "getting", "got", "make",
            "making", "want", "wanted", "wanna", "kinda", "really", "just", "also", "about", "using", "use",
            "used", "tell", "show", "explain", "guide", "docs", "documentation");

    private static final Map<String, List<String>> SYNONYMS = buildSynonyms();

    private PlainEnglish() {}

    /** True when the token looks like an error code / identifier and must not be stemmed. */
    public static boolean codeLike(String token) {
        if (token == null || token.length() < 2) {
            return true;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == '/') {
                return true;
            }
        }
        return false;
    }

    /**
     * Additive light stem for alphabetic English words (plurals and common endings).
     * Returns the input unchanged when stemming would not help or would be unsafe.
     */
    public static String lightStem(String word) {
        if (word == null) {
            return "";
        }
        String w = word.toLowerCase(Locale.ROOT);
        if (w.length() < 4 || codeLike(w) || !w.chars().allMatch(Character::isLetter)) {
            return w;
        }
        if (w.length() > 6 && w.endsWith("isation")) {
            return w.substring(0, w.length() - "isation".length()) + "ize";
        }
        if (w.length() > 6 && w.endsWith("ization")) {
            return w.substring(0, w.length() - "ization".length()) + "ize";
        }
        if (w.length() > 5 && w.endsWith("ation")) {
            return w.substring(0, w.length() - "ation".length()) + "ate";
        }
        if (w.length() > 5 && w.endsWith("tion") && !w.endsWith("ation")) {
            return w.substring(0, w.length() - "tion".length());
        }
        if (w.length() > 5 && w.endsWith("iness")) {
            return w.substring(0, w.length() - "iness".length()) + "y";
        }
        if (w.length() > 5 && w.endsWith("ment")) {
            return w.substring(0, w.length() - "ment".length());
        }
        if (w.length() > 5 && w.endsWith("ness")) {
            return w.substring(0, w.length() - "ness".length());
        }
        if (w.length() > 5 && w.endsWith("able")) {
            return w.substring(0, w.length() - "able".length());
        }
        if (w.length() > 5 && w.endsWith("ible")) {
            return w.substring(0, w.length() - "ible".length());
        }
        if (w.length() > 5 && w.endsWith("ing")) {
            String base = w.substring(0, w.length() - 3);
            if (base.length() >= 3 && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)) {
                return base.substring(0, base.length() - 1); // running -> run
            }
            if (base.endsWith("e") || base.length() < 4) {
                return base;
            }
            return base + "e"; // configuring -> configure (best-effort)
        }
        if (w.length() > 4 && w.endsWith("ied")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 4 && w.endsWith("ed") && !w.endsWith("eed")) {
            String base = w.substring(0, w.length() - 2);
            if (base.length() >= 3 && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)) {
                return base.substring(0, base.length() - 1);
            }
            return base.endsWith("e") ? base : base; // configured -> configur (paired with -ing→configure via stem match on docs)
        }
        if (w.length() > 5 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 4 && w.endsWith("es") && !w.endsWith("sses") && !w.endsWith("ses")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && !w.endsWith("os")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** Stem forms to index/query alongside the surface token (never replaces it). */
    public static List<String> morphology(String token) {
        if (token == null || token.isBlank() || codeLike(token)) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        String stem = lightStem(token);
        if (!stem.isBlank() && !stem.equals(token) && stem.length() >= 3) {
            out.add(stem);
            String again = lightStem(stem);
            if (!again.equals(stem) && again.length() >= 3) {
                out.add(again);
            }
        }
        return List.copyOf(out);
    }

    /** Curated synonyms for the query term (lowercased). Empty when unknown. */
    public static List<String> synonymsOf(String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        return SYNONYMS.getOrDefault(term.toLowerCase(Locale.ROOT), List.of());
    }

    /** Words that should not count against coverage for long plain-English questions. */
    public static boolean softForCoverage(String term) {
        return term != null && COVERAGE_SOFT.contains(term.toLowerCase(Locale.ROOT));
    }

    private static Map<String, List<String>> buildSynonyms() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        addGroup(groups, "sso", "single-sign-on", "singlesignon");
        addGroup(groups, "runbook", "playbook", "ops-guide", "opsguide");
        addGroup(groups, "onboarding", "onboard", "new-hire", "newhire", "orientation");
        addGroup(groups, "incident", "outage", "sev", "severity");
        addGroup(groups, "architecture", "design", "overview");
        addGroup(groups, "deploy", "deployment", "release", "rollout");
        addGroup(groups, "configure", "configuration", "setup", "set-up");
        addGroup(groups, "auth", "authentication", "login", "sign-in", "signin");
        addGroup(groups, "permission", "permissions", "access", "acl", "entitlement");
        addGroup(groups, "timeout", "timed-out", "time-out");
        addGroup(groups, "kubernetes", "k8s");
        addGroup(groups, "documentation", "docs", "wiki", "page");
        addGroup(groups, "troubleshoot", "troubleshooting", "debug", "debugging");
        addGroup(groups, "password", "credentials", "secret");
        addGroup(groups, "vpn", "remote-access");
        addGroup(groups, "howto", "how-to", "guide", "tutorial");
        return Map.copyOf(groups);
    }

    private static void addGroup(Map<String, List<String>> map, String... terms) {
        List<String> all = new ArrayList<>();
        for (String t : terms) {
            if (t != null && !t.isBlank()) {
                all.add(t.toLowerCase(Locale.ROOT));
            }
        }
        for (String t : all) {
            List<String> others = all.stream().filter(x -> !x.equals(t)).toList();
            map.put(t, others);
        }
    }
}
