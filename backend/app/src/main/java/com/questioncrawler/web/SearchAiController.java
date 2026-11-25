package com.questioncrawler.web;

import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.searchai.SearchAiResponse;
import com.questioncrawler.core.searchai.SearchAiService;
import com.questioncrawler.core.searchai.SearchAiStore;
import com.questioncrawler.core.searchai.StoredAnswer;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.Workspace;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Queries the search-ai REST API with the server's Kerberos ticket and stores every answer under
 * {@code <storage-root>/searchai}. The browser never talks to search-ai and no credential passes through this API.
 */
@RestController
@RequestMapping("/api/searchai")
public class SearchAiController {

    private final Workspace workspace;
    private CoreConfig serviceFor;
    private SearchAiService service;

    public SearchAiController(Workspace workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/whoami")
    public SearchAiResponse whoami() {
        return service().whoami();
    }

    @PostMapping("/search")
    public List<StoredAnswer> search(
            @RequestParam("query") String query,
            @RequestParam(name = "rows", required = false) Integer rows,
            @RequestParam(name = "start", required = false) Integer start,
            @RequestParam(name = "useCase", required = false) String useCase,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "pages", defaultValue = "1") int pages) {
        return service().search(query, rows, start, useCase, sortBy, pages);
    }

    @PostMapping("/answer")
    public StoredAnswer answer(@RequestParam("query") String query, @RequestParam(name = "useCase", required = false) String useCase) {
        return service().answer(query, useCase);
    }

    @PostMapping("/summary")
    public StoredAnswer summary(@RequestParam("query") String query, @RequestParam("chunkId") String chunkId) {
        return service().summary(query, chunkId);
    }

    @PostMapping("/usecases")
    public StoredAnswer useCases() {
        return service().useCases();
    }

    @GetMapping("/stored")
    public List<StoredAnswer> stored(@RequestParam(name = "kind", required = false) String kind,
                                     @RequestParam(name = "top", defaultValue = "20") int top) {
        return service().store().list(kind, Math.min(Math.max(top, 1), 200));
    }

    @GetMapping("/stored/{kind}/{name}")
    public StoredAnswer storedOne(@PathVariable("kind") String kind, @PathVariable("name") String name) {
        return service().store().read(kind + "/" + name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored answer " + kind + "/" + name));
    }

    /** One client per loaded configuration, so the "whoami once" session state survives between requests. */
    private synchronized SearchAiService service() {
        CoreConfig config = workspace.config();
        if (service == null || serviceFor != config) {
            SearchAiOptions options = SearchAiOptions.load(config.configFile(), config.storageRoot());
            service = new SearchAiService(new SearchAiClient(options), new SearchAiStore(options.storeDir()));
            serviceFor = config;
        }
        return service;
    }
}
