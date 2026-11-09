package com.questioncrawler.web;

import com.questioncrawler.core.search.AgeFilter;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.service.Views;
import com.questioncrawler.core.service.Workspace;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read access to the knowledge store plus manual topic overrides. Searching never triggers a sync. */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private static final int MAX_QUERY_LENGTH = 500;

    public record OverrideRequest(List<String> categories, String note) {}

    /** confirm must be true; the reserved tag "all" is refused by the core. */
    public record DeleteByTagRequest(String tag, boolean confirm) {}

    private final Workspace workspace;

    public KnowledgeController(Workspace workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "category", required = false) List<String> categories,
            @RequestParam(name = "top", defaultValue = "5") int top,
            @RequestParam(name = "maxChars", defaultValue = "0") int maxChars,
            @RequestParam(name = "maxQuestionAge", required = false) String maxQuestionAge,
            @RequestParam(name = "minQuestionAge", required = false) String minQuestionAge,
            @RequestParam(name = "maxAnswerAge", required = false) String maxAnswerAge,
            @RequestParam(name = "minAnswerAge", required = false) String minAnswerAge) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'q' must not be blank.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH); // a pasted log or stack trace: its beginning is searched
        }
        AgeFilter age = new AgeFilter(maxQuestionAge, minQuestionAge, maxAnswerAge, minAnswerAge);
        return workspace.kb().search(new SearchRequest(query, tags, categories, top, maxChars, 0, age));
    }

    /** The id may be a bare question id or "source:id". */
    @GetMapping("/questions/{id}")
    public Views.ThreadView question(@PathVariable("id") String id) {
        return workspace.kb().show(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No question with id " + id));
    }

    @PutMapping("/questions/{id}/categories")
    public Views.ThreadView setOverride(@PathVariable("id") String id, @RequestBody OverrideRequest request) {
        workspace.kb().setOverride(id, request.categories(), request.note());
        return question(id);
    }

    @DeleteMapping("/questions/{id}/categories")
    public Views.ThreadView clearOverride(@PathVariable("id") String id) {
        workspace.kb().clearOverride(id);
        return question(id);
    }

    /** Topic counts; {@code source} (a stored system id such as confluence or stackoverflow) limits them to one source. */
    @GetMapping("/categories")
    public List<Views.CategorySummary> categories(@RequestParam(name = "source", required = false) String source) {
        return workspace.kb().categories(source);
    }

    @GetMapping("/categories/{id}")
    public Views.CategoryListing category(@PathVariable("id") String id,
                                          @RequestParam(name = "maxQuestionAge", required = false) String maxQuestionAge,
                                          @RequestParam(name = "minQuestionAge", required = false) String minQuestionAge,
                                          @RequestParam(name = "source", required = false) String source) {
        return workspace.kb().category(id, new AgeFilter(maxQuestionAge, minQuestionAge, null, null), source)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown category " + id));
    }

    @PostMapping("/reindex")
    public Views.ReindexResult reindex() {
        return workspace.kb().reindex();
    }

    /**
     * Permanently deletes locally stored questions (and answers) that carry the given tag.
     * Requires {@code confirm: true}. Does not contact the remote site.
     */
    @PostMapping("/store/delete-by-tag")
    public Views.DeleteByTagResult deleteByTag(@RequestBody DeleteByTagRequest request) {
        if (request == null || !request.confirm()) {
            throw new IllegalArgumentException(
                    "Deletion removes stored questions permanently. Re-send with confirm=true to proceed.");
        }
        if (request.tag() == null || request.tag().isBlank()) {
            throw new IllegalArgumentException("A tag is required.");
        }
        return workspace.kb().deleteByTag(request.tag());
    }
}
