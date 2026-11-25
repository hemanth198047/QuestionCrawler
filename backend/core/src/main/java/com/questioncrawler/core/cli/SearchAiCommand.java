package com.questioncrawler.core.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.cli.Args.UsageException;
import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiException;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.searchai.SearchAiService;
import com.questioncrawler.core.searchai.SearchAiStore;
import com.questioncrawler.core.searchai.StoredAnswer;
import com.questioncrawler.core.service.CoreConfig;
import com.questioncrawler.core.util.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/** {@code searchai <whoami|search|answer|summary|usecases|list|show>}: query the search-ai REST API and keep the answers. */
final class SearchAiCommand {

    static final String USAGE = """
            searchai commands (Kerberos: run 'kinit' first on Linux/macOS; settings are searchai.* in the configuration):
              searchai whoami                              Check the sign-in (GET /whoami); not stored
              searchai search <text...>                    Raw search results (POST /search); stored
                  --rows <n>  --start <n>  --use-case <u>  --sort-by <s>  --pages <n>
              searchai answer <text...> [--use-case <u>]   AI-synthesised answer plus sources (POST /genairesponse); stored
              searchai summary <text...> --chunk-id <id>   Summary of one search hit (POST /summary); stored
              searchai usecases                            List the use_case filters (GET /usecases); stored
              searchai list [--kind <k>] [--top <n>]       List stored answers, newest first
              searchai show <id>                           Print one stored answer (id from 'list' or a search)
            """;

    private final PrintStream out;
    private final PrintStream err;

    SearchAiCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    int run(Args a, CoreConfig config) {
        if (a.positional.isEmpty()) {
            throw new UsageException("searchai needs a sub-command.\n" + USAGE);
        }
        String sub = a.positional.remove(0);
        SearchAiOptions options = SearchAiOptions.load(config.configFile(), config.storageRoot());
        SearchAiStore store = new SearchAiStore(options.storeDir());
        try {
            return switch (sub) {
                case "list" -> list(a, store);
                case "show" -> show(a, store);
                case "whoami" -> whoami(a, service(options, store));
                case "search" -> search(a, service(options, store));
                case "answer" -> answer(a, service(options, store));
                case "summary" -> summary(a, service(options, store));
                case "usecases" -> printStored(a, service(options, store).useCases());
                default -> throw new UsageException("Unknown searchai sub-command: " + sub + "\n" + USAGE);
            };
        } catch (SearchAiException e) {
            err.println("search-ai: " + e.getMessage());
            return 1;
        }
    }

    private static SearchAiService service(SearchAiOptions options, SearchAiStore store) {
        return new SearchAiService(new SearchAiClient(options), store);
    }

    private int whoami(Args a, SearchAiService service) {
        var r = service.whoami();
        if (a.json()) {
            json(r.json() != null ? r.json() : Json.MAPPER.getNodeFactory().textNode(r.text()));
        } else {
            out.println("Signed in (HTTP " + r.httpStatus() + "): " + (r.json() != null ? Json.writeCompact(r.json()) : r.text().strip()));
        }
        return 0;
    }

    private int search(Args a, SearchAiService service) {
        String query = query(a, "searchai search needs a query, for example: searchai search \"Claude Code\"");
        List<StoredAnswer> pages = service.search(query, a.value("--rows") == null ? null : a.intValue("--rows", 0),
                a.value("--start") == null ? null : a.intValue("--start", 0), a.value("--use-case"), a.value("--sort-by"), a.intValue("--pages", 1));
        if (a.json()) {
            json(pages);
        } else {
            for (StoredAnswer p : pages) {
                out.println("Stored " + p.id() + "  (start=" + p.params().getOrDefault("start", "0") + ", " + describe(p.response()) + ")");
            }
            out.println("Folder: " + service.store().dir());
        }
        return 0;
    }

    private int answer(Args a, SearchAiService service) {
        return printStored(a, service.answer(query(a, "searchai answer needs a question"), a.value("--use-case")));
    }

    private int summary(Args a, SearchAiService service) {
        if (a.value("--chunk-id") == null) {
            throw new UsageException("searchai summary needs --chunk-id <id> (take it from a search result)");
        }
        return printStored(a, service.summary(query(a, "searchai summary needs the query the search used"), a.value("--chunk-id")));
    }

    private int printStored(Args a, StoredAnswer stored) {
        if (a.json()) {
            json(stored);
        } else {
            out.println("Stored " + stored.id() + "  (" + describe(stored.response()) + ")");
            if (stored.response() != null) {
                out.println(Json.write(stored.response()));
            } else if (stored.text() != null) {
                out.println(stored.text());
            }
        }
        return 0;
    }

    private int list(Args a, SearchAiStore store) {
        List<StoredAnswer> answers = store.list(a.value("--kind"), a.intValue("--top", 20));
        if (a.json()) {
            json(answers);
        } else if (answers.isEmpty()) {
            out.println("Nothing stored yet in " + store.dir());
        } else {
            for (StoredAnswer s : answers) {
                out.println(String.format("%-48s %-9s %s", s.id(), s.kind(), s.params()));
            }
        }
        return 0;
    }

    private int show(Args a, SearchAiStore store) {
        if (a.positional.size() != 1) {
            throw new UsageException("searchai show needs exactly one id (see 'searchai list')");
        }
        var stored = store.read(a.positional.get(0));
        if (stored.isEmpty()) {
            err.println("No stored answer '" + a.positional.get(0) + "'.");
            return 1;
        }
        json(stored.get());
        return 0;
    }

    private static String query(Args a, String message) {
        if (a.positional.isEmpty()) {
            throw new UsageException(message);
        }
        return String.join(" ", a.positional);
    }

    private static String describe(JsonNode response) {
        if (response == null) {
            return "not JSON";
        }
        return response.isArray() ? response.size() + " item(s)" : response.isObject() ? response.size() + " field(s)" : "value";
    }

    private void json(Object value) {
        try {
            out.println(Json.MAPPER.copy().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true).writeValueAsString(value));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
