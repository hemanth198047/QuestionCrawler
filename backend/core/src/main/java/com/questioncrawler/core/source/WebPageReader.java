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

    /** Reads a page through the one browser window (company login included). Replaceable in tests. */
    @FunctionalInterface
    public interface Browser {
        WebPage read(URI url, String why) throws SourceException;
    }

    private static final int MAX_HOPS = 10;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Pattern META_REFRESH = Pattern.compile(
            "(?is)<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"']\\s*\\d*\\s*;\\s*url\\s*=\\s*'?([^\"'>\\s]+)");

    private final Http http;
    /** null: pages that need the company login (or that only the browser can reach) are skipped with the reason. */
    private final Browser browser;
    private final Runnable onNewRun;

    public WebPageReader(Http http, Browser browser) {
        this(http, browser, () -> { });
    }

    public WebPageReader(Http http, Browser browser, Runnable onNewRun) {
        this.http = http;
        this.browser = browser;
        this.onNewRun = onNewRun == null ? () -> { } : onNewRun;
    }

    /**
     * The reader used by the app: a plain request first (fast); a page that needs the company login, or that only the
     * browser can reach, is read through the one shared browser window.
     */
    public static WebPageReader create(ShortLinks.Options net, ConfluenceOptions.Browser browserOptions, boolean useBrowser, Function<String, String> env) {
        Http http = httpFor(net, env);
        if (!useBrowser) {
            return new WebPageReader(http, null);
        }
        BrowserPageReader window = BrowserPageReader.shared(browserOptions, env);
        return new WebPageReader(http, window::read, window::newRun);
    }

    /** A new collection or check starts (a browser window you closed may be used again). */
    public void newRun() {
        onNewRun.run();
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
        String why;
        try {
            Outcome o = follow(first);
            if (o.page() != null) {
                return new WebPage(link, o.page().finalUrl(), o.page().html(), o.page().contentType(), o.page().hops(), null);
            }
            why = Redirects.show(o.needsSignIn()) + " needs the company sign-in (" + o.why() + ")";
        } catch (SourceException e) {
            String m = String.valueOf(e.getMessage());
            if (e.kind() == SourceException.Kind.GONE || m.startsWith("HTTP 404") || m.contains("is not a web page") || m.startsWith("The link leads in a circle")) {
                throw e; // the browser would not do better
            }
            why = m; // for example HTTP 503 from the web proxy for an internal site, or a site only the browser reaches
        }
        if (browser == null) {
            throw new SourceException(SourceException.Kind.FORBIDDEN, "Not read: " + why
                    + ". Choose 'Read through my browser' under Signing in to read pages like this.");
        }
        WebPage viaBrowser = browser.read(first, why);
        return new WebPage(link, viaBrowser.finalUrl(), viaBrowser.html(), viaBrowser.contentType(), viaBrowser.hops(), viaBrowser.signIn());
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
        // Per site: the way that reached it (direct or through the web proxy), so each site is tried both ways once only.
        Map<String, Boolean> directWorks = new ConcurrentHashMap<>();
        return (uri, cookie) -> {
            if (proxiedClient == directClient) {
                return send(directClient, uri, cookie, timeout);
            }
            String site = String.valueOf(uri.getRawAuthority()).toLowerCase(Locale.ROOT);
            Boolean known = directWorks.get(site);
            boolean intranet = uri.getHost() != null && !uri.getHost().contains(".");
            boolean directFirst = known != null ? known : intranet;
            HttpClient firstTry = directFirst ? directClient : proxiedClient;
            HttpClient other = directFirst ? proxiedClient : directClient;
            ShortLinks.Hop answer;
            try {
                answer = send(firstTry, uri, cookie, timeout);
            } catch (IOException e) {
                if (known != null) {
                    throw e;
                }
                ShortLinks.Hop retry = send(other, uri, cookie, timeout);
                directWorks.put(site, !directFirst);
                return retry;
            }
            // A web proxy answers 502/503/504 for internal sites it cannot reach (the browser goes there directly).
            if (known == null && !directFirst && (answer.status() == 502 || answer.status() == 503 || answer.status() == 504)) {
                try {
                    ShortLinks.Hop retry = send(directClient, uri, cookie, timeout);
                    directWorks.put(site, true);
                    return retry;
                } catch (IOException ignored) {
                    // keep the proxy's answer
                }
            }
            directWorks.putIfAbsent(site, directFirst);
            return answer;
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
