package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.util.Json;
import com.questioncrawler.core.util.Times;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * First-class SharePoint source: Microsoft Graph site pages and optional list items.
 *
 * <p>Pages (and optionally list items) are collected as {@link Question} threads with empty answers.
 * Auth is Bearer via {@link HttpSourceClient}: either a pre-minted access token in an env var, or
 * Azure AD <em>client credentials</em> ({@code tenantId} + {@code clientId} + secret env name) which
 * this adapter exchanges at {@code login.microsoftonline.com} before Graph calls. Cookie scraping
 * and browser SSO automation are intentionally unsupported.
 *
 * <p>Confluence and Jive are separate {@code source.type} values — do not extend this adapter for them.
 */
public final class SharePointAdapter implements SourceAdapter {

    private final SourceSettings settings;
    private final SharePointOptions sharepoint;
    private final HttpSourceClient client;
    private final Function<String, String> env;
    private final String graphRoot;
    private final String siteUrl;
    private final List<String> notes = new ArrayList<>();
    private final AtomicReference<String> cachedSiteId = new AtomicReference<>();
    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private boolean modifiedFilterSupported = true;

    public SharePointAdapter(SourceSettings settings, SharePointOptions sharepoint, Function<String, String> env) {
        this.settings = settings;
        this.sharepoint = sharepoint == null ? SharePointOptions.DEFAULT : sharepoint;
        this.env = env == null ? System::getenv : env;
        this.graphRoot = this.sharepoint.graphBaseUrl();
        this.siteUrl = trimSlash(settings.baseUrl());
        this.client = new HttpSourceClient(settings, this::resolveAuthEnv);
    }

    /** Test constructor: inject a mock {@link HttpSourceClient} (already wired to the test env). */
    public SharePointAdapter(SourceSettings settings, SharePointOptions sharepoint, HttpSourceClient client,
                             Function<String, String> env) {
        this.settings = settings;
        this.sharepoint = sharepoint == null ? SharePointOptions.DEFAULT : sharepoint;
        this.env = env == null ? System::getenv : env;
        this.graphRoot = this.sharepoint.graphBaseUrl();
        this.siteUrl = trimSlash(settings.baseUrl());
        this.client = client;
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
        return modifiedFilterSupported;
    }

    @Override
    public String ping() throws SourceException {
        String siteId = resolveSiteId();
        JsonNode root = getJson(graphRoot + "/sites/" + encPath(siteId) + "?$select=id,displayName,webUrl");
        String name = text(root, "displayName");
        if (name == null || name.isBlank()) {
            name = text(root, "name");
        }
        return "SharePoint Graph reachable at " + graphRoot + " (site=" + (name == null ? siteId : name) + ")";
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        String siteId = resolveSiteId();
        if (cursor != null && cursor.startsWith("lists:")) {
            return listListItems(siteId, filter, modifiedSince, cursor);
        }
        if (sharepoint.pagesEnabled() && (cursor == null || cursor.startsWith("pages:") || cursor.startsWith("start:"))) {
            Page<QuestionSummary> pages = listPages(siteId, filter, modifiedSince, cursor);
            if (pages.nextCursor() != null) {
                return pages;
            }
            if (sharepoint.listItemsEnabled() && !sharepoint.listIds().isEmpty()) {
                // Continue into list streams after pages are exhausted.
                return new Page<>(pages.items(), "lists:" + sharepoint.listIds().get(0) + ":");
            }
            return pages;
        }
        if (sharepoint.listItemsEnabled()) {
            String start = cursor == null ? "lists:" + firstListId() + ":" : cursor;
            return listListItems(siteId, filter, modifiedSince, start);
        }
        return new Page<>(List.of(), null);
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        String siteId = resolveSiteId();
        if (id != null && id.startsWith("list:")) {
            return fetchListItem(siteId, id);
        }
        return fetchPage(siteId, id);
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) {
        return new Page<>(List.of(), null);
    }

    // ---------------------------------------------------------------- pages

