package com.questioncrawler.core.service;

import com.questioncrawler.core.storage.AtomicFiles;
import com.questioncrawler.core.util.AgeSpec;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Describes, validates and writes the configuration in human terms. Every setting has a label, a plain-language
 * explanation and (where useful) options, so a UI can render a complete settings screen from this metadata alone.
 * The file it writes is the same questioncrawler.properties the CLI reads, with the explanations as comments.
 */
public final class ConfigService {

    public enum Type { TEXT, NUMBER, SWITCH, SELECT, TAGS, PATH, AGE, ENV_NAME, URL }

    public record Option(String value, String label, String hint) {}

    /**
     * @param showForSources source types for which this setting matters (empty = always)
     * @param advanced       hidden behind "advanced" in the UI
     */
    public record Field(String key, String label, String help, Type type, List<Option> options, String defaultValue,
                        String placeholder, List<String> showForSources, boolean advanced, String unit) {}

    public record Section(String id, String title, String description, String icon, List<Field> fields) {}

    /** level: error (blocks saving), warning, info. */
    public record Issue(String key, String level, String message, String fix) {}

    public record ConfigView(String file, boolean exists, Map<String, String> values, List<Section> sections, List<Issue> issues, String preview) {}

    public record SaveResult(boolean saved, ConfigView view, List<Issue> issues) {}

    public record Preset(String id, String title, String description, String icon, Map<String, String> values) {}

    private static final List<String> ALL = List.of();
    private static final List<String> SE = List.of("stackexchange");
    private static final List<String> LIVE = List.of("live");
    private static final List<String> CONFLUENCE = List.of("confluence");
    private static final List<String> JIVE = List.of("jive");
    private static final List<String> SHAREPOINT = List.of("sharepoint");
    private static final List<String> REMOTE = List.of("stackexchange", "live", "confluence", "jive", "sharepoint");
    private static final List<String> AUTH_TOKEN = List.of("live", "confluence", "jive", "sharepoint");
    private static final List<String> LIVE_WIKI = List.of("live", "confluence", "jive", "sharepoint");

    private final Function<String, String> env;

    public ConfigService() {
        this(System::getenv);
    }

    public ConfigService(Function<String, String> env) {
        this.env = env;
    }

    // ================================================================== metadata

    private static Field f(String key, String label, String help, Type type, String def, String placeholder, List<String> sources, boolean advanced, String unit, Option... options) {
        return new Field(key, label, help, type, List.of(options), def, placeholder, sources, advanced, unit);
    }

