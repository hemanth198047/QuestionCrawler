package com.questioncrawler.core.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Hardened HTTP transport for live adapters.
 *
 * <ul>
 *   <li>Only configured hosts and path prefixes are contacted. Redirects are followed by default (see {@link Redirects}); the setting can restrict them to the same host or switch them off.</li>
 *   <li>TLS verification stays on. An organization CA certificate can be added to the trust set.</li>
 *   <li>The token is read from an environment variable at request time and is never logged.</li>
 *   <li>HTTP status codes are mapped to {@link SourceException.Kind}; retrying is the caller's job.</li>
 * </ul>
 */
public final class HttpSourceClient {

    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    /** Raw result of one exchange; {@code retryAfter} is the unparsed Retry-After header or null. */
    /**
     * @param finalUrl     the address that finally answered (after redirects); contains the query string, so never show it as it is
     * @param redirects    one line per redirect that was followed ("307 to https://host/path"), without query strings
     * @param redirectStop why a redirect was NOT followed (null if there was none or it was followed)
     */
    public record Response(int status, String body, String retryAfter, String contentType, String location, String wwwAuthenticate,
                           String finalUrl, List<String> redirects, String redirectStop) {
        public Response(int status, String body, String retryAfter) {
            this(status, body, retryAfter, null, null, null, null, List.of(), null);
        }

        public Response(int status, String body, String retryAfter, String contentType, String location) {
            this(status, body, retryAfter, contentType, location, null, null, List.of(), null);
        }

        public Response(int status, String body, String retryAfter, String contentType, String location, String wwwAuthenticate) {
            this(status, body, retryAfter, contentType, location, wwwAuthenticate, null, List.of(), null);
        }

        Response after(String url, List<String> chain, String stop) {
            return new Response(status, body, retryAfter, contentType, location, wwwAuthenticate, url, List.copyOf(chain), stop);
        }
    }

    private final SourceSettings settings;
    private final HttpClient http;
    private final Function<String, String> env;
    private final TokenSource bearer;

    /** Supplies a Bearer token at request time (for example a short-lived token from a Token Vending Machine). */
    @FunctionalInterface
    public interface TokenSource {
        String token() throws SourceException;
    }

    public HttpSourceClient(SourceSettings settings) {
        this(settings, System::getenv);
    }

    public HttpSourceClient(SourceSettings settings, Function<String, String> env) {
        this(settings, env, null);
    }

    /** As {@link #HttpSourceClient(SourceSettings, Function)}; {@code bearer} serves {@code source.auth.method=tvm}. */
    public HttpSourceClient(SourceSettings settings, Function<String, String> env, TokenSource bearer) {
        this.settings = settings;
        this.env = env;
        this.bearer = bearer;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (settings.caCertFile() != null) {
            builder.sslContext(buildSslContext(settings.caCertFile()));
        }
        ProxyConfig proxy = settings.proxy().orEnvironment(env);
        if (proxy.configured()) {
            builder.proxy(proxy.selector());
            java.net.Authenticator auth = proxy.authenticator(env);
            if (auth != null) {
                builder.authenticator(auth);
            }
        }
        this.http = builder.build();
    }

    /** GET that returns only on 2xx; every other outcome is thrown as a classified {@link SourceException}. */
    public Response get(String url) throws SourceException {
        Response response = exchange(url);
        if (response.status() >= 200 && response.status() < 300) {
            return response;
        }
        if (Redirects.isRedirect(response.status())) {
            throw new SourceException(SourceException.Kind.FATAL, "HTTP " + response.status() + " redirect from " + describe(checkAllowed(url)) + " was not followed: "
                    + (response.redirectStop() == null ? "unknown reason" : response.redirectStop()) + ".");
        }
        throw mapStatus(response.status(), response.retryAfter(), checkAllowed(url));
    }

    /**
     * GET that returns the response for ANY HTTP status (the body of an error can carry a more precise reason,
     * for example Stack Exchange's JSON error_id). Network failures and guard violations are still thrown.
     */
    public Response exchange(String url) throws SourceException {
        return exchange(url, Map.of());
    }

