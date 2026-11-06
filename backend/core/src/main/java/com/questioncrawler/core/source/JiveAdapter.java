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
 * First-class Jive source: Core REST API v3 content list/search.
 *
 * <p>Documents, discussions, and questions are collected as {@link Question} threads. Discussion/question
 * replies become answers when the messages endpoint is available; optional comments are attached on fetch.
 * Auth is {@code basic-env} (username + password/API token from env) or Bearer {@code token} (OAuth access
 * token) via {@link HttpSourceClient}. Cookie scraping and browser SSO automation are intentionally unsupported.
 *
 * <p>Confluence and SharePoint are separate {@code source.type} values — do not extend this adapter for them.
 */
public final class JiveAdapter implements SourceAdapter {

    /** Jive filter dates often look like {@code 2012-01-31T19:13:29.851+0000}. */
    private static final DateTimeFormatter JIVE_AFTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            .withZone(ZoneOffset.UTC);

    private final SourceSettings settings;
    private final JiveOptions jive;
    private final HttpSourceClient client;
    private final Function<String, String> env;
    private final String apiRoot;
    private final String siteBase;
    private final List<String> notes = new ArrayList<>();
    private boolean afterFilterSupported = true;

    public JiveAdapter(SourceSettings settings, JiveOptions jive, HttpSourceClient client) {
        this(settings, jive, client, System::getenv);
    }

    public JiveAdapter(SourceSettings settings, JiveOptions jive, HttpSourceClient client,
                       Function<String, String> env) {
        this.settings = settings;
        this.jive = jive == null ? JiveOptions.DEFAULT : jive;
        this.client = client;
        this.env = env;
        this.siteBase = trimSlash(settings.baseUrl());
        this.apiRoot = resolveApiRoot(this.siteBase, this.jive);
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
        return afterFilterSupported;
    }

