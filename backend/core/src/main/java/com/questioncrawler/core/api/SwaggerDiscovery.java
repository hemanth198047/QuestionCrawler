package com.questioncrawler.core.api;

import com.questioncrawler.core.source.SourceException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts from the address of a Swagger UI page (for example https://qa.company.com/swagger/index.html), finds the Swagger/OpenAPI
 * JSON document(s) behind it, and returns every endpoint. The page itself only draws the documentation; the data is in a JSON
 * file the page references (Swashbuckle: /swagger/v1/swagger.json), which is looked up in the page, in its small script
 * files, and at the usual locations.
 */
public final class SwaggerDiscovery {

    /** How a URL is fetched (the application supplies a hardened client: proxy, certificates, host allow-list). */
    @FunctionalInterface
    public interface Fetcher {
        String get(String url) throws SourceException;
    }

    public record Discovery(ApiCatalog catalog, ApiCapabilities capabilities, List<String> tried) {}

    private static final Pattern URL_ASSIGNMENT = Pattern.compile("[\"']?url[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SPEC_LIKE = Pattern.compile("([\"'])((?:[^\"'\\s]*/)?(?:swagger|openapi)[^\"'\\s]*\\.json|[^\"'\\s]*/api-docs[^\"'\\s]*|[^\"'\\s]*/swagger/v[\\d.]+/swagger\\.json)\\1");
    private static final Pattern SCRIPT_SRC = Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final Fetcher fetcher;

    public SwaggerDiscovery(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Reads a Swagger/OpenAPI JSON text that was obtained in another way (for example saved from a browser that is signed in). */
    public static Discovery fromJson(String json, String label) throws SourceException {
        if (json == null || json.isBlank()) {
            throw new SourceException(SourceException.Kind.FATAL, "The documentation file is empty.");
        }
        if (json.stripLeading().startsWith("openapi:") || json.stripLeading().startsWith("swagger:")) {
            throw new SourceException(SourceException.Kind.FATAL, "That file is YAML. Only JSON Swagger/OpenAPI documents are supported: save the JSON version (the link under the title, often swagger.json).");
        }
        ApiCatalog catalog = tryParse(json, label);
        if (catalog == null) {
            throw new SourceException(SourceException.Kind.FATAL, "That file is not a Swagger/OpenAPI JSON document (it has no 'paths'). "
                    + "Open the JSON address in your browser, save that page as swagger.json, and use that file.");
        }
        return new Discovery(catalog, ApiCapabilities.analyze(catalog), List.of(label));
    }

    public Discovery discover(String swaggerUrl) throws SourceException {
        if (swaggerUrl == null || swaggerUrl.isBlank()) {
            throw new SourceException(SourceException.Kind.FATAL, "No API documentation address was given.");
        }
        URI index;
        try {
            index = URI.create(swaggerUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new SourceException(SourceException.Kind.FATAL, "That is not a valid web address: " + swaggerUrl);
        }
        List<String> tried = new ArrayList<>();
        Map<String, ApiCatalog> found = new LinkedHashMap<>();

        String body = fetch(index.toString(), tried);
        if (body != null) {
            ApiCatalog direct = tryParse(body, index.toString());
            if (direct != null) {
                found.put(index.toString(), direct);
            } else if (body.stripLeading().startsWith("openapi:") || body.stripLeading().startsWith("swagger:")) {
                throw new SourceException(SourceException.Kind.FATAL, "That address returns a YAML document. Only JSON Swagger/OpenAPI documents are supported: "
                        + "open the Swagger page and use the JSON link under its title (often ending in swagger.json).");
            } else {
                for (String candidate : referencedSpecs(body, index, tried)) {
                    addIfSpec(candidate, tried, found);
                }
            }
        }
        if (found.isEmpty()) {
            for (String candidate : conventional(index)) {
                addIfSpec(candidate, tried, found);
                if (!found.isEmpty()) {
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            throw new SourceException(SourceException.Kind.NOT_FOUND, "We reached the address but could not find the Swagger/OpenAPI JSON document behind it. "
                    + "Open the page in your browser, click the link right under the title (often /swagger/v1/swagger.json) and use that address instead. "
                    + "Addresses we tried: " + String.join(", ", tried));
        }
        ApiCatalog merged = merge(found);
        return new Discovery(merged, ApiCapabilities.analyze(merged), tried);
    }

    // ------------------------------------------------------------------ finding the document

    private List<String> referencedSpecs(String body, URI index, List<String> tried) throws SourceException {
        Set<String> out = new LinkedHashSet<>();
        collect(body, index, out);
        Matcher s = SCRIPT_SRC.matcher(body);
        while (s.find()) {
            String src = s.group(1);
            if (src.matches("(?i).*(initializer|swagger-config|configuration|index)\\.js.*") || src.toLowerCase().contains("init")) {
                String script = fetch(resolve(index, src), tried);
                if (script != null) {
                    collect(script, index, out);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static void collect(String text, URI base, Set<String> out) {
        Matcher m = URL_ASSIGNMENT.matcher(text);
        while (m.find()) {
            String v = m.group(1);
            if (v.toLowerCase().matches(".*(swagger|openapi|api-docs|\\.json).*") && !v.toLowerCase().contains("petstore")) {
                out.add(resolve(base, v));
            }
        }
        Matcher l = SPEC_LIKE.matcher(text);
        while (l.find()) {
            String v = l.group(2);
            if (!v.toLowerCase().contains("petstore")) {
                out.add(resolve(base, v));
            }
        }
    }

    private static List<String> conventional(URI index) {
        String origin = index.getScheme() + "://" + index.getAuthority();
        String path = index.getPath() == null ? "" : index.getPath();
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        List<String> c = new ArrayList<>();
        for (String v : List.of("v1", "v2", "v2.3", "v3")) {
            c.add(origin + dir + "/" + v + "/swagger.json");
        }
        c.add(origin + dir + "/swagger.json");
        c.add(origin + "/swagger/v1/swagger.json");
        c.add(origin + "/swagger/v2.3/swagger.json");
        c.add(origin + "/swagger.json");
        c.add(origin + "/v3/api-docs");
        c.add(origin + "/api-docs");
        c.add(origin + "/openapi.json");
        return new ArrayList<>(new LinkedHashSet<>(c));
    }

    private void addIfSpec(String url, List<String> tried, Map<String, ApiCatalog> found) throws SourceException {
        if (found.containsKey(url)) {
            return;
        }
        String body = fetch(url, tried);
        if (body != null) {
            ApiCatalog catalog = tryParse(body, url);
            if (catalog != null) {
                found.put(url, catalog);
            }
        }
    }

    /** null when the address cannot be fetched (a 404 is normal while probing); authentication problems are real errors. */
    private String fetch(String url, List<String> tried) throws SourceException {
        tried.add(url);
        try {
            return fetcher.get(url);
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED) {
                throw e;
            }
            return null;
        }
    }

    private static ApiCatalog tryParse(String body, String url) {
        if (!OpenApiParser.looksLikeSpec(body)) {
            return null;
        }
        try {
            return OpenApiParser.parse(body, url);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ merging several documents

    private static ApiCatalog merge(Map<String, ApiCatalog> found) {
        List<ApiCatalog> all = new ArrayList<>(found.values());
        ApiCatalog primary = all.stream().filter(c -> c.find("GET", "/questions").isPresent()).findFirst().orElse(all.get(0));
        if (all.size() == 1) {
            return primary;
        }
        Map<String, ApiCatalog.Operation> ops = new LinkedHashMap<>();
        List<String> urls = new ArrayList<>();
        List<ApiCatalog.SecurityScheme> security = new ArrayList<>(primary.security());
        List<String> warnings = new ArrayList<>(primary.warnings());
        for (ApiCatalog c : all) {
            urls.addAll(c.specUrls());
            c.operations().forEach(o -> ops.putIfAbsent(o.method() + " " + o.path(), o));
            c.security().stream().filter(s -> security.stream().noneMatch(x -> x.id().equals(s.id()))).forEach(security::add);
        }
        warnings.add(all.size() + " documents were found and combined: " + String.join(", ", urls));
        return new ApiCatalog(primary.title(), primary.version(), urls, primary.suggestedBaseUrl(), security, new ArrayList<>(ops.values()), warnings);
    }

    private static String resolve(URI base, String ref) {
        try {
            return base.resolve(ref).toString();
        } catch (IllegalArgumentException e) {
            return ref;
        }
    }
}
