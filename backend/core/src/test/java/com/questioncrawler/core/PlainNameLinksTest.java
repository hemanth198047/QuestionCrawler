package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.ShortLinks;
import com.questioncrawler.core.source.WebPageReader;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Links typed without http:// or https:// (casc-docs/), and plain-http links. */
class PlainNameLinksTest {

    static final String PAGE = "<html><head><title>CASC docs</title></head><body><main><p>The CASC documentation home page, with guides and runbooks.</p></main></body></html>";

    @Test
    void typedNamesBecomeWebAddresses() {
        assertEquals("http://casc-docs/", ShortLinks.webAddress("casc-docs").toString());
        assertEquals("http://casc-docs/", ShortLinks.webAddress("casc-docs/").toString());
        assertEquals("http://casc-docs/guides/vpn", ShortLinks.webAddress("casc-docs/guides/vpn").toString());
        assertEquals("http://wiki.corp.com/", ShortLinks.webAddress("wiki.corp.com").toString());
        assertEquals("http://wiki:8080/x", ShortLinks.webAddress("wiki:8080/x").toString());
        assertEquals("http://casc-docs/a", ShortLinks.webAddress("http://casc-docs/a").toString());
        assertNull(ShortLinks.webAddress("ftp://files/x"));
        assertNull(ShortLinks.webAddress("not a link"));
    }

    @Test
    void aNameIsReadOverHttpAndAPlainHttpRedirectIsFollowed() throws Exception {
        List<String> asked = new ArrayList<>();
        WebPageReader reader = new WebPageReader((uri, cookie) -> {
            asked.add(uri.toString());
            return switch (uri.toString()) {
                case "http://casc-docs/" -> new ShortLinks.Hop(302, "https://docs.corp.com/casc", "");
                case "https://docs.corp.com/casc" -> new ShortLinks.Hop(302, "http://docs-old.corp.com/casc/home", ""); // https -> http
                case "http://docs-old.corp.com/casc/home" -> new ShortLinks.Hop(200, null, PAGE);
                default -> new ShortLinks.Hop(404, null, "");
            };
        }, null);
        var page = reader.read("casc-docs/");
        assertEquals("http://docs-old.corp.com/casc/home", page.finalUrl());
        assertTrue(page.html().contains("runbooks"));
    }

    @Test
    void whenHttpDoesNotAnswerHttpsIsTried() throws Exception {
        WebPageReader reader = new WebPageReader((uri, cookie) -> {
            if (uri.getScheme().equals("http")) {
                throw new IOException("connection refused");
            }
            return new ShortLinks.Hop(200, null, PAGE);
        }, null);
        assertEquals("https://casc-docs/", reader.read("casc-docs").finalUrl());
    }

    @Test
    void settingsAddressesWithoutSchemeAreCompletedAndTagsAreLeftAlone() {
        ShortLinks links = new ShortLinks(new ShortLinks.Options(true, List.of(), null, null, Duration.ofSeconds(2)), uri -> {
            if (uri.getHost().equals("acme.atlassian.net")) {
                return new ShortLinks.Hop(301, "https://acme.atlassian.net/", "");
            }
            return new ShortLinks.Hop(200, null, PAGE);
        }, k -> null);
        Map<String, String> v = new LinkedHashMap<>(Map.of("source.baseUrl", "acme.atlassian.net", "api.swaggerUrl", "casc-docs/swagger",
                "sync.tags", "ci/cd", "source.confluence.spaces", "DGF"));
        var notes = links.expandSettings(v, List.copyOf(v.keySet()));
        assertEquals("https://acme.atlassian.net", v.get("source.baseUrl"));
        assertEquals("http://casc-docs/swagger", v.get("api.swaggerUrl"));
        assertEquals("ci/cd", v.get("sync.tags"));
        assertEquals("DGF", v.get("source.confluence.spaces"));
        assertTrue(notes.get("source.baseUrl").contains("is used as"), notes.toString());
    }

    @Test
    void plainHttpIntranetLinksInContentAreFound() {
        ShortLinks links = new ShortLinks(new ShortLinks.Options(true, List.of("casc-docs"), null, null, null), uri -> new ShortLinks.Hop(404, null, ""), k -> null);
        assertEquals(List.of("http://casc-docs/guides", "casc-docs/"), links.findIn("See http://casc-docs/guides or just casc-docs/ and and/or http://localhost:8080/x"));
        assertTrue(links.isShortLink("http://casc-docs/x"));
        assertTrue(links.isShortLink("casc-docs/"));
        assertTrue(!links.isShortLink("http://localhost/x"));
        assertEquals(URI.create("http://casc-docs/"), ShortLinks.webAddress("casc-docs/"));
    }
}
