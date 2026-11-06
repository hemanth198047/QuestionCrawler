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
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Hardened HTTP transport for live adapters.
 *
 * <ul>
 *   <li>Only configured hosts and path prefixes are contacted; redirects are never followed.</li>
 *   <li>TLS verification stays on. An organization CA certificate can be added to the trust set.</li>
 *   <li>The token is read from an environment variable at request time and is never logged.</li>
 *   <li>HTTP status codes are mapped to {@link SourceException.Kind}; retrying is the caller's job.</li>
 * </ul>
 */
public final class HttpSourceClient {

    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    /** Raw result of one exchange; {@code retryAfter} is the unparsed Retry-After header or null. */
    public record Response(int status, String body, String retryAfter) {}

    private final SourceSettings settings;
    private final HttpClient http;
    private final Function<String, String> env;

    public HttpSourceClient(SourceSettings settings) {
        this(settings, System::getenv);
    }

    public HttpSourceClient(SourceSettings settings, Function<String, String> env) {
        this.settings = settings;
        this.env = env;
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
        URI uri = checkAllowed(url);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.timeout())
                .header("Accept", "application/json, text/html;q=0.8")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "QuestionCrawler/0.1")
                .GET();
        applyAuth(request);
        extraHeaders.forEach(request::header);
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
            return new Response(response.statusCode(), readBounded(in), response.headers().firstValue("Retry-After").orElse(null));
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

    private void applyAuth(HttpRequest.Builder request) throws SourceException {
        if (!settings.authMethod().equalsIgnoreCase("token")) {
            return;
        }
        String token = settings.tokenEnv() == null ? null : env.apply(settings.tokenEnv());
        if (token == null || token.isBlank()) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "Environment variable '" + settings.tokenEnv() + "' is not set; cannot authenticate");
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
                    "HTTP " + status + " redirect from " + where + " was not followed (a login redirect means authentication is required)");
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
