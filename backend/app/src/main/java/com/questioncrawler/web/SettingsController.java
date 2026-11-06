package com.questioncrawler.web;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.ConnectionReport;
import com.questioncrawler.core.service.Workspace;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
