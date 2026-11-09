package com.questioncrawler.core.service;

import com.questioncrawler.core.classification.Classifier;
import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.Classification;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.service.ConfigService.Issue;
import com.questioncrawler.core.service.ConfigService.SaveResult;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The running application's single point of truth: where the configuration file is, the current
 * {@link KnowledgeBase}, the sync runner, site diagnostics and category editing. Saving settings reloads
 * everything. Used by the web API (and available to any other front end).
 */
public final class Workspace {

    public record TaxonomyView(boolean custom, String file, String hash, Taxonomy taxonomy, List<String> problems) {}

    public record TryMatch(String categoryId, String name, double score, List<String> matchedRules) {}

    public record TryResult(List<TryMatch> matches, boolean fallback, String explanation) {}

    private static final String CONNECTION_STATE = "connection-check";

    private final Path configFile;
    private final Map<String, String> overrides;
    private final Function<String, String> env;
    private final Clock clock;
    private final ConfigService configService;
    private final SyncRunner runner;
    private volatile KnowledgeBase knowledgeBase;
    private volatile CoreConfig config;

    public Workspace(Path configFile, Map<String, String> overrides) {
        this(configFile, overrides, System::getenv, Clock.systemUTC());
    }

    public Workspace(Path configFile, Map<String, String> overrides, Function<String, String> env, Clock clock) {
        Path located = configFile != null ? configFile : CoreConfig.locate(null);
        this.configFile = (located != null ? located : Path.of(System.getProperty("user.home"), ".questioncrawler", "config.properties")).toAbsolutePath();
        this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        this.env = env;
        this.clock = clock;
        this.configService = new ConfigService(env);
        this.runner = new SyncRunner(this::kb);
        reload();
    }

    private volatile String loadError;

    /** Re-reads the settings. A broken file never prevents the app from opening: it falls back to defaults and says why. */
    public synchronized void reload() {
        CoreConfig loaded;
        String error = null;
        try {
            loaded = CoreConfig.load(Files.exists(configFile) ? configFile : null, overrides);
            knowledgeBase = new KnowledgeBase(loaded, null, null, clock);
        } catch (RuntimeException e) {
            error = "The settings file could not be used (" + e.getMessage() + "). Showing defaults: fix the highlighted settings and save.";
            loaded = CoreConfig.load(null, overrides);
            knowledgeBase = new KnowledgeBase(loaded, null, null, clock);
        }
        this.config = loaded;
        this.loadError = error;
    }

    public KnowledgeBase kb() { return knowledgeBase; }

    public CoreConfig config() { return config; }

    public Path configFile() { return configFile; }

    public SyncRunner runner() { return runner; }

    // ================================================================== configuration

    public ConfigService.ConfigView configView() {
        return configService.view(configFile, null);
    }

    public List<Issue> validate(Map<String, String> values) {
        Map<String, String> merged = configService.read(configFile);
        merged.putAll(values);
        return configService.validate(merged);
    }

    /** The settings file exactly as it would be written for these (unsaved) values. */
    public String previewConfig(Map<String, String> values) {
        Map<String, String> merged = configService.read(configFile);
        merged.putAll(values);
        return configService.render(merged);
    }

    public SaveResult saveConfig(Map<String, String> values) {
        SaveResult result = configService.save(configFile, values);
        if (result.saved()) {
            reload();
        }
        return result;
    }

    // ================================================================== connection check

