package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.SourceSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * No sign-in saved, but the site sits behind the company login: a request that lands on the login page signs in through
 * the browser (or reuses the window already open) and is sent again. A public site never opens a window.
 */
class AutoSignInTest {

    @Test
    void aLoginAnswerSignsInThroughTheBrowserAndTheRequestGoesThrough() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            boolean signedIn = String.valueOf(ex.getRequestHeaders().getFirst("Cookie")).contains("SESSION=ok");
            String path = ex.getRequestURI().getPath();
            String body = path.equals("/users/login") ? "<html><body>Log in - Example</body></html>"
                    : "<div class=\"s-post-summary\" data-post-id=\"1\"></div>";
            if (!signedIn && !path.equals("/users/login")) {
                ex.getResponseHeaders().add("Location", "/users/login?returnurl=%2fquestions");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            AtomicInteger windows = new AtomicInteger();
            BrowserLogin.Launcher launcher = start -> {
                windows.incrementAndGet();
                return new BrowserLogin.Session() {
                    @Override
                    public List<BrowserLogin.Cookie> cookies() {
                        return List.of(new BrowserLogin.Cookie("SESSION", "ok", "127.0.0.1", "/"));
                    }

                    @Override
                    public boolean alive() {
                        return true;
                    }

                    @Override
                    public void close() {
                    }
                };
            };
            BrowserLogin login = new BrowserLogin(origin, origin + "/", launcher, Duration.ofSeconds(10), Duration.ofMillis(10), Clock.systemUTC());
            SourceSettings s = new SourceSettings("internal-qa", origin, List.of("127.0.0.1"), List.of("/"), "none", null, "Authorization", "Bearer", null,
                    Duration.ofSeconds(5), true, null, null, ProxyConfig.NONE, new ApiOptions(null, null, null, null, false, null, null, "all"), null, null);
            HttpSourceClient probe = new HttpSourceClient(s, k -> null);
            HttpSourceClient client = new HttpSourceClient(s, k -> null, () -> login.cookieHeader(cookie ->
                    BrowserLogin.showsContent(probe.exchange(origin + "/questions", Map.of("Cookie", cookie)))));
            var r = client.exchange(origin + "/questions");
            assertEquals(200, r.status());
            assertTrue(r.body().contains("s-post-summary"), r.body());
            assertEquals(1, windows.get());
            client.exchange(origin + "/questions/1");
            assertEquals(1, windows.get(), "the session is reused: no second window");
        } finally {
            server.stop(0);
        }
    }
}
