package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.HttpSourceClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Recognising a company login, including a login page on the site's own host (Stack Overflow Enterprise /users/login). */
class SignInDetectionTest {

    private static HttpSourceClient.Response page(int status, String type, String body, String finalUrl, List<String> redirects) {
        return new HttpSourceClient.Response(status, body, null, type, null, null, finalUrl, redirects, null);
    }

    @Test
    void aSameHostRedirectToALoginPageNeedsSignIn() {
        var r = page(200, "text/html", "<html><body>Redirecting to your company sign-in…<script>location.href='https://sso'</script></body></html>",
                "https://qa.corp.com/users/login?returnurl=%2fquestions", List.of("302 to https://qa.corp.com/users/login"));
        assertTrue(BrowserLogin.needsSignIn(r));
    }

    @Test
    void aRedirectElsewhereOrALoginFormNeedsSignIn() {
        assertTrue(BrowserLogin.needsSignIn(page(302, "text/html", "", "https://qa.corp.com/questions", List.of())));
        assertTrue(BrowserLogin.needsSignIn(page(401, "text/html", "", "https://qa.corp.com/questions", List.of())));
        assertTrue(BrowserLogin.needsSignIn(page(200, "text/html", "<form><input type=\"password\"></form>", "https://qa.corp.com/", List.of())));
    }

    @Test
    void contentDoesNotNeedSignIn() {
        assertFalse(BrowserLogin.needsSignIn(page(200, "text/html", "<div class=\"s-post-summary\"></div>", "https://qa.corp.com/questions", List.of())));
        assertFalse(BrowserLogin.needsSignIn(page(200, "text/html", "<p>ok</p>", "https://qa.corp.com/questions?page=2",
                List.of("301 to https://qa.corp.com/questions?page=2"))));
    }

    @Test
    void theLoginForwardingPageIsNotMistakenForBeingSignedIn() {
        // The bug: an anonymous cookie + a request that ends on /users/login (a page without a password field) closed
        // the sign-in window at once. Only content behind the login counts as signed in.
        var loginPage = page(200, "text/html", "<html><body>Redirecting to your company sign-in…</body></html>",
                "https://qa.corp.com/users/login?returnurl=%2fquestions", List.of("302 to https://qa.corp.com/users/login"));
        assertFalse(BrowserLogin.showsContent(loginPage));
        var notFound = page(200, "text/html", "<html><body>Page not found</body></html>", "https://qa.corp.com/info", List.of());
        assertFalse(BrowserLogin.showsContent(notFound), "an ordinary page is not proof of being signed in");
        var webPage = page(200, "text/html", "<div class=\"content\">Welcome</div>", "https://qa.corp.com/wiki/home", List.of());
        assertFalse(BrowserLogin.showsContent(webPage), "only API data counts as proof of being signed in");
        assertTrue(BrowserLogin.showsContent(page(200, "application/json", "{\"items\":[]}", "https://qa.corp.com/api/2.3/questions", List.of())));
        assertTrue(BrowserLogin.showsContent(page(400, "application/json", "{\"error_message\":\"key required\"}", "https://qa.corp.com/api/2.3/questions", List.of())),
                "the API itself answered: the company login was passed");
        assertFalse(BrowserLogin.showsContent(page(401, "application/json", "{}", "https://qa.corp.com/api/2.3/questions", List.of())));
    }

    @Test
    void aSiteThatOnlyLetsTheBrowserInIsReadThroughTheWindow() throws Exception {
        // Already signed in (Windows sign-in): the window shows content, but no cookie works for the app's own requests.
        java.util.concurrent.atomic.AtomicBoolean minimised = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        BrowserLogin.Launcher launcher = start -> new BrowserLogin.Session() {
            @Override
            public List<BrowserLogin.Cookie> cookies() {
                return List.of(new BrowserLogin.Cookie("anon", "1", "winqa.example.com", "/"));
            }

            @Override
            public boolean alive() {
                return !closed.get();
            }

            @Override
            public HttpSourceClient.Response fetchInPage(String url, String origin, java.util.Map<String, String> headers) {
                return page(200, "application/json", "{\"results\":[{\"key\":\"ENG\"}]}", url, List.of());
            }

            @Override
            public void minimize() {
                minimised.set(true);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        BrowserLogin login = new BrowserLogin("https://winqa.example.com", "https://winqa.example.com/", launcher,
                java.time.Duration.ofSeconds(10), java.time.Duration.ofMillis(10), java.time.Clock.systemUTC())
                .withContentCheck(List.of("https://winqa.example.com/wiki/api/v2/spaces?limit=1"));
        login.cookieHeader(cookie -> false); // the cookies never work outside the browser
        assertTrue(login.readsThroughBrowser());
        assertTrue(minimised.get(), "the window is minimised, not closed");
        assertFalse(closed.get());
        assertTrue(BrowserLogin.transportFor(java.net.URI.create("https://winqa.example.com/questions/7")) != null,
                "requests to the site go through the window");
        closed.set(true); // the person closes the minimised window
        assertTrue(BrowserLogin.transportFor(java.net.URI.create("https://winqa.example.com/questions/7")) == null);
        assertFalse(login.readsThroughBrowser());
    }
}
