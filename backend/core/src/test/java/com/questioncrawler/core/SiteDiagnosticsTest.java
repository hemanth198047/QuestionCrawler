package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.ConnectionReport;
import com.questioncrawler.core.service.ConnectionReport.Stage;
import com.questioncrawler.core.service.SiteDiagnostics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Diagnostics against a local server that plays different kinds of sites. Nothing leaves this machine. */
class SiteDiagnosticsTest {

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply html(String body) { return new Reply(200, body, Map.of("Content-Type", "text/html")); }
        static Reply json(String body) { return new Reply(200, body, Map.of("Content-Type", "application/json")); }
        static Reply status(int s) { return new Reply(s, "", Map.of()); }
    }

    HttpServer server;
    volatile Function<String, Reply> site = path -> Reply.status(404);
    String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Reply r = site.apply(ex.getRequestURI().getPath());
            send(ex, r);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, Reply r) throws java.io.IOException {
        byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private ConnectionReport check(String type, String target, Function<String, String> env) {
        var in = new SiteDiagnostics.Input(type, target, "stackoverflow", List.of("artificial-intelligence"), "none", "", "", null, true, 3);
        return new SiteDiagnostics(env, Clock.systemUTC()).run(in);
    }

    private ConnectionReport check(String target) {
        return check("live", target, k -> null);
    }

    private static Stage stage(ConnectionReport r, String id) {
        return r.stages().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void demoSourceIsReadyWithoutAnyNetwork() {
        ConnectionReport r = check("fixture", "", k -> null);
        assertEquals("READY", r.verdict());
        assertEquals(100, r.score());
        assertEquals("OFFLINE_DEMO", r.route().kind());
        assertTrue(r.route().supported());
    }

    @Test
    void unknownHostIsExplainedWithWhatToDo() {
        ConnectionReport r = check("https://this-host-does-not-exist.invalid/api");
        assertEquals("BLOCKED", r.verdict());
        Stage network = stage(r, "network");
        assertEquals("FAIL", network.status());
        assertTrue(network.summary().contains("could not be found"));
        assertTrue(network.needs().stream().anyMatch(n -> n.what().contains("VPN")));
        assertTrue(network.needs().stream().allMatch(n -> !n.who().isBlank() && !n.why().isBlank()));
        assertEquals("SKIPPED", stage(r, "secure").status());
        assertEquals("NONE", r.route().kind());
        assertTrue(r.requirements().stream().anyMatch(q -> q.id().equals("network") && q.status().equals("MISSING")));
    }

    @Test
    void closedPortIsReportedAsBlockedByFirewallOrVpn() throws Exception {
        int port;
        try (var s = new java.net.ServerSocket(0)) { port = s.getLocalPort(); }
        ConnectionReport r = check("http://127.0.0.1:" + port + "/");
        assertEquals("BLOCKED", r.verdict());
        assertTrue(stage(r, "network").needs().stream().anyMatch(n -> n.who().contains("network")));
    }

    @Test
    void loginRedirectMeansAnApprovedTokenIsNeeded() {
        site = p -> new Reply(302, "", Map.of("Location", "https://login.company.example/sso/start?next=%2F"));
        ConnectionReport r = check(base + "/");
        Stage access = stage(r, "access");
        assertEquals("WARN", access.status());
        assertTrue(access.summary().contains("sign-in"), access.summary());
        assertTrue(access.facts().stream().anyMatch(f -> f.value().contains("login.company.example/sso/start")));
        assertFalse(access.facts().stream().anyMatch(f -> f.value().contains("next=")), "query strings are never shown");
        assertEquals("SIGN_IN_REQUIRED", r.route().kind());
        assertFalse(r.route().supported());
        assertEquals("NOT_SUPPORTED_YET", r.verdict());
    }

    @Test
    void unauthorizedShowsWhichSignInMethodTheSiteAsksFor() {
        site = p -> new Reply(401, "no", Map.of("WWW-Authenticate", "Bearer realm=\"qa\""));
        ConnectionReport r = check(base + "/");
        Stage access = stage(r, "access");
        assertEquals("WARN", access.status());
        assertTrue(access.facts().stream().anyMatch(f -> f.label().contains("Sign-in method") && f.value().contains("Bearer")));
        assertTrue(access.needs().get(0).what().toLowerCase().contains("token"));
    }

    @Test
    void publishedApiDescriptionIsFoundAndLikelyEndpointsAreListedNotInvented() {
        site = p -> switch (p) {
            case "/" -> Reply.html("<html><body>" + "Welcome to the Q&A site with plenty of readable text. ".repeat(20) + "</body></html>");
            case "/openapi.json" -> Reply.json("{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Acme Q&A API\"},\"paths\":{\"/v1/questions\":{},\"/v1/questions/{id}/answers\":{},\"/v1/users\":{},\"/health\":{}}}");
            default -> Reply.status(404);
        };
        ConnectionReport r = check(base + "/");
        Stage data = stage(r, "data");
        assertEquals("PASS", data.status());
        assertTrue(data.facts().stream().anyMatch(f -> f.label().equals("API name") && f.value().equals("Acme Q&A API")));
        String endpoints = data.facts().stream().filter(f -> f.label().contains("relevant")).findFirst().orElseThrow().value();
        assertTrue(endpoints.contains("/v1/questions") && endpoints.contains("/v1/questions/{id}/answers"));
        assertFalse(endpoints.contains("/v1/users") || endpoints.contains("/health"));
        assertEquals("API_FOUND", r.route().kind());
        assertFalse(r.route().supported(), "found, but the connector is not built until endpoints are confirmed");
    }

    @Test
    void robotsThatForbidCrawlingAreRespected() {
        site = p -> switch (p) {
            case "/" -> Reply.html("<html><body>" + "Readable content. ".repeat(60) + "</body></html>");
            case "/robots.txt" -> new Reply(200, "User-agent: *\nDisallow: /\n", Map.of("Content-Type", "text/plain"));
            default -> Reply.status(404);
        };
        ConnectionReport r = check(base + "/");
        Stage data = stage(r, "data");
        assertEquals("WARN", data.status());
        assertTrue(data.summary().contains("not to crawl"));
        assertEquals("DISCOURAGED", r.route().kind());
    }

    @Test
    void scriptBuiltPagesExplainWhyScrapingHtmlWillNotWork() {
        site = p -> p.equals("/") ? Reply.html("<html><body><div id=\"root\"></div><script src=\"/app.js\"></script></body></html>") : Reply.status(404);
        ConnectionReport r = check(base + "/");
        assertEquals("WEB_PAGES_SCRIPTED", r.route().kind());
        assertTrue(stage(r, "data").summary().contains("JavaScript"));
    }

    @Test
    void readablePagesWithoutAnApiAreFlaggedAsFragileAndNeedPermission() {
        site = p -> p.equals("/") ? Reply.html("<html><body>" + "Questions and answers about many topics. ".repeat(30) + "</body></html>") : Reply.status(404);
        ConnectionReport r = check(base + "/");
        assertEquals("WEB_PAGES", r.route().kind());
        assertFalse(r.route().supported());
        assertTrue(stage(r, "data").needs().stream().anyMatch(n -> n.what().contains("API documentation")));
        assertTrue(r.requirements().stream().anyMatch(q -> q.id().equals("terms") && q.status().equals("UNKNOWN")));
    }

    @Test
    void botProtectionIsReportedNeverEvaded() {
        site = p -> new Reply(403, "<html>Just a moment... captcha</html>", Map.of("Server", "cloudflare", "cf-ray", "abc", "Content-Type", "text/html"));
        ConnectionReport r = check(base + "/");
        Stage limits = stage(r, "limits");
        assertEquals("FAIL", limits.status());
        assertTrue(limits.needs().get(0).what().contains("approved"));
        assertEquals("DISCOURAGED", r.route().kind());
    }

    @Test
    void stackExchangeApiIsReadyAndShowsQuotaAndTagSize() {
        site = p -> {
            if (p.contains("/tags/")) return json("{\"items\":[{\"name\":\"artificial-intelligence\",\"count\":15432}],\"quota_remaining\":289,\"has_more\":false}");
            if (p.endsWith("/info")) return json("{\"items\":[{\"total_questions\":123}],\"quota_max\":300,\"quota_remaining\":290,\"has_more\":false}");
            return Reply.status(404);
        };
        ConnectionReport r = check("stackexchange", base + "/2.3", k -> null);
        assertEquals("OFFICIAL_API", r.route().kind());
        assertTrue(r.route().supported());
        assertEquals("PASS", stage(r, "data").status());
        assertTrue(stage(r, "data").facts().stream().anyMatch(f -> f.label().contains("left today")));
        Stage scope = stage(r, "scope");
        assertEquals("PASS", scope.status());
        assertTrue(scope.summary().contains("15,432"), scope.summary());
        assertEquals("INFO", stage(r, "limits").status(), "anonymous use is explained, not alarming");
        assertTrue(r.requirements().stream().anyMatch(q -> q.id().equals("terms") && q.detail().contains("CC BY-SA")));
        assertTrue(r.score() >= 85, "score " + r.score());
        assertTrue(List.of("READY", "NEEDS_ATTENTION").contains(r.verdict()), r.verdict());
    }

    @Test
    void missingTagOnStackExchangeIsCalledOut() {
        site = p -> {
            if (p.contains("/tags/")) return json("{\"items\":[],\"has_more\":false}");
            if (p.endsWith("/info")) return json("{\"items\":[{}],\"quota_remaining\":290,\"has_more\":false}");
            return Reply.status(404);
        };
        ConnectionReport r = check("stackexchange", base + "/2.3", k -> null);
        assertEquals("WARN", stage(r, "scope").status());
        assertTrue(stage(r, "scope").needs().get(0).what().contains("exact tag"));
    }

    @Test
    void tlsFailuresAreTranslatedIntoPlainLanguage() {
        assertTrue(SiteDiagnostics.explainTlsFailure(new javax.net.ssl.SSLHandshakeException("PKIX path building failed: unable to find valid certification path to requested target"))
                .contains("does not trust"));
        assertTrue(SiteDiagnostics.explainTlsFailure(new javax.net.ssl.SSLHandshakeException("No subject alternative DNS name matching x found."))
                .contains("different site name"));
        assertTrue(SiteDiagnostics.explainTlsFailure(new javax.net.ssl.SSLException("weird")).contains("failed"));
    }

    @Test
    void unencryptedAddressesAreFlaggedAndTokenValuesAreNeverShown() {
        site = p -> Reply.html("<html><body>" + "text ".repeat(100) + "</body></html>");
        var in = new SiteDiagnostics.Input("live", base + "/", "", List.of("x"), "token", "MY_TOKEN", "", null, true, 3);
        ConnectionReport r = new SiteDiagnostics(k -> k.equals("MY_TOKEN") ? "super-secret-value" : null, Clock.systemUTC()).run(in);
        assertEquals("WARN", stage(r, "secure").status());
        String all = com.questioncrawler.core.util.Json.write(r);
        assertFalse(all.contains("super-secret-value"));
        assertTrue(stage(r, "access").facts().stream().anyMatch(f -> f.value().contains("set (value not shown)")));
    }

    private static Reply json(String body) {
        return Reply.json(body);
    }
}
