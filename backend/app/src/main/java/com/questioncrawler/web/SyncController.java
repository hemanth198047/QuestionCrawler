package com.questioncrawler.web;

import com.questioncrawler.core.service.SyncRunner;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncReport;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Synchronization is always an explicit operation; nothing here is triggered by a search. */
@RestController
@RequestMapping("/api")
public class SyncController {

    /** All fields optional: defaults come from the saved settings. maxQuestionAge e.g. "30d" limits the time window. */
    public record SyncRequest(List<String> tags, String tagMode, Boolean partialTags, Boolean full, String maxQuestionAge) {}

    private final Workspace workspace;

    public SyncController(Workspace workspace) {
        this.workspace = workspace;
    }

    @PostMapping("/sync")
    public ResponseEntity<?> start(@RequestBody(required = false) SyncRequest request,
                                   @RequestParam(name = "wait", defaultValue = "false") boolean wait) {
        SyncRequest r = request == null ? new SyncRequest(null, null, null, null, null) : request;
        TagMode tagMode = r.tagMode() == null || r.tagMode().isBlank() ? null : TagMode.valueOf(r.tagMode().toUpperCase(Locale.ROOT));
        String window = r.maxQuestionAge() == null || r.maxQuestionAge().isBlank() ? null : r.maxQuestionAge();
        SyncOptions options = workspace.config().syncOptions(r.tags(), tagMode, r.partialTags(),
                Boolean.TRUE.equals(r.full()) ? SyncMode.FULL : SyncMode.INCREMENTAL, window);
        if (options.tags().isEmpty()) {
            throw new IllegalArgumentException("No tags configured: add at least one tag in Settings.");
        }
        SyncRunner runner = workspace.runner();
        if (wait) {
            SyncReport report = runner.runBlocking(options);
            return ResponseEntity.ok(report);
        }
        if (!runner.start(options)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(runner.state());
        }
        return ResponseEntity.accepted().body(runner.state());
    }

    @GetMapping("/sync/status")
    public SyncRunner.State status() {
        return workspace.runner().state();
    }

    @GetMapping("/sync/reports")
    public List<SyncReport> reports(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        var store = workspace.kb().store();
        return store.listReportIds().stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(id -> store.readReport(id, SyncReport.class))
                .flatMap(java.util.Optional::stream)
                .toList();
    }
}
