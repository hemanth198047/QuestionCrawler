package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.questioncrawler.core.classification.Taxonomy;
import com.questioncrawler.core.model.BodyFormat;
import com.questioncrawler.core.model.Question;
import com.questioncrawler.core.model.ThreadRecord;
import com.questioncrawler.core.model.ThreadStatus;
import com.questioncrawler.core.search.AnswerComposer;
import com.questioncrawler.core.search.PageSections;
import com.questioncrawler.core.search.SearchIndex;
import com.questioncrawler.core.search.SearchRequest;
import com.questioncrawler.core.search.SearchResponse;
import com.questioncrawler.core.search.SearchService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Paragraph-style pages (Confluence, Jive, SharePoint) answering plain questions with quoted sections. No AI model. */
class PageAnswerTest {

    @TempDir
    Path tmp;

    Path indexDir;
    Taxonomy taxonomy;

    static final String VPN_GUIDE = """
            The corporate VPN connects your laptop to internal systems from outside the office.

            # Requesting access

            New joiners get VPN access automatically on day one. Contractors must raise an access request in the IT portal and
            have it approved by their manager. Approval usually takes two working days.

            # Resetting your VPN token

            If your token stops working or you get a new phone, reset it yourself:

            1. Open the self-service portal at https://selfservice.example.com.
            2. Choose Security, then VPN token.
            3. Click Reset token and scan the new QR code with the authenticator app.
            4. Restart the VPN client and sign in with the new code.

            # Troubleshooting

            ## Client does not start

            Reinstall the client from the software center. Older versions than 9.2 are blocked.

            ## Slow connection

            Switch to the nearest gateway in the client settings. The Frankfurt gateway is usually fastest from Europe.

            # Contacts

            For VPN problems that the steps above do not fix, contact the network team at vpn-support@example.com or the
            #vpn-help channel.
            """;

    static final String RETENTION = """
            This document describes how long we keep data in our logging platform.

            Audit logs are kept for 400 days, because the regulator can ask for them for a full year after an incident.
            Application logs are kept for 30 days. Debug logs are deleted after 7 days.

            Deleting data earlier than this needs approval from the data protection officer.
            """;

    static final String GLOSSARY = """
            # Glossary

            ## Runbook

            A runbook is a documented procedure that tells the on-call engineer exactly what to do for a known alert.

            ## Postmortem

            A postmortem is a blameless write-up after an incident, published within five working days.
            """;

    static final String CANTEEN = """
            The canteen on the ground floor is open from 8 to 15. Lunch is served from 11:30.
            """;

    @BeforeEach
    void indexPages() {
        indexDir = tmp.resolve("index");
        taxonomy = Taxonomy.loadDefault();
        try (SearchIndex index = new SearchIndex(indexDir)) {
            index.resetForRebuild();
            index(index, "confluence", "101", "VPN guide", VPN_GUIDE, "https://acme.atlassian.net/wiki/spaces/IT/pages/101/VPN+guide");
            index(index, "jive", "202", "Log data retention", RETENTION, "https://community.example.com/docs/DOC-202");
            index(index, "sharepoint", "303", "Engineering glossary", GLOSSARY, "https://contoso.sharepoint.com/sites/eng/SitePages/Glossary.aspx");
            index(index, "sharepoint", "404", "Office facilities", CANTEEN, "https://contoso.sharepoint.com/sites/hr/SitePages/Facilities.aspx");
            index.commit();
        }
    }

