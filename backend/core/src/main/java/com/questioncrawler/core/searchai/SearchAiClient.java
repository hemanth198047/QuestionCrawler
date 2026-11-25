package com.questioncrawler.core.searchai;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the search-ai REST API (base path {@code /searchapi}). Authentication is Kerberos "negotiate" with a
 * persistent cookie jar, exactly like the documented curl helper:
 * <pre>curl -sS --negotiate -u : --location-trusted -c JAR -b JAR ...</pre>
 * curl is used (not java.net.http) because the JDK HTTP client has no Kerberos/SPNEGO support for this flow.
 * <p>{@code GET /whoami} is called once before the first POST: it seeds the cookie jar, and a POST on a fresh session
 * otherwise fails with HTTP 500.
 * <p>Parameters travel on the query string. Two silent-failure traps are avoided by construction: the filter key is
 * {@code use_case} (not {@code usecases}) and paging uses {@code start} (not {@code page}/{@code currentPage}).
 */
public final class SearchAiClient {

    private static final String STATUS_MARKER = "\n__QC_HTTP_STATUS__:";
    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int ERROR_SNIPPET = 300;

    private final SearchAiOptions options;
    private final CurlRunner curl;
    private volatile boolean sessionSeeded;
    /** Cookie header from "sign in with my browser"; sent from a private temp file, never on the command line. */
    private volatile String browserCookie;

    public SearchAiClient(SearchAiOptions options) {
        this(options, new CurlRunner.Process());
    }

    public SearchAiClient(SearchAiOptions options, CurlRunner curl) {
        this.options = options;
        this.curl = curl;
    }

    public SearchAiOptions options() {
        return options;
    }

    /** Use the session of a browser sign-in for every call from now on. */
    public void useBrowserCookie(String cookieHeader) {
        this.browserCookie = cookieHeader;
        this.sessionSeeded = true;
    }

    public boolean usesBrowserSession() {
        return browserCookie != null;
    }

    /** True when search-ai accepts this Cookie header (one /whoami call that is neither refused nor redirected to a login page). */
    public boolean accepts(String cookieHeader) {
        try {
            call("GET", "/whoami", new LinkedHashMap<>(), false, cookieHeader);
            return true;
        } catch (SearchAiException e) {
            return false;
        }
    }

    /** GET /whoami. Also seeds the cookie jar. */
    public SearchAiResponse whoami() {
        SearchAiResponse response = call("GET", "/whoami", new LinkedHashMap<>(), false);
        sessionSeeded = true;
        return response;
    }

