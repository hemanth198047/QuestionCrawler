package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Sign-in through the person's own browser, for sites that send API calls to a company login page (HTTP 302 to SSO).
 *
 * <p>The app opens Edge or Chrome on this computer at the site address. The person signs in on the real login page
 * exactly as they do every day (SSO, MFA, Windows sign-in: whatever the company uses); the app never sees or types a
 * password. As soon as the site accepts the browser's session, the app reads that session's cookies for the site's host
 * (over the Chrome DevTools Protocol, local only), closes the window, and uses the cookies for read-only API calls.
 * When the session expires the window opens again.
 *
 * <p>The cookies are kept in memory only. The browser uses its own profile folder (default
 * {@code ~/.questioncrawler/browser-profile}) so the company login can remember you between runs, like any browser.
 */
public final class BrowserLogin {

    private static final System.Logger LOG = System.getLogger("questioncrawler.signin");

    /** Checks whether the site accepts a Cookie header (for example: one cheap API call answers 200). */
    @FunctionalInterface
    public interface Prober {
        boolean accepted(String cookieHeader) throws SourceException;
    }

    /** A running browser window that can report its cookies. Replaceable in tests. */
    public interface Session extends AutoCloseable {
        List<Cookie> cookies() throws IOException;

        boolean alive();

        /** Brings the sign-in window in front of other windows (so the person sees it). */
        default void bringToFront() {
        }

        /**
         * Loads an address INSIDE the browser (from a page of {@code origin}), so everything the browser uses to sign you in
         * applies: cookies, your Windows login (Kerberos/NTLM), client certificates. Null when this session cannot do it.
         */
        default HttpSourceClient.Response fetchInPage(String url, String origin, Map<String, String> headers) throws IOException {
            return null;
        }

        /** Minimises the window (it stays open so the app can keep reading through it). */
        default void minimize() {
        }

        /**
         * Opens an address in the window as a person would (scripts run, the company login applies) and returns where
         * the window ended up and the page's HTML. Null when this session cannot do it.
         */
        default PageView openPage(String url, Duration wait) throws IOException {
            return null;
        }

        /** Where the window is now, and its HTML. Null when this session cannot tell. */
        default PageView currentPage() throws IOException {
            return null;
        }

        @Override
        void close();
    }

    /** Opens a browser window at an address. Replaceable in tests. */
    @FunctionalInterface
    public interface Launcher {
        Session open(String startUrl) throws IOException;
    }

    public record Cookie(String name, String value, String domain, String path) {}

    /** A page as the browser window shows it. */
    public record PageView(String url, String title, String html, boolean complete) {}

    private static final Map<String, BrowserLogin> BY_SITE = new ConcurrentHashMap<>();
    /** Every sign-in made in this app, so helper requests can reuse a session for the same host. */
    private static final java.util.Set<BrowserLogin> ALL = ConcurrentHashMap.newKeySet();

    private final String host;
    private final String startUrl;
    private final Launcher launcher;
    private final Duration timeout;
    private final Duration pollEvery;
    private final Clock clock;
    private volatile String header;
    private volatile Instant signedInAt;
    /** Set when the site only works inside the browser (for example Windows sign-in): requests go through this window. */
    private volatile Session readsThroughBrowser;
    private final String origin;
    private volatile List<String> contentUrls = List.of();
    /** Marker "cookie" while reading through the browser (requests are routed through the window, not sent with it). */
    public static final String THROUGH_BROWSER = "qc-reads-through-browser=1";
    private int logins;
    private String lastStatus = "not signed in yet";

    public BrowserLogin(String siteBase, String startUrl, Launcher launcher, Duration timeout, Duration pollEvery, Clock clock) {
        this.host = URI.create(siteBase).getHost().toLowerCase(Locale.ROOT);
        URI base = URI.create(siteBase);
        this.origin = base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getRawAuthority().toLowerCase(Locale.ROOT);
        this.startUrl = startUrl;
        this.launcher = launcher;
        this.timeout = timeout;
        this.pollEvery = pollEvery;
        this.clock = clock;
        ALL.add(this);
    }

