package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.Redirects;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.TagMode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The internal Q&A site is read through its REST API only: a redirect to /users/login is never followed. */
class InternalSiteLoginRedirectTest {

    HttpServer server;
    String site;
    final AtomicInteger loginHits = new AtomicInteger();
    volatile boolean apiWorks = true;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            if (path.startsWith("/users/login")) {
                loginHits.incrementAndGet();
                send(ex, 200, "text/html", "<html><title>Log in</title><form><input type=\"password\"></form></html>");
            } else if (path.startsWith("/api/2.3/") && apiWorks && query.contains("key=k3y")) {
                send(ex, 200, "application/json", "{\"items\":[],\"has_more\":false,\"quota_remaining\":9999,\"total\":0}");
            } else {
                ex.getResponseHeaders().add("Location", "/users/login?returnurl=" + path);
                send(ex, 302, "text/html", "");
            }
        });
        server.start();
        site = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private StackExchangeAdapter adapter(String base, Map<String, String> env) {
        var api = new ApiOptions("key", "query", "QA_TOKEN", "withbody", false, null, null, "all");
        var settings = new SourceSettings("internal", base, List.of("127.0.0.1"), List.of(), "none", null, null, null, null,
                Duration.ofSeconds(5), true, null, "QA_KEY", ProxyConfig.NONE, api);
        return new StackExchangeAdapter(settings, new HttpSourceClient(settings), env::get, d -> { }, null);
    }

    @Test
    void theWebsiteAddressIsReplacedByTheApiAddress() throws Exception {
        var adapter = adapter(site, Map.of("QA_KEY", "k3y"));
        adapter.listQuestions(new TagFilter(List.of("java"), TagMode.ANY, false), null, null);
        assertEquals(0, loginHits.get(), "the sign-in page is never requested");
        assertTrue(adapter.notes().stream().anyMatch(n -> n.contains(site + "/api/2.3")), adapter.notes().toString());
    }

    @Test
    void aRedirectToTheLoginPageSaysToCheckTheKeyAndToken() {
        apiWorks = false;
        var adapter = adapter(site + "/api/2.3", Map.of("QA_KEY", "k3y"));
        SourceException e = assertThrows(SourceException.class,
                () -> adapter.listQuestions(new TagFilter(List.of("java"), TagMode.ANY, false), null, null));
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertEquals(0, loginHits.get(), "the sign-in page is never requested");
        assertTrue(e.getMessage().contains("/users/login"), e.getMessage());
        assertTrue(e.getMessage().contains("access token"), e.getMessage());
        assertTrue(e.getMessage().contains("QA_TOKEN"), e.getMessage());
        assertFalse(e.getMessage().contains("browser sign-in:"), e.getMessage());
        assertFalse(e.getMessage().contains("k3y"), "secrets are never shown");
    }

    @Test
    void apiAddressAndSignInAddresses() {
        assertEquals("https://qa.corp.com/api/2.3", StackExchangeAdapter.apiAddressFor("https://qa.corp.com"));
        assertEquals("https://qa.corp.com/api/2.3", StackExchangeAdapter.apiAddressFor("https://qa.corp.com/questions"));
        assertEquals("https://corp.com/qa/api/2.3", StackExchangeAdapter.apiAddressFor("https://corp.com/qa/questions/tagged/java"));
        assertNull(StackExchangeAdapter.apiAddressFor("https://qa.corp.com/api/2.3"));
        assertTrue(Redirects.isSignInAddress("https://qa.corp.com/users/login?returnurl=%2fquestions"));
        assertTrue(Redirects.isSignInAddress("https://login.microsoftonline.com/abc/saml2"));
        assertFalse(Redirects.isSignInAddress("https://qa.corp.com/api/2.3/questions?key=login"));
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }
}
