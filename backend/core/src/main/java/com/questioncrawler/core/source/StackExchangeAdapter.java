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

    private static final int IDS_PER_CALL = 100; // documented cap on {ids} parameters
    private volatile int pageSize = 100;
    private volatile boolean pageSizeFromDocs;
    private static final int MAX_PAGES_PER_BATCH = 50;
    private static final int MAX_TAGS_ALL = 5;
    private static final Pattern WAIT_SECONDS = Pattern.compile("(\\d+)\\s*seconds");
    private static final int CACHE_LIMIT = 5000;

    private final SourceSettings settings;
    private final HttpSourceClient client;
    private final Function<String, String> env;
    private final Sleeper sleeper;
    private volatile String apiBase;
    private volatile String redirectedFrom;
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

    /** Page size to ask for: what the documentation allows (largest, up to 100), else 100 — corrected from the API's own error if needed. */
    private int pageSize() {
        if (!pageSizeFromDocs) {
            ApiCapabilities c = capabilities();
            pageSizeFromDocs = true;
            if (c != null && !c.pageSizes().isEmpty()) {
                pageSize = c.pageSizes().stream().filter(x -> x <= 100).max(Integer::compare).orElse(c.pageSizes().get(0));
            }
        }
        return pageSize;
    }

    /** The names this API uses for paging, tags, sort and dates: from its documentation, else the Stack Exchange defaults. */
    private com.questioncrawler.core.api.ParamMap pm() {
        ApiCapabilities c = capabilities();
        return c == null ? com.questioncrawler.core.api.ParamMap.defaults() : c.params();
    }

    private void putFilter(Map<String, String> params) {
        com.questioncrawler.core.api.ParamMap p = pm();
        if (p.filter() != null || !p.documented()) {
            params.put(p.filter() == null ? "filter" : p.filter(), bodyFilter);
        }
    }

    /** Sort and order, in the words the API documents (omitted when it has no such parameters). */
    private void putOrder(Map<String, String> params, String preferred) {
        com.questioncrawler.core.api.ParamMap p = pm();
        if (p.sort() != null) {
            params.put(p.sort(), p.sortFor(preferred));
        }
        if (p.order() != null) {
            params.put(p.order(), p.ascending());
        }
    }

    private static String dateValue(java.time.Instant when, boolean text) {
        return text ? when.toString() : String.valueOf(when.getEpochSecond());
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
        if (filter.partial() && !filter.isAll()) {
            throw new SourceException(SourceException.Kind.FATAL,
                    "Partial tag matching is not supported by the Stack Exchange API; use exact tags (partialTags=false).");
        }
        List<String> tags = filter.tags();
        if (tags.isEmpty()) {
            throw new SourceException(SourceException.Kind.FATAL, "At least one tag is required.");
        }
        boolean all = filter.mode() == TagMode.ALL;
        if (all && !filter.isAll() && tags.size() > MAX_TAGS_ALL) {
            throw new SourceException(SourceException.Kind.FATAL, "The Stack Exchange API accepts at most " + MAX_TAGS_ALL + " tags in ALL mode.");
        }
        boolean wildcard = filter.isAll(); // every tag: ask for questions without any tag filter
        boolean serverTagFilter = taggedSupported();
        int passes = wildcard || all || !serverTagFilter ? 1 : tags.size();
        int[] pos = parseCursor(cursor);
        int pass = pos[0];
        int page = pos[1];
        if (pass >= passes) {
            return new Page<>(List.of(), null);
        }
        String tagged = (all ? String.join(";", tags) : tags.get(pass));
        tagged = java.util.Arrays.stream(tagged.split(";")).map(t -> exactTags.getOrDefault(t, t)).collect(java.util.stream.Collectors.joining(pm().tagSeparator()));

        Map<String, String> params = new LinkedHashMap<>();
        if (serverTagFilter && !wildcard) {
            params.put(pm().tag() == null ? "tagged" : pm().tag(), tagged);
        }
        putOrder(params, "activity");
        putFilter(params);
        params.put(pm().size(), String.valueOf(pageSize()));
        params.put(pm().page(), String.valueOf(page));
        if (minSupported()) {
            Times.parse(modifiedSince).ifPresent(i -> params.put(pm().min(), dateValue(i, pm().minIsText())));
        }
        if (fromDateSupported()) {
            Times.parse(filter.createdAfter()).ifPresent(i -> params.put(pm().from(), dateValue(i, pm().fromIsText())));
        }
        java.time.Instant createdAfter = Times.parse(filter.createdAfter()).orElse(null);
        java.time.Instant since = Times.parse(modifiedSince).orElse(null);

        JsonNode root = listWithBodyFilter("/questions", params);
        lastListing = "GET /questions with " + params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
        if (serverTagFilter && !wildcard && (site == null || site.isBlank()) && page == 1 && ApiJson.items(root).isArray() && ApiJson.items(root).isEmpty() && tagResolutionTried.add(tagged)) {
            // Nothing found: an internal site may spell the tag differently (for example in capitals). Find its own spelling and ask again.
            // The lookup is a convenience: if it fails for any reason the collection simply goes on with the empty answer.
            try {
                List<String> resolved = new ArrayList<>();
                boolean changed = false;
                for (String t : tagged.split(";")) {
                    String exact = findExactTagName(t);
                    resolved.add(exact == null ? t : exact);
                    if (exact != null && !exact.equals(t)) {
                        exactTags.put(t, exact);
                        changed = true;
                    }
                }
                if (changed) {
                    params.put(pm().tag() == null ? "tagged" : pm().tag(), String.join(pm().tagSeparator(), resolved));
                    root = listWithBodyFilter("/questions", params);
                }
            } catch (SourceException ignored) {
                // keep the original (empty) answer
            }
        }
        List<QuestionSummary> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<String> commentedQuestions = new ArrayList<>();
        JsonNode listed = ApiJson.items(root);
        if (!listed.isArray() && root.isObject()) {
            notes.add("The API's answer has no list of questions. Fields in the answer: " + fieldsOf(root) + ".");
        }
        int unreadable = 0;
        int withTags = 0;
        int mismatched = 0;
        JsonNode firstUnreadable = null;
        for (JsonNode item : listed) {
            Question q = toQuestion(item);
            if (q.id() == null) {
                unreadable++;
                if (firstUnreadable == null) {
                    firstUnreadable = item;
                }
                continue;
            }
            // Features the API's documentation does not offer (or that it ignores) are applied here instead.
            if ((!serverTagFilter || tagFilterIgnored) && !wildcard && !filter.matches(q.tags())) {
                continue;
            }
            if (!q.tags().isEmpty()) {
                withTags++;
                if (!wildcard && !filter.matches(q.tags())) {
                    mismatched++;
                }
            }
            if (!fromDateSupported() && createdAfter != null && Times.parse(q.createdAt()).map(c -> c.isBefore(createdAfter)).orElse(false)) {
                continue;
            }
            if (!minSupported() && since != null && Times.parse(q.modifiedAt()).map(m -> m.isBefore(since)).orElse(false)) {
                continue;
            }
            Integer commentCount = ApiJson.integer(item, "comment_count", "commentCount", "commentsCount");
            if (commentCount != null && commentCount > 0) {
                commentedQuestions.add(q.id());
            }
            if (questionCache.size() > CACHE_LIMIT) {
                questionCache.clear();
                answerCache.clear();
                answersPrefetched.clear();
            }
            questionCache.put(q.id(), q);
            items.add(new QuestionSummary(q.id(), q.modifiedAt(), q.url()));
            Integer answerCount = ApiJson.integer(item, "answer_count", "answerCount", "answersCount", "numberOfAnswers");
            if (answerCount == null || answerCount > 0) { // a list that does not say how many answers there are: look
                ids.add(q.id());
            }
            answersPrefetched.remove(q.id());
        }
        if (serverTagFilter && !wildcard && !tagFilterIgnored && capabilities() == null && withTags >= 3 && mismatched * 4 >= withTags * 3) {
            // Without documentation the tag parameter's name is a guess. Most questions returned lack the tag: the site ignored the parameter.
            tagFilterIgnored = true;
            notes.add("The site ignored the tag parameter '" + params.keySet().stream().filter(k -> k.toLowerCase().contains("tag")).findFirst().orElse("tagged")
                    + "' (most questions it returned do not carry the requested tag), so tags are matched here instead, which downloads more. "
                    + "Add the API documentation on the Connect screen so the right parameter name is used.");
            items.removeIf(i -> {
                Question c = questionCache.get(i.id());
                return c != null && !filter.matches(c.tags());
            });
            ids.removeIf(id -> {
                Question c = questionCache.get(id);
                return c != null && !filter.matches(c.tags());
            });
        }
        if (unreadable > 0) {
            notes.add("The API returned " + listed.size() + " question(s) on a page but " + unreadable + " could not be read: no question id was found (looked for question_id, questionId, id, postId). "
                    + "Fields on the first one: " + fieldsOf(firstUnreadable) + ".");
        }
        prefetchAnswers(items, ids, commentedQuestions);

        String next;
        if (ApiJson.hasMore(root, page, pageSize(), ApiJson.items(root).size())) {
            next = pass + ":" + (page + 1);
        } else if (pass + 1 < passes) {
            next = (pass + 1) + ":1";
        } else {
            next = null;
        }
        return new Page<>(items, next);
    }

    /** One batched call per listing page (up to 100 ids) instead of one call per question. */
    private volatile boolean singleAnswers;
    private volatile boolean tagFilterIgnored; // the site returned questions that do not carry the requested tag: it ignores our tag parameter
    private volatile String lastListing;

    /** The most recent question-listing request, as parameter names and values (the access key is never part of it). */
    public String lastListingRequest() {
        return lastListing;
    }
    private final List<String> notes = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public List<String> notes() {
        synchronized (notes) {
            return new ArrayList<>(new java.util.LinkedHashSet<>(notes));
        }
    }

    private static String fieldsOf(JsonNode node) {
        List<String> names = ApiJson.fieldNames(node);
        return names.isEmpty() ? "none" : String.join(", ", names.size() > 25 ? names.subList(0, 25) : names) + (names.size() > 25 ? ", …" : "");
    }

    /** Does the documentation describe the answers endpoint with ONE question id ({id}) rather than a list ({ids})? */
    private boolean documentedSingleAnswers() {
        ApiCapabilities c = capabilities();
        if (c == null) {
            return false;
        }
        return c.capabilities().stream().anyMatch(x -> x.id().equals("answers") && x.found() && x.path() != null
                && x.path().matches("(?i).*\\{(id|questionId|question_id)}.*") && !x.path().toLowerCase().contains("{ids}"));
    }

    /**
     * Answers of one question (single) or of several (a list of ids), page by page. With a single question the answers belong to it even if
     * the API does not repeat the question id on each answer.
     */
    private void loadAnswers(String idPart, String singleQuestionId, List<String> commentedAnswers) throws SourceException {
        Map<String, String> params = new LinkedHashMap<>();
        putFilter(params);
        putOrder(params, "creation");
        params.put(pm().size(), String.valueOf(pageSize()));
        String path = "/questions/" + idPart + "/answers";
        for (int page = 1; page <= MAX_PAGES_PER_BATCH; page++) {
            params.put(pm().page(), String.valueOf(page));
            JsonNode root = call(path, params);
            for (JsonNode a : ApiJson.items(root)) {
                String qid = singleQuestionId != null ? singleQuestionId : ApiJson.id(a, "question_id", "questionId", "parentId", "parent_id");
                if (qid != null && answerCache.containsKey(qid)) {
                    answerCache.get(qid).add(toAnswer(a, qid));
                    Integer ac = ApiJson.integer(a, "comment_count", "commentCount", "commentsCount");
                    if (ac != null && ac > 0) {
                        commentedAnswers.add(ApiJson.id(a, "answer_id", "answerId", "id"));
                    }
                }
            }
            if (!ApiJson.hasMore(root, page, pageSize(), ApiJson.items(root).size())) {
                break;
            }
        }
    }

    private void prefetchAnswers(List<QuestionSummary> items, List<String> idsWithAnswers, List<String> commentedQuestions) throws SourceException {
        List<String> commentedAnswers = new ArrayList<>();
        for (QuestionSummary s : items) {
            answerCache.put(s.id(), new ArrayList<>());
        }
        if (!idsWithAnswers.isEmpty()) {
            boolean batched = !singleAnswers && !documentedSingleAnswers() && idsWithAnswers.size() > 0;
            if (batched) {
                try {
                    // With exactly one question the answers belong to it even if the API does not repeat its id on each answer.
                    loadAnswers(String.join(";", idsWithAnswers), idsWithAnswers.size() == 1 ? idsWithAnswers.get(0) : null, commentedAnswers);
                } catch (SourceException e) {
                    // An API that wants ONE question per call refuses a list of ids: switch to one call per question and remember it.
                    boolean refusal = e.kind() == SourceException.Kind.FATAL || e.kind() == SourceException.Kind.NOT_FOUND;
                    if (!refusal || idsWithAnswers.size() < 2) {
                        throw e;
                    }
                    singleAnswers = true;
                    notes.add("The API does not accept several question ids in one answers request, so answers are fetched one question at a time (slower).");
                    for (String id : idsWithAnswers) {
                        answerCache.put(id, new ArrayList<>());
                    }
                }
            }
            if (!batched || singleAnswers) {
                for (String id : idsWithAnswers) {
                    loadAnswers(enc(id), id, commentedAnswers);
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
        for (int from = 0; from < ids.size(); from += IDS_PER_CALL) {
            List<String> batch = ids.subList(from, Math.min(ids.size(), from + IDS_PER_CALL));
            Map<String, String> params = new LinkedHashMap<>();
            putFilter(params);
            putOrder(params, "creation");
            params.put(pm().size(), String.valueOf(pageSize()));
            for (int page = 1; page <= MAX_PAGES_PER_BATCH; page++) {
                params.put(pm().page(), String.valueOf(page));
                JsonNode root;
                try {
                    root = call(String.format(pathTemplate, String.join(";", batch)), params);
                } catch (SourceException e) {
                    if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED) {
                        throw e;
                    }
                    return out; // comments are optional context: never fail a collection because of them
                }
                for (JsonNode c : ApiJson.items(root)) {
                    String post = ApiJson.id(c, "post_id", "postId", "parentId", "parent_id");
                    if (post != null) {
                        String text = ApiJson.text(c, "body", "text", "content", "bodyHtml");
                        out.computeIfAbsent(post, k -> new ArrayList<>()).add(new Comment(ApiJson.id(c, "comment_id", "commentId", "id"), author(c),
                                text == null ? "" : text, ApiJson.instant(c, "creation_date", "creationDate", "createdAt", "created_at", "created")));
                    }
                }
                if (!ApiJson.hasMore(root, page, pageSize(), ApiJson.items(root).size())) {
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
                putFilter(params);
                return call(path, params);
            }
            throw e;
        }
        JsonNode items = ApiJson.items(root);
        if (items.isArray() && !items.isEmpty() && !items.get(0).has("body") && createBodyFilter()) {
            putFilter(params);
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
            String created = ApiJson.items(call("/filters/create", fp)).path(0).path("filter").asText("");
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
        // An internal API's list may leave out the text of each question: then the single-question endpoint has it.
        boolean needsBody = cached != null && cached.body().isBlank() && (site == null || site.isBlank()) && hasCapability("question");
        if (cached != null && !needsBody) {
            return cached;
        }
        Map<String, String> params = new LinkedHashMap<>();
        putFilter(params);
        JsonNode root;
        try {
            root = call("/questions/" + encodeId(id), params);
        } catch (SourceException e) {
            if (needsBody && e.kind() != SourceException.Kind.AUTH_FAILED && e.kind() != SourceException.Kind.QUOTA_EXHAUSTED) {
                return cached; // the text is a bonus here: keep what the list gave
            }
            throw e;
        }
        JsonNode items = ApiJson.items(root);
        boolean wholeAnswerIsTheQuestion = root.isObject() && !items.isArray() && ApiJson.id(root, "question_id", "questionId", "id") != null;
        JsonNode item = wholeAnswerIsTheQuestion ? root : items.isArray() && !items.isEmpty() ? items.get(0) : null;
        if (item == null) {
            if (needsBody) {
                return cached;
            }
            // The API answers 200 with no items for deleted or never-existing questions: not provably "deleted".
            throw new SourceException(SourceException.Kind.NOT_FOUND, "Question " + id + " is not returned by the API (deleted, never existed, or not visible)");
        }
        Question fetched = toQuestion(item);
        return needsBody && fetched.body().isBlank() ? cached : fetched;
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
        putFilter(params);
        putOrder(params, "creation");
        params.put(pm().size(), String.valueOf(pageSize()));
        params.put(pm().page(), String.valueOf(page));
        JsonNode root = call("/questions/" + encodeId(questionId) + "/answers", params);
        List<Answer> answers = new ArrayList<>();
        for (JsonNode a : ApiJson.items(root)) {
            answers.add(toAnswer(a, questionId));
        }
        return new Page<>(answers, ApiJson.hasMore(root, page, pageSize(), ApiJson.items(root).size()) ? "p:" + (page + 1) : null);
    }

    public record TagHit(String name, long count) {}

    /**
     * @param exists    a tag with this name was found on the site
     * @param exactName the spelling the site uses (may differ in case from what was typed)
     * @param count     number of questions with the tag, or -1 if the site did not say
     * @param similar   other tags whose names resemble the one asked for (when it was not found)
     * @param how       how it was found: "tag info", "tag list" or "question search"
     */
    public record TagCheck(boolean exists, String exactName, long count, List<TagHit> similar, String how) {}

    private final Map<String, String> exactTags = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> tagResolutionTried = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String squash(String tag) {
        return tag == null ? "" : tag.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    private static long countOf(JsonNode item) {
        for (String field : new String[] {"count", "questions_count", "question_count", "questionCount", "questionsCount", "postCount", "post_count", "total", "numQuestions", "uses", "usageCount", "numberOfQuestions"}) {
            if (item.path(field).canConvertToLong() && item.has(field)) {
                return item.get(field).asLong();
            }
        }
        return -1;
    }

    /** @param complete false when the site has more tags than were read (a limit, a failing page, or the site ignoring the page number) */
    public record TagListing(List<TagHit> tags, boolean complete) {}

    /** Receives each page of tags as it is read; return false to stop (for example when the person pressed Stop). */
    @FunctionalInterface
    public interface TagSink {
        boolean accept(List<TagHit> pageTags, int page);
    }

    /**
     * @param complete        the end of the site's tag list was reached
     * @param stopReason      in plain words, why reading ended
     * @param pagesRead       pages requested
     * @param pageSize        tags asked for per page
     * @param hasMoreUnreliable the site said "no more" on a full page but there were more (so its has_more flag cannot be trusted)
     */
    public record TagReadResult(boolean complete, String stopReason, int pagesRead, int pageSize, boolean hasMoreUnreliable) {}

    /**
     * Reads EVERY page of the site's tag list, with no fixed page limit. The end is decided from the pages themselves, not only from the
     * site's has_more flag: a full page is followed by one more request even if has_more says no; a page without anything new (the site ignoring
     * the page number) ends the reading with an explanation. Transient failures are retried; later failures keep what was read.
     *
     * @param maxPages     a safety limit (use Integer.MAX_VALUE for none)
     * @param budgetMillis a time limit (use Long.MAX_VALUE for none; a negative value reads nothing)
     */
    public TagReadResult readAllTags(TagSink sink, int maxPages, long budgetMillis) throws SourceException {
        long deadline = budgetMillis >= Long.MAX_VALUE / 2 ? Long.MAX_VALUE : System.currentTimeMillis() + budgetMillis;
        java.util.Set<String> seen = new java.util.HashSet<>();
        Map<String, String> params = new LinkedHashMap<>();
        boolean unreliable = false;
        boolean doubt = false; // a full page said "no more": the next page decides whether the site can be trusted
        int pages = 0;
        int asked = pageSize();
        for (int page = 1; page <= maxPages; page++) {
            if (System.currentTimeMillis() > deadline) {
                return new TagReadResult(false, "the time limit was reached", pages, asked, unreliable);
            }
            asked = pageSize(); // may have been corrected by the site's own page-size rule
            params.put(pm().page(), String.valueOf(page));
            params.put(pm().size(), String.valueOf(asked));
            JsonNode root = null;
            SourceException failure = null;
            for (int attempt = 1; attempt <= 3 && root == null; attempt++) { // a slow or busy page is tried again, then reported
                try {
                    root = call("/tags", params);
                } catch (SourceException e) {
                    failure = e;
                    boolean again = e.kind() == SourceException.Kind.TRANSIENT || e.kind() == SourceException.Kind.RATE_LIMITED;
                    if (!again) {
                        break;
                    }
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            pages = page;
            if (root == null) {
                if (page == 1 || (failure != null && (failure.kind() == SourceException.Kind.AUTH_FAILED))) {
                    throw failure;
                }
                String why = failure != null && failure.kind() == SourceException.Kind.QUOTA_EXHAUSTED ? "the request allowance for today ran out"
                        : "page " + page + " could not be read (" + (failure == null ? "unknown reason" : failure.getMessage()) + ")";
                return new TagReadResult(false, why, pages, asked, unreliable);
            }
            JsonNode items = ApiJson.items(root);
            List<TagHit> fresh = new ArrayList<>();
            int onPage = items.isArray() ? items.size() : 0;
            for (JsonNode t : items) {
                String name = t.path("name").asText(t.path("tag_name").asText(""));
                if (!name.isEmpty() && seen.add(name)) {
                    fresh.add(new TagHit(name, countOf(t)));
                }
            }
            if (onPage == 0) {
                return new TagReadResult(true, "the site has no more tags", pages, asked, unreliable);
            }
            if (fresh.isEmpty()) {
                return new TagReadResult(false, "the site sent the same tags again for page " + page + ": it seems to ignore the page number", pages, asked, unreliable);
            }
            if (doubt) {
                unreliable = true; // there WAS more after a "no more": the site's has_more flag cannot be trusted
                doubt = false;
            }
            if (!sink.accept(fresh, page)) {
                return new TagReadResult(false, "stopped", pages, asked, unreliable);
            }
            boolean more = ApiJson.hasMore(root, page, asked, onPage);
            if (!more) {
                if (onPage < asked) {
                    return new TagReadResult(true, "the site has no more tags", pages, asked, unreliable);
                }
                doubt = true; // a full page and has_more=false: ask for the next page to be sure
            }
        }
        return new TagReadResult(false, "the page limit was reached", pages, asked, unreliable);
    }

    /** EVERY tag the site lists, most used first (all pages are read). Convenience over {@link #readAllTags}. */
    public TagListing listAllTags(int maxPages, long budgetMillis) throws SourceException {
        List<TagHit> all = new ArrayList<>();
        TagReadResult r = readAllTags((tags, page) -> all.addAll(tags), maxPages, budgetMillis);
        all.sort(java.util.Comparator.comparingLong((TagHit h) -> -h.count()).thenComparing(TagHit::name));
        return new TagListing(all, r.complete());
    }

    /** Tags on the site whose names contain the text (the site's own spelling), best matches first. */
    public List<TagHit> findTags(String text, int limit) throws SourceException {
        String needle = squash(text);
        List<TagHit> hits = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>();
        if (!needle.isEmpty()) {
            params.put("inname", text.strip());
        }
        params.put(pm().size(), String.valueOf(pageSize()));
        for (int page = 1; page <= 5; page++) {
            params.put(pm().page(), String.valueOf(page));
            JsonNode root;
            try {
                root = call("/tags", params);
            } catch (SourceException e) {
                if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED || e.kind() == SourceException.Kind.RATE_LIMITED
                        || e.kind() == SourceException.Kind.TRANSIENT) {
                    throw e;
                }
                if (params.remove("inname") != null) { // the site does not know that parameter: list tags and filter here
                    page--;
                    continue;
                }
                if (hits.isEmpty()) {
                    throw e; // a real problem (address, https, key ...) must be shown, not disguised as "no tags"
                }
                return hits;
            }
            for (JsonNode t : ApiJson.items(root)) {
                String name = t.path("name").asText(t.path("tag_name").asText(""));
                if (!name.isEmpty() && squash(name).contains(needle) && hits.stream().noneMatch(h -> h.name().equals(name))) {
                    hits.add(new TagHit(name, countOf(t)));
                }
            }
            if (!ApiJson.hasMore(root, page, pageSize(), ApiJson.items(root).size()) || hits.size() >= limit * 3) {
                break;
            }
        }
        hits.sort(java.util.Comparator.comparing((TagHit h) -> !squash(h.name()).equals(needle)).thenComparing(h -> -h.count()).thenComparing(TagHit::name));
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    /** The number of questions in an /info answer (any of the usual field names, in the wrapper's first item or at the top), or -1. */
    private static long totalQuestionsOf(JsonNode info) {
        JsonNode first = ApiJson.items(info).path(0);
        for (JsonNode holder : new JsonNode[] {first, info}) {
            for (String f : new String[] {"total_questions", "totalQuestions", "questionCount", "questionsCount", "numberOfQuestions", "questions"}) {
                if (holder.path(f).isNumber()) {
                    return holder.get(f).asLong();
                }
            }
        }
        return -1;
    }

    /** How many questions the whole site holds, or -1 if it does not say. */
    public long siteQuestionCount() throws SourceException {
        try {
            if (hasCapability("info")) {
                long n = totalQuestionsOf(call("/info", new LinkedHashMap<>()));
                if (n >= 0) {
                    return n;
                }
            }
            Map<String, String> p = new LinkedHashMap<>();
            p.put(pm().size(), String.valueOf(pageSize()));
            p.put("filter", "total");
            JsonNode root = call("/questions", p);
            return root.has("total") ? root.get("total").asLong(-1) : -1;
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED) {
                throw e;
            }
            return -1;
        }
    }

    /**
     * Does this tag exist on the site, and how does the site spell it? Tries the tag's own info (as typed and in other casings),
     * then the site's tag list, and finally a real question search by that tag, which is what a collection does.
     */
    public TagCheck checkTag(String typed) throws SourceException {
        String plain = typed == null ? "" : typed.strip();
        String normalized = TagFilter.normalize(plain);
        java.util.LinkedHashSet<String> variants = new java.util.LinkedHashSet<>(List.of(normalized, plain, plain.toUpperCase(java.util.Locale.ROOT),
                plain.replaceAll("\\s+", "-"), plain.replaceAll("\\s+", "-").toUpperCase(java.util.Locale.ROOT)));
        variants.removeIf(String::isBlank);
        if (hasCapability("tagInfo")) {
            for (String v : variants) {
                try {
                    JsonNode items = ApiJson.items(call("/tags/" + enc(v) + "/info", new LinkedHashMap<>()));
                    if (items.isArray() && !items.isEmpty()) {
                        String exact = items.get(0).path("name").asText(v);
                        return new TagCheck(true, exact.isBlank() ? v : exact, countOf(items.get(0)), List.of(), "tag info");
                    }
                } catch (SourceException e) {
                    if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED || e.kind() == SourceException.Kind.TRANSIENT) {
                        throw e;
                    }
                    // this variant is not known (or the endpoint objects to it): try the next way
                }
            }
        }
        List<TagHit> similar = List.of();
        try {
            similar = findTags(plain, 8);
            for (TagHit h : similar) {
                if (squash(h.name()).equals(squash(plain))) {
                    return new TagCheck(true, h.name(), h.count(), similar, "tag list");
                }
            }
        } catch (SourceException e) {
            if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED) {
                throw e;
            }
        }
        for (String v : variants) { // the same search a collection performs
            Map<String, String> params = new LinkedHashMap<>();
            params.put(pm().tag() == null ? "tagged" : pm().tag(), v);
            params.put(pm().size(), String.valueOf(pageSize()));
            try {
                JsonNode root = call("/questions", params);
                JsonNode items = ApiJson.items(root);
                if (items.isArray() && !items.isEmpty()) {
                    String exact = v;
                    for (JsonNode q : items) {
                        for (JsonNode t : q.path("tags")) {
                            if (squash(t.asText()).equals(squash(v))) {
                                exact = t.asText();
                            }
                        }
                    }
                    long total = root.path("total").canConvertToLong() && root.has("total") ? root.get("total").asLong() : -1;
                    return new TagCheck(true, exact, total, similar, "question search");
                }
            } catch (SourceException e) {
                if (e.kind() == SourceException.Kind.AUTH_FAILED || e.kind() == SourceException.Kind.QUOTA_EXHAUSTED || e.kind() == SourceException.Kind.TRANSIENT) {
                    throw e;
                }
            }
        }
        return new TagCheck(false, null, -1, similar, null);
    }

    /** The site's own spelling of a tag, or null if it cannot be found. */
    private String findExactTagName(String tag) throws SourceException {
        TagCheck c = checkTag(tag);
        return c.exists() ? c.exactName() : null;
    }

    /** Number of questions carrying the tag on the whole site (documented /tags/{tag}/info), or -1 when the tag does not exist. */
    public long tagCount(String tag) throws SourceException {
        JsonNode root = call("/tags/" + enc(TagFilter.normalize(tag)) + "/info", new LinkedHashMap<>());
        JsonNode items = ApiJson.items(root);
        return items.isArray() && !items.isEmpty() ? items.get(0).path("count").asLong(0) : -1;
    }

    @Override
    public String ping() throws SourceException {
        String where = site == null || site.isBlank() ? "the internal API" : "site '" + site + "'";
        JsonNode root;
        String size;
        if (hasCapability("info")) {
            root = call("/info", new LinkedHashMap<>());
            long reported = totalQuestionsOf(root);
            size = reported >= 0 ? String.valueOf(reported) : "?";
        } else {
            Map<String, String> probe = new LinkedHashMap<>();
            probe.put(pm().size(), String.valueOf(pageSize()));
            probe.put("filter", "total");
            root = call("/questions", probe);
            size = root.has("total") ? root.get("total").asText() : "?";
        }
        boolean keyed = settings.apiKeyEnv() != null && env.apply(settings.apiKeyEnv()) != null;
        boolean publicSite = site != null && !site.isBlank();
        String keyNote = keyed ? (publicSite ? " (application key in use)" : " (access key in use)")
                : (publicSite ? " (anonymous: small daily quota)" : " (no access key set)");
        String moved = redirectedFrom == null ? "" : "; the site redirects " + ResponseInspector.withoutQuery(redirectedFrom) + " to " + apiBase + ", which is used directly from now on";
        return (publicSite ? "Stack Exchange API reachable" + moved + "; " : "API reachable" + moved + "; ") + where + " has " + size + " questions; request quota remaining: "
                + (quotaRemaining.get() < 0 ? "unknown" : quotaRemaining.get()) + keyNote;
    }

    // ================================================================== HTTP + error handling

    private JsonNode call(String path, Map<String, String> params) throws SourceException {
        try {
            return callOnce(path, params);
        } catch (SourceException e) {
            // "Invalid page size, use 15, 30, 50 or 100": adopt the largest allowed size and try once more.
            List<Integer> allowed = e.kind() == SourceException.Kind.FATAL ? ResponseInspector.allowedPageSizes(e.getMessage()) : List.of();
            String asked = params.get(pm().size());
            if (!allowed.isEmpty() && asked != null) {
                int better = allowed.stream().filter(x -> x <= 100).max(Integer::compare).orElse(allowed.get(0));
                if (!String.valueOf(better).equals(asked)) {
                    pageSize = better;
                    pageSizeFromDocs = true;
                    params.put(pm().size(), String.valueOf(better));
                    return callOnce(path, params);
                }
            }
            throw e;
        }
    }

    private JsonNode callOnce(String path, Map<String, String> params) throws SourceException {
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
        learnBase(path, response);
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
            message = ResponseInspector.errorSummary(response.body());
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
        // A refusal with a readable reason (for example validation errors) is shown as it is.
        if (!message.isBlank() && response.status() >= 400 && response.status() < 500 && response.status() != 404 && response.status() != 429) {
            return new SourceException(SourceException.Kind.FATAL, "The API rejected the request (HTTP " + response.status() + "): " + message);
        }
        // No JSON error body: fall back to generic HTTP status mapping (never includes the query string).
        return client.mapStatus(response.status(), response.retryAfter(), java.net.URI.create(ResponseInspector.withoutQuery(url)));
    }

    /** If the API address redirected to another place (a gateway path, https, another host), use that place directly from now on. */
    private void learnBase(String path, HttpSourceClient.Response r) {
        if (r.redirects().isEmpty() || r.finalUrl() == null || r.status() < 200 || r.status() >= 300) {
            return;
        }
        try {
            java.net.URI fin = java.net.URI.create(r.finalUrl());
            String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            String finPath = fin.getRawPath();
            if (finPath != null && finPath.endsWith(pathOnly)) {
                String newBase = (fin.getScheme() + "://" + fin.getRawAuthority() + finPath.substring(0, finPath.length() - pathOnly.length())).replaceAll("/+$", "");
                if (!newBase.equals(apiBase)) {
                    redirectedFrom = apiBase;
                    apiBase = newBase;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // keep using the configured address
        }
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
        if (Redirects.isRedirect(response.status()) && response.redirectStop() != null) {
            boolean login = response.location() != null && java.util.regex.Pattern.compile("(?i)login|signin|sign-in|sso|saml|oauth|authorize|adfs|okta").matcher(response.location()).find();
            throw new SourceException(login ? SourceException.Kind.AUTH_FAILED : SourceException.Kind.FATAL,
                    "The site redirected the request (HTTP " + response.status() + ") to " + ResponseInspector.withoutQuery(String.valueOf(response.location())) + " but it was not followed: " + response.redirectStop() + ". "
                            + (login ? "The redirect leads to a sign-in page, so the API wants credentials that were not accepted. "
                                    + SignInSystem.identify(response.location()).map(x -> x.sentence() + " ").orElse("") : "")
                            + (response.redirects().isEmpty() ? "" : "Followed before that: " + String.join("; ", response.redirects()) + ". ")
                            + "(On the Connect screen, 'See what the API really answers' shows the whole chain.)");
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
        String via = response.redirects().isEmpty() ? "" : " (after redirects: " + String.join(", ", response.redirects()) + ")";
        if (inspection.kind().equals("HTML_LOGIN")) {
            via += SignInSystem.identify(response.finalUrl(), response.location()).map(x -> " " + x.sentence()).orElse("");
        }
        throw new SourceException(kind, "The API did not answer with JSON. " + inspection.summary() + via + said + hint
                + " (On the Connect screen, 'See what the API really answers' shows the full answer.)");
    }

    // ================================================================== mapping

    private Question toQuestion(JsonNode n) {
        String id = ApiJson.id(n, "question_id", "questionId", "id", "postId", "post_id");
        List<String> tags = ApiJson.tags(n);
        String link = ApiJson.text(n, "link", "url", "webUrl", "web_url", "permalink", "htmlUrl");
        String bodyHtml = ApiJson.text(n, "body", "bodyHtml", "body_html", "text", "content");
        String bodyMarkdown = ApiJson.text(n, "bodyMarkdown", "body_markdown", "markdown");
        boolean markdown = bodyHtml == null && bodyMarkdown != null;
        Integer score = ApiJson.integer(n, "score", "votes", "voteCount", "vote_count");
        return new Question(id, ApiJson.text(n, "title", "name", "subject") == null ? "" : ApiJson.text(n, "title", "name", "subject"),
                markdown ? bodyMarkdown : bodyHtml == null ? "" : bodyHtml, markdown ? BodyFormat.MARKDOWN : BodyFormat.HTML,
                link != null ? link : id == null ? null : "https://" + siteHost + "/questions/" + id,
                tags, ApiJson.owner(n), score, ApiJson.instant(n, "creation_date", "creationDate", "createdAt", "created_at", "created", "createdDate", "dateCreated"),
                ApiJson.instant(n, "last_activity_date", "lastActivityDate", "last_activity", "lastActivity", "lastEditDate", "last_edit_date", "modifiedAt", "updatedAt", "updated_at", "lastModified"),
                List.<Comment>of(), List.of());
    }

    private Answer toAnswer(JsonNode n, String questionId) {
        String id = ApiJson.id(n, "answer_id", "answerId", "id", "postId", "post_id");
        String link = ApiJson.text(n, "link", "url", "webUrl", "web_url", "permalink");
        String bodyHtml = ApiJson.text(n, "body", "bodyHtml", "body_html", "text", "content");
        String bodyMarkdown = ApiJson.text(n, "bodyMarkdown", "body_markdown", "markdown");
        boolean markdown = bodyHtml == null && bodyMarkdown != null;
        String created = ApiJson.instant(n, "creation_date", "creationDate", "createdAt", "created_at", "created", "createdDate");
        String modified = ApiJson.instant(n, "last_activity_date", "lastActivityDate", "last_activity", "lastEditDate", "last_edit_date", "modifiedAt", "updatedAt", "updated_at");
        return new Answer(id, markdown ? bodyMarkdown : bodyHtml == null ? "" : bodyHtml, markdown ? BodyFormat.MARKDOWN : BodyFormat.HTML,
                link != null ? link : "https://" + siteHost + "/questions/" + questionId + "#" + id,
                ApiJson.bool(n, "is_accepted", "isAccepted", "accepted", "isAcceptedAnswer", "acceptedAnswer"),
                ApiJson.integer(n, "score", "votes", "voteCount", "vote_count"), ApiJson.owner(n), created, modified, List.of());
    }

    private static String author(JsonNode n) {
        return ApiJson.owner(n);
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
