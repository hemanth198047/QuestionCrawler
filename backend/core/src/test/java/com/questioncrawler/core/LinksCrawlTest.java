package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.service.KnowledgeBase;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Give a page address: the page and the pages it links to are read and stored. */
class LinksCrawlTest {

    @TempDir
    Path tmp;
    HttpServer server;
    String base;
    final Set<String> requested = ConcurrentHashMap.newKeySet();

    static String page(String title, String text, String... links) {
        StringBuilder a = new StringBuilder();
        for (String l : links) {
            a.append("<a href=\"").append(l).append("\">").append(l).append("</a> ");
        }
        return "<html><head><title>" + title + "</title></head><body><main><h1>" + title + "</h1><p>" + text + "</p>" + a + "</main></body></html>";
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> pages = Map.of(
                "/docs/start", page("Start", "Welcome to the team documentation portal for new joiners.", "a", "/docs/b", "#top", "/logout", "/docs/guide.pdf",
                        "http://other.invalid/x", "/docs/a?x=1#part", "mailto:team@corp.com", "/elsewhere/e"),
                "/docs/a", page("Page A", "Alpha explains how to request VPN access for contractors.", "/docs/c"),
                "/docs/c", page("Page C", "Charlie is two clicks away and describes certificate renewal."),
                "/elsewhere/e", page("Page E", "Echo lives in another section of the same site."));
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            requested.add(path);
            if (path.equals("/docs/b")) { // another address of page A
                ex.getResponseHeaders().add("Location", "/docs/a");
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            String body = pages.get(path);
            byte[] b = (body == null ? "not found" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Set<String> titlesAfterCollecting(String depth, String scope) {
        CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("d" + depth + scope).toString(), "source.type", "links",
                "source.links.list", base + "/docs/start", "source.links.fromCollected", "false", "source.auth.method", "none",
                "source.links.depth", depth, "source.links.scope", scope));
        KnowledgeBase kb = new KnowledgeBase(cfg);
        kb.sync(new SyncOptions(List.of("all"), null, false, SyncMode.FULL, null, 2, 30));
        return kb.store().allThreads().stream().map(t -> t.question().title()).collect(Collectors.toSet());
    }

    @Test
    void thePageAndThePagesItLinksToAreStored() {
        assertEquals(Set.of("Start", "Page A", "Page E"), titlesAfterCollecting("1", "site"));
        assertFalse(requested.contains("/logout"), "a sign-out link is never followed");
        assertFalse(requested.contains("/docs/guide.pdf"), "files are not read");
        assertFalse(requested.contains("/docs/c"), "two clicks away is beyond depth 1");
    }

    @Test
    void deeperAndNarrower() {
        assertEquals(Set.of("Start", "Page A", "Page C"), titlesAfterCollecting("2", "section"));
        assertFalse(requested.contains("/elsewhere/e"), "outside the start page's section");
    }

    @Test
    void storedPagesAnswerSearches() {
        CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("s").toString(), "source.type", "links",
                "source.links.list", base + "/docs/start", "source.links.fromCollected", "false", "source.auth.method", "none"));
        KnowledgeBase kb = new KnowledgeBase(cfg);
        kb.sync(new SyncOptions(List.of("all"), null, false, SyncMode.FULL, null, 2, 30));
        var r = kb.search(new com.questioncrawler.core.search.SearchRequest("VPN access for contractors", List.of(), List.of(), 5, 20000, 2000));
        assertTrue(r.evidence().stream().anyMatch(e -> e.title().equals("Page A")), r.toString());
    }
}