    /**
     * One shared sign-in per site and settings for the whole app, so Check access, Show my spaces and Collect reuse the
     * same session instead of opening a window each.
     */
    public static BrowserLogin forSite(String siteBase, ConfluenceOptions options, Function<String, String> env) {
        ConfluenceOptions.Browser b = options.browser();
        String start = b.loginUrl().isBlank() ? trimSlash(siteBase) + (options.cloud() ? "/wiki/" : "/") : b.loginUrl();
        return forSite(siteBase, start, b, env);
    }

    /** One shared sign-in for any site (Confluence, the internal Q&A site, ...), opened at {@code startUrl}. */
    public static BrowserLogin forSite(String siteBase, String startUrl, ConfluenceOptions.Browser b, Function<String, String> env) {
        String key = siteBase.toLowerCase(Locale.ROOT) + "|" + startUrl + "|" + b.path() + "|" + b.profileDir();
        return BY_SITE.computeIfAbsent(key, k -> new BrowserLogin(siteBase, startUrl,
                new ChromeLauncher(b, env), Duration.ofSeconds(b.timeoutSeconds()), Duration.ofSeconds(2), Clock.systemUTC()));
    }

    /**
     * Addresses that show content only when signed in (for example the question list or an API call). When the browser
     * window shows the site but its cookies alone do not work for the app (Windows sign-in), these are loaded inside the
     * browser instead, and the app keeps reading through the browser.
     */
    public BrowserLogin withContentCheck(List<String> urls) {
        this.contentUrls = urls == null ? List.of() : List.copyOf(urls);
        return this;
    }

    private volatile boolean webPages;

    /**
     * For reading ordinary web pages (the Links source): a page of the site that loads in the window, is not a login
     * page and does not end on a login address also counts as "signed in", not only API data.
     */
    public BrowserLogin acceptingWebPages() {
        this.webPages = true;
        return this;
    }

    /** An ordinary page of the site (not the login, not a redirect to it): proof that the browser is signed in. */
    public static boolean showsPage(HttpSourceClient.Response r) {
        if (r == null || needsSignIn(r) || r.status() < 200 || r.status() >= 300) {
            return false;
        }
        if (r.finalUrl() != null && LOGIN_URL.matcher(r.finalUrl().split("[?#]", 2)[0]).find()) {
            return false;
        }
        String body = r.body() == null ? "" : r.body();
        return !body.isBlank() && !looksLikeLoginPage(body);
    }

    /** True when the app reads this site through the browser window (the site signs you in in a way only the browser can). */
    public boolean readsThroughBrowser() {
        Session s = readsThroughBrowser;
        return s != null && s.alive();
    }

    /** A browser window to read an address through, when its site only works inside the browser; null otherwise. */
    public static Session transportFor(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String o = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
        for (BrowserLogin login : ALL) {
            Session s = login.readsThroughBrowser;
            if (s != null && login.origin.equals(o)) {
                if (s.alive()) {
                    return s;
                }
                login.dropBrowserReading(); // the person closed the minimised window: sign in again when needed
            }
        }
        return null;
    }

    public static String originOf(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
    }

