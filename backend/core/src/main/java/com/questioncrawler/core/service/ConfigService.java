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
    private static final List<String> REMOTE = List.of("stackexchange", "live", "confluence", "jive");
    private static final List<String> AUTH_TOKEN = List.of("live", "confluence", "jive");
    private static final List<String> LIVE_CONFLUENCE_JIVE = List.of("live", "confluence", "jive");

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
                        f("source.type", "Source", "Demo data needs no internet. Stack Overflow / Stack Exchange uses the public API. Confluence collects wiki pages via its REST API. Jive collects community content via Core REST v3. Your internal Q&A site needs its own connector. SharePoint will be a separate source later.",
                                Type.SELECT, "fixture", null, ALL, false, null,
                                new Option("fixture", "Demo data", "Offline sample questions, nothing to set up"),
                                new Option("stackexchange", "Stack Overflow / Stack Exchange", "Public site via its official API"),
                                new Option("confluence", "Confluence", "Cloud or Server/DC wiki pages via REST (separate from Jive/SharePoint)"),
                                new Option("jive", "Jive", "Community content via Core REST API v3 (separate from Confluence/SharePoint)"),
                                new Option("live", "Our internal Q&A site", "Needs API documentation (Stack Exchange–style)")),
                        f("source.siteUrl", "Site address", "The home page of the Stack Exchange site, for example https://stackoverflow.com/.",
                                Type.URL, "https://stackoverflow.com/", "https://stackoverflow.com/", SE, false, null),
                        f("source.stackexchange.keyEnv", "API key variable name", "Optional. Name of the environment variable that holds a free Stack Exchange application key. A key raises the daily request limit from about 300 to 10,000. The key itself is never stored here.",
                                Type.ENV_NAME, "STACKEXCHANGE_KEY", "STACKEXCHANGE_KEY", SE, false, null),
                        f("api.swaggerUrl", "API documentation (Swagger) address", "The page that lists the site's REST API, for example https://so.mycompany.com/swagger/index.html. We read it to find every endpoint, the API address and how the access key is sent.",
                                Type.URL, "", "https://so.mycompany.com/swagger/index.html", LIVE, false, null),
                        f("api.swaggerFile", "API documentation file (JSON)", "Use this when the documentation page needs a company sign-in (SSO). Open the documentation's JSON address in your browser (where you are signed in), save it as swagger.json, and upload it on the Connect screen: its location is filled in here.",
                                Type.PATH, "", "C:/data/swagger.json", LIVE, false, null),
                        f("source.baseUrl", "Site / API address", "For Confluence: the site origin (https://acme.atlassian.net). For Jive: the community origin (https://community.example.com). For an internal Q&A API: the address every call starts with (or leave empty to take it from Swagger).",
                                Type.URL, "", "https://community.example.com", LIVE_CONFLUENCE_JIVE, false, null),
                        f("source.confluence.edition", "Confluence edition", "Cloud uses /wiki/rest/api and email + API token (Basic). Server/Data Center uses /rest/api and a personal access token (Bearer).",
                                Type.SELECT, "cloud", null, CONFLUENCE, false, null,
                                new Option("cloud", "Confluence Cloud", "Atlassian Cloud site"),
                                new Option("server", "Server / Data Center", "Self-hosted Confluence")),
                        f("source.confluence.email", "Atlassian account email", "Cloud only. The email for Basic auth with your API token. Not a secret; the token stays in an environment variable.",
                                Type.TEXT, "", "you@example.com", CONFLUENCE, false, null),
                        f("source.confluence.emailEnv", "Email variable name (optional)", "Optional alternative: NAME of an environment variable that holds the Atlassian email instead of typing it above.",
                                Type.ENV_NAME, "", "CONFLUENCE_EMAIL", CONFLUENCE, true, null),
                        f("source.confluence.spaces", "Space keys", "Comma-separated Confluence space keys to include (for example ENG,HR). Strongly recommended so sync stays bounded.",
                                Type.TAGS, "", "ENG", CONFLUENCE, false, null),
                        f("source.confluence.ancestorPageId", "Ancestor page id", "Optional. Only collect pages under this page id (a subtree).",
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
                        f("source.systemId", "Storage system id", "Folder name under threads/ for this site. Defaults: confluence or jive depending on the source type.",
                                Type.TEXT, "", "jive", List.of("confluence", "jive"), true, null),
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
                        f("source.auth.method", "How to sign in", "Confluence Cloud: Email + API token (Basic). Confluence Server/DC: Bearer PAT. Jive: username + password (Basic) or OAuth Bearer access token. Internal Q&A: Bearer only when the API asks for it in addition to a key.",
                                Type.SELECT, "none", null, AUTH_TOKEN, false, null,
                                new Option("none", "Not needed", "Only for some internal Q&A APIs"),
                                new Option("basic-env", "Username/email + secret (Basic)", "Confluence Cloud email+token, or Jive username+password from env"),
                                new Option("token", "Bearer token / PAT / OAuth", "Confluence Server/DC PAT, Jive OAuth access token, or internal Bearer")),
                        f("source.auth.tokenEnv", "Token / password variable name", "The NAME of the environment variable that holds the secret (never the secret itself). Suggested: CONFLUENCE_API_TOKEN / CONFLUENCE_PAT, JIVE_PASSWORD / JIVE_ACCESS_TOKEN.",
                                Type.ENV_NAME, "JIVE_PASSWORD", "JIVE_PASSWORD", AUTH_TOKEN, false, null),
                        f("source.auth.headerName", "Auth header name", "The HTTP header that carries the credential. Almost always Authorization.",
                                Type.TEXT, "Authorization", "Authorization", AUTH_TOKEN, true, null),
                        f("source.auth.scheme", "Bearer prefix", "For Bearer/PAT auth only. Ignored for Basic (email + API token).",
                                Type.TEXT, "Bearer", "Bearer", AUTH_TOKEN, true, null),
                        f("source.allowedHosts", "Allowed hosts", "The app only ever talks to these hosts. Filled in automatically from the address above.",
                                Type.TAGS, "", "community.example.com", LIVE_CONFLUENCE_JIVE, true, null),
                        f("source.allowedPathPrefixes", "Allowed paths", "The app only requests addresses that start with one of these paths. Confluence Cloud default is /wiki/rest/api/; Jive default is /api/core/v3/.",
                                Type.TAGS, "/", "/api/core/v3/", LIVE_CONFLUENCE_JIVE, true, null),
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
                        f("sync.tags", "Tags", "Press Enter after each tag. For Stack Exchange these are question tags; for Confluence they are page labels; for Jive they are content tags (case-insensitive). Type all (or *) only with a Confluence space/ancestor, a Jive place, or a time window — unbounded whole-site sync is refused.",
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
        if (!List.of("fixture", "stackexchange", "live", "confluence", "jive").contains(type)) {
            issues.add(new Issue("source.type", "error", "Choose one of the listed sources.", "Pick Demo data, Stack Overflow / Stack Exchange, Confluence, Jive, or Our internal Q&A site."));
        }
        if (type.equals("stackexchange")) {
            String url = v.getOrDefault("source.siteUrl", "");
            URI uri = parseUri(url);
            if (uri == null || uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                issues.add(new Issue("source.siteUrl", "error", "That does not look like a secure web address.", "Use the full address, for example https://stackoverflow.com/"));
            }
            checkEnvName(issues, "source.stackexchange.keyEnv", v.get("source.stackexchange.keyEnv"), false);
        }
        if (type.equals("live") || type.equals("confluence") || type.equals("jive")) {
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
            } else if (type.equals("confluence") && url.isBlank()) {
                issues.add(new Issue("source.baseUrl", "error", "Enter the Confluence site address.", "For Cloud use https://your-site.atlassian.net — not a page URL inside the wiki."));
            } else if (type.equals("jive") && url.isBlank()) {
                issues.add(new Issue("source.baseUrl", "error", "Enter the Jive community address.", "Use the site origin, for example https://community.example.com — not a single content URL."));
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
                        || ("cloud".equals(edition) && !"token".equals(authMethod) && !"none".equals(authMethod))) {
                    String email = v.getOrDefault("source.confluence.email", "").strip();
                    String emailEnv = v.getOrDefault("source.confluence.emailEnv", "").strip();
                    if (email.isEmpty() && emailEnv.isEmpty() && ("basic-env".equals(authMethod) || "confluence-api-token".equals(authMethod)
                            || "cloud".equals(edition))) {
                        if ("basic-env".equals(authMethod) || "confluence-api-token".equals(authMethod) || "cloud".equals(edition)) {
                            issues.add(new Issue("source.confluence.email", "error", "Confluence Cloud Basic auth needs your Atlassian email.", "Enter the email, or set source.confluence.emailEnv to an environment variable name."));
                        }
                    }
                    checkEnvName(issues, "source.confluence.emailEnv", emailEnv, false);
                }
                String spaces = v.getOrDefault("source.confluence.spaces", "").strip();
                String ancestor = v.getOrDefault("source.confluence.ancestorPageId", "").strip();
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
                String ancestor = v.getOrDefault("source.confluence.ancestorPageId", "").strip();
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
        if ((type.equals("live") || type.equals("confluence") || type.equals("jive")) && merged.getOrDefault("source.allowedHosts", "").isBlank()) {
            java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
            for (String key : new String[] {"source.baseUrl", "api.swaggerUrl"}) {
                URI uri = parseUri(merged.get(key));
                if (uri != null && uri.getHost() != null) {
                    hosts.add(uri.getHost());
                }
            }
            if (!hosts.isEmpty()) {
                merged.put("source.allowedHosts", String.join(",", hosts));
            }
        }
        if (type.equals("confluence")) {
            String prefixes = merged.getOrDefault("source.allowedPathPrefixes", "").strip();
            if (prefixes.isEmpty() || prefixes.equals("/")) {
                String edition = merged.getOrDefault("source.confluence.edition", "cloud").strip().toLowerCase();
                merged.put("source.allowedPathPrefixes", "server".equals(edition) ? "/rest/api/" : "/wiki/rest/api/");
            }
            String auth = merged.getOrDefault("source.auth.method", "").strip();
            if (auth.isEmpty() || "none".equals(auth)) {
                String edition = merged.getOrDefault("source.confluence.edition", "cloud").strip().toLowerCase();
                merged.put("source.auth.method", "server".equals(edition) ? "token" : "basic-env");
            }
            if (merged.getOrDefault("source.auth.tokenEnv", "").isBlank()) {
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
