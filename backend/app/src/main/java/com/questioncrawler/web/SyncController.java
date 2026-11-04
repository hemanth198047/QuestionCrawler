package com.questioncrawler.web;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.service.Views;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.sync.SyncJobService;
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

    private final KnowledgeBase knowledgeBase;
    private final CoreConfig config;
    private final SyncJobService jobs;

    public SyncController(KnowledgeBase knowledgeBase, CoreConfig config, SyncJobService jobs) {
        this.knowledgeBase = knowledgeBase;
        this.config = config;
        this.jobs = jobs;
    }

    /** All fields optional: defaults come from the configuration file. */
    /** maxQuestionAge (for example "30d") limits collection to questions created within that age. */
    public record SyncRequest(List<String> tags, String tagMode, Boolean partialTags, Boolean full, String maxQuestionAge) {}

    @PostMapping("/sync")
    public ResponseEntity<?> start(@RequestBody(required = false) SyncRequest request,
                                   @RequestParam(name = "wait", defaultValue = "false") boolean wait) {
        SyncRequest r = request == null ? new SyncRequest(null, null, null, null, null) : request;
        TagMode tagMode = r.tagMode() == null ? null : TagMode.valueOf(r.tagMode().toUpperCase(Locale.ROOT));
        SyncOptions options = config.syncOptions(r.tags(), tagMode, r.partialTags(),
                Boolean.TRUE.equals(r.full()) ? SyncMode.FULL : SyncMode.INCREMENTAL, r.maxQuestionAge());
        if (options.tags().isEmpty()) {
            throw new IllegalArgumentException("No tags configured: pass tags in the request or set sync.tags in the configuration.");
        }
        if (wait) {
            SyncReport report = jobs.runBlocking(options);
            return ResponseEntity.ok(report);
        }
        if (!jobs.start(options)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(jobs.state());
        }
        return ResponseEntity.accepted().body(jobs.state());
    }

    @GetMapping("/sync/status")
    public SyncJobService.State syncStatus() {
        return jobs.state();
    }

    @GetMapping("/sync/reports")
    public List<SyncReport> reports(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return knowledgeBase.store().listReportIds().stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(id -> knowledgeBase.store().readReport(id, SyncReport.class))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    @PostMapping("/reindex")
    public Views.ReindexResult reindex() {
        return knowledgeBase.reindex();
    }
}
