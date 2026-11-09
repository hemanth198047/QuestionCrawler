package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.ConfluenceOptions;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Sign in with my browser": Confluence answers 302 to a company login page until the browser holds a session.
 * The browser is simulated (a fake window that "signs in" after a few polls); the Confluence API is a local mock.
 */
class ConfluenceBrowserLoginTest {

    @TempDir
    Path tmp;

    HttpServer server;
    String origin;
    /** The session cookie the mock Confluence accepts right now. */
    final AtomicReference<String> validSession = new AtomicReference<>("tenant.session.token=s1");
    final List<String> cookiesSeen = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            cookiesSeen.add(cookie == null ? "" : cookie);
            boolean ok = cookie != null && cookie.contains(validSession.get());
            if (!ok) {
                ex.getResponseHeaders().add("Location", "https://login.corp.example.com/saml/sso?RelayState=x");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] body = "{\"results\":[{\"id\":\"77\",\"key\":\"ENG\",\"name\":\"Engineering\"}]}".getBytes(StandardCharsets.UTF_8);
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

    /** A fake browser: reports no cookies for the first polls (the person is typing), then the current session cookie. */
    final class FakeBrowser implements BrowserLogin.Launcher {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicReference<String> openedAt = new AtomicReference<>();
        volatile boolean closeEarly;

        @Override
        public BrowserLogin.Session open(String startUrl) {
            opened.incrementAndGet();
            openedAt.set(startUrl);
            AtomicInteger polls = new AtomicInteger();
            return new BrowserLogin.Session() {
                @Override
                public List<BrowserLogin.Cookie> cookies() {
                    if (polls.incrementAndGet() < 3) {
                        return List.of(new BrowserLogin.Cookie("idp", "x", "login.corp.example.com", "/"));
                    }
                    String[] kv = validSession.get().split("=", 2);
                    return List.of(new BrowserLogin.Cookie(kv[0], kv[1], "127.0.0.1", "/"),
                            new BrowserLogin.Cookie("other", "y", "elsewhere.example.com", "/"));
                }

                @Override
                public boolean alive() {
                    return !closeEarly;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private ConfluenceAdapter adapter(FakeBrowser browser) {
        SourceSettings s = new SourceSettings("confluence", origin, List.of("127.0.0.1"), List.of("/wiki/api/v2/"), "browser", null,
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                new ApiOptions(null, null, null, null, false, null, null, "same-host"), null, null);
        ConfluenceOptions options = new ConfluenceOptions("cloud", "", List.of(), "", List.of("page"), "body.storage", false, 25, true, "", "",
                "v2", List.of(), ConfluenceOptions.Tvm.NONE);
        BrowserLogin login = new BrowserLogin(origin, origin + "/wiki/", browser, Duration.ofSeconds(10), Duration.ofMillis(5), Clock.systemUTC());
        return ConfluenceAdapter.withBrowserLogin(s, options, k -> null, login);
    }

    @Test
    void firstCallOpensTheBrowserAndUsesOnlyTheSitesCookies() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        ConfluenceAdapter adapter = adapter(browser);
        String ping = adapter.ping();
        assertTrue(ping.contains("v2"));
        assertEquals(1, browser.opened.get());
        assertEquals(origin + "/wiki/", browser.openedAt.get());
        String sent = cookiesSeen.get(cookiesSeen.size() - 1);
        assertEquals("tenant.session.token=s1", sent, "only cookies of the Confluence host are sent");
        adapter.ping();
        assertEquals(1, browser.opened.get(), "the session is reused");
    }

    @Test
    void expiredSessionRedirectingToLoginOpensTheBrowserAgainAndContinues() throws Exception {
        FakeBrowser browser = new FakeBrowser();
        ConfluenceAdapter adapter = adapter(browser);
        adapter.ping();
        validSession.set("tenant.session.token=s2"); // the session expired: Confluence now answers 302 to the login page
        var listing = adapter.listSpaces(100);
        assertEquals("ENG", listing.spaces().get(0).key());
        assertEquals(2, browser.opened.get());
        assertTrue(cookiesSeen.get(cookiesSeen.size() - 1).contains("s2"));
    }

    @Test
    void closingTheWindowBeforeSigningInIsAClearError() {
        FakeBrowser browser = new FakeBrowser();
        browser.closeEarly = true;
        SourceException e = assertThrows(SourceException.class, () -> adapter(browser).ping());
        assertEquals(SourceException.Kind.AUTH_FAILED, e.kind());
        assertTrue(e.getMessage().contains("closed"));
    }

    @Test
    void tokenSignInThatHitsALoginRedirectSuggestsBrowserSignIn() {
        SourceSettings s = new SourceSettings("confluence", origin, List.of("127.0.0.1"), List.of("/wiki/api/v2/"), "none", null,
                "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                new ApiOptions(null, null, null, null, false, null, null, "same-host"), null, null);
        ConfluenceOptions options = new ConfluenceOptions("cloud", "", List.of(), "", List.of("page"), "body.storage", false, 25, true, "", "",
                "v2", List.of(), ConfluenceOptions.Tvm.NONE);
        var adapter = new ConfluenceAdapter(s, options, new HttpSourceClient(s, k -> null), k -> null);
        SourceException e = assertThrows(SourceException.class, adapter::ping);
        assertTrue(e.getMessage().contains("login.corp.example.com"));
        assertTrue(e.getMessage().contains("source.auth.method=browser"));
    }

    @Test
    void configReadsBrowserSettingsWithoutAnySecret() throws Exception {
        Path file = tmp.resolve("qc.properties");
        Files.writeString(file, """
                storage.root=%s
                source.type=confluence
                source.baseUrl=https://acme.atlassian.net
                source.confluence.apiVersion=v2
                source.confluence.spaces=ENG
                source.auth.method=browser
                source.confluence.browser.timeoutSeconds=120
                source.allowedPathPrefixes=/wiki/api/v2/
                sync.tags=all
                """.formatted(tmp.resolve("data").toString().replace('\\', '/')));
        CoreConfig cfg = CoreConfig.load(file, Map.of());
        assertEquals(List.of(), cfg.validate());
        assertEquals(120, cfg.confluence().browser().timeoutSeconds());
        assertTrue(cfg.createAdapter(k -> null) instanceof ConfluenceAdapter, "creating the adapter does not open a browser");
        // Jive can be read through the browser too (its web pages, tag by tag): then 'all' is not a tag to search for
        CoreConfig jive = CoreConfig.load(file, Map.of("source.type", "jive"));
        assertTrue(jive.validate().stream().anyMatch(p -> p.contains("tag by tag")), jive.validate().toString());
        assertEquals(List.of(), CoreConfig.load(file, Map.of("source.type", "jive", "sync.tags", "casc")).validate());
    }
}
