package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Transport-level behaviour against a throw-away local HTTP server (no internal site involved). */
class HttpSourceClientTest {

    private static final String SECRET = "s3cr3t-token-value";

    HttpServer server;
    final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    final AtomicInteger hits = new AtomicInteger();
    String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> reply(ex, 200, "hello", null));
        server.createContext("/unauthorized", ex -> reply(ex, 401, "no", null));
        server.createContext("/forbidden", ex -> reply(ex, 403, "no", null));
        server.createContext("/missing", ex -> reply(ex, 404, "no", null));
        server.createContext("/gone", ex -> reply(ex, 410, "no", null));
        server.createContext("/limited", ex -> reply(ex, 429, "slow", "Retry-After: 12"));
        server.createContext("/unavailable", ex -> reply(ex, 503, "later", null));
        server.createContext("/boom", ex -> reply(ex, 500, "x", null));
        server.createContext("/redirect", ex -> reply(ex, 302, "", "Location: https://sso.example.internal/login?token=abc"));
        server.createContext("/other/area", ex -> reply(ex, 200, "elsewhere", null));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body, String header) throws java.io.IOException {
        hits.incrementAndGet();
        lastAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
        if (header != null) {
            String[] kv = header.split(": ", 2);
            ex.getResponseHeaders().add(kv[0], kv[1]);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private HttpSourceClient client(String authMethod, boolean insecure, List<String> prefixes, Map<String, String> env) {
        var settings = new SourceSettings("internal", base, List.of("127.0.0.1"), prefixes, authMethod, "TEST_TOKEN",
                "Authorization", "Bearer", null, Duration.ofSeconds(5), insecure);
        return new HttpSourceClient(settings, env::get);
    }

    private SourceException failure(HttpSourceClient c, String path) {
        return assertThrows(SourceException.class, () -> c.get(base + path));
    }

    @Test
    void successfulRequestSendsTheTokenFromTheEnvironmentVariable() throws Exception {
        var c = client("token", true, List.of("/"), Map.of("TEST_TOKEN", SECRET));
        var response = c.get(base + "/ok");
        assertEquals(200, response.status());
        assertEquals("hello", response.body());
        assertEquals("Bearer " + SECRET, lastAuthHeader.get());
    }

    @Test
    void noAuthHeaderIsSentWhenAuthenticationIsDisabled() throws Exception {
        client("none", true, List.of("/"), Map.of("TEST_TOKEN", SECRET)).get(base + "/ok");
        assertNull(lastAuthHeader.get());
    }

    @Test
    void missingTokenVariableIsAnAuthenticationFailureWithoutContactingTheServer() {
        var c = client("token", true, List.of("/"), Map.of());
        SourceException e = failure(c, "/ok");
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("TEST_TOKEN"));
        assertEquals(0, hits.get());
    }

    @Test
    void statusCodesAreMappedToActionableKinds() {
        var c = client("none", true, List.of("/"), Map.of());
        assertEquals(SourceException.Kind.AUTH_FAILED, failure(c, "/unauthorized").kind());
        assertEquals(SourceException.Kind.FORBIDDEN, failure(c, "/forbidden").kind());
        assertEquals(SourceException.Kind.NOT_FOUND, failure(c, "/missing").kind());
        assertEquals(SourceException.Kind.GONE, failure(c, "/gone").kind());
        assertEquals(SourceException.Kind.TRANSIENT, failure(c, "/boom").kind());
        assertTrue(SourceException.Kind.TRANSIENT.isRetryable());
        assertFalse(SourceException.Kind.AUTH_FAILED.isRetryable());
    }

    @Test
    void rateLimitingCarriesRetryAfter() {
        var c = client("none", true, List.of("/"), Map.of());
        SourceException limited = failure(c, "/limited");
        assertEquals(SourceException.Kind.RATE_LIMITED, limited.kind());
        assertEquals(Duration.ofSeconds(12), limited.retryAfter());
        SourceException unavailable = failure(c, "/unavailable");
        assertEquals(SourceException.Kind.RATE_LIMITED, unavailable.kind());
        assertNull(unavailable.retryAfter());
    }

    @Test
    void aRedirectIsNotFollowedWhenTheSettingIsOffAndQueryStringsStayOutOfMessages() {
        var settings = new SourceSettings("internal", base, List.of("127.0.0.1"), List.of("/"), "none", "TEST_TOKEN", "Authorization", "Bearer", null,
                Duration.ofSeconds(5), true, null, null, com.questioncrawler.core.source.ProxyConfig.NONE,
                new com.questioncrawler.core.source.ApiOptions(null, null, null, null, false, null, null, "off"));
        var c = new HttpSourceClient(settings, k -> null);
        SourceException e = failure(c, "/redirect");
        assertEquals(SourceException.Kind.FATAL, e.kind());
        assertFalse(e.getMessage().contains("token=abc"));
        assertEquals(1, hits.get(), "the redirect target is not contacted");
    }

    @Test
    void onlyConfiguredHostsAndPathsAreContacted() {
        var strictPath = client("none", true, List.of("/ok"), Map.of());
        assertEquals(SourceException.Kind.FATAL, failure(strictPath, "/other/area").kind());
        assertEquals(0, hits.get());

        SourceException otherHost = assertThrows(SourceException.class, () -> strictPath.get("http://evil.example/ok"));
        assertTrue(otherHost.getMessage().contains("allowedHosts"));
        SourceException traversal = assertThrows(SourceException.class, () -> strictPath.get(base + "/ok/../other/area"));
        assertEquals(SourceException.Kind.FATAL, traversal.kind());
        assertEquals(0, hits.get());
    }

    @Test
    void plainHttpIsRefusedUnlessExplicitlyAllowedForLocalTesting() {
        var c = client("none", false, List.of("/"), Map.of());
        SourceException e = failure(c, "/ok");
        assertTrue(e.getMessage().contains("https"));
        assertEquals(0, hits.get());
    }

    @Test
    void tokenValueNeverAppearsInErrorMessages() {
        var c = client("token", true, List.of("/"), Map.of("TEST_TOKEN", SECRET));
        for (String path : List.of("/unauthorized", "/forbidden", "/boom", "/redirect", "/limited")) {
            assertFalse(failure(c, path).getMessage().contains(SECRET), path);
        }
    }
}
