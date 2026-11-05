package com.questioncrawler.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.service.ConnectionReport.Fact;
import com.questioncrawler.core.service.ConnectionReport.Need;
import com.questioncrawler.core.service.ConnectionReport.Requirement;
import com.questioncrawler.core.service.ConnectionReport.Route;
import com.questioncrawler.core.service.ConnectionReport.Stage;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.StackExchangeAdapter;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Looks at a site the way a careful colleague would before trying to collect from it: Can we reach it? Is the secure
 * connection trusted? Do we need to sign in? Is there a proper way (API / export) to get the data, or only web pages?
 * Are there limits or bot protection? Does the topic we want exist?
 *
 * <p>It only issues a handful of plain GET requests (the home page, robots.txt and a few conventional API-description
 * paths), never sends credentials to anything but the configured site, never follows redirects, and never tries to get
 * around a login, CAPTCHA or block: it reports them and says what is needed.
 */
public final class SiteDiagnostics {

    /** What to check. {@code target} is the API/base address to probe (for Stack Exchange: the API base). */
    public record Input(String sourceType, String target, String site, List<String> tags, String authMethod,
                        String tokenEnv, String keyEnv, Path caCertFile, boolean allowInsecureHttp, int timeoutSeconds,
                        ProxyConfig proxy) {
        public Input(String sourceType, String target, String site, List<String> tags, String authMethod,
                     String tokenEnv, String keyEnv, Path caCertFile, boolean allowInsecureHttp, int timeoutSeconds) {
            this(sourceType, target, site, tags, authMethod, tokenEnv, keyEnv, caCertFile, allowInsecureHttp, timeoutSeconds, ProxyConfig.NONE);
        }
    }

    private static final Pattern LOGIN_WORDS = Pattern.compile("login|signin|sign-in|sso|oauth|saml|adfs|okta|microsoftonline|auth0|accounts\\.|/auth", Pattern.CASE_INSENSITIVE);
    private static final List<String> API_DESCRIPTION_PATHS = List.of("/openapi.json", "/swagger.json", "/v3/api-docs", "/api-docs");
    private static final int MAX_BODY = 300_000;