    /** POST /search: raw search results. {@code rows}/{@code start} null = server default (rows falls back to searchai.rows). */
    public SearchAiResponse search(String query, Integer rows, Integer start, String useCase, String sortBy) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", requireQuery(query));
        p.put("rows", String.valueOf(positive(rows == null ? Integer.valueOf(options.defaultRows()) : rows, "rows", 1)));
        if (start != null) {
            p.put("start", String.valueOf(positive(start, "start", 0)));
        }
        put(p, "use_case", useCase == null || useCase.isBlank() ? options.defaultUseCase() : useCase);
        put(p, "sort_by", sortBy);
        return call("POST", "/search", p, false);
    }

    /** POST /genairesponse: AI-synthesised answer plus sources. Needs a JSON body (empty object) or the server answers 422. */
    public SearchAiResponse genAiResponse(String query, String useCase) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", requireQuery(query));
        put(p, "use_case", useCase == null || useCase.isBlank() ? options.defaultUseCase() : useCase);
        return call("POST", "/genairesponse", p, true);
    }

    /** POST /summary: summary of one hit; {@code chunkId} comes from a previous /search response. */
    public SearchAiResponse summary(String query, String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkid is required: take it from a previous search result.");
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", requireQuery(query));
        p.put("chunkid", chunkId.strip());
        return call("POST", "/summary", p, false);
    }

    /** GET /usecases: the available use_case filters. */
    public SearchAiResponse useCases() {
        return call("GET", "/usecases", new LinkedHashMap<>(), false);
    }

    // ------------------------------------------------------------------ internals

    private SearchAiResponse call(String method, String endpoint, Map<String, String> params, boolean jsonBody) {
        return call(method, endpoint, params, jsonBody, browserCookie);
    }

    private SearchAiResponse call(String method, String endpoint, Map<String, String> params, boolean jsonBody, String cookie) {
        if (!endpoint.equals("/whoami")) {
            ensureSession();
        }
        String url = options.baseUri() + endpoint + queryString(params);
        // With a browser session no redirect is followed: the cookie must never be sent on to another host (the login page).
        List<String> command = new ArrayList<>(List.of(options.curlCommand(), "-sS", "--negotiate", "-u", ":"));
        if (cookie == null) {
            command.add("--location-trusted");
        }
        command.addAll(List.of(
                "-c", options.cookieJar().toString(), "-b", options.cookieJar().toString(),
                "--max-time", String.valueOf(options.timeoutSeconds()),
                "-H", "Accept: application/json",
                "-w", STATUS_MARKER + "%{http_code}"));
        Path headerFile = null;
        if (cookie != null) {
            headerFile = writeCookieHeader(cookie);
            command.addAll(List.of("-H", "@" + headerFile));
        }
        if (method.equals("POST")) {
            command.add("-X");
            command.add("POST");
        }
        if (jsonBody) {
            command.addAll(List.of("-H", "Content-Type: application/json", "-d", "{}"));
        }
        command.add(url);
        prepareCookieJar();

        CurlRunner.Result result;
        try {
            result = curl.run(command, options.timeoutSeconds());
        } finally {
            if (headerFile != null) {
                try {
                    Files.deleteIfExists(headerFile);
                } catch (IOException ignored) {
                    // a leftover owner-only temp file; the next call replaces it
                }
            }
        }
        if (result.exitCode() != 0) {
            throw new SearchAiException(curlFailure(result), 0);
        }
        String stdout = result.stdout();
        int marker = stdout.lastIndexOf(STATUS_MARKER);
        if (marker < 0) {
            throw new SearchAiException("curl returned no HTTP status for " + method + " " + endpoint + ".");
        }
        String body = stdout.substring(0, marker);
        int status;
        try {
            status = Integer.parseInt(stdout.substring(marker + STATUS_MARKER.length()).strip());
        } catch (NumberFormatException e) {
            throw new SearchAiException("curl returned an unreadable HTTP status for " + method + " " + endpoint + ".");
        }
        if (status >= 300 && status < 400 && cookie != null) {
            browserCookie = browserCookie != null && browserCookie.equals(cookie) ? null : browserCookie; // expired: ask for a new sign-in
            throw new SearchAiException(method + " " + endpoint + " was sent to a sign-in page (HTTP " + status + "): the browser sign-in has expired.", 401);
        }
        if (status == 401 && cookie != null && cookie.equals(browserCookie)) {
            browserCookie = null;
        }
        if (status < 200 || status >= 300) {
            throw new SearchAiException(statusFailure(method, endpoint, status, body), status);
        }
        JsonNode json = null;
        String text = null;
        try {
            json = body.isBlank() ? null : Json.MAPPER.readTree(stripSecurityPrefix(body));
        } catch (IOException e) {
            // Not JSON (for example a sign-in page): keep the text so the caller can see what came back.
        }
        if (json == null) {
            text = body;
            String head = body.stripLeading().toLowerCase(java.util.Locale.ROOT);
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                // A web page where data was expected is the company sign-in page (the redirect was followed and ended there).
                throw new SearchAiException(method + " " + endpoint + " answered with a web page, which is usually the company sign-in page.", 401);
            }
        }
        return new SearchAiResponse(endpoint, method, new LinkedHashMap<>(params), status, json, text);
    }

    private static Path headerFileIn(Path dir) throws IOException {
        return Files.createTempFile(dir, ".qc-hdr-", ".txt"); // owner-only on POSIX systems
    }

    private Path writeCookieHeader(String cookie) {
        try {
            Path dir = options.cookieJar().toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path f = headerFileIn(dir);
            Files.writeString(f, "Cookie: " + cookie.replace("\r", "").replace("\n", "") + "\n", StandardCharsets.UTF_8);
            return f;
        } catch (IOException e) {
            throw new SearchAiException("Cannot prepare the sign-in header file: " + e.getMessage(), 0, e);
        }
    }

    private synchronized void ensureSession() {
        if (!sessionSeeded) {
            try {
                whoami();
            } catch (SearchAiException e) {
                // Refused or unreachable: that is the real problem, report it. Any other answer (for example 404 because
                // this server has no /whoami) must not stop the search itself, which then shows its own result.
                if (e.httpStatus() == 0 || e.httpStatus() == 401 || e.httpStatus() == 403) {
                    throw e;
                }
            }
        }
    }

    private void prepareCookieJar() {
        Path jar = options.cookieJar();
        try {
            Files.createDirectories(jar.toAbsolutePath().getParent());
            if (!Files.exists(jar)) {
                try {
                    Files.createFile(jar, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException notPosix) {
                    Files.createFile(jar);
                }
            }
        } catch (IOException e) {
            throw new SearchAiException("Cannot prepare the cookie jar " + jar + ": " + e.getMessage()
                    + ". Change searchai.cookieJar to a writable file.", 0, e);
        }
    }

    private static String curlFailure(CurlRunner.Result r) {
        String err = r.stderr() == null ? "" : r.stderr().strip();
        String hint = switch (r.exitCode()) {
            case 6 -> " The host name could not be resolved: check searchai.baseUri and the network/VPN.";
            case 7 -> " Could not connect: check the network/VPN and any proxy (https_proxy).";
            case 28 -> " The request timed out: raise searchai.timeoutSeconds.";
            case 60 -> " The TLS certificate was not trusted: set CURL_CA_BUNDLE (or the curl --cacert default) to your organization's CA bundle. Verification is never switched off.";
            case 2, 4 -> " This curl was built without a needed feature (Kerberos/GSS-API is required for --negotiate).";
            default -> "";
        };
        return "curl failed (exit " + r.exitCode() + "): " + (err.isEmpty() ? "no message" : err) + "." + hint;
    }

    private static String statusFailure(String method, String endpoint, int status, String body) {
        String hint = switch (status) {
            case 401 -> " Not authenticated: on Linux/macOS run 'kinit' and retry; on Windows (SSPI) raise an IT ticket.";
            case 403 -> " Authenticated but not allowed to use this endpoint.";
            case 422 -> " The request was rejected as invalid (for /genairesponse a JSON body is required; a missing or misspelled parameter also causes this).";
            case 500 -> " Server error. On a fresh session this is what a POST without a prior /whoami returns; if it persists, delete the cookie jar and retry.";
            default -> "";
        };
        String snippet = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        if (snippet.length() > ERROR_SNIPPET) {
            snippet = snippet.substring(0, ERROR_SNIPPET) + "...";
        }
        return method + " " + endpoint + " answered HTTP " + status + "." + hint + (snippet.isEmpty() ? "" : " Answer: " + snippet);
    }

    private static String stripSecurityPrefix(String body) {
        String b = body.startsWith("﻿") ? body.substring(1) : body;
        return b.startsWith(")]}',") ? b.substring(5) : b;
    }

    private static String queryString(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        params.forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static void put(Map<String, String> p, String key, String value) {
        if (value != null && !value.isBlank()) {
            p.put(key, value.strip());
        }
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank.");
        }
        String q = query.strip();
        return q.length() > MAX_QUERY_LENGTH ? q.substring(0, MAX_QUERY_LENGTH) : q;
    }

    private static int positive(int value, String name, int min) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be at least " + min + ".");
        }
        return value;
    }
}
