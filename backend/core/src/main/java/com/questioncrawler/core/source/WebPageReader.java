package com.questioncrawler.core.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the web page a link leads to: follows short links and redirects one at a time, and when a site wants a
 * sign-in, opens the company login in a browser window (as for Confluence), waits for the person to sign in, and
 * then reads the page with that session. Read-only: only GET requests.
 *
 * <p>Cookies from a browser sign-in are sent only to the site they belong to. No password, token or key is ever
 * sent by this reader.
 */
public final class WebPageReader {

    /** The page a link led to. */
    public record WebPage(String link, String finalUrl, String html, String contentType, List<String> hops, String signIn) {}

    /** How requests are made. Replaceable in tests. */
    @FunctionalInterface
    public interface Http {
        ShortLinks.Hop get(URI uri, String cookieHeader) throws IOException;
    }

    /** Opens a browser sign-in for a site. Replaceable in tests. */
    @FunctionalInterface
    public interface SignIns {
        BrowserLogin forSite(String origin, String startUrl);
    }

    private static final int MAX_HOPS = 10;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Pattern META_REFRESH = Pattern.compile(
            "(?is)<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"']\\s*\\d*\\s*;\\s*url\\s*=\\s*'?([^\"'>\\s]+)");

    private final boolean browserSignIn;
    private final Http http;
    private final SignIns signIns;
    private final Map<String, BrowserLogin> logins = new ConcurrentHashMap<>();
    /** Sites whose sign-in did not finish in this run: their other pages are skipped instead of opening the window again. */
    private final Set<String> declined = ConcurrentHashMap.newKeySet();

    public WebPageReader(boolean browserSignIn, Http http, SignIns signIns) {
        this.browserSignIn = browserSignIn;
        this.http = http;
        this.signIns = signIns;
    }

    /** The reader used by the app: plain HTTP (proxy and company certificate from the settings), Edge/Chrome for sign-ins. */
    public static WebPageReader create(ShortLinks.Options net, ConfluenceOptions.Browser browser, boolean browserSignIn, Function<String, String> env) {
        Http http = httpFor(net, env);
        Map<String, BrowserLogin> shared = new ConcurrentHashMap<>();
        SignIns signIns = (origin, start) -> shared.computeIfAbsent(origin, o -> new BrowserLogin(o, start,
                new BrowserLogin.ChromeLauncher(browser, env), Duration.ofSeconds(browser.timeoutSeconds()), Duration.ofSeconds(2), Clock.systemUTC()))
                .acceptingWebPages().withContentCheck(List.of(start));
        return new WebPageReader(browserSignIn, http, signIns);
    }

    /** Reads the page {@code link} leads to (signing in through the browser when the site asks for it). */
    public WebPage read(String link) throws SourceException {
        URI first = toUri(link);
        if (first == null) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "'" + link + "' is not a web address");
        }
        if (ShortLinks.schemeGuessed(link)) {
            // Typed without http:// or https:// (casc-docs/): http first, https if http does not answer.
            first = reachableScheme(first);
        }
        String signedIn = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Outcome o = follow(first);
            if (o.page() != null) {
                return new WebPage(link, o.page().finalUrl(), o.page().html(), o.page().contentType(), o.page().hops(), signedIn);
            }
            // The site wants a sign-in at o.needsSignIn (the address that sent us to its login page).
            URI site = o.needsSignIn();
            String origin = BrowserLogin.originOf(site);
            if (!browserSignIn) {
                throw new SourceException(SourceException.Kind.FORBIDDEN, Redirects.show(site) + " needs a sign-in (" + o.why()
                        + "). Choose 'Open a browser window to sign in' under Signing in to read it.");
            }
            if (declined.contains(origin)) {
                throw new SourceException(SourceException.Kind.FORBIDDEN, "Skipped: the sign-in to " + site.getHost() + " was not finished earlier in this run.");
            }
            BrowserLogin login = logins.computeIfAbsent(origin, x -> signIns.forSite(origin, site.toString()));
            String before = login.current();
            if (before != null && attempt > 0) {
                login.invalidate(before); // the session no longer works: sign in again
            }
            try {
                login.cookieHeader(cookie -> accepted(site, cookie));
                signedIn = "signed in to " + site.getHost() + " through the browser";
            } catch (SourceException e) {
                declined.add(origin);
                throw new SourceException(SourceException.Kind.FORBIDDEN, "Could not sign in to " + site.getHost() + ": " + e.getMessage(), null, e);
            }
        }
        throw new SourceException(SourceException.Kind.FORBIDDEN, "The site still asks for a sign-in after signing in through the browser: " + Redirects.show(first));
    }

    /** Per site: whether an address typed without a scheme is reached with http or https (asked once). */
    private final Map<String, String> schemes = new ConcurrentHashMap<>();

    /** http://name/... when it answers; otherwise https://name/... when that answers; otherwise the http address. */
    private URI reachableScheme(URI httpFirst) {
        String known = schemes.get(httpFirst.getRawAuthority().toLowerCase(Locale.ROOT));
        if (known != null) {
            return known.equals("https") ? ShortLinks.withHttps(httpFirst) : httpFirst;
        }
        URI chosen = probeScheme(httpFirst);
        schemes.put(httpFirst.getRawAuthority().toLowerCase(Locale.ROOT), chosen.getScheme());
        return chosen;
    }

    private URI probeScheme(URI httpFirst) {
        try {
            http.get(httpFirst, null);
            return httpFirst;
        } catch (IOException e) {
            URI https = ShortLinks.withHttps(httpFirst);
            try {
                http.get(https, null);
                return https;
            } catch (IOException ignored) {
                return httpFirst; // the error for the http address is reported when it is read
            }
        }
    }

    private record Outcome(WebPage page, URI needsSignIn, String why) {}

    /** Follows the link to its page, or says which address needs a sign-in. */
    private Outcome follow(URI first) throws SourceException {
        URI current = first;
        List<String> hops = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            if (!seen.add(current.toString())) {
                throw new SourceException(SourceException.Kind.NOT_FOUND, "The link leads in a circle: " + String.join(", ", hops));
            }
            BrowserLogin.Session window = BrowserLogin.transportFor(current);
            ShortLinks.Hop answer;
            String finalUrl = current.toString();
            if (window != null) {
                // The site signs you in in a way only the browser can (Windows login): read the page through the window.
                HttpSourceClient.Response r;
                try {
                    r = window.fetchInPage(current.toString(), BrowserLogin.originOf(current), Map.of());
                } catch (IOException e) {
                    throw new SourceException(SourceException.Kind.TRANSIENT, "The browser window could not load " + Redirects.show(current) + ": " + e.getMessage(), null, e);
                }
                if (r == null || BrowserLogin.needsSignIn(r)) {
                    return new Outcome(null, current, "the browser window is on the sign-in page");
                }
                answer = new ShortLinks.Hop(r.status(), null, r.body());
                finalUrl = r.finalUrl() == null ? finalUrl : r.finalUrl();
                if (answer.status() >= 200 && answer.status() < 300) {
                    return new Outcome(new WebPage(first.toString(), finalUrl, r.body(), r.contentType() == null ? "text/html" : r.contentType(), hops, null), null, null);
                }
            } else {
                String cookie = BrowserLogin.sessionFor(current.getHost());
                if (BrowserLogin.THROUGH_BROWSER.equals(cookie)) {
                    cookie = null;
                }
                try {
                    answer = http.get(current, cookie);
                } catch (IOException e) {
                    throw new SourceException(SourceException.Kind.TRANSIENT, "Could not reach " + Redirects.show(current) + " (" + e.getClass().getSimpleName() + ")", null, e);
                }
            }
            int s = answer.status();
            if (s >= 300 && s < 400 && answer.location() != null && !answer.location().isBlank()) {
                URI next = nextOrFail(current, answer.location());
                if (Redirects.isSignInAddress(next.toString()) || next.toString().startsWith("about:")) {
                    return new Outcome(null, current, "it sends visitors to " + Redirects.show(next));
                }
                hops.add(s + " to " + Redirects.show(next));
                current = next;
                continue;
            }
            if (s == 401) {
                return new Outcome(null, current, "HTTP 401");
            }
            String body = answer.body() == null ? "" : answer.body();
            if (s >= 200 && s < 300) {
                if (BrowserLogin.looksLikeLoginPage(body) || Redirects.isSignInAddress(current.toString())) {
                    return new Outcome(null, current, "it shows a sign-in page");
                }
                Matcher m = META_REFRESH.matcher(body.length() > 65536 ? body.substring(0, 65536) : body);
                if (m.find()) { // short-link services that forward with a page instead of a redirect
                    URI next = nextOrFail(current, m.group(1).replace("&amp;", "&"));
                    if (!next.equals(current)) {
                        hops.add("forward to " + Redirects.show(next));
                        current = next;
                        continue;
                    }
                }
                return new Outcome(new WebPage(first.toString(), finalUrl, body, "text/html", hops, null), null, null);
            }
            if (s == 415) {
                throw new SourceException(SourceException.Kind.NOT_FOUND, Redirects.show(current) + " is not a web page (" + body + "); only web pages are read");
            }
            if (s == 403) {
                throw new SourceException(SourceException.Kind.FORBIDDEN, "HTTP 403: you do not have access to " + Redirects.show(current));
            }
            if (s == 404 || s == 410) {
                throw new SourceException(s == 410 ? SourceException.Kind.GONE : SourceException.Kind.NOT_FOUND, "HTTP " + s + ": the page does not exist: " + Redirects.show(current)
                        + (hops.isEmpty() ? "" : " (after " + String.join(", ", hops) + ")"));
            }
            if (s == 429 || s >= 500) {
                throw new SourceException(s == 429 ? SourceException.Kind.RATE_LIMITED : SourceException.Kind.TRANSIENT, "HTTP " + s + " from " + Redirects.show(current));
            }
            throw new SourceException(SourceException.Kind.NOT_FOUND, "HTTP " + s + " from " + Redirects.show(current));
        }
        throw new SourceException(SourceException.Kind.NOT_FOUND, "More than " + MAX_HOPS + " redirects: " + String.join(", ", hops));
    }

    /** Whether the browser's cookies open the page for the app (the sign-in is finished). */
    private boolean accepted(URI site, String cookie) {
        URI current = site;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            ShortLinks.Hop answer;
            try {
                answer = http.get(current, current.getHost().equalsIgnoreCase(site.getHost()) ? cookie : BrowserLogin.sessionFor(current.getHost()));
            } catch (IOException e) {
                return false;
            }
            if (answer.status() >= 300 && answer.status() < 400 && answer.location() != null) {
                URI next;
                try {
                    next = next(current, answer.location());
                } catch (IllegalArgumentException e) {
                    return false;
                }
                if (Redirects.isSignInAddress(next.toString())) {
                    return false;
                }
                current = next;
                continue;
            }
            return answer.status() >= 200 && answer.status() < 300 && !BrowserLogin.looksLikeLoginPage(answer.body());
        }
        return false;
    }

    private static URI nextOrFail(URI current, String location) throws SourceException {
        try {
            return next(current, location);
        } catch (IllegalArgumentException e) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "The link cannot be followed: " + e.getMessage());
        }
    }

    private static URI next(URI current, String location) throws IllegalArgumentException {
        // Plain http is allowed: company short links and intranet pages often use it. Nothing secret is sent with
        // these requests except the cookies of a browser sign-in to that very site.
        URI next = current.resolve(location.strip().replace(" ", "%20"));
        if (next.getScheme() == null || !(next.getScheme().equalsIgnoreCase("http") || next.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("the link leads to '" + next.getScheme() + "', not a web page: " + Redirects.show(next));
        }
        return next;
    }

    static URI toUri(String text) {
        return ShortLinks.webAddress(text);
    }

    // ================================================================== HTTP

    private static Http httpFor(ShortLinks.Options net, Function<String, String> env) {
        Duration timeout = net.timeout().compareTo(Duration.ofSeconds(20)) < 0 ? Duration.ofSeconds(20) : net.timeout();
        HttpClient.Builder direct = HttpClient.newBuilder().connectTimeout(net.timeout()).followRedirects(HttpClient.Redirect.NEVER);
        HttpClient.Builder proxied = HttpClient.newBuilder().connectTimeout(net.timeout()).followRedirects(HttpClient.Redirect.NEVER);
        if (net.caCertFile() != null) {
            try {
                javax.net.ssl.SSLContext ctx = HttpSourceClient.sslContextFor(net.caCertFile());
                direct.sslContext(ctx);
                proxied.sslContext(ctx);
            } catch (RuntimeException ignored) {
                // the default trust store is used
            }
        }
        ProxyConfig proxy = net.proxy().orEnvironment(env);
        if (proxy.configured()) {
            proxied.proxy(proxy.selector());
            java.net.Authenticator auth = proxy.authenticator(env);
            if (auth != null) {
                proxied.authenticator(auth);
            }
        }
        HttpClient directClient = direct.build();
        HttpClient proxiedClient = proxy.configured() ? proxied.build() : directClient;
        return (uri, cookie) -> {
            boolean intranet = uri.getHost() != null && !uri.getHost().contains(".");
            HttpClient firstTry = intranet ? directClient : proxiedClient;
            try {
                return send(firstTry, uri, cookie, timeout);
            } catch (IOException e) {
                if (proxiedClient == directClient) {
                    throw e;
                }
                return send(firstTry == directClient ? proxiedClient : directClient, uri, cookie, timeout);
            }
        };
    }

    private static ShortLinks.Hop send(HttpClient client, URI uri, String cookie, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "Mozilla/5.0 (QuestionCrawler page reader)")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .GET();
        if (cookie != null && !cookie.isBlank()) {
            b.header("Cookie", cookie);
        }
        try {
            HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            String body;
            try (InputStream in = r.body()) {
                boolean gzip = r.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
                InputStream src = gzip ? new java.util.zip.GZIPInputStream(in) : in;
                byte[] bytes = src.readNBytes(MAX_BYTES);
                if (r.statusCode() >= 200 && r.statusCode() < 300 && !type.isEmpty() && !type.contains("html") && !type.contains("text") && !type.contains("xml")) {
                    return new ShortLinks.Hop(415, null, type.split(";")[0]);
                }
                java.nio.charset.Charset cs = StandardCharsets.UTF_8;
                Matcher m = Pattern.compile("charset=([\\w\\-]+)").matcher(type);
                if (m.find()) {
                    try {
                        cs = java.nio.charset.Charset.forName(m.group(1));
                    } catch (RuntimeException ignored) {
                        // UTF-8
                    }
                }
                body = new String(bytes, cs);
            }
            return new ShortLinks.Hop(r.statusCode(), r.headers().firstValue("Location").orElse(null), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