    private record Probe(String url, int status, Map<String, List<String>> headers, String body, long millis, String error) {
        boolean ok() { return error == null && status >= 200 && status < 300; }
        String header(String name) {
            List<String> v = headers == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    private final Function<String, String> env;
    private final Clock clock;

    public SiteDiagnostics(Function<String, String> env, Clock clock) {
        this.env = env;
        this.clock = clock;
    }

    // ================================================================== entry point

    public ConnectionReport run(Input in) {
        String now = clock.instant().toString();
        if (in.sourceType().equals("fixture")) {
            return offlineDemo(now);
        }
        List<Stage> stages = new ArrayList<>();
        URI uri = parse(in.target());
        boolean https = uri != null && "https".equalsIgnoreCase(uri.getScheme());

        Stage network = networkStage(in, uri);
        stages.add(network);
        boolean reachable = network.status().equals("PASS");
        Stage secure = reachable ? secureStage(in, uri, https) : skipped("secure", "Secure connection", "Is the connection secure and trusted?", "Skipped because the site cannot be reached yet.");
        stages.add(secure);
        boolean secureOk = !secure.status().equals("FAIL") && reachable;

        Probe home = null;
        if (secureOk) {
            home = get(in, in.sourceType().equals("stackexchange") ? infoUrl(in) : in.target(), in.tokenEnv() != null && in.authMethod().equals("token"));
        }
        Stage access = secureOk ? accessStage(in, home) : skipped("access", "Sign-in", "Do we need to sign in?", "Skipped until the site can be reached over a trusted connection.");
        stages.add(access);

        DataFindings data = new DataFindings();
        Stage dataStage = secureOk ? dataStage(in, uri, home, data) : skipped("data", "Way to get the data", "Is there a proper way to get the data?", "Skipped until the site can be reached.");
        stages.add(dataStage);
        stages.add(secureOk ? limitsStage(in, home, data) : skipped("limits", "Limits and protection", "Are there limits or bot protection?", "Skipped until the site can be reached."));
        stages.add(scopeStage(in, secureOk, data));

        Route route = route(in, stages, data);
        return finish(now, in, stages, route, data);
    }

    // ================================================================== offline demo

    private ConnectionReport offlineDemo(String now) {
        List<Stage> stages = List.of(
                new Stage("network", "Reach the site", "Can this computer reach the site?", "PASS", "Nothing to reach: the demo data lives inside the app.", List.of(), List.of(), 0),
                new Stage("secure", "Secure connection", "Is the connection secure and trusted?", "SKIPPED", "No network connection is used.", List.of(), List.of(), 0),
                new Stage("access", "Sign-in", "Do we need to sign in?", "PASS", "No sign-in needed.", List.of(), List.of(), 0),
                new Stage("data", "Way to get the data", "Is there a proper way to get the data?", "PASS", "The demo data is built in and complete.", List.of(), List.of(), 0),
                new Stage("limits", "Limits", "Are there limits or protection?", "PASS", "No limits: everything is local.", List.of(), List.of(), 0),
                new Stage("scope", "Topic", "Does the topic exist?", "PASS", "The demo contains questions tagged orbit.", List.of(), List.of(), 0));
        Route route = new Route("OFFLINE_DEMO", "Built-in demo data", "Sample questions are packaged with the app so you can explore every feature without any account or internet connection.", true,
                List.of("Collect the demo data from the Collect page.", "Then explore it on the Explore page.", "When you are ready, switch the source to a real site on the Connect page."));
        List<Requirement> reqs = List.of(new Requirement("none", "Nothing needed", "MET", "The demo works offline."));
        return new ConnectionReport(now, "built-in demo data", "fixture", "READY", "The demo is ready to use",
                "Everything runs on this computer with sample data.", 100, stages, route, reqs);
    }

    // ================================================================== stage 1: network

    /** The proxy that applies to this request: settings first, then HTTPS_PROXY, otherwise none. */
    private ProxyConfig effectiveProxy(Input in, String host) {
        ProxyConfig p = (in.proxy() == null ? ProxyConfig.NONE : in.proxy()).orEnvironment(env);
        return p.appliesTo(host) ? p : ProxyConfig.NONE;
    }

    /** A connected plain socket to host:port, directly or through the proxy tunnel. */
    private java.net.Socket openSocket(Input in, String host, int port) throws IOException {
        int timeout = Math.max(1, in.timeoutSeconds()) * 1000;
        ProxyConfig proxy = effectiveProxy(in, host);
        if (proxy.configured()) {
            return proxy.tunnel(host, port, timeout, env);
        }
        java.net.Socket socket = new java.net.Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private Stage networkStage(Input in, URI uri) {
        long t0 = System.nanoTime();
        String title = "Reach the site";
        String q = "Can this computer reach the site?";
        if (uri == null || uri.getHost() == null) {
            return stage("network", title, q, "FAIL", "That is not a web address we can use.", List.of(),
                    List.of(new Need("A complete address starting with https://", "Without a valid address there is nothing to connect to.", "You")), t0);
        }
        List<Fact> facts = new ArrayList<>();
        int port = uri.getPort() > 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        facts.add(new Fact("Address", uri.getHost() + ":" + port));
        ProxyConfig px = effectiveProxy(in, uri.getHost());
        if (px.configured()) {
            return networkViaProxy(in, uri, port, px, facts, t0);
        }
        String proxy = proxyHint();
        if (proxy != null) {
            facts.add(new Fact("Proxy", proxy));
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            return stage("network", title, q, "FAIL", "The name '" + uri.getHost() + "' could not be found.", facts, List.of(
                    new Need("The correct host name", "A typo is the most common cause.", "You"),
                    new Need("A connection to the company network (VPN)", "Internal sites are usually invisible from outside the company network.", "You or your IT help desk"),
                    new Need("A web proxy address, if your company uses one", "With a proxy, name lookup is done by the proxy. Enter it under 'Web proxy address' and 'Web proxy port'.", "You or your IT help desk"),
                    new Need("Working DNS", "Your computer must be able to look up names on the network it is on.", "IT / network team")), t0);
        }
        facts.add(new Fact("Resolved to", addresses[0].getHostAddress() + (addresses.length > 1 ? " (+" + (addresses.length - 1) + " more)" : "")));
        long c0 = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(addresses[0], port), Math.max(1, in.timeoutSeconds()) * 1000);
            facts.add(new Fact("Connection time", (System.nanoTime() - c0) / 1_000_000 + " ms"));
        } catch (IOException e) {
            boolean timeout = e instanceof java.net.SocketTimeoutException;
            return stage("network", title, q, "FAIL", timeout
                    ? "The site did not answer in time. A firewall, a missing VPN or a required web proxy is the usual reason."
                    : "The site refused the direct connection. If your company uses a web proxy, this computer must connect through it.", facts, List.of(
                    new Need("The address of your company's web proxy (host and port)", "Direct connections to the internet are usually blocked; traffic must go through the proxy. Enter it under 'Web proxy address' and 'Web proxy port' (or set the HTTPS_PROXY environment variable).", "You or your IT help desk"),
                    new Need("Network access to " + uri.getHost() + " on port " + port, "If there is no proxy, a firewall or VPN is blocking the path from this computer.", "IT / network team")), t0);
        }
        return stage("network", title, q, "PASS", "The site is reachable from this computer.", facts, List.of(), t0);
    }

    private static String proxyHint() {
        String host = System.getProperty("https.proxyHost");
        if (host == null) {
            host = System.getProperty("http.proxyHost");
        }
        return host == null ? null : host + " (from Java settings)";
    }

    // ================================================================== stage 2: TLS

