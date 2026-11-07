package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived Bearer tokens from a company Token Vending Machine (TVM) that signs you in with Kerberos/SPNEGO.
 *
 * <p>Equivalent of {@code curl -u : --negotiate https://atlassian-tvm.example.com/exchange/token/confluence/<site>}.
 * The response carries {@code access_token}, {@code token_type} (Bearer) and {@code expires_in} (seconds, typically 900).
 * The token is cached in memory only (never written to disk or logs) and renewed shortly before it expires, or
 * immediately after the site answers 401.
 *
 * <p>Two ways to do the Kerberos sign-in:
 * <ul>
 *   <li>{@code curl} (default): runs the curl program, exactly like the documented command. Works wherever that
 *       command works for you (Windows 10+ ships curl with SSPI; macOS/Linux use your {@code kinit} ticket).</li>
 *   <li>{@code java}: the JDK's built-in SPNEGO support (no external program). Needs a Kerberos ticket the JVM can
 *       see; see docs/CONFLUENCE_TVM.md.</li>
 * </ul>
 */
public final class TvmTokenProvider {

    /** Fetches the raw JSON body from the TVM. Replaceable in tests. */
    @FunctionalInterface
    public interface Fetcher {
        String fetch() throws SourceException;
    }

    private final URI url;
    private final Fetcher fetcher;
    private final Clock clock;
    private final Duration refreshBefore;
    private String token;
    private Instant expiresAt = Instant.EPOCH;
    private int fetches;

    public TvmTokenProvider(ConfluenceOptions.Tvm tvm, ProxyConfig proxy, Duration timeout) {
        this(tvm, defaultFetcher(tvm, proxy, timeout), Clock.systemUTC());
    }

    public TvmTokenProvider(ConfluenceOptions.Tvm tvm, Fetcher fetcher, Clock clock) {
        this.url = URI.create(tvm.url());
        this.fetcher = fetcher;
        this.clock = clock;
        this.refreshBefore = Duration.ofSeconds(Math.max(0, tvm.refreshBeforeSeconds()));
    }

    /** A valid Bearer token, fetching a new one when none is cached or it is about to expire. */
    public synchronized String token() throws SourceException {
        if (token != null && clock.instant().isBefore(expiresAt.minus(refreshBefore))) {
            return token;
        }
        String body = fetcher.fetch();
        fetches++;
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body == null ? "" : body.strip());
        } catch (Exception e) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "The token service (" + host() + ") did not return JSON. If it returned a sign-in page, Kerberos sign-in failed: run 'klist' to check you have a ticket.");
        }
        String access = root == null ? null : root.path("access_token").asText(null);
        if (access == null || access.isBlank()) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "The token service (" + host() + ") answered without an access_token" + errorHint(root));
        }
        String type = root.path("token_type").asText("Bearer");
        if (!type.isBlank() && !type.equalsIgnoreCase("bearer")) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "The token service returned token_type '" + type + "'; only Bearer is supported");
        }
        long expiresIn = root.path("expires_in").asLong(900);
        token = access.strip();
        expiresAt = clock.instant().plusSeconds(expiresIn <= 0 ? 900 : expiresIn);
        return token;
    }

    /** Forgets the cached token (call after the site answered 401). */
    public synchronized void invalidate() {
        token = null;
        expiresAt = Instant.EPOCH;
    }

    /** How many tokens were fetched (for tests and diagnostics). */
    public synchronized int fetchCount() {
        return fetches;
    }

    public synchronized Instant expiresAt() {
        return expiresAt;
    }

    public String host() {
        return url.getHost();
    }

    private static String errorHint(JsonNode root) {
        if (root == null) {
            return "";
        }
        String err = root.path("error_description").asText(root.path("error").asText(root.path("message").asText("")));
        return err.isBlank() ? "" : ": " + truncate(err, 200);
    }

    // ------------------------------------------------------------------ fetchers

    public static Fetcher defaultFetcher(ConfluenceOptions.Tvm tvm, ProxyConfig proxy, Duration timeout) {
        Duration t = timeout == null ? Duration.ofSeconds(30) : timeout;
        return "java".equals(tvm.client()) ? () -> fetchWithJava(tvm, proxy, t) : () -> fetchWithCurl(tvm, proxy, t);
    }

    /** Builds the curl command line (no secrets on it: {@code -u :} means "use my Kerberos ticket"). */
    public static List<String> curlCommand(ConfluenceOptions.Tvm tvm, ProxyConfig proxy, Duration timeout) {
        List<String> cmd = new ArrayList<>();
        cmd.add(tvm.curlPath());
        cmd.add("--silent");
        cmd.add("--show-error");
        cmd.add("--negotiate");
        cmd.add("-u");
        cmd.add(":");
        cmd.add("--max-time");
        cmd.add(Long.toString(Math.max(1, timeout.toSeconds())));
        cmd.add("--header");
        cmd.add("Accept: application/json");
        if (tvm.useProxy() && proxy != null && proxy.configured()) {
            cmd.add("--proxy");
            cmd.add("http://" + proxy.host() + ":" + proxy.port());
        } else if (!tvm.useProxy()) {
            cmd.add("--noproxy");
            cmd.add("*");
        }
        if (tvm.caCertFile() != null && !tvm.caCertFile().isBlank()) {
            cmd.add("--cacert");
            cmd.add(tvm.caCertFile());
        }
        cmd.add("--write-out");
        cmd.add("\n%{http_code}");
        cmd.add(tvm.url());
        return cmd;
    }

    private static String fetchWithCurl(ConfluenceOptions.Tvm tvm, ProxyConfig proxy, Duration timeout) throws SourceException {
        List<String> cmd = curlCommand(tvm, proxy, timeout);
        Process process;
        Path errFile = null;
        try {
            errFile = Files.createTempFile("qc-tvm", ".err");
            process = new ProcessBuilder(cmd).redirectError(errFile.toFile()).start();
        } catch (IOException e) {
            deleteQuietly(errFile);
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "Could not run '" + tvm.curlPath() + "' to get a Confluence token (" + e.getMessage()
                            + "). Install curl or set source.confluence.tvm.curlPath, or use source.confluence.tvm.client=java.");
        }
        try {
            byte[] out = readAll(process.getInputStream());
            if (!process.waitFor(timeout.toSeconds() + 15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SourceException(SourceException.Kind.TRANSIENT, "The token service (" + URI.create(tvm.url()).getHost() + ") did not answer in time");
            }
            String stdout = new String(out, StandardCharsets.UTF_8);
            String stderr = truncate(Files.readString(errFile, StandardCharsets.UTF_8).strip(), 300);
            if (process.exitValue() != 0) {
                throw new SourceException(SourceException.Kind.AUTH_FAILED,
                        "curl could not reach the token service (exit " + process.exitValue() + ")" + (stderr.isEmpty() ? "" : ": " + stderr));
            }
            int nl = stdout.lastIndexOf('\n');
            String status = nl < 0 ? "" : stdout.substring(nl + 1).strip();
            String body = nl < 0 ? stdout : stdout.substring(0, nl);
            if (!status.startsWith("2")) {
                throw new SourceException(status.equals("401") || status.equals("403") ? SourceException.Kind.AUTH_FAILED : SourceException.Kind.TRANSIENT,
                        "The token service answered HTTP " + status + (status.equals("401")
                                ? ": Kerberos sign-in was not accepted. Run 'klist' (or sign in to Windows on the company network) and try again."
                                : ""));
            }
            return body;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.FATAL, "Interrupted while getting a Confluence token", null, e);
        } catch (IOException e) {
            throw new SourceException(SourceException.Kind.TRANSIENT, "Error reading the token service answer: " + e.getMessage(), null, e);
        } finally {
            deleteQuietly(errFile);
        }
    }

    private static String fetchWithJava(ConfluenceOptions.Tvm tvm, ProxyConfig proxy, Duration timeout) throws SourceException {
        // The JDK's HttpURLConnection answers "WWW-Authenticate: Negotiate" with a Kerberos ticket on its own.
        if (System.getProperty("javax.security.auth.useSubjectCredsOnly") == null) {
            System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");
        }
        if (System.getProperty("sun.security.jgss.native") == null
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            System.setProperty("sun.security.jgss.native", "true"); // use the Windows sign-in (SSPI)
        }
        HttpURLConnection conn = null;
        try {
            Proxy p = tvm.useProxy() && proxy != null && proxy.configured()
                    ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.host(), proxy.port()))
                    : Proxy.NO_PROXY;
            conn = (HttpURLConnection) URI.create(tvm.url()).toURL().openConnection(p);
            conn.setConnectTimeout((int) timeout.toMillis());
            conn.setReadTimeout((int) timeout.toMillis());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "QuestionCrawler/0.1");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new SourceException(status == 401 || status == 403 ? SourceException.Kind.AUTH_FAILED : SourceException.Kind.TRANSIENT,
                        "The token service answered HTTP " + status + (status == 401
                                ? ": the JVM could not sign in with Kerberos. Check 'klist', krb5.conf, or use source.confluence.tvm.client=curl."
                                : ""));
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } catch (SourceException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new SourceException(SourceException.Kind.TRANSIENT,
                    "Could not reach the token service " + URI.create(tvm.url()).getHost() + ": " + e.getClass().getSimpleName() + " " + e.getMessage(), null, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > 1_000_000) {
                throw new IOException("token service answer is too large");
            }
        }
        return buf.toByteArray();
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // temp file; harmless
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
