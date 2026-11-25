package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.searchai.CurlRunner;
import com.questioncrawler.core.searchai.SearchAiBrowserSignIn;
import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiException;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.source.BrowserLogin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** "Sign in with my browser" for search-ai, with a fake browser window and a fake curl. */
class SearchAiBrowserSignInTest {

    private static final String BASE = "https://search-ai.example.test/searchapi";

    @TempDir
    Path tmp;

    private SearchAiOptions options() {
        return new SearchAiOptions(BASE, tmp.resolve("jar"), "curl", 30, "", 10, tmp.resolve("store"));
    }

    /** Accepts only requests whose private header file carries the good session; records the command lines. */
    private static final class SessionCurl implements CurlRunner {
        final List<List<String>> calls = new ArrayList<>();
        final List<String> headerFiles = new ArrayList<>();
        final List<Path> headerPaths = new ArrayList<>();
        String goodCookie = "sid=abc";
        int redirectWhenBad = 302;

        @Override
        public Result run(List<String> command, int timeoutSeconds) {
            calls.add(command);
            String sent = "";
            int h = command.indexOf("-H");
            for (int i = 0; i < command.size() - 1; i++) {
                if (command.get(i).equals("-H") && command.get(i + 1).startsWith("@")) {
                    Path f = Path.of(command.get(i + 1).substring(1));
                    headerPaths.add(f);
                    try {
                        sent = Files.readString(f);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            }
            headerFiles.add(sent);
            boolean good = sent.contains(goodCookie);
            String url = command.get(command.size() - 1);
            if (!good && !sent.isEmpty()) {
                return new Result(0, "\n__QC_HTTP_STATUS__:" + redirectWhenBad, "");
            }
            if (!good && url.contains("whoami")) {
                return new Result(0, "unauthorized\n__QC_HTTP_STATUS__:401", "");
            }
            return new Result(0, "{\"ok\":true}\n__QC_HTTP_STATUS__:200", "");
        }
    }

    private static BrowserLogin loginWith(List<BrowserLogin.Cookie> cookies, boolean alive) {
        BrowserLogin.Launcher launcher = start -> new BrowserLogin.Session() {
            @Override public List<BrowserLogin.Cookie> cookies() { return cookies; }
            @Override public boolean alive() { return alive; }
            @Override public void close() { }
        };
        return new BrowserLogin("https://search-ai.example.test", "https://search-ai.example.test/", launcher,
                Duration.ofSeconds(5), Duration.ofMillis(10), Clock.systemUTC());
    }

    @Test
    void theBrowserSessionIsTakenAndUsedWithoutPuttingTheCookieOnTheCommandLine() {
        SessionCurl curl = new SessionCurl();
        SearchAiClient client = new SearchAiClient(options(), curl);
        BrowserLogin login = loginWith(List.of(
                new BrowserLogin.Cookie("sid", "abc", "search-ai.example.test", "/"),
                new BrowserLogin.Cookie("other", "x", "evil.example", "/")), true);

        SearchAiBrowserSignIn.signIn(login, client);
        assertTrue(client.usesBrowserSession());

        client.search("q", 5, null, null, null);
        List<String> last = curl.calls.get(curl.calls.size() - 1);
        assertFalse(String.join(" ", last).contains("sid=abc"), "the cookie must not appear in the command line");
        assertTrue(curl.headerFiles.get(curl.headerFiles.size() - 1).contains("Cookie: sid=abc"));
        assertFalse(curl.headerFiles.get(curl.headerFiles.size() - 1).contains("other=x"), "cookies of other hosts are not taken");
        assertFalse(last.contains("--location-trusted"), "a session cookie is never sent on to a redirect target");
        assertTrue(curl.headerPaths.stream().noneMatch(Files::exists), "the private header file is deleted after each call");
    }

    @Test
    void anExpiredBrowserSessionIsDroppedSoTheScreenAsksForANewSignIn() {
        SessionCurl curl = new SessionCurl();
        SearchAiClient client = new SearchAiClient(options(), curl);
        client.useBrowserCookie("sid=old");
        SearchAiException e = assertThrows(SearchAiException.class, () -> client.search("q", 5, null, null, null));
        assertEquals(401, e.httpStatus());
        assertFalse(client.usesBrowserSession());
        assertFalse(client.accepts("sid=old"));
        assertTrue(client.accepts("sid=abc"));
    }

    @Test
    void closingTheWindowBeforeSigningInIsExplained() {
        SessionCurl curl = new SessionCurl();
        SearchAiClient client = new SearchAiClient(options(), curl);
        SearchAiException e = assertThrows(SearchAiException.class,
                () -> SearchAiBrowserSignIn.signIn(loginWith(List.of(), false), client));
        assertEquals(401, e.httpStatus());
        assertTrue(e.getMessage().contains("closed"));
        assertFalse(client.usesBrowserSession());
    }

    @Test
    void aSignInPageWhereDataIsExpectedCountsAsNotSignedIn() {
        CurlRunner html = (c, t) -> new CurlRunner.Result(0, "<!DOCTYPE html><html><title>Sign in</title></html>\n__QC_HTTP_STATUS__:200", "");
        SearchAiException e = assertThrows(SearchAiException.class, () -> new SearchAiClient(options(), html).whoami());
        assertEquals(401, e.httpStatus());
        assertTrue(e.getMessage().contains("sign-in page"));
    }
}