    private Page<QuestionSummary> listPages(String siteId, TagFilter filter, String modifiedSince, String cursor)
            throws SourceException {
        String url;
        if (cursor != null && cursor.startsWith("pages:http")) {
            url = cursor.substring("pages:".length());
        } else if (cursor != null && cursor.startsWith("http")) {
            url = cursor;
        } else {
            StringBuilder sb = new StringBuilder(graphRoot).append("/sites/").append(encPath(siteId))
                    .append("/pages/microsoft.graph.sitePage?$top=").append(sharepoint.pageSize())
                    .append("&$select=").append(enc("id,name,title,webUrl,lastModifiedDateTime,createdDateTime,description"))
                    .append("&$orderby=").append(enc("lastModifiedDateTime desc"));
            if (modifiedSince != null && modifiedFilterSupported) {
                Times.parse(modifiedSince).ifPresent(since ->
                        sb.append("&$filter=").append(enc("lastModifiedDateTime ge " + since)));
            }
            url = sb.toString();
        }
        JsonNode root;
        try {
            root = getJson(url);
        } catch (SourceException e) {
            if (modifiedSince != null && modifiedFilterSupported && looksLikeFilterReject(e)) {
                modifiedFilterSupported = false;
                notes.add("This Graph tenant rejected lastModifiedDateTime $filter; falling back to full listing with local timestamp skip.");
                return listPages(siteId, filter, null, null);
            }
            throw e;
        }
        List<QuestionSummary> items = new ArrayList<>();
        for (JsonNode node : value(root)) {
            String id = text(node, "id");
            if (id == null || id.isBlank()) {
                continue;
            }
            String modified = iso(text(node, "lastModifiedDateTime"));
            if (modifiedSince != null && !modifiedFilterSupported && modified != null) {
                Instant since = Times.parse(modifiedSince).orElse(null);
                Instant when = Times.parse(modified).orElse(null);
                if (since != null && when != null && when.isBefore(since)) {
                    continue;
                }
            }
            if (!matchesPageFilter(filter, node)) {
                continue;
            }
            items.add(new QuestionSummary(id, modified, text(node, "webUrl")));
        }
        String next = text(root, "@odata.nextLink");
        if (next != null && !next.isBlank()) {
            return new Page<>(items, "pages:" + next);
        }
        return new Page<>(items, null);
    }

