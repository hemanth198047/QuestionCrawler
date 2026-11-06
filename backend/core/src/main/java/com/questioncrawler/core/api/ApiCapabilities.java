package com.questioncrawler.core.api;

import com.questioncrawler.core.api.ApiCatalog.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which of the API's endpoints serve which purpose of this application, and which optional features the API supports.
 * Decided from the published documentation, so a missing feature is reported instead of silently failing later.
 */
public record ApiCapabilities(List<Capability> capabilities, boolean ready, List<String> problems,
                              boolean taggedFilter, boolean fromDate, boolean minActivity, boolean customFilter, boolean createFilter,
                              int otherEndpoints, List<Integer> pageSizes, ParamMap params) {

    /** id: stable key; found: the documentation lists a matching endpoint. */
    public record Capability(String id, String label, String purpose, boolean required, boolean found,
                             String method, String path, List<String> parameters) {}

    private record Want(String id, String label, String purpose, boolean required, String method, String suffix) {}

    private static final List<Want> WANTS = List.of(
            new Want("questions", "List questions", "Finds the questions to collect (by tag, date and activity).", true, "GET", "/questions"),
            new Want("answers", "Answers of questions", "Downloads every answer of a batch of questions in one call.", true, "GET", "/questions/{ids}/answers"),
            new Want("question", "One question", "Re-checks a single question (used to notice deletions).", false, "GET", "/questions/{ids}"),
            new Want("questionComments", "Comments on questions", "Optional extra context under each question.", false, "GET", "/questions/{ids}/comments"),
            new Want("answerComments", "Comments on answers", "Optional extra context under each answer.", false, "GET", "/answers/{ids}/comments"),
            new Want("tags", "List tags", "Lets you pick tags from the ones that exist.", false, "GET", "/tags"),
            new Want("tagInfo", "Tag details", "Shows how many questions a tag has.", false, "GET", "/tags/{tags}/info"),
            new Want("filterCreate", "Create a response filter", "Asks the API to include question and answer text when it does not by default.", false, "GET", "/filters/create"),
            new Want("info", "Site information", "Checks the connection and shows the request allowance.", false, "GET", "/info"));

    public static ApiCapabilities analyze(ApiCatalog catalog) {
        List<Capability> caps = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> used = new ArrayList<>();
        for (Want w : WANTS) {
            Optional<Operation> op = catalog.find(w.method(), w.suffix());
            if (op.isEmpty() && w.id().equals("filterCreate")) {
                op = catalog.find("POST", w.suffix());
            }
            op.ifPresent(o -> used.add(o.method() + " " + o.path()));
            caps.add(new Capability(w.id(), w.label(), w.purpose(), w.required(), op.isPresent(),
                    op.map(Operation::method).orElse(null), op.map(Operation::path).orElse(null),
                    op.map(o -> o.parameters().stream().map(p -> p.name()).toList()).orElse(List.of())));
            if (w.required() && op.isEmpty()) {
                problems.add("The documentation lists no '" + w.method() + " " + w.suffix() + "' endpoint, which is needed to " + w.purpose().toLowerCase());
            }
        }
        Optional<Operation> questions = catalog.find("GET", "/questions");
        ParamMap pm = questions.map(o -> ParamMap.from(o.parameters())).orElse(ParamMap.defaults());
        boolean tagged = questions.isPresent() && pm.tag() != null;
        boolean fromdate = questions.isPresent() && pm.from() != null;
        boolean min = questions.isPresent() && pm.min() != null;
        boolean filter = questions.isPresent() && pm.filter() != null;
        boolean create = catalog.find("GET", "/filters/create").isPresent() || catalog.find("POST", "/filters/create").isPresent();
        if (questions.isPresent() && !tagged) {
            problems.add("'/questions' has no tag parameter: questions will be filtered by tag after download, which is slower.");
        }
        int others = (int) catalog.operations().stream().filter(o -> !used.contains(o.method() + " " + o.path())).count();
        boolean ready = caps.stream().filter(Capability::required).allMatch(Capability::found);
        List<Integer> sizes = new ArrayList<>();
        questions.flatMap(o -> o.parameters().stream().filter(p -> p.name().equals(pm.size())).findFirst()).ifPresent(p -> {
            p.allowed().forEach(a -> { try { sizes.add(Integer.parseInt(a.strip())); } catch (NumberFormatException ignored) { /* not a number */ } });
            if (sizes.isEmpty()) { // some documents only mention the sizes in the description
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{1,4})\\b").matcher(p.description() == null ? "" : p.description());
                List<Integer> found = new ArrayList<>();
                while (m.find()) { found.add(Integer.parseInt(m.group(1))); }
                if (found.size() >= 3) { sizes.addAll(found); }
            }
        });
        java.util.Collections.sort(sizes);
        return new ApiCapabilities(caps, ready, problems, tagged, fromdate, min, filter, create, others, List.copyOf(sizes), pm);
    }
}
