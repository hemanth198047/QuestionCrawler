package com.questioncrawler.core.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a direct answer from the best-matching passages WITHOUT an AI model: it picks the sentence(s), list, table or
 * code block that best answer the question and quotes them word for word, with the page and section they came from.
 *
 * <p>How a piece is chosen: how many of the question's words it contains (stems and synonyms count), how relevant its
 * whole passage was, and what kind of question it is ("how do I" prefers steps and lists, "what is" prefers a defining
 * sentence, "when / how long / how many" prefers numbers, "who" prefers contacts, "where" prefers links and locations,
 * "why" prefers reasons). Nothing is rephrased or invented; below a confidence floor it says no page clearly answers.
 */
public final class AnswerComposer {

    /** The direct answer shown above the passages. {@code status} is FOUND or NOT_FOUND. */
    public record DirectAnswer(String status, String confidence, String intent, List<Point> points, String note) {}

    /** One quoted piece of the answer and exactly where it comes from. */
    public record Point(String text, String kind, int evidenceRank, String questionId, String answerId, String title,
                        String section, String sourceUrl, String sectionUrl, double match) {}

    /** A passage to choose from: its evidence and its full text (the evidence passage may be trimmed). */
    public record Candidate(Evidence evidence, String fullText) {}

    enum Intent { HOW, WHAT, WHEN, WHO, WHERE, WHY, GENERAL }

    private enum Kind { SENTENCE, LIST, TABLE, CODE }

    private record Unit(Kind kind, String text, int block, int order, boolean firstInSection) {}

    private record Scored(Candidate candidate, List<Unit> units, int index, double coverage, double score) {}

