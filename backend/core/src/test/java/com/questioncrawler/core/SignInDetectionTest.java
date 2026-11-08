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
}
