package com.questioncrawler.core.searchai;

import com.questioncrawler.core.source.BrowserLogin;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.SourceException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Sends Search-AI calls from INSIDE the person's own Edge/Chrome window, where they are already signed in (the same page that
 * answers {@code /searchapi/whoami} in the browser). The browser brings its cookies and Windows/Kerberos sign-in itself, so the
 * app needs no ticket, no curl and no cookie handling. The window is opened once, kept minimised and closed with the app.
 */
public final class SearchAiBrowserTransport implements SearchAiClient.Transport {

    private final SearchAiOptions options;
    private final Function<String, String> env;
    private final BrowserLogin login;

    public SearchAiBrowserTransport(SearchAiOptions options, Function<String, String> env) {
        this(options, env, null);
    }

    /** Test seam: a prepared login. */
    SearchAiBrowserTransport(SearchAiOptions options, Function<String, String> env, BrowserLogin login) {
        this.options = options;
        this.env = env;
        this.login = login != null ? login : defaultLogin(options, env);
    }

    private static BrowserLogin defaultLogin(SearchAiOptions options, Function<String, String> env) {
        URI base = URI.create(options.baseUri());
        String origin = BrowserLogin.originOf(base);
        String start = options.browser().loginUrl().isBlank() ? origin + "/" : options.browser().loginUrl();
        // The window counts as signed in once /whoami answers with data inside it, and the app then reads through it.
        return BrowserLogin.forSite(origin, start, options.browser(), env).withContentCheck(List.of(options.baseUri() + "/whoami"));
    }

    @Override
    public SearchAiClient.Reply send(String method, String url, String body) throws IOException {
        URI uri = URI.create(url);
        BrowserLogin.Session session = BrowserLogin.transportFor(uri);
        if (session == null) {
            try {
                login.invalidate(login.current());
                login.cookieHeader(cookies -> false); // cookies alone never count: always read through the window
            } catch (SourceException e) {
                throw new SearchAiException(e.getMessage(), 401, e);
            }
            session = BrowserLogin.transportFor(uri);
        }
        if (session == null) {
            throw new SearchAiException("Your browser opened Search-AI, but the app could not read through it. Check that Edge or Chrome is installed "
                    + "(or set source.browser.path) and try again.", 401);
        }
        Map<String, String> headers = body == null
                ? Map.of("Accept", "application/json")
                : Map.of("Accept", "application/json", "Content-Type", "application/json");
        HttpSourceClient.Response r = session.fetchInPage(method, url, BrowserLogin.originOf(uri), headers, body);
        if (r == null) {
            throw new SearchAiException("The browser window is still on the sign-in page. Sign in there, then try again.", 401);
        }
        if (r.status() >= 300 && r.status() < 400) {
            login.invalidate(null);
            throw new SearchAiException("The browser was sent to a sign-in page (" + r.status() + "). Sign in in the browser window and try again.", 401);
        }
        return new SearchAiClient.Reply(r.status(), r.body() == null ? "" : r.body());
    }
}