    private Question fetchPage(String siteId, String id) throws SourceException {
        JsonNode node;
        try {
            node = getJson(graphRoot + "/sites/" + encPath(siteId) + "/pages/" + encPath(id)
                    + "/microsoft.graph.sitePage?$expand=" + enc("canvasLayout"));
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.NOT_FOUND || e.kind() == SourceException.Kind.GONE
                    || e.kind() == SourceException.Kind.FORBIDDEN) {
                throw e;
            }
            node = getJson(graphRoot + "/sites/" + encPath(siteId) + "/pages/" + encPath(id)
                    + "/microsoft.graph.sitePage");
        }
        String title = text(node, "title");
        if (title == null || title.isBlank()) {
            title = text(node, "name");
        }
        String body = extractCanvasText(node.path("canvasLayout"));
        String description = text(node, "description");
        if ((body == null || body.isBlank()) && description != null && !description.isBlank()) {
            body = description;
        }
        if (body == null) {
            body = "";
            notes.add("Page " + id + " had no readable canvas/description; stored title and metadata only.");
        }
        List<String> tags = pageTags(node);
        if (sharepoint.includeSiteTag()) {
            tags = withSiteTag(tags);
        }
        String created = iso(text(node, "createdDateTime"));
        String modified = iso(text(node, "lastModifiedDateTime"));
        String author = node.path("createdBy").path("user").path("displayName").asText(null);
        return new Question(id, title == null ? "" : title, body, BodyFormat.HTML,
                text(node, "webUrl"), tags, author, null, created, modified, List.of(), List.of());
    }

    // ---------------------------------------------------------------- list items

    private Page<QuestionSummary> listListItems(String siteId, TagFilter filter, String modifiedSince, String cursor)
            throws SourceException {
        String listId;
        String nextPart;
        if (cursor != null && cursor.startsWith("lists:")) {
            String rest = cursor.substring("lists:".length());
            int colon = rest.indexOf(':');
            if (colon < 0) {
                listId = rest.isBlank() ? firstListId() : rest;
                nextPart = "";
            } else {
                listId = rest.substring(0, colon);
                nextPart = rest.substring(colon + 1);
            }
        } else {
            listId = firstListId();
            nextPart = "";
        }
        if (listId == null || listId.isBlank()) {
            return new Page<>(List.of(), null);
        }
        String url;
        if (nextPart.startsWith("http")) {
            url = nextPart;
        } else {
            StringBuilder sb = new StringBuilder(graphRoot).append("/sites/").append(encPath(siteId))
                    .append("/lists/").append(encPath(listId))
                    .append("/items?$expand=fields&$top=").append(sharepoint.pageSize())
                    .append("&$orderby=").append(enc("lastModifiedDateTime desc"));
            if (modifiedSince != null && modifiedFilterSupported) {
                Times.parse(modifiedSince).ifPresent(since ->
                        sb.append("&$filter=").append(enc("lastModifiedDateTime ge " + since)));
            }
            url = sb.toString();
        }
        JsonNode root;
        try {
            root = getJson(url);
        } catch (SourceException e) {
            if (modifiedSince != null && modifiedFilterSupported && looksLikeFilterReject(e)) {
                modifiedFilterSupported = false;
                notes.add("This Graph tenant rejected list-item lastModifiedDateTime $filter; falling back to full listing.");
                return listListItems(siteId, filter, null, "lists:" + listId + ":");
            }
            throw e;
        }
        List<QuestionSummary> items = new ArrayList<>();
        for (JsonNode node : value(root)) {
            String itemId = text(node, "id");
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            String composite = "list:" + listId + ":" + itemId;
            String modified = iso(text(node, "lastModifiedDateTime"));
            if (modified == null) {
                modified = iso(node.path("fields").path("Modified").asText(null));
            }
            if (modifiedSince != null && !modifiedFilterSupported && modified != null) {
                Instant since = Times.parse(modifiedSince).orElse(null);
                Instant when = Times.parse(modified).orElse(null);
                if (since != null && when != null && when.isBefore(since)) {
                    continue;
                }
            }
            if (filter != null && !filter.isAll() && !filter.matches(listItemTags(node, listId))) {
                continue;
            }
            String webUrl = node.path("webUrl").asText(null);
            if (webUrl == null || webUrl.isBlank()) {
                webUrl = node.path("fields").path("FileRef").asText(null);
            }
            items.add(new QuestionSummary(composite, modified, webUrl));
        }
        String next = text(root, "@odata.nextLink");
        if (next != null && !next.isBlank()) {
            return new Page<>(items, "lists:" + listId + ":" + next);
        }
        List<String> lists = sharepoint.listIds();
        int idx = lists.indexOf(listId);
        if (idx >= 0 && idx + 1 < lists.size()) {
            return new Page<>(items, "lists:" + lists.get(idx + 1) + ":");
        }
        return new Page<>(items, null);
    }

    private Question fetchListItem(String siteId, String compositeId) throws SourceException {
        String[] parts = compositeId.split(":", 3);
        if (parts.length < 3) {
            throw new SourceException(SourceException.Kind.FATAL, "Invalid SharePoint list item id: " + compositeId);
        }
        String listId = parts[1];
        String itemId = parts[2];
        JsonNode node = getJson(graphRoot + "/sites/" + encPath(siteId) + "/lists/" + encPath(listId)
                + "/items/" + encPath(itemId) + "?$expand=fields");
        JsonNode fields = node.path("fields");
        String title = fields.path("Title").asText(null);
        if (title == null || title.isBlank()) {
            title = fields.path("LinkFilename").asText(null);
        }
        if (title == null || title.isBlank()) {
            title = fields.path("FileLeafRef").asText(null);
        }
        String body = fields.path("Body").asText(null);
        if (body == null || body.isBlank()) {
            body = fields.path("Description").asText(null);
        }
        if (body == null || body.isBlank()) {
            body = fields.path("Notes").asText(null);
        }
        if (body == null) {
            body = "";
            notes.add("List item " + compositeId + " had no Body/Description; stored title and metadata only.");
        }
        List<String> tags = listItemTags(node, listId);
        if (sharepoint.includeSiteTag()) {
            tags = withSiteTag(tags);
        }
        String created = iso(text(node, "createdDateTime"));
        if (created == null) {
            created = iso(fields.path("Created").asText(null));
        }
        String modified = iso(text(node, "lastModifiedDateTime"));
        if (modified == null) {
            modified = iso(fields.path("Modified").asText(null));
        }
        String author = node.path("createdBy").path("user").path("displayName").asText(null);
        String webUrl = text(node, "webUrl");
        if (webUrl == null || webUrl.isBlank()) {
            webUrl = fields.path("FileRef").asText(null);
        }
        return new Question(compositeId, title == null ? "" : title, body, BodyFormat.HTML,
                webUrl, tags, author, null, created, modified, List.of(), List.of());
    }

    // ---------------------------------------------------------------- site / auth / HTTP

    String resolveSiteId() throws SourceException {
        if (!sharepoint.siteId().isEmpty()) {
            cachedSiteId.set(sharepoint.siteId());
            return sharepoint.siteId();
        }
        String cached = cachedSiteId.get();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (siteUrl == null || siteUrl.isBlank()) {
            throw new SourceException(SourceException.Kind.NOT_CONFIGURED,
                    "SharePoint needs source.baseUrl (site URL) or source.sharepoint.siteId");
        }
        URI uri = URI.create(siteUrl);
        String host = uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String lookup = graphRoot + "/sites/" + encPath(host) + ":/" + path + "?$select=id,displayName,webUrl";
        JsonNode root = getJson(lookup);
        String id = text(root, "id");
        if (id == null || id.isBlank()) {
            throw new SourceException(SourceException.Kind.FATAL, "Graph did not return a site id for " + siteUrl);
        }
        cachedSiteId.set(id);
        return id;
    }

    private String resolveAuthEnv(String name) {
        if (settings.tokenEnv() != null && settings.tokenEnv().equals(name) && sharepoint.usesClientCredentials()) {
            try {
                return bearerToken();
            } catch (SourceException e) {
                return null;
            }
        }
        return env.apply(name);
    }

    private String bearerToken() throws SourceException {
        String cached = cachedToken.get();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (sharepoint.usesClientCredentials()) {
            String token = fetchClientCredentialsToken();
            cachedToken.set(token);
            return token;
        }
        String name = settings.tokenEnv();
        if (name == null || name.isBlank()) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "SharePoint needs source.auth.tokenEnv or source.sharepoint client-credentials settings");
        }
        String token = env.apply(name);
        if (token == null || token.isBlank()) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "Environment variable '" + name + "' is not set; cannot authenticate to Microsoft Graph");
        }
        cachedToken.set(token);
        return token;
    }

    private String fetchClientCredentialsToken() throws SourceException {
        String secret = env.apply(sharepoint.clientSecretEnv());
        if (secret == null || secret.isBlank()) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "Environment variable '" + sharepoint.clientSecretEnv()
                            + "' is not set; cannot run Azure AD client credentials");
        }
        String tokenUrl = "https://login.microsoftonline.com/" + encPath(sharepoint.tenantId())
                + "/oauth2/v2.0/token";
        String body = "client_id=" + enc(sharepoint.clientId())
                + "&client_secret=" + enc(secret)
                + "&scope=" + enc("https://graph.microsoft.com/.default")
                + "&grant_type=client_credentials";
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(settings.timeout() == null ? Duration.ofSeconds(30) : settings.timeout())
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(settings.timeout() == null ? Duration.ofSeconds(30) : settings.timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("User-Agent", "QuestionCrawler/0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SourceException(SourceException.Kind.AUTH_FAILED,
                        "Azure AD token endpoint returned HTTP " + response.statusCode());
            }
            JsonNode root = Json.MAPPER.readTree(response.body() == null ? "{}" : response.body());
            String access = text(root, "access_token");
            if (access == null || access.isBlank()) {
                throw new SourceException(SourceException.Kind.AUTH_FAILED,
                        "Azure AD token response had no access_token");
            }
            return access;
        } catch (SourceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.TRANSIENT, "Interrupted while requesting Azure AD token", null, e);
        } catch (Exception e) {
            throw new SourceException(SourceException.Kind.AUTH_FAILED,
                    "Azure AD client credentials failed: " + e.getMessage(), null, e);
        }
    }

    private JsonNode getJson(String url) throws SourceException {
        if (sharepoint.usesClientCredentials()) {
            bearerToken();
        }
        HttpSourceClient.Response response = client.exchange(url);
        if (response.status() >= 200 && response.status() < 300) {
            try {
                return Json.MAPPER.readTree(response.body() == null ? "{}" : response.body());
            } catch (Exception e) {
                throw new SourceException(SourceException.Kind.FATAL,
                        "SharePoint/Graph returned non-JSON from " + safe(url) + ": " + e.getMessage(), null, e);
            }
        }
        throw client.mapStatus(response.status(), response.retryAfter(), URI.create(graphRoot));
    }

    // ---------------------------------------------------------------- helpers

    public static String graphHost(String graphBaseUrl) {
        try {
            String host = URI.create(graphBaseUrl).getHost();
            return host == null ? "graph.microsoft.com" : host;
        } catch (Exception e) {
            return "graph.microsoft.com";
        }
    }

    private String firstListId() {
        return sharepoint.listIds().isEmpty() ? "" : sharepoint.listIds().get(0);
    }

    private List<String> withSiteTag(List<String> tags) {
        String label = sharepoint.siteId().isEmpty() ? siteLabel() : sharepoint.siteId();
        if (label == null || label.isBlank()) {
            return tags;
        }
        // Prefer a short site leaf for readability when siteId is a long Graph compound id.
        if (label.contains(",")) {
            label = siteLabel();
        }
        String synthetic = "site:" + label;
        if (tags.stream().anyMatch(t -> t.equalsIgnoreCase(synthetic))) {
            return tags;
        }
        List<String> out = new ArrayList<>(tags);
        out.add(synthetic);
        return out;
    }

    private String siteLabel() {
        try {
            URI uri = URI.create(siteUrl);
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            int slash = path.lastIndexOf('/');
            String leaf = slash >= 0 ? path.substring(slash + 1) : path;
            return leaf.isBlank() ? uri.getHost() : leaf;
        } catch (Exception e) {
            return "sharepoint";
        }
    }

    private static List<String> pageTags(JsonNode node) {
        Set<String> tags = new LinkedHashSet<>();
        String description = text(node, "description");
        // No native page labels in Graph sitePage; tags are derived at filter time from title/description.
        if (description != null && !description.isBlank()) {
            // keep empty structured tags; title match happens in matchesPageFilter
        }
        return new ArrayList<>(tags);
    }

    private static boolean matchesPageFilter(TagFilter filter, JsonNode node) {
        if (filter == null || filter.isAll()) {
            return true;
        }
        String hay = ((text(node, "title") == null ? "" : text(node, "title")) + " "
                + (text(node, "name") == null ? "" : text(node, "name")) + " "
                + (text(node, "description") == null ? "" : text(node, "description"))).toLowerCase(Locale.ROOT);
        List<String> wanted = filter.tags();
        if (filter.mode() == TagMode.ALL) {
            for (String w : wanted) {
                if (!hay.contains(w.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        }
        for (String w : wanted) {
            if (hay.contains(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> listItemTags(JsonNode node, String listId) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("list:" + listId);
        JsonNode fields = node.path("fields");
        JsonNode categories = fields.path("Category");
        if (categories.isTextual() && !categories.asText().isBlank()) {
            tags.add(categories.asText());
        } else if (categories.isArray()) {
            for (JsonNode c : categories) {
                if (c.isTextual() && !c.asText().isBlank()) {
                    tags.add(c.asText());
                }
            }
        }
        JsonNode keywords = fields.path("Keywords");
        if (keywords.isTextual() && !keywords.asText().isBlank()) {
            for (String part : keywords.asText().split("[,;]")) {
                if (!part.isBlank()) {
                    tags.add(part.strip());
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private static String extractCanvasText(JsonNode canvas) {
        if (canvas == null || canvas.isMissingNode() || canvas.isNull()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        collectText(canvas, out);
        String s = out.toString().strip();
        return s.isEmpty() ? null : s;
    }

    private static void collectText(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            for (String field : List.of("innerHtml", "html", "text", "title", "description", "markup")) {
                JsonNode v = node.get(field);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(v.asText());
                }
            }
            node.fields().forEachRemaining(e -> {
                if (!List.of("id", "@odata.type", "@odata.id", "@odata.context").contains(e.getKey())) {
                    collectText(e.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectText(child, out);
            }
        }
    }

    private static boolean looksLikeFilterReject(SourceException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return e.kind() == SourceException.Kind.FATAL || msg.contains("filter") || msg.contains("odata")
                || e.kind() == SourceException.Kind.NOT_FOUND;
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

    private static String encPath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    private static Iterable<JsonNode> value(JsonNode root) {
        JsonNode value = root.path("value");
        if (value.isArray()) {
            return value;
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
            // fall through
        }
        try {
            return OffsetDateTime.parse(raw).toInstant().toString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String safe(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
