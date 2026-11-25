package com.questioncrawler.core.searchai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * One answer from the search-ai API.
 *
 * @param endpoint   path below the base, e.g. {@code /search}
 * @param method     GET or POST
 * @param params     query-string parameters that were sent (no credentials are ever part of a request)
 * @param httpStatus HTTP status of the answer
 * @param json       the parsed body, or null when the body was not JSON
 * @param text       the raw body when it was not JSON, otherwise null
 */
public record SearchAiResponse(String endpoint, String method, Map<String, String> params, int httpStatus, JsonNode json, String text) {}
