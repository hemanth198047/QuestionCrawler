package com.questioncrawler.core.util;

/**
 * The words for what was collected, following the source: Confluence and SharePoint have pages, Stack Exchange /
 * Stack Overflow and the internal Q&A site have questions (with answers), Jive has posts (with replies).
 * Messages shown to people use these instead of a fixed "question".
 */
public record Terms(String item, String items, boolean hasAnswers, String answer, String answers) {

    public static final Terms QUESTIONS = new Terms("question", "questions", true, "answer", "answers");
    public static final Terms PAGES = new Terms("page", "pages", false, "answer", "answers");
    public static final Terms POSTS = new Terms("post", "posts", true, "reply", "replies");

    /** For a source type (source.type). */
    public static Terms of(String sourceType) {
        String t = sourceType == null ? "" : sourceType.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "confluence", "sharepoint" -> PAGES;
            case "jive" -> POSTS;
            default -> QUESTIONS;
        };
    }

    /** For a source adapter (by its kind, so this package needs no source classes). */
    public static Terms ofAdapter(Object adapter) {
        String name = adapter == null ? "" : adapter.getClass().getSimpleName();
        return name.startsWith("Confluence") || name.startsWith("SharePoint") ? PAGES : name.startsWith("Jive") ? POSTS : QUESTIONS;
    }

    /** For a stored system id (confluence, jive, sharepoint by default; a site name for Stack Exchange). */
    public static Terms ofSystem(String systemId) {
        String s = systemId == null ? "" : systemId.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("confluence") || s.startsWith("sharepoint") ? PAGES : s.startsWith("jive") ? POSTS : QUESTIONS;
    }

    /** A readable name for a stored system id when nothing better is known ("stackoverflow" -> "Stack Overflow"). */
    public static String sourceName(String systemId) {
        String s = systemId == null ? "" : systemId.strip();
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "confluence" -> "Confluence";
            case "jive" -> "Jive";
            case "sharepoint" -> "SharePoint";
            case "fixture" -> "Demo data";
            case "internal-qa" -> "Internal Q&A site";
            case "stackoverflow" -> "Stack Overflow";
            case "serverfault" -> "Server Fault";
            case "superuser" -> "Super User";
            case "askubuntu" -> "Ask Ubuntu";
            case "" -> "Unknown source";
            default -> Character.toUpperCase(s.charAt(0)) + s.substring(1);
        };
    }

    /** "1 page", "3 pages". */
    public String count(long n) {
        return n + " " + (n == 1 ? item : items);
    }

    public String countAnswers(long n) {
        return n + " " + (n == 1 ? answer : answers);
    }
}
