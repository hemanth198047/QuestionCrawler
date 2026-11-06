package com.questioncrawler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.questioncrawler.core.api.ApiCatalog.Parameter;
import com.questioncrawler.core.api.ParamMap;
import com.questioncrawler.core.source.ApiJson;
import com.questioncrawler.core.util.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The same questions arrive in different shapes from different APIs; none of them may be silently dropped. */
class ApiShapeTest {

    private static JsonNode json(String text) throws Exception {
        return Json.MAPPER.readTree(text);
    }

    @Test
    void theListIsFoundWhateverTheWrapperCallsIt() throws Exception {
        for (String name : new String[] {"items", "data", "results", "content", "value", "questions"}) {
            assertEquals(2, ApiJson.items(json("{\"" + name + "\":[{\"id\":1},{\"id\":2}],\"page\":1}")).size(), name);
        }
        assertEquals(2, ApiJson.items(json("[{\"id\":1},{\"id\":2}]")).size(), "the answer itself is a list");
        assertFalse(ApiJson.items(json("{\"message\":\"x\"}")).isArray(), "no list at all is detectable");
        assertFalse(ApiJson.items(json("{\"id\":1,\"title\":\"q\",\"tags\":[{\"name\":\"a\"}],\"answers\":[{\"id\":2}]}")).isArray(),
                "a single question has tags and answers of its own: they are not a list of questions");
    }

    @Test
    void thereIsMoreFromTheFlagFromThePageCountsOrFromAFullPage() throws Exception {
        assertTrue(ApiJson.hasMore(json("{\"has_more\":true}"), 1, 100, 100));
        assertFalse(ApiJson.hasMore(json("{\"has_more\":false}"), 1, 100, 100), "an explicit flag wins");
        assertTrue(ApiJson.hasMore(json("{\"page\":1,\"totalPages\":3}"), 1, 15, 15));
        assertFalse(ApiJson.hasMore(json("{\"page\":3,\"totalPages\":3}"), 3, 15, 15));
        assertTrue(ApiJson.hasMore(json("{\"page\":1,\"pageSize\":15,\"totalCount\":40}"), 1, 15, 15));
        assertFalse(ApiJson.hasMore(json("{\"page\":3,\"pageSize\":15,\"totalCount\":40}"), 3, 15, 10));
        assertTrue(ApiJson.hasMore(json("{}"), 1, 50, 50), "no information: a full page means there may be more");
        assertFalse(ApiJson.hasMore(json("{}"), 1, 50, 20), "a short page is the last");
    }

    @Test
    void datesInAnyUsualFormBecomeIsoInstants() throws Exception {
        JsonNode n = json("{\"a\":1700000000,\"b\":1700000000000,\"c\":\"2023-11-14T22:13:20Z\",\"d\":\"2023-11-14T23:13:20+01:00\",\"e\":\"2023-11-14T22:13:20\",\"f\":\"1700000000\",\"g\":\"not a date\"}");
        for (String f : new String[] {"a", "b", "c", "d", "e", "f"}) {
            assertEquals("2023-11-14T22:13:20Z", ApiJson.instant(n, f), f);
        }
        assertNull(ApiJson.instant(n, "g"));
        assertEquals("2023-11-14T22:13:20Z", ApiJson.instant(n, "missing", "creationDate", "c"), "the first name that is present wins");
    }

    @Test
    void tagsArriveAsNamesObjectsOrText() throws Exception {
        assertEquals(List.of("ai", "ml"), ApiJson.tags(json("{\"tags\":[\"ai\",\"ml\"]}")));
        assertEquals(List.of("ai", "ml"), ApiJson.tags(json("{\"tags\":[{\"id\":1,\"name\":\"ai\"},{\"id\":2,\"tagName\":\"ml\"}]}")));
        assertEquals(List.of("ai", "ml"), ApiJson.tags(json("{\"tags\":\"ai;ml\"}")));
        assertEquals(List.of(), ApiJson.tags(json("{}")));
    }

    @Test
    void idsScoresAndAuthorsAreReadFromTheUsualFields() throws Exception {
        assertEquals("42", ApiJson.id(json("{\"question_id\":42}"), "question_id", "id"));
        assertEquals("42", ApiJson.id(json("{\"id\":42}"), "question_id", "id"));
        assertEquals("abc", ApiJson.id(json("{\"id\":\"abc\"}"), "question_id", "id"));
        assertNull(ApiJson.id(json("{\"qid\":42}"), "question_id", "id"));
        assertEquals("alice", ApiJson.owner(json("{\"owner\":{\"display_name\":\"alice\"}}")));
        assertEquals("alice", ApiJson.owner(json("{\"owner\":{\"name\":\"alice\"}}")));
        assertEquals("alice", ApiJson.owner(json("{\"author\":{\"displayName\":\"alice\"}}")));
        assertEquals(3, ApiJson.integer(json("{\"votes\":3}"), "score", "votes"));
        assertTrue(ApiJson.bool(json("{\"isAccepted\":true}"), "is_accepted", "isAccepted"));
    }

    private static Parameter p(String name, String type, String... allowed) {
        return new Parameter(name, "query", false, type, "", List.of(allowed));
    }

    @Test
    void theParameterNamesComeFromTheDocumentation() {
        ParamMap v3 = ParamMap.from(List.of(p("page", "integer"), p("pageSize", "integer", "15", "30", "50", "100"), p("tags", "string"), p("sort", "string", "activity", "creation", "votes"),
                p("order", "string", "asc", "desc"), p("createdAfter", "string")));
        assertEquals("page", v3.page());
        assertEquals("pageSize", v3.size());
        assertEquals("tags", v3.tag());
        assertEquals(",", v3.tagSeparator());
        assertEquals("activity", v3.sortFor("activity"));
        assertEquals("creation", v3.sortFor("creation"));
        assertEquals("asc", v3.ascending());
        assertEquals("createdAfter", v3.from());
        assertTrue(v3.fromIsText(), "a text parameter wants an ISO date, not epoch seconds");
        assertNull(v3.min(), "not documented, so not sent");
        assertNull(v3.filter());
        assertTrue(v3.documented());

        ParamMap se = ParamMap.from(List.of(p("tagged", "string"), p("fromdate", "integer"), p("min", "integer"), p("filter", "string"), p("pagesize", "integer"), p("page", "integer")));
        assertEquals(";", se.tagSeparator());
        assertFalse(se.fromIsText());
        assertEquals("pagesize", se.size());

        ParamMap unusual = ParamMap.from(List.of(p("sortBy", "string", "LastModified", "CreatedDate"), p("direction", "string", "Ascending", "Descending")));
        assertEquals("LastModified", unusual.sortFor("activity"), "the documented word for 'by activity'");
        assertEquals("CreatedDate", unusual.sortFor("creation"));
        assertEquals("Ascending", unusual.ascending());

        ParamMap none = ParamMap.defaults();
        assertEquals("pagesize", none.size());
        assertFalse(none.documented());
    }
}
