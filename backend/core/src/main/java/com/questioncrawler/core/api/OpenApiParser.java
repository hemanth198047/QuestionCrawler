package com.questioncrawler.core.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.api.ApiCatalog.Operation;
import com.questioncrawler.core.api.ApiCatalog.Parameter;
import com.questioncrawler.core.api.ApiCatalog.SecurityScheme;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads Swagger 2.0 and OpenAPI 3.x JSON documents into an {@link ApiCatalog}. YAML is not supported. */
public final class OpenApiParser {

    private static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch", "head", "options");

    private OpenApiParser() {}

    public static boolean looksLikeSpec(String body) {
        if (body == null) {
            return false;
        }
        try {
            JsonNode n = Json.MAPPER.readTree(body);
            return n.isObject() && (n.has("openapi") || n.has("swagger")) && n.has("paths");
        } catch (IOException e) {
            return false;
        }
    }

    public static ApiCatalog parse(String json, String specUrl) throws IOException {
        JsonNode root = Json.MAPPER.readTree(json);
        if (!root.isObject() || !(root.has("openapi") || root.has("swagger")) || !root.has("paths")) {
            throw new IOException("This is not a Swagger / OpenAPI document (no 'openapi' or 'swagger' and 'paths').");
        }
        List<String> warnings = new ArrayList<>();
        boolean v3 = root.has("openapi");
        List<Operation> operations = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = root.get("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            JsonNode pathItem = resolve(root, p.getValue());
            List<Parameter> shared = parameters(root, pathItem.path("parameters"), v3);
            for (String m : METHODS) {
                JsonNode op = pathItem.get(m);
                if (op == null) {
                    continue;
                }
                List<Parameter> all = new ArrayList<>(shared);
                for (Parameter own : parameters(root, op.path("parameters"), v3)) {
                    all.removeIf(x -> x.name().equals(own.name()) && x.in().equals(own.in()));
                    all.add(own);
                }
                List<String> tags = new ArrayList<>();
                op.path("tags").forEach(t -> tags.add(t.asText()));
                List<String> codes = new ArrayList<>();
                op.path("responses").fieldNames().forEachRemaining(codes::add);
                operations.add(new Operation(m.toUpperCase(Locale.ROOT), p.getKey(), op.path("operationId").asText(null),
                        firstNonBlank(op.path("summary").asText(null), op.path("description").asText(null)), tags, all, codes,
                        op.path("deprecated").asBoolean(false)));
            }
        }
        if (operations.isEmpty()) {
            warnings.add("The document lists no operations.");
        }
        return new ApiCatalog(root.path("info").path("title").asText("API"), root.path("info").path("version").asText(""),
                specUrl == null ? List.of() : List.of(specUrl), suggestedBase(root, v3), security(root, v3), operations, warnings);
    }

    // ------------------------------------------------------------------ parameters and $ref

    private static List<Parameter> parameters(JsonNode root, JsonNode array, boolean v3) {
        List<Parameter> out = new ArrayList<>();
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode raw : array) {
            JsonNode n = resolve(root, raw);
            if (!n.has("name")) {
                continue;
            }
            JsonNode schema = v3 ? resolve(root, n.path("schema")) : n;
            List<String> allowed = new ArrayList<>();
            schema.path("enum").forEach(e -> allowed.add(e.asText()));
            out.add(new Parameter(n.path("name").asText(), n.path("in").asText("query"), n.path("required").asBoolean(false),
                    schema.path("type").asText(""), n.path("description").asText(""), allowed));
        }
        return out;
    }

    /** Follows "$ref": "#/components/parameters/X" style local references (a few levels). */
    private static JsonNode resolve(JsonNode root, JsonNode node) {
        JsonNode current = node;
        for (int i = 0; i < 6 && current != null && current.has("$ref"); i++) {
            String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                return current;
            }
            JsonNode target = root.at(ref.substring(1));
            if (target.isMissingNode()) {
                return current;
            }
            current = target;
        }
        return current == null ? Json.MAPPER.createObjectNode() : current;
    }

    // ------------------------------------------------------------------ security and servers

    private static List<SecurityScheme> security(JsonNode root, boolean v3) {
        List<SecurityScheme> out = new ArrayList<>();
        JsonNode defs = v3 ? root.path("components").path("securitySchemes") : root.path("securityDefinitions");
        Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode d = resolve(root, e.getValue());
            String type = d.path("type").asText("");
            if (type.equals("basic")) { // Swagger 2.0 spelling of http basic
                out.add(new SecurityScheme(e.getKey(), "http", null, null, "basic", d.path("description").asText("")));
            } else {
                out.add(new SecurityScheme(e.getKey(), type, d.path("in").asText(null), d.path("name").asText(null),
                        d.path("scheme").asText(null), d.path("description").asText("")));
            }
        }
        return out;
    }

    private static String suggestedBase(JsonNode root, boolean v3) {
        if (v3) {
            JsonNode servers = root.path("servers");
            if (servers.isArray() && !servers.isEmpty()) {
                String url = servers.get(0).path("url").asText("");
                return url.isBlank() || url.equals("/") ? null : url;
            }
            return null;
        }
        String host = root.path("host").asText("");
        if (host.isBlank()) {
            return root.path("basePath").asText(null);
        }
        String scheme = "https";
        JsonNode schemes = root.path("schemes");
        if (schemes.isArray() && !schemes.isEmpty()) {
            scheme = schemes.get(0).asText("https");
        }
        return scheme + "://" + host + root.path("basePath").asText("");
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b == null ? "" : b;
    }
}
