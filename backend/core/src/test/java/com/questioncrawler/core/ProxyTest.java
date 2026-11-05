package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.ConnectionReport;
import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.SiteDiagnostics;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Web proxy support, against a fake proxy that understands CONNECT tunnels and absolute-URI requests.
 * "example.test" does not resolve locally: reaching it proves that name lookup is done by the proxy.
 */
class ProxyTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ fake web proxy
    static final class FakeProxy implements AutoCloseable {
        final ServerSocket server;
        final int forwardPort;
        volatile int connectStatus = 200;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());

        FakeProxy(int forwardPort) throws IOException {
            this.forwardPort = forwardPort;
            this.server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket c = server.accept();
                        new Thread(() -> serve(c)).start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve(Socket client) {
            try (client) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                StringBuilder head = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    head.append((char) b);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                String text = head.toString();
                String first = text.lines().findFirst().orElse("");
                requests.add(first);
                text.lines().filter(l -> l.toLowerCase().startsWith("proxy-authorization")).forEach(authHeaders::add);
                if (first.startsWith("CONNECT")) {
                    if (connectStatus != 200) {
                        out.write(("HTTP/1.1 " + connectStatus + " Nope\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        return;
                    }
                    try (Socket upstream = new Socket(java.net.InetAddress.getLoopbackAddress(), forwardPort)) {
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        pipe(client, upstream);
                    }
                } else { // plain request with an absolute URI: forward to the local server, answering as origin
                    String path = first.split(" ")[1].replaceFirst("^https?://[^/]+", "");
                    try (Socket upstream = new Socket(java.net.InetAddress.getLoopbackAddress(), forwardPort)) {
                        upstream.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        upstream.getOutputStream().flush();
                        upstream.getInputStream().transferTo(out);
                    }
                }
            } catch (IOException ignored) {
                // test proxy: connection ended
            }
        }

        private static void pipe(Socket a, Socket b) throws IOException {
            Thread t = new Thread(() -> { try { a.getInputStream().transferTo(b.getOutputStream()); } catch (IOException ignored) { } });
            t.setDaemon(true);
            t.start();
            try { b.getInputStream().transferTo(a.getOutputStream()); } catch (IOException ignored) { }
        }

        @Override public void close() throws IOException { server.close(); }
    }

    HttpServer origin;
    FakeProxy proxy;
    final AtomicInteger originHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", ex -> {
            originHits.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        origin.start();
        proxy = new FakeProxy(origin.getAddress().getPort());
    }

    @AfterEach
    void stop() throws IOException {
        origin.stop(0);
        proxy.close();
    }

    private SourceSettings settings(ProxyConfig px) {
        return new SourceSettings("x", "http://example.test:80/api", List.of("example.test"), List.of("/"), "none", null, null, null, null,
                Duration.ofSeconds(5), true, null, null, px);
    }

    // ------------------------------------------------------------------ parsing and rules
    @Test
    void whatPeopleTypeIsUnderstood() {
        for (String text : List.of("webproxy.corp.com", "webproxy.corp.com:8080", "http://webproxy.corp.com:8080/", "https://user:secret@webproxy.corp.com:8080")) {
            ProxyConfig p = ProxyConfig.parse(text, "8080", "", "", "");
            assertEquals("webproxy.corp.com", p.host(), text);
            assertEquals(8080, p.port(), text);
        }
        assertEquals(3128, ProxyConfig.parse("proxy.corp.com:3128", "", "", "", "").port());
        assertEquals(9000, ProxyConfig.parse("proxy.corp.com:3128", "9000", "", "", "").port(), "the port field wins");
        assertFalse(ProxyConfig.parse("", "", "", "", "").configured());
        assertFalse(ProxyConfig.parse("proxy.corp.com", "", "", "", "").configured(), "a host without a port is not usable yet");
    }

    @Test
    void environmentVariablesAreUsedWhenNothingIsConfigured() {
        ProxyConfig env = ProxyConfig.NONE.orEnvironment(k -> Map.of("HTTPS_PROXY", "http://webproxy-inet-app.xxx.com:8080", "NO_PROXY", ".corp.com,intranet").get(k));
        assertTrue(env.configured());
        assertEquals("webproxy-inet-app.xxx.com", env.host());
        assertEquals(8080, env.port());
        assertTrue(env.fromEnvironment());
        assertTrue(env.describe().contains("HTTPS_PROXY"));
        assertTrue(env.bypass("wiki.corp.com") && env.bypass("intranet") && !env.bypass("api.stackexchange.com"));
        // settings win over the environment
        ProxyConfig set = ProxyConfig.parse("mine.corp.com", "3128", "", "", "").orEnvironment(k -> "http://other:1");
        assertEquals("mine.corp.com", set.host());
    }

    @Test
    void loopbackAndListedHostsSkipTheProxy() {
        ProxyConfig p = ProxyConfig.parse("proxy.corp.com", "8080", "intranet.corp.com, *.internal, 10.*", "", "");
        assertTrue(p.bypass("localhost") && p.bypass("127.0.0.1") && p.bypass("::1"));
        assertTrue(p.bypass("intranet.corp.com") && p.bypass("wiki.internal") && p.bypass("10.1.2.3"));
        assertFalse(p.bypass("api.stackexchange.com"));
        assertTrue(p.appliesTo("api.stackexchange.com"));
        assertFalse(p.appliesTo("127.0.0.1"));
    }

    // ------------------------------------------------------------------ the real downloader
    @Test
    void requestsGoThroughTheProxyAndTheProxyDoesTheNameLookup() throws Exception {
        var client = new HttpSourceClient(settings(ProxyConfig.parse("127.0.0.1", String.valueOf(proxy.port()), "", "", "")), k -> null);
        assertEquals("{\"ok\":true}", client.get("http://example.test:80/api/ping").body());
        assertEquals(1, proxy.requests.size());
        assertTrue(proxy.requests.get(0).startsWith("GET http://example.test"), proxy.requests.get(0));
        assertEquals(1, originHits.get());
    }

    @Test
    void withoutAProxyThatNameCannotBeReached() {
        var client = new HttpSourceClient(settings(ProxyConfig.NONE), k -> null);
        SourceException e = assertThrows(SourceException.class, () -> client.get("http://example.test:80/api/ping"));
        assertEquals(SourceException.Kind.TRANSIENT, e.kind());
        assertEquals(0, proxy.requests.size());
    }

    @Test
    void theProxyFromTheEnvironmentIsUsedAutomatically() throws Exception {
        var client = new HttpSourceClient(settings(ProxyConfig.NONE), k -> k.equals("HTTPS_PROXY") ? "http://127.0.0.1:" + proxy.port() : null);
        assertEquals(200, client.get("http://example.test:80/api/ping").status());
        assertEquals(1, proxy.requests.size());
    }

    // ------------------------------------------------------------------ the connection check
    private ConnectionReport check(ProxyConfig px, String target) {
        var in = new SiteDiagnostics.Input("live", target, "", List.of("x"), "none", "", "", null, true, 3, px);
        return new SiteDiagnostics(k -> null, Clock.systemUTC()).run(in);
    }

    private static ConnectionReport.Stage stage(ConnectionReport r, String id) {
        return r.stages().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void siteReachableOnlyThroughTheProxyIsReportedAsReachable() {
        ProxyConfig px = ProxyConfig.parse("127.0.0.1", String.valueOf(proxy.port()), "", "", "");
        ConnectionReport r = check(px, "http://example.test:" + origin.getAddress().getPort() + "/api");
        var network = stage(r, "network");
        assertEquals("PASS", network.status(), network.summary());
        assertTrue(network.summary().contains("through your web proxy"));
        assertTrue(network.facts().stream().anyMatch(f -> f.label().equals("Proxy") && f.value().contains("127.0.0.1:" + proxy.port())));
        assertEquals("PASS", stage(r, "access").status(), "the HTTP probe also went through the proxy: " + stage(r, "access").summary());
    }

    @Test
    void httpsSitesUseAConnectTunnelThroughTheProxy() {
        ProxyConfig px = ProxyConfig.parse("127.0.0.1", String.valueOf(proxy.port()), "", "", "");
        ConnectionReport r = check(px, "https://example.test:" + origin.getAddress().getPort() + "/api");
        assertEquals("PASS", stage(r, "network").status(), stage(r, "network").summary());
        assertTrue(proxy.requests.stream().anyMatch(l -> l.startsWith("CONNECT example.test:" + origin.getAddress().getPort())), proxy.requests.toString());
        assertTrue(stage(r, "network").facts().stream().anyMatch(f -> f.label().equals("Tunnel through the proxy")));
        // The fake origin does not speak TLS, so the secure stage fails: but it was attempted THROUGH the tunnel.
        assertEquals("FAIL", stage(r, "secure").status());
    }

    @Test
    void aProxyThatBlocksTheSiteIsExplainedAndWhoToAskIsNamed() {
        proxy.connectStatus = 403;
        ConnectionReport r = check(ProxyConfig.parse("127.0.0.1", String.valueOf(proxy.port()), "", "", ""), "https://blocked.example.test/api");
        var n = stage(r, "network");
        assertEquals("FAIL", n.status());
        assertTrue(n.summary().contains("blocks this site by policy"), n.summary());
        assertTrue(n.needs().get(0).what().contains("allowed through the web proxy"));
        assertTrue(n.needs().get(0).who().contains("security"));
        assertEquals("BLOCKED", r.verdict());
    }

    @Test
    void aProxyThatWantsSignInIsExplained() {
        proxy.connectStatus = 407;
        ConnectionReport r = check(ProxyConfig.parse("127.0.0.1", String.valueOf(proxy.port()), "", "", ""), "https://x.example.test/api");
        var n = stage(r, "network");
        assertTrue(n.summary().contains("sign in"), n.summary());
        assertTrue(n.needs().stream().anyMatch(x -> x.what().contains("CNTLM")));
    }

    @Test
    void proxyCredentialsAreSentFromEnvironmentVariablesAndNeverShown() throws Exception {
        ProxyConfig px = ProxyConfig.parse("127.0.0.1", String.valueOf(proxy.port()), "", "PX_USER", "PX_PASS");
        java.util.function.Function<String, String> env = k -> Map.of("PX_USER", "alice", "PX_PASS", "s3cret-pw").get(k);
        try (Socket s = px.tunnel("example.test", 443, 3000, env)) {
            assertNotNull(s);
        }
        assertEquals(1, proxy.authHeaders.size());
        assertTrue(proxy.authHeaders.get(0).contains("Basic " + java.util.Base64.getEncoder().encodeToString("alice:s3cret-pw".getBytes(StandardCharsets.UTF_8))));
        var in = new SiteDiagnostics.Input("live", "https://example.test/api", "", List.of("x"), "none", "", "", null, true, 3, px);
        String json = com.questioncrawler.core.util.Json.write(new SiteDiagnostics(env, Clock.systemUTC()).run(in));
        assertFalse(json.contains("s3cret-pw") || json.contains("alice"));
    }

    @Test
    void anUnreachableProxyIsDistinguishedFromAnUnreachableSite() throws Exception {
        int dead;
        try (ServerSocket s = new ServerSocket(0)) { dead = s.getLocalPort(); }
        ConnectionReport r = check(ProxyConfig.parse("127.0.0.1", String.valueOf(dead), "", "", ""), "https://api.example.test/2.3");
        var n = stage(r, "network");
        assertEquals("FAIL", n.status());
        assertTrue(n.summary().contains("cannot reach your web proxy"), n.summary());
    }

    @Test
    void withoutAProxyARefusedConnectionSuggestsEnteringTheCompanyProxy() throws Exception {
        int dead;
        try (ServerSocket s = new ServerSocket(0)) { dead = s.getLocalPort(); }
        ConnectionReport r = check(ProxyConfig.NONE, "http://127.0.0.1:" + dead + "/");
        var n = stage(r, "network");
        assertEquals("FAIL", n.status());
        assertTrue(n.needs().get(0).what().contains("web proxy"), n.needs().toString());
        assertTrue(n.needs().get(0).why().contains("Web proxy address"));
    }

    // ------------------------------------------------------------------ settings
    @Test
    void proxySettingsAreExplainedValidatedSavedAndLoaded() throws Exception {
        var service = new ConfigService(k -> null);
        assertTrue(ConfigService.sections().stream().flatMap(s -> s.fields().stream()).anyMatch(f -> f.key().equals("network.proxyHost") && !f.advanced()),
                "the proxy address and port are not hidden behind 'advanced'");
        Path file = tmp.resolve("c.properties");
        var bad = service.save(file, Map.of("source.type", "stackexchange", "sync.tags", "ai", "network.proxyHost", "webproxy-inet-app.xxx.com"));
        assertFalse(bad.saved());
        assertTrue(bad.issues().stream().anyMatch(i -> i.key().equals("network.proxyPort") && i.level().equals("error")));
        var ok = service.save(file, Map.of("source.type", "stackexchange", "sync.tags", "ai", "network.proxyHost", "http://webproxy-inet-app.xxx.com:8080/",
                "network.proxyPort", "8080", "storage.root", tmp.resolve("data").toString().replace('\\', '/')));
        assertTrue(ok.saved(), ok.issues().toString());
        CoreConfig c = CoreConfig.load(file, Map.of());
        assertEquals("webproxy-inet-app.xxx.com", c.source().proxy().host());
        assertEquals(8080, c.source().proxy().port());
        assertTrue(Files.readString(file).contains("network.proxyHost="));
    }

    @Test
    void draftProxySettingsFromTheConnectScreenAreUsedByTheCheck() throws Exception {
        Workspace w = new Workspace(tmp.resolve("cfg/config.properties"), Map.of("storage.root", tmp.resolve("d").toString()), k -> null, Clock.systemUTC());
        ConnectionReport r = w.checkConnection(Map.of("source.type", "live", "source.baseUrl", "http://example.test:" + origin.getAddress().getPort() + "/api",
                "source.allowInsecureHttp", "true", "network.proxyHost", "127.0.0.1", "network.proxyPort", String.valueOf(proxy.port()), "source.timeoutSeconds", "3"));
        assertEquals("PASS", stage(r, "network").status(), stage(r, "network").summary());
        assertTrue(proxy.requests.size() >= 1);
    }
}