    /** As {@link #exchange(String)} with extra request headers (for example an API key sent as a header). */
    public Response exchange(String url, Map<String, String> extraHeaders) throws SourceException {
        boolean testingASession = extraHeaders.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Cookie"));
        if ("browser".equalsIgnoreCase(settings.authMethod()) && bearer != null && !testingASession) {
            bearer.token(); // sign in first (opens the window when needed), so the request below knows how to reach the site
        }
        boolean autoSignIn = bearer != null && !testingASession && (settings.authMethod().isBlank() || settings.authMethod().equalsIgnoreCase("none"));
        if (!autoSignIn) {
            return exchangeOnceRouted(url, extraHeaders, testingASession);
        }
        // No sign-in chosen, but the site may sit behind the company login: when it answers with its login (or sends the
        // request to another host's login), sign in through the browser (or use the window already open) and try again.
        Response r;
        SourceException redirectedAway = null;
        try {
            r = exchangeOnceRouted(url, extraHeaders, false);
        } catch (SourceException e) {
            if (e.getMessage() == null || !e.getMessage().contains("redirected")) {
                throw e;
            }
            r = null;
            redirectedAway = e;
        }
        if (r == null || BrowserLogin.needsSignIn(r)) {
            try {
                bearer.token();
            } catch (SourceException signInFailed) {
                // No browser here (a server, no screen, ...): keep the site's own answer and its explanation.
                if (r != null) {
                    return r;
                }
                throw redirectedAway;
            }
            r = exchangeOnceRouted(url, extraHeaders, false);
        }
        return r;
    }

    private Response exchangeOnceRouted(String url, Map<String, String> extraHeaders, boolean testingASession) throws SourceException {
        URI first = checkAllowed(url);
        // A site that only lets the browser in (Windows sign-in, client certificate, ...): read it through that window.
        BrowserLogin.Session window = testingASession ? null : BrowserLogin.transportFor(first);
        if (window != null) {
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            extraHeaders.forEach((k, v) -> {
                if (!k.equalsIgnoreCase("Cookie")) {
                    headers.put(k, v);
                }
            });
            try {
                Response viaBrowser = window.fetchInPage(url, BrowserLogin.originOf(first), headers);
                if (viaBrowser != null) {
                    return viaBrowser;
                }
            } catch (IOException e) {
                throw new SourceException(SourceException.Kind.TRANSIENT, "The browser window could not load " + describe(first) + ": " + e.getMessage(), null, e);
            }
        }
        URI current = first;
        java.util.Set<String> visited = new HashSet<>();
        visited.add(current.toString());
        List<String> chain = new ArrayList<>();
        for (int hops = 0; ; hops++) {
            Response r;
            try {
                r = exchangeOnce(current, extraHeaders);
            } catch (SourceException e) {
                if (chain.isEmpty()) {
                    throw e;
                }
                throw new SourceException(e.kind(), "The site redirected the request (" + String.join(", ", chain) + ") but the address it leads to could not be used: " + e.getMessage(),
                        e.retryAfter(), e);
            }
            if (!Redirects.isRedirect(r.status())) {
                return r.after(current.toString(), chain, null);
            }
            Redirects.Step step = Redirects.next(current, r.location(), first, settings.api().redirectMode(), visited, hops);
            if (step.follow() && !"https".equalsIgnoreCase(step.target().getScheme()) && !settings.allowInsecureHttp()) {
                step = new Redirects.Step(null, "the redirect leads to unencrypted http (" + Redirects.show(step.target()) + ") and source.allowInsecureHttp is off");
            }
            if (!step.follow()) {
                return r.after(current.toString(), chain, step.stopReason());
            }
            chain.add(r.status() + " to " + Redirects.show(step.target()));
            visited.add(step.target().toString());
            current = step.target();
        }
    }

