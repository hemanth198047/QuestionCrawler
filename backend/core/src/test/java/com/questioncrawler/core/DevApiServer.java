package com.questioncrawler.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.search.AgeFilter;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.service.Workspace;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.storage.StoreException;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncReport;
import com.questioncrawler.core.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Test-scope stand-in for the Spring controllers: the same routes over the same {@link Workspace}. It lets the UI be
 * run and inspected without Spring (used for UI development and screenshots here). The Spring layer must mirror it.
 * Usage: DevApiServer [port] [configFile]
 */
public final class DevApiServer {

    private final Workspace workspace;

    DevApiServer(Workspace workspace) {
        this.workspace = workspace;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path config = args.length > 1 ? Path.of(args[1]) : null;
        Workspace w = new Workspace(config, Map.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        DevApiServer api = new DevApiServer(w);
        server.createContext("/api", api::handle);
        server.start();
        System.out.println("Dev API on http://127.0.0.1:" + port + " using " + w.configFile());
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        Object body;
        try {
            body = route(ex);
        } catch (IllegalArgumentException e) {
            status = 400;
            body = Map.of("detail", String.valueOf(e.getMessage()));
        } catch (IllegalStateException e) {
            status = 409;
            body = Map.of("detail", String.valueOf(e.getMessage()));
        } catch (StoreException e) {
            status = 503;
            body = Map.of("detail", String.valueOf(e.getMessage()));
        } catch (NotFound e) {
            status = 404;
            body = Map.of("detail", e.getMessage());
        } catch (RuntimeException e) {
            status = 500;
            body = Map.of("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static final class NotFound extends RuntimeException {
        NotFound(String m) { super(m); }
    }

    private Object route(HttpExchange ex) throws IOException {
        return dispatch(ex.getRequestMethod(), ex.getRequestURI().getPath(), query(ex.getRequestURI().getRawQuery()), ex);
    }

    private Object dispatch(String method, String path, Map<String, List<String>> q, HttpExchange ex) throws IOException {
        // Body is read lazily and only once.
        JsonNode body = null;
        if (!method.equals("GET") && !method.equals("DELETE")) {
            byte[] raw = ex.getRequestBody().readAllBytes();
            body = raw.length == 0 ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(raw);
        }
        var kb = workspace.kb();
        switch (method + " " + path) {
            case "GET /api/health": return Map.of("status", "UP", "application", "questioncrawler-dev", "javaVersion", Runtime.version().toString());
            case "GET /api/overview": return workspace.overview();
            case "GET /api/config": return workspace.configView();
            case "GET /api/config/presets": return com.questioncrawler.core.service.ConfigService.presets();
            case "POST /api/config/validate": return Map.of("issues", workspace.validate(stringMap(body.path("values"))), "preview", workspace.previewConfig(stringMap(body.path("values"))));
            case "PUT /api/config": return workspace.saveConfig(stringMap(body.path("values")));
            case "POST /api/api-docs/discover": return workspace.discoverApi(stringMap(body.path("values")));
            case "POST /api/tags/listing": return workspace.startTagListing(stringMap(body.path("values")));
            case "GET /api/tags/listing": return workspace.tagListing(intVal(first(q, "from"), 0));
            case "POST /api/tags/listing/cancel": workspace.cancelTagListing(); return Map.of("ok", true);
            case "POST /api/tags/search": return workspace.searchTags(stringMap(body.path("values")), body.path("q").asText(null));
            case "POST /api/api-docs/probe": return workspace.probeApi(stringMap(body.path("values")), body.path("path").asText(null));
            case "POST /api/api-docs/file": return workspace.saveApiDocs(body.path("content").asText(""));
            case "POST /api/topics/discover": return workspace.discoverTopics(body.path("k").asInt(0));
            case "POST /api/connection/check": return workspace.checkConnection(stringMap(body.path("values")));
            case "GET /api/connection/last": return workspace.lastConnection().orElse(null);
            case "GET /api/taxonomy": return workspace.taxonomyView();
            case "PUT /api/taxonomy": return workspace.saveTaxonomy(Json.MAPPER.treeToValue(body, Taxonomy.class));
            case "POST /api/taxonomy/reset": return workspace.resetTaxonomy();
            case "POST /api/taxonomy/try": {
                Taxonomy draft = body.has("taxonomy") && !body.get("taxonomy").isNull() ? Json.MAPPER.treeToValue(body.get("taxonomy"), Taxonomy.class) : null;
                List<String> tags = new ArrayList<>();
                body.path("tags").forEach(t -> tags.add(t.asText()));
                return workspace.tryClassify(draft, body.path("title").asText(""), body.path("body").asText(""), tags);
            }
            case "POST /api/reindex": return kb.reindex();
            case "GET /api/status": return kb.status();
            case "GET /api/categories": return kb.categories();
            case "GET /api/search": {
                AgeFilter age = new AgeFilter(first(q, "maxQuestionAge"), first(q, "minQuestionAge"), first(q, "maxAnswerAge"), first(q, "minAnswerAge"));
                String text = first(q, "q");
                if (text == null || text.isBlank()) throw new IllegalArgumentException("Query parameter 'q' must not be blank.");
                return kb.search(new SearchRequest(text, q.get("tag"), q.get("category"), intVal(first(q, "top"), 5), intVal(first(q, "maxChars"), 0), 0, age));
            }
            case "POST /api/sync": {
                var tags = new ArrayList<String>();
                body.path("tags").forEach(t -> tags.add(t.asText()));
                var options = workspace.config().syncOptions(tags, body.hasNonNull("tagMode") ? TagMode.valueOf(body.get("tagMode").asText().toUpperCase(Locale.ROOT)) : null,
                        body.hasNonNull("partialTags") ? body.get("partialTags").asBoolean() : null,
                        body.path("full").asBoolean(false) ? SyncMode.FULL : SyncMode.INCREMENTAL, body.hasNonNull("maxQuestionAge") ? body.get("maxQuestionAge").asText() : null);
                if (options.tags().isEmpty()) throw new IllegalArgumentException("No tags configured: add at least one tag in Settings.");
                if (first(q, "wait") != null && first(q, "wait").equals("true")) {
                    SyncReport r = workspace.runner().runBlocking(options);
                    return r;
                }
                if (!workspace.runner().start(options)) throw new IllegalStateException("A synchronization is already running.");
                return workspace.runner().state();
            }
            case "GET /api/sync/status": return workspace.runner().state();
            case "GET /api/sync/reports": {
                List<SyncReport> out = new ArrayList<>();
                kb.store().listReportIds().stream().limit(intVal(first(q, "limit"), 10)).forEach(id -> kb.store().readReport(id, SyncReport.class).ifPresent(out::add));
                return out;
            }
            default:
        }
        if (path.startsWith("/api/categories/") && method.equals("GET")) {
            AgeFilter age = new AgeFilter(first(q, "maxQuestionAge"), first(q, "minQuestionAge"), null, null);
            return kb.category(decode(path.substring("/api/categories/".length())), age).orElseThrow(() -> new NotFound("Unknown category"));
        }
        if (path.startsWith("/api/questions/")) {
            String rest = path.substring("/api/questions/".length());
            if (rest.endsWith("/categories")) {
                String id = decode(rest.substring(0, rest.length() - "/categories".length()));
                if (method.equals("PUT")) {
                    List<String> cats = new ArrayList<>();
                    body.path("categories").forEach(c -> cats.add(c.asText()));
                    kb.setOverride(id, cats, body.hasNonNull("note") ? body.get("note").asText() : null);
                } else if (method.equals("DELETE")) {
                    kb.clearOverride(id);
                }
                return kb.show(id).orElseThrow(() -> new NotFound("No question " + id));
            }
            return kb.show(decode(rest)).orElseThrow(() -> new NotFound("No question with id " + rest));
        }
        throw new NotFound("No route " + method + " " + path);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> m = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText()));
        }
        return m;
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return m;
    }

    private static String first(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null || v.isEmpty() || v.get(0).isBlank() ? null : v.get(0);
    }

    private static int intVal(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
