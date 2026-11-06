package com.questioncrawler.core.source;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;

/**
 * Placeholder for the real internal site. Transport, host allow-listing, TLS, authentication and
 * error mapping are implemented in {@link HttpSourceClient} and are real. What is NOT implemented
 * is the site-specific part: endpoint paths, JSON field names or HTML selectors. Those are unknown
 * and are deliberately not guessed.
 *
 * <p>To finish the integration, see docs/LIVE_INTEGRATION.md: implement listQuestions, fetchQuestion
 * and listAnswers here using verified documentation or sanitized sample responses.
 *
 * <p>Note: Confluence is {@code source.type=confluence} ({@link ConfluenceAdapter}); Jive is
 * {@code source.type=jive} ({@link JiveAdapter}). SharePoint will be a separate source type later —
 * do not overload this stub for them.
 */
public final class LiveSourceAdapter implements SourceAdapter {

    private static final String MISSING =
            "Live source integration is not implemented: the internal site's API endpoints/response format are unknown. "
                    + "Provide API documentation or sanitized sample responses, then implement LiveSourceAdapter "
                    + "(see docs/LIVE_INTEGRATION.md). Use source.type=fixture for the offline demo.";

    private final SourceSettings settings;
    private final HttpSourceClient client;

    public LiveSourceAdapter(SourceSettings settings, HttpSourceClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public String systemId() {
        return settings.systemId();
    }

    @Override
    public boolean supportsModifiedSince() {
        return false;
    }

    @Override
    public Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException {
        throw new SourceException(SourceException.Kind.NOT_CONFIGURED, MISSING);
    }

    @Override
    public Question fetchQuestion(String id) throws SourceException {
        throw new SourceException(SourceException.Kind.NOT_CONFIGURED, MISSING);
    }

    @Override
    public Page<Answer> listAnswers(String questionId, String cursor) throws SourceException {
        throw new SourceException(SourceException.Kind.NOT_CONFIGURED, MISSING);
    }

    /** Reachability and TLS/auth check against the configured base URL only. */
    @Override
    public String ping() throws SourceException {
        HttpSourceClient.Response response = client.get(settings.baseUrl());
        return "HTTP " + response.status() + " from " + settings.baseUrl();
    }
}
