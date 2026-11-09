package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ShortLinks;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Short links (go/xyz, bit.ly, ...) are expanded in collected content and in pasted settings. */
class ShortLinksTest {

    @TempDir
    Path tmp;

    static final Map<String, String> TARGETS = Map.of(
            "http://go/vpn", "https://wiki.corp.com/display/IT/VPN+setup",
            "https://bit.ly/abc", "https://t.co/chain",
            "https://t.co/chain", "https://example.com/real?x=1",
            "http://go/secret", "https://sso.corp.com/login?next=x");

    private ShortLinks links(List<String> asked) {
        return new ShortLinks(new ShortLinks.Options(true, List.of(), null, null, Duration.ofSeconds(2)), uri -> {
            asked.add(uri.toString());
            String to = TARGETS.get(uri.toString());
            return to == null ? new ShortLinks.Hop(404, null, "") : new ShortLinks.Hop(302, to, "");
        }, k -> null);
    }

    @Test
    void shortLinksInContentKeepTheirNameAndGainTheRealAddress() {
        List<String> asked = new ArrayList<>();
        ShortLinks l = links(asked);
        String md = "See go/vpn, or [the guide](http://go/vpn) and <https://bit.ly/abc>.\n\n```\ncurl go/vpn\n```\nAlso `go/vpn` and go/unknown and go/vpn.";
        String out = l.expandIn(md);
        assertTrue(out.startsWith("See [go/vpn](https://wiki.corp.com/display/IT/VPN+setup), or [the guide](https://wiki.corp.com/display/IT/VPN+setup) and [https://bit.ly/abc](https://example.com/real?x=1)."), out);
        assertTrue(out.contains("```\ncurl go/vpn\n```"), "code is left alone: " + out);
        assertTrue(out.contains("`go/vpn`"), out);
        assertTrue(out.contains("go/unknown and"), "unknown links stay: " + out);
        assertTrue(out.endsWith("[go/vpn](https://wiki.corp.com/display/IT/VPN+setup)."), out);
        assertEquals(1, asked.stream().filter(a -> a.equals("http://go/vpn")).count(), "each link is looked up once");
    }

    @Test
    void aShortLinkThatAsksForASignInIsLeftAsItIs() {
        ShortLinks l = links(new ArrayList<>());
        assertEquals("Open go/secret now", l.expandIn("Open go/secret now"));
        assertTrue(l.resolve("go/secret").problem().contains("sign-in"));
    }

    @Test
    void wordsThatOnlyLookLikeLinksAreNotTouched() {
        List<String> asked = new ArrayList<>();
        ShortLinks l = links(asked);
        assertEquals("a go/no-go decision; ago/x; https://wiki.corp.com/go/vpn", l.expandIn("a go/no-go decision; ago/x; https://wiki.corp.com/go/vpn"));
        assertFalse(asked.contains("http://ago/x"));
        assertFalse(l.isShortLink("https://wiki.corp.com/go/vpn"));
        assertTrue(l.isShortLink("https://go.corp.com/vpn"));
        assertFalse(l.isShortLink("go/"));
    }

    @Test
    void collectedItemsAnswersAndCommentsAreExpanded() {
        ShortLinks l = links(new ArrayList<>());
        Question q = new Question("1", "VPN", "Use go/vpn", BodyFormat.MARKDOWN, "https://qa/1", List.of("vpn"), null, null, null, null,
                List.of(new com.questioncrawler.core.model.Comment("c", null, "go/vpn helps", null)), List.of());
        Question out = l.expand(q);
        assertEquals("Use [go/vpn](https://wiki.corp.com/display/IT/VPN+setup)", out.body());
        assertTrue(out.comments().get(0).body().startsWith("[go/vpn]("));
    }

    @Test
    void aPastedShortLinkIsSavedAsTheAddressItLeadsTo() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dgf", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "https://xxx.atlassian.net/wiki/spaces/DGF/overview");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try {
            String shortLink = "http://127.0.0.1:" + server.getAddress().getPort() + "/dgf";
            Path file = tmp.resolve("qc.properties");
            Files.writeString(file, "storage.root=" + tmp.resolve("d").toString().replace('\\', '/') + "\n");
            var result = new ConfigService(k -> null).save(file, Map.of("source.type", "confluence", "source.baseUrl", shortLink,
                    "network.shortLinkHosts", "127.0.0.1", "source.confluence.apiVersion", "v2", "source.auth.method", "browser", "sync.tags", "all"));
            assertTrue(result.saved(), result.issues().toString());
            CoreConfig saved = CoreConfig.load(file, Map.of());
            assertEquals("https://xxx.atlassian.net", saved.source().baseUrl());
            assertEquals(List.of("DGF"), saved.confluence().spaces());
            assertTrue(result.issues().stream().anyMatch(i -> i.key().equals("source.baseUrl") && i.level().equals("info") && i.message().contains("leads to")),
                    result.issues().toString());
            assertTrue(saved.shortLinks().enabled());
        } finally {
            server.stop(0);
        }
    }
}
