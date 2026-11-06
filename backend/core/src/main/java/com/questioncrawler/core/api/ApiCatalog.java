package com.questioncrawler.core.api;

import java.util.List;
import java.util.Locale;

/**
 * What a REST API publishes about itself (Swagger / OpenAPI): every endpoint with its parameters, and how it expects
 * to be authenticated. Read from the API's own documentation, never guessed.
 */
public record ApiCatalog(
        String title,
        String version,
        List<String> specUrls,
        /** Base address suggested by the document (servers / host + basePath), or null. */
        String suggestedBaseUrl,
        List<SecurityScheme> security,
        List<Operation> operations,
        List<String> warnings) {

    public record Parameter(String name, String in, boolean required, String type, String description, List<String> allowed) {
        public Parameter {
            allowed = allowed == null ? List.of() : List.copyOf(allowed);
        }
    }

    public record Operation(String method, String path, String operationId, String summary, List<String> tags,
                            List<Parameter> parameters, List<String> responseCodes, boolean deprecated) {
        public Operation {
            tags = tags == null ? List.of() : List.copyOf(tags);
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            responseCodes = responseCodes == null ? List.of() : List.copyOf(responseCodes);
        }

        public boolean hasParameter(String name) {
            return parameters.stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
        }
    }

    /** type: apiKey, http, oauth2, openIdConnect. {@code in} is query/header/cookie for apiKey. */
    public record SecurityScheme(String id, String type, String in, String name, String scheme, String description) {}

    public ApiCatalog {
        specUrls = specUrls == null ? List.of() : List.copyOf(specUrls);
        security = security == null ? List.of() : List.copyOf(security);
        operations = operations == null ? List.of() : List.copyOf(operations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    private static final java.util.regex.Pattern BASE_PREFIX = java.util.regex.Pattern.compile("^(/(api|v?\\d+(\\.\\d+)*))*$");

    /**
     * The operation whose path ends with the template. Path parameters match by position, not by name. The part in front
     * must look like a base path ("/api/2.3"), so "/questions" is never confused with "/users/{id}/questions".
     */
    public java.util.Optional<Operation> find(String method, String pathSuffix) {
        String want = normalize(pathSuffix);
        return operations.stream()
                .filter(o -> o.method().equalsIgnoreCase(method) && normalize(o.path()).endsWith(want))
                .min(java.util.Comparator
                        .comparingInt((Operation o) -> rank(normalize(o.path()).substring(0, normalize(o.path()).length() - want.length())))
                        .thenComparingInt(o -> o.path().length()));
    }

    private static int rank(String prefix) {
        if (BASE_PREFIX.matcher(prefix).matches()) {
            return 0;
        }
        return prefix.contains("{") ? 2 : 1;
    }

    /** "/questions/{ids}/answers" -> "/questions/{}/answers" */
    public static String normalize(String path) {
        return (path == null ? "" : path).toLowerCase(Locale.ROOT).replaceAll("\\{[^}/]*}", "{}").replaceAll("/+$", "");
    }
}
