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
 * First-class Confluence source, read-only. Two APIs:
 * <ul>
 *   <li>{@code source.confluence.apiVersion=v1} (default): classic Content REST API + CQL search, Cloud and Server/DC.</li>
 *   <li>{@code source.confluence.apiVersion=v2}: Confluence Cloud REST API v2 ({@code /wiki/api/v2/pages?body-format=storage}).</li>
 * </ul>
 *
 * <p>Pages are collected as {@link Question} threads (empty answers). Labels map to tags.
 * Auth is {@code basic-env} (Cloud email + API token), Bearer {@code token} (PAT / token from an environment variable), or
 * {@code tvm} (short-lived Bearer token from a company Token Vending Machine via Kerberos, renewed automatically; see
 * {@link TvmTokenProvider}). Cookie scraping and HTML UI login are intentionally unsupported.
 *
 * <p>Jive ({@code source.type=jive}) and SharePoint are separate {@code source.type} values — do not extend this adapter for them.
 */
public final class ConfluenceAdapter implements SourceAdapter, BrowserSignIn {

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
    private final TvmTokenProvider tvm;
    private BrowserLogin browser;
    private final java.util.Map<String, String> spaceKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String v2Base;
    private List<String> resolvedSpaceIds;
    private List<String> treeIds;


    public ConfluenceAdapter(SourceSettings settings, ConfluenceOptions confluence, HttpSourceClient client) {
        this(settings, confluence, client, System::getenv);
    }

    public ConfluenceAdapter(SourceSettings settings, ConfluenceOptions confluence, HttpSourceClient client,
                             Function<String, String> env) {
        this(settings, confluence, client, env, null);
    }

    /** @param tvm the token provider behind {@code client} when {@code source.auth.method=tvm} (renewed once on HTTP 401), or null */
    public ConfluenceAdapter(SourceSettings settings, ConfluenceOptions confluence, HttpSourceClient client,
                             Function<String, String> env, TvmTokenProvider tvm) {
        this.settings = settings;
        this.confluence = confluence == null ? ConfluenceOptions.DEFAULT : confluence;
        this.client = client;
        this.env = env;
        this.tvm = tvm;
        this.siteBase = trimSlash(settings.baseUrl());
        this.apiRoot = resolveApiRoot(this.siteBase, this.confluence);
    }

    /** Builds the adapter with its HTTP client, wiring a Token Vending Machine when {@code source.auth.method=tvm}. */
    public static ConfluenceAdapter create(SourceSettings settings, ConfluenceOptions confluence, Function<String, String> env) {
        ConfluenceOptions options = confluence == null ? ConfluenceOptions.DEFAULT : confluence;
        TvmTokenProvider tvm = null;
        if ("tvm".equalsIgnoreCase(settings.authMethod()) && options.tvm().configured()) {
            tvm = new TvmTokenProvider(options.tvm(), settings.proxy().orEnvironment(env), settings.timeout());
        }
        if ("browser".equalsIgnoreCase(settings.authMethod())) {
            return withBrowserLogin(settings, options, env, BrowserLogin.forSite(settings.baseUrl(), options, env));
        }
        TvmTokenProvider provider = tvm;
        HttpSourceClient client = new HttpSourceClient(settings, env, provider == null ? null : provider::token);
        return new ConfluenceAdapter(settings, options, client, env, provider);
    }

    /** An adapter that signs in through {@code login} (the person's browser) and sends its session cookies. */
    public static ConfluenceAdapter withBrowserLogin(SourceSettings settings, ConfluenceOptions options, Function<String, String> env, BrowserLogin login) {
        ConfluenceAdapter[] self = new ConfluenceAdapter[1];
        HttpSourceClient client = new HttpSourceClient(settings, env, () -> login.cookieHeader(self[0]::sessionAccepted));
        self[0] = new ConfluenceAdapter(settings, options, client, env, null).withBrowser(login);
        return self[0];
    }

    /** Uses browser sign-in: the session is checked, and renewed through the browser when the site rejects it. */
    public ConfluenceAdapter withBrowser(BrowserLogin login) {
        this.browser = login;
        return this;
    }

