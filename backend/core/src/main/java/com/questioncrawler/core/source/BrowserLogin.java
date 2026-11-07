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

    /** Checks whether the site accepts a Cookie header (for example: one cheap API call answers 200). */
    @FunctionalInterface
    public interface Prober {
        boolean accepted(String cookieHeader) throws SourceException;
    }

    /** A running browser window that can report its cookies. Replaceable in tests. */
    public interface Session extends AutoCloseable {
        List<Cookie> cookies() throws IOException;

        boolean alive();

        @Override
        void close();
    }

    /** Opens a browser window at an address. Replaceable in tests. */
    @FunctionalInterface
    public interface Launcher {
        Session open(String startUrl) throws IOException;
    }

    public record Cookie(String name, String value, String domain, String path) {}

    private static final Map<String, BrowserLogin> BY_SITE = new ConcurrentHashMap<>();

    private final String host;
    private final String startUrl;
    private final Launcher launcher;
    private final Duration timeout;
    private final Duration pollEvery;
    private final Clock clock;
    private String header;
    private Instant signedInAt;
    private int logins;
    private String lastStatus = "not signed in yet";

    public BrowserLogin(String siteBase, String startUrl, Launcher launcher, Duration timeout, Duration pollEvery, Clock clock) {
        this.host = URI.create(siteBase).getHost().toLowerCase(Locale.ROOT);
        this.startUrl = startUrl;
        this.launcher = launcher;
        this.timeout = timeout;
        this.pollEvery = pollEvery;
        this.clock = clock;
    }

    /**
     * One shared sign-in per site and settings for the whole app, so Check access, Show my spaces and Collect reuse the
     * same session instead of opening a window each.
     */
    public static BrowserLogin forSite(String siteBase, ConfluenceOptions options, Function<String, String> env) {
        ConfluenceOptions.Browser b = options.browser();
        String start = b.loginUrl().isBlank() ? trimSlash(siteBase) + (options.cloud() ? "/wiki/" : "/") : b.loginUrl();
        String key = siteBase.toLowerCase(Locale.ROOT) + "|" + start + "|" + b.path() + "|" + b.profileDir();
        return BY_SITE.computeIfAbsent(key, k -> new BrowserLogin(siteBase, start,
                new ChromeLauncher(b, env), Duration.ofSeconds(b.timeoutSeconds()), Duration.ofSeconds(2), Clock.systemUTC()));
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
            header = null;
            lastStatus = "the session expired; sign in again";
        }
    }

    public synchronized int loginCount() {
        return logins;
    }

    public synchronized String status() {
        return header == null ? lastStatus : "signed in through the browser at " + signedInAt + " (cookies for " + host + ", values not shown)";
    }

    private void login(Prober prober) throws SourceException {
        Instant deadline = clock.instant().plus(timeout);
        lastStatus = "waiting for you to sign in in the browser window";
        try (Session session = launcher.open(startUrl)) {
            while (clock.instant().isBefore(deadline)) {
                String candidate = headerFor(session.cookies());
                if (!candidate.isEmpty() && prober.accepted(candidate)) {
                    header = candidate;
                    signedInAt = clock.instant();
                    logins++;
                    lastStatus = "signed in";
                    return;
                }
                if (!session.alive()) {
                    lastStatus = "the sign-in window was closed before signing in";
                    throw new SourceException(SourceException.Kind.AUTH_FAILED,
                            "The sign-in window was closed before Confluence accepted the login. Try again and finish signing in.");
                }
                sleep(pollEvery);
            }
        } catch (IOException e) {
            lastStatus = "the browser could not be opened";
            throw new SourceException(SourceException.Kind.AUTH_FAILED, "Could not open a browser for signing in: " + e.getMessage(), null, e);
        }
        lastStatus = "sign-in timed out";
        throw new SourceException(SourceException.Kind.AUTH_FAILED,
                "Nobody finished signing in within " + timeout.toMinutes() + " minutes (source.confluence.browser.timeoutSeconds).");
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
            if (os.contains("linux") && blank(env.apply("DISPLAY")) && blank(env.apply("WAYLAND_DISPLAY"))) {
                throw new IOException("there is no screen on this computer (no DISPLAY): browser sign-in needs a desktop session");
            }
            Path exe = findBrowser(options.path(), os, env);
            if (exe == null) {
                throw new IOException("no Edge or Chrome was found. Install one, or set source.confluence.browser.path to the browser program");
            }
            Path profile = options.profileDir().isBlank()
                    ? Path.of(System.getProperty("user.home"), ".questioncrawler", "browser-profile")
                    : Path.of(options.profileDir());
            Files.createDirectories(profile);
            Path portFile = profile.resolve("DevToolsActivePort");
            Files.deleteIfExists(portFile);
            List<String> cmd = new ArrayList<>(List.of(exe.toString(),
                    "--user-data-dir=" + profile.toAbsolutePath(),
                    "--remote-debugging-port=0",
                    "--remote-debugging-address=127.0.0.1",
                    "--no-first-run", "--no-default-browser-check", "--new-window"));
            if (os.contains("linux") && "root".equals(System.getProperty("user.name"))) {
                cmd.add("--no-sandbox"); // Chrome refuses to run as root otherwise (containers)
            }
            cmd.add(startUrl);
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            String wsUrl = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < until && wsUrl == null) {
                if (Files.exists(portFile)) {
                    List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
                    if (lines.size() >= 2 && !lines.get(0).isBlank()) {
                        wsUrl = "ws://127.0.0.1:" + lines.get(0).strip() + lines.get(1).strip();
                    }
                }
                if (!process.isAlive() && wsUrl == null) {
                    throw new IOException("the browser stopped right away. If it is already open with the same profile, close that window; or set source.confluence.browser.profileDir to another folder");
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
                throw new IOException("the browser did not open its local DevTools port within 30 seconds");
            }
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
            return null;
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
            return process.isAlive();
        }

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

        private JsonNode call(String method) throws IOException {
            if (!process.isAlive()) {
                throw new IOException("the browser window is closed");
            }
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> answer = new CompletableFuture<>();
            pending.put(id, answer);
            try {
                socket.sendText("{\"id\":" + id + ",\"method\":\"" + method + "\"}", true).get(10, TimeUnit.SECONDS);
                JsonNode reply = answer.get(15, TimeUnit.SECONDS);
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
