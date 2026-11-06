package com.questioncrawler.core.api;

import com.questioncrawler.core.source.ApiOptions;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.SourceException;
import com.questioncrawler.core.source.SourceSettings;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads the API documentation however it can be had, in this order: a saved file (no network, no sign-in), otherwise the
 * address, sending the configured Bearer token (if any) and, when the first attempt is refused, retrying with the API access key.
 *
 * <p>An interactive single sign-on (a browser redirect with company login or MFA) is deliberately not attempted. When the
 * documentation needs one, the error says what to do instead.
 */
public final class ApiDocsLoader {

    private ApiDocsLoader() {}

    public static SwaggerDiscovery.Discovery load(SourceSettings settings, Function<String, String> env) throws SourceException {
        ApiOptions api = settings.api();
        if (api.swaggerFile() != null) {
            Path file = Path.of(api.swaggerFile());
            try {
                return SwaggerDiscovery.fromJson(Files.readString(file, StandardCharsets.UTF_8), "file " + file.getFileName());
            } catch (IOException e) {
                throw new SourceException(SourceException.Kind.FATAL, "The documentation file could not be read (" + file + "): " + e.getMessage());
            }
        }
        if (api.swaggerUrl() == null) {
            throw new SourceException(SourceException.Kind.FATAL, "No API documentation address or file was given.");
        }
        HttpSourceClient client = new HttpSourceClient(settings, env);
        String key = settings.apiKeyEnv() == null || settings.apiKeyEnv().isBlank() ? null : env.apply(settings.apiKeyEnv());
        SwaggerDiscovery.Fetcher fetcher = url -> {
            try {
                return client.get(url).body();
            } catch (SourceException first) {
                if ((first.kind() == SourceException.Kind.AUTH_FAILED || first.kind() == SourceException.Kind.FORBIDDEN) && key != null && !key.isBlank()) {
                    try { // some sites accept the API access key on the documentation too
                        return "header".equals(api.keyIn())
                                ? client.exchange(url, Map.of(api.keyName(), key)).body()
                                : client.get(url + (url.contains("?") ? "&" : "?") + URLEncoder.encode(api.keyName(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(key, StandardCharsets.UTF_8)).body();
                    } catch (SourceException second) {
                        throw signInHelp(first);
                    }
                }
                if (first.kind() == SourceException.Kind.AUTH_FAILED || first.kind() == SourceException.Kind.FORBIDDEN) {
                    throw signInHelp(first);
                }
                throw first;
            }
        };
        return new SwaggerDiscovery(fetcher).discover(api.swaggerUrl());
    }

    /** What to do when the documentation itself needs a sign-in. */
    public static SourceException signInHelp(SourceException cause) {
        return new SourceException(SourceException.Kind.AUTH_FAILED, "The API documentation needs a sign-in (" + cause.getMessage() + "). "
                + "QuestionCrawler cannot do an interactive single sign-on (SSO) login. Do one of these: "
                + "(1) open the documentation's JSON address in your browser, where you are signed in, save it as swagger.json and upload that file here; "
                + "(2) ask the site owners for a token (or service account) and set it under 'Extra sign-in'; "
                + "(3) ask the owners to allow the API access key on the documentation.");
    }
}
