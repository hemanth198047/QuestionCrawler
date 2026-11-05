package com.questioncrawler.config;

import com.questioncrawler.core.service.Workspace;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the framework-free core module into Spring. Same configuration file and logic as the CLI. */
@Configuration
@EnableScheduling
public class CoreConfiguration {

    @Bean
    public Workspace workspace(AppProperties properties) {
        Path file = properties.configFile() == null || properties.configFile().isBlank() ? null : Path.of(properties.configFile());
        Map<String, String> overrides = new HashMap<>();
        if (properties.storageRoot() != null && !properties.storageRoot().isBlank()) {
            overrides.put("storage.root", properties.storageRoot());
        }
        return new Workspace(file, overrides);
    }
}