    @Override
    public String ping() throws SourceException {
        JsonNode root = getJson(apiRoot + "/places?count=1");
        int size = root.path("list").size();
        if (size == 0) {
            size = root.path("items").size();
        }
        return "Jive Core API reachable at " + apiRoot + " (places listing size=" + size + ")";
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        StringBuilder url = new StringBuilder(apiRoot).append("/contents?");
        List<String> filters = buildFilters(filter, modifiedSince);
        for (String f : filters) {
            url.append("filter=").append(enc(f)).append('&');
        }
        url.append("count=").append(jive.pageSize());
        url.append("&sort=").append(enc("updatedDesc"));
        int startIndex = 0;
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.startsWith("start:")) {
                startIndex = parseInt(cursor.substring("start:".length()), 0);
            } else {
                startIndex = parseInt(cursor, 0);
            }
        }
        url.append("&startIndex=").append(startIndex);

        JsonNode root;
        try {
            root = getJson(url.toString());
        } catch (SourceException e) {
            if (modifiedSince != null && afterFilterSupported && looksLikeAfterReject(e)) {
                afterFilterSupported = false;
                notes.add("This Jive build rejected the after/modificationDate filter; falling back to full listing with local timestamp skip.");
                return listQuestions(filter, null, cursor);
            }
            throw e;
        }

        List<QuestionSummary> items = new ArrayList<>();
        for (JsonNode node : list(root)) {
            String id = contentId(node);
            if (id == null || id.isBlank()) {
                continue;
            }
            String modified = modifiedAt(node);
            if (modifiedSince != null && !afterFilterSupported && modified != null) {
                Instant since = Times.parse(modifiedSince).orElse(null);
                Instant when = Times.parse(modified).orElse(null);
                if (since != null && when != null && when.isBefore(since)) {
                    continue;
                }
            }
            items.add(new QuestionSummary(id, modified, browseUrl(node)));
        }
        return new Page<>(items, nextCursor(root, startIndex, items.size()));
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        JsonNode node = getJson(apiRoot + "/contents/" + enc(id));
        String title = text(node, "subject");
        if (title == null || title.isBlank()) {
            title = text(node, "title");
        }
        String body = bodyText(node);
        BodyFormat format = bodyFormat(node);
        if (body == null) {
            body = "";
            notes.add("Content " + id + " had no readable content.text; stored title and metadata only.");
        }
        List<String> tags = tags(node);
        if (jive.includePlaceTag()) {
            String placeId = placeId(node);
            if (placeId != null && !placeId.isBlank()) {
                String synthetic = "place:" + placeId;
                if (tags.stream().noneMatch(t -> t.equalsIgnoreCase(synthetic))) {
                    tags = new ArrayList<>(tags);
                    tags.add(synthetic);
                }
            }
        }
        String type = text(node, "type");
        if (type != null && !type.isBlank()) {
            String synthetic = "type:" + type;
            if (tags.stream().noneMatch(t -> t.equalsIgnoreCase(synthetic))) {
                tags = new ArrayList<>(tags);
                tags.add(synthetic);
            }
        }
        String author = node.path("author").path("displayName").asText(null);
        if (author == null || author.isBlank()) {
            author = node.path("author").path("jive").path("username").asText(null);
        }
        String created = iso(text(node, "published"));
        String modified = modifiedAt(node);
        List<Comment> comments = List.of();
        if (jive.collectComments()) {
            try {
                comments = fetchComments(id);
            } catch (SourceException e) {
                notes.add("Comments for content " + id + " could not be collected (" + e.kind() + "); body was kept.");
            }
        }
        return new Question(id, title == null ? "" : title, body, format, browseUrl(node), tags, author, null,
                created, modified, comments, List.of());
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) throws SourceException {
        // Documents have no answers. Discussions/questions expose replies under /messages/{id}/messages.
        StringBuilder url = new StringBuilder(apiRoot).append("/messages/").append(enc(questionId))
                .append("/messages?count=").append(jive.pageSize());
        int startIndex = 0;
        if (cursor != null && !cursor.isBlank()) {
            startIndex = cursor.startsWith("start:")
                    ? parseInt(cursor.substring("start:".length()), 0)
                    : parseInt(cursor, 0);
            url.append("&startIndex=").append(startIndex);
        } else {
            url.append("&startIndex=0");
        }
        JsonNode root;
        try {
            root = getJson(url.toString());
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.NOT_FOUND || e.kind() == SourceException.Kind.GONE
                    || e.kind() == SourceException.Kind.FORBIDDEN) {
                return new Page<>(List.of(), null);
            }
            // Some content types have no messages collection — treat as empty answers.
            if (e.kind() == SourceException.Kind.FATAL) {
                return new Page<>(List.of(), null);
            }
            throw e;
        }
        List<Answer> out = new ArrayList<>();
        for (JsonNode node : list(root)) {
            String id = contentId(node);
            if (id == null || id.isBlank()) {
                id = text(node, "id");
            }
            String body = bodyText(node);
            BodyFormat format = bodyFormat(node);
            String author = node.path("author").path("displayName").asText(null);
            boolean accepted = node.path("answer").asBoolean(false) || node.path("correctAnswer").asBoolean(false);
            Integer score = node.path("likeCount").isMissingNode() ? null : node.path("likeCount").asInt();
            String created = iso(text(node, "published"));
            String modified = iso(text(node, "updated"));
            out.add(new Answer(id, body == null ? "" : body, format, browseUrl(node), accepted, score, author,
                    created, modified, List.of()));
        }
        return new Page<>(out, nextCursor(root, startIndex, out.size()));
    }

    // ---------------------------------------------------------------- filters / mapping

    List<String> buildFilters(TagFilter filter, String modifiedSince) {
        List<String> filters = new ArrayList<>();
        List<String> types = jive.contentTypes();
        if (!types.isEmpty()) {
            filters.add("type(" + String.join(",", types) + ")");
        }
        if (!jive.places().isEmpty()) {
            List<String> placeUris = new ArrayList<>();
            for (String place : jive.places()) {
                if (place.startsWith("http") || place.startsWith("/places/")) {
                    placeUris.add(place.startsWith("http") ? place : place);
                } else {
                    placeUris.add("/places/" + place);
                }
            }
            filters.add("place(" + String.join(",", placeUris) + ")");
        }
        if (!filter.isAll()) {
            List<String> tags = filter.tags();
            if (filter.mode() == TagMode.ALL && tags.size() > 1) {
                filters.add("tag(" + String.join(" AND ", tags) + ")");
            } else if (!tags.isEmpty()) {
                filters.add("tag(" + String.join(",", tags) + ")");
            }
        }
        if (filter.createdAfter() != null) {
            Times.parse(filter.createdAfter()).ifPresent(from ->
                    filters.add("creationDate(" + formatAfter(from) + ",null)"));
        }
        if (modifiedSince != null && afterFilterSupported) {
            Times.parse(modifiedSince).ifPresent(since -> filters.add("after(" + formatAfter(since) + ")"));
        }
        return filters;
    }

    private List<Comment> fetchComments(String contentId) throws SourceException {
        JsonNode root = getJson(apiRoot + "/contents/" + enc(contentId) + "/comments?count=" + jive.pageSize());
        List<Comment> out = new ArrayList<>();
        for (JsonNode node : list(root)) {
            String id = contentId(node);
            if (id == null) {
                id = text(node, "id");
            }
            String author = node.path("author").path("displayName").asText(null);
            String body = bodyText(node);
            String created = iso(text(node, "published"));
            out.add(new Comment(id, author, body, created));
        }
        return out;
    }

    private String bodyText(JsonNode node) {
        JsonNode content = node.path("content");
        if (content.isTextual() && !content.asText().isBlank()) {
            return content.asText();
        }
        JsonNode text = content.path("text");
        if (text.isTextual() && !text.asText().isBlank()) {
            return text.asText();
        }
        return null;
    }

    private BodyFormat bodyFormat(JsonNode node) {
        String type = node.path("content").path("type").asText("");
        if (type.toLowerCase(Locale.ROOT).contains("html")) {
            return BodyFormat.HTML;
        }
        if (type.toLowerCase(Locale.ROOT).contains("text")) {
            return BodyFormat.TEXT;
        }
        // Jive content.text is usually HTML even when type is omitted.
        return BodyFormat.HTML;
    }

    private List<String> tags(JsonNode node) {
        Set<String> tags = new LinkedHashSet<>();
        JsonNode arr = node.path("tags");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                if (t.isTextual() && !t.asText().isBlank()) {
                    tags.add(t.asText());
                } else {
                    String name = t.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        tags.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private String placeId(JsonNode node) {
        String place = node.path("parent").asText(null);
        if (place == null || place.isBlank()) {
            place = node.path("parentPlace").path("id").asText(null);
        }
        if (place == null || place.isBlank()) {
            place = node.path("parentPlace").path("placeID").asText(null);
        }
        if (place == null) {
            return null;
        }
        int slash = place.lastIndexOf('/');
        return slash >= 0 ? place.substring(slash + 1) : place;
    }

    private String modifiedAt(JsonNode node) {
        String when = text(node, "updated");
        if (when == null || when.isBlank()) {
            when = text(node, "published");
        }
        return iso(when);
    }

    private String browseUrl(JsonNode node) {
        String html = node.path("resources").path("html").path("ref").asText(null);
        if (html != null && !html.isBlank()) {
            return html;
        }
        String permalink = text(node, "permalink");
        if (permalink != null && !permalink.isBlank()) {
            return permalink.startsWith("http") ? permalink : siteBase + (permalink.startsWith("/") ? permalink : "/" + permalink);
        }
        String id = contentId(node);
        return id == null ? siteBase : siteBase + "/docs/" + id;
    }

    private String nextCursor(JsonNode root, int startIndex, int got) {
        JsonNode links = root.path("links");
        String next = links.path("next").asText(null);
        if (next != null && !next.isBlank()) {
            String start = queryParam(next, "startIndex");
            if (start != null) {
                return "start:" + start;
            }
        }
        int count = root.path("itemsPerPage").asInt(jive.pageSize());
        if (count <= 0) {
            count = jive.pageSize();
        }
        if (got >= count) {
            return "start:" + (startIndex + got);
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode getJson(String url) throws SourceException {
        HttpSourceClient.Response response = client.exchange(url);
        if (response.status() >= 200 && response.status() < 300) {
            String raw = response.body() == null ? "{}" : response.body();
            // Jive sometimes prefixes JSON with throw-away security:  )]}',\n
            if (raw.startsWith(")]}'")) {
                int nl = raw.indexOf('\n');
                raw = nl >= 0 ? raw.substring(nl + 1) : raw.substring(4);
            }
            try {
                return Json.MAPPER.readTree(raw);
            } catch (Exception e) {
                throw new SourceException(SourceException.Kind.FATAL, "Jive returned non-JSON from " + safe(url) + ": " + e.getMessage(), null, e);
            }
        }
        throw client.mapStatus(response.status(), response.retryAfter(), URI.create(settings.baseUrl()));
    }

    private static boolean looksLikeAfterReject(SourceException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return e.kind() == SourceException.Kind.FATAL || msg.contains("after") || msg.contains("filter")
                || msg.contains("modificationdate") || e.kind() == SourceException.Kind.NOT_FOUND;
    }

    // ---------------------------------------------------------------- helpers

    public static String resolveApiRoot(String siteBase, JiveOptions options) {
        if (options.apiRoot() != null && !options.apiRoot().isBlank()) {
            String root = options.apiRoot().strip();
            if (root.startsWith("http")) {
                return trimSlash(root);
            }
            return trimSlash(siteBase + (root.startsWith("/") ? root : "/" + root));
        }
        return trimSlash(siteBase + options.defaultApiRootPath());
    }

    private static String formatAfter(Instant when) {
        return JIVE_AFTER.format(when);
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    private static String contentId(JsonNode node) {
        String id = text(node, "contentID");
        if (id == null || id.isBlank()) {
            id = text(node, "id");
        }
        if (id != null && id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        return id;
    }

    private static Iterable<JsonNode> list(JsonNode root) {
        JsonNode list = root.path("list");
        if (list.isArray()) {
            return list;
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
            // Jive often returns +0000 without a colon
        }
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant().toString();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toString();
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

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String safe(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
