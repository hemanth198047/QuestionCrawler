package com.questioncrawler.web;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final String applicationName;

    public HealthController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    public record HealthResponse(String status, String application, String javaVersion, Instant timestamp) {}

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", applicationName, Runtime.version().toString(), Instant.now());
    }
}