    static final double FLOOR = 0.40;
    private static final int MAX_ANSWER_CHARS = 900;
    private static final int MAX_POINTS = 2;

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}0-9\"'(\\[*_`])");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*+•]|\\d{1,3}[.)])\\s+.*");
    private static final Pattern ORDERED_LINE = Pattern.compile("^\\s*\\d{1,3}[.)]\\s+.*");
    private static final Pattern DEFINES = Pattern.compile("\\b(is|are|means|refers to|stands for|is defined as|is used to|describes)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d");
    private static final Pattern CONTACT = Pattern.compile("@|\\b(contact|owner|owned by|team|responsible|reach out|support|mailbox|channel|slack|teams)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION = Pattern.compile("https?://|\\]\\(|\\b(located|found (?:at|in|under)|go to|navigate|open|path|folder|menu|page)\\b|[/\\\\][\\w.-]+[/\\\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON = Pattern.compile("\\b(because|so that|due to|reason|in order to|this (?:prevents|ensures|avoids)|otherwise)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPERATIVE = Pattern.compile("^\\s*(?:\\d+[.)]\\s*)?(go|click|open|run|select|set|enter|type|choose|navigate|install|enable|disable|add|remove|create|restart|log ?in|sign in|request|raise|submit|use|copy|download|configure|update)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> WHEN_WORDS = Set.of("long", "many", "much", "often", "time", "date", "when");
    private static final Pattern PRONOUN_START = Pattern.compile("^(it|this|that|these|those|they|then)\\b", Pattern.CASE_INSENSITIVE);

    private AnswerComposer() {}

    static Intent intent(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT).strip();
        if (q.matches("^(how (do|can|should|would|to|does)|steps?|procedure|process|guide|instructions?)\\b.*")
                || q.matches(".*\\b(how to|how do i|how can i|steps to|set ?up|configure|install|reset|enable|request|apply for|onboard)\\b.*")) {
            return Intent.HOW;
        }
        if (q.matches("^(when|how long|how many|how much|how often|what time|what date|which version)\\b.*")
                || q.matches(".*\\b(retention|deadline|duration|limit|timeout|expiry|expire|sla|frequency|cost|price)\\b.*")) {
            return Intent.WHEN;
        }
        if (q.matches(".*\\b(who|whom|whose)\\b.*") || q.matches(".*\\b(contact|owner|responsible|point of contact|escalate to)\\b.*")) {
            return Intent.WHO;
        }
        if (q.matches(".*\\bwhere\\b.*") || q.matches(".*\\b(link|url|location|where can i find|find the)\\b.*")) {
            return Intent.WHERE;
        }
        if (q.matches("^why\\b.*") || q.matches(".*\\b(reason|purpose)\\b.*")) {
            return Intent.WHY;
        }
        if (q.matches("^(what|which)\\b.*") || q.matches(".*\\b(define|definition|meaning|stand for|explain)\\b.*")) {
            return Intent.WHAT;
        }
        return Intent.GENERAL;
    }

    public static DirectAnswer compose(String question, List<SymbolTokens.Word> words, List<Candidate> candidates) {
        Intent intent = intent(question);
        String intentName = intent.name().toLowerCase(Locale.ROOT);
        if (words.isEmpty() || candidates.isEmpty()) {
            return notFound(intentName, "No page or answer in the collected knowledge matches this question.");
        }
        if (intent == Intent.WHEN) { // "how LONG / how MANY" are question words here, not what the answer must mention
            List<SymbolTokens.Word> topical = words.stream().filter(w -> !WHEN_WORDS.contains(w.whole())).toList();
            words = topical.isEmpty() ? words : topical;
        }
        double topScore = candidates.stream().mapToDouble(c -> c.evidence().score()).max().orElse(1);
        List<Candidate> usable = candidates.stream().filter(c -> c.evidence().flags().isEmpty()).toList(); // suspicious text is never quoted
        List<List<Unit>> unitsOf = new ArrayList<>();
        List<List<String>> tokensOf = new ArrayList<>();
        for (Candidate c : usable) {
            String text = c.fullText() == null || c.fullText().isBlank() ? c.evidence().passage() : c.fullText();
            List<Unit> units = units(text);
            unitsOf.add(units);
            for (Unit u : units) {
                tokensOf.add(SymbolTokens.expand(u.text()));
            }
        }
        double[] weights = weights(words, tokensOf);
        // On a Q&A site the question text describes the problem; its answers hold the solution. When answers of a
        // question are among the candidates, they are preferred over the question's own text (pages are not affected).
        Set<String> answered = new HashSet<>();
        java.util.Map<String, Double> threadBest = new java.util.HashMap<>();
        for (Candidate c : usable) {
            if ("answer".equals(c.evidence().type())) {
                answered.add(c.evidence().questionId());
            }
            threadBest.merge(c.evidence().questionId(), c.evidence().score(), Math::max);
        }
        List<Scored> all = new ArrayList<>();
        int flat = 0;
        for (int ci = 0; ci < usable.size(); ci++) {
            Candidate c = usable.get(ci);
            List<Unit> units = unitsOf.get(ci);
            // An answer is as relevant as the question it answers (its own text rarely repeats the question's words).
            double ownScore = "answer".equals(c.evidence().type())
                    ? Math.max(c.evidence().score(), 0.9 * threadBest.getOrDefault(c.evidence().questionId(), 0.0))
                    : c.evidence().score();
            double relevance = topScore <= 0 ? 0 : Math.min(1, ownScore / topScore);
            // The page title and the headings above a piece are part of what it answers ("Slow connection" › "Switch gateway").
            List<String> headingTokens = SymbolTokens.expand((c.evidence().title() == null ? "" : c.evidence().title()) + "\n"
                    + (c.evidence().section() == null ? "" : c.evidence().section()));
            for (int i = 0; i < units.size(); i++) {
                Unit u = units.get(i);
                List<String> tokens = tokensOf.get(flat++);
                double own = weightedCoverage(words, weights, tokens);
                List<String> withHeading = new ArrayList<>(tokens);
                withHeading.addAll(headingTokens);
                double inContext = weightedCoverage(words, weights, withHeading);
                double cov = Math.max(own, 0.45 * own + 0.55 * inContext);
                boolean isAnswer = "answer".equals(c.evidence().type());
                double score = 0.55 * cov + 0.30 * relevance + bonus(intent, u) - (u.text().length() < 25 ? 0.1 : 0)
                        - (needsSignal(intent) && !hasSignal(intent, u) ? 0.15 : 0)
                        - (!isAnswer && answered.contains(c.evidence().questionId()) ? 0.30 : 0)
                        + (isAnswer && c.evidence().accepted() ? 0.08 : 0);
                all.add(new Scored(c, units, i, cov, score));
            }
        }
        all.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (all.isEmpty() || all.get(0).coverage() < FLOOR) {
            return notFound(intentName, "No page clearly answers this. The closest passages are listed below; check them yourself.");
        }
        Scored best = all.get(0);
        List<Point> points = new ArrayList<>();
        points.add(point(best));
        Set<String> bestTokens = new HashSet<>(SymbolTokens.expand(best.units().get(best.index()).text()));
        for (Scored s : all.subList(1, all.size())) {
            if (points.size() >= MAX_POINTS) {
                break;
            }
            boolean otherSource = !s.candidate().evidence().questionId().equals(best.candidate().evidence().questionId())
                    || !java.util.Objects.equals(s.candidate().evidence().section(), best.candidate().evidence().section());
            if (otherSource && s.score() >= 0.85 * best.score() && s.coverage() >= 0.6
                    && overlap(bestTokens, SymbolTokens.expand(s.units().get(s.index()).text())) < 0.6) {
                points.add(point(s));
            }
        }
        double topEvidenceCoverage = best.candidate().evidence().termCoverage();
        String confidence = best.coverage() >= 0.75 && topEvidenceCoverage >= 0.7 ? "HIGH" : best.coverage() >= 0.55 ? "MEDIUM" : "LOW";
        if (needsSignal(intent) && !hasSignal(intent, best.units().get(best.index()))) {
            confidence = "LOW"; // a "who / when / where" answer without any contact, number or location is only a guess
        }
        String note = "Quoted word for word from your collected pages and answers (no AI model). Check the source before relying on it.";
        return new DirectAnswer("FOUND", confidence, intentName, points, note);
    }

    /**
     * How much each question word counts: words found in few of the candidate pieces ("debug") count more than words
     * found almost everywhere ("logs", "days"), so the piece about the specific thing asked wins a tie.
     */
    static double[] weights(List<SymbolTokens.Word> words, List<List<String>> pieces) {
        double[] w = new double[words.size()];
        int n = Math.max(1, pieces.size());
        for (int i = 0; i < words.size(); i++) {
            List<SymbolTokens.Word> one = List.of(words.get(i));
            int df = 0;
            for (List<String> tokens : pieces) {
                if (SearchService.coverage(one, tokens) >= 0.85) {
                    df++;
                }
            }
            w[i] = PlainEnglish.softForCoverage(words.get(i).whole()) ? 0 : 1.0 + Math.log((double) n / (1 + df));
            w[i] = Math.max(0.5, w[i]) * (PlainEnglish.softForCoverage(words.get(i).whole()) ? 0 : 1);
        }
        return w;
    }

    static double weightedCoverage(List<SymbolTokens.Word> words, double[] weights, List<String> tokens) {
        double total = 0;
        double got = 0;
        for (int i = 0; i < words.size(); i++) {
            total += weights[i];
            if (weights[i] > 0) {
                got += weights[i] * SearchService.coverage(List.of(words.get(i)), tokens);
            }
        }
        return total <= 0 ? SearchService.coverage(words, tokens) : got / total;
    }

    private static DirectAnswer notFound(String intent, String note) {
        return new DirectAnswer("NOT_FOUND", "NONE", intent, List.of(), note);
    }

    /** Who, when and where questions are only really answered by a contact, a number/date or a location. */
    private static boolean needsSignal(Intent intent) {
        return intent == Intent.WHO || intent == Intent.WHEN || intent == Intent.WHERE;
    }

    private static boolean hasSignal(Intent intent, Unit u) {
        return switch (intent) {
            case WHO -> CONTACT.matcher(u.text()).find();
            case WHEN -> NUMBER.matcher(u.text()).find();
            case WHERE -> LOCATION.matcher(u.text()).find();
            default -> true;
        };
    }

    private static double bonus(Intent intent, Unit u) {
        String t = u.text();
        return switch (intent) {
            case HOW -> (u.kind() == Kind.LIST ? (ORDERED_LINE.matcher(t).find() || t.lines().anyMatch(l -> ORDERED_LINE.matcher(l).matches()) ? 0.25 : 0.15) : 0)
                    + (u.kind() == Kind.CODE ? 0.08 : 0)
                    + (u.kind() == Kind.SENTENCE && IMPERATIVE.matcher(t).find() ? 0.10 : 0);
            case WHAT -> (u.kind() == Kind.SENTENCE && DEFINES.matcher(t).find() ? 0.18 : 0) + (u.firstInSection() ? 0.06 : 0)
                    - (u.kind() == Kind.CODE ? 0.10 : 0);
            case WHEN -> (NUMBER.matcher(t).find() ? 0.20 : 0) - (u.kind() == Kind.CODE ? 0.10 : 0);
            case WHO -> CONTACT.matcher(t).find() ? 0.20 : 0;
            case WHERE -> LOCATION.matcher(t).find() ? 0.18 : 0;
            case WHY -> REASON.matcher(t).find() ? 0.20 : 0;
            case GENERAL -> (u.firstInSection() ? 0.04 : 0) - (u.kind() == Kind.CODE ? 0.05 : 0);
        };
    }

    /** The chosen piece plus the context that makes it readable on its own. */
    private static Point point(Scored s) {
        List<Unit> units = s.units();
        Unit u = units.get(s.index());
        StringBuilder text = new StringBuilder();
        Unit before = s.index() > 0 ? units.get(s.index() - 1) : null;
        Unit after = s.index() + 1 < units.size() ? units.get(s.index() + 1) : null;
        boolean introBefore = before != null && before.kind() == Kind.SENTENCE
                && (u.kind() != Kind.SENTENCE && (before.text().strip().endsWith(":") || before.block() == u.block() - 1) && before.text().length() <= 300
                || u.kind() == Kind.SENTENCE && before.block() == u.block() && PRONOUN_START.matcher(u.text().strip()).find());
        if (introBefore) {
            text.append(before.text().strip()).append(u.kind() == Kind.SENTENCE ? " " : "\n\n");
        }
        text.append(u.text().strip());
        if (after != null) {
            boolean listFollows = u.kind() == Kind.SENTENCE && u.text().strip().endsWith(":") && after.kind() != Kind.SENTENCE;
            boolean nextSentence = u.kind() == Kind.SENTENCE && after.kind() == Kind.SENTENCE && after.block() == u.block()
                    && text.length() + after.text().length() < 450;
            if (listFollows) {
                text.append("\n\n").append(after.text().strip());
            } else if (nextSentence) {
                text.append(' ').append(after.text().strip());
            }
        }
        String quoted = trim(text.toString());
        Evidence e = s.candidate().evidence();
        return new Point(quoted, u.kind().name().toLowerCase(Locale.ROOT), e.rank(), e.questionId(), e.answerId(), e.title(),
                e.section(), e.sourceUrl(), e.sectionUrl(), Math.round(s.coverage() * 1000.0) / 1000.0);
    }

    /** Keeps the answer short; a long list or code block is cut at a line boundary. */
    private static String trim(String text) {
        if (text.length() <= MAX_ANSWER_CHARS) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            if (out.length() + line.length() + 1 > MAX_ANSWER_CHARS) {
                break;
            }
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
            }
            out.append(line).append('\n');
        }
        if (out.length() == 0) {
            return text.substring(0, MAX_ANSWER_CHARS) + "…";
        }
        if (inFence) {
            out.append("```\n");
        }
        return out.append("…").toString();
    }

    /** Splits Markdown into sentences, lists, tables and code blocks, in order. */
    static List<Unit> units(String markdown) {
        List<Unit> out = new ArrayList<>();
        List<String> blocks = PageSections.blocks(markdown == null ? "" : markdown);
        int order = 0;
        for (int b = 0; b < blocks.size(); b++) {
            String block = blocks.get(b);
            String first = block.stripLeading();
            if (first.startsWith("```") || first.startsWith("~~~")) {
                out.add(new Unit(Kind.CODE, block, b, order++, b == 0));
            } else if (block.lines().allMatch(l -> l.stripLeading().startsWith("|"))) {
                out.add(new Unit(Kind.TABLE, block, b, order++, b == 0));
            } else if (block.lines().filter(l -> !l.isBlank()).findFirst().map(l -> LIST_LINE.matcher(l).matches()).orElse(false)) {
                out.add(new Unit(Kind.LIST, block, b, order++, b == 0));
            } else {
                String para = block.replaceAll("\\s*\\n\\s*", " ").strip();
                if (para.startsWith(">")) {
                    para = para.replaceFirst("^>\\s*", "");
                }
                boolean firstSentence = true;
                for (String sentence : SENTENCE_END.split(para)) {
                    if (!sentence.isBlank()) {
                        out.add(new Unit(Kind.SENTENCE, sentence.strip(), b, order++, b == 0 && firstSentence));
                        firstSentence = false;
                    }
                }
            }
        }
        return out;
    }

    private static double overlap(Set<String> a, List<String> bList) {
        Set<String> b = new HashSet<>(bList);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