    /** Checks the saved settings, or the settings being edited (draft values are NOT saved). */
    public ConnectionReport checkConnection(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        // Short links pasted as addresses (go/xyz, bit.ly/...) are checked as the address they lead to.
        Map<String, String> beforeLinks = new java.util.HashMap<>(v);
        Map<String, String> shortLinkNotes = new com.questioncrawler.core.source.ShortLinks(
                com.questioncrawler.core.source.ShortLinks.Options.from(v::get), env).expandSettings(v, new ArrayList<>(v.keySet()));
        String type = v.getOrDefault("source.type", "fixture");
        if (type.equals("links")) {
            return checkLinks(v);
        }
        List<String> tags = Arrays.stream(v.getOrDefault("sync.tags", "").split(",")).map(TagFilter::normalize).filter(t -> !t.isEmpty()).toList();
        String target;
        String site = "";
        String keyEnv = v.getOrDefault("source.stackexchange.keyEnv", "");
        if (type.equals("stackexchange")) {
            target = "https://api.stackexchange.com/2.3";
            site = CoreConfig.siteFromUrl(v.getOrDefault("source.siteUrl", "https://stackoverflow.com/"));
        } else {
            target = v.getOrDefault("source.baseUrl", "");
            if (type.equals("confluence")) {
                target = com.questioncrawler.core.source.ConfluenceOptions.siteBase(target);
            }
            if (type.equals("live")) {
                site = v.getOrDefault("api.site", "");
                keyEnv = v.getOrDefault("api.keyEnv", "");
            }
        }
        com.questioncrawler.core.source.ApiOptions apiOptions = apiOptions(v);
        String ca = v.getOrDefault("source.caCertFile", "").strip();
        int timeout = parseInt(v.get("source.timeoutSeconds"), 15);
        String authMethod = v.getOrDefault("source.auth.method",
                type.equals("sharepoint") ? "token"
                        : ((type.equals("confluence") || type.equals("jive")) ? "basic-env" : "none"));
        String authUser = type.equals("jive")
                ? v.getOrDefault("source.jive.username", "")
                : v.getOrDefault("source.confluence.email", "");
        String authUserEnv = type.equals("jive")
                ? v.getOrDefault("source.jive.usernameEnv", "")
                : v.getOrDefault("source.confluence.emailEnv", "");
        // The site sends visitors to a company login page and no sign-in is chosen yet: sign in through the browser
        // (the same as choosing "Sign in with my browser"), and tell the screen to select it so saving keeps it.
        boolean autoBrowser = false;
        boolean mayAutoBrowser = type.equals("confluence") // the internal Q&A site uses its REST API (no browser)
                && (authMethod.isBlank() || authMethod.equals("none") || autoBrowserFor(type, v));
        ConnectionReport report = null;
        ConnectionReport withoutBrowser = null;
        // Run the check; if its sign-in step finds a company login page (the same test that shows "the site sends
        // visitors to a sign-in page"), sign in through the browser and check again with that session.
        for (int attempt = 0; attempt < 2; attempt++) {
        SiteDiagnostics.Input input = new SiteDiagnostics.Input(type, target, site, tags, authMethod,
                v.getOrDefault("source.auth.tokenEnv", ""), keyEnv,
                ca.isEmpty() ? null : Path.of(ca), Boolean.parseBoolean(v.getOrDefault("source.allowInsecureHttp", "false")), Math.min(timeout, 30),
                proxyOf(v), apiOptions, authUser, authUserEnv);
        SiteDiagnostics diagnostics = new SiteDiagnostics(env, clock);
        if (type.equals("confluence")) {
            com.questioncrawler.core.source.ConfluenceOptions confluence;
            try {
                confluence = CoreConfig.load(null, v).confluence();
            } catch (RuntimeException e) {
                confluence = com.questioncrawler.core.source.ConfluenceOptions.DEFAULT;
            }
            String tvmStatus = null;
            if ("tvm".equalsIgnoreCase(authMethod)) {
                // Get a short-lived token from the Token Vending Machine and let the probes use it as a Bearer token.
                String token = null;
                if (!confluence.tvm().configured()) {
                    tvmStatus = "NOT configured: set source.confluence.tvm.url";
                } else {
                    try {
                        com.questioncrawler.core.source.TvmTokenProvider tvm = new com.questioncrawler.core.source.TvmTokenProvider(
                                confluence.tvm(), proxyOf(v).orEnvironment(env), java.time.Duration.ofSeconds(Math.min(timeout, 30)));
                        token = tvm.token();
                        tvmStatus = "token received from " + tvm.host() + " (value not shown; valid until " + tvm.expiresAt() + ")";
                    } catch (com.questioncrawler.core.source.SourceException e) {
                        tvmStatus = "FAILED: " + e.getMessage();
                    }
                }
                String tvmToken = token;
                String tokenName = "QC_CONFLUENCE_TVM_TOKEN";
                java.util.function.Function<String, String> baseEnv = env;
                java.util.function.Function<String, String> withToken = name -> tokenName.equals(name) ? tvmToken : baseEnv.apply(name);
                input = new SiteDiagnostics.Input(input.sourceType(), input.target(), input.site(), input.tags(), "token", tokenName,
                        input.keyEnv(), input.caCertFile(), input.allowInsecureHttp(), input.timeoutSeconds(), input.proxy(), input.api(),
                        input.authUser(), input.authUserEnv());
                diagnostics = new SiteDiagnostics(withToken, clock);
            }
            diagnostics.withConfluence(confluence, tvmStatus);
            if ("browser".equalsIgnoreCase(authMethod)) {
                // Sign in through the browser first (opens the company login page if there is no session yet).
                String status;
                String cookie = null;
                try {
                    var adapter = (com.questioncrawler.core.source.ConfluenceAdapter) CoreConfig.load(null, v).createAdapter(env);
                    status = adapter.signIn();
                    cookie = adapter.sessionCookie();
                } catch (com.questioncrawler.core.source.SourceException | RuntimeException e) {
                    status = "FAILED: " + e.getMessage();
                }
                diagnostics.withBrowserSession(status, cookie);
            }
        }
        report = diagnostics.run(input);
        if (attempt == 0 && mayAutoBrowser && !"browser".equalsIgnoreCase(authMethod) && accessNeedsLogin(report)) {
            withoutBrowser = report;
            authMethod = "browser";
            v.put("source.auth.method", "browser");
            autoBrowser = true;
            continue;
        }
        break;
        }
        if (autoBrowser && withoutBrowser != null && browserCouldNotBeUsed(report)) {
            // The automatic browser sign-in could not run here (no browser, no screen, ...): keep the full sign-in
            // analysis of the first check and add why the browser could not be used. Nothing is switched.
            report = withBrowserProblem(withoutBrowser, accessStage(report));
            autoBrowser = false;
        }
        Map<String, String> toApply = new java.util.LinkedHashMap<>();
        if (autoBrowser) {
            toApply.put("source.auth.method", "browser");
        }
        if (!shortLinkNotes.isEmpty()) {
            List<ConnectionReport.Fact> facts = new ArrayList<>();
            boolean allFound = true;
            for (Map.Entry<String, String> note : shortLinkNotes.entrySet()) {
                facts.add(new ConnectionReport.Fact(note.getKey(), note.getValue().replaceFirst("^Short link: ", "")));
                String now = v.get(note.getKey());
                if (now != null && !now.equals(beforeLinks.get(note.getKey()))) {
                    toApply.put(note.getKey(), now);
                } else {
                    allFound = false;
                }
            }
            List<ConnectionReport.Stage> stages = new ArrayList<>();
            stages.add(new ConnectionReport.Stage("shortlinks", "Short links", "Where do the short links lead?", allFound ? "PASS" : "WARN",
                    allFound ? "Short links were expanded: the real addresses are checked and put into the form (save to keep them)."
                            : "A short link could not be expanded; it is used as typed. Paste the address it leads to instead, or add your short-link service under Company network.",
                    facts, List.of(), 0));
            stages.addAll(report.stages());
            report = new ConnectionReport(report.checkedAt(), report.target(), report.sourceType(), report.verdict(), report.headline(), report.detail(),
                    report.score(), stages, report.route(), report.requirements(), report.settingsToApply());
        }
        if (!toApply.isEmpty()) {
            report = report.withSettingsToApply(toApply);
        }
        try {
            knowledgeBase.store().init();
            knowledgeBase.store().writeState(CONNECTION_STATE, report);
        } catch (RuntimeException ignored) {
            // the report is still returned even if it cannot be remembered
        }
        return report;
    }

