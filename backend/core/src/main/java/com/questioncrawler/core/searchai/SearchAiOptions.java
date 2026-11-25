package com.questioncrawler.core.searchai;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Settings for the search-ai REST API ({@code <baseUri>/search}, {@code /genairesponse}, ...). Read from the same
 * properties file as everything else, keys {@code searchai.*}. Nothing secret is stored: authentication is the
 * caller's Kerberos ticket (run {@code kinit} first on Linux/macOS); the cookie jar only holds the session cookie.
 *
 * @param baseUri        API base, no trailing slash, e.g. {@code https://search-ai.webfarm.xx.com/searchapi}
 * @param cookieJar      curl cookie jar (session cookie seeded by /whoami); change the file name here, not in code
 * @param curlCommand    curl executable (a path or a name on PATH)
 * @param timeoutSeconds whole-request timeout handed to curl ({@code --max-time})
 * @param defaultUseCase use_case sent when a call gives none (empty = none)
 * @param defaultRows    rows sent for /search when a call gives none
 * @param storeDir       where fetched answers are stored (under the storage root)
 */
public record SearchAiOptions(
        String baseUri,
        Path cookieJar,
        String curlCommand,
        int timeoutSeconds,
        String defaultUseCase,
        int defaultRows,
        Path storeDir) {

    public static final String DEFAULT_BASE_URI = "https://search-ai.webfarm.xx.com/searchapi";

    public SearchAiOptions {
        if (baseUri == null || baseUri.isBlank()) {
            throw new IllegalArgumentException("searchai.baseUri is empty. Set it to the search-ai API base, for example " + DEFAULT_BASE_URI);
        }
        baseUri = baseUri.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(baseUri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("searchai.baseUri is not a valid address: " + baseUri);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") || uri.getHost() == null) {
            // Kerberos negotiate and the session cookie must never travel in clear text.
            throw new IllegalArgumentException("searchai.baseUri must be an https:// address with a host: " + baseUri);
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("searchai.timeoutSeconds must be at least 1");
        }
        if (defaultRows < 1) {
            throw new IllegalArgumentException("searchai.rows must be at least 1");
        }
        curlCommand = curlCommand == null || curlCommand.isBlank() ? "curl" : curlCommand.strip();
        defaultUseCase = defaultUseCase == null ? "" : defaultUseCase.strip();
    }

    /** Reads {@code searchai.*} from the properties file (may be null) and places the store under the storage root. */
    public static SearchAiOptions load(Path configFile, Path storageRoot) {
        Properties p = new Properties();
        if (configFile != null && Files.exists(configFile)) {
            try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read configuration " + configFile + ": " + e.getMessage(), e);
            }
        }
        Path base = configFile == null ? Path.of("").toAbsolutePath() : configFile.toAbsolutePath().getParent();
        return new SearchAiOptions(
                p.getProperty("searchai.baseUri", DEFAULT_BASE_URI),
                resolve(base, p.getProperty("searchai.cookieJar", "~/.cache/questioncrawler/search-cookies.jar")),
                p.getProperty("searchai.curl", "curl"),
                intValue(p, "searchai.timeoutSeconds", 60),
                p.getProperty("searchai.useCase", ""),
                intValue(p, "searchai.rows", 10),
                storageRoot.toAbsolutePath().normalize().resolve("searchai"));
    }

    private static int intValue(Properties p, String key, int fallback) {
        String v = p.getProperty(key, "").strip();
        if (v.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number: " + v);
        }
    }

    private static Path resolve(Path base, String value) {
        String v = value.strip();
        Path p = Path.of(v.startsWith("~") ? System.getProperty("user.home") + v.substring(1) : v);
        return p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
    }
}
