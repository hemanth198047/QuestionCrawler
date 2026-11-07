package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.ConfluenceOptions;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.SourceSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end with a REAL Edge/Chrome/Chromium: a mock Confluence redirects (302) to a mock company login page, which
 * "signs in" by itself and redirects back; the app must notice, take the session cookie and read the API.
 * Runs only when QC_BROWSER_E2E names the browser program (it needs a screen, for example xvfb-run).
 */
@EnabledIfEnvironmentVariable(named = "QC_BROWSER_E2E", matches = ".+")
class BrowserLoginRealBrowserTest {

    @TempDir
    Path tmp;

    @Test
    void signsInThroughARedirectToALoginPageAndReadsTheApi() throws Exception {
        HttpServer login = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer wiki = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String wikiOrigin = "http://127.0.0.1:" + wiki.getAddress().getPort();
        String loginOrigin = "http://localhost:" + login.getAddress().getPort(); // another host, like a real IdP
        wiki.createContext("/", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            String path = ex.getRequestURI().getPath();
            if (path.equals("/wiki/sso/callback")) { // the IdP sends the person back: Confluence starts a session
                ex.getResponseHeaders().add("Set-Cookie", "tenant.session.token=REAL-SESSION; Path=/; HttpOnly");
                ex.getResponseHeaders().add("Location", wikiOrigin + "/wiki/home");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (cookie == null || !cookie.contains("tenant.session.token=REAL-SESSION")) {
                ex.getResponseHeaders().add("Location", loginOrigin + "/saml/login?back=" + wikiOrigin + "/wiki/sso/callback");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (path.startsWith("/wiki/api/v2/spaces")) {
                send(ex, "application/json", "{\"results\":[{\"id\":\"1\",\"key\":\"ENG\",\"name\":\"Engineering\"}]}");
            } else {
                send(ex, "text/html", "<h1>Confluence home</h1>");
            }
        });
        login.createContext("/", ex -> {
            String back = ex.getRequestURI().getQuery().replace("back=", "");
            // The person would type their credentials here; this page "submits" itself after a moment.
            send(ex, "text/html", "<form><input type=password></form><script>setTimeout(function(){location.href='" + back + "'}, 1500)</script>");
        });
        wiki.start();
        login.start();
        try {
            SourceSettings s = new SourceSettings("confluence", wikiOrigin, List.of("127.0.0.1"), List.of("/wiki/api/v2/"), "browser", null,
                    "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                    new ApiOptions(null, null, null, null, false, null, null, "same-host"), null, null);
            ConfluenceOptions.Browser b = new ConfluenceOptions.Browser(System.getenv("QC_BROWSER_E2E"), tmp.resolve("profile").toString(), "", 60);
            ConfluenceOptions options = new ConfluenceOptions("cloud", "", List.of(), "", List.of("page"), "body.storage", false, 25, true, "", "",
                    "v2", List.of(), ConfluenceOptions.Tvm.NONE, b);
            BrowserLogin signIn = new BrowserLogin(wikiOrigin, wikiOrigin + "/wiki/", new BrowserLogin.ChromeLauncher(b, System::getenv),
                    Duration.ofSeconds(60), Duration.ofSeconds(1), Clock.systemUTC());
            ConfluenceAdapter adapter = ConfluenceAdapter.withBrowserLogin(s, options, System::getenv, signIn);
            var spaces = adapter.listSpaces(10);
            assertEquals("ENG", spaces.spaces().get(0).key());
            assertEquals(1, signIn.loginCount());
            assertTrue(signIn.status().startsWith("signed in"));
        } finally {
            wiki.stop(0);
            login.stop(0);
        }
    }

    @Test
    void theInternalQaSiteIsReadAfterARealBrowserSignIn() throws Exception {
        HttpServer login = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer qa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String qaOrigin = "http://127.0.0.1:" + qa.getAddress().getPort();
        String loginOrigin = "http://localhost:" + login.getAddress().getPort();
        qa.createContext("/", ex -> {
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            String path = ex.getRequestURI().getPath();
            if (path.equals("/sso/callback")) {
                ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=QA-SESSION; Path=/; HttpOnly");
                ex.getResponseHeaders().add("Location", qaOrigin + "/questions");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (cookie == null || !cookie.contains("JSESSIONID=QA-SESSION")) {
                ex.getResponseHeaders().add("Location", loginOrigin + "/adfs/login?back=" + qaOrigin + "/sso/callback");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            if (path.startsWith("/api/")) {
                send(ex, "application/json", "{\"items\":[{\"total_questions\":7}]}");
            } else {
                send(ex, "text/html", "<h1>Q&A home</h1>");
            }
        });
        login.createContext("/", ex -> {
            String back = ex.getRequestURI().getQuery().replace("back=", "");
            send(ex, "text/html", "<form><input type=password></form><script>setTimeout(function(){location.href='" + back + "'}, 1500)</script>");
        });
        qa.start();
        login.start();
        try {
            SourceSettings s = new SourceSettings("internal-qa", qaOrigin + "/api/2.3", List.of("127.0.0.1"), List.of("/"), "browser", null,
                    "Authorization", "Bearer", null, Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE,
                    new ApiOptions("key", "query", null, "withbody", false, null, null, "same-host"), null, null);
            ConfluenceOptions.Browser b = new ConfluenceOptions.Browser(System.getenv("QC_BROWSER_E2E"), tmp.resolve("profile2").toString(), "", 60);
            BrowserLogin signIn = new BrowserLogin(qaOrigin, qaOrigin + "/", new BrowserLogin.ChromeLauncher(b, System::getenv),
                    Duration.ofSeconds(60), Duration.ofSeconds(1), Clock.systemUTC());
            var probeClient = new com.questioncrawler.core.source.HttpSourceClient(s, k -> null);
            BrowserLogin.Prober probe = cookie -> {
                try {
                    return BrowserLogin.letIn(probeClient.exchange(qaOrigin + "/api/2.3/info", java.util.Map.of("Cookie", cookie)));
                } catch (com.questioncrawler.core.source.SourceException e) {
                    return false;
                }
            };
            var adapter = new com.questioncrawler.core.source.StackExchangeAdapter(s,
                    new com.questioncrawler.core.source.HttpSourceClient(s, k -> null, () -> signIn.cookieHeader(probe)), k -> null,
                    com.questioncrawler.core.util.Sleeper.REAL, null).withBrowser(signIn, probe);
            String ping = adapter.ping();
            assertTrue(ping.contains("7"), ping);
            assertEquals(1, signIn.loginCount());
        } finally {
            qa.stop(0);
            login.stop(0);
        }
    }

    private static void send(HttpExchange ex, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