    /** Opens the browser sign-in now if there is no session yet (no-op for other sign-in methods). Returns a status line. */
    @Override
    public String signIn() throws SourceException {
        if (browser == null) {
            return "this sign-in method does not use the browser";
        }
        browser.cookieHeader(this::sessionAccepted);
        return browser.status();
    }

    /** The browser session's Cookie header, or null (never shown; used by the connection check). */
    @Override
    public String sessionCookie() {
        return browser == null ? null : browser.current();
    }

    /** True when one cheap API call with this Cookie header answers 2xx with JSON (the person is signed in). */
    boolean sessionAccepted(String cookieHeader) throws SourceException {
        String probe = apiRoot + (confluence.v2() ? "/spaces?limit=1" : "/space?limit=1");
        HttpSourceClient.Response r;
        try {
            r = client.exchange(probe, java.util.Map.of("Cookie", cookieHeader, "X-Atlassian-Token", "no-check"));
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.TRANSIENT) {
                return false; // keep waiting: the network may come back while the person signs in
            }
            throw e;
        }
        String type = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        return r.status() >= 200 && r.status() < 300 && type.contains("json");
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
        if (confluence.v2()) {
            JsonNode root = getJson(apiRoot + "/spaces?limit=1");
            String auth = tvm != null ? " with a Token Vending Machine token from " + tvm.host() : "";
            return "Confluence Cloud REST API v2 reachable at " + apiRoot + auth + " (spaces visible: " + (results(root).iterator().hasNext() ? "yes" : "none") + ")";
        }
        JsonNode root = getJson(apiRoot + "/space?limit=1");
        int size = root.path("size").asInt(root.path("results").size());
        String edition = confluence.cloud() ? "Cloud" : "Server/DC";
        return "Confluence " + edition + " reachable at " + apiRoot + " (space listing size=" + size + ")";
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        if (confluence.v2()) {
            return listQuestionsV2(filter, modifiedSince, cursor);
        }
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
        if (confluence.v2()) {
            return fetchQuestionV2(id);
        }
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

    // ---------------------------------------------------------------- spaces (for choosing what to collect)

    /** One Confluence space the signed-in account can see. */
    public record SpaceInfo(String key, String name, String type, String status, String id, String url) {}

    /** The spaces read so far; {@code complete} is false when the listing stopped at {@code max}. */
    public record SpaceListing(List<SpaceInfo> spaces, boolean complete) {}

    /** Lists the spaces this account can read (v1 or v2 API, following every page up to {@code max}). */
    public SpaceListing listSpaces(int max) throws SourceException {
        List<SpaceInfo> out = new ArrayList<>();
        String url = confluence.v2() ? apiRoot + "/spaces?limit=250&sort=key" : apiRoot + "/space?limit=100";
        int pages = 0;
        while (url != null && out.size() < max && pages++ < 1000) {
            JsonNode root = getJson(url);
            rememberBase(root);
            int got = 0;
            for (JsonNode node : results(root)) {
                got++;
                String key = text(node, "key");
                if (key == null || key.isBlank()) {
                    continue;
                }
                String webui = node.path("_links").path("webui").asText(null);
                String base = v2Base != null ? v2Base : siteBase + (confluence.cloud() ? "/wiki" : "");
                String link = webui == null ? base + "/spaces/" + key : webui.startsWith("http") ? webui : base + webui;
                out.add(new SpaceInfo(key, text(node, "name"), text(node, "type"), text(node, "status"), text(node, "id"), link));
                if (out.size() >= max) {
                    break;
                }
            }
            String next = node(root.path("_links").path("next"));
            if (next != null) {
                url = absolute(next);
            } else if (!confluence.v2() && got >= root.path("limit").asInt(100) && got > 0) {
                url = apiRoot + "/space?limit=100&start=" + (root.path("start").asInt(0) + got);
            } else {
                url = null;
            }
        }
        out.sort(java.util.Comparator.comparing(sp -> sp.key().toLowerCase(Locale.ROOT)));
        return new SpaceListing(out, url == null);
    }

    // ---------------------------------------------------------------- REST API v2 (Cloud)

