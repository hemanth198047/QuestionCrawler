package com.questioncrawler.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.service.ConnectionReport.Fact;
import com.questioncrawler.core.service.ConnectionReport.Need;
import com.questioncrawler.core.service.ConnectionReport.Requirement;
import com.questioncrawler.core.service.ConnectionReport.Route;
import com.questioncrawler.core.service.ConnectionReport.Stage;
import com.questioncrawler.core.api.ApiCapabilities;
import com.questioncrawler.core.api.ApiCatalog;
import com.questioncrawler.core.api.ApiDocsLoader;
import com.questioncrawler.core.api.SwaggerDiscovery;
import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.ConfluenceAdapter;
import com.questioncrawler.core.source.JiveAdapter;
import com.questioncrawler.core.source.JiveOptions;
import com.questioncrawler.core.source.ConfluenceOptions;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.ProxyConfig;
import com.questioncrawler.core.source.Redirects;
import com.questioncrawler.core.source.TagFilter;
import com.questioncrawler.core.source.SignInSystem;
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
 * paths), follows redirects the way the real downloader does (and shows every hop), and never tries to get
 * around a login, CAPTCHA or block: it reports them and says what is needed.
 */
public final class SiteDiagnostics {

    /** What to check. {@code target} is the API/base address to probe (for Stack Exchange: the API base). */
    public record Input(String sourceType, String target, String site, List<String> tags, String authMethod,
                        String tokenEnv, String keyEnv, Path caCertFile, boolean allowInsecureHttp, int timeoutSeconds,
                        ProxyConfig proxy, ApiOptions api, String authUser, String authUserEnv) {
        public Input(String sourceType, String target, String site, List<String> tags, String authMethod,
                     String tokenEnv, String keyEnv, Path caCertFile, boolean allowInsecureHttp, int timeoutSeconds,
                     ProxyConfig proxy, ApiOptions api) {
            this(sourceType, target, site, tags, authMethod, tokenEnv, keyEnv, caCertFile, allowInsecureHttp, timeoutSeconds,
                    proxy, api, null, null);
        }

        public Input(String sourceType, String target, String site, List<String> tags, String authMethod,
                     String tokenEnv, String keyEnv, Path caCertFile, boolean allowInsecureHttp, int timeoutSeconds, ProxyConfig proxy) {
            this(sourceType, target, site, tags, authMethod, tokenEnv, keyEnv, caCertFile, allowInsecureHttp, timeoutSeconds, proxy, ApiOptions.DEFAULT, null, null);
        }

        public Input(String sourceType, String target, String site, List<String> tags, String authMethod,
                     String tokenEnv, String keyEnv, Path caCertFile, boolean allowInsecureHttp, int timeoutSeconds) {
            this(sourceType, target, site, tags, authMethod, tokenEnv, keyEnv, caCertFile, allowInsecureHttp, timeoutSeconds, ProxyConfig.NONE, ApiOptions.DEFAULT, null, null);
        }

        /** The internal site's REST API: Swagger documentation (an address or a saved file) is configured. (Without it, the generic site probes run.) */
        public boolean liveApi() {
            return sourceType.equals("live") && api != null && api.hasDocs();
        }

        public boolean confluence() {
            return "confluence".equals(sourceType);
        }

        public boolean jive() {
            return "jive".equals(sourceType);
        }
    }

    private static final Pattern LOGIN_WORDS = Pattern.compile("login|signin|sign-in|sso|oauth|saml|adfs|okta|microsoftonline|auth0|accounts\\.|/auth", Pattern.CASE_INSENSITIVE);
    private static final List<String> API_DESCRIPTION_PATHS = List.of("/openapi.json", "/swagger.json", "/v3/api-docs", "/api-docs");
    private static final int MAX_BODY = 300_000;

    private record Probe(String url, int status, Map<String, List<String>> headers, String body, long millis, String error, List<String> chain, String stop) {
        Probe(String url, int status, Map<String, List<String>> headers, String body, long millis, String error) {
            this(url, status, headers, body, millis, error, List.of(), null);
        }

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
        URI uri = parse(in.liveApi() && (in.target() == null || in.target().isBlank()) ? in.api().swaggerUrl() : in.target());
        boolean https = uri != null && "https".equalsIgnoreCase(uri.getScheme());

