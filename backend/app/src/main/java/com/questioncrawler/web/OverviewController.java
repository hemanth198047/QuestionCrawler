package com.questioncrawler.web;

import com.questioncrawler.core.service.Overview;
import com.questioncrawler.core.service.Views;
import com.questioncrawler.core.service.Workspace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Everything the home screen needs, plus the raw store status. */
@RestController
@RequestMapping("/api")
public class OverviewController {

    private final Workspace workspace;

    public OverviewController(Workspace workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/overview")
    public Overview overview() {
        return workspace.overview();
    }

    @GetMapping("/status")
    public Views.StatusReport status() {
        return workspace.kb().status();
    }
}
