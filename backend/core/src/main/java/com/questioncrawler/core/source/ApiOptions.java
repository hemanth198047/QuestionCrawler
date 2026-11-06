package com.questioncrawler.core.source;

/**
 * How an internal "Stack Exchange API 2.3 style" REST API wants to be called. The defaults match the documented behaviour
 * (an access key, response filters, epoch dates); {@code swaggerUrl} lets the application learn the rest from the API's own
 * Swagger documentation.
 *
 * @param keyName         name of the API access key parameter or header (default "key")
 * @param keyIn           "query" (default) or "header"
 * @param accessTokenEnv  optional environment variable holding an OAuth access token (sent as access_token)
 * @param bodyFilter      response filter that includes question and answer text (default "withbody")
 * @param collectComments also collect comments on questions and answers
 * @param swaggerUrl      address of the Swagger documentation page (optional)
 */
public record ApiOptions(String keyName, String keyIn, String accessTokenEnv, String bodyFilter, boolean collectComments, String swaggerUrl) {

    public static final ApiOptions DEFAULT = new ApiOptions("key", "query", null, "withbody", false, null);

    public ApiOptions {
        keyName = keyName == null || keyName.isBlank() ? "key" : keyName.strip();
        keyIn = "header".equalsIgnoreCase(keyIn) ? "header" : "query";
        bodyFilter = bodyFilter == null || bodyFilter.isBlank() ? "withbody" : bodyFilter.strip();
        accessTokenEnv = accessTokenEnv == null || accessTokenEnv.isBlank() ? null : accessTokenEnv.strip();
        swaggerUrl = swaggerUrl == null || swaggerUrl.isBlank() ? null : swaggerUrl.strip();
    }
}