        if (in.liveApi() && uri == null && in.api().swaggerFile() != null) {
            // The documentation comes from a saved file and no address is known yet: read the file, skip the network steps.
            DataFindings fileData = new DataFindings();
            String why = "Not tested yet: the documentation was read from a saved file, so we do not know where the API lives. Enter the API address.";
            stages.add(skipped("network", "Reach the site", "Can this computer reach the site?", why));
            stages.add(skipped("secure", "Secure connection", "Is the connection secure and trusted?", why));
            stages.add(skipped("access", "Sign-in", "Do we need to sign in?", why));
            stages.add(liveApiStage(in, fileData, System.nanoTime()));
            stages.add(skipped("limits", "Limits and protection", "Are there limits or bot protection?", why));
            stages.add(scopeStage(in, false, fileData));
            return finish(now, in, stages, route(in, stages, fileData), fileData);
        }

        Stage network = networkStage(in, uri);
        stages.add(network);
        boolean reachable = network.status().equals("PASS");
        Stage secure = reachable ? secureStage(in, uri, https) : skipped("secure", "Secure connection", "Is the connection secure and trusted?", "Skipped because the site cannot be reached yet.");
        stages.add(secure);
        boolean secureOk = !secure.status().equals("FAIL") && reachable;

        Probe home = null;
        if (secureOk) {
            boolean withAuth = in.tokenEnv() != null && (in.authMethod().equals("token")
                    || in.authMethod().equals("basic-env") || in.authMethod().equals("confluence-api-token"));
            home = get(in, in.sourceType().equals("stackexchange") ? infoUrl(in)
                    : in.confluence() ? confluenceProbeUrl(in)
                    : in.jive() ? jiveProbeUrl(in)
                    : probeUrl(in), withAuth);
        }
        Stage access = secureOk ? accessStage(in, home) : skipped("access", "Sign-in", "Do we need to sign in?", "Skipped until the site can be reached over a trusted connection.");
        stages.add(access);

        DataFindings data = new DataFindings();
        Stage dataStage = secureOk ? dataStage(in, uri, home, data) : skipped("data", "Way to get the data", "Is there a proper way to get the data?", "Skipped until the site can be reached.");
        stages.add(dataStage);
        if (in.liveApi() && data.restApiReady && !access.status().equals("PASS")) {
            // The website may send visitors to single sign-on while the REST API accepts a key / token directly: the real test call decides.
            List<Fact> facts = new ArrayList<>(access.facts());
            facts.add(new Fact("Note", "The web address leads to a sign-in page, but a real API call with your key" + (in.tokenEnv() != null && "token".equals(in.authMethod()) ? " and token" : "") + " succeeded."));
            stages.set(2, stage("access", "Sign-in", "Do we need to sign in?", "PASS",
                    "No browser sign-in is needed: the REST API accepts our access key" + (in.tokenEnv() != null && "token".equals(in.authMethod()) ? " and token" : "") + " directly, even though the website itself leads to a sign-in page.",
                    facts, List.of(), System.nanoTime()));
        }
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

