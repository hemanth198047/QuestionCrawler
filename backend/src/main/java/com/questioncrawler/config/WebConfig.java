package com.questioncrawler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS is opt-in: only origins listed in configuration are allowed. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
