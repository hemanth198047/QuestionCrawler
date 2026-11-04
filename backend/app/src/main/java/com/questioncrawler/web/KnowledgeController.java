package com.questioncrawler.web;

import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.Views;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read access to the knowledge store plus manual category overrides. Searching never triggers a sync. */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private static final int MAX_QUERY_LENGTH = 500;

    private final KnowledgeBase knowledgeBase;

    public KnowledgeController(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "category", required = false) List<String> categories,
            @RequestParam(name = "top", defaultValue = "5") int top,
            @RequestParam(name = "maxChars", defaultValue = "0") int maxChars) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'q' must not be blank.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Query is longer than " + MAX_QUERY_LENGTH + " characters.");
        }
        return knowledgeBase.search(new SearchRequest(query, tags, categories, top, maxChars, 0));
    }

    /** The id may be a bare question id or "source:id". */
    @GetMapping("/questions/{id}")
    public Views.ThreadView question(@PathVariable("id") String id) {
        return knowledgeBase.show(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No question with id " + id));
    }

    @GetMapping("/categories")
    public List<Views.CategorySummary> categories() {
        return knowledgeBase.categories();
    }

    @GetMapping("/categories/{id}")
    public Views.CategoryListing category(@PathVariable("id") String id) {
        return knowledgeBase.category(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown category " + id));
    }

    @GetMapping("/status")
    public Views.StatusReport status() {
        return knowledgeBase.status();
    }

    public record OverrideRequest(List<String> categories, String note) {}

    @PutMapping("/questions/{id}/categories")
    public Views.ThreadView setOverride(@PathVariable("id") String id, @RequestBody OverrideRequest request) {
        knowledgeBase.setOverride(id, request.categories(), request.note());
        return question(id);
    }

    @DeleteMapping("/questions/{id}/categories")
    public Views.ThreadView clearOverride(@PathVariable("id") String id) {
        knowledgeBase.clearOverride(id);
        return question(id);
    }
}
