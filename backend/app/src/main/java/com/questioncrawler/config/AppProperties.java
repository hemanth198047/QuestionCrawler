package com.questioncrawler.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web-layer settings (prefix "questioncrawler"). Knowledge-base settings (tags, source, storage,
 * taxonomy) live in the shared questioncrawler.properties file that the CLI also uses; these two
 * properties only say where to find it. Never put credentials here.
 *
 * @param configFile  path of questioncrawler.properties (empty = same lookup order as the CLI)
 * @param storageRoot optional override of storage.root
 */
@ConfigurationProperties(prefix = "questioncrawler")
public record AppProperties(Cors cors, String configFile, String storageRoot) {

    public AppProperties {
        if (cors == null) {
            cors = new Cors(List.of());
        }
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
