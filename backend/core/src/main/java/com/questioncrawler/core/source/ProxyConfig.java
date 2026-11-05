package com.questioncrawler.core.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A company web proxy. Settings win; otherwise the usual HTTPS_PROXY / HTTP_PROXY / NO_PROXY environment variables are
 * used (Java does not read those by itself). Loopback addresses never go through the proxy.
 *
 * <p>Only HTTP proxies are supported, with optional Basic sign-in whose credentials come from environment variables
 * (named here, never stored). Proxies that need Windows sign-in (NTLM/Kerberos) need a local helper such as CNTLM.
 */
public record ProxyConfig(String host, int port, List<String> noProxy, String userEnv, String passwordEnv, boolean fromEnvironment) {

    public static final ProxyConfig NONE = new ProxyConfig(null, 0, List.of(), null, null, false);

    /** The proxy answered a CONNECT request with something other than 200. */
    public static final class ProxyException extends IOException {
        private final int status;

        public ProxyException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public ProxyConfig {
        noProxy = noProxy == null ? List.of() : List.copyOf(noProxy);
    }

    public boolean configured() {
        return host != null && !host.isBlank() && port > 0;
    }

    /**
     * Lenient parsing of what a person types: "webproxy.corp.com", "webproxy.corp.com:8080" or "http://webproxy.corp.com:8080/".
     * An explicit port field wins over a port inside the host text.
     */
    public static ProxyConfig parse(String hostText, String portText, String noProxyCsv, String userEnv, String passwordEnv) {
        String h = hostText == null ? "" : hostText.strip();
        int port = 0;
        if (h.contains("://")) {
            h = h.substring(h.indexOf("://") + 3);
        }
        int at = h.lastIndexOf('@');
        if (at >= 0) {
            h = h.substring(at + 1); // credentials in a URL are ignored on purpose
        }
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(h.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                port = 0;
            }
            h = h.substring(0, colon);
        }
        if (portText != null && !portText.isBlank()) {
            try {
                port = Integer.parseInt(portText.strip());
            } catch (NumberFormatException ignored) {
                // keep what we have; validation reports the bad port
            }
        }
        List<String> skip = new ArrayList<>();
        if (noProxyCsv != null) {
            for (String s : noProxyCsv.split("[,;\\s]+")) {
                if (!s.isBlank()) {
                    skip.add(s.strip());
                }
            }
        }
        return new ProxyConfig(h.isBlank() ? null : h, port, skip, blankToNull(userEnv), blankToNull(passwordEnv), false);
    }

    /** HTTPS_PROXY (or HTTP_PROXY) and NO_PROXY, in either letter case. */
    public static ProxyConfig fromEnvironment(Function<String, String> env) {
        String value = first(env, "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy");
        if (value == null) {
            return NONE;
        }
        ProxyConfig parsed = parse(value, null, first(env, "NO_PROXY", "no_proxy"), null, null);
        if (!parsed.configured() && parsed.host() != null) {
            parsed = new ProxyConfig(parsed.host(), value.toLowerCase(Locale.ROOT).startsWith("https://") ? 443 : 80, parsed.noProxy(), null, null, false);
        }
        return new ProxyConfig(parsed.host(), parsed.port(), parsed.noProxy(), null, null, true);
    }

    /** This proxy if configured, otherwise the one from the environment (if any). */
    public ProxyConfig orEnvironment(Function<String, String> env) {
        if (configured()) {
            return this;
        }
        ProxyConfig fromEnv = fromEnvironment(env);
        return fromEnv.configured() ? fromEnv : this;
    }

    /** True when this address must NOT use the proxy (loopback, or on the bypass list). */
    public boolean bypass(String targetHost) {
        if (targetHost == null) {
            return true;
        }
        String t = targetHost.toLowerCase(Locale.ROOT);
        if (t.equals("localhost") || t.startsWith("127.") || t.equals("::1") || t.equals("[::1]")) {
            return true;
        }
        for (String raw : noProxy) {
            String rule = raw.toLowerCase(Locale.ROOT);
            if (rule.equals("*") || rule.equals(t)) {
                return true;
            }
            if (rule.endsWith("*") && t.startsWith(rule.substring(0, rule.length() - 1))) {
                return true;
            }
            if (rule.startsWith("*.") && t.endsWith(rule.substring(1))) {
                return true;
            }
            if (rule.startsWith(".") && (t.endsWith(rule) || t.equals(rule.substring(1)))) {
                return true;
            }
        }
        return false;
    }

    public boolean appliesTo(String targetHost) {
        return configured() && !bypass(targetHost);
    }

    /** For java.net.http.HttpClient. */
    public ProxySelector selector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (!configured() || bypass(uri == null ? null : uri.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // the caller reports the failure
            }
        };
    }

    /** Basic proxy sign-in from environment variables, or null when none is configured. */
    public Authenticator authenticator(Function<String, String> env) {
        String user = credential(env, userEnv);
        String password = credential(env, passwordEnv);
        if (user == null || password == null) {
            return null;
        }
        // The JDK refuses Basic authentication through HTTPS tunnels unless this is cleared (JDK default: "Basic").
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(user, password.toCharArray()) : null;
            }
        };
    }

    /**
     * Opens a TCP tunnel through the proxy to host:port (HTTP CONNECT), which is how HTTPS traffic passes a web proxy.
     * The returned socket is connected to the target; the caller wraps it in TLS.
     */
    public Socket tunnel(String targetHost, int targetPort, int timeoutMillis, Function<String, String> env) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            String user = credential(env, userEnv);
            String password = credential(env, passwordEnv);
            StringBuilder req = new StringBuilder("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n")
                    .append("Proxy-Connection: Keep-Alive\r\n");
            if (user != null && password != null) {
                req.append("Proxy-Authorization: Basic ")
                        .append(Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8))).append("\r\n");
            }
            req.append("\r\n");
            socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String head = readHead(socket.getInputStream());
            String statusLine = head.lines().findFirst().orElse("");
            String[] parts = statusLine.split(" ", 3);
            int status = parts.length > 1 ? Integer.parseInt(parts[1].strip()) : -1;
            if (status != 200) {
                throw new ProxyException(status, "The proxy answered " + statusLine.strip());
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a failed connection
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("The proxy sent an unreadable reply", e);
        }
    }

    public String describe() {
        return host + ":" + port + (fromEnvironment ? " (from the HTTPS_PROXY environment variable)" : " (from settings)");
    }

    // ------------------------------------------------------------------ helpers

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && head.length() < 16_384) {
            head.append((char) b);
            int n = head.length();
            if (n >= 4 && head.charAt(n - 4) == '\r' && head.charAt(n - 3) == '\n' && head.charAt(n - 2) == '\r' && head.charAt(n - 1) == '\n') {
                break;
            }
        }
        if (head.length() == 0) {
            throw new IOException("The proxy closed the connection without answering");
        }
        return head.toString();
    }

    private static String credential(Function<String, String> env, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String v = env.apply(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String first(Function<String, String> env, String... names) {
        for (String n : names) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
