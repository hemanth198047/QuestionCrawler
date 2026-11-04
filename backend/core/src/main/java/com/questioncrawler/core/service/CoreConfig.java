package com.questioncrawler.core.service;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.source.FixtureSourceAdapter;
import com.questioncrawler.core.source.HttpSourceClient;
import com.questioncrawler.core.source.LiveSourceAdapter;
import com.questioncrawler.core.source.SourceAdapter;
import com.questioncrawler.core.source.SourceSettings;
import com.questioncrawler.core.source.TagMode;
import com.questioncrawler.core.sync.SyncMode;
import com.questioncrawler.core.sync.SyncOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Typed view of the .properties configuration. No secrets live here: only the NAME of the token variable. */
public record CoreConfig(
        Path configFile,
        Path storageRoot,
        Path taxonomyFile,
        String sourceType,
        SourceSettings source,
        List<Path> fixtureDirs,
        int fixturePageSize,
        List<String> tags,
        TagMode tagMode,
        boolean partialTags,
        Duration overlap,
        int concurrency,
        int maxAttempts,
        int retentionDays,
        boolean schedulerEnabled,
        Duration schedulerInterval,
        int staleAfterHours) {

    public static Path defaultStorageRoot() {
        return Path.of(System.getProperty("user.home"), ".questioncrawler", "data");
    }

    /** Resolution order: explicit path, QUESTIONCRAWLER_CONFIG, ./questioncrawler.properties, ~/.questioncrawler/config.properties. */
    public static Path locate(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String env = System.getenv("QUESTIONCRAWLER_CONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path local = Path.of("questioncrawler.properties");
        if (Files.exists(local)) {
            return local;
        }
        Path home = Path.of(System.getProperty("user.home"), ".questioncrawler", "config.properties");
        return Files.exists(home) ? home : null;
    }

    /** Loads the file (may be null for pure defaults); non-null overrides win over file values. */
    public static CoreConfig load(Path file, Map<String, String> overrides) {
        Properties p = new Properties();
        if (file != null) {
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("Configuration file not found: " + file + ". Run 'init' to create one.");
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read configuration " + file + ": " + e.getMessage(), e);
            }
        }
        if (overrides != null) {
            overrides.forEach(p::setProperty);
        }
        Path base = file == null ? Path.of("").toAbsolutePath() : file.toAbsolutePath().getParent();

        String root = p.getProperty("storage.root", "").strip();
        Path storage = root.isEmpty() ? defaultStorageRoot() : resolve(base, root);
        String taxonomy = p.getProperty("taxonomy.file", "").strip();
        Path taxonomyFile = taxonomy.isEmpty() ? null : resolve(base, taxonomy);
        String type = p.getProperty("source.type", "fixture").strip().toLowerCase();

        String ca = p.getProperty("source.caCertFile", "").strip();
        SourceSettings settings = new SourceSettings(
                p.getProperty("source.systemId", "internal-qa").strip(),
                p.getProperty("source.baseUrl", "").strip(),
                csv(p.getProperty("source.allowedHosts", "")),
                csv(p.getProperty("source.allowedPathPrefixes", "/")),
                p.getProperty("source.auth.method", "none").strip(),
                p.getProperty("source.auth.tokenEnv", "").strip(),
                p.getProperty("source.auth.headerName", "Authorization").strip(),
                p.getProperty("source.auth.scheme", "Bearer").strip(),
                ca.isEmpty() ? null : resolve(base, ca),
                Duration.ofSeconds(intValue(p, "source.timeoutSeconds", 30)),
                Boolean.parseBoolean(p.getProperty("source.allowInsecureHttp", "false").strip()));

        List<Path> fixtureDirs = csv(p.getProperty("source.fixture.dirs", "")).stream().map(s -> resolve(base, s)).toList();
        return new CoreConfig(file, storage, taxonomyFile, type, settings, fixtureDirs,
                intValue(p, "source.fixture.pageSize", 2),
                csv(p.getProperty("sync.tags", "")),
                TagMode.valueOf(p.getProperty("sync.tagMode", "ANY").strip().toUpperCase()),
                Boolean.parseBoolean(p.getProperty("sync.partialTags", "false").strip()),
                Duration.ofMinutes(intValue(p, "sync.overlapMinutes", 60)),
                intValue(p, "sync.concurrency", 4),
                intValue(p, "sync.maxAttempts", 5),
                intValue(p, "sync.retentionDays", 30),
                Boolean.parseBoolean(p.getProperty("sync.scheduler.enabled", "false").strip()),
                Duration.ofMinutes(intValue(p, "sync.scheduler.intervalMinutes", 360)),
                intValue(p, "status.staleAfterHours", 168));
    }

    public static CoreConfig forStorage(Path storageRoot) {
        return load(null, Map.of("storage.root", storageRoot.toString()));
    }

    // ------------------------------------------------------------------ behaviour

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (!sourceType.equals("fixture") && !sourceType.equals("live")) {
            problems.add("source.type must be 'fixture' or 'live' (was '" + sourceType + "')");
        }
        if (tags.isEmpty()) {
            problems.add("sync.tags is empty: at least one target tag is required to run 'sync'");
        }
        if (sourceType.equals("live")) {
            if (source.baseUrl().isBlank()) {
                problems.add("source.baseUrl is required for source.type=live");
            } else {
                try {
                    java.net.URI uri = java.net.URI.create(source.baseUrl());
                    if (uri.getHost() == null) {
                        problems.add("source.baseUrl has no host");
                    } else if (source.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(uri.getHost()))) {
                        problems.add("source.allowedHosts must include the base URL host '" + uri.getHost() + "'");
                    }
                    if (!"https".equalsIgnoreCase(uri.getScheme()) && !source.allowInsecureHttp()) {
                        problems.add("source.baseUrl must use https (source.allowInsecureHttp is for local testing only)");
                    }
                } catch (IllegalArgumentException e) {
                    problems.add("source.baseUrl is not a valid URL");
                }
            }
            if (source.authMethod().equalsIgnoreCase("token") && source.tokenEnv().isBlank()) {
                problems.add("source.auth.tokenEnv must name the environment variable that holds the token");
            }
            if (source.caCertFile() != null && !Files.isReadable(source.caCertFile())) {
                problems.add("source.caCertFile is not readable: " + source.caCertFile());
            }
        }
        if (taxonomyFile != null && !Files.isReadable(taxonomyFile)) {
            problems.add("taxonomy.file is not readable: " + taxonomyFile);
        }
        if (concurrency < 1 || concurrency > 16) {
            problems.add("sync.concurrency must be between 1 and 16");
        }
        return problems;
    }

    public Taxonomy loadTaxonomy() {
        return taxonomyFile != null && Files.isReadable(taxonomyFile) ? Taxonomy.load(taxonomyFile) : Taxonomy.loadDefault();
    }

    public SourceAdapter createAdapter() {
        if (sourceType.equals("live")) {
            return new LiveSourceAdapter(source, new HttpSourceClient(source));
        }
        if (fixtureDirs.isEmpty()) {
            return FixtureSourceAdapter.fromClasspath(fixturePageSize, fixturePageSize, "fixtures/demo-site");
        }
        return FixtureSourceAdapter.fromDirectories(fixturePageSize, fixturePageSize, fixtureDirs.toArray(Path[]::new));
    }

    public SyncOptions syncOptions(List<String> tagOverride, TagMode modeOverride, Boolean partialOverride, SyncMode mode) {
        return new SyncOptions(tagOverride == null || tagOverride.isEmpty() ? tags : tagOverride,
                modeOverride == null ? tagMode : modeOverride,
                partialOverride == null ? partialTags : partialOverride,
                mode, overlap, concurrency, retentionDays);
    }

    /** Writes the commented configuration template with the given storage root. */
    public static String template(Path storageRoot) {
        try (InputStream in = CoreConfig.class.getClassLoader().getResourceAsStream("defaults/questioncrawler.properties")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.replace("@STORAGE_ROOT@", storageRoot.toString().replace('\\', '/'));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Path resolve(Path base, String value) {
        Path p = Path.of(value.startsWith("~") ? System.getProperty("user.home") + value.substring(1) : value);
        return p.isAbsolute() ? p.normalize() : base.resolve(p).normalize();
    }

    private static List<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static int intValue(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration value " + key + " must be an integer: " + v);
        }
    }
}
