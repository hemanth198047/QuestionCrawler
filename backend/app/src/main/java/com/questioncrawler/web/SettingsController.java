package com.questioncrawler.web;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.ConnectionReport;
import com.questioncrawler.core.service.TagListingJob;
import com.questioncrawler.core.service.Workspace;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings, the connection check, and the editable category rules. */
@RestController
@RequestMapping("/api")
public class SettingsController {

    public record ValuesRequest(Map<String, String> values) {
        public Map<String, String> safe() {
            return values == null ? Map.of() : values;
        }
    }

    public record TopicsRequest(Integer k) {}

    public record ContentRequest(String content) {}

    public record TagSearchRequest(Map<String, String> values, String q) {}

    public record ProbeRequest(Map<String, String> values, String path) {}

    public record TryRequest(Taxonomy taxonomy, String title, String body, List<String> tags) {}

    private final Workspace workspace;

    public SettingsController(Workspace workspace) {
        this.workspace = workspace;
    }

    // ---- settings
    @GetMapping("/config")
    public ConfigService.ConfigView config() {
        return workspace.configView();
    }

    @GetMapping("/config/presets")
    public List<ConfigService.Preset> presets() {
        return ConfigService.presets();
    }

    @PostMapping("/config/validate")
    public Map<String, Object> validate(@RequestBody ValuesRequest request) {
        return Map.of("issues", workspace.validate(request.safe()), "preview", workspace.previewConfig(request.safe()));
    }

    @PutMapping("/config")
    public ConfigService.SaveResult save(@RequestBody ValuesRequest request) {
        return workspace.saveConfig(request.safe());
    }

    // ---- the site's API documentation (Swagger)
    @PostMapping("/api-docs/discover")
    public Workspace.ApiDocsResult discoverApi(@RequestBody(required = false) ValuesRequest request) {
        return workspace.discoverApi(request == null ? Map.of() : request.safe());
    }

    /** Stores a copy of the documentation saved from a signed-in browser; the returned path goes into api.swaggerFile. */
    @PostMapping("/api-docs/file")
    public Workspace.SavedDocs saveApiDocs(@RequestBody ContentRequest request) {
        return workspace.saveApiDocs(request == null || request.content() == null ? "" : request.content());
    }

    /** One real API call, shown raw (key hidden): finds out what the site really answers. */
    @PostMapping("/api-docs/probe")
    public Workspace.ApiProbe probeApi(@RequestBody(required = false) ProbeRequest request) {
        return request == null ? workspace.probeApi(Map.of(), null) : workspace.probeApi(request.values() == null ? Map.of() : request.values(), request.path());
    }

    /** Finds tags on the site by a few letters (the site's own spelling and counts). */
    @PostMapping("/tags/search")
    public Workspace.TagSearch searchTags(@RequestBody(required = false) TagSearchRequest request) {
        return request == null ? workspace.searchTags(Map.of(), null) : workspace.searchTags(request.values() == null ? Map.of() : request.values(), request.q());
    }

    /** Starts reading every tag of the site in the background (a site can have 100,000+ tags: it takes a while). */
    @PostMapping("/tags/listing")
    public TagListingJob.State startTagListing(@RequestBody(required = false) ValuesRequest request) {
        return workspace.startTagListing(request == null ? Map.of() : request.safe());
    }

    /** Progress, and the tags read since position {@code from}. */
    @GetMapping("/tags/listing")
    public TagListingJob.Chunk tagListing(@RequestParam(name = "from", defaultValue = "0") int from) {
        return workspace.tagListing(from);
    }

    @PostMapping("/tags/listing/cancel")
    public void cancelTagListing() {
        workspace.cancelTagListing();
    }

    /** Asks for a first page of questions the way a collection does and shows what came back and what was understood. */
    @PostMapping("/connection/collect-test")
    public Workspace.CollectionTest testCollection(@RequestBody(required = false) ValuesRequest request) {
        return workspace.testCollection(request == null ? Map.of() : request.safe());
    }

    // ---- connection check
    @PostMapping("/connection/check")
    public ConnectionReport check(@RequestBody(required = false) ValuesRequest request) {
        return workspace.checkConnection(request == null ? Map.of() : request.safe());
    }

    @GetMapping("/connection/last")
    public ConnectionReport last() {
        return workspace.lastConnection().orElse(null);
    }

    // ---- category rules
    @GetMapping("/taxonomy")
    public Workspace.TaxonomyView taxonomy() {
        return workspace.taxonomyView();
    }

    @PutMapping("/taxonomy")
    public Workspace.TaxonomyView saveTaxonomy(@RequestBody Taxonomy taxonomy) {
        return workspace.saveTaxonomy(taxonomy);
    }

    @PostMapping("/taxonomy/reset")
    public Workspace.TaxonomyView resetTaxonomy() {
        return workspace.resetTaxonomy();
    }

    /** Proposes topics found in the collected questions; apply them with PUT /taxonomy and POST /reindex. */
    @PostMapping("/topics/discover")
    public com.questioncrawler.core.classification.TopicDiscovery.Proposal discoverTopics(@RequestBody(required = false) TopicsRequest request) {
        return workspace.discoverTopics(request == null || request.k() == null ? 0 : request.k());
    }

    @PostMapping("/taxonomy/try")
    public Workspace.TryResult tryClassify(@RequestBody TryRequest request) {
        return workspace.tryClassify(request.taxonomy(), request.title(), request.body(), request.tags());
    }
}