    private synchronized void dropBrowserReading() {
        Session s = readsThroughBrowser;
        readsThroughBrowser = null;
        if (THROUGH_BROWSER.equals(header)) {
            header = null;
        }
        if (s != null) {
            s.close();
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ALL.forEach(l -> {
            Session s = l.readsThroughBrowser;
            if (s != null) {
                s.close(); // the minimised reading window closes with the app
            }
        }), "close-browser-sign-in"));
    }

    /**
     * The Cookie header of a session someone already signed in to for this host, or null. Lets helper requests (reading
     * the API documentation, trying an address on the Connect screen) reuse the browser sign-in without opening a window.
     */
    public static String sessionFor(String host) {
        if (host == null) {
            return null;
        }
        String best = null;
        Instant bestAt = null;
        for (BrowserLogin login : ALL) { // no locking: a sign-in window may be open and waiting on another login
            String h = login.header;
            Instant at = login.signedInAt;
            if (h != null && at != null && domainMatches(host.toLowerCase(Locale.ROOT), login.host) && (bestAt == null || at.isAfter(bestAt))) {
                best = h;
                bestAt = at;
            }
        }
        return best;
    }

    /**
     * True when a response shows the site let the session in: no redirect to a login page, no 401/403, and not a login
     * form (an API error such as "missing key" or "not found" still proves the company login was passed).
     */
    public static boolean letIn(HttpSourceClient.Response r) {
        if (r == null || Redirects.isRedirect(r.status())) {
            return false;
        }
        int s = r.status();
        if (s == 401 || s == 403 || s == 407 || s >= 500) {
            return false;
        }
        // Past the company login: content, or an answer from the site itself (even "missing key" or "not found").
        return !looksLikeLoginPage(r.body());
    }

    /**
     * True only when a response is real content behind the login: API data (JSON, 2xx) or a Q&A website page with
     * questions. Never when the request ended on a login address, was redirected away, or got a login form.
     */
    public static boolean showsContent(HttpSourceClient.Response r) {
        if (r == null || needsSignIn(r) || r.status() == 403 || r.status() == 407 || r.status() >= 500) {
            return false;
        }
        if (r.finalUrl() != null && LOGIN_URL.matcher(r.finalUrl()).find()) {
            return false;
        }
        String type = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        String body = r.body() == null ? "" : r.body();
        if (type.contains("json")) {
            // The API itself answered (even "missing key" proves the company login was passed).
            return body.stripLeading().startsWith("{") || body.stripLeading().startsWith("[");
        }
        if (r.status() < 200 || r.status() >= 300) {
            return false;
        }
        return false; // only API data counts: a web page is no proof of being signed in (it may be the login's forwarding page)
    }

    /** An HTML page with a password field or a SAML/OAuth form: the company login, not content. */
    public static boolean looksLikeLoginPage(String body) {
        if (body == null) {
            return false;
        }
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("type=\"password\"") || b.contains("type='password'") || b.contains("samlrequest") || b.contains("samlresponse")
                || (b.contains("<form") && (b.contains("login") || b.contains("sign in") || b.contains("signin")));
    }

    /** True when a response means "sign in first": 401, a redirect, or a login page instead of content. */
    private static final java.util.regex.Pattern LOGIN_URL = java.util.regex.Pattern.compile(
            "login|logon|signin|sign-in|sso|oauth|saml|adfs|okta|microsoftonline|auth0|/auth|/users/login|wsignin|openid|idp", java.util.regex.Pattern.CASE_INSENSITIVE);

    public static boolean needsSignIn(HttpSourceClient.Response r) {
        if (r == null) {
            return false;
        }
        if (r.status() == 401 || Redirects.isRedirect(r.status())) {
            return true;
        }
        // Redirected (on the same host) to a sign-in page, e.g. /users/login?returnurl=... on Stack Overflow Enterprise.
        boolean redirected = r.redirects() != null && !r.redirects().isEmpty();
        if (redirected && r.finalUrl() != null && LOGIN_URL.matcher(r.finalUrl()).find()) {
            return true;
        }
        String type = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        return r.status() >= 200 && r.status() < 300 && type.contains("html") && looksLikeLoginPage(r.body());
    }

    /** The Cookie header of the current session, signing in through the browser first when there is none. */
    public synchronized String cookieHeader(Prober prober) throws SourceException {
        if (header != null) {
            return header;
        }
        login(prober);
        return header;
    }

    /** The current Cookie header, or null when not signed in (never opens a window). */
    public synchronized String current() {
        return header;
    }

    /** Forgets the session if it is still the one that was rejected (another thread may already have signed in again). */
    public synchronized void invalidate(String rejected) {
        if (rejected == null || rejected.equals(header)) {
            if (readsThroughBrowser != null) {
                dropBrowserReading();
            }
            header = null;
            lastStatus = "the session expired; sign in again";
        }
    }

    public synchronized int loginCount() {
        return logins;
    }

    public synchronized String status() {
        if (header != null && readsThroughBrowser != null) {
            return "signed in through the browser at " + signedInAt + " (the site signs you in with your Windows/browser login, so the app reads "
                    + host + " through that browser window, kept minimised; it closes when the app stops)";
        }
        return header == null ? lastStatus : "signed in through the browser at " + signedInAt + " (cookies for " + host + ", values not shown)";
    }

    private volatile Session openWindow;

    /** Brings an open sign-in window to the front again (the "Show the sign-in window" button). False when none is open. */
    public static boolean showOpenWindows() {
        boolean any = false;
        for (BrowserLogin login : ALL) {
            Session w = login.openWindow;
            if (w != null && w.alive()) {
                w.bringToFront();
                any = true;
            }
        }
        return any;
    }

    /** True while a sign-in window is open and waiting for the person. */
    public static boolean anyWindowOpen() {
        return ALL.stream().anyMatch(l -> l.openWindow != null && l.openWindow.alive());
    }

    private void login(Prober prober) throws SourceException {
        Instant deadline = clock.instant().plus(timeout);
        lastStatus = "waiting for you to sign in in the browser window";
        LOG.log(System.Logger.Level.INFO, "Opening the browser sign-in window at {0}", startUrl);
        Session session;
        try {
            session = launcher.open(startUrl);
        } catch (IOException e) {
            lastStatus = "the browser could not be opened";
            LOG.log(System.Logger.Level.WARNING, "The browser sign-in window could not be opened: " + e.getMessage(), e);
            throw new SourceException(SourceException.Kind.AUTH_FAILED, "Could not open a browser for signing in: " + e.getMessage(), null, e);
        }
        boolean keep = false;
        try {
            openWindow = session;
            Instant opened = clock.instant();
            int fronted = 0;
            while (clock.instant().isBefore(deadline)) {
                // Bring the window to the front right away and twice more early on: windows opened by a background
                // program often appear behind the current one, and the person may not notice it.
                long since = Duration.between(opened, clock.instant()).toMillis();
                if ((fronted == 0 && since >= 800) || (fronted == 1 && since >= 3000) || (fronted == 2 && since >= 8000)) {
                    session.bringToFront();
                    fronted++;
                }
                // 1. The browser's cookies work for the app's own requests: take them and close the window.
                String candidate;
                try {
                    candidate = headerFor(session.cookies());
                } catch (IOException e) {
                    candidate = "";
                }
                if (!candidate.isEmpty() && prober.accepted(candidate)) {
                    LOG.log(System.Logger.Level.INFO, "Signed in through the browser; closing the sign-in window");
                    signedInAt = clock.instant(); // before header: readers that see the header also see when
                    header = candidate;
                    logins++;
                    lastStatus = "signed in";
                    return;
                }
                // 2. The window shows the site's content, but its cookies alone are not enough (Windows/Kerberos sign-in,
                //    client certificate, ...): read through the browser itself, keeping the window minimised.
                if (showsContentInBrowser(session)) {
                    LOG.log(System.Logger.Level.INFO, "The site signs you in inside the browser (no usable cookie): the app reads "
                            + "through the browser window, now minimised");
                    session.minimize();
                    readsThroughBrowser = session;
                    keep = true;
                    signedInAt = clock.instant();
                    header = THROUGH_BROWSER;
                    logins++;
                    lastStatus = "signed in";
                    return;
                }
                if (!session.alive()) {
                    lastStatus = "the sign-in window was closed before signing in";
                    throw new SourceException(SourceException.Kind.AUTH_FAILED,
                            "The sign-in window was closed before the app could confirm the sign-in. Try again and leave the window open: "
                                    + "it closes (or minimises) by itself as soon as the site shows its content.");
                }
                sleep(pollEvery);
            }
        } finally {
            openWindow = null;
            if (!keep) {
                session.close(); // the window closes as soon as the site lets you in
            }
        }
        lastStatus = "sign-in timed out";
        throw new SourceException(SourceException.Kind.AUTH_FAILED,
                "Nobody finished signing in within " + timeout.toMinutes() + " minutes (source.browser.timeoutSeconds).");
    }

    /** Whether the content addresses load inside the browser window (the site lets the browser in). */
    private boolean showsContentInBrowser(Session session) {
        List<String> urls = contentUrls.isEmpty() ? List.of(origin + "/") : contentUrls;
        for (String url : urls) {
            try {
                HttpSourceClient.Response r = session.fetchInPage(url, origin, Map.of());
                if (r != null && (showsContent(r) || (webPages && showsPage(r)))) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                // the page is still on the login, or the window is busy
            }
        }
        return false;
    }

    /** The cookies that the browser would send to this host, as one Cookie header. */
    String headerFor(List<Cookie> cookies) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (Cookie c : cookies) {
            if (c.name() == null || c.name().isBlank() || c.value() == null || !domainMatches(host, c.domain())) {
                continue;
            }
            byName.putIfAbsent(c.name(), c.value());
        }
        List<String> parts = new ArrayList<>();
        byName.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join("; ", parts);
    }

    static boolean domainMatches(String host, String cookieDomain) {
        if (cookieDomain == null || cookieDomain.isBlank()) {
            return false;
        }
        String d = cookieDomain.toLowerCase(Locale.ROOT);
        if (d.startsWith(".")) {
            d = d.substring(1);
        }
        return host.equals(d) || host.endsWith("." + d);
    }

    private static void sleep(Duration d) throws SourceException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.FATAL, "Interrupted while waiting for the browser sign-in", null, e);
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ================================================================== Edge / Chrome over the DevTools Protocol

    /** Starts Edge or Chrome with its own profile and a local-only DevTools port, and reads cookies through it. */
    public static final class ChromeLauncher implements Launcher {
        private final ConfluenceOptions.Browser options;
        private final Function<String, String> env;

        public ChromeLauncher(ConfluenceOptions.Browser options, Function<String, String> env) {
            this.options = options;
            this.env = env == null ? System::getenv : env;
        }

        @Override
        public Session open(String startUrl) throws IOException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux") && insideWsl()) {
                throw new IOException("the app runs inside WSL (Linux on Windows), where it cannot open your Windows browser. "
                        + "Start it from a normal Windows terminal (PowerShell or Command Prompt) with 'npm start'");
            }
            if (os.contains("linux") && blank(env.apply("DISPLAY")) && blank(env.apply("WAYLAND_DISPLAY"))) {
                throw new IOException("there is no screen on this computer (no DISPLAY): browser sign-in needs a desktop session");
            }
            Path exe = findBrowser(options.path(), os, env);
            if (exe == null) {
                throw new IOException(blank(options.path())
                        ? "no Edge or Chrome was found on this computer. Set 'Browser program' (source.browser.path) to the browser's .exe, "
                        + "for example C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
                        : "the browser program '" + options.path() + "' (source.browser.path) does not exist or cannot be started");
            }
            LOG.log(System.Logger.Level.INFO, "Browser for sign-in: {0}", exe);
            Path profile = options.profileDir().isBlank()
                    ? Path.of(System.getProperty("user.home"), ".questioncrawler", "browser-profile")
                    : Path.of(options.profileDir());
            try {
                return launch(exe, profile, os, startUrl);
            } catch (ProfileBusy busy) {
                // An earlier sign-in window still holds the app's profile (the new browser handed over to it and quit):
                // use a spare profile so a window really opens. Your company login may ask once more there.
                return launch(exe, Path.of(profile + "-" + Long.toString(System.currentTimeMillis(), 36)), os, startUrl);
            }
        }

        /** The browser quit at once: another browser already uses this profile folder. */
        private static final class ProfileBusy extends IOException {
            ProfileBusy() {
                super("the app's browser profile is in use by another window");
            }
        }

        private Session launch(Path exe, Path profile, String os, String startUrl) throws IOException {
            Files.createDirectories(profile);
            Path portFile = profile.resolve("DevToolsActivePort");
            Files.deleteIfExists(portFile);
            List<String> cmd = new ArrayList<>(List.of(exe.toString(),
                    "--user-data-dir=" + profile.toAbsolutePath(),
                    "--remote-debugging-port=0",
                    "--remote-debugging-address=127.0.0.1",
                    "--no-first-run", "--no-default-browser-check", "--new-window",
                    "--window-size=1100,800", "--window-position=120,80"));
            if (os.contains("linux") && "root".equals(System.getProperty("user.name"))) {
                cmd.add("--no-sandbox"); // Chrome refuses to run as root otherwise (containers)
            }
            cmd.add(startUrl);
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            String wsUrl = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (System.nanoTime() < until && wsUrl == null) {
                if (Files.exists(portFile)) {
                    List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
                    if (lines.size() >= 2 && !lines.get(0).isBlank()) {
                        wsUrl = "ws://127.0.0.1:" + lines.get(0).strip() + lines.get(1).strip();
                    }
                }
                if (!process.isAlive() && wsUrl == null) {
                    throw new ProfileBusy();
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroy();
                    throw new IOException("interrupted");
                }
            }
            if (wsUrl == null) {
                process.destroy();
                throw new IOException("the browser opened, but does not let the app take over the login (its local remote-control "
                        + "(DevTools) port stayed off; company policy often switches it off for Edge). Set 'Browser program' to Chrome, or ask IT "
                        + "to allow remote debugging for this app's own browser profile");
            }
            LOG.log(System.Logger.Level.INFO, "Sign-in window open (profile {0})", profile);
            return new CdpSession(process, wsUrl);
        }

        static Path findBrowser(String configured, String os, Function<String, String> env) {
            if (!blank(configured)) {
                Path p = Path.of(configured);
                return Files.isExecutable(p) ? p : null;
            }
            List<String> candidates = new ArrayList<>();
            if (os.contains("win")) {
                for (String root : new String[] {env.apply("ProgramFiles(x86)"), env.apply("ProgramFiles"), env.apply("LOCALAPPDATA")}) {
                    if (!blank(root)) {
                        candidates.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
                        candidates.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
                    }
                }
            } else if (os.contains("mac")) {
                candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
                candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
                candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            } else {
                String path = env.apply("PATH");
                for (String dir : (path == null ? "/usr/bin" : path).split(java.io.File.pathSeparator)) {
                    for (String name : new String[] {"microsoft-edge", "google-chrome", "google-chrome-stable", "chromium", "chromium-browser"}) {
                        candidates.add(dir + "/" + name);
                    }
                }
            }
            for (String c : candidates) {
                Path p = Path.of(c);
                if (Files.isExecutable(p)) {
                    return p;
                }
            }
            if (os.contains("win")) { // installs elsewhere register themselves under App Paths
                for (String exe : new String[] {"msedge.exe", "chrome.exe"}) {
                    for (String hive : new String[] {"HKCU", "HKLM"}) {
                        Path p = registryAppPath(hive + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exe);
                        if (p != null) {
                            return p;
                        }
                    }
                }
            }
            return null;
        }

        /** The default value of a Windows registry key holding a program path (App Paths), or null. */
        static Path registryAppPath(String key) {
            try {
                Process p = new ProcessBuilder("reg", "query", key, "/ve").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    return null;
                }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("REG_(?:EXPAND_)?SZ\\s+(.+)").matcher(out);
                if (m.find()) {
                    Path path = Path.of(m.group(1).strip().replace("\"", ""));
                    return Files.isRegularFile(path) ? path : null;
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        }

        static boolean insideWsl() {
            try {
                return Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT).contains("microsoft");
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    /** One browser process, talked to over its local DevTools WebSocket. */
    static final class CdpSession implements Session {
        private final Process process;
        private final WebSocket socket;
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();

        CdpSession(Process process, String wsUrl) throws IOException {
            this.process = process;
            try {
                this.socket = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build().newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(wsUrl), new Listener()).get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                process.destroy();
                throw new IOException("could not connect to the browser's local DevTools port: " + e.getMessage(), e);
            }
        }

        @Override
        public List<Cookie> cookies() throws IOException {
            JsonNode result = call("Storage.getCookies");
            List<Cookie> out = new ArrayList<>();
            for (JsonNode c : result.path("cookies")) {
                out.add(new Cookie(c.path("name").asText(null), c.path("value").asText(null), c.path("domain").asText(null), c.path("path").asText("/")));
            }
            return out;
        }

        @Override
        public boolean alive() {
            // The live connection to the browser, not the program that was started: on Windows the started program
            // can hand over to another browser process and exit while the window stays open.
            return !closed && !socket.isInputClosed();
        }

        private volatile boolean closed;

        @Override
        public void close() {
            try {
                call("Browser.close");
            } catch (IOException ignored) {
                // the window may already be closed
            }
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
            }
        }

        @Override
        public void bringToFront() {
            try {
                String target = null;
                for (JsonNode t : call("Target.getTargets", "{}").path("targetInfos")) {
                    if ("page".equals(t.path("type").asText())) {
                        target = t.path("targetId").asText();
                        break;
                    }
                }
                if (target == null) {
                    return;
                }
                String quoted = Json.MAPPER.writeValueAsString(target);
                try {
                    // Minimise and restore: the reliable way to raise a window above others on Windows and macOS.
                    int window = call("Browser.getWindowForTarget", "{\"targetId\":" + quoted + "}").path("windowId").asInt();
                    call("Browser.setWindowBounds", "{\"windowId\":" + window + ",\"bounds\":{\"windowState\":\"minimized\"}}");
                    call("Browser.setWindowBounds", "{\"windowId\":" + window + ",\"bounds\":{\"windowState\":\"normal\"}}");
                } catch (IOException ignored) {
                    // older browsers: activating the tab below still helps
                }
                call("Target.activateTarget", "{\"targetId\":" + quoted + "}");
            } catch (IOException ignored) {
                // bringing to front is a convenience; the window is open either way
            }
        }

        private volatile String pageSession;

        /** A DevTools session attached to the browser's page (tab), for running things inside it. */
        private String pageSession() throws IOException {
            String s = pageSession;
            if (s != null) {
                return s;
            }
            String target = null;
            for (JsonNode t : call("Target.getTargets", "{}").path("targetInfos")) {
                if ("page".equals(t.path("type").asText())) {
                    target = t.path("targetId").asText();
                    break;
                }
            }
            if (target == null) {
                target = call("Target.createTarget", "{\"url\":\"about:blank\"}").path("targetId").asText();
            }
            s = call("Target.attachToTarget", "{\"targetId\":" + Json.MAPPER.writeValueAsString(target) + ",\"flatten\":true}").path("sessionId").asText();
            pageSession = s;
            return s;
        }

        private String evaluate(String expression, int seconds) throws IOException {
            String params = "{\"expression\":" + Json.MAPPER.writeValueAsString(expression) + ",\"awaitPromise\":true,\"returnByValue\":true}";
            JsonNode result;
            try {
                result = call("Runtime.evaluate", params, pageSession(), seconds);
            } catch (IOException e) {
                pageSession = null; // the tab may have changed: attach again next time
                throw e;
            }
            if (result.has("exceptionDetails")) {
                throw new IOException("the page could not run it: " + result.path("exceptionDetails").path("text").asText());
            }
            return result.path("result").path("value").asText(null);
        }

        @Override
        public synchronized HttpSourceClient.Response fetchInPage(String url, String origin, Map<String, String> headers) throws IOException {
            // Run from a page of the site itself, so the browser sends everything it signs in with.
            String at = evaluate("location.origin", 10);
            if (at == null || !at.equalsIgnoreCase(origin)) {
                String here = evaluate("location.href", 10);
                boolean onLogin = here != null && LOGIN_URL.matcher(here).find();
                if (onLogin) {
                    return null; // the person is still signing in: do not navigate away from the login
                }
                call("Page.navigate", "{\"url\":" + Json.MAPPER.writeValueAsString(origin + "/") + "}", pageSession(), 20);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < until) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                    String o = evaluate("document.readyState === 'complete' ? location.origin : ''", 10);
                    if (o != null && o.equalsIgnoreCase(origin)) {
                        break;
                    }
                }
            }
            String js = "(async (u, h) => { try { const r = await fetch(u, { credentials: 'include', headers: h });"
                    + " const t = await r.text(); return JSON.stringify({ s: r.status, u: r.url, ty: r.headers.get('content-type') || '', r: r.redirected, b: t }); }"
                    + " catch (e) { return JSON.stringify({ s: 0, e: String(e) }); } })(" + Json.MAPPER.writeValueAsString(url) + ", "
                    + Json.MAPPER.writeValueAsString(headers == null ? Map.of() : headers) + ")";
            String raw = evaluate(js, 120);
            JsonNode r = Json.MAPPER.readTree(raw == null ? "{}" : raw);
            int status = r.path("s").asInt(0);
            if (status == 0) {
                // Blocked on the way, typically a redirect to the company login on another site: sign in again.
                return new HttpSourceClient.Response(302, "", null, "text/html", "about:login", null, url, List.of(), "redirect inside the browser");
            }
            String finalUrl = r.path("u").asText(url);
            List<String> redirects = r.path("r").asBoolean(false) ? List.of("redirect to " + finalUrl) : List.of();
            return new HttpSourceClient.Response(status, r.path("b").asText(""), null, r.path("ty").asText(""), null, null, finalUrl, redirects, null);
        }

        @Override
        public synchronized PageView openPage(String url, Duration wait) throws IOException {
            call("Page.enable", null, pageSession(), 10);
            call("Page.navigate", "{\"url\":" + Json.MAPPER.writeValueAsString(url) + "}", pageSession(), 30);
            long until = System.nanoTime() + wait.toNanos();
            PageView v = null;
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                try {
                    v = currentPage();
                } catch (IOException e) {
                    continue; // the page is being replaced (a redirect): ask again
                }
                if (v != null && v.complete() && !"about:blank".equals(v.url())) {
                    break;
                }
            }
            try {
                Thread.sleep(800); // let the page's scripts fill in the content
                v = currentPage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // keep the last view
            }
            return v;
        }

        @Override
        public PageView currentPage() throws IOException {
            String raw = evaluate("JSON.stringify({u: location.href, t: document.title, r: document.readyState,"
                    + " h: document.documentElement ? document.documentElement.outerHTML : ''})", 20);
            JsonNode n = Json.MAPPER.readTree(raw == null ? "{}" : raw);
            return new PageView(n.path("u").asText(""), n.path("t").asText(""), n.path("h").asText(""), "complete".equals(n.path("r").asText()));
        }

        /** True when the window shows a company sign-in (a login address, or a page with a password field / SAML form). */
        public static boolean onSignIn(PageView v) {
            if (v == null) {
                return false;
            }
            String where = v.url() == null ? "" : v.url().split("[?#]", 2)[0];
            String h = v.html() == null ? "" : v.html().toLowerCase(Locale.ROOT);
            return LOGIN_URL.matcher(where).find() || h.contains("type=\"password\"") || h.contains("type='password'")
                    || h.contains("samlrequest") || h.contains("samlresponse");
        }

        @Override
        public void minimize() {
            try {
                String target = null;
                for (JsonNode t : call("Target.getTargets", "{}").path("targetInfos")) {
                    if ("page".equals(t.path("type").asText())) {
                        target = t.path("targetId").asText();
                        break;
                    }
                }
                if (target != null) {
                    int window = call("Browser.getWindowForTarget", "{\"targetId\":" + Json.MAPPER.writeValueAsString(target) + "}").path("windowId").asInt();
                    call("Browser.setWindowBounds", "{\"windowId\":" + window + ",\"bounds\":{\"windowState\":\"minimized\"}}");
                }
            } catch (IOException ignored) {
                // a visible window still works
            }
        }

        private JsonNode call(String method) throws IOException {
            return call(method, null);
        }

        private JsonNode call(String method, String paramsJson) throws IOException {
            return call(method, paramsJson, null, 15);
        }

        private JsonNode call(String method, String paramsJson, String sessionId, int seconds) throws IOException {
            if (closed) {
                throw new IOException("the browser window is closed");
            }
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> answer = new CompletableFuture<>();
            pending.put(id, answer);
            try {
                String message = "{\"id\":" + id + ",\"method\":\"" + method + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson)
                        + (sessionId == null ? "" : ",\"sessionId\":\"" + sessionId + "\"") + "}";
                synchronized (socket) { // a WebSocket sends one message at a time
                    socket.sendText(message, true).get(10, TimeUnit.SECONDS);
                }
                JsonNode reply = answer.get(seconds, TimeUnit.SECONDS);
                if (reply.has("error")) {
                    throw new IOException("browser answered " + reply.path("error").path("message").asText("an error"));
                }
                return reply.path("result");
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("no answer from the browser: " + e.getClass().getSimpleName(), e);
            } finally {
                pending.remove(id);
            }
        }

        private final class Listener implements WebSocket.Listener {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                closed = true; // the person closed the browser
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                closed = true;
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String text = buffer.toString();
                    buffer.setLength(0);
                    try {
                        JsonNode node = Json.MAPPER.readTree(text);
                        CompletableFuture<JsonNode> f = node.has("id") ? pending.get(node.path("id").asInt()) : null;
                        if (f != null) {
                            f.complete(node);
                        }
                    } catch (Exception ignored) {
                        // events and unreadable messages are not needed
                    }
                }
                ws.request(1);
                return null;
            }
        }
    }
}
