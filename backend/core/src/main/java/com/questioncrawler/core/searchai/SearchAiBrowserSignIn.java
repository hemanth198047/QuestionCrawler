package com.questioncrawler.core.searchai;

import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.SourceException;
import java.net.URI;
import java.util.Locale;
import java.util.function.Function;

/**
 * "Sign in with my browser": when search-ai answers unauthenticated, open the person's own Edge/Chrome on the Search-AI site,
 * let them sign in on the real company login page (SSO, MFA...), take the session cookie for that host, and use it for the
 * API calls. Same mechanism as Confluence. The app never sees or types a password; the cookie stays in memory.
 */
public final class SearchAiBrowserSignIn {

    private SearchAiBrowserSignIn() {}

    public static void signIn(SearchAiOptions options, SearchAiClient client, Function<String, String> env) {
        URI base = URI.create(options.baseUri());
        String origin = base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getRawAuthority().toLowerCase(Locale.ROOT);
        String start = options.browser().loginUrl().isBlank() ? origin + "/" : options.browser().loginUrl();
        signIn(BrowserLogin.forSite(origin, start, options.browser(), env), client);
    }

    /** Test seam: the browser login is passed in. */
    public static void signIn(BrowserLogin login, SearchAiClient client) {
        String stale = login.current();
        if (stale != null && !client.accepts(stale)) {
            login.invalidate(stale); // expired: sign in again instead of reusing it
        }
        String header;
        try {
            header = login.cookieHeader(client::accepts);
        } catch (SourceException e) {
            throw new SearchAiException(e.getMessage(), 401, e);
        }
        if (header == null || BrowserLogin.THROUGH_BROWSER.equals(header)) {
            login.invalidate(header);
            throw new SearchAiException("Search-AI let your browser in, but its sign-in only works inside the browser window and cannot be reused by "
                    + "the app. Use your company Kerberos sign-in on this computer instead (be on the company network and signed in to your computer).", 401);
        }
        client.useBrowserCookie(header);
    }
}