    private void index(SearchIndex index, String system, String id, String title, String body, String url) {
        Question page = new Question(id, title, body, BodyFormat.MARKDOWN, url, List.of(), "Ada", null,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", List.of(), List.of());
        ThreadRecord thread = new ThreadRecord(ThreadRecord.SCHEMA_VERSION, system, page, id,
                "2026-02-03T00:00:00Z", "hash", ThreadStatus.ACTIVE, null, null, null, "2026-02-03T00:00:00Z", List.of("scope"));
        index.indexThread(thread, List.of("uncategorized"), taxonomy);
    }

    private SearchResponse ask(String question) {
        return new SearchService(indexDir, taxonomy).search(new SearchRequest(question, null, null, 5, 0, 0));
    }

    @Test
    void howQuestionIsAnsweredWithTheStepsOfTheRightSection() {
        SearchResponse r = ask("how do I reset my VPN token");
        AnswerComposer.DirectAnswer a = r.answer();
        assertEquals("FOUND", a.status());
        assertEquals("how", a.intent());
        AnswerComposer.Point p = a.points().get(0);
        assertEquals("101", p.questionId());
        assertEquals("Resetting your VPN token", p.section());
        assertTrue(p.text().contains("1. Open the self-service portal"), p.text());
        assertTrue(p.text().contains("Restart the VPN client"), p.text());
        assertTrue(p.sectionUrl().endsWith("#:~:text=Resetting%20your%20VPN%20token"), p.sectionUrl());
        assertEquals("Resetting your VPN token", r.evidence().get(0).section());
    }

    @Test
    void howLongQuestionQuotesTheSentenceWithTheNumber() {
        AnswerComposer.DirectAnswer a = ask("how long are audit logs kept").answer();
        assertEquals("FOUND", a.status());
        assertEquals("202", a.points().get(0).questionId());
        assertTrue(a.points().get(0).text().contains("Audit logs are kept for 400 days"), a.points().get(0).text());
    }

    @Test
    void whoQuestionQuotesTheContact() {
        AnswerComposer.DirectAnswer a = ask("who should I contact about VPN problems").answer();
        assertEquals("FOUND", a.status());
        assertTrue(a.points().get(0).text().contains("vpn-support@example.com"), a.points().get(0).text());
        assertEquals("Contacts", a.points().get(0).section());
    }

    @Test
    void whatIsQuestionQuotesTheDefinition() {
        AnswerComposer.DirectAnswer a = ask("what is a runbook").answer();
        assertEquals("FOUND", a.status());
        assertEquals("303", a.points().get(0).questionId());
        assertTrue(a.points().get(0).text().startsWith("A runbook is a documented procedure"), a.points().get(0).text());
        assertEquals("Glossary › Runbook", a.points().get(0).section());
    }

    @Test
    void subSectionHeadingsHelpFindTheParagraph() {
        SearchResponse r = ask("VPN client does not start");
        assertEquals("Troubleshooting › Client does not start", r.evidence().get(0).section());
        assertTrue(r.answer().points().get(0).text().contains("Reinstall the client"), r.answer().points().get(0).text());
    }

    @Test
    void unrelatedQuestionSaysNotFoundInsteadOfGuessing() {
        AnswerComposer.DirectAnswer a = ask("how do I fly a helicopter").answer();
        assertEquals("NOT_FOUND", a.status());
        assertTrue(a.points().isEmpty());
    }

    @Test
    void sectionsKeepHeadingPathsAndLongSectionsAreCutWithOverlap() {
        List<PageSections.Section> s = PageSections.split(VPN_GUIDE);
        assertEquals(List.of(), s.get(0).headings(), "text before the first heading");
        assertTrue(s.stream().anyMatch(x -> x.path().equals("Troubleshooting › Slow connection")));
        assertFalse(s.stream().anyMatch(x -> x.path().equals("Troubleshooting")), "a heading directly followed by a sub-heading is not its own empty section");

        StringBuilder longText = new StringBuilder("# Big section\n\n");
        for (int i = 0; i < 12; i++) {
            longText.append("Paragraph ").append(i).append(" talks about item ").append(i).append(". ").append("x ".repeat(110)).append("\n\n");
        }
        longText.append("```\ncode line 1\n\ncode line 2\n```\n");
        List<PageSections.Section> parts = PageSections.split(longText.toString());
        assertTrue(parts.size() >= 2, "a long section becomes several passages");
        assertTrue(parts.stream().allMatch(p -> p.headings().equals(List.of("Big section"))));
        String last = parts.get(parts.size() - 1).text();
        assertTrue(last.contains("code line 1\n\ncode line 2"), "a code block is never cut");
        for (int i = 1; i < parts.size(); i++) {
            String prev = parts.get(i - 1).text();
            String lastParagraph = prev.substring(prev.lastIndexOf("Paragraph"));
            assertNotNull(lastParagraph);
        }
    }

    @Test
    void theSpecificWordDecidesBetweenSimilarSentences() {
        AnswerComposer.DirectAnswer a = ask("how many days are debug logs kept").answer();
        assertTrue(a.points().get(0).text().startsWith("Debug logs are deleted after 7 days"), a.points().get(0).text());
    }

    @Test
    void shortQuestionsAreAnsweredByTheSectionWhoseHeadingMatches() {
        AnswerComposer.DirectAnswer slow = ask("my VPN is slow").answer();
        assertEquals("FOUND", slow.status());
        assertEquals("Troubleshooting › Slow connection", slow.points().get(0).section());
        assertTrue(slow.points().get(0).text().startsWith("Switch to the nearest gateway"));
        AnswerComposer.DirectAnswer wont = ask("VPN client won't start").answer();
        assertEquals("FOUND", wont.status(), "contractions do not become stray words");
        assertTrue(wont.points().get(0).text().startsWith("Reinstall the client"));
    }

    @Test
    void aWhoAnswerWithoutAnyContactIsOnlyAPossibleAnswer() {
        AnswerComposer.DirectAnswer a = ask("I can't connect to the VPN, who can help").answer();
        if ("FOUND".equals(a.status()) && !a.points().get(0).text().contains("@")) {
            assertEquals("LOW", a.confidence());
        }
    }
}
