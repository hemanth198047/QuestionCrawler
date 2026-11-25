package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.cli.Cli;
import com.questioncrawler.core.searchai.CurlRunner;
import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiException;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.searchai.SearchAiService;
import com.questioncrawler.core.searchai.SearchAiStore;
import com.questioncrawler.core.searchai.StoredAnswer;
import com.questioncrawler.core.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** search-ai client, store and CLI without a network or a Kerberos ticket: curl is replaced by a fake. */
class SearchAiTest {

    private static final String BASE = "https://search-ai.example.test/searchapi";

    @TempDir
    Path tmp;

    /** Records every curl command line and answers by URL. */
    private static final class FakeCurl implements CurlRunner {
        final List<List<String>> calls = new ArrayList<>();
        Function<String, String> answer; // url -> "<body>\n__QC_HTTP_STATUS__:<code>"
        int exit;

        @Override
        public Result run(List<String> command, int timeoutSeconds) {
            calls.add(command);
            return new Result(exit, answer.apply(command.get(command.size() - 1)), exit == 0 ? "" : "curl: (7) Failed to connect");
        }

        static String ok(String body) {
            return body + "\n__QC_HTTP_STATUS__:200";
        }
    }

    private SearchAiOptions options() {
        return new SearchAiOptions(BASE + "/", tmp.resolve("jar/cookies.jar"), "curl", 30, "", 10, tmp.resolve("store"));
    }

    @Test
    void whoamiIsCalledOnceBeforeTheFirstPostAndUsesKerberosAndTheCookieJar() {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> FakeCurl.ok(url.contains("/whoami") ? "{\"user\":\"me\"}" : "{\"results\":[]}");
        SearchAiClient client = new SearchAiClient(options(), curl);

        client.search("Claude Code", 10, null, null, null);
        client.search("Claude Code", 10, 10, "tech", "relevance");

        assertEquals(3, curl.calls.size());
        assertTrue(curl.calls.get(0).get(curl.calls.get(0).size() - 1).endsWith("/searchapi/whoami"));
        List<String> post = curl.calls.get(1);
        assertTrue(post.containsAll(List.of("--negotiate", "-u", ":", "--location-trusted", "-c", "-b", "-X", "POST")));
        assertEquals(post.get(post.indexOf("-c") + 1), post.get(post.indexOf("-b") + 1));
        assertEquals(BASE + "/search?query=Claude+Code&rows=10", post.get(post.size() - 1));
        assertEquals(BASE + "/search?query=Claude+Code&rows=10&start=10&use_case=tech&sort_by=relevance",
                curl.calls.get(2).get(curl.calls.get(2).size() - 1));
        assertTrue(Files.exists(tmp.resolve("jar/cookies.jar")));
    }

    @Test
    void useCaseAndStartUseTheDocumentedSingularKeys() {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> FakeCurl.ok("{}");
        new SearchAiClient(options(), curl).search("x", 5, 20, "tech", null);
        String url = curl.calls.get(1).get(curl.calls.get(1).size() - 1);
        assertTrue(url.contains("use_case=tech") && url.contains("start=20"));
        assertFalse(url.contains("usecases=") || url.contains("page=") || url.contains("currentPage="));
    }

    @Test
    void genAiResponseSendsAnEmptyJsonBodyAndEncodesTheQuestion() {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> FakeCurl.ok("{\"answer\":\"a\"}");
        new SearchAiClient(options(), curl).genAiResponse("how to deploy & scale on k8s?", "tech");
        List<String> post = curl.calls.get(1);
        assertEquals("{}", post.get(post.indexOf("-d") + 1));
        assertTrue(post.contains("Content-Type: application/json"));
        assertEquals(BASE + "/genairesponse?query=how+to+deploy+%26+scale+on+k8s%3F&use_case=tech", post.get(post.size() - 1));
    }

    @Test
    void errorsSayWhatToDo() {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> "unauthorized\n__QC_HTTP_STATUS__:401";
        SearchAiException e = assertThrows(SearchAiException.class, () -> new SearchAiClient(options(), curl).whoami());
        assertEquals(401, e.httpStatus());
        assertTrue(e.getMessage().contains("kinit"));

        FakeCurl unreachable = new FakeCurl();
        unreachable.exit = 7;
        unreachable.answer = url -> "";
        assertTrue(assertThrows(SearchAiException.class, () -> new SearchAiClient(options(), unreachable).whoami()).getMessage().contains("connect"));

        FakeCurl invalid = new FakeCurl();
        invalid.answer = url -> url.contains("whoami") ? FakeCurl.ok("{}") : "{\"detail\":\"bad\"}\n__QC_HTTP_STATUS__:422";
        assertTrue(assertThrows(SearchAiException.class, () -> new SearchAiClient(options(), invalid).genAiResponse("q", null)).getMessage().contains("422"));
    }

    @Test
    void invalidInputIsRefusedBeforeAnythingIsSent() {
        FakeCurl curl = new FakeCurl();
        SearchAiClient client = new SearchAiClient(options(), curl);
        assertThrows(IllegalArgumentException.class, () -> client.search("  ", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> client.search("x", 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> client.summary("x", " "));
        assertThrows(IllegalArgumentException.class, () -> new SearchAiOptions("http://plain.example/searchapi", tmp.resolve("j"), "curl", 30, "", 10, tmp));
        assertTrue(curl.calls.isEmpty());
    }

    @Test
    void answersAreStoredUnchangedAndCanBeListedAndReadBack() throws Exception {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> FakeCurl.ok(url.contains("/usecases") ? "[\"tech\",\"hr\"]" : "{\"results\":[{\"chunkid\":\"1_0\",\"title\":\"T\"}]}");
        SearchAiService service = new SearchAiService(new SearchAiClient(options(), curl), new SearchAiStore(options().storeDir()));

        StoredAnswer search = service.search("Claude Code", 10, null, null, null, 1).get(0);
        StoredAnswer cases = service.useCases();

        assertTrue(search.id().startsWith("search/"));
        assertEquals("1_0", search.response().at("/results/0/chunkid").asText());
        assertEquals(search.id(), service.store().read(search.id()).orElseThrow().id());
        assertEquals("Claude Code", service.store().read(search.id()).orElseThrow().params().get("query"));
        assertEquals(List.of(cases.id()), service.store().list("usecases", 10).stream().map(StoredAnswer::id).toList());
        assertEquals(2, service.store().list(null, 0).size());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.store().read("../../etc/passwd")).getMessage().contains("Not a stored answer id"));
    }