    private Stage networkViaProxy(Input in, URI uri, int port, ProxyConfig px, List<Fact> facts, long t0) {
        String title = "Reach the site";
        String q = "Can this computer reach the site?";
        facts.add(new Fact("Proxy", px.describe()));
        long c0 = System.nanoTime();
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        try {
            if (https) {
                // HTTPS passes a web proxy through a CONNECT tunnel; the proxy (not this computer) looks up the name.
                try (Socket tunnel = px.tunnel(uri.getHost(), port, Math.max(1, in.timeoutSeconds()) * 1000, env)) {
                    facts.add(new Fact("Tunnel through the proxy", (System.nanoTime() - c0) / 1_000_000 + " ms"));
                }
            } else {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(px.host(), px.port()), Math.max(1, in.timeoutSeconds()) * 1000);
                }
            }
        } catch (ProxyConfig.ProxyException e) {
            return stage("network", title, q, "FAIL", proxyRefusal(e, uri.getHost()), facts, proxyNeeds(e, uri.getHost()), t0);
        } catch (IOException e) {
            boolean timeout = e instanceof java.net.SocketTimeoutException;
            return stage("network", title, q, "FAIL", timeout
                    ? "The web proxy " + px.host() + ":" + px.port() + " did not answer in time."
                    : "We cannot reach your web proxy at " + px.host() + ":" + px.port() + ".", facts, List.of(
                    new Need("The correct proxy host name and port", "A typo, or an old proxy address, is the most common cause.", "You or your IT help desk"),
                    new Need("To be on the company network or VPN", "Proxies are usually only reachable from inside the company network.", "You or your IT help desk")), t0);
        }
        return stage("network", title, q, "PASS", "The site is reachable through your web proxy.", facts, List.of(), t0);
    }

    private static String proxyRefusal(ProxyConfig.ProxyException e, String host) {
        return switch (e.status()) {
            case 407 -> "Your web proxy wants you to sign in (HTTP 407) before it connects to " + host + ".";
            case 403 -> "Your web proxy refused to connect to " + host + " (HTTP 403): it blocks this site by policy.";
            case 502, 503, 504 -> "Your web proxy could not reach " + host + " (HTTP " + e.status() + ").";
            default -> "Your web proxy refused the connection to " + host + " (" + e.getMessage() + ").";
        };
    }

    private static List<Need> proxyNeeds(ProxyConfig.ProxyException e, String host) {
        if (e.status() == 407) {
            return List.of(new Need("Proxy sign-in details (a user name and password)", "Set the variable names under 'Proxy user variable name' and 'Proxy password variable name' (advanced settings) and set those variables in your terminal.", "You or your IT help desk"),
                    new Need("A local sign-in helper such as CNTLM, if your proxy uses Windows sign-in", "Java cannot use Windows sign-in (NTLM/Kerberos) with a proxy by itself.", "Your IT help desk"));
        }
        if (e.status() == 403) {
            return List.of(new Need(host + " allowed through the web proxy", "The proxy's rules (often a website category) block this address.", "IT / network security team"));
        }
        return List.of(new Need("A working route from the proxy to " + host, "The proxy accepted our request but could not complete it.", "IT / network team"));
    }

    private Stage secureStage(Input in, URI uri, boolean https) {
        long t0 = System.nanoTime();
        String title = "Secure connection";
        String q = "Is the connection secure and trusted?";
        if (!https) {
            return stage("secure", title, q, in.allowInsecureHttp() ? "WARN" : "FAIL",
                    in.allowInsecureHttp() ? "This address is not encrypted. Acceptable for local testing only."
                            : "This address is not encrypted (http). Collecting over http is blocked on purpose.",
                    List.of(new Fact("Protocol", "http")),
                    in.allowInsecureHttp() ? List.of() : List.of(new Need("An https:// address", "Passwords, tokens and content must not travel unencrypted.", "The site owners")), t0);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        List<Fact> facts = new ArrayList<>();
        try {
            SSLContext context = in.caCertFile() != null ? SSLContext.getInstance("TLS") : SSLContext.getDefault();
            if (in.caCertFile() != null) {
                context = trustWith(in.caCertFile());
            }
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(openSocket(in, uri.getHost(), port), uri.getHost(), port, true)) {
                socket.setSoTimeout(Math.max(1, in.timeoutSeconds()) * 1000);
                var params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                socket.startHandshake();
                var session = socket.getSession();
                facts.add(new Fact("Protocol", session.getProtocol()));
                if (session.getPeerCertificates().length > 0 && session.getPeerCertificates()[0] instanceof X509Certificate cert) {
                    describe(cert, facts);
                    long days = ChronoUnit.DAYS.between(clock.instant(), cert.getNotAfter().toInstant());
                    if (days < 14) {
                        return stage("secure", title, q, "WARN", "The connection is trusted but the site's certificate expires in " + Math.max(days, 0) + " day(s).", facts,
                                List.of(new Need("A renewed certificate", "When it expires, secure connections will start failing.", "The site owners")), t0);
                    }
                }
            }
            return stage("secure", title, q, "PASS", "The connection is encrypted and the site's identity is trusted.", facts, List.of(), t0);
        } catch (SSLException e) {
            return stage("secure", title, q, "FAIL", explainTlsFailure(e), tlsFactsOnFailure(uri, port, in, facts), tlsNeeds(e), t0);
        } catch (IOException | java.security.GeneralSecurityException e) {
            return stage("secure", title, q, "FAIL", "The secure handshake could not be completed (" + e.getClass().getSimpleName() + ").", facts,
                    List.of(new Need("A working secure (https) endpoint", "The site did not complete a normal secure handshake.", "The site owners")), t0);
        }
    }

    /** Plain-language reason for a TLS failure. Public so it can be tested without a real server. */
    public static String explainTlsFailure(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("PKIX") || m.contains("unable to find valid certification path") || m.contains("trustAnchors")) {
            return "The site's security certificate was issued by an authority this computer does not trust. This is typical for company-internal sites.";
        }
        if (m.contains("No subject alternative") || m.contains("No name matching") || m.toLowerCase(Locale.ROOT).contains("hostname")) {
            return "The certificate belongs to a different site name than the one you entered.";
        }
        if (m.contains("expired") || m.contains("NotAfter")) {
            return "The site's certificate has expired.";
        }
        if (m.contains("handshake_failure") || m.contains("protocol_version")) {
            return "The site and this computer could not agree on a secure protocol.";
        }
        return "The secure handshake failed: " + (m.length() > 140 ? m.substring(0, 140) + "…" : m);
    }

    private static List<Need> tlsNeeds(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("PKIX") || m.contains("unable to find valid certification path") || m.contains("trustAnchors")) {
            return List.of(new Need("The company's root certificate (a .pem or .crt file)", "It lets this computer trust the site. Web proxies that inspect secure traffic present the company's own certificate, so this is common behind a proxy. Then set 'Company certificate file' in Settings.", "IT / security team"));
        }
        if (m.contains("No subject alternative") || m.contains("No name matching")) {
            return List.of(new Need("The address the certificate was issued for", "Use the exact host name from the certificate.", "The site owners"));
        }
        return List.of(new Need("A valid, trusted certificate on the site", "We never switch certificate checks off.", "The site owners"));
    }

    /** On failure, peek at the certificate with a probe-only connection (no data is exchanged) so we can say who issued it. */
    private List<Fact> tlsFactsOnFailure(URI uri, int port, Input in, List<Fact> facts) {
        List<Fact> out = new ArrayList<>(facts);
        try {
            SSLContext probe = SSLContext.getInstance("TLS");
            probe.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            try (SSLSocket socket = (SSLSocket) probe.getSocketFactory().createSocket(openSocket(in, uri.getHost(), port), uri.getHost(), port, true)) {
                socket.startHandshake();
                if (socket.getSession().getPeerCertificates()[0] instanceof X509Certificate cert) {
                    describe(cert, out);
                }
            }
        } catch (Exception ignored) {
            // diagnosis only: nothing to add
        }
        return out;
    }

    private static void describe(X509Certificate cert, List<Fact> facts) {
        facts.add(new Fact("Certificate for", cert.getSubjectX500Principal().getName()));
        facts.add(new Fact("Issued by", cert.getIssuerX500Principal().getName()));
        facts.add(new Fact("Valid until", cert.getNotAfter().toInstant().toString().substring(0, 10)));
    }

    private static SSLContext trustWith(Path caFile) throws java.security.GeneralSecurityException, IOException {
        java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        javax.net.ssl.TrustManagerFactory defaults = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((java.security.KeyStore) null);
        int i = 0;
        for (TrustManager tm : defaults.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                for (X509Certificate c : x.getAcceptedIssuers()) {
                    ks.setCertificateEntry("d" + i++, c);
                }
            }
        }
        try (var is = Files.newInputStream(caFile)) {
            for (var c : CertificateFactory.getInstance("X.509").generateCertificates(is)) {
                ks.setCertificateEntry("o" + i++, c);
            }
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    // ================================================================== stage 3: sign-in / permission

    private Stage accessStage(Input in, Probe home) {
        long t0 = System.nanoTime();
        String title = "Sign-in";
        String q = "Do we need to sign in?";
        List<Fact> facts = new ArrayList<>();
        if (home == null || home.error() != null) {
            return stage("access", title, q, "FAIL", "The site did not answer our request" + (home == null ? "." : ": " + home.error()), facts,
                    List.of(new Need("A site that answers requests", "We could connect but received no valid reply.", "The site owners")), t0);
        }
        facts.add(new Fact("Reply", "HTTP " + home.status()));
        boolean tokenConfigured = in.authMethod() != null && in.authMethod().equals("token");
        if (tokenConfigured) {
            boolean has = in.tokenEnv() != null && env.apply(in.tokenEnv()) != null && !env.apply(in.tokenEnv()).isBlank();
            facts.add(new Fact("Token variable " + in.tokenEnv(), has ? "set (value not shown)" : "NOT set"));
        }
        int s = home.status();
        if (s >= 200 && s < 300) {
            boolean loginForm = home.body() != null && home.body().toLowerCase(Locale.ROOT).matches("(?s).*type\\s*=\\s*[\"']password[\"'].*");
            if (loginForm) {
                return stage("access", title, q, "WARN", "The address shows a sign-in form. Content is probably only visible after logging in.", facts,
                        List.of(new Need("An approved way to sign in without a browser (an access token or service account)", "We never type passwords into forms or bypass MFA.", "The site owners / platform team")), t0);
            }
            return stage("access", title, q, "PASS", in.sourceType().equals("stackexchange")
                    ? "No sign-in is needed. Anyone may read public questions through the API."
                    : "The address answers without signing in.", facts, List.of(), t0);
        }
        if (s >= 300 && s < 400) {
            String loc = home.header("location");
            facts.add(new Fact("Redirects to", loc == null ? "unknown" : hostAndPath(loc)));
            if (loc != null && LOGIN_WORDS.matcher(loc).find()) {
                return stage("access", title, q, "WARN", "The site sends visitors to a sign-in page. Reading content requires an approved login.", facts,
                        List.of(new Need("An access token or service account approved for reading Q&A", "The redirect means anonymous access is not allowed. We will not bypass it.", "The site owners / platform team"),
                                new Need("To know which sign-in system is used (for example SSO)", "It decides which kind of token you should request.", "The site owners")), t0);
            }
            return stage("access", title, q, "INFO", "The address redirects elsewhere; redirects are not followed automatically for safety.", facts,
                    List.of(new Need("The final address of the API or site", "Use the address after the redirect in Settings.", "The site owners")), t0);
        }
        if (s == 401) {
            String www = home.header("www-authenticate");
            if (www != null) {
                facts.add(new Fact("Sign-in method requested", www.length() > 80 ? www.substring(0, 80) + "…" : www));
            }
            return stage("access", title, q, "WARN", tokenConfigured ? "The site rejected our credentials (401)." : "The site requires credentials (401).", facts,
                    List.of(new Need(tokenConfigured ? "A valid, unexpired token" : "An access token", "Ask for read-only access to questions and answers.", "The site owners / platform team")), t0);
        }
        if (s == 403) {
            return stage("access", title, q, "WARN", "The site understood us but refuses access (403). Our account or network may lack permission.", facts,
                    List.of(new Need("Permission to read questions and answers", "Access is denied for this identity or network.", "The site owners / platform team")), t0);
        }
        if (s == 404) {
            return stage("access", title, q, "WARN", "Nothing was found at this exact address (404). The host works but the path may be wrong.", facts,
                    List.of(new Need("The correct API address", "Check the address with the site's documentation.", "The site owners")), t0);
        }
        return stage("access", title, q, "WARN", "The site replied with an unexpected status (HTTP " + s + ").", facts, List.of(), t0);
    }

    // ================================================================== stage 4: way to get the data

    private static final class DataFindings {
        boolean officialApi;
        boolean apiDescription;
        boolean jsApp;
        boolean robotsBlocksAll;
        boolean robotsFound;
        boolean pagesReadable;
        boolean botProtection;
        boolean rateLimitSeen;
        boolean loginWall;
        String rateInfo;
        String quotaInfo;
        long tagQuestions = Long.MIN_VALUE;
        boolean tagChecked;
        String tagError;
        List<String> candidateEndpoints = new ArrayList<>();
    }

    private Stage dataStage(Input in, URI uri, Probe home, DataFindings d) {
        long t0 = System.nanoTime();
        String title = "Way to get the data";
        String q = "Is there a proper way to get the data?";
        List<Fact> facts = new ArrayList<>();

        if (in.sourceType().equals("stackexchange")) {
            try {
                StackExchangeAdapter adapter = stackExchange(in);
                String ping = adapter.ping();
                d.officialApi = true;
                d.quotaInfo = adapter.quotaRemaining() >= 0 ? adapter.quotaRemaining() + " requests left today" : null;
                facts.add(new Fact("Official API", "Stack Exchange API 2.3"));
                facts.add(new Fact("Site", in.site()));
                if (d.quotaInfo != null) {
                    facts.add(new Fact("Requests left today", String.valueOf(adapter.quotaRemaining())));
                }
                if (!in.tags().isEmpty()) {
                    try {
                        d.tagChecked = true;
                        d.tagQuestions = adapter.tagCount(in.tags().get(0));
                    } catch (SourceException e) {
                        d.tagError = e.getMessage();
                    }
                }
                return stage("data", title, q, "PASS", "Yes: this site has an official, documented API that this app supports.", facts, List.of(), t0);
            } catch (SourceException e) {
                return stage("data", title, q, e.kind() == SourceException.Kind.QUOTA_EXHAUSTED ? "WARN" : "FAIL", friendly(e), facts,
                        e.kind() == SourceException.Kind.QUOTA_EXHAUSTED
                                ? List.of(new Need("A free Stack Exchange application key", "It raises the daily limit from about 300 to 10,000 requests.", "You (register at stackapps.com)"))
                                : List.of(new Need("A reachable Stack Exchange API", "The documented API did not answer as expected.", "Try again later")), t0);
            }
        }

        // Generic site: look for a machine-readable description, robots rules and what the home page is made of.
        String origin = origin(uri);
        for (String path : API_DESCRIPTION_PATHS) {
            Probe p = get(in, origin + path, false);
            if (p.ok() && p.body() != null && (p.body().contains("\"openapi\"") || p.body().contains("\"swagger\""))) {
                d.apiDescription = true;
                facts.add(new Fact("API description found", origin + path));
                try {
                    JsonNode root = Json.MAPPER.readTree(p.body());
                    JsonNode info = root.path("info");
                    if (info.has("title")) {
                        facts.add(new Fact("API name", info.path("title").asText()));
                    }
                    JsonNode paths = root.path("paths");
                    facts.add(new Fact("Endpoints described", String.valueOf(paths.size())));
                    paths.fieldNames().forEachRemaining(name -> {
                        if (name.matches("(?i).*(question|answer|post|thread|tag|topic).*") && d.candidateEndpoints.size() < 8) {
                            d.candidateEndpoints.add(name);
                        }
                    });
                    if (!d.candidateEndpoints.isEmpty()) {
                        facts.add(new Fact("Likely relevant endpoints", String.join(", ", d.candidateEndpoints)));
                    }
                } catch (IOException ignored) {
                    // description exists but is not parseable here
                }
                break;
            }
        }
        Probe robots = get(in, origin + "/robots.txt", false);
        if (robots.ok() && robots.body() != null) {
            d.robotsFound = true;
            d.robotsBlocksAll = robotsDisallowsEverything(robots.body());
            facts.add(new Fact("robots.txt", d.robotsBlocksAll ? "asks automated tools to stay away" : "found, does not block everything"));
        } else {
            facts.add(new Fact("robots.txt", "not found"));
        }
        if (home != null && home.ok() && home.body() != null) {
            String body = home.body().toLowerCase(Locale.ROOT);
            String text = body.replaceAll("(?s)<script.*?</script>", " ").replaceAll("(?s)<style.*?</style>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            d.jsApp = text.length() < 400 && (body.contains("id=\"root\"") || body.contains("id=\"app\"") || body.contains("__next") || body.contains("ng-app")
                    || body.contains("enable javascript"));
            d.pagesReadable = !d.jsApp && home.header("content-type") != null && home.header("content-type").contains("html");
            facts.add(new Fact("Home page", d.jsApp ? "built by JavaScript: little readable text" : home.header("content-type") == null ? "unknown type" : home.header("content-type").split(";")[0]));
        }

        if (d.apiDescription) {
            return stage("data", title, q, "PASS", "Yes: the site publishes a description of its API, which is the best way to collect data.", facts,
                    List.of(new Need("Confirmation of which endpoints return questions and answers", "We need to wire the connector to the exact endpoints, never guessing them.", "The site owners / API documentation")), t0);
        }
        if (d.robotsBlocksAll) {
            return stage("data", title, q, "WARN", "No API description was found, and the site asks automated tools not to crawl its pages.", facts, List.of(
                    new Need("API documentation or a data export", "Collecting pages against the site's stated rules is not appropriate.", "The site owners"),
                    new Need("Written permission for automated access", "Only if the owners approve crawling.", "The site owners")), t0);
        }
        if (d.jsApp) {
            return stage("data", title, q, "WARN", "The pages are built in the browser by JavaScript, so reading the HTML would return almost nothing. A real API or export is needed.", facts, List.of(
                    new Need("API documentation or sample responses", "Behind JavaScript pages there is almost always an API the owners can describe.", "The site owners"),
                    new Need("An export of the questions and answers (JSON/CSV)", "A one-off export can be loaded without any collection.", "The site owners")), t0);
        }
        return stage("data", title, q, "WARN", "No API description was found. The pages are readable, but collecting from pages is fragile and needs the owners' approval.", facts, List.of(
                new Need("API documentation or sample responses", "This is what lets us build a reliable connector without guessing.", "The site owners"),
                new Need("Permission to collect from pages (if no API exists)", "Page layouts change; collecting without permission can break rules.", "The site owners")), t0);
    }

    static boolean robotsDisallowsEverything(String robots) {
        boolean inAll = false;
        for (String raw : robots.split("\\R")) {
            String line = raw.split("#", 2)[0].strip();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                inAll = lower.substring(11).strip().equals("*");
            } else if (inAll && lower.startsWith("disallow:") && lower.substring(9).strip().equals("/")) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== stage 5: limits

    private Stage limitsStage(Input in, Probe home, DataFindings d) {
        long t0 = System.nanoTime();
        String title = "Limits and protection";
        String q = "Are there limits or bot protection?";
        List<Fact> facts = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        String status = "PASS";
        String summary = "No rate limits or bot protection were detected.";
        if (home != null && home.headers() != null) {
            for (String h : List.of("x-ratelimit-limit", "x-ratelimit-remaining", "ratelimit-limit", "ratelimit-remaining", "retry-after")) {
                if (home.header(h) != null) {
                    facts.add(new Fact(h, home.header(h)));
                    d.rateLimitSeen = true;
                }
            }
            String server = home.header("server");
            boolean cf = home.header("cf-ray") != null || (server != null && server.toLowerCase(Locale.ROOT).contains("cloudflare"));
            boolean challenge = home.body() != null && home.body().toLowerCase(Locale.ROOT).matches("(?s).*(captcha|just a moment|attention required|are you a robot).*");
            if (cf) {
                facts.add(new Fact("Protection layer", "Cloudflare detected"));
            }
            if (challenge || (cf && (home.status() == 403 || home.status() == 503))) {
                d.botProtection = true;
            }
        }
        if (in.sourceType().equals("stackexchange")) {
            if (d.quotaInfo != null) {
                facts.add(new Fact("Daily request allowance", d.quotaInfo));
            }
            boolean hasKey = in.keyEnv() != null && !in.keyEnv().isBlank() && env.apply(in.keyEnv()) != null && !env.apply(in.keyEnv()).isBlank();
            facts.add(new Fact("Application key", hasKey ? "in use (higher limit)" : "not set (about 300 requests a day)"));
            if (!hasKey) {
                status = "INFO";
                summary = "Anonymous use is allowed with a small daily allowance. Fine for trying things out.";
                needs.add(new Need("A free application key (optional)", "Raises the limit to about 10,000 requests a day for bigger collections.", "You (stackapps.com)"));
            }
        }
        if (d.rateLimitSeen && !in.sourceType().equals("stackexchange")) {
            status = "INFO";
            summary = "The site reports request limits. The app slows down and retries automatically.";
        }
        if (d.botProtection) {
            status = "FAIL";
            summary = "The site is protecting itself against automated traffic (CAPTCHA or a challenge page).";
            needs.add(new Need("An allow-listed, approved route for automated access (API key, service account or IP allow-list)", "We never solve CAPTCHAs or evade protection.", "The site owners / security team"));
        }
        return stage("limits", title, q, status, summary, facts, needs, t0);
    }

    // ================================================================== stage 6: scope

    private Stage scopeStage(Input in, boolean reachable, DataFindings d) {
        long t0 = System.nanoTime();
        String title = "Your topic";
        String q = "Does the topic you want exist?";
        if (in.tags().isEmpty()) {
            return stage("scope", title, q, "WARN", "No tags are chosen yet, so we do not know what to collect.", List.of(),
                    List.of(new Need("One or more tags", "Tags are the labels that group questions, such as artificial-intelligence.", "You (Settings, or the wizard)")), t0);
        }
        List<Fact> facts = List.of(new Fact("Tags", String.join(", ", in.tags())));
        if (!reachable) {
            return skipped("scope", title, q, "Will be checked once the site can be reached.");
        }
        if (!in.sourceType().equals("stackexchange")) {
            return stage("scope", title, q, "SKIPPED", "Checking that a tag exists needs the site's API, which is not connected yet.", facts, List.of(), t0);
        }
        if (d.tagChecked && d.tagQuestions >= 0) {
            return stage("scope", title, q, "PASS", "The tag '" + in.tags().get(0) + "' exists and has " + String.format("%,d", d.tagQuestions) + " question(s) on the site.",
                    List.of(new Fact("Tag", in.tags().get(0)), new Fact("Questions with this tag (all time)", String.format("%,d", d.tagQuestions))), List.of(), t0);
        }
        if (d.tagChecked) {
            return stage("scope", title, q, "WARN", "The site has no tag named '" + in.tags().get(0) + "'.", facts,
                    List.of(new Need("The exact tag name used on the site", "Tags use lowercase words joined by hyphens, for example artificial-intelligence.", "You")), t0);
        }
        return stage("scope", title, q, "INFO", "Could not check the tag" + (d.tagError == null ? "." : ": " + d.tagError), facts, List.of(), t0);
    }

    // ================================================================== route, verdict, requirements

    private Route route(Input in, List<Stage> stages, DataFindings d) {
        Stage network = byId(stages, "network");
        Stage secure = byId(stages, "secure");
        Stage access = byId(stages, "access");
        if (network.status().equals("FAIL") || secure.status().equals("FAIL")) {
            return new Route("NONE", "No route yet: the site cannot be reached safely", "Before anything else, the connection itself has to work.", false,
                    List.of("Open the red step in the map and follow what is needed.", "Run the check again afterwards."));
        }
        if (in.sourceType().equals("stackexchange") && d.officialApi) {
            return new Route("OFFICIAL_API", "Official API (recommended)", "This site publishes a documented API. It is the proper, stable, permitted way to collect questions and answers.", true,
                    List.of("Save these settings.", "Open Collect and start with a small time window.", "Keep the source links: the content is licensed CC BY-SA and requires attribution."));
        }
        if (d.botProtection) {
            return new Route("DISCOURAGED", "Blocked by bot protection", "The site actively refuses automated traffic. Asking the owners for an approved route is the only appropriate way forward.", false,
                    List.of("Use the request template below to ask the site owners for API access or an export."));
        }
        if (d.apiDescription) {
            return new Route("API_FOUND", "An API exists, the connector still needs to be built", "The site describes its own API. With the exact endpoints confirmed, the internal connector can be completed.", false,
                    List.of("Send the request template below to the site owners to confirm endpoints and get a token.", "Share the API description with the developer finishing the connector."));
        }
        boolean signIn = access.status().equals("WARN") && (access.summary().contains("sign") || access.summary().contains("credentials") || access.summary().contains("401"));
        if (signIn) {
            return new Route("SIGN_IN_REQUIRED", "Sign-in required: an approved token is needed", "Content sits behind a login. The proper path is a read-only token or service account issued by the site owners.", false,
                    List.of("Request read-only API access (template below).", "Store the token in an environment variable, never in a file.", "Run the check again with the token set."));
        }
        if (d.robotsBlocksAll) {
            return new Route("DISCOURAGED", "Pages are off-limits to automated tools", "robots.txt asks automated tools to stay away. Respect it and ask for an API or export instead.", false,
                    List.of("Use the request template below."));
        }
        if (d.jsApp) {
            return new Route("WEB_PAGES_SCRIPTED", "Only script-built pages: an API or export is needed", "Reading the pages directly would return almost nothing because the content is created in the browser.", false,
                    List.of("Ask the owners for API documentation or an export (template below)."));
        }
        return new Route("WEB_PAGES", "Readable pages, but no API found", "The pages can be read, yet collecting from pages is fragile and needs the owners' approval. An API or export is far better.", false,
                List.of("Ask the owners for API documentation or sample responses (template below).", "If they only offer pages, they must approve collection and a reader must be built against real examples."));
    }

    private ConnectionReport finish(String now, Input in, List<Stage> stages, Route route, DataFindings d) {
        long weights = 0;
        long earned = 0;
        for (Stage s : stages) {
            int w = switch (s.id()) {
                case "network", "secure" -> 25;
                case "access" -> 20;
                case "data" -> 20;
                case "limits" -> 5;
                default -> 5;
            };
            if (s.status().equals("SKIPPED")) {
                continue;
            }
            weights += w;
            earned += switch (s.status()) {
                case "PASS" -> w;
                case "INFO" -> w * 9 / 10;
                case "WARN" -> w / 2;
                default -> 0;
            };
        }
        int score = weights == 0 ? 0 : (int) Math.round(100.0 * earned / weights);
        boolean blocked = stages.stream().anyMatch(s -> (s.id().equals("network") || s.id().equals("secure")) && s.status().equals("FAIL"));
        boolean anyFail = stages.stream().anyMatch(s -> s.status().equals("FAIL"));
        boolean anyWarn = stages.stream().anyMatch(s -> s.status().equals("WARN"));
        String verdict;
        String headline;
        String detail;
        if (blocked) {
            verdict = "BLOCKED";
            headline = "We cannot reach this site yet";
            detail = "Something between this computer and the site is in the way. The map shows exactly where, and what is needed to open it.";
        } else if (route.supported() && !anyFail) {
            verdict = anyWarn ? "NEEDS_ATTENTION" : "READY";
            headline = anyWarn ? "Almost ready: a few things deserve a look" : "This site is ready to collect from";
            detail = "There is a supported, proper way to get the data. " + (anyWarn ? "Review the amber steps first." : "Save your settings and start collecting.");
        } else if (!route.supported() && !route.kind().equals("NONE")) {
            verdict = "NOT_SUPPORTED_YET";
            headline = "The site is reachable, but we cannot collect from it yet";
            detail = "We can reach it, but there is no supported way to read its content yet. Here is what is needed and who to ask.";
        } else {
            verdict = "NEEDS_ATTENTION";
            headline = "Some steps need attention";
            detail = "Open the highlighted steps to see what to do.";
        }
        return new ConnectionReport(now, in.target(), in.sourceType(), verdict, headline, detail, score, stages, route, requirements(in, stages, d));
    }

    private List<Requirement> requirements(Input in, List<Stage> stages, DataFindings d) {
        List<Requirement> r = new ArrayList<>();
        r.add(req("network", "A network path to the site", byId(stages, "network"), "Your computer must be able to reach the host (VPN, proxy, firewall rules)."));
        r.add(req("secure", "A trusted secure connection", byId(stages, "secure"), "The site's certificate must be trusted by this computer."));
        Stage access = byId(stages, "access");
        r.add(new Requirement("access", "Permission to read questions and answers", access.status().equals("PASS") ? "MET" : access.status().equals("SKIPPED") ? "UNKNOWN" : "MISSING",
                access.status().equals("PASS") ? "No sign-in is required for what we read." : "An approved read-only token or service account may be needed."));
        Stage data = byId(stages, "data");
        r.add(new Requirement("data", "A proper way to get the data (API or export)", data.status().equals("PASS") ? "MET" : data.status().equals("SKIPPED") ? "UNKNOWN" : "MISSING",
                data.status().equals("PASS") ? "An API is available and supported." : "Ask the site owners for API documentation, sample responses, or a data export."));
        Stage limits = byId(stages, "limits");
        r.add(new Requirement("limits", "Room within request limits", limits.status().equals("FAIL") ? "MISSING" : limits.status().equals("SKIPPED") ? "UNKNOWN" : "MET",
                limits.status().equals("FAIL") ? "Bot protection blocks automated requests." : "The app slows down and retries automatically."));
        r.add(new Requirement("topic", "Tags to collect", in.tags().isEmpty() ? "MISSING" : "MET",
                in.tags().isEmpty() ? "Choose at least one tag." : "Collecting: " + String.join(", ", in.tags())));
        r.add(new Requirement("terms", "Permission to store and use the content", "UNKNOWN",
                in.sourceType().equals("stackexchange") ? "Stack Overflow content is licensed CC BY-SA: keep the links to the original posts. Check the Stack Exchange API terms for anything beyond small, local use."
                        : "Confirm with your organization that collecting this content for internal search is allowed."));
        return r;
    }

    private Requirement req(String id, String label, Stage s, String detail) {
        return new Requirement(id, label, s.status().equals("PASS") || s.status().equals("INFO") || s.status().equals("WARN") && id.equals("secure") && s.summary().contains("not encrypted") ? "MET"
                : s.status().equals("SKIPPED") ? "UNKNOWN" : "MISSING", detail);
    }

    // ================================================================== HTTP helpers

    private Probe get(Input in, String url, boolean withToken) {
        long t0 = System.nanoTime();
        try {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, in.timeoutSeconds()))).followRedirects(HttpClient.Redirect.NEVER);
            ProxyConfig proxy = effectiveProxy(in, URI.create(url).getHost());
            if (proxy.configured()) {
                b.proxy(proxy.selector());
                java.net.Authenticator auth = proxy.authenticator(env);
                if (auth != null) {
                    b.authenticator(auth);
                }
            }
            if (in.caCertFile() != null) {
                b.sslContext(trustWith(in.caCertFile()));
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(1, in.timeoutSeconds())))
                    .header("User-Agent", "QuestionCrawler-diagnostics/0.1").header("Accept", "application/json, text/html;q=0.8, */*;q=0.5").header("Accept-Encoding", "gzip").GET();
            if (withToken && in.tokenEnv() != null && env.apply(in.tokenEnv()) != null && !env.apply(in.tokenEnv()).isBlank()) {
                req.header("Authorization", "Bearer " + env.apply(in.tokenEnv()));
            }
            HttpResponse<java.io.InputStream> r = b.build().send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            boolean gz = r.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
            try (var raw = r.body(); var is = gz ? new java.util.zip.GZIPInputStream(raw) : raw) {
                byte[] bytes = is.readNBytes(MAX_BODY);
                Map<String, List<String>> headers = new java.util.HashMap<>();
                r.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
                return new Probe(url, r.statusCode(), headers, new String(bytes, StandardCharsets.UTF_8), (System.nanoTime() - t0) / 1_000_000, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Probe(url, -1, null, null, 0, "interrupted");
        } catch (Exception e) {
            return new Probe(url, -1, null, null, (System.nanoTime() - t0) / 1_000_000, e.getClass().getSimpleName());
        }
    }

    private StackExchangeAdapter stackExchange(Input in) {
        SourceSettings s = new SourceSettings(in.site(), in.target(), List.of(URI.create(in.target()).getHost()), List.of(URI.create(in.target()).getPath() + "/"),
                "none", null, null, null, in.caCertFile(), Duration.ofSeconds(Math.max(1, in.timeoutSeconds())), in.allowInsecureHttp(), in.site(), in.keyEnv(),
                in.proxy());
        return new StackExchangeAdapter(s, new HttpSourceClient(s, env), env, d -> { });
    }

    private String infoUrl(Input in) {
        return in.target() + "/info?site=" + in.site();
    }

    private static String friendly(SourceException e) {
        return switch (e.kind()) {
            case QUOTA_EXHAUSTED -> "The free request allowance for today is used up. It resets by itself, or a free application key raises it.";
            case RATE_LIMITED -> "The site asked us to slow down right now. Try again in a moment.";
            case TRANSIENT -> "The site did not answer properly (a temporary problem).";
            case AUTH_FAILED -> "The site rejected our credentials.";
            default -> e.getMessage();
        };
    }

    // ================================================================== small utilities

    private static URI parse(String s) {
        try {
            return s == null || s.isBlank() ? null : URI.create(s.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private static String hostAndPath(String url) {
        try {
            URI u = URI.create(url);
            return (u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
        } catch (IllegalArgumentException e) {
            return "(unreadable address)";
        }
    }

    private static Stage byId(List<Stage> stages, String id) {
        return stages.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static Stage stage(String id, String title, String question, String status, String summary, List<Fact> facts, List<Need> needs, long startNanos) {
        return new Stage(id, title, question, status, summary, List.copyOf(facts), List.copyOf(needs), (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static Stage skipped(String id, String title, String question, String summary) {
        return new Stage(id, title, question, "SKIPPED", summary, List.of(), List.of(), 0);
    }
}
