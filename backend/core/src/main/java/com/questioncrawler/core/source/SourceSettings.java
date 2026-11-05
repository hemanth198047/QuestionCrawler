package com.questioncrawler.core.source;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Connection settings for a live source. Credentials are never stored here: {@code tokenEnv} and
 * {@code apiKeyEnv} are the NAMES of environment variables.
 *
 * @param site      Stack Exchange site parameter (for example "stackoverflow"); unused by other sources
 * @param apiKeyEnv optional environment variable holding a Stack Exchange application key (raises the quota)
 * @param proxy     company web proxy (NONE = use HTTPS_PROXY from the environment, if any)
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
        boolean allowInsecureHttp,
        String site,
        String apiKeyEnv,
        ProxyConfig proxy) {

    public SourceSettings {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        allowedPathPrefixes = allowedPathPrefixes == null || allowedPathPrefixes.isEmpty() ? List.of("/") : List.copyOf(allowedPathPrefixes);
        authMethod = authMethod == null ? "none" : authMethod;
        authHeaderName = authHeaderName == null || authHeaderName.isBlank() ? "Authorization" : authHeaderName;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        proxy = proxy == null ? ProxyConfig.NONE : proxy;
    }

    public SourceSettings(String systemId, String baseUrl, List<String> allowedHosts, List<String> allowedPathPrefixes,
                          String authMethod, String tokenEnv, String authHeaderName, String authScheme, Path caCertFile,
                          Duration timeout, boolean allowInsecureHttp, String site, String apiKeyEnv) {
        this(systemId, baseUrl, allowedHosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, site, apiKeyEnv, ProxyConfig.NONE);
    }

    public SourceSettings(String systemId, String baseUrl, List<String> allowedHosts, List<String> allowedPathPrefixes,
                          String authMethod, String tokenEnv, String authHeaderName, String authScheme, Path caCertFile,
                          Duration timeout, boolean allowInsecureHttp) {
        this(systemId, baseUrl, allowedHosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, null, null, ProxyConfig.NONE);
    }
}
