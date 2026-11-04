package com.questioncrawler.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed application settings (prefix "questioncrawler"). Values come from
 * application.yml or environment variables; never put credentials in the YAML.
 */
@ConfigurationProperties(prefix = "questioncrawler")
public record AppProperties(Cors cors) {

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
