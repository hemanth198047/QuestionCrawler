package com.questioncrawler.web;

import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.searchai.SearchAiResponse;
import com.questioncrawler.core.searchai.SearchAiService;
import com.questioncrawler.core.searchai.SearchAiStore;
import com.questioncrawler.core.searchai.StoredAnswer;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.searchai.KerberosTicket;
import com.questioncrawler.core.searchai.SearchAiBrowserTransport;
import com.questioncrawler.core.searchai.SearchAiException;
import com.questioncrawler.core.searchai.SearchAiPageSaver;
import com.questioncrawler.core.searchai.SearchAiStatus;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** What the screen shows in the history list: no answer body, so the list stays small. */
    public record HistoryEntry(String id, String kind, String fetchedAt, String query, Map<String, String> params, int httpStatus) {}

    public record SettingsRequest(String baseUri) {}

    private final Workspace workspace;
    private CoreConfig serviceFor;
    private SearchAiService service;

    public SearchAiController(Workspace workspace) {
        this.workspace = workspace;
    }

    /** One call for the screen: address set? ticket? does search-ai let us in? Never fails; problems come back as text. */
    @GetMapping("/status")
    public SearchAiStatus status() {
        SearchAiService svc = service();
        return SearchAiStatus.check(svc.client().options(), svc.client(), KerberosTicket.probe());
    }

    /**
     * Opens the person's browser on Search-AI so they can sign in on the company login page, then returns the new status.
     * Returns when signed in, when the window is closed, or after the sign-in time limit (source.browser.timeoutSeconds).
     */
    @PostMapping("/sign-in")
    public SearchAiStatus signIn() {
        SearchAiService svc = service();
        try {
            svc.client().useBrowser();
            svc.client().whoami();
        } catch (SearchAiException e) {
            return new SearchAiStatus(true, svc.client().options().baseUri(), false, KerberosTicket.probe().state(), null, e.getMessage(),
                    "The browser sign-in did not finish. Press \"Sign in with my browser\" to try again (this needs a desktop session with Edge or Chrome).", true);
        }
        return status();
    }

    /** Saves the search-ai address once (an address without a path gets /searchapi added). Other settings are left alone. */
    @PutMapping("/settings")
    public SearchAiStatus saveSettings(@RequestBody SettingsRequest request) {
        String url = request == null || request.baseUri() == null ? "" : request.baseUri().strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a valid address. It looks like https://search-ai.yourcompany.com/searchapi");
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https") || uri.getHost() == null) {
            throw new IllegalArgumentException("The address must start with https:// (it looks like https://search-ai.yourcompany.com/searchapi).");
        }
        if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
            url = url + "/searchapi";
        }
        var saved = workspace.saveConfig(Map.of("searchai.baseUri", url));
        if (!saved.saved()) {
            throw new IllegalArgumentException(saved.issues().stream().filter(i -> "error".equals(i.level())).map(i -> i.message()).findFirst()
                    .orElse("The address could not be saved."));
        }
        return status();
    }

    /**
     * Reads the pages the chosen results lead to and stores their content, so the normal search finds it later. Only on demand:
     * nothing is read until this is called. A page that needs the company login opens the browser window once.
     */
    @PostMapping("/pages")
    public List<SearchAiPageSaver.Saved> savePages(@RequestBody List<SearchAiPageSaver.Request> pages) {
        CoreConfig config = workspace.config();
        var net = config.shortLinks() == null ? com.questioncrawler.core.source.ShortLinks.Options.OFF : config.shortLinks();
        var browser = config.confluence() == null ? com.questioncrawler.core.source.ConfluenceOptions.Browser.DEFAULT : config.confluence().browser();
        var reader = com.questioncrawler.core.source.WebPageReader.create(net, browser, true, System::getenv);
        reader.newRun();
        return new SearchAiPageSaver(workspace.kb(), reader::read).save(pages);
    }

    /** Pages saved so far (the screen marks these results as saved). */
    @GetMapping("/pages")
    public List<SearchAiPageSaver.SavedPage> savedPages() {
        return new SearchAiPageSaver(workspace.kb(), link -> {
            throw new com.questioncrawler.core.source.SourceException(com.questioncrawler.core.source.SourceException.Kind.FATAL, "not reading");
        }).saved();
    }

    @GetMapping("/history")
    public List<HistoryEntry> history(@RequestParam(name = "top", defaultValue = "30") int top) {
        return service().store().list(null, Math.min(Math.max(top, 1), 100)).stream()
                .map(a -> new HistoryEntry(a.id(), a.kind(), a.fetchedAt(), a.params().get("query"), a.params(), a.httpStatus())).toList();
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
            SearchAiClient client = new SearchAiClient(options);
            if (!"curl".equals(options.transport())) {
                client.withBrowser(new SearchAiBrowserTransport(options, System::getenv));
            }
            service = new SearchAiService(client, new SearchAiStore(options.storeDir()));
            serviceFor = config;
        }
        return service;
    }
}
