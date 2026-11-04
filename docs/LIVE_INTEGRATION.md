# Connecting the real internal site

`LiveSourceAdapter` is intentionally a stub: **no endpoint, authentication flow or HTML selector has been invented.**

## Already implemented (transport)

`HttpSourceClient`: https-only (plain http needs `source.allowInsecureHttp=true`, local testing), host and path-prefix
allow-lists, no redirects, timeouts, bounded response size, token from an environment variable (header name and scheme
configurable), organization CA certificate, and mapping of HTTP statuses to `SourceException.Kind`
(401 -> AUTH_FAILED, 403 -> FORBIDDEN, 404 -> NOT_FOUND, 410 -> GONE, 429/503 -> RATE_LIMITED with `Retry-After`
seconds or HTTP-date, 5xx/IO -> TRANSIENT). Retry with exponential backoff lives in `Retrier`, used by the sync engine.
All of this is unit-tested against a local HTTP server.

## What I need to finish it

1. **Which access route is approved?** (a) an official API, (b) an approved export, (c) HTML collection if permitted.
2. **API documentation or sanitized sample responses** for: listing questions by tag (pagination style, modified-since filter?),
   one question, answers of a question (pagination, accepted flag), comments, deleted/forbidden behaviour, rate limits.
3. **Authentication method** (token header? approved SSO service account?) and the variable name to use.
4. **Tag semantics** (case, synonyms, hierarchies) and canonical URL patterns for questions and answers.
5. A **CA certificate** (PEM) if the site uses an internal CA.

## Where the code goes

Implement `listQuestions`, `fetchQuestion`, `listAnswers` in `LiveSourceAdapter` (return raw bodies with `bodyFormat` HTML or
MARKDOWN; the normalizer does the rest), set `supportsModifiedSince()` honestly, and map explicit "deleted" / "no permission"
responses to `GONE` / `FORBIDDEN` so reconciliation can set precise statuses. Add tests using the sanitized samples (a recorded
response served by a local `HttpServer`, as in `HttpSourceClientTest`). Do not add selectors or endpoints without a sample to check against.