    /** Facts and needs for "the site sends programs to a sign-in page": names the system and what to ask for. */
    private static void signInFindings(Probe home, List<Fact> facts, List<Need> needs) {
        var system = SignInSystem.identify(home.url(), home.header("location"));
        system.ifPresent(sys -> facts.add(new Fact("Sign-in system", sys.name())));
        needs.add(new Need("A way for a program to sign in" + system.map(sys -> " to " + sys.name()).orElse(""),
                system.map(sys -> "A browser sign-in cannot be done by this app. " + capital(sys.forPrograms()) + ".").orElse("A browser sign-in (redirects, SSO pages, MFA) cannot be done by a program: it needs a token or a service account."),
                "The site owners / your IT identity team"));
        needs.add(new Need("The address of the API that accepts a token", "The web address sends everyone to the sign-in page. API clients usually use a different address (a gateway or an /api path) that accepts a token or key instead.",
                "The site owners"));
        system.ifPresent(sys -> needs.add(new Need("What to ask for, exactly", String.join(" ", sys.ask()), "You, in a message to IT (a ready-made one is under 'Request template')")));
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

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
        boolean redirected = !home.chain().isEmpty();
        if (redirected) {
            facts.add(new Fact("Redirected", String.join(" → ", home.chain())));
            facts.add(new Fact("Final address", hostAndPath(home.url())));
        }
        if (redirected && LOGIN_WORDS.matcher(home.url()).find()) {
            List<Need> needs = new ArrayList<>();
            signInFindings(home, facts, needs);
            return stage("access", title, q, "WARN", "The site sends visitors to a sign-in page (through " + home.chain().size() + " redirect" + (home.chain().size() == 1 ? "" : "s") + "). Reading content requires an approved login.", facts, needs, t0);
        }
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
                    : "The address answers without signing in" + (redirected ? " (after " + home.chain().size() + " redirect" + (home.chain().size() == 1 ? "" : "s") + ")." : "."), facts, List.of(), t0);
        }
        if (s >= 300 && s < 400) {
            String loc = home.header("location");
            facts.add(new Fact("Redirects to", loc == null ? "unknown" : hostAndPath(loc)));
            if (loc != null && LOGIN_WORDS.matcher(loc).find()) {
                List<Need> needs = new ArrayList<>();
                signInFindings(home, facts, needs);
                return stage("access", title, q, "WARN", "The site sends visitors to a sign-in page. Reading content requires an approved login.", facts, needs, t0);
            }
            return stage("access", title, q, "INFO", "The address redirects elsewhere and the redirect was not followed: " + (home.stop() == null ? "unknown reason" : home.stop()) + ".", facts,
                    List.of(new Need("The final address of the API or site", "Use the address after the redirect in Settings, or change 'Follow redirects'.", "You or the site owners")), t0);
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
        boolean restApi;
        boolean restApiReady;
        String quotaInfo;
        long tagQuestions = Long.MIN_VALUE;
        boolean tagChecked;
        StackExchangeAdapter.TagCheck tagCheck;
        boolean allTags;
        long siteQuestions = -1;
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
                        if (TagFilter.asksForAll(in.tags())) {
                            d.allTags = true;
                            try {
                                d.siteQuestions = adapter.siteQuestionCount();
                            } catch (SourceException ignored) {
                                d.siteQuestions = -1; // the size is nice to have; the test call above already covers credentials
                            }
                        } else {
                            d.tagChecked = true;
                            d.tagCheck = adapter.checkTag(in.tags().get(0));
                            d.tagQuestions = d.tagCheck.exists() ? d.tagCheck.count() : -1;
                        }
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

        if (in.confluence()) {
            try {
                ConfluenceAdapter adapter = confluenceAdapter(in);
                String ping = adapter.ping();
                d.officialApi = true;
                facts.add(new Fact("Official API", "Confluence Content REST API + CQL"));
                facts.add(new Fact("Reachability", ping));
                facts.add(new Fact("Note", "Tags mean Confluence labels; pages are stored as threads with empty answers."));
                return stage("data", title, q, "PASS",
                        "Yes: Confluence exposes a documented REST API that this app supports (separate from Jive/SharePoint).",
                        facts, List.of(), t0);
            } catch (SourceException e) {
                return stage("data", title, q, "FAIL", friendly(e), facts,
                        List.of(new Need("Working Confluence credentials",
                                "Set the token/PAT environment variable and (for Cloud) your Atlassian email. Cookie/SSO scraping is not supported.",
                                "You")), t0);
            }
        }

        if (in.jive()) {
            try {
                JiveAdapter adapter = jiveAdapter(in);
                String ping = adapter.ping();
                d.officialApi = true;
                facts.add(new Fact("Official API", "Jive Core REST API v3"));
                facts.add(new Fact("Reachability", ping));
                facts.add(new Fact("Note", "Tags mean Jive content tags; discussions/questions may include replies as answers."));
                return stage("data", title, q, "PASS",
                        "Yes: Jive exposes a documented Core REST API that this app supports (separate from Confluence/SharePoint).",
                        facts, List.of(), t0);
            } catch (SourceException e) {
                return stage("data", title, q, "FAIL", friendly(e), facts,
                        List.of(new Need("Working Jive credentials",
                                "Set JIVE_PASSWORD (Basic) or JIVE_ACCESS_TOKEN (Bearer OAuth), plus username for Basic. Cookie/SSO scraping is not supported.",
                                "You")), t0);
            }
        }

        if (in.liveApi()) {
            return liveApiStage(in, d, t0);
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

    /** The internal site's REST API: read its Swagger documentation, check the access key, and make one real call. */
    private Stage liveApiStage(Input in, DataFindings d, long t0) {
        String title = "Way to get the data";
        String q = "Is there a proper way to get the data?";
        List<Fact> facts = new ArrayList<>();
        List<Need> needs = new ArrayList<>();
        d.restApi = true;
        ApiOptions api = in.api() == null ? ApiOptions.DEFAULT : in.api();
        String base = in.target() == null ? "" : in.target();
        ApiCapabilities caps = null;
        ApiCatalog catalog = null;
        String status = "PASS";
        String summary = "Yes: the site has a REST API that this app can use.";

        if (api.hasDocs()) {
            try {
                var settings = apiSettings(in, !base.isBlank() ? base : api.swaggerUrl() != null ? api.swaggerUrl() : "https://localhost/", api);
                var discovery = ApiDocsLoader.load(settings, env);
                catalog = discovery.catalog();
                caps = discovery.capabilities();
                facts.add(new Fact("API documentation", catalog.title() + (catalog.version().isBlank() ? "" : " " + catalog.version())
                        + (api.swaggerFile() != null ? " (from a saved file)" : "")));
                facts.add(new Fact("Endpoints documented", String.valueOf(catalog.operations().size())));
                if (base.isBlank() && catalog.suggestedBaseUrl() != null) {
                    String suggested = catalog.suggestedBaseUrl();
                    if (suggested.startsWith("http")) {
                        base = suggested;
                    } else if (api.swaggerUrl() != null) {
                        base = URI.create(api.swaggerUrl()).resolve(suggested).toString();
                    } else {
                        facts.add(new Fact("API path (from the documentation)", suggested + ": add your site's address in front of it"));
                    }
                    if (!base.isBlank()) {
                        facts.add(new Fact("API address (from the documentation)", base));
                    }
                }
                catalog.security().stream().filter(x -> "apiKey".equals(x.type())).findFirst().ifPresent(k ->
                        facts.add(new Fact("Access key is sent", "as '" + k.name() + "' in the " + ("header".equals(k.in()) ? "request header" : "address (query)"))));
                for (var c : caps.capabilities()) {
                    facts.add(new Fact((c.found() ? "Will use: " : (c.required() ? "MISSING: " : "Not offered: ")) + c.label(),
                            c.found() ? c.method() + " " + c.path() : "not listed in the documentation"));
                }
                if (!caps.ready()) {
                    status = "FAIL";
                    summary = "The API is documented, but endpoints this app needs are missing.";
                    caps.problems().stream().filter(p -> p.startsWith("The documentation lists no")).forEach(p ->
                            needs.add(new Need(p.replace("The documentation lists no ", "An endpoint like ").replaceAll("endpoint, which is needed to.*", "endpoint"), "Without it questions or answers cannot be downloaded.", "The site owners / API documentation")));
                }
                caps.problems().stream().filter(p -> !p.startsWith("The documentation lists no")).forEach(p -> facts.add(new Fact("Note", p)));
            } catch (SourceException e) {
                status = "WARN";
                summary = "We could not read the API documentation: " + friendly(e);
                needs.add(new Need("The correct documentation address, or the JSON document behind it", "Open the Swagger page in your browser and use the link under its title (often /swagger/v1/swagger.json).", "You or the site owners"));
                if (e.kind() == SourceException.Kind.AUTH_FAILED) {
                    needs.add(new Need("A saved copy of the documentation (JSON file)", "Open the documentation's JSON address in your browser, where you are signed in, save it as swagger.json and upload it on the Connect screen.", "You"));
                    needs.add(new Need("A token or service account for the documentation", "Interactive single sign-on (SSO) cannot be done by this app; a token can be sent instead (Extra sign-in settings).", "The site owners / IT"));
                }
            }
        }
        if (base.isBlank()) {
            facts.add(new Fact("API address", "not known yet"));
            needs.add(new Need("The API address", "Enter it in the settings, or provide a readable Swagger address so it can be found.", "You or the site owners"));
            return stage("data", title, q, "WARN", "We do not know the API address yet.", facts, needs, t0);
        }

        // The access key: requests cannot succeed without it.
        String keyVar = in.keyEnv();
        boolean hasKey = keyVar != null && !keyVar.isBlank() && env.apply(keyVar) != null && !env.apply(keyVar).isBlank();
        facts.add(new Fact("Access key variable " + (keyVar == null || keyVar.isBlank() ? "(not named)" : keyVar), hasKey ? "set (value not shown)" : "NOT set"));
        if (!hasKey) {
            if (status.equals("PASS")) {
                status = "WARN";
                summary = "The API is documented, but there is no access key to call it with.";
            }
            needs.add(new Need("An API access key", "Every request needs one. Ask the site owners, then set it in the environment variable named in the settings (never in a file).", "The site owners / platform team"));
        } else {
            try {
                final String resolvedBase = base;
                final ApiCapabilities learned = caps;
                StackExchangeAdapter adapter = new StackExchangeAdapter(apiSettings(in, resolvedBase, api), new HttpSourceClient(apiSettings(in, resolvedBase, api), env), env, d2 -> { },
                        learned == null ? null : () -> learned);
                adapter.ping();
                d.quotaInfo = adapter.quotaRemaining() >= 0 ? adapter.quotaRemaining() + " requests left" : null;
                facts.add(new Fact("Test call", "succeeded"));
                if (d.quotaInfo != null) {
                    facts.add(new Fact("Requests left", String.valueOf(adapter.quotaRemaining())));
                }
                if (!in.tags().isEmpty()) {
                    try {
                        if (TagFilter.asksForAll(in.tags())) {
                            d.allTags = true;
                            try {
                                d.siteQuestions = adapter.siteQuestionCount();
                            } catch (SourceException ignored) {
                                d.siteQuestions = -1; // the size is nice to have; the test call above already covers credentials
                            }
                        } else {
                            d.tagChecked = true;
                            d.tagCheck = adapter.checkTag(in.tags().get(0));
                            d.tagQuestions = d.tagCheck.exists() ? d.tagCheck.count() : -1;
                        }
                    } catch (SourceException e) {
                        d.tagError = e.getMessage();
                    }
                }
            } catch (SourceException e) {
                status = e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.FORBIDDEN ? "FAIL" : "WARN";
                summary = "The test call failed: " + friendly(e);
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("sign-in page") && !e.getMessage().toLowerCase().contains("bearer token:")) {
                    needs.add(new Need("A credential the API accepts without a browser", "The call was sent to a single sign-on page instead of being answered. Check the API access key (and token) first; if both are set and it still happens, the API needs a token or service account.", "The site owners / your IT identity team"));
                    needs.add(new Need("The address of the API that accepts a key or token", "The web address and the API often differ: the API may live on a gateway or under /api.", "The site owners"));
                } else if (e.getMessage() != null && e.getMessage().toLowerCase().contains("bearer")) {
                    needs.add(new Need("A Bearer token for the API", "The API wants a token in an 'Authorization: Bearer' header in addition to the key. Set 'Bearer token sign-in' to 'Bearer token' and name the environment variable that holds it.",
                            "You (profile settings: API / personal access tokens) or the site owners"));
                } else {
                    needs.add(new Need(e.kind() == SourceException.Kind.AUTH_FAILED ? "A valid API access key and the right way to send it" : "A working API address", "The documented API did not accept our test call.", "The site owners"));
                }
            }
        }
        d.restApiReady = status.equals("PASS");
        return stage("data", title, q, status, summary, facts, needs, t0);
    }

    private SourceSettings apiSettings(Input in, String base, ApiOptions api) {
        URI u = URI.create(base);
        List<String> hosts = new ArrayList<>();
        if (u.getHost() != null) {
            hosts.add(u.getHost());
        }
        if (api.swaggerUrl() != null) {
            try {
                String h = URI.create(api.swaggerUrl()).getHost();
                if (h != null && !hosts.contains(h)) {
                    hosts.add(h);
                }
            } catch (IllegalArgumentException ignored) {
                // reported elsewhere
            }
        }
        return new SourceSettings(in.sourceType(), base, hosts, List.of("/"), in.authMethod() == null ? "none" : in.authMethod(), in.tokenEnv(), "Authorization", "Bearer",
                in.caCertFile(), Duration.ofSeconds(Math.max(1, in.timeoutSeconds())), in.allowInsecureHttp(), in.site(), in.keyEnv(), in.proxy(), api);
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
        if (d.restApi && d.quotaInfo != null) {
            facts.add(new Fact("Request allowance", d.quotaInfo));
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
        if (TagFilter.asksForAll(in.tags()) && reachable) {
            boolean publicSite = in.sourceType().equals("stackexchange");
            List<Fact> all = new ArrayList<>();
            all.add(new Fact("Tags", in.confluence()
                    ? "all (every matching page label / unrestricted labels)"
                    : in.jive()
                    ? "all (every matching content tag / unrestricted tags)"
                    : "all (every question, whatever its tags)"));
            all.add(new Fact("Questions on the site", d.siteQuestions >= 0 ? String.format("%,d", d.siteQuestions) : "the site does not say"));
            if (publicSite) {
                return stage("scope", title, q, "WARN", "'all' on a public Stack Exchange site means millions of questions. Choose a time window (for example 7 days) on the Collect page, or name the tags you want.", all,
                        List.of(new Need("A short time window, or specific tags", "Collecting every tag of a public site is refused without a time window: it would use up the request allowance.", "You")), t0);
            }
            if (in.confluence()) {
                return stage("scope", title, q, "WARN",
                        "Label 'all' on Confluence needs a space key, ancestor page, or time window. Unbounded whole-site sync is refused at collect time.",
                        all, List.of(new Need("A space, ancestor, or time window", "Set source.confluence.spaces or a Collect window.", "You")), t0);
            }
            if (in.jive()) {
                return stage("scope", title, q, "WARN",
                        "Tag 'all' on Jive needs a place id or time window. Unbounded whole-site sync is refused at collect time.",
                        all, List.of(new Need("A place or time window", "Set source.jive.places or a Collect window.", "You")), t0);
            }
            boolean large = d.siteQuestions > 20_000;
            return stage("scope", title, q, "PASS", "Every question on the site will be collected (all tags)" + (d.siteQuestions >= 0 ? ": about " + String.format("%,d", d.siteQuestions) + " question(s)." : ".")
                    + (large ? " That is a lot: start with a time window (for example 1 year) to try it out." : ""), all, List.of(), t0);
        }
        if (!reachable) {
            return skipped("scope", title, q, "Will be checked once the site can be reached.");
        }
        if (in.confluence() && d.officialApi) {
            return stage("scope", title, q, "PASS",
                    "Confluence labels (tags) are applied as CQL filters when collecting. Exact label existence is verified during sync.",
                    facts, List.of(), t0);
        }
        if (in.jive() && d.officialApi) {
            return stage("scope", title, q, "PASS",
                    "Jive content tags are applied as REST filters when collecting. Exact tag existence is verified during sync.",
                    facts, List.of(), t0);
        }
        if (!in.sourceType().equals("stackexchange") && !d.restApi) {
            return stage("scope", title, q, "SKIPPED", "Checking that a tag exists needs the site's API, which is not connected yet.", facts, List.of(), t0);
        }
        if (d.tagChecked && d.tagCheck != null && d.tagCheck.exists()) {
            String typed = in.tags().get(0);
            String exact = d.tagCheck.exactName();
            List<Fact> found = new ArrayList<>();
            found.add(new Fact("Tag you chose", typed));
            boolean differs = exact != null && !exact.equals(typed);
            if (differs) {
                found.add(new Fact("Exact name on the site", exact));
            }
            found.add(new Fact("Questions with this tag (all time)", d.tagCheck.count() >= 0 ? String.format("%,d", d.tagCheck.count()) : "yes, the count is not given"));
            found.add(new Fact("Found by", d.tagCheck.how()));
            return stage("scope", title, q, "PASS", "The tag '" + typed + "' exists on the site" + (differs ? " (the site writes it '" + exact + "'; that spelling is used automatically)" : "")
                    + (d.tagCheck.count() >= 0 ? " and has " + String.format("%,d", d.tagCheck.count()) + " question(s)." : "."), found, List.of(), t0);
        }
        if (d.tagChecked) {
            List<Fact> notFound = new ArrayList<>(facts);
            List<StackExchangeAdapter.TagHit> similar = d.tagCheck == null ? List.of() : d.tagCheck.similar();
            if (!similar.isEmpty()) {
                notFound.add(new Fact("Similar tags on the site", similar.stream().limit(8).map(h -> h.name() + (h.count() >= 0 ? " (" + String.format("%,d", h.count()) + ")" : "")).collect(java.util.stream.Collectors.joining(", "))));
            }
            return stage("scope", title, q, "WARN", "We could not find a tag named '" + in.tags().get(0) + "' on the site" + (similar.isEmpty() ? "." : ", but similar tags exist (listed below)."), notFound,
                    List.of(new Need("The exact tag name used on the site", similar.isEmpty()
                            ? "We tried the name in several spellings, the site's tag list and a real question search. On the Connect screen use 'Find tags on the site' to search the site's own list, or ask the site owners for the exact name."
                            : "Pick one of the similar tags with 'Find tags on the site' on the Connect screen.", "You")), t0);
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
        if ((in.sourceType().equals("stackexchange") || in.confluence() || in.jive()) && d.officialApi) {
            String summary = in.confluence()
                    ? "Confluence exposes a documented REST API. Pages are collected as local threads (labels become tags). This is separate from Jive or SharePoint."
                    : in.jive()
                    ? "Jive exposes a documented Core REST API. Content is collected as local threads (tags stay tags; discussion replies become answers when available). This is separate from Confluence or SharePoint."
                    : "This site publishes a documented API. It is the proper, stable, permitted way to collect questions and answers.";
            List<String> next = in.confluence()
                    ? List.of("Save these settings.", "Restrict by space keys and/or labels.", "Open Collect and start with a modest time window.")
                    : in.jive()
                    ? List.of("Save these settings.", "Restrict by place ids and/or tags.", "Open Collect and start with a modest time window.")
                    : List.of("Save these settings.", "Open Collect and start with a small time window.", "Keep the source links: the content is licensed CC BY-SA and requires attribution.");
            return new Route("OFFICIAL_API", "Official API (recommended)", summary, true, next);
        }
        if (d.restApiReady) {
            return new Route("REST_API", "Your site's REST API (recommended)", "The site documents its REST API with Swagger. Every endpoint this app needs is available, so questions and answers can be collected the proper, permitted way.", true,
                    List.of("Save these settings.", "Open Collect and start with a small time window.", "Then use Organize to discover topics from the questions."));
        }
        if (d.restApi && !d.botProtection) {
            return new Route("REST_API_INCOMPLETE", "A REST API exists, but something is missing", "The site has a REST API, but the documentation, the API address or the access key still needs attention before collecting can start.", false,
                    List.of("Open the amber or red step 'Way to get the data' and follow what is needed.", "Run the check again afterwards."));
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
        List<String> chain = new ArrayList<>();
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
            HttpClient client = b.build();
            Redirects.Mode mode = (in.api() == null ? ApiOptions.DEFAULT : in.api()).redirectMode();
            URI first = URI.create(url);
            URI current = first;
            java.util.Set<String> visited = new java.util.HashSet<>();
            visited.add(current.toString());
            Probe lastRedirect = null;
            for (int hops = 0; ; hops++) {
                HttpRequest.Builder req = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(Math.max(1, in.timeoutSeconds())))
                        .header("User-Agent", "QuestionCrawler-diagnostics/0.1").header("Accept", "application/json, text/html;q=0.8, */*;q=0.5").header("Accept-Encoding", "gzip").GET();
                if (withToken && in.tokenEnv() != null && env.apply(in.tokenEnv()) != null && !env.apply(in.tokenEnv()).isBlank()) {
                    String secret = env.apply(in.tokenEnv());
                    if ("basic-env".equals(in.authMethod()) || "confluence-api-token".equals(in.authMethod())) {
                        String user = in.authUser();
                        if ((user == null || user.isBlank()) && in.authUserEnv() != null) {
                            user = env.apply(in.authUserEnv());
                        }
                        if (user != null && !user.isBlank()) {
                            String basic = java.util.Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
                            req.header("Authorization", "Basic " + basic);
                        }
                    } else {
                        req.header("Authorization", "Bearer " + secret);
                    }
                }
                HttpResponse<java.io.InputStream> r;
                try {
                    r = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                } catch (java.io.IOException unreachable) {
                    if (lastRedirect == null) {
                        throw unreachable;
                    }
                    return new Probe(lastRedirect.url(), lastRedirect.status(), lastRedirect.headers(), lastRedirect.body(), lastRedirect.millis(), null, lastRedirect.chain(),
                            "the address it leads to (" + Redirects.show(current) + ") could not be reached from this computer: " + unreachable.getClass().getSimpleName());
                }
                boolean gz = r.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
                Map<String, List<String>> headers = new java.util.HashMap<>();
                String text;
                try (var raw = r.body(); var is = gz ? new java.util.zip.GZIPInputStream(raw) : raw) {
                    text = new String(is.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
                }
                r.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (!Redirects.isRedirect(r.statusCode())) {
                    return new Probe(current.toString(), r.statusCode(), headers, text, ms, null, chain, null);
                }
                List<String> loc = headers.get("location");
                Redirects.Step step = Redirects.next(current, loc == null || loc.isEmpty() ? null : loc.get(0), first, mode, visited, hops);
                if (step.follow() && !"https".equalsIgnoreCase(step.target().getScheme()) && !in.allowInsecureHttp() && !"http".equalsIgnoreCase(first.getScheme())) {
                    step = new Redirects.Step(null, "the redirect leads to unencrypted http (" + Redirects.show(step.target()) + ")");
                }
                if (!step.follow()) {
                    return new Probe(current.toString(), r.statusCode(), headers, text, ms, null, chain, step.stopReason());
                }
                lastRedirect = new Probe(current.toString(), r.statusCode(), headers, text, ms, null, List.copyOf(chain), null);
                chain.add(r.statusCode() + " to " + Redirects.show(step.target()));
                visited.add(step.target().toString());
                current = step.target();
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

    private ConfluenceAdapter confluenceAdapter(Input in) {
        URI base = URI.create(in.target());
        String host = base.getHost();
        ConfluenceOptions options = ConfluenceOptions.DEFAULT;
        String apiPath = options.defaultApiRootPath() + "/";
        String auth = in.authMethod() == null || in.authMethod().isBlank() ? "basic-env" : in.authMethod();
        SourceSettings s = new SourceSettings("confluence", in.target(),
                host == null ? List.of() : List.of(host),
                List.of(apiPath, "/wiki/rest/api/", "/rest/api/"),
                auth, in.tokenEnv(), "Authorization", "Bearer", in.caCertFile(),
                Duration.ofSeconds(Math.max(1, in.timeoutSeconds())), in.allowInsecureHttp(), null, null,
                in.proxy(),
                in.api() == null ? ApiOptions.DEFAULT : in.api(),
                in.authUser(), in.authUserEnv());
        return new ConfluenceAdapter(s, options, new HttpSourceClient(s, env), env);
    }

    private static String confluenceProbeUrl(Input in) {
        String root = ConfluenceAdapter.resolveApiRoot(in.target(), ConfluenceOptions.DEFAULT);
        return root + "/space?limit=1";
    }

    private JiveAdapter jiveAdapter(Input in) {
        URI base = URI.create(in.target());
        String host = base.getHost();
        JiveOptions options = JiveOptions.DEFAULT;
        String apiPath = options.defaultApiRootPath() + "/";
        String auth = in.authMethod() == null || in.authMethod().isBlank() ? "basic-env" : in.authMethod();
        SourceSettings s = new SourceSettings("jive", in.target(),
                host == null ? List.of() : List.of(host),
                List.of(apiPath, "/api/core/v3/"),
                auth, in.tokenEnv(), "Authorization", "Bearer", in.caCertFile(),
                Duration.ofSeconds(Math.max(1, in.timeoutSeconds())), in.allowInsecureHttp(), null, null,
                in.proxy(),
                in.api() == null ? ApiOptions.DEFAULT : in.api(),
                in.authUser(), in.authUserEnv());
        return new JiveAdapter(s, options, new HttpSourceClient(s, env), env);
    }

    private static String jiveProbeUrl(Input in) {
        String root = JiveAdapter.resolveApiRoot(in.target(), JiveOptions.DEFAULT);
        return root + "/places?count=1";
    }

    private static String probeUrl(Input in) {
        return in.liveApi() && (in.target() == null || in.target().isBlank()) ? in.api().swaggerUrl() : in.target();
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
