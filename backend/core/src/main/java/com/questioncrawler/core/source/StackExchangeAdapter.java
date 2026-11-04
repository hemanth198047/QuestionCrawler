package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
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

    private final Map<String, Question> questionCache = new ConcurrentHashMap<>();
    private final Map<String, List<Answer>> answerCache = new ConcurrentHashMap<>();
    private final Set<String> answersPrefetched = ConcurrentHashMap.newKeySet();
    private final AtomicLong backoffUntilMillis = new AtomicLong();
    private final AtomicInteger quotaRemaining = new AtomicInteger(-1);

    public StackExchangeAdapter(SourceSettings settings, HttpSourceClient client) {
        this(settings, client, System::getenv, Sleeper.REAL);
    }

    public StackExchangeAdapter(SourceSettings settings, HttpSourceClient client, Function<String, String> env, Sleeper sleeper) {
        this.settings = settings;
        this.client = client;
        this.env = env;
        this.sleeper = sleeper;
        this.apiBase = settings.baseUrl().endsWith("/") ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1) : settings.baseUrl();
        this.site = settings.site();
        String host = site + ".com";
        this.siteHost = host;
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
        int passes = all ? 1 : tags.size();
        int[] pos = parseCursor(cursor);
        int pass = pos[0];
        int page = pos[1];
        if (pass >= passes) {
            return new Page<>(List.of(), null);
        }
        String tagged = all ? String.join(";", tags) : tags.get(pass);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("tagged", tagged);
        params.put("sort", "activity");
        params.put("order", "asc");
        params.put("filter", "withbody");
        params.put("pagesize", String.valueOf(PAGE_SIZE));
        params.put("page", String.valueOf(page));
        Times.parse(modifiedSince).ifPresent(i -> params.put("min", String.valueOf(i.getEpochSecond())));
        Times.parse(filter.createdAfter()).ifPresent(i -> params.put("fromdate", String.valueOf(i.getEpochSecond())));

        JsonNode root = call("/questions", params);
        List<QuestionSummary> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            Question q = toQuestion(item);
            if (q.id() == null) {
                continue;
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
        prefetchAnswers(items, ids);

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
    private void prefetchAnswers(List<QuestionSummary> items, List<String> idsWithAnswers) throws SourceException {
        for (QuestionSummary s : items) {
            answerCache.put(s.id(), new ArrayList<>());
        }
        if (!idsWithAnswers.isEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("filter", "withbody");
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
                    }
                }
                if (!root.path("has_more").asBoolean(false)) {
                    break;
                }
            }
        }
        for (QuestionSummary s : items) {
            answersPrefetched.add(s.id());
        }
    }

    // ================================================================== single question

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        Question cached = questionCache.get(id);
        if (cached != null) {
            return cached;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("filter", "withbody");
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
        params.put("filter", "withbody");
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

    @Override
    public String ping() throws SourceException {
        JsonNode root = call("/info", new LinkedHashMap<>());
        JsonNode info = root.path("items").path(0);
        return "Stack Exchange API reachable; site '" + site + "' has " + info.path("total_questions").asText("?") + " questions; "
                + "request quota remaining: " + (quotaRemaining.get() < 0 ? "unknown" : quotaRemaining.get())
                + (settings.apiKeyEnv() != null && env.apply(settings.apiKeyEnv()) != null ? " (application key in use)" : " (anonymous: small daily quota)");
    }

    // ================================================================== HTTP + error handling

    private JsonNode call(String path, Map<String, String> params) throws SourceException {
        awaitBackoff();
        StringBuilder url = new StringBuilder(apiBase).append(path).append("?site=").append(enc(site));
        params.forEach((k, v) -> url.append('&').append(k).append('=').append(enc(v)));
        String key = settings.apiKeyEnv() == null || settings.apiKeyEnv().isBlank() ? null : env.apply(settings.apiKeyEnv());
        if (key != null && !key.isBlank()) {
            url.append("&key=").append(enc(key));
        }
        HttpSourceClient.Response response = client.exchange(url.toString());
        JsonNode root = parse(response.body());
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
        if (errorId == 403) {
            return new SourceException(SourceException.Kind.FORBIDDEN, "Stack Exchange API: access denied");
        }
        if (errorId >= 0) {
            return new SourceException(SourceException.Kind.FATAL, "Stack Exchange API error " + errorId + ": " + message);
        }
        // No JSON error body: fall back to generic HTTP status mapping (never includes the query string).
        return client.mapStatus(response.status(), response.retryAfter(), java.net.URI.create(url.substring(0, url.indexOf('?'))));
    }

    private static JsonNode parse(String body) throws SourceException {
        try {
            return Json.MAPPER.readTree(body);
        } catch (java.io.IOException e) {
            throw new SourceException(SourceException.Kind.FATAL, "Response is not valid JSON");
        }
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
