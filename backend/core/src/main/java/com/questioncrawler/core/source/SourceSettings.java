package com.questioncrawler.core.source;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Connection settings for a live source. Credentials are never stored here: {@code tokenEnv} is the
 * NAME of an environment variable that holds the token.
 */
public record SourceSettings(
        String systemId,
        String baseUrl,
        List<String> allowedHosts,
        List<String> allowedPathPrefixes,
        String authMethod,
        String tokenEnv,
        String authHeaderName,
        String authScheme,
        Path caCertFile,
        Duration timeout,
        boolean allowInsecureHttp) {

    public SourceSettings {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        allowedPathPrefixes = allowedPathPrefixes == null || allowedPathPrefixes.isEmpty() ? List.of("/") : List.copyOf(allowedPathPrefixes);
        authMethod = authMethod == null ? "none" : authMethod;
        authHeaderName = authHeaderName == null || authHeaderName.isBlank() ? "Authorization" : authHeaderName;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}
