package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.util.Json;
import com.questioncrawler.core.util.Times;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * First-class Confluence source: classic Content REST API + CQL search for Cloud and Server/DC.
 *
 * <p>Pages are collected as {@link Question} threads (empty answers in v1). Labels map to tags.
 * Auth is {@code basic-env} (Cloud email + API token) or Bearer {@code token} (Server/DC PAT) via
 * {@link HttpSourceClient}. Cookie scraping and HTML UI login are intentionally unsupported.
 *
 * <p>Jive and SharePoint are separate future {@code source.type} values — do not extend this adapter for them.
 */
public final class ConfluenceAdapter implements SourceAdapter {

    private static final DateTimeFormatter CQL_LASTMODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneOffset.UTC);

    private final SourceSettings settings;
    private final ConfluenceOptions confluence;
    private final HttpSourceClient client;
    private final Function<String, String> env;
    private final String apiRoot;
    private final String siteBase;
    private final List<String> notes = new ArrayList<>();
    private boolean lastmodifiedSupported = true;

    public ConfluenceAdapter(SourceSettings settings, ConfluenceOptions confluence, HttpSourceClient client) {
        this(settings, confluence, client, System::getenv);
    }

    public ConfluenceAdapter(SourceSettings settings, ConfluenceOptions confluence, HttpSourceClient client,
                             Function<String, String> env) {
        this.settings = settings;
        this.confluence = confluence == null ? ConfluenceOptions.DEFAULT : confluence;
        this.client = client;
        this.env = env;
        this.siteBase = trimSlash(settings.baseUrl());
        this.apiRoot = resolveApiRoot(this.siteBase, this.confluence);
    }

    @Override
    public String systemId() {
        return settings.systemId();
    }

    @Override
    public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override
    public boolean supportsModifiedSince() {
        return lastmodifiedSupported;
    }

    @Override
    public String ping() throws SourceException {
        JsonNode root = getJson(apiRoot + "/space?limit=1");
        int size = root.path("size").asInt(root.path("results").size());
        String edition = confluence.cloud() ? "Cloud" : "Server/DC";
        return "Confluence " + edition + " reachable at " + apiRoot + " (space listing size=" + size + ")";
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        String cql = buildCql(filter, modifiedSince);
        StringBuilder url = new StringBuilder(apiRoot).append("/content/search?cql=").append(enc(cql))
                .append("&limit=").append(confluence.pageSize())
                .append("&expand=").append(enc("version,history,space,metadata.labels"));
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.startsWith("start:")) {
                url.append("&start=").append(enc(cursor.substring("start:".length())));
            } else {
                url.append("&cursor=").append(enc(cursor));
            }
        }
        JsonNode root;
        try {
            root = getJson(url.toString());
        } catch (SourceException e) {
            if (modifiedSince != null && lastmodifiedSupported && looksLikeCqlReject(e)) {
                lastmodifiedSupported = false;
                notes.add("This Confluence build rejected CQL lastmodified; falling back to full listing with local timestamp skip.");
                return listQuestions(filter, null, cursor);
            }
            throw e;
        }
        List<QuestionSummary> items = new ArrayList<>();
        for (JsonNode node : results(root)) {
            String id = text(node, "id");
            if (id == null || id.isBlank()) {
                continue;
            }
            String modified = modifiedAt(node);
            if (modifiedSince != null && !lastmodifiedSupported && modified != null) {
                Instant since = Times.parse(modifiedSince).orElse(null);
                Instant when = Times.parse(modified).orElse(null);
                if (since != null && when != null && when.isBefore(since)) {
                    continue;
                }
            }
            items.add(new QuestionSummary(id, modified, browseUrl(node)));
        }
        return new Page<>(items, nextCursor(root, cursor, items.size()));
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        String expand = confluence.bodyExpand() + ",version,space,metadata.labels,history,ancestors";
        JsonNode node;
        try {
            node = getJson(apiRoot + "/content/" + enc(id) + "?expand=" + enc(expand));
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.NOT_FOUND || e.kind() == SourceException.Kind.GONE
                    || e.kind() == SourceException.Kind.FORBIDDEN) {
                throw e;
            }
            // Body expand may fail for restricted storage; retry with view HTML.
            if (!"body.view".equals(confluence.bodyExpand())) {
                node = getJson(apiRoot + "/content/" + enc(id) + "?expand=" + enc("body.view,version,space,metadata.labels,history,ancestors"));
            } else {
                throw e;
            }
        }
        String title = text(node, "title");
        String body = bodyHtml(node);
        if (body == null) {
            body = "";
            notes.add("Page " + id + " had no readable body.storage/view; stored title and metadata only.");
        }
        List<String> tags = labels(node);
        if (confluence.includeSpaceTag()) {
            String spaceKey = node.path("space").path("key").asText(null);
            if (spaceKey != null && !spaceKey.isBlank()) {
                String synthetic = "space:" + spaceKey;
                if (tags.stream().noneMatch(t -> t.equalsIgnoreCase(synthetic))) {
                    tags = new ArrayList<>(tags);
                    tags.add(synthetic);
                }
            }
        }
        String author = node.path("history").path("createdBy").path("displayName").asText(null);
        if (author == null || author.isBlank()) {
            author = node.path("history").path("createdBy").path("accountId").asText(null);
        }
        String created = iso(node.path("history").path("createdDate").asText(null));
        String modified = modifiedAt(node);
        List<Comment> comments = List.of();
        if (confluence.collectComments()) {
            try {
                comments = fetchComments(id);
            } catch (SourceException e) {
                notes.add("Comments for page " + id + " could not be collected (" + e.kind() + "); page body was kept.");
            }
        }
        return new Question(id, title == null ? "" : title, body, BodyFormat.HTML, browseUrl(node), tags, author, null,
                created, modified, comments, List.of());
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) {
        // Confluence is not Q&A: answers stay empty. Optional page comments are attached in fetchQuestion.
        return new Page<>(List.of(), null);
    }

    // ---------------------------------------------------------------- CQL / mapping

    String buildCql(TagFilter filter, String modifiedSince) {
        List<String> parts = new ArrayList<>();
        List<String> types = confluence.contentTypes();
        if (types.size() == 1) {
            parts.add("type = " + quote(types.get(0)));
        } else if (!types.isEmpty()) {
            parts.add("type in (" + String.join(",", types.stream().map(ConfluenceAdapter::quote).toList()) + ")");
        } else {
            parts.add("type = \"page\"");
        }
        if (!confluence.spaces().isEmpty()) {
            if (confluence.spaces().size() == 1) {
                parts.add("space = " + quote(confluence.spaces().get(0)));
            } else {
                parts.add("space in (" + String.join(",", confluence.spaces().stream().map(ConfluenceAdapter::quote).toList()) + ")");
            }
        }
        if (!confluence.ancestorPageId().isEmpty()) {
            parts.add("ancestor = " + confluence.ancestorPageId());
        }
        if (!filter.isAll()) {
            List<String> labels = filter.tags();
            if (filter.mode() == TagMode.ALL) {
                for (String label : labels) {
                    parts.add("label = " + quote(label));
                }
            } else if (labels.size() == 1) {
                parts.add("label = " + quote(labels.get(0)));
            } else if (!labels.isEmpty()) {
                parts.add("label in (" + String.join(",", labels.stream().map(ConfluenceAdapter::quote).toList()) + ")");
            }
        }
        if (filter.createdAfter() != null) {
            Times.parse(filter.createdAfter()).ifPresent(from ->
                    parts.add("created >= " + quote(CQL_LASTMODIFIED.format(from))));
        }
        if (modifiedSince != null && lastmodifiedSupported) {
            Times.parse(modifiedSince).ifPresent(since ->
                    parts.add("lastmodified >= " + quote(CQL_LASTMODIFIED.format(since))));
        }
        return String.join(" AND ", parts);
    }

    private List<Comment> fetchComments(String pageId) throws SourceException {
        JsonNode root = getJson(apiRoot + "/content/" + enc(pageId) + "/child/comment?expand="
                + enc("body.storage,history") + "&limit=" + confluence.pageSize());
        List<Comment> out = new ArrayList<>();
        for (JsonNode node : results(root)) {
            String id = text(node, "id");
            String author = node.path("history").path("createdBy").path("displayName").asText(null);
            String body = bodyHtml(node);
            String created = iso(node.path("history").path("createdDate").asText(null));
            out.add(new Comment(id, author, body, created));
        }
        return out;
    }

    private String bodyHtml(JsonNode node) {
        JsonNode storage = node.path("body").path("storage").path("value");
        if (storage.isTextual() && !storage.asText().isBlank()) {
            return storage.asText();
        }
        JsonNode view = node.path("body").path("view").path("value");
        if (view.isTextual() && !view.asText().isBlank()) {
            return view.asText();
        }
        return null;
    }

    private List<String> labels(JsonNode node) {
        Set<String> tags = new LinkedHashSet<>();
        JsonNode results = node.path("metadata").path("labels").path("results");
        if (results.isArray()) {
            for (JsonNode label : results) {
                String name = label.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    tags.add(name);
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private String modifiedAt(JsonNode node) {
        String when = node.path("version").path("when").asText(null);
        if (when == null || when.isBlank()) {
            when = node.path("history").path("lastUpdated").path("when").asText(null);
        }
        return iso(when);
    }

    private String browseUrl(JsonNode node) {
        String webui = node.path("_links").path("webui").asText(null);
        String base = node.path("_links").path("base").asText(null);
        if (webui != null && webui.startsWith("http")) {
            return webui;
        }
        String origin = base != null && !base.isBlank() ? trimSlash(base) : siteBase;
        if (webui != null && !webui.isBlank()) {
            return origin + (webui.startsWith("/") ? webui : "/" + webui);
        }
        String id = text(node, "id");
        return id == null ? origin : origin + "/pages/viewpage.action?pageId=" + id;
    }

    private String nextCursor(JsonNode root, String previousCursor, int got) {
        JsonNode links = root.path("_links");
        String next = links.path("next").asText(null);
        if (next != null && !next.isBlank()) {
            String cursor = queryParam(next, "cursor");
            if (cursor != null) {
                return cursor;
            }
            String start = queryParam(next, "start");
            if (start != null) {
                return "start:" + start;
            }
        }
        int start = root.path("start").asInt(-1);
        int limit = root.path("limit").asInt(confluence.pageSize());
        int size = root.path("size").asInt(got);
        if (start >= 0 && size >= limit) {
            return "start:" + (start + size);
        }
        if (got >= confluence.pageSize() && previousCursor == null) {
            return "start:" + got;
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode getJson(String url) throws SourceException {
        HttpSourceClient.Response response;
        try {
            response = client.exchange(url);
        } catch (SourceException e) {
            throw e;
        }
        if (response.status() >= 200 && response.status() < 300) {
            try {
                return Json.MAPPER.readTree(response.body() == null ? "{}" : response.body());
            } catch (Exception e) {
                throw new SourceException(SourceException.Kind.FATAL, "Confluence returned non-JSON from " + safe(url) + ": " + e.getMessage(), null, e);
            }
        }
        throw client.mapStatus(response.status(), response.retryAfter(), URI.create(settings.baseUrl()));
    }

    private static boolean looksLikeCqlReject(SourceException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return e.kind() == SourceException.Kind.FATAL || msg.contains("cql") || msg.contains("lastmodified")
                || e.kind() == SourceException.Kind.NOT_FOUND;
    }

    // ---------------------------------------------------------------- helpers

    public static String resolveApiRoot(String siteBase, ConfluenceOptions options) {
        if (options.apiRoot() != null && !options.apiRoot().isBlank()) {
            String root = options.apiRoot().strip();
            if (root.startsWith("http")) {
                return trimSlash(root);
            }
            return trimSlash(siteBase + (root.startsWith("/") ? root : "/" + root));
        }
        return trimSlash(siteBase + options.defaultApiRootPath());
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Iterable<JsonNode> results(JsonNode root) {
        JsonNode results = root.path("results");
        if (results.isArray()) {
            return results;
        }
        return ApiJson.items(root);
    }

    private static String iso(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw).toString();
        } catch (Exception ignored) {
            // Confluence often returns offset or local timestamps
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toString();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String queryParam(String urlOrPath, String name) {
        int q = urlOrPath.indexOf('?');
        String query = q < 0 ? urlOrPath : urlOrPath.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String safe(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