    public static List<Section> sections() {
        return List.of(
                new Section("source", "Where do the questions come from?",
                        "Pick the site to collect from and tell the app how to reach it. Use the Connect page to test access.", "plug", List.of(
                        f("source.type", "Source", "Demo data needs no internet. Stack Overflow / Stack Exchange uses the public API. Confluence collects wiki pages via its REST API. Jive collects community content via Core REST v3. SharePoint collects site pages/list items via Microsoft Graph. Your internal Q&A site needs its own connector.",
                                Type.SELECT, "fixture", null, ALL, false, null,
                                new Option("fixture", "Demo data", "Offline sample questions, nothing to set up"),
                                new Option("stackexchange", "Stack Overflow / Stack Exchange", "Public site via its official API"),
                                new Option("confluence", "Confluence", "Cloud or Server/DC wiki pages via REST (separate from Jive/SharePoint)"),
                                new Option("jive", "Jive", "Community content via Core REST API v3 (separate from Confluence/SharePoint)"),
                                new Option("sharepoint", "SharePoint", "Site pages / list items via Microsoft Graph (separate from Confluence/Jive)"),
                                new Option("live", "Our internal Q&A site", "Needs API documentation (Stack Exchange–style)")),
                        f("source.siteUrl", "Site address", "The home page of the Stack Exchange site, for example https://stackoverflow.com/.",
                                Type.URL, "https://stackoverflow.com/", "https://stackoverflow.com/", SE, false, null),
                        f("source.stackexchange.keyEnv", "API key variable name", "Optional. Name of the environment variable that holds a free Stack Exchange application key. A key raises the daily request limit from about 300 to 10,000. The key itself is never stored here.",
                                Type.ENV_NAME, "STACKEXCHANGE_KEY", "STACKEXCHANGE_KEY", SE, false, null),
                        f("api.swaggerUrl", "API documentation (Swagger) address", "The page that lists the site's REST API, for example https://so.mycompany.com/swagger/index.html. We read it to find every endpoint, the API address and how the access key is sent.",
                                Type.URL, "", "https://so.mycompany.com/swagger/index.html", LIVE, false, null),
                        f("api.swaggerFile", "API documentation file (JSON)", "Use this when the documentation page needs a company sign-in (SSO). Open the documentation's JSON address in your browser (where you are signed in), save it as swagger.json, and upload it on the Connect screen: its location is filled in here.",
                                Type.PATH, "", "C:/data/swagger.json", LIVE, false, null),
                        f("source.baseUrl", "Site / API address", "For Confluence: the site (https://acme.atlassian.net); pasting any page or space address also works, the site is taken from it. For Jive: the community origin (https://community.example.com). For SharePoint: the site URL (https://contoso.sharepoint.com/sites/Engineering). For an internal Q&A API: the address every call starts with (or leave empty to take it from Swagger).",
                                Type.URL, "", "https://contoso.sharepoint.com/sites/Engineering", LIVE_WIKI, false, null),
                        f("source.confluence.edition", "Confluence edition", "Cloud uses /wiki/rest/api and email + API token (Basic). Server/Data Center uses /rest/api and a personal access token (Bearer).",
                                Type.SELECT, "cloud", null, CONFLUENCE, false, null,
                                new Option("cloud", "Confluence Cloud", "Atlassian Cloud site"),
                                new Option("server", "Server / Data Center", "Self-hosted Confluence")),
                        f("source.confluence.apiVersion", "Confluence REST API", "v1 = classic Content API + CQL (Cloud and Server/DC). v2 = Confluence Cloud REST API v2 (/wiki/api/v2/pages?body-format=storage), the one used with a Token Vending Machine token. Read-only either way.",
                                Type.SELECT, "v1", null, CONFLUENCE, false, null,
                                new Option("v1", "v1: Content API + CQL", "Cloud or Server/DC"),
                                new Option("v2", "v2: Cloud REST API v2", "Cloud only; /wiki/api/v2")),
                        f("source.confluence.tvm.url", "Token Vending Machine address", "Only for sign-in 'Kerberos + Token Vending Machine'. The full exchange address, for example https://atlassian-tvm.mycompany.com/exchange/token/confluence/acme.atlassian.net. The app calls it like 'curl -u : --negotiate <address>' and renews the ~15-minute token by itself.",
                                Type.URL, "", "https://atlassian-tvm.mycompany.com/exchange/token/confluence/acme.atlassian.net", CONFLUENCE, false, null),
                        f("source.confluence.tvm.client", "Kerberos sign-in via", "curl (recommended) runs the same command you would type: curl -u : --negotiate. java uses the JDK's own Kerberos support (needs a ticket the JVM can see).",
                                Type.SELECT, "curl", null, CONFLUENCE, true, null,
                                new Option("curl", "curl --negotiate", "Recommended"),
                                new Option("java", "Java built-in SPNEGO", "No external program")),
                        f("source.confluence.tvm.curlPath", "curl program", "Only if curl is not on the PATH. Windows 10+ has curl.exe built in.",
                                Type.TEXT, "curl", "curl", CONFLUENCE, true, null),
                        f("source.confluence.tvm.useProxy", "Token service through the web proxy", "Usually off: the Token Vending Machine is an internal address. Turn on only if it must be reached through the proxy below.",
                                Type.SWITCH, "false", null, CONFLUENCE, true, null),
                        f("source.confluence.tvm.caCertFile", "Token service certificate file", "Only for curl, if the Token Vending Machine uses a company certificate authority curl does not trust yet.",
                                Type.PATH, "", "C:\\certs\\company-ca.pem", CONFLUENCE, true, null),
                        f("source.confluence.spaces", "Spaces to collect", "Every page in these spaces is collected. Press 'Show my spaces' below and tick them, or type space keys (ENG) or paste a space address (https://acme.atlassian.net/wiki/spaces/ENG/overview). Press Enter after each.",
                                Type.TAGS, "", "ENG", CONFLUENCE, false, null),
                        f("source.confluence.browser.path", "Browser program", "Only for 'Sign in with my browser'. Leave empty to use Edge or Chrome automatically. Otherwise the full path, for example C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe.",
                                Type.PATH, "", "", CONFLUENCE, true, null),
                        f("source.confluence.browser.profileDir", "Browser profile folder", "Only for 'Sign in with my browser'. The app's own browser profile, so your company login can remember you. Empty = ~/.questioncrawler/browser-profile.",
                                Type.PATH, "", "", CONFLUENCE, true, null),
                        f("source.confluence.browser.loginUrl", "Address to open for signing in", "Only for 'Sign in with my browser'. Empty = the Confluence site itself, which sends you to the company login page.",
                                Type.URL, "", "", CONFLUENCE, true, null),
                        f("source.confluence.browser.timeoutSeconds", "Time to finish signing in", "Only for 'Sign in with my browser'. How long the app waits for you to finish the login in the browser.",
                                Type.NUMBER, "300", "300", CONFLUENCE, true, "seconds"),
                        f("source.confluence.pageIds", "Only these pages (optional)", "Advanced. Instead of whole spaces, collect exactly these pages (the number in the page address, for example .../pages/123456789/Title). Leave empty to collect the spaces above.",
                                Type.TAGS, "", "123456789", CONFLUENCE, true, null),
                        f("source.confluence.email", "Atlassian account email", "Cloud only. The email for Basic auth with your API token. Not a secret; the token stays in an environment variable.",
                                Type.TEXT, "", "you@example.com", CONFLUENCE, false, null),
                        f("source.confluence.emailEnv", "Email variable name (optional)", "Optional alternative: NAME of an environment variable that holds the Atlassian email instead of typing it above.",
                                Type.ENV_NAME, "", "CONFLUENCE_EMAIL", CONFLUENCE, true, null),
                        f("source.confluence.ancestorPageId", "Ancestor page id", "Advanced, optional. Instead of whole spaces, only collect the pages under this page id (a subtree).",
                                Type.TEXT, "", "123456", CONFLUENCE, true, null),
                        f("source.confluence.contentTypes", "Content types", "Usually just page. Add blogpost only if you want blog posts too.",
                                Type.TAGS, "page", "page", CONFLUENCE, true, null),
                        f("source.confluence.apiRoot", "REST API root override", "Leave empty for the edition default (/wiki/rest/api on Cloud, /rest/api on Server/DC). Set only if your site uses a custom context path.",
                                Type.TEXT, "", "/wiki/rest/api", CONFLUENCE, true, null),
                        f("source.confluence.bodyExpand", "Page body format", "Prefer storage XHTML (converted to Markdown). Fall back to view HTML if needed.",
                                Type.SELECT, "body.storage", null, CONFLUENCE, true, null,
                                new Option("body.storage", "Storage (XHTML)", "Recommended"),
                                new Option("body.view", "View (rendered HTML)", "Fallback")),
                        f("source.confluence.collectComments", "Also collect page comments", "Downloads footer/inline page comments as thread comments. Uses more API requests. Failures are non-fatal.",
                                Type.SWITCH, "false", null, CONFLUENCE, false, null),
                        f("source.confluence.pageSize", "Search page size", "How many pages Confluence returns per search request (1–50).",
                                Type.NUMBER, "25", null, CONFLUENCE, true, null),
                        f("source.confluence.includeSpaceTag", "Add space:KEY tags", "Adds a synthetic space:ENG-style tag on each page for filtering and classification.",
                                Type.SWITCH, "true", null, CONFLUENCE, true, null),
                        f("source.systemId", "Storage system id", "Folder name under threads/ for this site. Defaults: confluence, jive, or sharepoint depending on the source type.",
                                Type.TEXT, "", "sharepoint", List.of("confluence", "jive", "sharepoint"), true, null),
                        f("source.jive.username", "Jive username", "Basic auth only. The Jive username (often a non-SSO service account). Not a secret; the password/token stays in an environment variable.",
                                Type.TEXT, "", "svc-questioncrawler", JIVE, false, null),
                        f("source.jive.usernameEnv", "Username variable name (optional)", "Optional alternative: NAME of an environment variable that holds the Jive username instead of typing it above.",
                                Type.ENV_NAME, "", "JIVE_USERNAME", JIVE, true, null),
                        f("source.jive.places", "Place ids", "Comma-separated Jive place/space ids to include (numeric ids). Strongly recommended so sync stays bounded.",
                                Type.TAGS, "", "1006", JIVE, false, null),
                        f("source.jive.contentTypes", "Content types", "Jive object types to collect. Defaults cover documents, discussions, and questions.",
                                Type.TAGS, "document,discussion,question", "document,discussion,question", JIVE, true, null),
                        f("source.jive.apiRoot", "REST API root override", "Leave empty for /api/core/v3. Set only if your site uses a custom context path.",
                                Type.TEXT, "", "/api/core/v3", JIVE, true, null),
                        f("source.jive.collectComments", "Also collect comments", "Downloads content comments as thread comments. Uses more API requests. Failures are non-fatal.",
                                Type.SWITCH, "false", null, JIVE, false, null),
                        f("source.jive.pageSize", "List page size", "How many content items Jive returns per request (1–100).",
                                Type.NUMBER, "25", null, JIVE, true, null),
                        f("source.jive.includePlaceTag", "Add place:ID tags", "Adds a synthetic place:1006-style tag on each item for filtering and classification.",
                                Type.SWITCH, "true", null, JIVE, true, null),
                        f("source.sharepoint.tenantId", "Azure AD tenant id", "Directory (tenant) ID for client-credentials auth. Leave empty when using a pre-minted Bearer access token.",
                                Type.TEXT, "", "00000000-0000-0000-0000-000000000000", SHAREPOINT, false, null),
                        f("source.sharepoint.clientId", "App (client) id", "Entra ID app registration application (client) ID. Used with client credentials only.",
                                Type.TEXT, "", "11111111-1111-1111-1111-111111111111", SHAREPOINT, false, null),
                        f("source.sharepoint.clientSecretEnv", "Client secret variable name", "NAME of the environment variable that holds the app client secret (never the secret itself). Used only for Azure AD client credentials.",
                                Type.ENV_NAME, "SHAREPOINT_CLIENT_SECRET", "SHAREPOINT_CLIENT_SECRET", SHAREPOINT, false, null),
                        f("source.sharepoint.siteId", "Graph site id (optional)", "Optional Microsoft Graph site id. Leave empty to resolve from the SharePoint site URL above.",
                                Type.TEXT, "", "", SHAREPOINT, true, null),
                        f("source.sharepoint.listIds", "List ids", "Optional comma-separated Graph list ids when collecting list items (content mode listItems or both).",
                                Type.TAGS, "", "", SHAREPOINT, false, null),
                        f("source.sharepoint.contentMode", "What to collect", "Site pages (default), list items, or both. Separate from Confluence/Jive content types.",
                                Type.SELECT, "pages", null, SHAREPOINT, false, null,
                                new Option("pages", "Site pages", "SharePoint site pages via Graph"),
                                new Option("listItems", "List items", "Items from the configured list ids"),
                                new Option("both", "Pages and list items", "Pages first, then configured lists")),
                        f("source.sharepoint.graphBaseUrl", "Graph base URL", "Usually https://graph.microsoft.com/v1.0. Override only for national clouds (for example graph.microsoft.us).",
                                Type.URL, "https://graph.microsoft.com/v1.0", "https://graph.microsoft.com/v1.0", SHAREPOINT, true, null),
                        f("source.sharepoint.pageSize", "List page size", "How many pages/items Graph returns per request (1–100).",
                                Type.NUMBER, "25", null, SHAREPOINT, true, null),
                        f("source.sharepoint.includeSiteTag", "Add site:NAME tags", "Adds a synthetic site:Engineering-style tag on each item for filtering and classification.",
                                Type.SWITCH, "true", null, SHAREPOINT, true, null),
                        f("source.sharepoint.collectComments", "Also collect comments", "Reserved; SharePoint page comments are not collected in this version.",
                                Type.SWITCH, "false", null, SHAREPOINT, true, null),
                        f("api.keyEnv", "API access key variable name", "All API requests need an access key. Enter the NAME of the environment variable that holds your key (the key itself is never saved), then set that variable in your terminal.",
                                Type.ENV_NAME, "INTERNAL_QA_API_KEY", "INTERNAL_QA_API_KEY", LIVE, false, null),
                        f("api.collectComments", "Also collect comments", "Downloads the comments under questions and answers as extra context. Uses more API requests.",
                                Type.SWITCH, "false", null, LIVE, false, null),
                        f("api.keyName", "Key parameter name", "How the key is called in each request. Leave empty to use what the Swagger documentation says (usually 'key').",
                                Type.TEXT, "", "key", LIVE, true, null),
                        f("api.keyIn", "Send the key in", "Normally in the address (query). Choose 'header' if the documentation says so.",
                                Type.SELECT, "query", null, LIVE, true, null,
                                new Option("query", "The address (query)", "?key=..."),
                                new Option("header", "A request header", "key: ...")),
                        f("api.accessTokenEnv", "Access token variable name", "Only if the API also needs a signed-in user token. Name of the environment variable that holds it.",
                                Type.ENV_NAME, "", "INTERNAL_QA_ACCESS_TOKEN", LIVE, true, null),
                        f("api.site", "Site name", "Only if the API expects a 'site' parameter. Most internal APIs do not.",
                                Type.TEXT, "", "", LIVE, true, null),
                        f("api.bodyFilter", "Response filter with text", "The filter that makes the API include question and answer text. Leave empty for the standard one; if it does not exist, one is created for you.",
                                Type.TEXT, "", "withbody", LIVE, true, null),
                        f("source.auth.method", "How to sign in", "Confluence Cloud: Email + API token (Basic). Confluence Server/DC: Bearer PAT. Jive: username + password (Basic) or OAuth Bearer. SharePoint: Bearer Graph token (or leave Bearer and set tenant/client for client credentials). Internal Q&A: Bearer only when the API asks for it in addition to a key.",
                                Type.SELECT, "none", null, AUTH_TOKEN, false, null,
                                new Option("none", "Not needed", "Only for some internal Q&A APIs"),
                                new Option("basic-env", "Username/email + secret (Basic)", "Confluence Cloud email+token, or Jive username+password from env"),
                                new Option("token", "Bearer token / PAT / OAuth / Graph", "Confluence Server/DC PAT, Jive OAuth, SharePoint Graph token, or internal Bearer"),
                                new Option("tvm", "Kerberos + Token Vending Machine (Confluence)", "Short-lived Bearer token from your company TVM; no secret to store"),
                                new Option("browser", "Sign in with my browser (Confluence)", "Opens your company login page in Edge/Chrome, like you do every day; no secret to store")),
                        f("source.auth.tokenEnv", "Token / password variable name", "The NAME of the environment variable that holds the secret (never the secret itself). Suggested: CONFLUENCE_API_TOKEN / CONFLUENCE_PAT, JIVE_PASSWORD / JIVE_ACCESS_TOKEN, SHAREPOINT_ACCESS_TOKEN.",
                                Type.ENV_NAME, "SHAREPOINT_ACCESS_TOKEN", "SHAREPOINT_ACCESS_TOKEN", AUTH_TOKEN, false, null),
                        f("source.auth.headerName", "Auth header name", "The HTTP header that carries the credential. Almost always Authorization.",
                                Type.TEXT, "Authorization", "Authorization", AUTH_TOKEN, true, null),
                        f("source.auth.scheme", "Bearer prefix", "For Bearer/PAT auth only. Ignored for Basic (email + API token).",
                                Type.TEXT, "Bearer", "Bearer", AUTH_TOKEN, true, null),
                        f("source.allowedHosts", "Allowed hosts", "The app only ever talks to these hosts. Filled in automatically from the address above (SharePoint also adds graph.microsoft.com).",
                                Type.TAGS, "", "contoso.sharepoint.com,graph.microsoft.com", LIVE_WIKI, true, null),
                        f("source.allowedPathPrefixes", "Allowed paths", "The app only requests addresses that start with one of these paths. Confluence Cloud default is /wiki/rest/api/; Jive default is /api/core/v3/; SharePoint Graph default is /v1.0/.",
                                Type.TAGS, "/", "/v1.0/", LIVE_WIKI, true, null),
                        f("source.caCertFile", "Company certificate file", "Only needed if your company uses its own security certificate authority. Path to a .pem/.crt file.",
                                Type.PATH, "", "C:\\certs\\company-ca.pem", REMOTE, true, null),
                        f("network.proxyHost", "Web proxy address", "Only if your company reaches the internet through a web proxy. Enter the host name, for example webproxy.mycompany.com (leave empty if there is none). If the HTTPS_PROXY environment variable is already set, you can leave this empty.",
                                Type.TEXT, "", "webproxy.mycompany.com", REMOTE, false, null),
                        f("network.proxyPort", "Web proxy port", "The proxy's port number, usually 8080 or 3128.",
                                Type.NUMBER, "", "8080", REMOTE, false, null),
                        f("network.noProxy", "Addresses that skip the proxy", "Host names that should be reached directly, not through the proxy. Local addresses always skip it.",
                                Type.TAGS, "", "intranet.mycompany.com", REMOTE, true, null),
                        f("network.proxyUserEnv", "Proxy user variable name", "Only if the proxy asks you to sign in (a user name and password). Name of the environment variable that holds the user name. Proxies that use Windows sign-in need a local helper such as CNTLM.",
                                Type.ENV_NAME, "", "PROXY_USER", REMOTE, true, null),
                        f("network.proxyPasswordEnv", "Proxy password variable name", "Name of the environment variable that holds the proxy password. The password itself is never stored in a file.",
                                Type.ENV_NAME, "", "PROXY_PASSWORD", REMOTE, true, null),
                        f("source.followRedirects", "Follow redirects", "Internal sites often answer with a redirect (HTTP 301/302/307/308) to the real address, for example from http to https or to a gateway. By default they are followed, up to 5 in a row; a redirect down to plain http is never followed. Credentials are sent along, so choose 'Same host only' if you do not trust where a redirect might lead.",
                                Type.SELECT, "all", null, REMOTE, false, null,
                                new Option("all", "Yes, follow them", "Recommended for internal sites"),
                                new Option("same-host", "Same host only", "Stop if it leads to another host"),
                                new Option("off", "No", "Never follow redirects")),
                        f("source.timeoutSeconds", "Wait at most", "How long to wait for the site to answer before giving up.",
                                Type.NUMBER, "30", null, REMOTE, true, "seconds"),
                        f("source.allowInsecureHttp", "Allow unencrypted http", "For local testing only. Never turn this on for a real site.",
                                Type.SWITCH, "false", null, REMOTE, true, null))),
                new Section("topic", "What should we collect?",
                        "Tags are the labels people put on questions. We collect every question that has your tags.", "tags", List.of(
                        f("sync.tags", "Tags", "Press Enter after each tag. For Stack Exchange these are question tags; for Confluence they are page labels; for Jive they are content tags; for SharePoint they match page title/description text or list keywords (case-insensitive). Type all (or *) only with a bounded site/space/place or a time window — unbounded whole-site sync is refused.",
                                Type.TAGS, "", "artificial-intelligence", ALL, false, null),
                        f("sync.tagMode", "A question must have", "With several tags: any of them, or all of them.", Type.SELECT, "ANY", null, ALL, false, null,
                                new Option("ANY", "Any of the tags", "Wider: questions matching at least one tag"),
                                new Option("ALL", "All of the tags", "Narrower: questions matching every tag")),
                        f("sync.partialTags", "Match part of a tag", "Also accept tags that merely contain your text. Leave off for exact matching.",
                                Type.SWITCH, "false", null, ALL, true, null),
                        f("sync.maxQuestionAge", "Only collect questions newer than", "Limits the download to recent questions, for example 30d, 6m or 1y. Empty means everything.",
                                Type.AGE, "", "1y", ALL, false, null))),
                new Section("collection", "How should collecting behave?",
                        "Sensible defaults are already set. Change these only if you know you need to.", "refresh", List.of(
                        f("sync.scheduler.enabled", "Refresh automatically", "Keeps the knowledge fresh while this application is running. Off by default.",
                                Type.SWITCH, "false", null, ALL, false, null),
                        f("sync.scheduler.intervalMinutes", "Refresh every", "How often an automatic refresh happens.", Type.NUMBER, "360", null, ALL, false, "minutes"),
                        f("status.staleAfterHours", "Call it out of date after", "After this long without a successful refresh the app warns that answers may be outdated.",
                                Type.NUMBER, "168", null, ALL, false, "hours"),
                        f("sync.retentionDays", "Keep removed questions for", "Questions deleted or hidden on the site disappear from search at once and are erased after this many days.",
                                Type.NUMBER, "30", null, ALL, true, "days"),
                        f("sync.overlapMinutes", "Safety overlap", "Each update re-checks this much recent history to catch late edits and clock differences.",
                                Type.NUMBER, "60", null, ALL, true, "minutes"),
                        f("sync.concurrency", "Parallel downloads", "How many questions are downloaded at the same time (1-16).", Type.NUMBER, "4", null, ALL, true, null),
                        f("sync.maxAttempts", "Retries per request", "How many times to retry a request that failed temporarily.", Type.NUMBER, "5", null, ALL, true, null),
                        f("source.fixture.pageSize", "Demo page size", "Only for the demo data: how many questions are shown per page of results.", Type.NUMBER, "2", null, List.of("fixture"), true, null))),
                new Section("storage", "Where is everything saved?",
                        "All collected knowledge is stored as ordinary files in one folder. There is no database.", "folder", List.of(
                        f("storage.root", "Knowledge folder", "Use a folder approved by your organization. Keep it outside the source-code folder.",
                                Type.PATH, "", "C:\\Users\\you\\.questioncrawler\\data", ALL, false, null),
                        f("taxonomy.file", "Categories file", "The file that defines your categories. Edit categories on the Organize page; this path is created for you.",
                                Type.PATH, "", "taxonomy.json", ALL, true, null))));
    }

