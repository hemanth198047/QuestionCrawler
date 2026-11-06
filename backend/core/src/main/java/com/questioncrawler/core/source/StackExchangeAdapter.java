package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.api.ApiCapabilities;
import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Comment;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.util.Json;
import com.questioncrawler.core.util.Sleeper;
import com.questioncrawler.core.util.Times;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter for the public, documented Stack Exchange API (https://api.stackexchange.com/docs), used for
 * stackoverflow.com and other public Stack Exchange sites. It is NOT a guess about an internal site:
 * a private site must still be integrated from its own documentation (see docs/LIVE_INTEGRATION.md).
 *
 * <p>Behaviour, from the API documentation:
 * <ul>
 *   <li>{@code tagged=a;b} is an AND constraint (max 5 tags). ANY mode therefore runs one pass per tag.</li>
 *   <li>Listing uses {@code sort=activity&order=asc}; {@code min} (modified since) applies to last activity,
 *       {@code fromdate} (created after) applies to creation date. Question 'modifiedAt' is the last
 *       activity date, so a new answer on an old question is noticed by incremental syncs.</li>
 *   <li>Responses carry {@code backoff} (seconds to wait before the next call), {@code has_more} and quota fields.</li>
 *   <li>Anonymous quota is small (about 300 requests/day), so the answers of a whole listing page are
 *       prefetched in one batched call and the question bodies come from the listing itself.</li>
 * </ul>
 * Comments are not collected (they cost extra quota). Content is CC BY-SA licensed: keep the attribution links.
 */
public final class StackExchangeAdapter implements SourceAdapter {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES_PER_BATCH = 50;
    private static final int MAX_TAGS_ALL = 5;
    private static final Pattern WAIT_SECONDS = Pattern.compile("(\\d+)\\s*seconds");
    private static final int CACHE_LIMIT = 5000;

    private final SourceSettings settings;
    private final HttpSourceClient client;
    private final Function<String, String> env;
    private final Sleeper sleeper;
    private final String apiBase;
    private final String site;
    private final String siteHost;
    private final ApiOptions api;
    private final Supplier<ApiCapabilities> capabilityProvider;
    private volatile ApiCapabilities caps;
    private volatile boolean capsLoaded;
    private volatile String bodyFilter;
    private volatile boolean filterCreationTried;

    private final Map<String, Question> questionCache = new ConcurrentHashMap<>();
    private final Map<String, List<Answer>> answerCache = new ConcurrentHashMap<>();
    private final Set<String> answersPrefetched = ConcurrentHashMap.newKeySet();
    private final AtomicLong backoffUntilMillis = new AtomicLong();
    private final AtomicInteger quotaRemaining = new AtomicInteger(-1);

    public StackExchangeAdapter(SourceSettings settings, HttpSourceClient client) {
        this(settings, client, System::getenv, Sleeper.REAL);
    }

    public StackExchangeAdapter(SourceSettings settings, HttpSourceClient client, Function<String, String> env, Sleeper sleeper) {
        this(settings, client, env, sleeper, null);
    }

    /**
     * @param capabilityProvider learns what the API supports from its Swagger documentation (called once, lazily); may be null,
     *                           in which case the standard Stack Exchange 2.3 feature set is assumed.
     */
    public StackExchangeAdapter(SourceSettings settings, HttpSourceClient client, Function<String, String> env, Sleeper sleeper,
                                Supplier<ApiCapabilities> capabilityProvider) {
        this.capabilityProvider = capabilityProvider;
        this.api = settings.api();
        this.bodyFilter = settings.api().bodyFilter();
        this.settings = settings;
        this.client = client;
        this.env = env;
        this.sleeper = sleeper;
        this.apiBase = settings.baseUrl().endsWith("/") ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1) : settings.baseUrl();
        this.site = settings.site();
        this.siteHost = site == null || site.isBlank() ? java.net.URI.create(apiBase).getHost() : site + ".com";
    }

    @Override
    public String systemId() {
        return settings.systemId();
    }

    @Override
    public boolean supportsModifiedSince() {
        return true;
    }

    /** Remaining request quota reported by the last response, or -1 when unknown. */
    public int quotaRemaining() {
        return quotaRemaining.get();
    }

    // ================================================================== what this API supports

    private ApiCapabilities capabilities() {
        if (!capsLoaded) {
            synchronized (this) {
                if (!capsLoaded) {
                    try {
                        caps = capabilityProvider == null ? null : capabilityProvider.get();
                    } catch (RuntimeException e) {
                        caps = null; // documentation unavailable: assume the standard feature set
                    }
                    capsLoaded = true;
                }
            }
        }
        return caps;
    }

    private boolean taggedSupported() { ApiCapabilities c = capabilities(); return c == null || c.taggedFilter(); }

    private boolean fromDateSupported() { ApiCapabilities c = capabilities(); return c == null || c.fromDate(); }

    private boolean minSupported() { ApiCapabilities c = capabilities(); return c == null || c.minActivity(); }

    private boolean hasCapability(String id) {
        ApiCapabilities c = capabilities();
        return c == null || c.capabilities().stream().anyMatch(x -> x.id().equals(id) && x.found());
    }

    // ================================================================== listing

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        if (filter.partial()) {
            throw new SourceException(SourceException.Kind.FATAL,
                    "Partial tag matching is not supported by the Stack Exchange API; use exact tags (partialTags=false).");
        }
        List<String> tags = filter.tags();
        if (tags.isEmpty()) {
            throw new SourceException(SourceException.Kind.FATAL, "At least one tag is required.");
        }
        boolean all = filter.mode() == TagMode.ALL;
        if (all && tags.size() > MAX_TAGS_ALL) {
            throw new SourceException(SourceException.Kind.FATAL, "The Stack Exchange API accepts at most " + MAX_TAGS_ALL + " tags in ALL mode.");
        }
        boolean serverTagFilter = taggedSupported();
        int passes = all || !serverTagFilter ? 1 : tags.size();
        int[] pos = parseCursor(cursor);
        int pass = pos[0];
        int page = pos[1];
        if (pass >= passes) {
            return new Page<>(List.of(), null);
        }
        String tagged = all ? String.join(";", tags) : tags.get(pass);

        Map<String, String> params = new LinkedHashMap<>();
        if (serverTagFilter) {
            params.put("tagged", tagged);
        }
        params.put("sort", "activity");
        params.put("order", "asc");
        params.put("filter", bodyFilter);
        params.put("pagesize", String.valueOf(PAGE_SIZE));
        params.put("page", String.valueOf(page));
        if (minSupported()) {
            Times.parse(modifiedSince).ifPresent(i -> params.put("min", String.valueOf(i.getEpochSecond())));
        }
        if (fromDateSupported()) {
            Times.parse(filter.createdAfter()).ifPresent(i -> params.put("fromdate", String.valueOf(i.getEpochSecond())));
        }
        java.time.Instant createdAfter = Times.parse(filter.createdAfter()).orElse(null);
        java.time.Instant since = Times.parse(modifiedSince).orElse(null);

        JsonNode root = listWithBodyFilter("/questions", params);
        List<QuestionSummary> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String> commentedQuestions = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            Question q = toQuestion(item);
            if (q.id() == null) {
                continue;
            }
            // Features the API's documentation does not offer are applied here instead.
            if (!serverTagFilter && !filter.matches(q.tags())) {
                continue;
            }
            if (!fromDateSupported() && createdAfter != null && Times.parse(q.createdAt()).map(c -> c.isBefore(createdAfter)).orElse(false)) {
                continue;
            }
            if (!minSupported() && since != null && Times.parse(q.modifiedAt()).map(m -> m.isBefore(since)).orElse(false)) {
                continue;
            }
            if (item.path("comment_count").asInt(0) > 0) {
                commentedQuestions.add(q.id());
            }
            if (questionCache.size() > CACHE_LIMIT) {
                questionCache.clear();
                answerCache.clear();
                answersPrefetched.clear();
            }
            questionCache.put(q.id(), q);
            items.add(new QuestionSummary(q.id(), q.modifiedAt(), q.url()));
            if (item.path("answer_count").asInt(0) > 0) {
                ids.add(q.id());
            }
            answersPrefetched.remove(q.id());
        }
        prefetchAnswers(items, ids, commentedQuestions);

        String next;
        if (root.path("has_more").asBoolean(false)) {
            next = pass + ":" + (page + 1);
        } else if (pass + 1 < passes) {
            next = (pass + 1) + ":1";
        } else {
            next = null;
        }
        return new Page<>(items, next);
    }

    /** One batched call per listing page (up to 100 ids) instead of one call per question. */
    private void prefetchAnswers(List<QuestionSummary> items, List<String> idsWithAnswers, List<String> commentedQuestions) throws SourceException {
        List<String> commentedAnswers = new ArrayList<>();
        for (QuestionSummary s : items) {
            answerCache.put(s.id(), new ArrayList<>());
        }
        if (!idsWithAnswers.isEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("filter", bodyFilter);
            params.put("sort", "creation");
            params.put("order", "asc");
            params.put("pagesize", String.valueOf(PAGE_SIZE));
            String path = "/questions/" + String.join(";", idsWithAnswers) + "/answers";
            for (int page = 1; page <= MAX_PAGES_PER_BATCH; page++) {
                params.put("page", String.valueOf(page));
                JsonNode root = call(path, params);
                for (JsonNode a : root.path("items")) {
                    String qid = a.path("question_id").asText(null);
                    if (qid != null && answerCache.containsKey(qid)) {
                        answerCache.get(qid).add(toAnswer(a, qid));
                        if (a.path("comment_count").asInt(0) > 0) {
                            commentedAnswers.add(a.path("answer_id").asText());
                        }
                    }
                }
                if (!root.path("has_more").asBoolean(false)) {
                    break;
                }
            }
        }
        attachComments(items, commentedQuestions, commentedAnswers);
        for (QuestionSummary s : items) {
            answersPrefetched.add(s.id());
        }
    }

    /** Optional: comments under questions and answers, fetched in batches of up to 100 posts. */
    private void attachComments(List<QuestionSummary> items, List<String> questionIds, List<String> answerIds) throws SourceException {
        if (!api.collectComments()) {
            return;
        }
        Map<String, List<Comment>> onQuestions = hasCapability("questionComments") ? fetchComments("/questions/%s/comments", questionIds) : Map.of();
        Map<String, List<Comment>> onAnswers = hasCapability("answerComments") ? fetchComments("/answers/%s/comments", answerIds) : Map.of();
        for (QuestionSummary s : items) {
            Question q = questionCache.get(s.id());
            List<Answer> answers = answerCache.getOrDefault(s.id(), List.of());
            List<Answer> withComments = new ArrayList<>();
            for (Answer a : answers) {
                withComments.add(onAnswers.containsKey(a.id()) ? a.withBody(a.body(), a.bodyFormat(), onAnswers.get(a.id())) : a);
            }
            if (answerCache.containsKey(s.id())) {
                answerCache.put(s.id(), withComments);
            }
            if (q != null && onQuestions.containsKey(s.id())) {
                questionCache.put(s.id(), q.withContent(q.body(), q.bodyFormat(), onQuestions.get(s.id()), q.answers()));
            }
        }
    }

    private Map<String, List<Comment>> fetchComments(String pathTemplate, List<String> ids) throws SourceException {
        Map<String, List<Comment>> out = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += PAGE_SIZE) {
            List<String> batch = ids.subList(from, Math.min(ids.size(), from + PAGE_SIZE));
            Map<String, String> params = new LinkedHashMap<>();
            params.put("filter", bodyFilter);
            params.put("sort", "creation");
            params.put("order", "asc");
            params.put("pagesize", String.valueOf(PAGE_SIZE));
            for (int page = 1; page <= MAX_PAGES_PER_BATCH; page++) {
                params.put("page", String.valueOf(page));
                JsonNode root;
                try {
                    root = call(String.format(pathTemplate, String.join(";", batch)), params);
                } catch (SourceException e) {
                    if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED) {
                        throw e;
                    }
                    return out; // comments are optional context: never fail a collection because of them
                }
                for (JsonNode c : root.path("items")) {
                    String post = c.path("post_id").asText(null);
                    if (post != null) {
                        out.computeIfAbsent(post, k -> new ArrayList<>()).add(new Comment(c.path("comment_id").asText(null), author(c),
                                c.path("body").asText(""), epoch(c, "creation_date")));
                    }
                }
                if (!root.path("has_more").asBoolean(false)) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Some APIs only include question and answer text with a custom filter. If the first page comes back without any text
     * (or the API does not know the filter name), a suitable filter is created with the API's own /filters/create endpoint.
     */
    private JsonNode listWithBodyFilter(String path, Map<String, String> params) throws SourceException {
        JsonNode root;
        try {
            root = call(path, params);
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.FATAL && e.getMessage().toLowerCase().contains("filter") && createBodyFilter()) {
                params.put("filter", bodyFilter);
                return call(path, params);
            }
            throw e;
        }
        JsonNode items = root.path("items");
        if (items.isArray() && !items.isEmpty() && !items.get(0).has("body") && createBodyFilter()) {
            params.put("filter", bodyFilter);
            return call(path, params);
        }
        return root;
    }

    private boolean createBodyFilter() throws SourceException {
        if (filterCreationTried || !hasCapability("filterCreate")) {
            return false;
        }
        filterCreationTried = true;
        Map<String, String> fp = new LinkedHashMap<>();
        fp.put("include", "question.body;answer.body;comment.body");
        fp.put("base", "default");
        fp.put("unsafe", "false");
        try {
            String created = call("/filters/create", fp).path("items").path(0).path("filter").asText("");
            if (!created.isBlank()) {
                bodyFilter = created;
                return true;
            }
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.AUTH_FAILED) {
                throw e;
            }
        }
        return false;
    }

    // ================================================================== single question

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        Question cached = questionCache.get(id);
        if (cached != null) {
            return cached;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("filter", bodyFilter);
        JsonNode root = call("/questions/" + encodeId(id), params);
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) {
            // The API answers 200 with no items for deleted or never-existing questions: not provably "deleted".
            throw new SourceException(SourceException.Kind.NOT_FOUND, "Question " + id + " is not returned by the API (deleted, never existed, or not visible)");
        }
        return toQuestion(items.get(0));
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) throws SourceException {
        if (cursor == null && answersPrefetched.contains(questionId)) {
            List<Answer> cached = answerCache.remove(questionId);
            questionCache.remove(questionId);
            answersPrefetched.remove(questionId);
            if (cached != null) {
                return new Page<>(cached, null);
            }
        }
        int page = 1;
        if (cursor != null) {
            if (!cursor.startsWith("p:")) {
                throw new SourceException(SourceException.Kind.FATAL, "Invalid cursor: " + cursor);
            }
            page = Integer.parseInt(cursor.substring(2));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("filter", bodyFilter);
        params.put("sort", "creation");
        params.put("order", "asc");
        params.put("pagesize", String.valueOf(PAGE_SIZE));
        params.put("page", String.valueOf(page));
        JsonNode root = call("/questions/" + encodeId(questionId) + "/answers", params);
        List<Answer> answers = new ArrayList<>();
        for (JsonNode a : root.path("items")) {
            answers.add(toAnswer(a, questionId));
        }
        return new Page<>(answers, root.path("has_more").asBoolean(false) ? "p:" + (page + 1) : null);
    }

    /** Number of questions carrying the tag on the whole site (documented /tags/{tag}/info), or -1 when the tag does not exist. */
    public long tagCount(String tag) throws SourceException {
        JsonNode root = call("/tags/" + enc(TagFilter.normalize(tag)) + "/info", new LinkedHashMap<>());
        JsonNode items = root.path("items");
        return items.isArray() && !items.isEmpty() ? items.get(0).path("count").asLong(0) : -1;
    }

    @Override
    public String ping() throws SourceException {
        String where = site == null || site.isBlank() ? "the internal API" : "site '" + site + "'";
        JsonNode root;
        String size;
        if (hasCapability("info")) {
            root = call("/info", new LinkedHashMap<>());
            size = root.path("items").path(0).path("total_questions").asText("?");
        } else {
            Map<String, String> probe = new LinkedHashMap<>();
            probe.put("pagesize", "1");
            probe.put("filter", "total");
            root = call("/questions", probe);
            size = root.has("total") ? root.get("total").asText() : "?";
        }
        boolean keyed = settings.apiKeyEnv() != null && env.apply(settings.apiKeyEnv()) != null;
        boolean publicSite = site != null && !site.isBlank();
        String keyNote = keyed ? (publicSite ? " (application key in use)" : " (access key in use)")
                : (publicSite ? " (anonymous: small daily quota)" : " (no access key set)");
        return (publicSite ? "Stack Exchange API reachable; " : "API reachable; ") + where + " has " + size + " questions; request quota remaining: "
                + (quotaRemaining.get() < 0 ? "unknown" : quotaRemaining.get()) + keyNote;
    }

    // ================================================================== HTTP + error handling

    private JsonNode call(String path, Map<String, String> params) throws SourceException {
        awaitBackoff();
        StringBuilder url = new StringBuilder(apiBase).append(path);
        char sep = '?';
        if (site != null && !site.isBlank()) {
            url.append(sep).append("site=").append(enc(site));
            sep = '&';
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            url.append(sep).append(e.getKey()).append('=').append(enc(e.getValue()));
            sep = '&';
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String key = settings.apiKeyEnv() == null || settings.apiKeyEnv().isBlank() ? null : env.apply(settings.apiKeyEnv());
        if (key != null && !key.isBlank()) {
            if ("header".equals(api.keyIn())) {
                headers.put(api.keyName(), key);
            } else {
                url.append(sep).append(enc(api.keyName())).append('=').append(enc(key));
                sep = '&';
            }
        }
        String token = api.accessTokenEnv() == null ? null : env.apply(api.accessTokenEnv());
        if (token != null && !token.isBlank()) {
            url.append(sep).append("access_token=").append(enc(token));
        }
        HttpSourceClient.Response response = client.exchange(url.toString(), headers);
        JsonNode root = parseBody(response, url.toString());
        if (response.status() < 200 || response.status() >= 300) {
            throw mapError(response, root, url.toString());
        }
        if (root.has("quota_remaining")) {
            quotaRemaining.set(root.get("quota_remaining").asInt());
        }
        int backoff = root.path("backoff").asInt(0);
        if (backoff > 0) {
            backoffUntilMillis.accumulateAndGet(System.currentTimeMillis() + backoff * 1000L, Math::max);
        }
        return root;
    }

    private void awaitBackoff() throws SourceException {
        long wait = backoffUntilMillis.get() - System.currentTimeMillis();
        if (wait > 0) {
            try {
                sleeper.sleep(Duration.ofMillis(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException(SourceException.Kind.FATAL, "Interrupted while honouring the API's backoff request", null, e);
            }
        }
    }

    /** The API reports most problems as HTTP 400 with a JSON body containing error_id / error_message. */
    private SourceException mapError(HttpSourceClient.Response response, JsonNode root, String url) {
        int errorId = root.path("error_id").asInt(-1);
        String message = root.path("error_message").asText("");
        if (message.isBlank()) {
            for (String field : new String[] {"message", "error", "detail", "title"}) {
                if (root.path(field).isTextual() && !root.path(field).asText().isBlank()) {
                    message = root.path(field).asText();
                    break;
                }
            }
        }
        if (errorId == 502) { // throttle violation or quota exhausted
            Matcher m = WAIT_SECONDS.matcher(message);
            long seconds = m.find() ? Long.parseLong(m.group(1)) : 60;
            if (Duration.ofSeconds(seconds).compareTo(Retrier.MAX_HONOURED_RETRY_AFTER) > 0) {
                return new SourceException(SourceException.Kind.QUOTA_EXHAUSTED,
                        "Stack Exchange request quota is used up; it resets in about " + (seconds / 3600) + "h "
                                + ((seconds % 3600) / 60) + "m. Set an application key in the environment variable named by "
                                + "source.stackexchange.keyEnv to raise the daily limit, or retry later.");
            }
            return new SourceException(SourceException.Kind.RATE_LIMITED, "Stack Exchange throttle: " + message, Duration.ofSeconds(seconds), null);
        }
        if (errorId == 401 || errorId == 402 || errorId == 405 || errorId == 406) {
            return new SourceException(SourceException.Kind.AUTH_FAILED, "The API rejected our credentials (error " + errorId + (message.isBlank() ? "" : ": " + message)
                    + "). Check the API access key (and access token, if used) and how it is sent.");
        }
        if (errorId == 403) {
            return new SourceException(SourceException.Kind.FORBIDDEN, "The API denied access (error 403): the key may lack permission for this content.");
        }
        if (errorId == 503) {
            return new SourceException(SourceException.Kind.TRANSIENT, "The API is temporarily unavailable (error 503).");
        }
        if (errorId >= 0) {
            return new SourceException(SourceException.Kind.FATAL, "Stack Exchange API error " + errorId + ": " + message);
        }
        // A refusal that says which sign-in it wants (for example {"message":"Missing bearer token"}) is explained as such.
        String authKind = ResponseInspector.authKind(response.status(), response.wwwAuthenticate(), response.body());
        if (authKind != null) {
            return new SourceException(SourceException.Kind.AUTH_FAILED, "HTTP " + response.status() + ": the API refused the request"
                    + (message.isBlank() ? "" : " (" + message + ")") + ". " + ResponseInspector.authHints(authKind).get(0));
        }
        // No JSON error body: fall back to generic HTTP status mapping (never includes the query string).
        return client.mapStatus(response.status(), response.retryAfter(), java.net.URI.create(ResponseInspector.withoutQuery(url)));
    }

    /** The answer as JSON. If it is not JSON, the error says what it is (a sign-in page, a redirect, ...) and what to check. */
    private JsonNode parseBody(HttpSourceClient.Response response, String url) throws SourceException {
        JsonNode node = null;
        try {
            node = Json.MAPPER.readTree(ResponseInspector.stripNoise(response.body()));
        } catch (java.io.IOException e) {
            // explained below
        }
        if (node != null && node.isObject()) {
            return node;
        }
        List<String> secrets = new ArrayList<>();
        for (String name : new String[] {settings.apiKeyEnv(), api.accessTokenEnv(), settings.tokenEnv()}) {
            if (name != null && !name.isBlank() && env.apply(name) != null) {
                secrets.add(env.apply(name));
            }
        }
        var inspection = ResponseInspector.inspect(response.status(), response.contentType(), response.location(), response.wwwAuthenticate(), response.body(), secrets);
        boolean ok = response.status() >= 200 && response.status() < 300;
        boolean authText = ResponseInspector.authKind(response.status(), response.wwwAuthenticate(), ResponseInspector.stripNoise(response.body())) != null;
        SourceException.Kind kind = authText ? SourceException.Kind.AUTH_FAILED
                : ok ? (inspection.kind().equals("HTML_LOGIN") ? SourceException.Kind.AUTH_FAILED : SourceException.Kind.FATAL)
                : inspection.kind().equals("REDIRECT") && inspection.hints().get(0).contains("sign-in")
                        ? SourceException.Kind.AUTH_FAILED
                        : client.mapStatus(response.status(), response.retryAfter(), java.net.URI.create(ResponseInspector.withoutQuery(url))).kind();
        String hint = inspection.hints().isEmpty() ? "" : " " + inspection.hints().get(0);
        String said = !inspection.kind().startsWith("HTML") && !inspection.preview().isBlank() && inspection.preview().length() <= 200
                ? " The site said: \"" + inspection.preview().strip() + "\"." : "";
        throw new SourceException(kind, "The API did not answer with JSON. " + inspection.summary() + said + hint
                + " (On the Connect screen, 'See what the API really answers' shows the full answer.)");
    }

    // ================================================================== mapping

    private Question toQuestion(JsonNode n) {
        String id = n.path("question_id").asText(null);
        List<String> tags = new ArrayList<>();
        n.path("tags").forEach(t -> tags.add(t.asText()));
        String link = n.path("link").asText(null);
        return new Question(id, n.path("title").asText(""), n.path("body").asText(""), BodyFormat.HTML,
                link != null ? link : id == null ? null : "https://" + siteHost + "/questions/" + id,
                tags, author(n), n.has("score") ? n.get("score").asInt() : null, epoch(n, "creation_date"),
                epoch(n, "last_activity_date"), List.<Comment>of(), List.of());
    }

    private Answer toAnswer(JsonNode n, String questionId) {
        String id = n.path("answer_id").asText(null);
        String link = n.path("link").asText(null);
        String modified = epoch(n, "last_activity_date");
        return new Answer(id, n.path("body").asText(""), BodyFormat.HTML,
                link != null ? link : "https://" + siteHost + "/questions/" + questionId + "#" + id,
                n.path("is_accepted").asBoolean(false), n.has("score") ? n.get("score").asInt() : null, author(n),
                epoch(n, "creation_date"), modified, List.of());
    }

    private static String author(JsonNode n) {
        JsonNode name = n.path("owner").path("display_name");
        return name.isMissingNode() || name.isNull() ? null : name.asText();
    }

    private static String epoch(JsonNode n, String field) {
        return n.has(field) && n.get(field).canConvertToLong() ? Instant.ofEpochSecond(n.get(field).asLong()).toString() : null;
    }

    private static int[] parseCursor(String cursor) throws SourceException {
        if (cursor == null) {
            return new int[] {0, 1};
        }
        String[] parts = cursor.split(":");
        try {
            if (parts.length == 2) {
                return new int[] {Math.max(0, Integer.parseInt(parts[0])), Math.max(1, Integer.parseInt(parts[1]))};
            }
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw new SourceException(SourceException.Kind.FATAL, "Invalid cursor: " + cursor);
    }

    private static String encodeId(String id) throws SourceException {
        if (!id.matches("\\d{1,12}")) {
            throw new SourceException(SourceException.Kind.FATAL, "Invalid question id: " + id);
        }
        return id;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