    /**
     * The Links source: reads the first links now (expanding short links, and opening the browser sign-in when a site
     * asks for it), and says for each one where it led and whether its page could be read.
     */
    private ConnectionReport checkLinks(Map<String, String> v) {
        long t0 = System.nanoTime();
        List<ConnectionReport.Stage> stages = new ArrayList<>();
        List<ConnectionReport.Fact> facts = new ArrayList<>();
        int ok = 0;
        int total;
        String problem = null;
        try {
            CoreConfig linksConfig = CoreConfig.load(null, v);
            var adapter = (com.questioncrawler.core.source.LinksAdapter) linksConfig.createAdapter(env);
            var lo = linksConfig.links();
            facts.add(new ConnectionReport.Fact("Following links", lo.crawls()
                    ? lo.depth() + " click(s) deep, " + ("any".equals(lo.scope()) ? "to any site" : "section".equals(lo.scope()) ? "inside the start page's section" : "on the same site")
                    + ", at most " + lo.maxPages() + " pages (checked when you collect)"
                    : "off: only the listed pages are read"));
            List<String> all = adapter.links();
            total = all.size();
            if (total == 0) {
                problem = "There are no links to read yet. Add short links or page addresses, or collect another source first (its short links are read too).";
            } else {
                Map<String, String> tried = adapter.tryLinks(5);
                for (Map.Entry<String, String> e : tried.entrySet()) {
                    facts.add(new ConnectionReport.Fact(e.getKey(), e.getValue()));
                    if (e.getValue().startsWith("OK")) {
                        ok++;
                    }
                }
                if (total > tried.size()) {
                    facts.add(new ConnectionReport.Fact("Not tried now", (total - tried.size()) + " more link(s): they are read when you collect"));
                }
            }
        } catch (RuntimeException e) {
            total = 0;
            problem = "The links could not be read: " + e.getMessage();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String status = problem != null ? "FAIL" : ok == facts.stream().filter(f -> !f.label().equals("Not tried now") && !f.label().equals("Following links")).count() ? "PASS" : ok > 0 ? "WARN" : "FAIL";
        String summary = problem != null ? problem
                : ok + " of the first " + Math.min(total, 5) + " link(s) were read" + (status.equals("PASS") ? "." : "; see why the others failed below.");
        List<ConnectionReport.Need> needs = status.equals("PASS") ? List.of() : List.of(new ConnectionReport.Need(
                "Pages that open for you", "A page that needs your company login is read after you sign in in the browser window that opens. "
                + "Pages you cannot open in your own browser cannot be read either.", "You"));
        stages.add(new ConnectionReport.Stage("links", "Links", "Do the links lead to pages we can read?", status, summary, facts, needs, ms));
        String verdict = status.equals("PASS") ? "READY" : status.equals("WARN") ? "NEEDS_ATTENTION" : "BLOCKED";
        ConnectionReport report = new ConnectionReport(clock.instant().toString(), "links", "links", verdict,
                status.equals("PASS") ? "Your links can be read" : status.equals("WARN") ? "Some links can be read" : "The links cannot be read yet", summary,
                status.equals("PASS") ? 100 : status.equals("WARN") ? 60 : 10, stages,
                new ConnectionReport.Route("WEB_PAGES", "Web pages behind the links", "Each short link is expanded and the page it leads to is read; "
                        + "a browser window opens for sites that need your sign-in.", true, List.of("Save the settings", "Collect")),
                List.of(new ConnectionReport.Requirement("links", "Links to read", total > 0 ? "MET" : "MISSING", total + " link(s)")));
        try {
            knowledgeBase.store().init();
            knowledgeBase.store().writeState(CONNECTION_STATE, report);
        } catch (RuntimeException ignored) {
            // still returned
        }
        return report;
    }

    /** The check's sign-in step found a company login (redirect to a sign-in page, a sign-in form, 401). */
    static boolean accessNeedsLogin(ConnectionReport report) {
        for (ConnectionReport.Stage st : report.stages()) {
            if (!st.id().equals("access") || st.status().equals("PASS") || st.status().equals("SKIPPED")) {
                continue;
            }
            String text = (st.summary() + " " + st.needs().stream().map(ConnectionReport.Need::what).reduce("", String::concat)).toLowerCase(java.util.Locale.ROOT);
            return text.contains("sign-in") || text.contains("sign in") || text.contains("login") || text.contains("log in");
        }
        return false;
    }

    /** Confluence's default (email + API token) with no email or token set: nothing is really chosen yet. */
    private boolean autoBrowserFor(String type, Map<String, String> v) {
        if (!type.equals("confluence") || !"basic-env".equals(v.getOrDefault("source.auth.method", ""))) {
            return false;
        }
        String tokenEnv = v.getOrDefault("source.auth.tokenEnv", "");
        return v.getOrDefault("source.confluence.email", "").isBlank() && v.getOrDefault("source.confluence.emailEnv", "").isBlank()
                && (tokenEnv.isBlank() || env.apply(tokenEnv) == null);
    }

    static boolean browserCouldNotBeUsed(ConnectionReport report) {
        ConnectionReport.Stage a = accessStage(report);
        return a != null && a.status().equals("FAIL") && a.summary().contains("the sign-in browser could not be used");
    }

    static ConnectionReport.Stage accessStage(ConnectionReport report) {
        return report.stages().stream().filter(st -> st.id().equals("access")).findFirst().orElse(null);
    }

    /** The first check's report, with the browser problem added to its sign-in step. */
    static ConnectionReport withBrowserProblem(ConnectionReport first, ConnectionReport.Stage failed) {
        List<ConnectionReport.Stage> stages = new ArrayList<>();
        for (ConnectionReport.Stage st : first.stages()) {
            if (st.id().equals("access") && failed != null) {
                List<ConnectionReport.Fact> facts = new ArrayList<>(st.facts());
                String reason = failed.summary().replaceFirst("^.*the sign-in browser could not be used: ", "");
                facts.add(new ConnectionReport.Fact("Browser sign-in", "could not be used: " + reason));
                List<ConnectionReport.Need> needs = new ArrayList<>(failed.needs());
                needs.addAll(st.needs());
                st = new ConnectionReport.Stage(st.id(), st.title(), st.question(), st.status(), st.summary()
                        + " The app tried to open your browser for the company login, but: " + reason, facts, needs, st.millis());
            }
            stages.add(st);
        }
        return new ConnectionReport(first.checkedAt(), first.target(), first.sourceType(), first.verdict(), first.headline(), first.detail(),
                first.score(), stages, first.route(), first.requirements(), first.settingsToApply());
    }

    private static com.questioncrawler.core.source.ApiOptions apiOptions(Map<String, String> v) {
        return new com.questioncrawler.core.source.ApiOptions(v.get("api.keyName"), v.get("api.keyIn"), v.get("api.accessTokenEnv"), v.get("api.bodyFilter"),
                Boolean.parseBoolean(v.getOrDefault("api.collectComments", "false")), v.get("api.swaggerUrl"), v.get("api.swaggerFile"), v.get("source.followRedirects"));
    }

    private static com.questioncrawler.core.source.ProxyConfig proxyOf(Map<String, String> v) {
        return com.questioncrawler.core.source.ProxyConfig.parse(v.getOrDefault("network.proxyHost", ""), v.getOrDefault("network.proxyPort", ""),
                v.getOrDefault("network.noProxy", ""), v.getOrDefault("network.proxyUserEnv", ""), v.getOrDefault("network.proxyPasswordEnv", ""));
    }

    /** What the Swagger reader found (or why it could not read the documentation). Safe to show: no secrets are included. */
    public record ApiDocsResult(boolean ok, String problem, com.questioncrawler.core.api.ApiCatalog catalog,
                                com.questioncrawler.core.api.ApiCapabilities capabilities, List<String> tried,
                                String suggestedBaseUrl, String suggestedKeyName, String suggestedKeyIn,
                                boolean needsSignIn, String suggestedBasePath, boolean bearerRequired) {}

    /** Reads the API documentation (address or saved file) and lists every endpoint. Nothing is saved. */
    public ApiDocsResult discoverApi(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        String swagger = v.getOrDefault("api.swaggerUrl", "").strip();
        String file = v.getOrDefault("api.swaggerFile", "").strip();
        if (swagger.isEmpty() && file.isEmpty()) {
            return new ApiDocsResult(false, "Enter the API documentation (Swagger) address first, or upload a saved copy of it.", null, null, List.of(), null, null, null, false, null, false);
        }
        try {
            java.net.URI u = swagger.isEmpty() ? null : java.net.URI.create(swagger);
            String ca = v.getOrDefault("source.caCertFile", "").strip();
            var settings = new com.questioncrawler.core.source.SourceSettings("docs", swagger.isEmpty() ? "https://localhost/" : swagger,
                    u == null || u.getHost() == null ? List.of() : List.of(u.getHost()), List.of("/"),
                    v.getOrDefault("source.auth.method", "none"), v.getOrDefault("source.auth.tokenEnv", ""), v.getOrDefault("source.auth.headerName", "Authorization"),
                    v.getOrDefault("source.auth.scheme", "Bearer"), ca.isEmpty() ? null : Path.of(ca),
                    java.time.Duration.ofSeconds(Math.min(parseInt(v.get("source.timeoutSeconds"), 30), 60)),
                    Boolean.parseBoolean(v.getOrDefault("source.allowInsecureHttp", "false")), null, v.getOrDefault("api.keyEnv", ""), proxyOf(v), apiOptions(v));
            var discovery = com.questioncrawler.core.api.ApiDocsLoader.load(settings, env);
            var key = discovery.catalog().security().stream().filter(x -> "apiKey".equals(x.type()) && x.name() != null).findFirst();
            String given = discovery.catalog().suggestedBaseUrl();
            String base = given == null ? null : given.startsWith("http") ? given : u == null ? null : u.resolve(given).toString();
            String path = given != null && base == null ? given : null;
            return new ApiDocsResult(true, null, discovery.catalog(), discovery.capabilities(), discovery.tried(), base,
                    key.map(com.questioncrawler.core.api.ApiCatalog.SecurityScheme::name).orElse(null), key.map(com.questioncrawler.core.api.ApiCatalog.SecurityScheme::in).orElse(null),
                    false, path, discovery.catalog().security().stream().anyMatch(x -> ("http".equals(x.type()) && "bearer".equalsIgnoreCase(x.scheme()))
                            || "oauth2".equals(x.type()) || "openIdConnect".equals(x.type())));
        } catch (com.questioncrawler.core.source.SourceException e) {
            boolean signIn = e.kind() == com.questioncrawler.core.source.SourceException.Kind.AUTH_FAILED || e.kind() == com.questioncrawler.core.source.SourceException.Kind.FORBIDDEN;
            return new ApiDocsResult(false, e.getMessage(), null, null, List.of(), null, null, null, signIn, null, false);
        } catch (IllegalArgumentException e) {
            return new ApiDocsResult(false, "That is not a valid web address.", null, null, List.of(), null, null, null, false, null, false);
        }
    }

    /** The raw answer of one API call: what the site really sends back. The key and tokens are never included. */
    public record ApiProbe(boolean reached, String problem, String url, int status, String contentType, int characters, String kind,
                           String summary, String preview, List<String> hints, boolean keySent, String keyPlacement,
                           String authNeeded, String wwwAuthenticate, boolean tokenSent, String suggestedPath, List<String> redirects, String signInSystem) {
        public ApiProbe(boolean reached, String problem, String url, int status, String contentType, int characters, String kind,
                        String summary, String preview, List<String> hints, boolean keySent, String keyPlacement,
                        String authNeeded, String wwwAuthenticate, boolean tokenSent) {
            this(reached, problem, url, status, contentType, characters, kind, summary, preview, hints, keySent, keyPlacement, authNeeded, wwwAuthenticate, tokenSent, null, List.of(), null);
        }
    }

    /**
     * Makes one GET request to the API (default /info) exactly as a collection would, and shows the answer. Used to find out why
     * a call fails: a sign-in page, a redirect, the wrong address, a proxy block page, and so on. Nothing is saved.
     */
    public ApiProbe probeApi(Map<String, String> draft, String requestedPath) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        String path = requestedPath == null || requestedPath.isBlank() ? "/info" : requestedPath.strip();
        if (!path.startsWith("/") || path.contains("://") || path.contains("..") || path.contains(" ")) {
            return new ApiProbe(false, "Enter a path that starts with / (for example /info or /questions?pagesize=15).", null, 0, null, 0, null, null, null, List.of(), false, null, null, null, false);
        }
        var api = apiOptions(v);
        String base = v.getOrDefault("source.baseUrl", "").strip();
        List<String> notes = new ArrayList<>();
        String keyName = api.keyName();
        String keyIn = api.keyIn();
        if (base.isEmpty() && (api.hasDocs())) { // take the address (and key placement) from the documentation
            var docs = discoverApi(v);
            if (docs.ok()) {
                base = docs.suggestedBaseUrl() == null ? "" : docs.suggestedBaseUrl();
                if (v.getOrDefault("api.keyName", "").isBlank() && docs.suggestedKeyName() != null) {
                    keyName = docs.suggestedKeyName();
                    keyIn = docs.suggestedKeyIn() == null ? "query" : docs.suggestedKeyIn();
                }
            }
        }
        if (base.isEmpty()) {
            return new ApiProbe(false, "The API address is not known yet. Enter it above, or read the documentation first.", null, 0, null, 0, null, null, null, List.of(), false, null, null, null, false);
        }
        java.net.URI baseUri;
        try {
            baseUri = java.net.URI.create(base);
            if (baseUri.getHost() == null) {
                throw new IllegalArgumentException("no host");
            }
        } catch (IllegalArgumentException e) {
            return new ApiProbe(false, "The API address is not a valid web address.", null, 0, null, 0, null, null, null, List.of(), false, null, null, null, false);
        }
        String keyValue = env.apply(v.getOrDefault("api.keyEnv", ""));
        String tokenValue = api.accessTokenEnv() == null ? null : env.apply(api.accessTokenEnv());
        String bearer = "token".equals(v.get("source.auth.method")) ? env.apply(v.getOrDefault("source.auth.tokenEnv", "")) : null;
        List<String> secrets = new ArrayList<>();
        for (String x : new String[] {keyValue, tokenValue, bearer}) {
            if (x != null && !x.isBlank()) {
                secrets.add(x);
            }
        }
        StringBuilder url = new StringBuilder(base.replaceAll("/+$", "")).append(path);
        char sep = path.contains("?") ? '&' : '?';
        String site = v.getOrDefault("api.site", "").strip();
        if (!site.isEmpty()) {
            url.append(sep).append("site=").append(java.net.URLEncoder.encode(site, java.nio.charset.StandardCharsets.UTF_8));
            sep = '&';
        }
        Map<String, String> headers = new LinkedHashMap<>();
        boolean keySent = keyValue != null && !keyValue.isBlank();
        if (keySent) {
            if ("header".equals(keyIn)) {
                headers.put(keyName, keyValue);
            } else {
                url.append(sep).append(java.net.URLEncoder.encode(keyName, java.nio.charset.StandardCharsets.UTF_8)).append('=')
                        .append(java.net.URLEncoder.encode(keyValue, java.nio.charset.StandardCharsets.UTF_8));
                sep = '&';
            }
        }
        if (tokenValue != null && !tokenValue.isBlank()) {
            url.append(sep).append("access_token=").append(java.net.URLEncoder.encode(tokenValue, java.nio.charset.StandardCharsets.UTF_8));
        }
        String ca = v.getOrDefault("source.caCertFile", "").strip();
        var settings = new com.questioncrawler.core.source.SourceSettings("probe", base, List.of(baseUri.getHost()), List.of("/"),
                v.getOrDefault("source.auth.method", "none"), v.getOrDefault("source.auth.tokenEnv", ""), v.getOrDefault("source.auth.headerName", "Authorization"),
                v.getOrDefault("source.auth.scheme", "Bearer"), ca.isEmpty() ? null : Path.of(ca),
                java.time.Duration.ofSeconds(Math.min(parseInt(v.get("source.timeoutSeconds"), 30), 60)),
                Boolean.parseBoolean(v.getOrDefault("source.allowInsecureHttp", "false")), null, null, proxyOf(v), api);
        String shownUrl = com.questioncrawler.core.source.ResponseInspector.redact(url.toString(), secrets);
        String placement = keySent ? ("header".equals(keyIn) ? "in the request header '" + keyName + "'" : "in the address as '" + keyName + "'") : null;
        try {
            var response = new com.questioncrawler.core.source.HttpSourceClient(settings, env).exchange(url.toString(), headers);
            String stripped = com.questioncrawler.core.source.ResponseInspector.stripNoise(response.body());
            boolean json = false;
            try {
                json = com.questioncrawler.core.util.Json.MAPPER.readTree(stripped).isObject();
            } catch (java.io.IOException ignored) {
                // not JSON: inspected below
            }
            var inspection = json ? new com.questioncrawler.core.source.ResponseInspector.Inspection("JSON",
                    "The site answered with JSON (HTTP " + response.status() + ")" + (response.status() >= 400 ? ", but it is an error." : "."),
                    com.questioncrawler.core.source.ResponseInspector.redact(stripped.length() > 700 ? stripped.substring(0, 700) + " …" : stripped, secrets),
                    response.status() >= 400 ? List.of("Read the error message in the answer above: it usually names the problem (key, parameter, permission).") : List.of())
                    : com.questioncrawler.core.source.ResponseInspector.inspect(response.status(), response.contentType(), response.location(), response.wwwAuthenticate(), response.body(), secrets);
            List<String> hints = new ArrayList<>(inspection.hints());
            if (response.redirectStop() != null) {
                hints.add(0, "A redirect was not followed: " + response.redirectStop() + ". Change the setting 'Follow redirects' or use the address it leads to.");
            } else if (!response.redirects().isEmpty()) {
                hints.add(0, "The request was redirected " + response.redirects().size() + " time(s) and the final answer is shown. If the API address always redirects, use the final address directly.");
            }
            var signIn = (inspection.kind().equals("HTML_LOGIN") || inspection.kind().equals("REDIRECT"))
                    ? com.questioncrawler.core.source.SignInSystem.identify(response.finalUrl(), response.location()) : java.util.Optional.<com.questioncrawler.core.source.SignInSystem>empty();
            signIn.ifPresent(sys -> {
                hints.add(0, sys.sentence());
                hints.add(1, "Ask your IT identity team or the site owners for: " + String.join(" ", sys.ask()));
            });
            String authNeeded = com.questioncrawler.core.source.ResponseInspector.authKind(response.status(), response.wwwAuthenticate(), stripped);
            if (json && authNeeded != null) { // a JSON refusal that names the sign-in it wants
                hints.addAll(0, com.questioncrawler.core.source.ResponseInspector.authHints(authNeeded));
            }
            // A JSON refusal with a readable reason (validation errors, wrong parameter): say it in words.
            String suggested = null;
            String reason = json && response.status() >= 400 ? com.questioncrawler.core.source.ResponseInspector.errorSummary(response.body()) : "";
            if (!reason.isBlank()) {
                hints.add(0, "The API says: " + com.questioncrawler.core.source.ResponseInspector.redact(reason, secrets));
                var sizes = com.questioncrawler.core.source.ResponseInspector.allowedPageSizes(reason);
                if (!sizes.isEmpty()) {
                    String fixed = path.replaceAll("(?i)(page[_-]?size=)\\d+", "$1" + sizes.get(0));
                    suggested = fixed.equals(path) ? null : fixed;
                    hints.add(1, "This API only accepts certain page sizes (" + sizes + "). Use one of them in the path, for example pagesize=" + sizes.get(0)
                            + ". Collections adapt to this automatically, so you do not need to change any setting.");
                }
            }
            if (bearer != null && "bearer".equals(authNeeded)) {
                hints.add(0, "A Bearer token WAS sent but was not accepted: it may be expired, for another site, or lack permission. Create a new token and try again.");
            }
            if (!keySent) {
                hints.add(0, "No API access key was sent: the variable '" + v.getOrDefault("api.keyEnv", "") + "' is not set in the terminal that started the app.");
            }
            return new ApiProbe(true, null, shownUrl, response.status(), response.contentType(), response.body() == null ? 0 : response.body().length(),
                    inspection.kind(), inspection.summary(), inspection.preview(), hints, keySent, placement, authNeeded,
                    com.questioncrawler.core.source.ResponseInspector.redact(response.wwwAuthenticate() == null ? "" : response.wwwAuthenticate(), secrets), bearer != null, suggested, response.redirects(), signIn.map(com.questioncrawler.core.source.SignInSystem::name).orElse(null));
        } catch (com.questioncrawler.core.source.SourceException e) {
            return new ApiProbe(false, com.questioncrawler.core.source.ResponseInspector.redact(e.getMessage(), secrets), shownUrl, 0, null, 0, null, null, null,
                    List.of("The request did not get an answer at all: see the Check access result for the network steps (proxy, certificate)."), keySent, placement, null, null, bearer != null);
        }
    }