    private Page<QuestionSummary> listQuestionsV2(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        if (!confluence.pageIds().isEmpty() || !confluence.ancestorPageId().isEmpty()) {
            return listTreeV2(filter, cursor);
        }
        if (confluence.contentTypes().stream().anyMatch(t -> !t.equalsIgnoreCase("page")) && cursor == null) {
            notes.add("REST API v2 mode collects pages only; other content types (" + String.join(",", confluence.contentTypes()) + ") are skipped.");
        }
        String url;
        if (cursor != null && cursor.startsWith("next:")) {
            url = absolute(cursor.substring("next:".length()));
        } else {
            StringBuilder b = new StringBuilder(apiRoot).append("/pages?limit=").append(confluence.pageSize())
                    .append("&status=current&sort=-modified-date");
            List<String> spaceIds = spaceIdsV2();
            if (!spaceIds.isEmpty()) {
                b.append("&space-id=").append(enc(String.join(",", spaceIds)));
            }
            url = b.toString();
        }
        JsonNode root = getJson(url);
        rememberBase(root);
        Instant since = modifiedSince == null ? null : Times.parse(modifiedSince).orElse(null);
        Instant createdAfter = filter.createdAfter() == null ? null : Times.parse(filter.createdAfter()).orElse(null);
        List<QuestionSummary> items = new ArrayList<>();
        boolean reachedOld = false;
        for (JsonNode node : results(root)) {
            String id = text(node, "id");
            if (id == null || id.isBlank()) {
                continue;
            }
            String modified = iso(node.path("version").path("createdAt").asText(null));
            Instant when = modified == null ? null : Times.parse(modified).orElse(null);
            if (since != null && when != null && when.isBefore(since)) {
                reachedOld = true; // sorted newest first: everything after this is older
                break;
            }
            Instant created = Times.parse(iso(node.path("createdAt").asText(null))).orElse(null);
            if (createdAfter != null && created != null && created.isBefore(createdAfter)) {
                continue;
            }
            if (!filter.isAll() && !filter.matches(labelsV2(id))) {
                continue;
            }
            items.add(new QuestionSummary(id, modified, browseUrlV2(node)));
        }
        String next = reachedOld ? null : node(root.path("_links").path("next"));
        return new Page<>(items, next == null ? null : "next:" + next);
    }

    /** Explicit page ids and/or every page under the ancestor page: listed once, then served from memory. */
    private Page<QuestionSummary> listTreeV2(TagFilter filter, String cursor) throws SourceException {
        if (treeIds == null || cursor == null) {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(confluence.pageIds());
            if (!confluence.ancestorPageId().isEmpty()) {
                ids.addAll(descendantsV2(confluence.ancestorPageId()));
            }
            if (!confluence.spaces().isEmpty()) {
                notes.add("Page ids / ancestor page are set, so source.confluence.spaces is not used to list pages.");
            }
            treeIds = new ArrayList<>(ids);
        }
        int from = cursor == null ? 0 : Integer.parseInt(cursor.substring("tree:".length()));
        int to = Math.min(treeIds.size(), from + confluence.pageSize());
        List<QuestionSummary> items = new ArrayList<>();
        for (String id : treeIds.subList(from, to)) {
            if (!filter.isAll() && !filter.matches(labelsV2(id))) {
                continue;
            }
            items.add(new QuestionSummary(id, null, null));
        }
        return new Page<>(items, to < treeIds.size() ? "tree:" + to : null);
    }

