package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.search.AnswerComposer;
import com.questioncrawler.core.search.SymbolTokens;
import com.questioncrawler.core.source.ShortLinks;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Results show the part of a page that answers the question; a short-link hiccup never stops a collection. */
class RelevantPartsTest {

    static final String PAGE = """
            Welcome to the CASC onboarding portal. This site collects everything a new team member needs in the first weeks.

            The office opens at eight and the cafeteria is on the second floor. Parking passes are issued by facilities.

            To request VPN access, open the Access Portal and choose Remote access. Your manager approves the request within one day.

            Laptops are shipped on your first day. Bring your badge to the service desk to collect it.

            ```
            vpn-cli connect --profile casc
            ```

            For questions about payroll contact the HR mailbox. Holidays follow the regional calendar published every January.
            """;

    @Test
    void onlyThePartsThatAnswerTheQuestionAreShownInPageOrder() {
        List<String> parts = AnswerComposer.relevantParts(SymbolTokens.contentWords("how do I request VPN access"), PAGE, 3);
        assertTrue(!parts.isEmpty(), "something answers it");
        assertTrue(parts.get(0).startsWith("To request VPN access, open the Access Portal"), parts.toString());
        assertTrue(parts.stream().noneMatch(p -> p.contains("cafeteria") || p.contains("payroll")), "unrelated parts are left out: " + parts);
    }

    @Test
    void aShortPassageNeedsNoFiltering() {
        assertEquals(List.of(), AnswerComposer.relevantParts(SymbolTokens.contentWords("vpn"), "Use the VPN.", 3));
    }

    @Test
    void aShortLinkServiceThatAnswersStrangelyDoesNotStopTheCollection() {
        ShortLinks links = new ShortLinks(new ShortLinks.Options(true, List.of(), null, null, Duration.ofSeconds(1)), uri -> {
            throw new NullPointerException("Cannot invoke \"java.io.InputStream.readNBytes(int)\" because \"in\" is null");
        }, k -> null);
        Question q = new Question("1", "t", "See go/vpn for details.", BodyFormat.MARKDOWN, null, List.of(), null, null, null, null, List.of(), List.of());
        assertEquals("See go/vpn for details.", links.expand(q).body(), "the link stays as it was");
        assertTrue(links.resolve("go/vpn").problem().contains("could not be read"));
    }
}