    private final TagListingJob tagListing = new TagListingJob();

    /** The site adapter for what is currently typed on the Connect screen (the saved settings, overridden by the draft). */
    private com.questioncrawler.core.source.StackExchangeAdapter adapterFromDraft(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        String type = v.getOrDefault("source.type", "fixture");
        if (!type.equals("live") && !type.equals("stackexchange")) {
            throw new IllegalStateException("Tags can be looked up on a real site only. Choose a site first.");
        }
        var adapter = CoreConfig.load(null, v).createAdapter(env);
        if (adapter instanceof com.questioncrawler.core.source.StackExchangeAdapter api) {
            return api;
        }
        throw new IllegalStateException("Tags can be looked up on a real site only.");
    }

    /** Starts reading EVERY tag of the site in the background (no page limit). Poll {@link #tagListing(int)}. */
    public TagListingJob.State startTagListing(Map<String, String> draft) {
        Map<String, String> copy = draft == null ? Map.of() : Map.copyOf(draft);
        return tagListing.start(() -> adapterFromDraft(copy));
    }

    public TagListingJob.Chunk tagListing(int from) {
        return tagListing.chunk(from);
    }

    public void cancelTagListing() {
        tagListing.cancel();
    }

    public record CollectionSample(String id, String title, List<String> tags, String createdAt, int answers, int bodyCharacters) {}

