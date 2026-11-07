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
        Tvm tvm,
        Browser browser) {

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

    /**
     * Browser sign-in settings for {@code source.auth.method=browser} (company SSO login page in Edge/Chrome).
     *
     * @param path           the browser program; empty = find Edge or Chrome automatically
     * @param profileDir     the browser profile folder the app uses; empty = ~/.questioncrawler/browser-profile
     * @param loginUrl       the address to open; empty = the Confluence site (it redirects to the login page by itself)
     * @param timeoutSeconds how long to wait for the person to finish signing in (default 300)
     */
    public record Browser(String path, String profileDir, String loginUrl, int timeoutSeconds) {
        public static final Browser DEFAULT = new Browser("", "", "", 300);

        public Browser {
            path = path == null ? "" : path.strip();
            profileDir = profileDir == null ? "" : profileDir.strip();
            loginUrl = loginUrl == null ? "" : loginUrl.strip();
            timeoutSeconds = timeoutSeconds <= 0 ? 300 : timeoutSeconds;
        }
    }

    public ConfluenceOptions(String edition, String apiRoot, List<String> spaces, String ancestorPageId, List<String> contentTypes,
                             String bodyExpand, boolean collectComments, int pageSize, boolean includeSpaceTag, String email, String emailEnv,
                             String apiVersion, List<String> pageIds, Tvm tvm) {
        this(edition, apiRoot, spaces, ancestorPageId, contentTypes, bodyExpand, collectComments, pageSize, includeSpaceTag, email, emailEnv,
                apiVersion, pageIds, tvm, Browser.DEFAULT);
    }

    /** The settings used before REST API v2, TVM and page ids existed. */
    public ConfluenceOptions(String edition, String apiRoot, List<String> spaces, String ancestorPageId, List<String> contentTypes,
                             String bodyExpand, boolean collectComments, int pageSize, boolean includeSpaceTag, String email, String emailEnv) {
        this(edition, apiRoot, spaces, ancestorPageId, contentTypes, bodyExpand, collectComments, pageSize, includeSpaceTag, email, emailEnv,
                "v1", List.of(), Tvm.NONE, Browser.DEFAULT);
    }

    public ConfluenceOptions {
        edition = edition == null || edition.isBlank() ? "cloud" : edition.strip().toLowerCase();
        apiRoot = apiRoot == null ? "" : apiRoot.strip();
        spaces = spaces == null ? List.of() : spaces.stream().map(ConfluenceOptions::spaceKey).filter(k -> !k.isEmpty()).distinct().toList();
        ancestorPageId = ancestorPageId == null ? "" : ancestorPageId.strip();
        contentTypes = contentTypes == null || contentTypes.isEmpty() ? List.of("page") : List.copyOf(contentTypes);
        bodyExpand = bodyExpand == null || bodyExpand.isBlank() ? "body.storage" : bodyExpand.strip();
        apiVersion = apiVersion == null || apiVersion.isBlank() ? "v1" : apiVersion.strip().toLowerCase();
        pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
        tvm = tvm == null ? Tvm.NONE : tvm;
        browser = browser == null ? Browser.DEFAULT : browser;
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

    /**
     * A space key from what a person pastes: the key itself ("ENG"), or a space/page address such as
     * https://acme.atlassian.net/wiki/spaces/ENG/overview, .../spaces/ENG/pages/123/Title or (Server) .../display/ENG/Home.
     */
    public static String spaceKey(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        for (String marker : new String[] {"/spaces/", "/display/"}) {
            int at = t.indexOf(marker);
            if (at >= 0) {
                String rest = t.substring(at + marker.length());
                int end = 0;
                while (end < rest.length() && "/?#".indexOf(rest.charAt(end)) < 0) {
                    end++;
                }
                String key = java.net.URLDecoder.decode(rest.substring(0, end), java.nio.charset.StandardCharsets.UTF_8);
                return key.strip();
            }
        }
        return t;
    }

    /** Path parts that mark "inside the wiki" (a page, a space, the API): everything from here on is not the site address. */
    private static final java.util.regex.Pattern WIKI_PATH = java.util.regex.Pattern.compile(
            "/(wiki|spaces|display|pages|rest|plugins|x|label|search|people|homepage\\.action|dashboard\\.action|login\\.action)(/|$|\\?)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The Confluence site address from whatever was pasted: a page, a space or the site itself.
     * {@code https://acme.atlassian.net/wiki/spaces/DGF/pages/123/Title} becomes {@code https://acme.atlassian.net};
     * on Server/Data Center a context path is kept ({@code https://wiki.corp.com/confluence/display/ENG/Home} becomes
     * {@code https://wiki.corp.com/confluence}). Query strings and fragments are dropped.
     */
    public static String siteBase(String pasted) {
        if (pasted == null || pasted.isBlank()) {
            return pasted == null ? "" : pasted.strip();
        }
        String text = pasted.strip();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(text.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            return text;
        }
        if (uri.getScheme() == null || uri.getRawAuthority() == null) {
            return text;
        }
        String origin = uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.endsWith(".atlassian.net") || host.endsWith(".jira.com")) {
            return origin; // Cloud: the API lives under /wiki of the site itself
        }
        java.util.regex.Matcher m = WIKI_PATH.matcher(path);
        String context = m.find() ? path.substring(0, m.start()) : path;
        while (context.endsWith("/")) {
            context = context.substring(0, context.length() - 1);
        }
        return origin + context;
    }

    /** The page id in a pasted page address ({@code /pages/123456} or {@code ?pageId=123456}), or null. */
    public static String pageIdFrom(String pasted) {
        if (pasted == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:/pages/|[?&]pageId=)(\\d+)").matcher(pasted);
        return m.find() ? m.group(1) : null;
    }

    /** The space key in a pasted space or page address, or null when the address is just the site. */
    public static String spaceKeyFrom(String pasted) {
        if (pasted == null || !(pasted.contains("/spaces/") || pasted.contains("/display/"))) {
            return null;
        }
        String key = spaceKey(pasted);
        return key.isBlank() || key.contains("/") || key.contains(":") ? null : key;
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
