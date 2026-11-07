package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.ConfluenceOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pasted page or space address is understood as the Confluence site (no ".../Title/wiki" addresses). */
class ConfluenceSiteAddressTest {

    static final String PAGE = "https://xxx.atlassian.net/wiki/spaces/DGF/pages/8348961496/Casc+Acceleration+Tracker+Strategy+and+Improvement+Roadmap";

    @TempDir
    Path tmp;

    @Test
    void siteIsTakenFromPageSpaceAndSiteAddresses() {
        assertEquals("https://xxx.atlassian.net", ConfluenceOptions.siteBase(PAGE));
        assertEquals("https://xxx.atlassian.net", ConfluenceOptions.siteBase("https://xxx.atlassian.net/wiki/spaces/DGF/overview?homepageId=1"));
        assertEquals("https://xxx.atlassian.net", ConfluenceOptions.siteBase("https://xxx.atlassian.net/wiki"));
        assertEquals("https://xxx.atlassian.net", ConfluenceOptions.siteBase("https://xxx.atlassian.net/"));
        assertEquals("https://xxx.atlassian.net", ConfluenceOptions.siteBase("https://xxx.atlassian.net"));
        assertEquals("https://wiki.corp.com/confluence", ConfluenceOptions.siteBase("https://wiki.corp.com/confluence/display/ENG/Home"));
        assertEquals("https://wiki.corp.com", ConfluenceOptions.siteBase("https://wiki.corp.com/pages/viewpage.action?pageId=42"));
        assertEquals("https://wiki.corp.com/confluence", ConfluenceOptions.siteBase("https://wiki.corp.com/confluence"));
        assertEquals("DGF", ConfluenceOptions.spaceKeyFrom(PAGE));
        assertEquals("8348961496", ConfluenceOptions.pageIdFrom(PAGE));
        assertNull(ConfluenceOptions.spaceKeyFrom("https://xxx.atlassian.net"));
    }

    @Test
    void theAppNeverAppendsWikiToAPageAddress() {
        CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("d").toString(), "source.type", "confluence",
                "source.baseUrl", PAGE, "source.confluence.apiVersion", "v2", "source.confluence.spaces", "DGF",
                "source.auth.method", "browser", "sync.tags", "all"));
        assertEquals("https://xxx.atlassian.net", cfg.source().baseUrl());
        assertEquals("https://xxx.atlassian.net/wiki/api/v2", ConfluenceAdapter.resolveApiRoot(cfg.source().baseUrl(), cfg.confluence()));
        assertTrue(cfg.source().allowedHosts().contains("xxx.atlassian.net"));
        assertEquals(List.of(), cfg.validate());
    }

    @Test
    void savingAPageAddressStoresTheSiteAndItsSpace() throws Exception {
        Path file = tmp.resolve("qc.properties");
        Files.writeString(file, "storage.root=" + tmp.resolve("d").toString().replace('\\', '/') + "\n");
        ConfigService service = new ConfigService(k -> null);
        var result = service.save(file, Map.of("source.type", "confluence", "source.baseUrl", PAGE, "source.confluence.apiVersion", "v2",
                "source.auth.method", "browser", "sync.tags", "all", "sync.maxQuestionAge", "1y"));
        assertTrue(result.saved(), result.issues().toString());
        CoreConfig saved = CoreConfig.load(file, Map.of());
        assertEquals("https://xxx.atlassian.net", saved.source().baseUrl());
        assertEquals(List.of("DGF"), saved.confluence().spaces());
        assertTrue(Files.readString(file).contains("xxx.atlassian.net"));
        assertTrue(!Files.readString(file).contains("Roadmap"), "the page address is not kept as the site address");
        var issues = service.validate(new java.util.LinkedHashMap<>(Map.of("source.type", "confluence", "source.baseUrl", PAGE)));
        assertTrue(issues.stream().anyMatch(i -> i.key().equals("source.baseUrl") && i.level().equals("info")));
    }

    @Test
    void checkAccessSaysTheChosenSpaceWillBeCollectedInsteadOfWarning() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = "{\"results\":[{\"id\":\"1\",\"key\":\"DGF\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String site = "http://127.0.0.1:" + server.getAddress().getPort();
            var in = new com.questioncrawler.core.service.SiteDiagnostics.Input("confluence", site, "", List.of("all"), "none", null, null,
                    null, true, 5, com.questioncrawler.core.source.ProxyConfig.NONE, com.questioncrawler.core.source.ApiOptions.DEFAULT, null, null);
            ConfluenceOptions withSpace = CoreConfig.load(null, Map.of("source.type", "confluence", "source.baseUrl", site,
                    "source.confluence.apiVersion", "v2", "source.confluence.spaces", "DGF")).confluence();
            var scope = new com.questioncrawler.core.service.SiteDiagnostics(k -> null, java.time.Clock.systemUTC())
                    .withConfluence(withSpace, null).run(in).stages().stream().filter(st -> st.id().equals("scope")).findFirst().orElseThrow();
            assertEquals("PASS", scope.status(), scope.summary());
            assertTrue(scope.summary().contains("every page in the space DGF"), scope.summary());

            ConfluenceOptions noSpace = CoreConfig.load(null, Map.of("source.type", "confluence", "source.baseUrl", site,
                    "source.confluence.apiVersion", "v2")).confluence();
            var warn = new com.questioncrawler.core.service.SiteDiagnostics(k -> null, java.time.Clock.systemUTC())
                    .withConfluence(noSpace, null).run(in).stages().stream().filter(st -> st.id().equals("scope")).findFirst().orElseThrow();
            assertEquals("WARN", warn.status());
            assertTrue(warn.summary().contains("No space is chosen"), warn.summary());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPastedPageAddressCountsAsChoosingItsSpace() {
        CoreConfig cfg = CoreConfig.load(null, Map.of("storage.root", tmp.resolve("d").toString(), "source.type", "confluence",
                "source.baseUrl", PAGE, "source.confluence.apiVersion", "v2", "source.auth.method", "browser", "sync.tags", "all"));
        assertEquals(List.of("DGF"), cfg.confluence().spaces());
        assertEquals(List.of(), cfg.validate());
        var issues = new ConfigService(k -> null).validate(new java.util.LinkedHashMap<>(Map.of("source.type", "confluence",
                "source.baseUrl", PAGE, "source.auth.method", "browser", "source.confluence.apiVersion", "v2", "sync.tags", "all")));
        assertTrue(issues.stream().noneMatch(i -> i.level().equals("error")), issues.toString());
    }
}