    private List<String> descendantsV2(String rootId) throws SourceException {
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(rootId));
        Set<String> seen = new java.util.HashSet<>(queue);
        boolean useDirect = true;
        while (!queue.isEmpty()) {
            String parent = queue.poll();
            String url = apiRoot + "/pages/" + enc(parent) + (useDirect ? "/direct-children" : "/children") + "?limit=250";
            while (url != null) {
                JsonNode root;
                try {
                    root = getJson(url);
                } catch (SourceException e) {
                    if (useDirect && e.kind() == SourceException.Kind.NOT_FOUND && parent.equals(rootId) && out.isEmpty()) {
                        useDirect = false; // older v2 sites only have /children
                        url = apiRoot + "/pages/" + enc(parent) + "/children?limit=250";
                        continue;
                    }
                    throw e;
                }
                for (JsonNode child : results(root)) {
                    String id = text(child, "id");
                    String type = child.path("type").asText("page");
                    if (id == null || !seen.add(id)) {
                        continue;
                    }
                    if (type.equals("page") || type.equals("folder")) {
                        queue.add(id);
                    }
                    if (type.equals("page")) {
                        out.add(id);
                    }
                }
                String next = node(root.path("_links").path("next"));
                url = next == null ? null : absolute(next);
            }
        }
        return out;
    }

    private List<String> spaceIdsV2() throws SourceException {
        if (resolvedSpaceIds != null) {
            return resolvedSpaceIds;
        }
        if (confluence.spaces().isEmpty()) {
            resolvedSpaceIds = List.of();
            return resolvedSpaceIds;
        }
        JsonNode root = getJson(apiRoot + "/spaces?limit=250&keys=" + enc(String.join(",", confluence.spaces())));
        List<String> ids = new ArrayList<>();
        Set<String> found = new java.util.HashSet<>();
        for (JsonNode space : results(root)) {
            String id = text(space, "id");
            String key = text(space, "key");
            if (id != null && key != null) {
                ids.add(id);
                found.add(key.toUpperCase(Locale.ROOT));
                spaceKeys.put(id, key);
            }
        }
        List<String> missing = confluence.spaces().stream().filter(k -> !found.contains(k.toUpperCase(Locale.ROOT))).toList();
        if (ids.isEmpty()) {
            throw new SourceException(SourceException.Kind.NOT_FOUND,
                    "None of the Confluence spaces " + confluence.spaces() + " were found (or this account cannot see them)");
        }
        if (!missing.isEmpty()) {
            notes.add("Confluence spaces not found or not visible: " + String.join(", ", missing));
        }
        resolvedSpaceIds = List.copyOf(ids);
        return resolvedSpaceIds;
    }

    private Question fetchQuestionV2(String id) throws SourceException {
        String format = "body.view".equals(confluence.bodyExpand()) ? "view" : "storage";
        JsonNode node = getJson(apiRoot + "/pages/" + enc(id) + "?body-format=" + format + "&include-labels=true&include-version=true");
        rememberBase(node);
        String title = text(node, "title");
        String body = bodyHtml(node);
        if (body == null) {
            body = "";
            notes.add("Page " + id + " had no readable body.storage; stored title and metadata only.");
        }
        List<String> tags;
        JsonNode labels = node.path("labels").path("results");
        if (labels.isArray()) {
            tags = new ArrayList<>();
            for (JsonNode label : labels) {
                String name = label.path("name").asText(null);
                if (name != null && !name.isBlank() && !tags.contains(name)) {
                    tags.add(name);
                }
            }
        } else {
            tags = new ArrayList<>(labelsV2(id));
        }
        if (confluence.includeSpaceTag()) {
            String key = spaceKeyV2(text(node, "spaceId"));
            if (key != null && tags.stream().noneMatch(t -> t.equalsIgnoreCase("space:" + key))) {
                tags.add("space:" + key);
            }
        }
        String author = text(node, "authorId");
        String created = iso(node.path("createdAt").asText(null));
        String modified = iso(node.path("version").path("createdAt").asText(null));
        List<Comment> comments = List.of();
        if (confluence.collectComments()) {
            try {
                comments = fetchCommentsV2(id);
            } catch (SourceException e) {
                notes.add("Comments for page " + id + " could not be collected (" + e.kind() + "); page body was kept.");
            }
        }
        return new Question(id, title == null ? "" : title, body, BodyFormat.HTML, browseUrlV2(node), tags, author, null,
                created, modified, comments, List.of());
    }

    private List<String> labelsV2(String pageId) throws SourceException {
        List<String> out = new ArrayList<>();
        String url = apiRoot + "/pages/" + enc(pageId) + "/labels?limit=250";
        while (url != null) {
            JsonNode root = getJson(url);
            for (JsonNode label : results(root)) {
                String name = label.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    out.add(name);
                }
            }
            String next = node(root.path("_links").path("next"));
            url = next == null ? null : absolute(next);
        }
        return out;
    }

    private String spaceKeyV2(String spaceId) {
        if (spaceId == null || spaceId.isBlank()) {
            return null;
        }
        String known = spaceKeys.get(spaceId);
        if (known != null) {
            return known;
        }
        try {
            String key = text(getJson(apiRoot + "/spaces/" + enc(spaceId)), "key");
            if (key != null && !key.isBlank()) {
                spaceKeys.put(spaceId, key);
                return key;
            }
        } catch (SourceException e) {
            // the space tag is optional
        }
        return null;
    }

    private List<Comment> fetchCommentsV2(String pageId) throws SourceException {
        List<Comment> out = new ArrayList<>();
        String url = apiRoot + "/pages/" + enc(pageId) + "/footer-comments?body-format=storage&limit=" + Math.min(confluence.pageSize(), 100);
        while (url != null) {
            JsonNode root = getJson(url);
            for (JsonNode node : results(root)) {
                out.add(new Comment(text(node, "id"), node.path("version").path("authorId").asText(null), bodyHtml(node),
                        iso(node.path("version").path("createdAt").asText(null))));
            }
            String next = node(root.path("_links").path("next"));
            url = next == null ? null : absolute(next);
        }
        return out;
    }

    private void rememberBase(JsonNode root) {
        String base = root.path("_links").path("base").asText(null);
        if (base != null && base.startsWith("http")) {
            v2Base = trimSlash(base);
        }
    }

    private String browseUrlV2(JsonNode node) {
        String webui = node.path("_links").path("webui").asText(null);
        String base = v2Base != null ? v2Base : siteBase + (confluence.cloud() ? "/wiki" : "");
        if (webui != null && webui.startsWith("http")) {
            return webui;
        }
        if (webui != null && !webui.isBlank()) {
            return base + (webui.startsWith("/") ? webui : "/" + webui);
        }
        String id = text(node, "id");
        return id == null ? base : base + "/pages/viewpage.action?pageId=" + id;
    }

    /** Turns a "_links.next" path (relative to the site origin) into a full address. */
    private String absolute(String next) {
        if (next.startsWith("http://") || next.startsWith("https://")) {
            return next;
        }
        URI base = URI.create(siteBase);
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        return origin + (next.startsWith("/") ? next : "/" + next);
    }

    private static String node(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode getJson(String url) throws SourceException {
        String used = browser == null ? null : browser.current();
        HttpSourceClient.Response response = client.exchange(url);
        if (response.status() == 401 && tvm != null) {
            // TVM tokens live ~15 minutes: get a fresh one and try once more.
            tvm.invalidate();
            response = client.exchange(url);
        }
        if (browser != null && (response.status() == 401 || Redirects.isRedirect(response.status()) || looksLikeLoginPage(response))) {
            // The session expired (the site sends us to the login page): sign in again in the browser, then retry once.
            browser.invalidate(used == null ? browser.current() : used);
            response = client.exchange(url);
        }
        if (Redirects.isRedirect(response.status()) && response.location() != null) {
            String where = loginHost(response.location());
            throw new SourceException(SourceException.Kind.AUTH_FAILED, "Confluence sent the request to a sign-in page (" + where + ")"
                    + (browser == null
                    ? ". Your company login runs in a browser: choose the sign-in 'Sign in with my browser' (source.auth.method=browser) and try again."
                    : " even after signing in again in the browser. Check that you signed in with an account that can read this Confluence site."));
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

    /** A 200 that is really an HTML login form (some SSO gateways answer like that instead of redirecting). */
    private static boolean looksLikeLoginPage(HttpSourceClient.Response r) {
        if (r.status() < 200 || r.status() >= 300 || r.contentType() == null || !r.contentType().toLowerCase(Locale.ROOT).contains("html")) {
            return false;
        }
        String body = r.body() == null ? "" : r.body().toLowerCase(Locale.ROOT);
        return body.contains("type=\"password\"") || body.contains("type='password'") || body.contains("saml") || body.contains("login");
    }

    private static String loginHost(String location) {
        try {
            String h = URI.create(location).getHost();
            return h == null ? "a login page" : h;
        } catch (IllegalArgumentException e) {
            return "a login page";
        }
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