    public static Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        for (Section s : sections()) {
            for (Field field : s.fields()) {
                d.put(field.key(), field.defaultValue());
            }
        }
        return d;
    }

    public static List<Preset> presets() {
        return List.of(
                new Preset("demo", "Try it with demo data", "Sample questions built into the app. No internet, no account. Best for seeing how everything works.", "sparkles",
                        Map.of("source.type", "fixture", "sync.tags", "orbit", "sync.maxQuestionAge", "")),
                new Preset("stackoverflow", "Stack Overflow: Artificial Intelligence", "Collect real questions from stackoverflow.com about artificial intelligence, from the last year.", "globe",
                        Map.of("source.type", "stackexchange", "source.siteUrl", "https://stackoverflow.com/", "sync.tags", "artificial-intelligence", "sync.tagMode", "ANY", "sync.maxQuestionAge", "1y")),
                new Preset("stackexchange", "Another Stack Exchange site", "Server Fault, Super User, Ask Ubuntu or any public Stack Exchange community.", "layers",
                        Map.of("source.type", "stackexchange", "source.siteUrl", "https://serverfault.com/", "sync.tags", "", "sync.maxQuestionAge", "1y")),
                new Preset("confluence", "Confluence wiki", "Collect Confluence Cloud or Server/DC pages via the REST API. Set the site address, email + token env (Cloud) or PAT (Server), and space keys or labels. Separate from Jive/SharePoint.", "book",
                        Map.ofEntries(
                                Map.entry("source.type", "confluence"),
                                Map.entry("source.baseUrl", ""),
                                Map.entry("source.confluence.edition", "cloud"),
                                Map.entry("source.confluence.email", "you@example.com"),
                                Map.entry("source.confluence.spaces", "ENG"),
                                Map.entry("source.auth.method", "basic-env"),
                                Map.entry("source.auth.tokenEnv", "CONFLUENCE_API_TOKEN"),
                                Map.entry("sync.tags", "runbook"),
                                Map.entry("sync.maxQuestionAge", "1y"),
                                Map.entry("sync.concurrency", "2"),
                                Map.entry("source.followRedirects", "same-host"),
                                Map.entry("source.allowedPathPrefixes", "/wiki/rest/api/"))),
                new Preset("confluence-tvm", "Confluence (company sign-in)", "Read Confluence Cloud pages with the REST API v2, using a short-lived token from your company Token Vending Machine (Kerberos) and the web proxy. Nothing secret to store.", "book",
                        Map.ofEntries(
                                Map.entry("source.type", "confluence"),
                                Map.entry("source.baseUrl", ""),
                                Map.entry("source.confluence.edition", "cloud"),
                                Map.entry("source.confluence.apiVersion", "v2"),
                                Map.entry("source.auth.method", "tvm"),
                                Map.entry("source.confluence.tvm.url", ""),
                                Map.entry("source.confluence.tvm.client", "curl"),
                                Map.entry("source.confluence.spaces", ""),
                                Map.entry("source.confluence.pageIds", ""),
                                Map.entry("sync.tags", "all"),
                                Map.entry("sync.maxQuestionAge", "1y"),
                                Map.entry("sync.concurrency", "2"),
                                Map.entry("source.followRedirects", "same-host"),
                                Map.entry("source.allowedPathPrefixes", "/wiki/api/v2/"))),
                new Preset("confluence-browser", "Confluence (sign in with my browser)", "Read Confluence pages when the site sends you to your company login page. The app opens that page in Edge/Chrome, you sign in as usual, and it carries on. Nothing for admins to change; nothing secret to store.", "book",
                        Map.ofEntries(
                                Map.entry("source.type", "confluence"),
                                Map.entry("source.baseUrl", ""),
                                Map.entry("source.confluence.edition", "cloud"),
                                Map.entry("source.confluence.apiVersion", "v2"),
                                Map.entry("source.auth.method", "browser"),
                                Map.entry("source.confluence.spaces", ""),
                                Map.entry("sync.tags", "all"),
                                Map.entry("sync.maxQuestionAge", "1y"),
                                Map.entry("sync.concurrency", "2"),
                                Map.entry("source.followRedirects", "same-host"),
                                Map.entry("source.allowedPathPrefixes", "/wiki/api/v2/"))),
                new Preset("jive", "Jive community", "Collect Jive documents/discussions via Core REST v3. Set the community address, username + password env (or OAuth Bearer), and place ids. Separate from Confluence/SharePoint.", "users",
                        Map.ofEntries(
                                Map.entry("source.type", "jive"),
                                Map.entry("source.baseUrl", ""),
                                Map.entry("source.jive.username", "svc-questioncrawler"),
                                Map.entry("source.jive.places", "1006"),
                                Map.entry("source.auth.method", "basic-env"),
                                Map.entry("source.auth.tokenEnv", "JIVE_PASSWORD"),
                                Map.entry("sync.tags", "runbook"),
                                Map.entry("sync.maxQuestionAge", "1y"),
                                Map.entry("sync.concurrency", "2"),
                                Map.entry("source.followRedirects", "same-host"),
                                Map.entry("source.allowedPathPrefixes", "/api/core/v3/"))),
                new Preset("sharepoint", "SharePoint site", "Collect SharePoint Online site pages via Microsoft Graph. Set the site URL and either a Bearer token env or Azure AD client credentials (tenant, client id, secret env). Separate from Confluence/Jive.", "folder",
                        Map.ofEntries(
                                Map.entry("source.type", "sharepoint"),
                                Map.entry("source.baseUrl", "https://contoso.sharepoint.com/sites/Engineering"),
                                Map.entry("source.sharepoint.contentMode", "pages"),
                                Map.entry("source.sharepoint.graphBaseUrl", "https://graph.microsoft.com/v1.0"),
                                Map.entry("source.auth.method", "token"),
                                Map.entry("source.auth.tokenEnv", "SHAREPOINT_ACCESS_TOKEN"),
                                Map.entry("sync.tags", "all"),
                                Map.entry("sync.maxQuestionAge", "1y"),
                                Map.entry("sync.concurrency", "2"),
                                Map.entry("source.followRedirects", "same-host"),
                                Map.entry("source.allowedHosts", "contoso.sharepoint.com,graph.microsoft.com"),
                                Map.entry("source.allowedPathPrefixes", "/v1.0/"))),
                new Preset("internal", "Our internal Q&A site", "Your company's Stack Overflow-style site, through its REST API. Paste the link to its Swagger page and we read every endpoint for you.", "building",
                        Map.of("source.type", "live", "source.baseUrl", "", "api.swaggerUrl", "", "sync.tags", "")));
    }

    // ================================================================== reading / rendering

    public Map<String, String> read(Path file) {
        Map<String, String> values = new LinkedHashMap<>(defaults());
        Properties p = new Properties();
        if (file != null && Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
            }
            for (String key : p.stringPropertyNames()) {
                values.put(key, p.getProperty(key).strip());
            }
        }
        return values;
    }

    public ConfigView view(Path file, Map<String, String> values) {
        Map<String, String> effective = values == null ? read(file) : values;
        return new ConfigView(file == null ? null : file.toString(), file != null && Files.exists(file), effective, sections(),
                validate(effective), render(effective));
    }

    /** The file exactly as it would be written: readable, grouped, every setting explained. */
    public String render(Map<String, String> values) {
        StringBuilder out = new StringBuilder("# QuestionCrawler settings. Written by the app; you can also edit this file by hand.\n"
                + "# Secrets are never stored here: settings that need a password or token only name an environment variable.\n");
        List<String> known = new ArrayList<>();
        for (Section section : sections()) {
            out.append("\n# ==== ").append(section.title()).append(" ====\n");
            for (Field field : section.fields()) {
                known.add(field.key());
                out.append("# ").append(field.label()).append(": ").append(field.help()).append('\n');
                out.append(field.key()).append('=').append(escape(values.getOrDefault(field.key(), field.defaultValue()))).append('\n');
            }
        }
        List<String> extra = values.keySet().stream().filter(k -> !known.contains(k)).toList();
        if (!extra.isEmpty()) {
            out.append("\n# ==== Other settings kept from the previous file ====\n");
            extra.forEach(k -> out.append(k).append('=').append(escape(values.get(k))).append('\n'));
        }
        return out.toString();
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\n", " ");
    }

    // ================================================================== validation

    public List<Issue> validate(Map<String, String> submitted) {
        // Settings that are not mentioned count as their defaults.
        Map<String, String> v = new LinkedHashMap<>(defaults());
        v.putAll(submitted);
        List<Issue> issues = new ArrayList<>();
        String type = v.getOrDefault("source.type", "fixture");
        if (!List.of("fixture", "stackexchange", "live", "confluence", "jive", "sharepoint").contains(type)) {
            issues.add(new Issue("source.type", "error", "Choose one of the listed sources.", "Pick Demo data, Stack Overflow / Stack Exchange, Confluence, Jive, SharePoint, or Our internal Q&A site."));
        }
        if (type.equals("stackexchange")) {
            String url = v.getOrDefault("source.siteUrl", "");
            URI uri = parseUri(url);
            if (uri == null || uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                issues.add(new Issue("source.siteUrl", "error", "That does not look like a secure web address.", "Use the full address, for example https://stackoverflow.com/"));
            }
            checkEnvName(issues, "source.stackexchange.keyEnv", v.get("source.stackexchange.keyEnv"), false);
        }
        if (type.equals("live") || type.equals("confluence") || type.equals("jive") || type.equals("sharepoint")) {
            String url = v.getOrDefault("source.baseUrl", "");
            String swagger = v.getOrDefault("api.swaggerUrl", "");
            URI uri = parseUri(url);
            URI docs = parseUri(swagger);
            boolean insecure = Boolean.parseBoolean(v.getOrDefault("source.allowInsecureHttp", "false"));
            String docsFile = v.getOrDefault("api.swaggerFile", "").strip();
            if (type.equals("live")) {
                if (!docsFile.isEmpty() && !java.nio.file.Files.isReadable(Path.of(docsFile))) {
                    issues.add(new Issue("api.swaggerFile", "error", "That documentation file cannot be read.", "Check the path, or upload the file again on the Connect screen."));
                }
                if (url.isBlank() && swagger.isBlank() && docsFile.isEmpty()) {
                    issues.add(new Issue("api.swaggerUrl", "error", "Enter the API documentation (Swagger) address, or the API address.", "Ask the site owners for the link to their Swagger page; it usually ends in /swagger/index.html."));
                }
            } else if (type.equals("confluence") && !url.isBlank()
                    && !com.questioncrawler.core.source.ConfluenceOptions.siteBase(url).equals(url.strip().replaceAll("/+$", ""))) {
                String site = com.questioncrawler.core.source.ConfluenceOptions.siteBase(url);
                String space = com.questioncrawler.core.source.ConfluenceOptions.spaceKeyFrom(url);
                issues.add(new Issue("source.baseUrl", "info", "That is an address inside the wiki. The site address " + site + " will be used"
                        + (space != null ? ", and the space " + space + " (if no space is chosen yet)." : "."),
                        "Nothing to do: it is corrected when you save. To collect only this one page, put its id in “Only these pages”."));
            } else if (type.equals("confluence") && url.isBlank()) {
                issues.add(new Issue("source.baseUrl", "error", "Enter the Confluence site address.", "For Cloud use https://your-site.atlassian.net — not a page URL inside the wiki."));
            } else if (type.equals("jive") && url.isBlank()) {
                issues.add(new Issue("source.baseUrl", "error", "Enter the Jive community address.", "Use the site origin, for example https://community.example.com — not a single content URL."));
            } else if (type.equals("sharepoint") && url.isBlank()
                    && v.getOrDefault("source.sharepoint.siteId", "").strip().isEmpty()) {
                issues.add(new Issue("source.baseUrl", "error", "Enter the SharePoint site URL (or a Graph site id).",
                        "Use https://contoso.sharepoint.com/sites/Engineering — not a single page URL."));
            }
            if (!url.isBlank() && (uri == null || uri.getHost() == null)) {
                issues.add(new Issue("source.baseUrl", "error", "That does not look like a web address.", "Start with https:// and include the full host name."));
            } else if (!url.isBlank() && !"https".equalsIgnoreCase(uri.getScheme()) && !insecure) {
                issues.add(new Issue("source.baseUrl", "error", "Unencrypted http is not allowed for a real site.", "Use an https:// address."));
            }
            if (type.equals("live")) {
                if (!swagger.isBlank() && (docs == null || docs.getHost() == null)) {
                    issues.add(new Issue("api.swaggerUrl", "error", "That does not look like a web address.", "Start with https:// and include the full host name."));
                } else if (!swagger.isBlank() && !"https".equalsIgnoreCase(docs.getScheme()) && !insecure) {
                    issues.add(new Issue("api.swaggerUrl", "error", "Unencrypted http is not allowed for a real site.", "Use an https:// address."));
                }
                checkEnvName(issues, "api.keyEnv", v.get("api.keyEnv"), true);
                checkEnvName(issues, "api.accessTokenEnv", v.get("api.accessTokenEnv"), false);
            }
            String authMethod = v.getOrDefault("source.auth.method", "none");
            if ("token".equals(authMethod) || "basic-env".equals(authMethod) || "confluence-api-token".equals(authMethod)) {
                checkEnvName(issues, "source.auth.tokenEnv", v.get("source.auth.tokenEnv"), true);
            }
            if (type.equals("confluence")) {
                String edition = v.getOrDefault("source.confluence.edition", "cloud").strip().toLowerCase();
                if (!List.of("cloud", "server").contains(edition)) {
                    issues.add(new Issue("source.confluence.edition", "error", "Choose Cloud or Server / Data Center.", null));
                }
                if ("basic-env".equals(authMethod) || "confluence-api-token".equals(authMethod)
                        || ("cloud".equals(edition) && !"token".equals(authMethod) && !"none".equals(authMethod) && !"tvm".equals(authMethod) && !"browser".equals(authMethod))) {
                    String email = v.getOrDefault("source.confluence.email", "").strip();
                    String emailEnv = v.getOrDefault("source.confluence.emailEnv", "").strip();
                    if (email.isEmpty() && emailEnv.isEmpty() && ("basic-env".equals(authMethod) || "confluence-api-token".equals(authMethod)
                            || "cloud".equals(edition))) {
                        {
                            issues.add(new Issue("source.confluence.email", "error", "Confluence Cloud Basic auth needs your Atlassian email.", "Enter the email, or set source.confluence.emailEnv to an environment variable name."));
                        }
                    }
                    checkEnvName(issues, "source.confluence.emailEnv", emailEnv, false);
                }
                String apiVersion = v.getOrDefault("source.confluence.apiVersion", "v1").strip().toLowerCase();
                if (!List.of("v1", "v2").contains(apiVersion)) {
                    issues.add(new Issue("source.confluence.apiVersion", "error", "Choose v1 or v2.", null));
                } else if ("v2".equals(apiVersion) && "server".equals(edition)) {
                    issues.add(new Issue("source.confluence.apiVersion", "error", "REST API v2 exists only on Confluence Cloud.", "Use v1 for Server / Data Center."));
                }
                if ("tvm".equals(authMethod)) {
                    String tvmUrl = v.getOrDefault("source.confluence.tvm.url", "").strip();
                    URI tvm = tvmUrl.isEmpty() ? null : parseUri(tvmUrl);
                    if (tvmUrl.isEmpty()) {
                        issues.add(new Issue("source.confluence.tvm.url", "error", "Enter the Token Vending Machine address.",
                                "The address you use with curl -u : --negotiate, for example https://atlassian-tvm.mycompany.com/exchange/token/confluence/acme.atlassian.net."));
                    } else if (tvm == null || tvm.getHost() == null || !"https".equalsIgnoreCase(tvm.getScheme())) {
                        issues.add(new Issue("source.confluence.tvm.url", "error", "That does not look like an https:// address.", null));
                    }
                    if (!"v2".equals(apiVersion)) {
                        issues.add(new Issue("source.confluence.apiVersion", "warning", "Token Vending Machine tokens are normally used with REST API v2.",
                                "Choose v2 unless your platform team says the token also works with the classic API."));
                    }
                }
                String spaces = v.getOrDefault("source.confluence.spaces", "").strip();
                String ancestor = v.getOrDefault("source.confluence.ancestorPageId", "").strip()
                        + v.getOrDefault("source.confluence.pageIds", "").strip();
                String tags = v.getOrDefault("sync.tags", "").strip();
                String age = v.getOrDefault("sync.maxQuestionAge", "").strip();
                if (com.questioncrawler.core.source.TagFilter.asksForAll(java.util.Arrays.asList(tags.split(",")))
                        && spaces.isEmpty() && ancestor.isEmpty() && age.isEmpty()) {
                    issues.add(new Issue("source.confluence.spaces", "error",
                            "Collecting every Confluence page without a space, ancestor, or time window is refused.",
                            "Add space keys, an ancestor page id, specific labels instead of 'all', or a max age window."));
                } else if (spaces.isEmpty() && ancestor.isEmpty() && tags.isEmpty()) {
                    issues.add(new Issue("source.confluence.spaces", "warning",
                            "No spaces or labels yet — collection will have nothing useful to target.",
                            "Add at least one space key or label (tags)."));
                }
            }
            if (type.equals("jive")) {
                String authMethodJ = v.getOrDefault("source.auth.method", "none");
                if ("basic-env".equals(authMethodJ)) {
                    String user = v.getOrDefault("source.jive.username", "").strip();
                    String userEnv = v.getOrDefault("source.jive.usernameEnv", "").strip();
                    if (user.isEmpty() && userEnv.isEmpty()) {
                        issues.add(new Issue("source.jive.username", "error", "Jive Basic auth needs a username.",
                                "Enter the username, or set source.jive.usernameEnv to an environment variable name."));
                    }
                    checkEnvName(issues, "source.jive.usernameEnv", userEnv, false);
                }
                String places = v.getOrDefault("source.jive.places", "").strip();
                String tagsJ = v.getOrDefault("sync.tags", "").strip();
                String ageJ = v.getOrDefault("sync.maxQuestionAge", "").strip();
                if (com.questioncrawler.core.source.TagFilter.asksForAll(java.util.Arrays.asList(tagsJ.split(",")))
                        && places.isEmpty() && ageJ.isEmpty()) {
                    issues.add(new Issue("source.jive.places", "error",
                            "Collecting every Jive item without a place or time window is refused.",
                            "Add place ids, specific tags instead of 'all', or a max age window."));
                } else if (places.isEmpty() && tagsJ.isEmpty()) {
                    issues.add(new Issue("source.jive.places", "warning",
                            "No places or tags yet — collection will have nothing useful to target.",
                            "Add at least one place id or tag."));
                }
            }
            if (type.equals("sharepoint")) {
                String tenant = v.getOrDefault("source.sharepoint.tenantId", "").strip();
                String clientId = v.getOrDefault("source.sharepoint.clientId", "").strip();
                String secretEnv = v.getOrDefault("source.sharepoint.clientSecretEnv", "").strip();
                boolean clientCreds = !tenant.isEmpty() && !clientId.isEmpty();
                if (clientCreds) {
                    checkEnvName(issues, "source.sharepoint.clientSecretEnv",
                            secretEnv.isEmpty() ? "SHAREPOINT_CLIENT_SECRET" : secretEnv, true);
                } else if ("token".equals(authMethod) || authMethod.isBlank() || "none".equals(authMethod)) {
                    // Bearer access token path (default for SharePoint).
                    checkEnvName(issues, "source.auth.tokenEnv",
                            v.getOrDefault("source.auth.tokenEnv", "SHAREPOINT_ACCESS_TOKEN"), true);
                }
                String mode = v.getOrDefault("source.sharepoint.contentMode", "pages").strip().toLowerCase();
                String lists = v.getOrDefault("source.sharepoint.listIds", "").strip();
                if (("listitems".equals(mode) || "list-items".equals(mode)) && lists.isEmpty()) {
                    issues.add(new Issue("source.sharepoint.listIds", "error",
                            "List-items mode needs at least one Graph list id.",
                            "Add source.sharepoint.listIds, or switch content mode to pages."));
                }
                String siteId = v.getOrDefault("source.sharepoint.siteId", "").strip();
                String tagsSp = v.getOrDefault("sync.tags", "").strip();
                String ageSp = v.getOrDefault("sync.maxQuestionAge", "").strip();
                if (com.questioncrawler.core.source.TagFilter.asksForAll(java.util.Arrays.asList(tagsSp.split(",")))
                        && url.isBlank() && siteId.isEmpty() && ageSp.isEmpty()) {
                    issues.add(new Issue("source.baseUrl", "error",
                            "Collecting every SharePoint item without a site or time window is refused.",
                            "Set the site URL (or site id), or a max age window."));
                } else if (url.isBlank() && siteId.isEmpty() && tagsSp.isEmpty()) {
                    issues.add(new Issue("source.baseUrl", "warning",
                            "No SharePoint site yet — collection will have nothing useful to target.",
                            "Enter the site URL."));
                }
            }
        }
        if (!type.equals("fixture")) {
            String ca = v.getOrDefault("source.caCertFile", "").strip();
            if (!ca.isEmpty() && !Files.isReadable(Path.of(ca))) {
                issues.add(new Issue("source.caCertFile", "error", "We cannot read that certificate file.", "Check the path, or leave it empty if you do not need a company certificate."));
            }
        }
        if (!type.equals("fixture")) {
            com.questioncrawler.core.source.ProxyConfig px = com.questioncrawler.core.source.ProxyConfig.parse(
                    v.getOrDefault("network.proxyHost", ""), v.getOrDefault("network.proxyPort", ""), "", "", "");
            if (px.host() != null && px.port() <= 0) {
                issues.add(new Issue("network.proxyPort", "error", "Enter the proxy's port number.", "Usually 8080 or 3128. Ask your IT help desk if you are not sure."));
            } else if (px.port() > 65535) {
                issues.add(new Issue("network.proxyPort", "error", "A port number is between 1 and 65535.", null));
            }
            checkEnvName(issues, "network.proxyUserEnv", v.get("network.proxyUserEnv"), false);
            checkEnvName(issues, "network.proxyPasswordEnv", v.get("network.proxyPasswordEnv"), false);
        }
        String tags = v.getOrDefault("sync.tags", "").strip();
        if (tags.isEmpty()) {
            issues.add(new Issue("sync.tags", "warning", "No tags yet, so there is nothing to collect.", "Add at least one tag, for example artificial-intelligence."));
        } else if (com.questioncrawler.core.source.TagFilter.asksForAll(java.util.Arrays.asList(tags.split(",")))) {
            if (tags.split(",").length > 1) {
                issues.add(new Issue("sync.tags", "warning", "'all' already collects every question, so the other tags are not needed.", "Remove the other tags, or remove 'all' to collect only those."));
            }
            if (type.equals("stackexchange")) {
                issues.add(new Issue("sync.maxQuestionAge", "warning", "Every tag on a public Stack Exchange site is millions of questions.", "Choose a short time window on the Collect page (for example 7 days); collecting is refused without one."));
            }
            if (type.equals("confluence")) {
                String spaces = v.getOrDefault("source.confluence.spaces", "").strip();
                String ancestor = v.getOrDefault("source.confluence.ancestorPageId", "").strip()
                        + v.getOrDefault("source.confluence.pageIds", "").strip();
                String age = v.getOrDefault("sync.maxQuestionAge", "").strip();
                if (spaces.isEmpty() && ancestor.isEmpty() && age.isEmpty()) {
                    issues.add(new Issue("sync.maxQuestionAge", "error",
                            "Label 'all' on Confluence needs a space, ancestor page, or time window.",
                            "Set space keys or pick a collection window on the Collect page."));
                }
            }
            if (type.equals("jive")) {
                String places = v.getOrDefault("source.jive.places", "").strip();
                String age = v.getOrDefault("sync.maxQuestionAge", "").strip();
                if (places.isEmpty() && age.isEmpty()) {
                    issues.add(new Issue("sync.maxQuestionAge", "error",
                            "Tag 'all' on Jive needs a place id or time window.",
                            "Set place ids or pick a collection window on the Collect page."));
                }
            }
            if (type.equals("sharepoint")) {
                String siteUrl = v.getOrDefault("source.baseUrl", "").strip();
                String siteId = v.getOrDefault("source.sharepoint.siteId", "").strip();
                String age = v.getOrDefault("sync.maxQuestionAge", "").strip();
                if (siteUrl.isEmpty() && siteId.isEmpty() && age.isEmpty()) {
                    issues.add(new Issue("sync.maxQuestionAge", "error",
                            "Tag 'all' on SharePoint needs a site URL/site id or time window.",
                            "Set the site address or pick a collection window on the Collect page."));
                }
            }
        } else if ("ALL".equals(v.get("sync.tagMode")) && type.equals("stackexchange") && tags.split(",").length > 5) {
            issues.add(new Issue("sync.tagMode", "error", "Stack Exchange accepts at most 5 tags when all must match.", "Remove some tags or choose 'Any of the tags'."));
        }
        if (type.equals("stackexchange") && Boolean.parseBoolean(v.getOrDefault("sync.partialTags", "false"))) {
            issues.add(new Issue("sync.partialTags", "error", "Stack Exchange cannot match part of a tag.", "Turn this off to match tags exactly."));
        }
        String age = v.getOrDefault("sync.maxQuestionAge", "").strip();
        if (!age.isEmpty() && !AgeSpec.isValid(age)) {
            issues.add(new Issue("sync.maxQuestionAge", "error", "We could not read that as an age.", "Use a number and a unit: 30d (days), 2w (weeks), 6m (months) or 1y (years)."));
        }
        checkNumber(issues, v, "source.timeoutSeconds", 1, 300, "seconds");
        checkNumber(issues, v, "sync.concurrency", 1, 16, "");
        checkNumber(issues, v, "sync.maxAttempts", 1, 20, "");
        checkNumber(issues, v, "sync.retentionDays", 0, 3650, "days");
        checkNumber(issues, v, "sync.overlapMinutes", 0, 10080, "minutes");
        checkNumber(issues, v, "sync.scheduler.intervalMinutes", 5, 100000, "minutes");
        checkNumber(issues, v, "status.staleAfterHours", 1, 100000, "hours");
        String root = v.getOrDefault("storage.root", "").strip();
        if (!root.isEmpty()) {
            Path p = Path.of(root.startsWith("~") ? System.getProperty("user.home") + root.substring(1) : root);
            Path existing = p;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || !Files.isWritable(existing)) {
                issues.add(new Issue("storage.root", "error", "We cannot write to that location.", "Choose a folder you have permission to create files in."));
            }
        }
        return issues;
    }

    private void checkEnvName(List<Issue> issues, String key, String name, boolean required) {
        if (name == null || name.isBlank()) {
            if (required) {
                issues.add(new Issue(key, "error", "Enter the name of the variable that holds your token.", "For example INTERNAL_QA_TOKEN. Then set it in your terminal before starting."));
            }
            return;
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            issues.add(new Issue(key, "error", "A variable name may only contain letters, digits and underscores.", "For example MY_SITE_TOKEN."));
            return;
        }
        String value = env.apply(name);
        if (value == null || value.isBlank()) {
            issues.add(new Issue(key, required ? "warning" : "info",
                    required ? "The variable " + name + " is not set where this app is running, so signing in will fail."
                            : "No key is set in " + name + ", so the smaller anonymous request limit applies.",
                    "Set it in the terminal that starts the app, for example: $env:" + name + "='your-value' (PowerShell) or export " + name + "='your-value' (Mac/Linux)."));
        }
    }

    private static void checkNumber(List<Issue> issues, Map<String, String> v, String key, int min, int max, String unit) {
        String raw = v.getOrDefault(key, "").strip();
        if (raw.isEmpty()) {
            return;
        }
        try {
            int n = Integer.parseInt(raw);
            if (n < min || n > max) {
                issues.add(new Issue(key, "error", "Enter a number between " + min + " and " + max + (unit.isEmpty() ? "" : " " + unit) + ".", null));
            }
        } catch (NumberFormatException e) {
            issues.add(new Issue(key, "error", "Enter a whole number.", null));
        }
    }

    private static URI parseUri(String s) {
        try {
            return s == null || s.isBlank() ? null : URI.create(s.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ================================================================== saving

    /** Merges the submitted values over the current file, validates, and writes atomically if there are no errors. */
    public SaveResult save(Path file, Map<String, String> submitted) {
        Map<String, String> merged = read(file);
        submitted.forEach((k, val) -> merged.put(k, val == null ? "" : val.strip()));
        // Friendliness: the host allow-list follows the address the user typed.
        String type = merged.getOrDefault("source.type", "fixture");
        if (type.equals("confluence")) {
            // A pasted page or space address is saved as the site address; its space becomes the space to collect.
            String pasted = merged.getOrDefault("source.baseUrl", "");
            String site = com.questioncrawler.core.source.ConfluenceOptions.siteBase(pasted);
            if (!site.equals(pasted)) {
                merged.put("source.baseUrl", site);
                String space = com.questioncrawler.core.source.ConfluenceOptions.spaceKeyFrom(pasted);
                if (space != null && merged.getOrDefault("source.confluence.spaces", "").isBlank()
                        && merged.getOrDefault("source.confluence.pageIds", "").isBlank()
                        && merged.getOrDefault("source.confluence.ancestorPageId", "").isBlank()) {
                    merged.put("source.confluence.spaces", space);
                }
            }
        }
        if ((type.equals("live") || type.equals("confluence") || type.equals("jive") || type.equals("sharepoint"))
                && merged.getOrDefault("source.allowedHosts", "").isBlank()) {
            java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
            for (String key : new String[] {"source.baseUrl", "api.swaggerUrl"}) {
                URI uri = parseUri(merged.get(key));
                if (uri != null && uri.getHost() != null) {
                    hosts.add(uri.getHost());
                }
            }
            if (type.equals("sharepoint")) {
                URI graph = parseUri(merged.getOrDefault("source.sharepoint.graphBaseUrl",
                        "https://graph.microsoft.com/v1.0"));
                if (graph != null && graph.getHost() != null) {
                    hosts.add(graph.getHost());
                } else {
                    hosts.add("graph.microsoft.com");
                }
            }
            if (!hosts.isEmpty()) {
                merged.put("source.allowedHosts", String.join(",", hosts));
            }
        }
        if (type.equals("confluence")) {
            String spaces = merged.getOrDefault("source.confluence.spaces", "");
            if (!spaces.isBlank()) { // pasted space addresses are saved as their keys
                merged.put("source.confluence.spaces", String.join(",",
                        com.questioncrawler.core.source.ConfluenceOptions.csv(spaces).stream()
                                .map(com.questioncrawler.core.source.ConfluenceOptions::spaceKey).filter(k -> !k.isEmpty()).distinct().toList()));
            }
            String prefixes = merged.getOrDefault("source.allowedPathPrefixes", "").strip();
            if (prefixes.isEmpty() || prefixes.equals("/")) {
                String edition = merged.getOrDefault("source.confluence.edition", "cloud").strip().toLowerCase();
                boolean v2 = "v2".equalsIgnoreCase(merged.getOrDefault("source.confluence.apiVersion", "v1").strip());
                merged.put("source.allowedPathPrefixes", v2 ? "/wiki/api/v2/" : "server".equals(edition) ? "/rest/api/" : "/wiki/rest/api/");
            }
            String auth = merged.getOrDefault("source.auth.method", "").strip();
            if (auth.isEmpty() || "none".equals(auth)) {
                String edition = merged.getOrDefault("source.confluence.edition", "cloud").strip().toLowerCase();
                merged.put("source.auth.method", "server".equals(edition) ? "token" : "basic-env");
            }
            if (merged.getOrDefault("source.auth.tokenEnv", "").isBlank() && !"tvm".equals(merged.get("source.auth.method"))
                    && !"browser".equals(merged.get("source.auth.method"))) {
                String edition = merged.getOrDefault("source.confluence.edition", "cloud").strip().toLowerCase();
                merged.put("source.auth.tokenEnv", "server".equals(edition) ? "CONFLUENCE_PAT" : "CONFLUENCE_API_TOKEN");
            }
            if (merged.getOrDefault("source.systemId", "").isBlank()) {
                merged.put("source.systemId", "confluence");
            }
            if (merged.getOrDefault("source.followRedirects", "").isBlank()) {
                merged.put("source.followRedirects", "same-host");
            }
        }
        if (type.equals("jive")) {
            String prefixes = merged.getOrDefault("source.allowedPathPrefixes", "").strip();
            if (prefixes.isEmpty() || prefixes.equals("/")) {
                merged.put("source.allowedPathPrefixes", "/api/core/v3/");
            }
            String auth = merged.getOrDefault("source.auth.method", "").strip();
            if (auth.isEmpty() || "none".equals(auth)) {
                merged.put("source.auth.method", "basic-env");
            }
            if (merged.getOrDefault("source.auth.tokenEnv", "").isBlank()) {
                String method = merged.getOrDefault("source.auth.method", "basic-env");
                merged.put("source.auth.tokenEnv", "token".equals(method) ? "JIVE_ACCESS_TOKEN" : "JIVE_PASSWORD");
            }
            if (merged.getOrDefault("source.systemId", "").isBlank()) {
                merged.put("source.systemId", "jive");
            }
            if (merged.getOrDefault("source.followRedirects", "").isBlank()) {
                merged.put("source.followRedirects", "same-host");
            }
        }
        if (type.equals("sharepoint")) {
            String prefixes = merged.getOrDefault("source.allowedPathPrefixes", "").strip();
            if (prefixes.isEmpty() || prefixes.equals("/")) {
                merged.put("source.allowedPathPrefixes", "/v1.0/");
            }
            String auth = merged.getOrDefault("source.auth.method", "").strip();
            if (auth.isEmpty() || "none".equals(auth)) {
                merged.put("source.auth.method", "token");
            }
            if (merged.getOrDefault("source.auth.tokenEnv", "").isBlank()) {
                merged.put("source.auth.tokenEnv", "SHAREPOINT_ACCESS_TOKEN");
            }
            if (merged.getOrDefault("source.sharepoint.graphBaseUrl", "").isBlank()) {
                merged.put("source.sharepoint.graphBaseUrl", "https://graph.microsoft.com/v1.0");
            }
            if (merged.getOrDefault("source.sharepoint.contentMode", "").isBlank()) {
                merged.put("source.sharepoint.contentMode", "pages");
            }
            if (merged.getOrDefault("source.systemId", "").isBlank()) {
                merged.put("source.systemId", "sharepoint");
            }
            if (merged.getOrDefault("source.followRedirects", "").isBlank()) {
                merged.put("source.followRedirects", "same-host");
            }
            String hosts = merged.getOrDefault("source.allowedHosts", "");
            if (!hosts.toLowerCase().contains("graph.microsoft.com")) {
                merged.put("source.allowedHosts", hosts.isBlank() ? "graph.microsoft.com" : hosts + ",graph.microsoft.com");
            }
        }
        List<Issue> issues = validate(merged);
        boolean blocked = issues.stream().anyMatch(i -> i.level().equals("error"));
        if (blocked) {
            return new SaveResult(false, view(file, merged), issues);
        }
        try {
            AtomicFiles.write(file, render(merged));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot write " + file + ": " + e.getMessage(), e);
        }
        return new SaveResult(true, view(file, read(file)), issues);
    }
}
