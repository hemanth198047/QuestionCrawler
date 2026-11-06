package com.questioncrawler.core.service;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.ConfluenceOptions;
import com.questioncrawler.core.source.JiveAdapter;
import com.questioncrawler.core.source.JiveOptions;
import com.questioncrawler.core.source.FixtureSourceAdapter;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.SourceAdapter;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/** Typed view of the .properties configuration. No secrets live here: only the NAME of the token variable. */
public record CoreConfig(
        Path configFile,
        Path storageRoot,
        Path taxonomyFile,
        String sourceType,
        SourceSettings source,
        List<Path> fixtureDirs,
        int fixturePageSize,
        List<String> tags,
        TagMode tagMode,
        boolean partialTags,
        Duration overlap,
        int concurrency,
        int maxAttempts,
        int retentionDays,
        boolean schedulerEnabled,
        Duration schedulerInterval,
        int staleAfterHours,
        String maxQuestionAge,
        boolean explicitKeyName,
        ConfluenceOptions confluence,
        JiveOptions jive) {

    public static Path defaultStorageRoot() {
        return Path.of(System.getProperty("user.home"), ".questioncrawler", "data");
    }

    /** Resolution order: explicit path, QUESTIONCRAWLER_CONFIG, ./questioncrawler.properties, ~/.questioncrawler/config.properties. */
    public static Path locate(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String env = System.getenv("QUESTIONCRAWLER_CONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path local = Path.of("questioncrawler.properties");
        if (Files.exists(local)) {
            return local;
        }
        Path home = Path.of(System.getProperty("user.home"), ".questioncrawler", "config.properties");
        return Files.exists(home) ? home : null;
    }

    /** Loads the file (may be null for pure defaults); non-null overrides win over file values. */
    public static CoreConfig load(Path file, Map<String, String> overrides) {
        Properties p = new Properties();
        if (file != null) {
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("Configuration file not found: " + file + ". Run 'init' to create one.");
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read configuration " + file + ": " + e.getMessage(), e);
            }
        }
        if (overrides != null) {
            overrides.forEach(p::setProperty);
        }
        Path base = file == null ? Path.of("").toAbsolutePath() : file.toAbsolutePath().getParent();

        String root = p.getProperty("storage.root", "").strip();
        Path storage = root.isEmpty() ? defaultStorageRoot() : resolve(base, root);
        String taxonomy = p.getProperty("taxonomy.file", "").strip();
        Path taxonomyFile = taxonomy.isEmpty() ? null : resolve(base, taxonomy);
        String type = p.getProperty("source.type", "fixture").strip().toLowerCase();
        String site = p.getProperty("source.stackexchange.site", "").strip();
        if (type.equals("live")) {
            site = p.getProperty("api.site", "").strip();
        }
        if (site.isEmpty() && !type.equals("live")) {
            site = siteFromUrl(p.getProperty("source.siteUrl", "https://stackoverflow.com/").strip());
        }
        boolean stackExchange = type.equals("stackexchange");
        boolean confluenceSource = type.equals("confluence");
        String apiBase = p.getProperty("source.stackexchange.apiBaseUrl", "https://api.stackexchange.com/2.3").strip();
        String hosts = p.getProperty("source.allowedHosts", "");
        String prefixes = p.getProperty("source.allowedPathPrefixes", "/");
        String systemId = p.getProperty("source.systemId", "").strip();
        String baseUrl = p.getProperty("source.baseUrl", "").strip();
        boolean liveApi = type.equals("live");
        String swagger = p.getProperty("api.swaggerUrl", "").strip();
        ConfluenceOptions confluence = new ConfluenceOptions(
                p.getProperty("source.confluence.edition", "cloud"),
                p.getProperty("source.confluence.apiRoot", ""),
                ConfluenceOptions.csv(p.getProperty("source.confluence.spaces", "")),
                p.getProperty("source.confluence.ancestorPageId", ""),
                ConfluenceOptions.csv(p.getProperty("source.confluence.contentTypes", "page")),
                p.getProperty("source.confluence.bodyExpand", "body.storage"),
                Boolean.parseBoolean(p.getProperty("source.confluence.collectComments", "false").strip()),
                intValue(p, "source.confluence.pageSize", 25),
                Boolean.parseBoolean(p.getProperty("source.confluence.includeSpaceTag", "true").strip()),
                p.getProperty("source.confluence.email", ""),
                p.getProperty("source.confluence.emailEnv", ""));
        boolean jiveSource = type.equals("jive");
        JiveOptions jive = new JiveOptions(
                p.getProperty("source.jive.apiRoot", ""),
                JiveOptions.csv(p.getProperty("source.jive.places", "")),
                JiveOptions.csv(p.getProperty("source.jive.contentTypes", "document,discussion,question")),
                Boolean.parseBoolean(p.getProperty("source.jive.collectComments", "false").strip()),
                intValue(p, "source.jive.pageSize", 25),
                Boolean.parseBoolean(p.getProperty("source.jive.includePlaceTag", "true").strip()),
                p.getProperty("source.jive.username", ""),
                p.getProperty("source.jive.usernameEnv", ""));
        if (liveApi || confluenceSource || jiveSource) {
            // The API address (and Swagger, for live) may be on different hosts: allow those, nothing else.
            java.util.LinkedHashSet<String> allowed = new java.util.LinkedHashSet<>(csv(hosts));
            for (String url : new String[] {baseUrl, liveApi ? swagger : ""}) {
                try {
                    String h = url.isBlank() ? null : java.net.URI.create(url).getHost();
                    if (h != null) {
                        allowed.add(h);
                    }
                } catch (IllegalArgumentException ignored) {
                    // validation reports it
                }
            }
            hosts = String.join(",", allowed);
        }
        if (stackExchange) {
            baseUrl = apiBase;
            if (hosts.isBlank()) {
                hosts = java.net.URI.create(apiBase).getHost();   // derived from the explicit API base URL
            }
            if (prefixes.isBlank() || prefixes.strip().equals("/")) {
                prefixes = java.net.URI.create(apiBase).getPath() + "/";
            }
            if (systemId.isEmpty()) {
                systemId = site;
            }
        } else if (confluenceSource) {
            if (systemId.isEmpty()) {
                systemId = "confluence";
            }
            if ((prefixes.isBlank() || prefixes.strip().equals("/")) && !baseUrl.isBlank()) {
                prefixes = confluence.defaultApiRootPath() + "/";
            }
        } else if (jiveSource) {
            if (systemId.isEmpty()) {
                systemId = "jive";
            }
            if ((prefixes.isBlank() || prefixes.strip().equals("/")) && !baseUrl.isBlank()) {
                prefixes = jive.defaultApiRootPath() + "/";
            }
        } else if (systemId.isEmpty()) {
            systemId = "internal-qa";
        }

        String authMethodDefault = confluenceSource
                ? (confluence.cloud() ? "basic-env" : "token")
                : (jiveSource ? "basic-env" : "none");
        String authMethod = p.getProperty("source.auth.method", authMethodDefault).strip();
        String tokenEnvDefault = confluenceSource
                ? (confluence.cloud() ? "CONFLUENCE_API_TOKEN" : "CONFLUENCE_PAT")
                : (jiveSource ? "JIVE_PASSWORD" : "INTERNAL_QA_TOKEN");
        String followRedirects = p.getProperty("source.followRedirects",
                (confluenceSource || jiveSource) ? "same-host" : "all");
        String ca = p.getProperty("source.caCertFile", "").strip();
        String authUser = confluenceSource ? confluence.email() : (jiveSource ? jive.username() : null);
        String authUserEnv = confluenceSource
                ? (confluence.emailEnv().isBlank() ? null : confluence.emailEnv())
                : (jiveSource ? (jive.usernameEnv().isBlank() ? null : jive.usernameEnv()) : null);
        SourceSettings settings = new SourceSettings(
                systemId,
                baseUrl,
                csv(hosts),
                csv(prefixes),
                authMethod,
                p.getProperty("source.auth.tokenEnv", tokenEnvDefault).strip(),
                p.getProperty("source.auth.headerName", "Authorization").strip(),
                p.getProperty("source.auth.scheme", "Bearer").strip(),
                ca.isEmpty() ? null : resolve(base, ca),
                Duration.ofSeconds(intValue(p, "source.timeoutSeconds", 30)),
                Boolean.parseBoolean(p.getProperty("source.allowInsecureHttp", "false").strip()),
                site,
                liveApi ? p.getProperty("api.keyEnv", "").strip() : p.getProperty("source.stackexchange.keyEnv", "STACKEXCHANGE_KEY").strip(),
                com.questioncrawler.core.source.ProxyConfig.parse(p.getProperty("network.proxyHost", ""), p.getProperty("network.proxyPort", ""),
                        p.getProperty("network.noProxy", ""), p.getProperty("network.proxyUserEnv", ""), p.getProperty("network.proxyPasswordEnv", "")),
                new com.questioncrawler.core.source.ApiOptions(p.getProperty("api.keyName", ""), p.getProperty("api.keyIn", "query"),
                        p.getProperty("api.accessTokenEnv", ""), p.getProperty("api.bodyFilter", ""),
                        Boolean.parseBoolean(p.getProperty("api.collectComments", "false").strip()), swagger, p.getProperty("api.swaggerFile", ""),
                        followRedirects),
                authUser,
                authUserEnv);

        List<Path> fixtureDirs = csv(p.getProperty("source.fixture.dirs", "")).stream().map(s -> resolve(base, s)).toList();
        return new CoreConfig(file, storage, taxonomyFile, type, settings, fixtureDirs,
                intValue(p, "source.fixture.pageSize", 2),
                csv(p.getProperty("sync.tags", "")),
                TagMode.valueOf(p.getProperty("sync.tagMode", "ANY").strip().toUpperCase()),
                Boolean.parseBoolean(p.getProperty("sync.partialTags", "false").strip()),
                Duration.ofMinutes(intValue(p, "sync.overlapMinutes", 60)),
                intValue(p, "sync.concurrency", (confluenceSource || jiveSource) ? 2 : 4),
                intValue(p, "sync.maxAttempts", 5),
                intValue(p, "sync.retentionDays", 30),
                Boolean.parseBoolean(p.getProperty("sync.scheduler.enabled", "false").strip()),
                Duration.ofMinutes(intValue(p, "sync.scheduler.intervalMinutes", 360)),
                intValue(p, "status.staleAfterHours", 168),
                p.getProperty("sync.maxQuestionAge", "").strip().isEmpty() ? null : p.getProperty("sync.maxQuestionAge").strip(),
                !p.getProperty("api.keyName", "").isBlank(),
                confluence,
                jive);
    }

    public static CoreConfig forStorage(Path storageRoot) {
        return load(null, Map.of("storage.root", storageRoot.toString()));
    }

    // ------------------------------------------------------------------ behaviour

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (!List.of("fixture", "live", "stackexchange", "confluence", "jive").contains(sourceType)) {
            problems.add("source.type must be 'fixture', 'live', 'stackexchange', 'confluence' or 'jive' (was '" + sourceType + "')");
        }
        if (maxQuestionAge != null && !com.questioncrawler.core.util.AgeSpec.isValid(maxQuestionAge)) {
            problems.add("sync.maxQuestionAge must look like 30d, 12h, 2w, 6m or 1y (was '" + maxQuestionAge + "')");
        }
        if (sourceType.equals("stackexchange")) {
            if (source.site() == null || source.site().isBlank()) {
                problems.add("Could not determine the Stack Exchange site; set source.siteUrl or source.stackexchange.site");
            }
            try {
                java.net.URI api = java.net.URI.create(source.baseUrl());
                if (!"https".equalsIgnoreCase(api.getScheme()) && !source.allowInsecureHttp()) {
                    problems.add("source.stackexchange.apiBaseUrl must use https");
                }
                if (source.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(api.getHost()))) {
                    problems.add("source.allowedHosts must include the API host '" + api.getHost() + "'");
                }
            } catch (IllegalArgumentException e) {
                problems.add("source.stackexchange.apiBaseUrl is not a valid URL");
            }
        }
        if (tags.isEmpty()) {
            problems.add("sync.tags is empty: at least one target tag is required to run 'sync'");
        }
        if (sourceType.equals("live") || sourceType.equals("confluence") || sourceType.equals("jive")) {
            if (sourceType.equals("live") && source.baseUrl().isBlank() && !source.api().hasDocs()) {
                problems.add("Give the API address (source.baseUrl) or the API documentation address (api.swaggerUrl) for source.type=live");
            } else if (sourceType.equals("confluence") && source.baseUrl().isBlank()) {
                problems.add("source.baseUrl is required for Confluence (for example https://acme.atlassian.net)");
            } else if (sourceType.equals("jive") && source.baseUrl().isBlank()) {
                problems.add("source.baseUrl is required for Jive (for example https://community.example.com)");
            } else if (!source.baseUrl().isBlank()) {
                try {
                    java.net.URI uri = java.net.URI.create(source.baseUrl());
                    if (uri.getHost() == null) {
                        problems.add("source.baseUrl has no host");
                    } else if (source.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(uri.getHost()))) {
                        problems.add("source.allowedHosts must include the base URL host '" + uri.getHost() + "'");
                    }
                    if (!"https".equalsIgnoreCase(uri.getScheme()) && !source.allowInsecureHttp()) {
                        problems.add("source.baseUrl must use https (source.allowInsecureHttp is for local testing only)");
                    }
                } catch (IllegalArgumentException e) {
                    problems.add("source.baseUrl is not a valid URL");
                }
            }
            if ((source.authMethod().equalsIgnoreCase("token")
                    || source.authMethod().equalsIgnoreCase("basic-env")
                    || source.authMethod().equalsIgnoreCase("confluence-api-token"))
                    && source.tokenEnv().isBlank()) {
                problems.add("source.auth.tokenEnv must name the environment variable that holds the token");
            }
            if (sourceType.equals("confluence")) {
                if (!List.of("cloud", "server").contains(confluence.edition())) {
                    problems.add("source.confluence.edition must be 'cloud' or 'server'");
                }
                if (source.authMethod().equalsIgnoreCase("basic-env")
                        || source.authMethod().equalsIgnoreCase("confluence-api-token")) {
                    if ((source.authUser() == null || source.authUser().isBlank())
                            && (source.authUserEnv() == null || source.authUserEnv().isBlank())) {
                        problems.add("Confluence Cloud Basic auth needs source.confluence.email or source.confluence.emailEnv");
                    }
                }
                if (TagFilter.asksForAll(tags) && !confluence.hasBoundedScope() && maxQuestionAge == null) {
                    problems.add("Confluence tag 'all' needs a space, ancestor page, or sync.maxQuestionAge window (refusing unbounded site sync)");
                }
            }
            if (sourceType.equals("jive")) {
                if (source.authMethod().equalsIgnoreCase("basic-env")) {
                    if ((source.authUser() == null || source.authUser().isBlank())
                            && (source.authUserEnv() == null || source.authUserEnv().isBlank())) {
                        problems.add("Jive Basic auth needs source.jive.username or source.jive.usernameEnv");
                    }
                }
                if (TagFilter.asksForAll(tags) && !jive.hasBoundedScope() && maxQuestionAge == null) {
                    problems.add("Jive tag 'all' needs a place id or sync.maxQuestionAge window (refusing unbounded site sync)");
                }
            }
            if (source.caCertFile() != null && !Files.isReadable(source.caCertFile())) {
                problems.add("source.caCertFile is not readable: " + source.caCertFile());
            }
        }
        if (taxonomyFile != null && !Files.isReadable(taxonomyFile)) {
            problems.add("taxonomy.file is not readable: " + taxonomyFile);
        }
        if (concurrency < 1 || concurrency > 16) {
            problems.add("sync.concurrency must be between 1 and 16");
        }
        return problems;
    }

    public Taxonomy loadTaxonomy() {
        return taxonomyFile != null && Files.isReadable(taxonomyFile) ? Taxonomy.load(taxonomyFile) : Taxonomy.loadDefault();
    }

    public SourceAdapter createAdapter() {
        return createAdapter(System::getenv);
    }

    public SourceAdapter createAdapter(Function<String, String> env) {
        if (sourceType.equals("live")) {
            return createInternalApiAdapter(env);
        }
        if (sourceType.equals("stackexchange")) {
            return new com.questioncrawler.core.source.StackExchangeAdapter(source, new HttpSourceClient(source));
        }
        if (sourceType.equals("confluence")) {
            // Dedicated adapter — do not route through StackExchangeAdapter or LiveSourceAdapter.
            return new ConfluenceAdapter(source, confluence, new HttpSourceClient(source, env), env);
        }
        if (sourceType.equals("jive")) {
            // Dedicated adapter — separate from Confluence / SharePoint / Stack Exchange.
            return new JiveAdapter(source, jive, new HttpSourceClient(source, env), env);
        }
        if (fixtureDirs.isEmpty()) {
            return FixtureSourceAdapter.fromClasspath(fixturePageSize, fixturePageSize, "fixtures/demo-site");
        }
        return FixtureSourceAdapter.fromDirectories(fixturePageSize, fixturePageSize, fixtureDirs.toArray(Path[]::new));
    }

    /**
     * The internal site's REST API (Stack Exchange 2.3 style). If a Swagger address is configured, the API's own documentation
     * supplies what is not set here: the API address, how the access key is sent, and which optional features exist.
     */
    private SourceAdapter createInternalApiAdapter(Function<String, String> env) {
        com.questioncrawler.core.source.SourceSettings working = source;
        com.questioncrawler.core.api.ApiCapabilities[] learned = new com.questioncrawler.core.api.ApiCapabilities[1];
        java.util.function.Supplier<com.questioncrawler.core.api.ApiCapabilities> provider = null;
        String suggestedPath = null;
        if (source.api().hasDocs()) {
            try {
                var discovery = com.questioncrawler.core.api.ApiDocsLoader.load(working, env);
                learned[0] = discovery.capabilities();
                String base = working.baseUrl();
                String suggested = discovery.catalog().suggestedBaseUrl();
                if (base.isBlank() && suggested != null) {
                    if (suggested.startsWith("http")) {
                        base = suggested;
                    } else if (source.api().swaggerUrl() != null) {
                        base = java.net.URI.create(source.api().swaggerUrl()).resolve(suggested).toString();
                    } else {
                        suggestedPath = suggested;
                    }
                }
                var api = working.api();
                var keyScheme = discovery.catalog().security().stream().filter(x -> "apiKey".equals(x.type()) && x.name() != null).findFirst();
                if (keyScheme.isPresent() && source.api().keyName().equals("key") && !explicitKeyName) {
                    api = new com.questioncrawler.core.source.ApiOptions(keyScheme.get().name(), keyScheme.get().in(), api.accessTokenEnv(),
                            api.bodyFilter(), api.collectComments(), api.swaggerUrl(), api.swaggerFile(), api.redirects());
                }
                working = working.withBase(base, api);
            } catch (com.questioncrawler.core.source.SourceException e) {
                // The documentation could not be read now; the standard feature set is assumed and the API address must be set.
                learned[0] = null;
            }
            provider = () -> learned[0];
        }
        if (working.baseUrl().isBlank()) {
            throw new IllegalStateException("The API address is not known. " + (suggestedPath != null
                    ? "The documentation says the API lives under '" + suggestedPath + "': enter your site's full address in front of it (for example https://your-site" + suggestedPath + "). "
                    : "Enter it in the settings, or make the API documentation readable ")
                    + "(run 'Check access' on the Connect screen to see why it cannot be read).");
        }
        return new com.questioncrawler.core.source.StackExchangeAdapter(working, new HttpSourceClient(working, env), env,
                com.questioncrawler.core.util.Sleeper.REAL, provider);
    }

    public SyncOptions syncOptions(List<String> tagOverride, TagMode modeOverride, Boolean partialOverride, SyncMode mode) {
        return syncOptions(tagOverride, modeOverride, partialOverride, mode, null);
    }

    /**
     * {@code maxQuestionAgeOverride} (for example "30d") limits collection to recently created questions; "all" means no limit even if the
     * saved settings have a window; null keeps the saved window.
     */
    public SyncOptions syncOptions(List<String> tagOverride, TagMode modeOverride, Boolean partialOverride, SyncMode mode,
                                   String maxQuestionAgeOverride) {
        String age = maxQuestionAgeOverride == null ? maxQuestionAge
                : com.questioncrawler.core.util.AgeSpec.isEverything(maxQuestionAgeOverride) ? null : maxQuestionAgeOverride;
        String scopeExtra = sourceType.equals("confluence") && confluence != null ? confluence.scopeFragment()
                : (sourceType.equals("jive") && jive != null ? jive.scopeFragment() : null);
        return new SyncOptions(tagOverride == null || tagOverride.isEmpty() ? tags : tagOverride,
                modeOverride == null ? tagMode : modeOverride,
                partialOverride == null ? partialTags : partialOverride,
                mode, overlap, concurrency, retentionDays, age, scopeExtra);
    }

    /** Writes the commented configuration template with the given storage root. */
    public static String template(Path storageRoot) {
        try (InputStream in = CoreConfig.class.getClassLoader().getResourceAsStream("defaults/questioncrawler.properties")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.replace("@STORAGE_ROOT@", storageRoot.toString().replace('\\', '/'));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Path resolve(Path base, String value) {
        Path p = Path.of(value.startsWith("~") ? System.getProperty("user.home") + value.substring(1) : value);
        return p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
    }

    /** stackoverflow.com -> stackoverflow; unix.stackexchange.com -> unix; serverfault.com -> serverfault. */
    static String siteFromUrl(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase().startsWith("www.") ? host.substring(4) : host.toLowerCase();
            if (host.endsWith(".stackexchange.com")) {
                return host.substring(0, host.length() - ".stackexchange.com".length());
            }
            return host.endsWith(".com") ? host.substring(0, host.length() - 4) : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static List<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static int intValue(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration value " + key + " must be an integer: " + v);
        }
    }
}
