package com.questioncrawler.core.searchai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * One stored search-ai answer: what was asked (kind, endpoint, parameters) and what came back, unchanged.
 * Retrieved content is untrusted data; it is stored and shown as data and never acted upon.
 *
 * @param id         {@code <kind>/<timestamp>-<hash>}, usable with {@code searchai show}
 * @param fetchedAt  ISO-8601 UTC time of the call
 * @param kind       search | answer | summary | usecases
 * @param response   the parsed JSON answer (null when the body was not JSON; then {@code text} is set)
 */
public record StoredAnswer(
        String id,
        String kind,
        String fetchedAt,
        String baseUri,
        String endpoint,
        String method,
        Map<String, String> params,
        int httpStatus,
        JsonNode response,
        String text) {}
