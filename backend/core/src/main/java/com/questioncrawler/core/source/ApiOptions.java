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
 * @param redirects       how redirects are handled: "all" (default), "same-host" or "off"
 * @param swaggerFile     a saved copy of the Swagger JSON on this computer (optional; for documentation that needs a sign-in)
 */
public record ApiOptions(String keyName, String keyIn, String accessTokenEnv, String bodyFilter, boolean collectComments, String swaggerUrl, String swaggerFile, String redirects) {

    public static final ApiOptions DEFAULT = new ApiOptions("key", "query", null, "withbody", false, null, null, "all");

    public ApiOptions {
        keyName = keyName == null || keyName.isBlank() ? "key" : keyName.strip();
        keyIn = "header".equalsIgnoreCase(keyIn) ? "header" : "query";
        bodyFilter = bodyFilter == null || bodyFilter.isBlank() ? "withbody" : bodyFilter.strip();
        accessTokenEnv = accessTokenEnv == null || accessTokenEnv.isBlank() ? null : accessTokenEnv.strip();
        swaggerUrl = swaggerUrl == null || swaggerUrl.isBlank() ? null : swaggerUrl.strip();
        swaggerFile = swaggerFile == null || swaggerFile.isBlank() ? null : swaggerFile.strip();
        redirects = Redirects.Mode.parse(redirects).text();
    }

    public ApiOptions(String keyName, String keyIn, String accessTokenEnv, String bodyFilter, boolean collectComments, String swaggerUrl, String swaggerFile) {
        this(keyName, keyIn, accessTokenEnv, bodyFilter, collectComments, swaggerUrl, swaggerFile, "all");
    }

    public Redirects.Mode redirectMode() {
        return Redirects.Mode.parse(redirects);
    }

    public ApiOptions(String keyName, String keyIn, String accessTokenEnv, String bodyFilter, boolean collectComments, String swaggerUrl) {
        this(keyName, keyIn, accessTokenEnv, bodyFilter, collectComments, swaggerUrl, null);
    }

    /** Is there any documentation to read (an address or a saved file)? */
    public boolean hasDocs() {
        return swaggerUrl != null || swaggerFile != null;
    }
}
