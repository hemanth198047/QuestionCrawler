package com.questioncrawler.core.source;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Connection settings for a live source. Credentials are never stored here: {@code tokenEnv} and
 * {@code apiKeyEnv} are the NAMES of environment variables.
 *
 * @param site         Stack Exchange site parameter (for example "stackoverflow"); unused by other sources
 * @param apiKeyEnv    optional environment variable holding a Stack Exchange application key (raises the quota)
 * @param proxy        company web proxy (NONE = use HTTPS_PROXY from the environment, if any)
 * @param authUser     non-secret username/email for {@code basic-env} auth (for example Confluence Cloud)
 * @param authUserEnv  optional env var name holding the username/email when not set as {@code authUser}
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
        ProxyConfig proxy,
        ApiOptions api,
        String authUser,
        String authUserEnv) {

    public SourceSettings {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        allowedPathPrefixes = allowedPathPrefixes == null || allowedPathPrefixes.isEmpty() ? List.of("/") : List.copyOf(allowedPathPrefixes);
        authMethod = authMethod == null ? "none" : authMethod;
        authHeaderName = authHeaderName == null || authHeaderName.isBlank() ? "Authorization" : authHeaderName;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        proxy = proxy == null ? ProxyConfig.NONE : proxy;
        api = api == null ? ApiOptions.DEFAULT : api;
        authUser = authUser == null || authUser.isBlank() ? null : authUser.strip();
        authUserEnv = authUserEnv == null || authUserEnv.isBlank() ? null : authUserEnv.strip();
    }

    /** A copy that talks to another API address (its host is added to the allowed hosts). */
    public SourceSettings withBase(String newBase, ApiOptions newApi) {
        List<String> hosts = new java.util.ArrayList<>(allowedHosts);
        try {
            String host = java.net.URI.create(newBase).getHost();
            if (host != null && hosts.stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
                hosts.add(host);
            }
        } catch (IllegalArgumentException ignored) {
            // an unusable address is reported when it is used
        }
        return new SourceSettings(systemId, newBase, hosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, site, apiKeyEnv, proxy, newApi == null ? api : newApi, authUser, authUserEnv);
    }

    public SourceSettings(String systemId, String baseUrl, List<String> allowedHosts, List<String> allowedPathPrefixes,
                          String authMethod, String tokenEnv, String authHeaderName, String authScheme, Path caCertFile,
                          Duration timeout, boolean allowInsecureHttp, String site, String apiKeyEnv, ProxyConfig proxy,
                          ApiOptions api) {
        this(systemId, baseUrl, allowedHosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, site, apiKeyEnv, proxy, api, null, null);
    }

    public SourceSettings(String systemId, String baseUrl, List<String> allowedHosts, List<String> allowedPathPrefixes,
                          String authMethod, String tokenEnv, String authHeaderName, String authScheme, Path caCertFile,
                          Duration timeout, boolean allowInsecureHttp, String site, String apiKeyEnv, ProxyConfig proxy) {
        this(systemId, baseUrl, allowedHosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, site, apiKeyEnv, proxy, ApiOptions.DEFAULT, null, null);
    }

    public SourceSettings(String systemId, String baseUrl, List<String> allowedHosts, List<String> allowedPathPrefixes,
                          String authMethod, String tokenEnv, String authHeaderName, String authScheme, Path caCertFile,
                          Duration timeout, boolean allowInsecureHttp, String site, String apiKeyEnv) {
        this(systemId, baseUrl, allowedHosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, site, apiKeyEnv, ProxyConfig.NONE, ApiOptions.DEFAULT, null, null);
    }

    public SourceSettings(String systemId, String baseUrl, List<String> allowedHosts, List<String> allowedPathPrefixes,
                          String authMethod, String tokenEnv, String authHeaderName, String authScheme, Path caCertFile,
                          Duration timeout, boolean allowInsecureHttp) {
        this(systemId, baseUrl, allowedHosts, allowedPathPrefixes, authMethod, tokenEnv, authHeaderName, authScheme,
                caCertFile, timeout, allowInsecureHttp, null, null, ProxyConfig.NONE, ApiOptions.DEFAULT, null, null);
    }
}
