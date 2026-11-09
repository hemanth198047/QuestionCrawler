package com.questioncrawler.core.source;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Short links (go/xyz, https://go.corp.com/xyz, bit.ly, aka.ms, ...): finds out where they really lead and
 * writes that address into collected content and into pasted settings.
 *
 * <p>Looking a link up only asks the short-link service for its redirect: no credentials, cookies or tokens are
 * sent, the real destination is never requested, and a lookup that ends at a sign-in page is left as it was.
 * Answers are remembered (also between runs), so each link is looked up once.
 */
public final class ShortLinks {

    /** Short-link services recognised without any setting. Your own can be added in network.shortLinkHosts. */
    public static final List<String> DEFAULT_HOSTS = List.of("go", "goto", "golink", "golinks", "bit.ly", "tinyurl.com", "aka.ms", "t.co", "ow.ly",
            "is.gd", "buff.ly", "rebrand.ly", "tiny.cc", "lnkd.in", "goo.gl", "t.ly", "cutt.ly", "shorturl.at", "rb.gy", "short.io", "bl.ink");

    /** Settings: on/off, extra short-link hosts, and how to reach them (proxy, company certificate). */
    public record Options(boolean enabled, List<String> extraHosts, ProxyConfig proxy, Path caCertFile, Duration timeout) {
        public static final Options OFF = new Options(false, List.of(), ProxyConfig.NONE, null, Duration.ofSeconds(5));

        public Options {
            extraHosts = extraHosts == null ? List.of() : extraHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT))
                    .map(h -> h.replaceFirst("^https?://", "").replaceAll("/.*$", "")).filter(h -> !h.isEmpty()).distinct().toList();
            proxy = proxy == null ? ProxyConfig.NONE : proxy;
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
        }

        /** From settings values (network.expandShortLinks, network.shortLinkHosts, the proxy and certificate settings). */
        public static Options from(Function<String, String> get) {
            String on = value(get, "network.expandShortLinks");
            String ca = value(get, "source.caCertFile");
            int seconds;
            try {
                seconds = Integer.parseInt(value(get, "source.timeoutSeconds"));
            } catch (NumberFormatException e) {
                seconds = 10;
            }
            return new Options(on.isEmpty() || Boolean.parseBoolean(on), List.of(value(get, "network.shortLinkHosts").split(",")),
                    ProxyConfig.parse(value(get, "network.proxyHost"), value(get, "network.proxyPort"), value(get, "network.noProxy"),
                            value(get, "network.proxyUserEnv"), value(get, "network.proxyPasswordEnv")),
                    ca.isEmpty() ? null : Path.of(ca), Duration.ofSeconds(Math.max(2, Math.min(seconds, 10))));
        }

        private static String value(Function<String, String> get, String key) {
            String v = get.apply(key);
            return v == null ? "" : v.strip();
        }
    }

    /** Where a short link leads, or why that is not known. */
    public record Lookup(String link, String target, String problem) {
        public boolean found() {
            return target != null;
        }
    }

    /** Asks a short-link service where one address leads. Replaceable in tests. */
    @FunctionalInterface
    public interface Fetcher {
        /** status, Location header (may be null) and the start of the body. */
        Hop fetch(URI uri) throws IOException;
    }

    public record Hop(int status, String location, String body) {}

    private static final int MAX_HOPS = 5;
    private static final Pattern META_REFRESH = Pattern.compile(
            "(?is)<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"'][^\"']*?url\\s*=\\s*'?([^\"'>\\s]+)");
    private static final Pattern SCRIPT_LOCATION = Pattern.compile(
            "(?is)(?:window\\.|document\\.)?location(?:\\.href)?\\s*(?:=|\\.replace\\(|\\.assign\\()\\s*[\"']([^\"']+)[\"']");

    private final Options options;
    private final Fetcher fetcher;
    private final Set<String> singleLabelHosts;
    private final Pattern linkPattern;
    private final Map<String, Lookup> cache = new ConcurrentHashMap<>();
    private final Set<String> unreachableHosts = ConcurrentHashMap.newKeySet();

    public ShortLinks(Options options, Function<String, String> env) {
        this(options, null, env);
    }

    /** @param fetcher how to ask a short-link service (null: over HTTP, without following redirects) */
    public ShortLinks(Options options, Fetcher fetcher, Function<String, String> env) {
        this.options = options == null ? Options.OFF : options;
        this.fetcher = fetcher != null ? fetcher : httpFetcher(this.options, env == null ? System::getenv : env);
        Set<String> single = new LinkedHashSet<>();
        for (String h : hosts()) {
            if (!h.contains(".")) {
                single.add(h);
            }
        }
        this.singleLabelHosts = single;
        String bare = single.isEmpty() ? "go" : String.join("|", single.stream().map(Pattern::quote).toList());
        this.linkPattern = Pattern.compile(
                "(?<code>`[^`\\n]*`)"
                        + "|(?<mdlink>\\[(?<text>[^\\]\\n]*)\\]\\((?<target>[^)\\s]+)(?<rest>[^)\\n]*)\\))"
                        + "|<(?<auto>https?://[^>\\s]+)>"
                        + "|(?<url>https?://[^\\s<>()\\[\\]\"'`]+)"
                        + "|(?<bare>(?<![\\w/.:@\\-])(?i:" + bare + ")/(?:[A-Za-z0-9][\\w\\-./~%+#=?&]*)?)");
    }

    public boolean enabled() {
        return options.enabled();
    }

    private List<String> hosts() {
        List<String> all = new ArrayList<>(DEFAULT_HOSTS);
        all.addAll(options.extraHosts());
        return all;
    }

    /** True for a short link: a known short-link host with something after it ("go/xyz", "https://bit.ly/abc"). */
    public boolean isShortLink(String text) {
        if (text == null) {
            return false;
        }
        URI uri = toUri(text.strip());
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!schemeGuessed(text) && !host.contains(".") && !host.equals("localhost")) {
            return true; // http://casc-docs/...: a one-word intranet name, the way company short links are written
        }
        // A short-link name you added yourself (casc-docs) is a link on its own too: casc-docs/ opens its start page.
        return isShortHost(uri.getHost()) && (path.replace("/", "").length() > 0 || options.extraHosts().contains(uri.getHost().toLowerCase(Locale.ROOT)));
    }

    private boolean isShortHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String s : hosts()) {
            if (h.equals(s) || (s.startsWith(".") && h.endsWith(s))) {
                return true;
            }
        }
        // go.mycompany.com, goto.corp: the usual names of company short-link services
        return h.startsWith("go.") || h.startsWith("goto.") || h.startsWith("golinks.");
    }

    /** "go/xyz" (no scheme, as people type them) becomes http://go/xyz. */
    private static URI toUri(String text) {
        return webAddress(text);
    }

    /**
     * A typed address as a web address. Without http:// or https:// (casc-docs, casc-docs/, casc-docs/page,
     * go.corp.com/x, wiki:8080/page) http:// is assumed first; callers try https:// when http does not answer.
     */
    public static URI webAddress(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.contains(" ")) {
            return null;
        }
        if (!t.matches("(?i)^https?://.*")) {
            if (t.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*") || t.startsWith("/") || t.startsWith("mailto:")) {
                return null; // another scheme (ftp://, file://) or a path, not a web address
            }
            t = t.matches("^[A-Za-z0-9.\\-]+(:\\d+)?(/.*)?$") ? "http://" + t : null;
        }
        if (t == null) {
            return null;
        }
        try {
            URI u = URI.create(t);
            if (u.getHost() == null) {
                return null;
            }
            // casc-docs (no slash) becomes http://casc-docs/
            return u.getRawPath() == null || u.getRawPath().isEmpty() ? URI.create(t + "/") : u;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when the address was typed without http:// or https://. */
    static boolean schemeGuessed(String text) {
        return text != null && !text.strip().matches("(?i)^https?://.*");
    }

    /** The same address with https:// instead of http://. */
    static URI withHttps(URI u) {
        return URI.create("https" + u.toString().substring(u.getScheme().length()));
    }

    /** Where a short link leads (looked up once, then remembered). */
    public Lookup resolve(String link) {
        URI asUri = toUri(link.strip());
        String key = asUri == null ? link.strip() : asUri.toString(); // go/x and http://go/x are the same link
        Lookup known = cache.get(key);
        if (known != null) {
            return known;
        }
        Lookup found = lookUp(key, schemeGuessed(link));
        cache.put(key, found);
        return new Lookup(link.strip(), found.target(), found.problem());
    }

    private Lookup lookUp(String link, boolean schemeGuessed) {
        if (!options.enabled()) {
            return new Lookup(link, null, "expanding short links is switched off");
        }
        URI current = toUri(link);
        if (current == null || current.getHost() == null) {
            return new Lookup(link, null, "not a web address");
        }
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            String host = current.getHost().toLowerCase(Locale.ROOT);
            if (unreachableHosts.contains(host)) {
                return new Lookup(link, null, "the short-link service " + host + " could not be reached");
            }
            Hop answer;
            try {
                answer = fetcher.fetch(current);
            } catch (IOException e) {
                if (hop == 0 && schemeGuessed && "http".equalsIgnoreCase(current.getScheme())) {
                    // typed without http:// or https:// and http does not answer: try https
                    schemeGuessed = false;
                    current = withHttps(current);
                    hop--;
                    continue;
                }
                unreachableHosts.add(host);
                return new Lookup(link, null, "the short-link service " + host + " could not be reached (" + e.getClass().getSimpleName() + ")");
            }
            String next = null;
            if (answer.status() >= 300 && answer.status() < 400 && answer.location() != null && !answer.location().isBlank()) {
                next = answer.location().strip();
            } else if (answer.status() >= 200 && answer.status() < 300 && answer.body() != null) {
                Matcher m = META_REFRESH.matcher(answer.body());
                if (m.find()) {
                    next = m.group(1);
                } else {
                    Matcher s = SCRIPT_LOCATION.matcher(answer.body());
                    if (s.find()) {
                        next = s.group(1);
                    }
                }
            }
            if (next == null) {
                return new Lookup(link, null, answer.status() == 404 ? "the short-link service does not know this link (404)"
                        : "the short-link service answered HTTP " + answer.status() + " without saying where the link leads");
            }
            URI target;
            try {
                target = current.resolve(next.replace("&amp;", "&").replace(" ", "%20"));
            } catch (IllegalArgumentException e) {
                return new Lookup(link, null, "the short-link service gave an address that is not valid");
            }
            if (target.getScheme() == null || !(target.getScheme().equalsIgnoreCase("http") || target.getScheme().equalsIgnoreCase("https"))) {
                return new Lookup(link, null, "the link leads to '" + target.getScheme() + "', not a web address");
            }
            if (Redirects.isSignInAddress(target.toString())) {
                return new Lookup(link, null, "the short-link service asks for a sign-in (" + Redirects.show(target) + ")");
            }
            if (target.getHost() == null || !isShortHost(target.getHost()) || target.getHost().equalsIgnoreCase(current.getHost()) && samePathAndQuery(target, current)) {
                return new Lookup(link, target.toString(), null);
            }
            current = target; // another short link (t.co -> bit.ly -> ...)
        }
        return new Lookup(link, null, "more than " + MAX_HOPS + " short links in a row");
    }

    private static boolean samePathAndQuery(URI a, URI b) {
        return String.valueOf(a.getRawPath()).equals(String.valueOf(b.getRawPath())) && String.valueOf(a.getRawQuery()).equals(String.valueOf(b.getRawQuery()));
    }

    // ================================================================== in collected content

    /** The item with every short link in its text, answers and comments followed by where it really leads. */
    public Question expand(Question q) {
        if (!options.enabled() || q == null) {
            return q;
        }
        List<Comment> comments = q.comments().stream().map(c -> new Comment(c.id(), c.author(), expandIn(c.body()), c.createdAt())).toList();
        List<Answer> answers = q.answers().stream().map(a -> a.withBody(expandIn(a.body()), a.bodyFormat(),
                a.comments().stream().map(c -> new Comment(c.id(), c.author(), expandIn(c.body()), c.createdAt())).toList())).toList();
        return q.withContent(expandIn(q.body()), q.bodyFormat(), comments, answers);
    }

    /**
     * Markdown with short links expanded: {@code [text](go/x)} points at the real address, and a short link written
     * out ({@code go/x}, {@code https://bit.ly/x}) becomes {@code [go/x](real address)}, so the short name people know
     * stays visible and the real address is searchable. Code is left alone; unknown links stay as they are.
     */
    public String expandIn(String markdown) {
        if (!options.enabled() || markdown == null || markdown.isEmpty() || !mayContainShortLink(markdown)) {
            return markdown;
        }
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            String t = line.stripLeading();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                if (!inFence) {
                    out.append(expandText(chunk.toString()));
                    chunk.setLength(0);
                }
                inFence = !inFence;
                out.append(line).append('\n');
            } else if (inFence) {
                out.append(line).append('\n');
            } else {
                chunk.append(line).append('\n');
            }
        }
        out.append(expandText(chunk.toString()));
        String result = out.toString();
        return markdown.endsWith("\n") ? result : result.substring(0, Math.max(0, result.length() - 1));
    }

    private boolean mayContainShortLink(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String h : hosts()) {
            if (lower.contains(h + "/")) {
                return true;
            }
        }
        return lower.contains("://go.") || lower.contains("://goto.") || lower.contains("://golinks.")
                || Pattern.compile("https?://[a-z0-9\\-]+[/:]").matcher(lower).find(); // http://casc-docs/...
    }

    private String expandText(String text) {
        if (text.isEmpty()) {
            return text;
        }
        Matcher m = linkPattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group("code") != null) {
                replacement = m.group();
            } else if (m.group("mdlink") != null) {
                String target = m.group("target");
                String real = isShortLink(target) ? resolve(target).target() : null;
                replacement = real == null ? m.group() : "[" + m.group("text") + "](" + real + m.group("rest") + ")";
            } else if (m.group("auto") != null) {
                String link = m.group("auto");
                String real = isShortLink(link) ? resolve(link).target() : null;
                replacement = real == null ? m.group() : "[" + link + "](" + real + ")";
            } else {
                String raw = m.group("url") != null ? m.group("url") : m.group("bare");
                String link = raw.replaceAll("[.,;:!?]+$", "");
                String trailing = raw.substring(link.length());
                String real = isShortLink(link) ? resolve(link).target() : null;
                replacement = real == null ? m.group() : "[" + link + "](" + real + ")" + trailing;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The short links written in a text (also those already expanded as [go/x](real address)), in order, without repeats. */
    public List<String> findIn(String text) {
        List<String> found = new ArrayList<>();
        if (text == null || text.isEmpty() || !mayContainShortLink(text)) {
            return found;
        }
        Matcher m = linkPattern.matcher(text);
        while (m.find()) {
            List<String> candidates = new ArrayList<>();
            if (m.group("mdlink") != null) {
                // [go/vpn](real address) was expanded already: the short link is read, not its address a second time
                candidates.add(isShortLink(m.group("text").strip()) ? m.group("text") : m.group("target"));
            } else if (m.group("auto") != null) {
                candidates.add(m.group("auto"));
            } else if (m.group("url") != null || m.group("bare") != null) {
                candidates.add((m.group("url") != null ? m.group("url") : m.group("bare")).replaceAll("[.,;:!?]+$", ""));
            }
            for (String c : candidates) {
                String t = c == null ? "" : c.strip();
                if (isShortLink(t) && !found.contains(t)) {
                    found.add(t);
                }
            }
        }
        return found;
    }

    // ================================================================== in settings

    /**
     * Replaces short links in settings values (a whole value, or one entry of a comma-separated list) with where they
     * lead. Returns, per changed key, a sentence for the screen; values that could not be looked up are left as typed.
     */
    public Map<String, String> expandSettings(Map<String, String> values, Iterable<String> keys) {
        Map<String, String> notes = new LinkedHashMap<>();
        if (!options.enabled()) {
            return notes;
        }
        for (String key : keys) {
            String value = values.get(key);
            if (value == null || value.isBlank() || key.endsWith("Env") || key.equals("network.shortLinkHosts") || key.startsWith("storage.")
                    || key.endsWith("File") || key.endsWith("Dir") || key.endsWith("Dirs") || key.endsWith("curlPath")
                    || key.startsWith("sync.") || key.equals("source.type") || key.equals("source.links.list") || key.equals("source.systemId")
                    || key.startsWith("network.proxy") || key.equals("network.noProxy") || key.startsWith("api.key")) {
                continue;
            }
            boolean addressKey = key.toLowerCase(Locale.ROOT).endsWith("url");
            List<String> parts = new ArrayList<>();
            List<String> said = new ArrayList<>();
            boolean changed = false;
            for (String part : value.split(",", -1)) {
                String p = part.strip();
                if (isShortLink(p)) {
                    Lookup l = resolve(p);
                    if (l.found()) {
                        parts.add(l.target());
                        said.add(p + " leads to " + l.target());
                        changed = true;
                        continue;
                    }
                    said.add(p + " could not be expanded: " + l.problem());
                }
                if (addressKey && schemeGuessed(p) && webAddress(p) != null) {
                    // An address typed without http:// or https:// (casc-docs/, wiki.corp.com): use the one that answers.
                    String fixed = withScheme(p);
                    parts.add(fixed);
                    said.add(p + " is used as " + fixed);
                    changed = true;
                    continue;
                }
                parts.add(p);
            }
            if (changed) {
                values.put(key, String.join(",", parts));
            }
            if (!said.isEmpty()) {
                notes.put(key, (said.stream().allMatch(x -> x.contains(" is used as ")) ? "Address: " : "Short link: ")
                        + String.join("; ", said) + (changed ? ". The real address is used." : "."));
            }
        }
        return notes;
    }

    /**
     * An address typed without a scheme, with the scheme that works: https when http is not answered or only sends
     * you on to https on the same site, otherwise http (many intranet sites and short links are plain http).
     */
    String withScheme(String typed) {
        URI http = webAddress(typed);
        String asTyped = typed.strip();
        try {
            Hop answer = fetcher.fetch(http);
            if (answer.status() >= 300 && answer.status() < 400 && answer.location() != null) {
                URI to = http.resolve(answer.location().strip());
                if ("https".equalsIgnoreCase(to.getScheme()) && http.getHost().equalsIgnoreCase(to.getHost())) {
                    return "https://" + asTyped;
                }
            }
            return "http://" + asTyped;
        } catch (IOException | RuntimeException e) {
            try {
                fetcher.fetch(withHttps(http));
                return "https://" + asTyped;
            } catch (IOException | RuntimeException again) {
                return "http://" + asTyped;
            }
        }
    }

    // ================================================================== remembering answers between runs

    /** Links that were found, to keep for the next run (problems are retried next time). */
    public Map<String, String> known() {
        Map<String, String> out = new LinkedHashMap<>();
        cache.forEach((k, v) -> {
            if (v.found()) {
                out.put(k, v.target());
            }
        });
        return out;
    }

    public void remember(Map<String, String> known) {
        if (known != null) {
            known.forEach((k, v) -> {
                URI u = k == null ? null : toUri(k);
                if (u != null && v != null) {
                    cache.putIfAbsent(u.toString(), new Lookup(u.toString(), v, null));
                }
            });
        }
    }

    // ================================================================== HTTP

    private static Fetcher httpFetcher(Options options, Function<String, String> env) {
        HttpClient.Builder direct = HttpClient.newBuilder().connectTimeout(options.timeout()).followRedirects(HttpClient.Redirect.NEVER);
        HttpClient.Builder proxied = HttpClient.newBuilder().connectTimeout(options.timeout()).followRedirects(HttpClient.Redirect.NEVER);
        if (options.caCertFile() != null) {
            try {
                javax.net.ssl.SSLContext ctx = HttpSourceClient.sslContextFor(options.caCertFile());
                direct.sslContext(ctx);
                proxied.sslContext(ctx);
            } catch (RuntimeException ignored) {
                // the default trust store is used
            }
        }
        ProxyConfig proxy = options.proxy().orEnvironment(env);
        boolean viaProxy = proxy.configured();
        if (viaProxy) {
            proxied.proxy(proxy.selector());
            java.net.Authenticator auth = proxy.authenticator(env);
            if (auth != null) {
                proxied.authenticator(auth);
            }
        }
        HttpClient directClient = direct.build();
        HttpClient proxiedClient = viaProxy ? proxied.build() : directClient;
        return uri -> {
            // A company short-link service (go/xyz) is on the intranet: try it directly when the proxy cannot reach it.
            boolean intranet = uri.getHost() != null && !uri.getHost().contains(".");
            try {
                return send(intranet ? directClient : proxiedClient, uri, options.timeout());
            } catch (IOException e) {
                if (proxiedClient == directClient) {
                    throw e;
                }
                return send(intranet ? proxiedClient : directClient, uri, options.timeout());
            }
        };
    }

    private static Hop send(HttpClient client, URI uri, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "QuestionCrawler/0.1 (short link lookup)")
                .header("Accept", "text/html,*/*;q=0.5")
                .GET().build();
        try {
            HttpResponse<InputStream> r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = r.body()) {
                body = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            }
            return new Hop(r.statusCode(), r.headers().firstValue("Location").orElse(null), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
