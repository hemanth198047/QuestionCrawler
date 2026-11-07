package com.questioncrawler.core.source;

import java.util.Arrays;
import java.util.List;

/**
 * Confluence-only connection options. Kept separate from Stack Exchange / live / fixture settings
 * so Cloud wiki keys never mix with Q&amp;A API fields. Secrets stay as environment-variable names.
 *
 * <p>Jive and SharePoint are separate {@code source.type} values — not extensions of this record.
 */
public record ConfluenceOptions(
        String edition,
        String apiRoot,
        List<String> spaces,
        String ancestorPageId,
        List<String> contentTypes,
        String bodyExpand,
        boolean collectComments,
        int pageSize,
        boolean includeSpaceTag,
        String email,
        String emailEnv,
        String apiVersion,
        List<String> pageIds,
        Tvm tvm) {

    public static final ConfluenceOptions DEFAULT = new ConfluenceOptions(
            "cloud", "", List.of(), "", List.of("page"), "body.storage", false, 25, true, "", "");

    /**
     * Token Vending Machine settings for {@code source.auth.method=tvm}: a Kerberos/SPNEGO sign-in that returns a short-lived
     * Bearer token. Nothing secret lives here.
     *
     * @param url                  the TVM exchange address, for example https://atlassian-tvm.corp.com/exchange/token/confluence/acme.atlassian.net
     * @param client               {@code curl} (runs curl --negotiate, default) or {@code java} (JDK built-in SPNEGO)
     * @param curlPath             the curl program (default {@code curl}; on Windows {@code curl.exe} is built in)
     * @param useProxy             whether the TVM call goes through the web proxy (default false: the TVM is internal)
     * @param caCertFile           optional company CA file for the TVM call made by curl
     * @param refreshBeforeSeconds renew the token this many seconds before it expires (default 60)
     */
    public record Tvm(String url, String client, String curlPath, boolean useProxy, String caCertFile, int refreshBeforeSeconds) {
        public static final Tvm NONE = new Tvm("", "curl", "curl", false, "", 60);

        public Tvm {
            url = url == null ? "" : url.strip();
            client = client == null || client.isBlank() ? "curl" : client.strip().toLowerCase();
            curlPath = curlPath == null || curlPath.isBlank() ? "curl" : curlPath.strip();
            caCertFile = caCertFile == null ? "" : caCertFile.strip();
            refreshBeforeSeconds = refreshBeforeSeconds < 0 ? 60 : refreshBeforeSeconds;
        }

        public boolean configured() {
            return !url.isBlank();
        }
    }

    /** The settings used before REST API v2, TVM and page ids existed. */
    public ConfluenceOptions(String edition, String apiRoot, List<String> spaces, String ancestorPageId, List<String> contentTypes,
                             String bodyExpand, boolean collectComments, int pageSize, boolean includeSpaceTag, String email, String emailEnv) {
        this(edition, apiRoot, spaces, ancestorPageId, contentTypes, bodyExpand, collectComments, pageSize, includeSpaceTag, email, emailEnv,
                "v1", List.of(), Tvm.NONE);
    }

    public ConfluenceOptions {
        edition = edition == null || edition.isBlank() ? "cloud" : edition.strip().toLowerCase();
        apiRoot = apiRoot == null ? "" : apiRoot.strip();
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        ancestorPageId = ancestorPageId == null ? "" : ancestorPageId.strip();
        contentTypes = contentTypes == null || contentTypes.isEmpty() ? List.of("page") : List.copyOf(contentTypes);
        bodyExpand = bodyExpand == null || bodyExpand.isBlank() ? "body.storage" : bodyExpand.strip();
        apiVersion = apiVersion == null || apiVersion.isBlank() ? "v1" : apiVersion.strip().toLowerCase();
        pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
        tvm = tvm == null ? Tvm.NONE : tvm;
        pageSize = pageSize <= 0 ? 25 : Math.min(pageSize, "v2".equals(apiVersion) ? 250 : 50);
        email = email == null ? "" : email.strip();
        emailEnv = emailEnv == null ? "" : emailEnv.strip();
    }

    public boolean cloud() {
        return !"server".equals(edition);
    }

    /** True for the newer Confluence Cloud REST API v2 ({@code /wiki/api/v2}). */
    public boolean v2() {
        return "v2".equals(apiVersion);
    }

    /** Default REST root for the edition and API version when {@link #apiRoot} is empty. */
    public String defaultApiRootPath() {
        if (v2()) {
            return "/wiki/api/v2";
        }
        return cloud() ? "/wiki/rest/api" : "/rest/api";
    }

    /** Opaque fragment folded into the sync checkpoint scope (spaces + ancestor + page ids). */
    public String scopeFragment() {
        String spacesKey = String.join(",", spaces.stream().map(s -> s.toLowerCase()).sorted().toList());
        String fragment = "spaces=" + spacesKey + "|ancestor=" + ancestorPageId.toLowerCase();
        if (!pageIds.isEmpty()) {
            fragment += "|pages=" + String.join(",", pageIds.stream().sorted().toList());
        }
        return fragment;
    }

    public boolean hasBoundedScope() {
        return !spaces.isEmpty() || !ancestorPageId.isEmpty() || !pageIds.isEmpty();
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