    public record CollectionTest(boolean ok, String problem, List<String> tags, int found, int shown, String request, List<CollectionSample> samples, List<String> notes, boolean tagsAll) {}

    /**
     * Asks for the first page of questions exactly as a collection would (with the tags currently typed) and shows what came back and what was
     * understood: how many questions, which ones, their tags, dates and answers. Nothing is stored.
     */
    public CollectionTest testCollection(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        List<String> tags = new ArrayList<>();
        for (String t : v.getOrDefault("sync.tags", "").split(",")) {
            if (!t.isBlank()) {
                tags.add(t.strip());
            }
        }
        boolean all = com.questioncrawler.core.source.TagFilter.asksForAll(tags);
        if (tags.isEmpty()) {
            return new CollectionTest(false, "Choose at least one tag first (or the tag 'all').", tags, 0, 0, null, List.of(), List.of(), false);
        }
        try {
            var api = adapterFromDraft(draft);
            var filter = new com.questioncrawler.core.source.TagFilter(tags, com.questioncrawler.core.source.TagMode.valueOf(v.getOrDefault("sync.tagMode", "ANY").toUpperCase()), false, null);
            var page = api.listQuestions(filter, null, null);
            List<CollectionSample> samples = new ArrayList<>();
            for (var summary : page.items().stream().limit(3).toList()) {
                var q = api.fetchQuestion(summary.id());
                int answers = q.answers().size(); // answers embedded in the question, if the API sends them
                try {
                    answers = Math.max(answers, api.listAnswers(summary.id(), null).items().size());
                } catch (com.questioncrawler.core.source.SourceException e) {
                    // answers are shown when available
                }
                samples.add(new CollectionSample(summary.id(), q.title(), q.tags(), q.createdAt(), answers, q.body() == null ? 0 : q.body().length()));
            }
            return new CollectionTest(true, null, tags, page.items().size(), samples.size(), api.lastListingRequest(), samples, api.notes(), all);
        } catch (com.questioncrawler.core.source.SourceException e) {
            return new CollectionTest(false, e.getMessage(), tags, 0, 0, null, List.of(), List.of(), all);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new CollectionTest(false, e.getMessage(), tags, 0, 0, null, List.of(), List.of(), all);
        }
    }

