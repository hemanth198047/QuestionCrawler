package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.searchai.CurlRunner;
import com.questioncrawler.core.searchai.KerberosTicket;
import com.questioncrawler.core.searchai.SearchAiClient;
import com.questioncrawler.core.searchai.SearchAiOptions;
import com.questioncrawler.core.searchai.SearchAiStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one-call status the Search-AI screen shows: plain-language messages, never an exception. */
class SearchAiStatusTest {

    @TempDir
    Path tmp;

    private SearchAiOptions options(String base) {
        return new SearchAiOptions(base, tmp.resolve("jar"), "curl", 30, "", 10, tmp.resolve("store"));
    }

    private static CurlRunner answering(int exit, String stdout) {
        return (command, timeout) -> new CurlRunner.Result(exit, stdout, exit == 0 ? "" : "curl: (6) Could not resolve host");
    }

    @Test
    void anAddressThatIsStillTheExampleAsksForTheAddressAndMakesNoCall() {
        CurlRunner mustNotRun = (c, t) -> { throw new AssertionError("no network call expected"); };
        SearchAiOptions o = options(SearchAiOptions.DEFAULT_BASE_URI);
        SearchAiStatus s = SearchAiStatus.check(o, new SearchAiClient(o, mustNotRun), new KerberosTicket("unknown", null));
        assertFalse(s.configured());
        assertTrue(s.message().contains("not set"));
    }

    @Test
    void connectedWhenWhoamiAnswers() {
        SearchAiOptions o = options("https://search-ai.corp.example/searchapi");
        SearchAiStatus s = SearchAiStatus.check(o, new SearchAiClient(o, answering(0, "{}\n__QC_HTTP_STATUS__:200")), new KerberosTicket("valid", "me@CORP"));
        assertTrue(s.configured() && s.signedIn());
        assertEquals("me@CORP", s.principal());
        assertNull(s.hint());
    }

    @Test
    void a401IsExplainedPerTicketState() {
        SearchAiOptions o = options("https://search-ai.corp.example/searchapi");
        CurlRunner unauthorized = answering(0, "no\n__QC_HTTP_STATUS__:401");
        assertTrue(SearchAiStatus.check(o, new SearchAiClient(o, unauthorized), new KerberosTicket("missing", null)).hint().contains("not signed in"));
        assertTrue(SearchAiStatus.check(o, new SearchAiClient(o, unauthorized), new KerberosTicket("not-needed", null)).hint().contains("Windows"));
    }

    @Test
    void networkAndCurlProblemsGetOrdinaryAdvice() {
        SearchAiOptions o = options("https://search-ai.corp.example/searchapi");
        SearchAiStatus down = SearchAiStatus.check(o, new SearchAiClient(o, answering(6, "")), new KerberosTicket("valid", null));
        assertFalse(down.signedIn());
        assertTrue(down.hint().contains("company network"));
        SearchAiOptions missingCurl = new SearchAiOptions("https://search-ai.corp.example/searchapi", tmp.resolve("jar"), tmp.resolve("nope").toString(), 30, "", 10, tmp);
        assertTrue(SearchAiStatus.check(missingCurl, new SearchAiClient(missingCurl), new KerberosTicket("valid", null)).hint().contains("curl"));
    }

    @Test
    void ticketProbeTellsValidMissingAndUnknownApart() {
        assertEquals("valid", KerberosTicket.probe(a -> a.size() == 2 ? new String[] {"0", ""} : new String[] {"0", "Ticket cache: x\nDefault principal: me@CORP\n"}).state());
        assertEquals("me@CORP", KerberosTicket.probe(a -> a.size() == 2 ? new String[] {"0", ""} : new String[] {"0", "Default principal: me@CORP"}).principal());
        assertEquals("missing", KerberosTicket.probe(a -> new String[] {"1", ""}).state());
        assertEquals("unknown", KerberosTicket.probe((List<String> a) -> null).state());
    }
}
