package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.service.ConfigService;
import com.questioncrawler.core.service.ConfigService.Issue;
import com.questioncrawler.core.service.CoreConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServiceTest {

    @TempDir
    Path tmp;

    private final ConfigService service = new ConfigService(k -> null);

    private static boolean has(List<Issue> issues, String key, String level) {
        return issues.stream().anyMatch(i -> i.key().equals(key) && i.level().equals(level));
    }

    @Test
    void everySettingIsExplainedInPlainLanguage() {
        Set<String> keys = new HashSet<>();
        for (var section : ConfigService.sections()) {
            assertFalse(section.title().isBlank());
            assertFalse(section.description().isBlank());
            for (var field : section.fields()) {
                assertTrue(keys.add(field.key()), "duplicate " + field.key());
                assertTrue(field.label().length() > 2 && field.help().length() > 15, field.key() + " needs a label and an explanation");
                if (field.type() == ConfigService.Type.SELECT) {
                    assertTrue(field.options().size() >= 2, field.key());
                    field.options().forEach(o -> assertFalse(o.hint() == null || o.hint().isBlank(), field.key() + " option " + o.value()));
                }
            }
        }
        assertTrue(keys.containsAll(Set.of("source.type", "sync.tags", "sync.maxQuestionAge", "storage.root")));
    }

    @Test
    void savedFileIsReadableByTheRealConfigLoaderAndExplainsItself() throws Exception {
        Path file = tmp.resolve("cfg/config.properties");
        var result = service.save(file, Map.of("source.type", "stackexchange", "source.siteUrl", "https://stackoverflow.com/",
                "sync.tags", "Artificial Intelligence", "sync.maxQuestionAge", "90d", "storage.root", tmp.resolve("data").toString().replace('\\', '/')));
        assertTrue(result.saved(), result.issues().toString());
        String text = Files.readString(file);
        assertTrue(text.contains("# Tags: Press Enter after each tag"), "settings are documented inside the file");
        assertTrue(text.contains("sync.maxQuestionAge=90d"));
        assertFalse(text.toLowerCase().contains("password="));

        CoreConfig c = CoreConfig.load(file, Map.of());
        assertEquals("stackexchange", c.sourceType());
        assertEquals("stackoverflow", c.source().site());
        assertEquals(List.of("Artificial Intelligence"), c.tags());
        assertEquals("90d", c.maxQuestionAge());
        assertEquals(List.of(), c.validate());
        assertEquals("90d", service.read(file).get("sync.maxQuestionAge"));
    }

    @Test
    void partialSavesKeepEverythingElse() {
        Path file = tmp.resolve("c.properties");
        service.save(file, Map.of("sync.tags", "a,b", "sync.concurrency", "3"));
        service.save(file, Map.of("sync.maxQuestionAge", "30d"));
        var values = service.read(file);
        assertEquals("a,b", values.get("sync.tags"));
        assertEquals("3", values.get("sync.concurrency"));
        assertEquals("30d", values.get("sync.maxQuestionAge"));
    }

    @Test
    void problemsAreReportedInPlainLanguageWithAFixAndErrorsBlockSaving() {
        Path file = tmp.resolve("bad.properties");
        var bad = service.save(file, Map.of("source.type", "live", "source.baseUrl", "http://qa.internal/api", "sync.maxQuestionAge", "recently",
                "sync.concurrency", "99", "source.auth.method", "token", "source.auth.tokenEnv", "not a name"));
        assertFalse(bad.saved());
        assertFalse(Files.exists(file), "nothing is written when there are errors");
        assertTrue(has(bad.issues(), "source.baseUrl", "error"));
        assertTrue(has(bad.issues(), "sync.maxQuestionAge", "error"));
        assertTrue(has(bad.issues(), "sync.concurrency", "error"));
        assertTrue(has(bad.issues(), "source.auth.tokenEnv", "error"));
        bad.issues().stream().filter(i -> i.level().equals("error")).forEach(i -> {
            assertTrue(i.message().length() > 10, i.key());
            assertNotNull(i.message());
        });
        assertTrue(bad.issues().stream().filter(i -> i.key().equals("sync.maxQuestionAge")).findFirst().orElseThrow().fix().contains("30d"));
    }

    @Test
    void warningsDoNotBlockSavingAndMissingTokenVariableIsExplained() {
        Path file = tmp.resolve("warn.properties");
        var result = service.save(file, Map.of("source.type", "live", "source.baseUrl", "https://qa.example.com/api", "source.auth.method", "token",
                "source.auth.tokenEnv", "MY_TOKEN", "sync.tags", ""));
        assertTrue(result.saved(), result.issues().toString());
        assertTrue(has(result.issues(), "sync.tags", "warning"));
        var tokenWarning = result.issues().stream().filter(i -> i.key().equals("source.auth.tokenEnv")).findFirst().orElseThrow();
        assertEquals("warning", tokenWarning.level());
        assertTrue(tokenWarning.fix().contains("MY_TOKEN"));
        assertEquals("qa.example.com", service.read(file).get("source.allowedHosts"), "allowed host follows the address typed");
    }

    @Test
    void stackExchangeSpecificRules() {
        var issues = service.validate(new java.util.LinkedHashMap<>(Map.of("source.type", "stackexchange", "source.siteUrl", "stackoverflow", "sync.partialTags", "true",
                "sync.tags", "a,b,c,d,e,f", "sync.tagMode", "ALL")));
        assertTrue(has(issues, "source.siteUrl", "error"));
        assertTrue(has(issues, "sync.partialTags", "error"));
        assertTrue(has(issues, "sync.tagMode", "error"));
        var noKey = service.validate(new java.util.LinkedHashMap<>(Map.of("source.type", "stackexchange", "source.siteUrl", "https://stackoverflow.com/", "sync.tags", "ai")));
        assertTrue(has(noKey, "source.stackexchange.keyEnv", "info"), "anonymous quota is explained, not scary");
    }

    @Test
    void presetsAreCompleteAndValid() {
        for (var preset : ConfigService.presets()) {
            assertFalse(preset.description().isBlank());
            var values = new java.util.LinkedHashMap<>(ConfigService.defaults());
            values.putAll(preset.values());
            assertFalse(service.validate(values).stream().anyMatch(i -> i.level().equals("error")
                    && !i.key().equals("source.baseUrl")
                    && !i.key().equals("source.confluence.tvm.url")
                    && !i.key().equals("api.swaggerUrl")
                    && !i.key().equals("source.auth.tokenEnv")), preset.id());
        }
    }

    @Test
    void renderedFileRoundTripsEveryValue() {
        Path file = tmp.resolve("rt.properties");
        var values = new java.util.LinkedHashMap<>(ConfigService.defaults());
        values.put("sync.tags", "x y,z");
        values.put("taxonomy.file", "C:\\Data\\Q A\\categories.json");
        service.save(file, values);
        var back = service.read(file);
        assertEquals("x y,z", back.get("sync.tags"));
        assertEquals("C:\\Data\\Q A\\categories.json", back.get("taxonomy.file"), "Windows paths survive");
    }
}