    public record TagSearch(boolean ok, String problem, List<com.questioncrawler.core.source.StackExchangeAdapter.TagHit> tags, String exact, boolean spellingDiffers,
                            boolean allTags, long siteQuestions, boolean truncated) {
        public TagSearch(boolean ok, String problem, List<com.questioncrawler.core.source.StackExchangeAdapter.TagHit> tags, String exact, boolean spellingDiffers) {
            this(ok, problem, tags, exact, spellingDiffers, false, -1, false);
        }
    }

    /** Looks up tags on the site by a few letters, with the site's own spelling and counts. Uses what is currently typed on the Connect screen. */
    /** The Confluence spaces the configured account can read, so they can be ticked instead of typed. */
    public record ConfluenceSpaces(boolean ok, String problem, List<com.questioncrawler.core.source.ConfluenceAdapter.SpaceInfo> spaces,
                                   boolean complete, List<String> chosen) {}

    /** Lists Confluence spaces with the saved settings, or the settings being edited (draft values are NOT saved). */
    public ConfluenceSpaces confluenceSpaces(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        if (!"confluence".equals(v.getOrDefault("source.type", "fixture"))) {
            return new ConfluenceSpaces(false, "Choose Confluence as the source first.", List.of(), true, List.of());
        }
        if (v.getOrDefault("source.baseUrl", "").isBlank()) {
            return new ConfluenceSpaces(false, "Enter the Confluence site address first (for example https://acme.atlassian.net).", List.of(), true, List.of());
        }
        try {
            CoreConfig config = CoreConfig.load(null, v);
            var adapter = (com.questioncrawler.core.source.ConfluenceAdapter) config.createAdapter(env);
            var listing = adapter.listSpaces(MAX_CONFLUENCE_SPACES);
            return new ConfluenceSpaces(true, listing.spaces().isEmpty() ? "This account cannot see any Confluence space." : null,
                    listing.spaces(), listing.complete(), config.confluence().spaces());
        } catch (com.questioncrawler.core.source.SourceException e) {
            return new ConfluenceSpaces(false, e.getMessage(), List.of(), true, List.of());
        } catch (RuntimeException e) {
            return new ConfluenceSpaces(false, "The spaces could not be listed: " + e.getMessage(), List.of(), true, List.of());
        }
    }

    static final int MAX_CONFLUENCE_SPACES = 10_000;

    public record SignInResult(boolean ok, String message) {}

    /** Signs in to Confluence through the browser now (opens the company login page), using the saved or draft settings. */
    public SignInResult confluenceSignIn(Map<String, String> draft) {
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        String type = v.getOrDefault("source.type", "fixture");
        if (!"confluence".equals(type) || !"browser".equalsIgnoreCase(v.getOrDefault("source.auth.method", ""))) {
            return new SignInResult(false, "Browser sign-in is for Confluence: choose Confluence and 'Sign in with my browser' first.");
        }
        if (v.getOrDefault("source.baseUrl", "").isBlank() && ("confluence".equals(type) || v.getOrDefault("api.swaggerUrl", "").isBlank())) {
            return new SignInResult(false, "confluence".equals(type)
                    ? "Enter the Confluence site address first (for example https://acme.atlassian.net)."
                    : "Enter the site address or the API documentation address first.");
        }
        try {
            var adapter = CoreConfig.load(null, v).createAdapter(env);
            if (!(adapter instanceof com.questioncrawler.core.source.BrowserSignIn signIn)) {
                return new SignInResult(false, "This source does not sign in through the browser.");
            }
            return new SignInResult(true, signIn.signIn());
        } catch (com.questioncrawler.core.source.SourceException e) {
            return new SignInResult(false, e.getMessage());
        } catch (RuntimeException e) {
            return new SignInResult(false, "Signing in did not work: " + e.getMessage());
        }
    }

