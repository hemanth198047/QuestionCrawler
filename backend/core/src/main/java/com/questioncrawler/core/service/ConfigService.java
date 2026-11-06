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
    private static final List<String> REMOTE = List.of("stackexchange", "live");

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
                        f("source.type", "Source", "Demo data needs no internet. Stack Overflow / Stack Exchange uses the public, documented API. Your internal site needs its own connector.",
                                Type.SELECT, "fixture", null, ALL, false, null,
                                new Option("fixture", "Demo data", "Offline sample questions, nothing to set up"),
                                new Option("stackexchange", "Stack Overflow / Stack Exchange", "Public site via its official API"),
                                new Option("live", "Our internal site", "Needs API documentation (not built yet)")),
                        f("source.siteUrl", "Site address", "The home page of the Stack Exchange site, for example https://stackoverflow.com/.",
                                Type.URL, "https://stackoverflow.com/", "https://stackoverflow.com/", SE, false, null),
                        f("source.stackexchange.keyEnv", "API key variable name", "Optional. Name of the environment variable that holds a free Stack Exchange application key. A key raises the daily request limit from about 300 to 10,000. The key itself is never stored here.",
                                Type.ENV_NAME, "STACKEXCHANGE_KEY", "STACKEXCHANGE_KEY", SE, false, null),
                        f("api.swaggerUrl", "API documentation (Swagger) address", "The page that lists the site's REST API, for example https://so.mycompany.com/swagger/index.html. We read it to find every endpoint, the API address and how the access key is sent.",
                                Type.URL, "", "https://so.mycompany.com/swagger/index.html", LIVE, false, null),
                        f("api.swaggerFile", "API documentation file (JSON)", "Use this when the documentation page needs a company sign-in (SSO). Open the documentation's JSON address in your browser (where you are signed in), save it as swagger.json, and upload it on the Connect screen: its location is filled in here.",
                                Type.PATH, "", "C:/data/swagger.json", LIVE, false, null),
                        f("source.baseUrl", "API address", "The address every API call starts with, for example https://so.mycompany.com/api/2.3. Leave it empty to take it from the Swagger documentation.",
                                Type.URL, "", "https://so.mycompany.com/api/2.3", LIVE, false, null),
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
                        f("source.auth.method", "Bearer token sign-in", "Choose 'Bearer token' if the API answers 'Missing bearer token' (HTTP 401): it then also wants a token in an 'Authorization: Bearer' header, in addition to the API key.",
                                Type.SELECT, "none", null, LIVE, false, null,
                                new Option("none", "Not needed", "The API key is enough"),
                                new Option("token", "Bearer token", "A token from your profile or the site's owners")),
                        f("source.auth.tokenEnv", "Bearer token variable name", "The NAME of the environment variable that holds the token (the token itself is never saved). Set that variable in the terminal that starts the app.",
                                Type.ENV_NAME, "INTERNAL_QA_TOKEN", "INTERNAL_QA_TOKEN", LIVE, false, null),
                        f("source.auth.headerName", "Bearer header name", "The HTTP header the site expects the Bearer token in.",
                                Type.TEXT, "Authorization", "Authorization", LIVE, true, null),
                        f("source.auth.scheme", "Bearer prefix", "Text placed before the token, for example Bearer. Leave empty for none.",
                                Type.TEXT, "Bearer", "Bearer", LIVE, true, null),
                        f("source.allowedHosts", "Allowed hosts", "The app only ever talks to these hosts. Filled in automatically from the address above.",
                                Type.TAGS, "", "qa.mycompany.com", LIVE, true, null),
                        f("source.allowedPathPrefixes", "Allowed paths", "The app only requests addresses that start with one of these paths.",
                                Type.TAGS, "/", "/api/", LIVE, true, null),
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
                        f("sync.tags", "Tags", "Press Enter after each tag. 'Artificial Intelligence' and 'artificial-intelligence' mean the same tag.",
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
        if (!List.of("fixture", "stackexchange", "live").contains(type)) {
            issues.add(new Issue("source.type", "error", "Choose one of the listed sources.", "Pick Demo data, Stack Overflow / Stack Exchange, or Our internal site."));
        }
        if (type.equals("stackexchange")) {
            String url = v.getOrDefault("source.siteUrl", "");
            URI uri = parseUri(url);
            if (uri == null || uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                issues.add(new Issue("source.siteUrl", "error", "That does not look like a secure web address.", "Use the full address, for example https://stackoverflow.com/"));
            }
            checkEnvName(issues, "source.stackexchange.keyEnv", v.get("source.stackexchange.keyEnv"), false);
        }
        if (type.equals("live")) {
            String url = v.getOrDefault("source.baseUrl", "");
            String swagger = v.getOrDefault("api.swaggerUrl", "");
            URI uri = parseUri(url);
            URI docs = parseUri(swagger);
            boolean insecure = Boolean.parseBoolean(v.getOrDefault("source.allowInsecureHttp", "false"));
            String docsFile = v.getOrDefault("api.swaggerFile", "").strip();
            if (!docsFile.isEmpty() && !java.nio.file.Files.isReadable(Path.of(docsFile))) {
                issues.add(new Issue("api.swaggerFile", "error", "That documentation file cannot be read.", "Check the path, or upload the file again on the Connect screen."));
            }
            if (url.isBlank() && swagger.isBlank() && docsFile.isEmpty()) {
                issues.add(new Issue("api.swaggerUrl", "error", "Enter the API documentation (Swagger) address, or the API address.", "Ask the site owners for the link to their Swagger page; it usually ends in /swagger/index.html."));
            }
            if (!url.isBlank() && (uri == null || uri.getHost() == null)) {
                issues.add(new Issue("source.baseUrl", "error", "That does not look like a web address.", "Start with https:// and include the full host name."));
            } else if (!url.isBlank() && !"https".equalsIgnoreCase(uri.getScheme()) && !insecure) {
                issues.add(new Issue("source.baseUrl", "error", "Unencrypted http is not allowed for a real site.", "Use an https:// address."));
            }
            if (!swagger.isBlank() && (docs == null || docs.getHost() == null)) {
                issues.add(new Issue("api.swaggerUrl", "error", "That does not look like a web address.", "Start with https:// and include the full host name."));
            } else if (!swagger.isBlank() && !"https".equalsIgnoreCase(docs.getScheme()) && !insecure) {
                issues.add(new Issue("api.swaggerUrl", "error", "Unencrypted http is not allowed for a real site.", "Use an https:// address."));
            }
            checkEnvName(issues, "api.keyEnv", v.get("api.keyEnv"), true);
            checkEnvName(issues, "api.accessTokenEnv", v.get("api.accessTokenEnv"), false);
            if ("token".equals(v.get("source.auth.method"))) {
                checkEnvName(issues, "source.auth.tokenEnv", v.get("source.auth.tokenEnv"), true);
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
        if (type.equals("live") && merged.getOrDefault("source.allowedHosts", "").isBlank()) {
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
