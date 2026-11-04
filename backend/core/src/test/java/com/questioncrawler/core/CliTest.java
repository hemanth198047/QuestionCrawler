package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.cli.Cli;
import com.questioncrawler.core.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real CLI in-process, exactly as the Copilot skill and users do. */
class CliTest {

    @TempDir
    Path tmp;

    private record Result(int code, String out, String err) {}

    private Result cli(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = new Cli(new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)).run(args);
        return new Result(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private String[] withStorage(String... args) {
        String[] all = new String[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--storage";
        all[args.length + 1] = tmp.resolve("store").toString();
        return all;
    }

    @Test
    void fullWorkflowThroughTheCommandLine() throws Exception {
        assertEquals(0, cli(withStorage("sync", "--tag", "orbit")).code());

        Result search = cli(withStorage("search", "ERR_CONN_TIMEOUT", "--format", "json", "--top", "3"));
        assertEquals(0, search.code());
        JsonNode json = Json.MAPPER.readTree(search.out());
        assertEquals("1001", json.at("/evidence/0/questionId").asText());
        assertTrue(json.at("/evidence/0/untrusted").asBoolean());
        assertTrue(json.at("/evidence/0/sourceUrl").asText().startsWith("https://qa.example.internal/questions/1001"));

        // Options before the command work too.
        Result reordered = cli("--storage", tmp.resolve("store").toString(), "status", "--format", "json");
        assertEquals(0, reordered.code());
        assertEquals(10, Json.MAPPER.readTree(reordered.out()).get("threadCount").asInt());

        Result show = cli(withStorage("show", "1001", "--format", "json"));
        assertEquals(3, Json.MAPPER.readTree(show.out()).at("/thread/question/answers").size());
        assertEquals(1, cli(withStorage("show", "424242")).code());

        assertTrue(cli(withStorage("categories")).out().contains("installation-setup"));
        assertTrue(cli(withStorage("categories", "installation-setup")).out().contains("1002"));

        assertEquals(0, cli(withStorage("override", "set", "1001", "performance", "--note", "tuning")).code());
        assertTrue(cli(withStorage("show", "1001")).out().contains("manual override"));
        assertEquals(0, cli(withStorage("override", "clear", "1001")).code());

        Path zip = tmp.resolve("b.zip");
        assertEquals(0, cli(withStorage("backup", "--out", zip.toString())).code());
        assertTrue(Files.size(zip) > 0);
        assertEquals(3, cli(withStorage("restore", zip.toString())).code(), "restore requires explicit confirmation");
        assertEquals(0, cli(withStorage("reindex")).code());
    }

    @Test
    void usageErrorsAreReportedWithExitCodeTwo() {
        assertEquals(2, cli().code());
        assertEquals(2, cli("frobnicate").code());
        assertEquals(2, cli("search").code());
        assertEquals(2, cli("search", "x", "--bogus").code());
        assertEquals(2, cli("sync", "--tag").code());
        assertTrue(cli("help").out().contains("Commands:"));
    }

    @Test
    void syncWithoutTagsExplainsWhatToDo() {
        Result r = cli(withStorage("sync"));
        assertEquals(2, r.code());
        assertTrue(r.err().contains("No tags configured"));
    }

    @Test
    void searchOnAMissingStoreSaysHowToProceed() {
        Result r = cli("search", "timeout", "--storage", tmp.resolve("empty").toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("init"));
    }

    @Test
    void initWritesConfigTaxonomyAndStorageWithoutSecrets() throws Exception {
        Path cfg = tmp.resolve("cfg/config.properties");
        Result r = cli("init", "--config", cfg.toString(), "--storage", tmp.resolve("data").toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.exists(cfg));
        assertTrue(Files.exists(cfg.getParent().resolve("taxonomy.json")));
        assertTrue(Files.exists(tmp.resolve("data/storage-manifest.json")));
        assertFalse(Files.readString(cfg).contains("github_pat"));
        Result doctor = cli("doctor", "--config", cfg.toString());
        assertTrue(doctor.out().contains("storage-writable"));
        assertEquals(0, doctor.code(), doctor.out());
    }

    @Test
    void demoRunsOfflineAndShowsTheWholePipeline() {
        Result r = cli("demo", "--storage", tmp.resolve("demo").toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("COMPLETE_FOR_ACCESSIBLE_SCOPE"));
        assertTrue(r.out().contains("DELETED"));
        assertTrue(r.out().contains("No sufficiently relevant evidence"));
    }
}