    public TagSearch searchTags(Map<String, String> draft, String text) {
        boolean browse = text == null || text.isBlank() || com.questioncrawler.core.source.TagFilter.asksForAll(List.of(text));
        Map<String, String> v = configService.read(configFile);
        if (draft != null) {
            v.putAll(draft);
        }
        String type = v.getOrDefault("source.type", "fixture");
        if (!type.equals("live") && !type.equals("stackexchange")) {
            return new TagSearch(false, "Tags can be looked up on a real site only. Choose a site first.", List.of(), null, false);
        }
        try {
            CoreConfig config = CoreConfig.load(null, v);
            var adapter = config.createAdapter(env);
            if (!(adapter instanceof com.questioncrawler.core.source.StackExchangeAdapter api)) {
                return new TagSearch(false, "Tags can be looked up on a real site only.", List.of(), null, false);
            }
            if (browse) { // every tag of the site, most used first, and how many questions the whole site holds
                var listing = api.listAllTags(Integer.MAX_VALUE, 600_000); // every page (the screen uses the background job with progress instead)
                var all = listing.tags();
                long size = -1;
                try {
                    size = api.siteQuestionCount(); // nice to have: the tag list is the result, so a failure here must not hide it
                } catch (com.questioncrawler.core.source.SourceException ignored) {
                    // the size is simply not shown
                }
                return new TagSearch(true, all.isEmpty() ? "The site did not list any tags. You can still collect everything with the tag 'all'." : null, all, null, false,
                        true, size, !listing.complete());
            }
            var check = api.checkTag(text);
            List<com.questioncrawler.core.source.StackExchangeAdapter.TagHit> hits = new ArrayList<>(api.findTags(text, 20));
            String exact = check.exists() ? check.exactName() : null;
            if (exact != null && hits.stream().noneMatch(h -> h.name().equals(exact))) {
                hits.add(0, new com.questioncrawler.core.source.StackExchangeAdapter.TagHit(exact, check.count()));
            }
            return new TagSearch(true, hits.isEmpty() ? "No tag on the site looks like '" + text.strip() + "'. Check the spelling, or ask the site owners." : null, hits, exact,
                    exact != null && !exact.equals(text.strip()));
        } catch (com.questioncrawler.core.source.SourceException e) {
            return new TagSearch(false, e.getMessage(), List.of(), null, false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new TagSearch(false, e.getMessage(), List.of(), null, false);
        }
    }

    /** Where an uploaded copy of the documentation is kept: next to the settings file. */
    public Path apiDocsFile() {
        Path dir = configFile.toAbsolutePath().getParent();
        return (dir == null ? Path.of(".") : dir).resolve("api-docs").resolve("swagger.json");
    }

    public record SavedDocs(boolean ok, String problem, String path, int operations) {}

    /** Keeps a copy of the API documentation (saved from a signed-in browser) after checking that it really is one. */
    public SavedDocs saveApiDocs(String json) {
        try {
            var discovery = com.questioncrawler.core.api.SwaggerDiscovery.fromJson(json, "uploaded file");
            Path target = apiDocsFile();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling("swagger.json.tmp");
            Files.writeString(tmp, json, java.nio.charset.StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new SavedDocs(true, null, target.toString().replace('\\', '/'), discovery.catalog().operations().size());
        } catch (com.questioncrawler.core.source.SourceException e) {
            return new SavedDocs(false, e.getMessage(), null, 0);
        } catch (java.io.IOException e) {
            return new SavedDocs(false, "The file could not be saved: " + e.getMessage(), null, 0);
        }
    }

    public Optional<ConnectionReport> lastConnection() {
        try {
            return knowledgeBase.store().isInitialized() ? knowledgeBase.store().readState(CONNECTION_STATE, ConnectionReport.class) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ================================================================== categories (taxonomy)

    public TaxonomyView taxonomyView() {
        Taxonomy t = knowledgeBase.taxonomy();
        Path file = taxonomyPath();
        boolean custom = config.taxonomyFile() != null && Files.isReadable(config.taxonomyFile());
        return new TaxonomyView(custom, file.toString(), t.hash(), t, t.validate());
    }

    public TaxonomyView saveTaxonomy(Taxonomy taxonomy) {
        List<String> problems = taxonomy.validate();
        if (!problems.isEmpty()) {
            return new TaxonomyView(false, taxonomyPath().toString(), taxonomy.hash(), taxonomy, problems);
        }
        Path file = taxonomyPath();
        try {
            Files.createDirectories(file.getParent());
            com.questioncrawler.core.storage.AtomicFiles.write(file, Json.write(taxonomy));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot write " + file + ": " + e.getMessage(), e);
        }
        if (config.taxonomyFile() == null) {
            configService.save(configFile, Map.of("taxonomy.file", file.getFileName().toString()));
        }
        reload();
        return taxonomyView();
    }

    public TaxonomyView resetTaxonomy() {
        Path file = taxonomyPath();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot remove " + file + ": " + e.getMessage(), e);
        }
        configService.save(configFile, Map.of("taxonomy.file", ""));
        reload();
        return taxonomyView();
    }

    private Path taxonomyPath() {
        return config.taxonomyFile() != null ? config.taxonomyFile() : configFile.getParent().resolve("taxonomy.json");
    }

    /** Shows which categories a sample question would get, and exactly which rules matched. */
    public TryResult tryClassify(Taxonomy draft, String title, String body, List<String> tags) {
        Taxonomy taxonomy = draft != null ? draft : knowledgeBase.taxonomy();
        List<String> problems = taxonomy.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("These category settings have problems: " + String.join("; ", problems));
        }
        Question q = new Question("sample", title == null ? "" : title, body == null ? "" : body, BodyFormat.MARKDOWN, null,
                tags == null ? List.of() : tags, null, null, null, null, List.of(), List.of());
        Classification c = new Classifier(taxonomy).classify(q);
        List<TryMatch> matches = c.categories().stream()
                .map(m -> new TryMatch(m.categoryId(), taxonomy.displayName(m.categoryId()), m.score(), m.matchedRules())).toList();
        boolean fallback = matches.size() == 1 && matches.get(0).categoryId().equals(taxonomy.fallbackCategory());
        return new TryResult(matches, fallback, fallback
                ? "No category reached the minimum score of " + taxonomy.minScore() + ", so it falls back to '" + taxonomy.displayName(taxonomy.fallbackCategory()) + "'."
                : "A category is chosen when its matching rules add up to at least " + taxonomy.minScore() + " points.");
    }

    /** Proposes topics found in the collected questions (nothing is saved until the person applies them). */
    public com.questioncrawler.core.classification.TopicDiscovery.Proposal discoverTopics(int k) {
        return com.questioncrawler.core.classification.TopicDiscovery.discover(knowledgeBase.store().allThreads(), k);
    }

    // ================================================================== overview

    public Overview overview() {
        KnowledgeBase kb = knowledgeBase;
        boolean configExists = Files.exists(configFile);
        String type = config.sourceType();
        String label = switch (type) {
            case "fixture" -> "Demo data";
            case "stackexchange" -> "Stack Exchange: " + config.source().site();
            case "confluence" -> "Confluence" + (config.source().baseUrl().isBlank() ? "" : ": " + config.source().baseUrl());
            case "jive" -> "Jive" + (config.source().baseUrl().isBlank() ? "" : ": " + config.source().baseUrl());
            case "sharepoint" -> "SharePoint" + (config.source().baseUrl().isBlank() ? "" : ": " + config.source().baseUrl());
            case "links" -> "Links (" + config.links().links().size() + " listed" + (config.links().fromCollected() ? " + short links in collected items" : "") + ")";
            default -> "Internal site";
        };
        int questions = 0;
        int answers = 0;
        int accepted = 0;
        int unanswered = 0;
        int hidden = 0;
        Map<String, Integer> byStatus = new TreeMap<>();
        Map<String, int[]> perSource = new java.util.LinkedHashMap<>();
        List<Overview.CategorySlice> categories = new ArrayList<>();
        List<Overview.RunBrief> runs = new ArrayList<>();
        if (kb.store().isInitialized()) {
            for (ThreadRecord t : kb.store().allThreads()) {
                byStatus.merge(t.status().name(), 1, Integer::sum);
                if (t.isPurged() || !t.status().isRetrievable()) {
                    hidden++;
                    continue;
                }
                questions++;
                answers += t.question().answers().size();
                int[] c = perSource.computeIfAbsent(t.sourceSystem(), k -> new int[2]);
                c[0]++;
                c[1] += t.question().answers().size();
                if (t.question().hasAcceptedAnswer()) accepted++;
                if (t.question().answers().isEmpty()) unanswered++;
            }
            kb.categories().stream().filter(c -> c.questionCount() > 0)
                    .forEach(c -> categories.add(new Overview.CategorySlice(c.id(), c.name(), c.questionCount())));
            kb.store().listReportIds().stream().limit(6).forEach(id -> kb.store().readReport(id, SyncReport.class).ifPresent(r ->
                    runs.add(new Overview.RunBrief(r.runId(), r.mode(), r.outcome(), r.finishedAt(), r.added(), r.updated(), r.failures().size()))));
        }
        Optional<ConnectionReport> conn = lastConnection();
        List<Overview.SetupStep> steps = new ArrayList<>();
        com.questioncrawler.core.util.Terms terms = com.questioncrawler.core.util.Terms.of(type);
        steps.add(new Overview.SetupStep("choose", "Choose your source",
                configExists ? "Source: " + label + (config.tags().isEmpty() ? "" : " · " + (type.equals("confluence") ? "labels: " : "tags: ") + String.join(", ", config.tags())) : "Pick the system and what you care about.",
                configExists && (!config.tags().isEmpty() || type.equals("fixture")) ? "DONE" : "TODO", "connect"));
        String checkStatus;
        String checkText;
        if (type.equals("fixture")) {
            checkStatus = configExists ? "DONE" : "TODO";
            checkText = "Demo data needs no connection.";
        } else if (conn.isEmpty()) {
            checkStatus = "TODO";
            checkText = "See whether this computer can reach the site and what is needed.";
        } else {
            String verdict = conn.get().verdict();
            checkStatus = verdict.equals("READY") || verdict.equals("NEEDS_ATTENTION") ? "DONE" : "ATTENTION";
            checkText = conn.get().headline();
        }
        steps.add(new Overview.SetupStep("check", "Check we can reach it", checkText, checkStatus, "connect"));
        steps.add(new Overview.SetupStep("collect", terms.hasAnswers() ? "Collect " + terms.items() + " and " + terms.answers() : "Collect " + terms.items(),
                questions > 0 ? terms.count(questions) + (terms.hasAnswers() ? " and " + terms.countAnswers(answers) : "") + " saved."
                        : "Download the " + terms.items() + " into your knowledge folder.",
                questions > 0 ? "DONE" : steps.get(0).status().equals("DONE") ? "TODO" : "LOCKED", "collect"));
        steps.add(new Overview.SetupStep("explore", "Ask your knowledge",
                questions > 0 ? (terms == com.questioncrawler.core.util.Terms.PAGES ? "Ask in plain words; the best paragraph is quoted as the answer." : "Search by words, error messages, tags and age.")
                        : "Available after the first collection.",
                questions > 0 ? "READY" : "LOCKED", "explore"));
        String next = steps.stream().filter(s -> s.status().equals("TODO") || s.status().equals("ATTENTION")).map(Overview.SetupStep::id).findFirst().orElse("explore");
        List<String> notes = new ArrayList<>();
        var lucene = com.questioncrawler.core.search.LuceneDoctor.cachedInspection();
        if (!lucene.healthy()) { // a conflicting Lucene makes every collection fail at the end: say so before the person tries
            notes.add("The search library (Lucene) has a conflict on the classpath, so collecting will fail at the last step. " + String.join(" ", lucene.problems())
                    + (lucene.fixes().isEmpty() ? "" : " " + lucene.fixes().get(0)));
        }
        if (loadError != null) {
            notes.add(loadError);
        }
        if (kb.store().isInitialized()) {
            // Plain-language versions of the store's technical notes (the CLI keeps its own wording).
            Views.StatusReport status = kb.status();
            if (status.interruptedRunPending()) {
                notes.add("A collection was interrupted. Start a new one and it continues where it stopped.");
            }
            if (status.threadsClassifiedWithOlderTaxonomy() > 0) {
                notes.add(terms.count(status.threadsClassifiedWithOlderTaxonomy()) + (status.threadsClassifiedWithOlderTaxonomy() == 1 ? " was" : " were")
                        + " sorted with older topic rules. Re-sort them to apply your current rules.");
            }
            if (status.indexedDocuments() == null && status.threadCount() > 0) {
                notes.add("The search index is missing. Re-sort your " + terms.items() + " to rebuild it.");
            }
        }
        String window = config.maxQuestionAge();
        return new Overview(configFile.toString(), configExists, type, label, config.tags(), window, steps, next,
                new Overview.Totals(questions, answers, accepted, unanswered, hidden, byStatus), categories, runs, kb.freshness(), runner.isRunning(), notes,
                scopeOf(config), sourceCounts(perSource, config, label));
    }

    /** What is stored per source, the configured one first, each in its own words (pages, questions, posts). */
    private static List<Overview.SourceCount> sourceCounts(Map<String, int[]> perSource, CoreConfig config, String currentLabel) {
        String currentSystem = config.sourceType().equals("fixture") ? com.questioncrawler.core.source.FixtureSourceAdapter.SYSTEM_ID : config.source().systemId();
        List<Overview.SourceCount> out = new ArrayList<>();
        perSource.forEach((system, c) -> {
            boolean current = system.equals(currentSystem);
            var terms = current ? com.questioncrawler.core.util.Terms.of(config.sourceType()) : com.questioncrawler.core.util.Terms.ofSystem(system);
            out.add(new Overview.SourceCount(system, com.questioncrawler.core.util.Terms.sourceName(system),
                    terms.items(), c[0], terms.hasAnswers() ? c[1] : 0, current));
        });
        out.sort(java.util.Comparator.comparing((Overview.SourceCount s) -> !s.current()).thenComparing(s -> -s.count()));
        return out;
    }

    /** What bounds the collection besides tags, in plain words, or null. */
    private static String scopeOf(CoreConfig config) {
        if ("confluence".equals(config.sourceType()) && config.confluence() != null && config.confluence().hasBoundedScope()) {
            var o = config.confluence();
            List<String> parts = new ArrayList<>();
            if (!o.pageIds().isEmpty()) {
                parts.add("Pages: " + String.join(", ", o.pageIds()));
            }
            if (!o.ancestorPageId().isEmpty()) {
                parts.add("Everything under page " + o.ancestorPageId());
            }
            if (parts.isEmpty()) {
                parts.add((o.spaces().size() == 1 ? "Space: " : "Spaces: ") + String.join(", ", o.spaces()));
            }
            return String.join(" · ", parts);
        }
        if ("jive".equals(config.sourceType()) && config.jive() != null && config.jive().hasBoundedScope()) {
            return "Places: " + String.join(", ", config.jive().places());
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
