package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.BrowserSignIn;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.util.Sleeper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The internal Q&A site behind the company login: its API answers 302 to the login page until the browser holds a
 * session, exactly like Confluence. The browser is simulated; the API is a local mock.
 */
class InternalSiteBrowserLoginTest {

    @TempDir
    Path tmp;

    HttpServer server;
    String origin;
    final AtomicReference<String> validSession = new AtomicReference<>("JSESSIONID=one");
    final List<String> cookiesSeen = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            cookiesSeen.add(cookie == null ? "" : cookie);
            if (cookie == null || !cookie.contains(validSession.get())) {
                ex.getResponseHeaders().add("Location", "https://sso.corp.example.com/adfs/ls/?wa=wsignin1.0");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] body = "{\"items\":[{\"total_questions\":42}],\"has_more\":false}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** A fake browser window: the person "signs in" after a few polls, then the site's session cookie is there. */
    final class FakeBrowser implements BrowserLogin.Launcher {
        final AtomicInteger opened = new AtomicInteger();

        @Override
        public BrowserLogin.Session open(String startUrl) {
            opened.incrementAndGet();
            AtomicInteger polls = new AtomicInteger();
            return new BrowserLogin.Session() {
                @Override
                public List<BrowserLogin.Cookie> cookies() {
                    if (polls.incrementAndGet() < 2) {
                        return List.of();
                    }
                    String[] kv = validSession.get().split("=", 2);
                    return List.of(new BrowserLogin.Cookie(kv[0], kv[1], "127.0.0.1", "/"));
                }

                @Override
                public boolean alive() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private StackExchangeAdapter adapter(FakeBrowser browser) {
        SourceSettings s = new SourceSettings("internal-qa", origin + "/api/2.3", List.of("127.0.0.1"), List.of("/"), "browser", null,
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                new ApiOptions("key", "query", null, "withbody", false, null, null, "same-host"), null, null);
        BrowserLogin login = new BrowserLogin(origin, origin + "/", browser, Duration.ofSeconds(10), Duration.ofMillis(5), Clock.systemUTC());
        HttpSourceClient probeClient = new HttpSourceClient(s, k -> null);
        BrowserLogin.Prober probe = cookie -> {
            try {
                return BrowserLogin.letIn(probeClient.exchange(origin + "/api/2.3/info", Map.of("Cookie", cookie)));
            } catch (com.questioncrawler.core.source.SourceException e) {
                return false;
            }
        };
        return new StackExchangeAdapter(s, new HttpSourceClient(s, k -> null, () -> login.cookieHeader(probe)), k -> null, Sleeper.REAL, null)
                .withBrowser(login, probe);
    }

    @Test
    void theApiIsReadAfterSigningInThroughTheBrowser() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        StackExchangeAdapter adapter = adapter(browser);
        String ping = adapter.ping();
        assertTrue(ping.contains("42"), ping);
        assertEquals(1, browser.opened.get());
        assertEquals("JSESSIONID=one", cookiesSeen.get(cookiesSeen.size() - 1));
        assertTrue(adapter instanceof BrowserSignIn);
        assertEquals("JSESSIONID=one", adapter.sessionCookie());
    }

    @Test
    void anExpiredSessionOpensTheBrowserAgainAndTheRequestContinues() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        StackExchangeAdapter adapter = adapter(browser);
        adapter.ping();
        validSession.set("JSESSIONID=two"); // the session ran out: the API redirects to the login page again
        String ping = adapter.ping();
        assertTrue(ping.contains("42"), ping);
        assertEquals(2, browser.opened.get());
        assertTrue(cookiesSeen.get(cookiesSeen.size() - 1).contains("two"));
    }

    @Test
    void theSessionIsReusedByHelperRequestsWithoutAnotherWindow() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        adapter(browser).ping();
        // A helper (for example "read the API documentation" on Connect) has no sign-in of its own: it uses the session.
        assertEquals("JSESSIONID=one", BrowserLogin.sessionFor("127.0.0.1"));
    }

    @Test
    void browserSignInIsAValidChoiceForTheInternalSite() {
        CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("d").toString(), "source.type", "live",
                "source.baseUrl", "https://qa.corp.example.com/api/2.3", "source.auth.method", "browser", "sync.tags", "java",
                "source.browser.timeoutSeconds", "120"));
        assertEquals(List.of(), cfg.validate());
        assertEquals(120, cfg.confluence().browser().timeoutSeconds(), "browser settings are shared by every source");
        assertTrue(cfg.createAdapter(k -> null) instanceof BrowserSignIn, "creating the adapter does not open a browser");
        CoreConfig jive = CoreConfig.load(null, Map.of("source.type", "jive", "source.baseUrl", "https://community.example.com",
                "source.auth.method", "browser", "sync.tags", "all"));
        assertTrue(jive.validate().stream().anyMatch(p -> p.contains("browser")));
    }
}