    private Response exchangeOnce(URI uri, Map<String, String> extraHeaders) throws SourceException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.timeout())
                .header("Accept", "application/json, text/html;q=0.8")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "QuestionCrawler/0.1")
                .GET();
        applyAuth(request, extraHeaders, uri);
        extraHeaders.forEach(request::setHeader);
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new SourceException(SourceException.Kind.TRANSIENT, "Request timed out: " + describe(uri), null, e);
        } catch (IOException e) {
            throw new SourceException(SourceException.Kind.TRANSIENT, "Network error contacting " + describe(uri) + ": " + e.getClass().getSimpleName(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.FATAL, "Interrupted", null, e);
        }
        boolean gzipHeader = response.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
        try (InputStream raw = response.body()) {
            // Some proxies strip the Content-Encoding header but leave the body compressed: sniff the gzip signature as well.
            java.io.PushbackInputStream peek = new java.io.PushbackInputStream(raw, 2);
            byte[] sig = peek.readNBytes(2);
            peek.unread(sig);
            boolean gzipSignature = sig.length == 2 && (sig[0] & 0xff) == 0x1f && (sig[1] & 0xff) == 0x8b;
            InputStream in = gzipHeader || gzipSignature ? new java.util.zip.GZIPInputStream(peek) : peek;
            return new Response(response.statusCode(), readBounded(in), response.headers().firstValue("Retry-After").orElse(null),
                    response.headers().firstValue("Content-Type").orElse(null), response.headers().firstValue("Location").orElse(null),
                    response.headers().firstValue("WWW-Authenticate").orElse(null));
        } catch (IOException e) {
            throw new SourceException(SourceException.Kind.TRANSIENT, "Error reading response from " + describe(uri), null, e);
        }
    }

    // ---------------------------------------------------------------- guards

    URI checkAllowed(String url) throws SourceException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SourceException(SourceException.Kind.FATAL, "Invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && settings.allowInsecureHttp())) {
            throw new SourceException(SourceException.Kind.FATAL, "Only https URLs are allowed (set source.allowInsecureHttp=true for local testing only)");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (settings.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            throw new SourceException(SourceException.Kind.FATAL, "Host not in source.allowedHosts: " + host);
        }
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        if (path.contains("..") || settings.allowedPathPrefixes().stream().noneMatch(path::startsWith)) {
            throw new SourceException(SourceException.Kind.FATAL, "Path not in source.allowedPathPrefixes: " + path);
        }
        return uri;
    }

    private void applyAuth(HttpRequest.Builder request, Map<String, String> extraHeaders, URI uri) throws SourceException {
        String method = settings.authMethod();
        if (method.equalsIgnoreCase("none") || method.isBlank()) {
            // No sign-in chosen: still send the browser session when someone signed in to this site (automatic sign-in).
            String cookie = extraHeaders.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Cookie")) ? null : BrowserLogin.sessionFor(uri.getHost());
            if (cookie != null && !cookie.equals(BrowserLogin.THROUGH_BROWSER)) {
                request.header("Cookie", cookie);
            }
            return;
        }
        if (method.equalsIgnoreCase("browser")) {
            if (extraHeaders.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Cookie"))) {
                return; // the caller is testing a specific session
            }
            String cookie = bearer != null ? bearer.token() : BrowserLogin.sessionFor(uri.getHost());
            if (cookie == null || cookie.isBlank()) {
                throw new SourceException(SourceException.Kind.AUTH_FAILED,
                        "Not signed in yet: press 'Sign in with my browser' on the Connect screen (your company login page opens), then try again.");
            }
            request.header("Cookie", cookie);
            return;
        }
        if (method.equalsIgnoreCase("tvm")) {
            if (bearer == null) {
                throw new SourceException(SourceException.Kind.FATAL,
                        "source.auth.method=tvm needs source.confluence.tvm.url (the Token Vending Machine address)");
            }
            request.header(settings.authHeaderName(), "Bearer " + bearer.token());
            return;
        }
        String token = settings.tokenEnv() == null ? null : env.apply(settings.tokenEnv());
        if (token == null || token.isBlank()) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "Environment variable '" + settings.tokenEnv() + "' is not set; cannot authenticate");
        }
        if (method.equalsIgnoreCase("basic-env") || method.equalsIgnoreCase("confluence-api-token")) {
            String user = settings.authUser();
            if ((user == null || user.isBlank()) && settings.authUserEnv() != null) {
                user = env.apply(settings.authUserEnv());
            }
            if (user == null || user.isBlank()) {
                throw new SourceException(SourceException.Kind.AUTH_FAILED,
                        "Basic auth needs a username/email (set source.confluence.email / source.jive.username or their *Env variants)");
            }
            String basic = java.util.Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
            request.header(settings.authHeaderName(), "Basic " + basic);
            return;
        }
        if (!method.equalsIgnoreCase("token")) {
            throw new SourceException(SourceException.Kind.FATAL,
                    "Unknown source.auth.method '" + method + "' (use none, token, basic-env, tvm, or browser)");
        }
        String scheme = settings.authScheme();
        request.header(settings.authHeaderName(), scheme == null || scheme.isBlank() ? token : scheme + " " + token);
    }

    // ---------------------------------------------------------------- mapping

    /** Maps an HTTP status to a classified failure (never includes query strings). */
    public SourceException mapStatus(int status, String retryAfterHeader, URI uri) {
        String where = describe(uri);
        if (status == 401) {
            return new SourceException(SourceException.Kind.AUTH_FAILED, "HTTP 401 from " + where + ": credentials missing, expired or rejected");
        }
        if (status == 407) {
            return new SourceException(SourceException.Kind.AUTH_FAILED, "HTTP 407 from the web proxy: it wants you to sign in. Set the proxy user/password variable names in the settings (or ask IT for an allow-listed route).");
        }
        if (status == 403) {
            return new SourceException(SourceException.Kind.FORBIDDEN, "HTTP 403 from " + where + ": access refused");
        }
        if (status == 404) {
            return new SourceException(SourceException.Kind.NOT_FOUND, "HTTP 404 from " + where);
        }
        if (status == 410) {
            return new SourceException(SourceException.Kind.GONE, "HTTP 410 from " + where + ": resource reported as deleted");
        }
        if (status == 429 || status == 503) {
            Duration retryAfter = parseRetryAfter(retryAfterHeader);
            return new SourceException(SourceException.Kind.RATE_LIMITED, "HTTP " + status + " from " + where, retryAfter, null);
        }
        if (status >= 300 && status < 400) {
            return new SourceException(SourceException.Kind.FATAL,
                    "HTTP " + status + " redirect from " + where + " was not followed (see the setting 'Follow redirects'); a redirect to a sign-in page means authentication is required");
        }
        if (status >= 500) {
            return new SourceException(SourceException.Kind.TRANSIENT, "HTTP " + status + " from " + where);
        }
        return new SourceException(SourceException.Kind.FATAL, "Unexpected HTTP " + status + " from " + where);
    }

    /** Supports both forms allowed by RFC 9110: delay in seconds, or an HTTP-date. */
    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(trimmed)));
        } catch (NumberFormatException ignored) {
            // maybe an HTTP-date
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration wait = Duration.between(ZonedDateTime.now(when.getZone()), when);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Host and path only: query strings can carry secrets and are never put in messages. */
    private static String describe(URI uri) {
        return uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
    }

    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("Response exceeds " + MAX_BODY_BYTES + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- TLS

    /** Default trust store PLUS the organization CA. Certificate verification is never disabled. */
    private static SSLContext buildSslContext(java.nio.file.Path caFile) {
        try {
            KeyStore combined = KeyStore.getInstance(KeyStore.getDefaultType());
            combined.load(null, null);
            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            int i = 0;
            for (var tm : defaults.getTrustManagers()) {
                if (tm instanceof javax.net.ssl.X509TrustManager x509) {
                    for (X509Certificate cert : x509.getAcceptedIssuers()) {
                        combined.setCertificateEntry("default-" + i++, cert);
                    }
                }
            }
            try (InputStream in = Files.newInputStream(caFile)) {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                Collection<? extends Certificate> certs = factory.generateCertificates(in);
                for (Certificate cert : certs) {
                    combined.setCertificateEntry("org-ca-" + i++, cert);
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(combined);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load CA certificate " + caFile + ": " + e.getMessage(), e);
        }
    }
}
