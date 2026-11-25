package com.questioncrawler.core.searchai;

import java.util.ArrayList;
import java.util.List;

/** Calls search-ai and stores every answer. Used by the CLI and the REST API. */
public final class SearchAiService {

    /** Upper bound for one paged search, so a typo cannot download the whole index. */
    public static final int MAX_PAGES = 50;

    private final SearchAiClient client;
    private final SearchAiStore store;

    public SearchAiService(SearchAiClient client, SearchAiStore store) {
        this.client = client;
        this.store = store;
    }

    public SearchAiStore store() {
        return store;
    }

    public SearchAiClient client() {
        return client;
    }

    /** GET /whoami (not stored: it only proves the Kerberos sign-in works). */
    public SearchAiResponse whoami() {
        return client.whoami();
    }

    /**
     * Raw search results. With {@code pages} &gt; 1 the next page is requested with {@code start += rows} until a page has
     * fewer hits than {@code rows}, is empty, repeats the previous page, or the hits cannot be located in the answer.
     * Each page is stored as its own answer.
     */
    public List<StoredAnswer> search(String query, Integer rows, Integer start, String useCase, String sortBy, int pages) {
        if (pages < 1 || pages > MAX_PAGES) {
            throw new IllegalArgumentException("pages must be between 1 and " + MAX_PAGES + ".");
        }
        int pageRows = rows == null ? client.options().defaultRows() : rows;
        int offset = start == null ? 0 : start;
        List<StoredAnswer> stored = new ArrayList<>();
        String previous = null;
        for (int page = 0; page < pages; page++) {
            SearchAiResponse response = client.search(query, pageRows, page == 0 && start == null ? null : offset, useCase, sortBy);
            stored.add(store.save("search", client.options().baseUri(), response));
            if (page + 1 >= pages || response.json() == null) {
                break;
            }
            int count = ResultList.count(response.json());
            String fingerprint = ResultList.fingerprint(response.json());
            if (count < pageRows || fingerprint.equals(previous)) {
                break; // last page, no list found (-1), or the server ignored start
            }
            previous = fingerprint;
            offset += pageRows;
        }
        return stored;
    }

    public StoredAnswer answer(String query, String useCase) {
        return store.save("answer", client.options().baseUri(), client.genAiResponse(query, useCase));
    }

    public StoredAnswer summary(String query, String chunkId) {
        return store.save("summary", client.options().baseUri(), client.summary(query, chunkId));
    }

    public StoredAnswer useCases() {
        return store.save("usecases", client.options().baseUri(), client.useCases());
    }
}
