package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.LinksAdapter;
import com.questioncrawler.core.source.ShortLinks;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.WebPageReader;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Links source: short links expanded, the pages behind them read, signing in through the browser when asked. */
class LinksSourceTest {

    @TempDir
    Path tmp;

    static final String PAGE = "<html><head><title>VPN setup - IT</title></head><body><nav>menu</nav><main><h1>VPN setup</h1>"
            + "<p>Install the client, then sign in with your badge number. Restart the laptop once.</p></main></body></html>";

    @Test
    void aShortLinkBehindTheCompanyLoginIsReadAfterSigningInThroughTheBrowser() throws Exception {
        List<String> cookiesSent = new ArrayList<>();
        AtomicInteger windows = new AtomicInteger();
        WebPageReader.Http http = (uri, cookie) -> {
            cookiesSent.add(uri.getHost() + "=" + cookie);
            String u = uri.toString();
            if (u.equals("http://go/vpn")) {
                return new ShortLinks.Hop(302, "https://wiki.corp.com/display/IT/VPN", "");
            }
            if (u.equals("https://wiki.corp.com/display/IT/VPN")) {
                return "sid=ok".equals(cookie) ? new ShortLinks.Hop(200, null, PAGE)
                        : new ShortLinks.Hop(302, "https://login.corp.com/sso/saml?RelayState=x", "");
            }
            return new ShortLinks.Hop(404, null, "");
        };
        WebPageReader.SignIns signIns = (origin, start) -> new BrowserLogin(origin, start, url -> {
            windows.incrementAndGet();
            return new BrowserLogin.Session() {
                public List<BrowserLogin.Cookie> cookies() {
                    return List.of(new BrowserLogin.Cookie("sid", "ok", "wiki.corp.com", "/"), new BrowserLogin.Cookie("other", "x", "elsewhere.com", "/"));
                }

                public boolean alive() {
                    return true;
                }

                public void close() {
                }
            };
        }, Duration.ofSeconds(5), Duration.ofMillis(10), Clock.systemUTC()).acceptingWebPages();
        WebPageReader reader = new WebPageReader(true, http, signIns);

        WebPageReader.WebPage page = reader.read("go/vpn");
        assertEquals("https://wiki.corp.com/display/IT/VPN", page.finalUrl());
        assertTrue(page.html().contains("badge number"));
        assertEquals(1, windows.get(), "one sign-in window for the site");
        assertTrue(cookiesSent.stream().filter(c -> c.startsWith("go=")).allMatch(c -> c.equals("go=null")), "no cookie goes to the short-link service: " + cookiesSent);
        assertTrue(cookiesSent.stream().noneMatch(c -> c.contains("other=x")), "cookies of other sites are never sent");

        var q = LinksAdapter.toQuestion(LinksAdapter.idFor("go/vpn"), "go/vpn", page);
        assertEquals("VPN setup - IT", q.title());
        assertTrue(q.body().contains("(link: go/vpn)") && !q.body().contains("menu"), q.body());
        assertTrue(q.tags().containsAll(List.of("link", "site:wiki.corp.com", "short-link")), q.tags().toString());
    }

    @Test
    void withoutBrowserSignInAPageThatNeedsOneIsSkippedWithTheReason() {
        WebPageReader.Http http = (uri, cookie) -> new ShortLinks.Hop(302, "https://login.corp.com/login", "");
        WebPageReader reader = new WebPageReader(false, http, (o, s) -> {
            throw new AssertionError("no window may open");
        });
        SourceException e = assertThrows(SourceException.class, () -> reader.read("https://wiki.corp.com/x"));
        assertEquals(SourceException.Kind.FORBIDDEN, e.kind());
        assertTrue(e.getMessage().contains("needs a sign-in"), e.getMessage());
    }

    @Test
    void shortLinksInCollectedItemsAreFound() throws Exception {
        Path threads = tmp.resolve("threads");
        Files.createDirectories(threads.resolve("confluence"));
        Files.createDirectories(threads.resolve("links"));
        Files.writeString(threads.resolve("confluence").resolve("1.json"), "{\"body\":\"See [go/vpn](https://wiki/x) and https://bit.ly/abc.\\nAlso go/vpn\"}");
        Files.writeString(threads.resolve("links").resolve("2.json"), "{\"body\":\"go/ignored\"}");
        ShortLinks finder = new ShortLinks(new ShortLinks.Options(true, List.of(), null, null, null), uri -> new ShortLinks.Hop(404, null, ""), k -> null);
        LinksAdapter a = new LinksAdapter("links", List.of("https://intranet.corp.com/page"), true, threads, finder, null);
        assertEquals(List.of("https://intranet.corp.com/page", "go/vpn", "https://bit.ly/abc"), a.links());
    }

    @Test
    void collectingReadsEveryListedPage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/s/vpn", ex -> {
            ex.getResponseHeaders().add("Location", "/wiki/vpn");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/wiki/vpn", ex -> {
            byte[] b = PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("data").toString(), "source.type", "links",
                    "source.links.list", base + "/s/vpn, " + base + "/missing", "source.links.fromCollected", "false", "source.auth.method", "none"));
            assertEquals(List.of(), cfg.validate());
            KnowledgeBase kb = new KnowledgeBase(cfg);
            var report = kb.sync(new SyncOptions(List.of("all"), null, false, SyncMode.FULL, null, 2, 30));
            assertEquals(1, report.added(), report.toString());
            ThreadRecord t = kb.store().allThreads().get(0);
            assertEquals("links", t.sourceSystem());
            assertEquals(base + "/wiki/vpn", t.question().url());
            assertTrue(t.question().body().contains("badge number"), t.question().body());
            assertNull(t.question().answers().isEmpty() ? null : "answers");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anAddressThatIsNotALinkIsRefused() {
        WebPageReader reader = new WebPageReader(true, (u, c) -> new ShortLinks.Hop(200, null, PAGE), (o, s) -> null);
        assertThrows(SourceException.class, () -> reader.read("not a link"));
        assertEquals("http://go/vpn", URI.create("http://go/vpn").toString());
    }
}
