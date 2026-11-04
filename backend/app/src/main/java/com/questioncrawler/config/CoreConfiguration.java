package com.questioncrawler.config;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
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
    public CoreConfig coreConfig(AppProperties properties) {
        Path file = CoreConfig.locate(properties.configFile());
        Map<String, String> overrides = new HashMap<>();
        if (properties.storageRoot() != null && !properties.storageRoot().isBlank()) {
            overrides.put("storage.root", properties.storageRoot());
        }
        return CoreConfig.load(file, overrides);
    }

    @Bean
    public KnowledgeBase knowledgeBase(CoreConfig config) {
        KnowledgeBase knowledgeBase = new KnowledgeBase(config);
        knowledgeBase.init();
        return knowledgeBase;
    }
}