    @Test
    void pagingAdvancesStartByRowsAndStopsOnAShortPageOrARepeat() {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> {
            if (url.contains("whoami")) {
                return FakeCurl.ok("{}");
            }
            if (url.contains("start=4")) {
                return FakeCurl.ok("{\"results\":[{\"id\":5}]}"); // short page: last
            }
            boolean second = url.endsWith("start=2");
            return FakeCurl.ok("{\"results\":[{\"id\":" + (second ? 3 : 1) + "},{\"id\":" + (second ? 4 : 2) + "}]}");
        };
        SearchAiService service = new SearchAiService(new SearchAiClient(options(), curl), new SearchAiStore(options().storeDir()));
        List<StoredAnswer> pages = service.search("q", 2, null, null, null, 10);
        assertEquals(3, pages.size());
        assertEquals("2", pages.get(1).params().get("start"));
        assertEquals("4", pages.get(2).params().get("start"));

        FakeCurl ignoresStart = new FakeCurl();
        ignoresStart.answer = url -> FakeCurl.ok("{\"results\":[{\"id\":1},{\"id\":2}]}");
        assertEquals(2, new SearchAiService(new SearchAiClient(options(), ignoresStart), new SearchAiStore(tmp.resolve("s2"))).search("q", 2, null, null, null, 10).size());

        FakeCurl unknownShape = new FakeCurl();
        unknownShape.answer = url -> FakeCurl.ok("{\"weird\":1}");
        assertEquals(1, new SearchAiService(new SearchAiClient(options(), unknownShape), new SearchAiStore(tmp.resolve("s3"))).search("q", 2, null, null, null, 10).size());
    }

    @Test
    void realCurlProcessPathParsesBodyAndStatus() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path fake = tmp.resolve("fakecurl.sh");
        Files.writeString(fake, "#!/bin/sh\nprintf '{\"ok\":true}\\n__QC_HTTP_STATUS__:200'\n");
        assertTrue(fake.toFile().setExecutable(true));
        SearchAiOptions o = new SearchAiOptions(BASE, tmp.resolve("c.jar"), fake.toString(), 30, "", 10, tmp.resolve("st"));
        var r = new SearchAiClient(o).useCases();
        assertEquals(200, r.httpStatus());
        assertTrue(r.json().get("ok").asBoolean());

        SearchAiOptions missing = new SearchAiOptions(BASE, tmp.resolve("c.jar"), tmp.resolve("nope").toString(), 30, "", 10, tmp.resolve("st"));
        assertTrue(assertThrows(SearchAiException.class, () -> new SearchAiClient(missing).whoami()).getMessage().contains("Could not start"));
    }

    @Test
    void commandLineStoresAndListsAnswers() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path fake = tmp.resolve("fakecurl.sh");
        Files.writeString(fake, "#!/bin/sh\nprintf '{\"results\":[{\"chunkid\":\"9_0\"}]}\\n__QC_HTTP_STATUS__:200'\n");
        assertTrue(fake.toFile().setExecutable(true));
        Path config = tmp.resolve("qc.properties");
        Files.writeString(config, "storage.root=" + tmp.resolve("store").toString().replace('\\', '/') + "\n"
                + "searchai.baseUri=" + BASE + "\nsearchai.curl=" + fake.toString().replace('\\', '/')
                + "\nsearchai.cookieJar=" + tmp.resolve("cli.jar").toString().replace('\\', '/') + "\n");

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        Cli cli = new Cli(new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        assertEquals(0, cli.run(new String[] {"searchai", "search", "Claude", "Code", "--rows", "5", "--json", "--config", config.toString()}), e.toString());
        var json = Json.MAPPER.readTree(o.toString(StandardCharsets.UTF_8));
        assertEquals("Claude Code", json.at("/0/params/query").asText());
        assertEquals("9_0", json.at("/0/response/results/0/chunkid").asText());
        String id = json.at("/0/id").asText();
        assertNotNull(id);

        o.reset();
        assertEquals(0, cli.run(new String[] {"searchai", "list", "--config", config.toString()}));
        assertTrue(o.toString(StandardCharsets.UTF_8).contains(id));
        assertTrue(Files.exists(tmp.resolve("store/searchai").resolve(id + ".json")));
    }

    @Test
    void aWhoamiThatIsMissingOnTheServerDoesNotStopTheSearch() {
        FakeCurl curl = new FakeCurl();
        curl.answer = url -> url.contains("/whoami") ? "not found\n__QC_HTTP_STATUS__:404" : FakeCurl.ok("{\"results\":[]}");
        assertEquals(200, new SearchAiClient(options(), curl).search("q", 5, null, null, null).httpStatus());

        FakeCurl refused = new FakeCurl();
        refused.answer = url -> "no\n__QC_HTTP_STATUS__:401";
        assertEquals(401, assertThrows(SearchAiException.class, () -> new SearchAiClient(options(), refused).search("q", 5, null, null, null)).httpStatus());
    }
}
