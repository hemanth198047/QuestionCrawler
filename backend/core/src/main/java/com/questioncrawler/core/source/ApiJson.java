package com.questioncrawler.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tolerant readers for the JSON of Q&A APIs. Stack Exchange 2.3 style APIs use snake_case ({@code question_id}, epoch seconds, tags as strings,
 * {@code has_more}); other APIs use camelCase ({@code id}, ISO dates, tags as objects, {@code page}/{@code totalPages}). Each reader accepts the
 * usual spellings, so a response is not silently discarded because a field has another name.
 */
public final class ApiJson {

    // Only names that are wrappers. "tags", "answers" and "comments" are NOT here: a single question has such arrays of its own.
    private static final String[] LISTS = {"items", "data", "results", "content", "value", "records", "questions"};

    private ApiJson() {}

    /** The list in a wrapper: "items" or another usual name, or the answer itself when it is a list. */
    public static JsonNode items(JsonNode root) {
        if (root == null) {
            return com.questioncrawler.core.util.Json.MAPPER.missingNode();
        }
        if (root.isArray()) {
            return root;
        }
        for (String name : LISTS) {
            if (root.path(name).isArray()) {
                return root.get(name);
            }
        }
        return root.path("items");
    }

    /** Names of the fields on an object (for explaining what an unexpected answer looks like). */
    public static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isObject()) {
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                out.add(it.next());
            }
        }
        return out;
    }

    /** Is there more after this page? has_more / hasMore, else totalPages / totalCount, else "the page was full". */
    public static boolean hasMore(JsonNode root, int page, int asked, int got) {
        for (String f : new String[] {"has_more", "hasMore", "hasNextPage", "hasNext"}) {
            if (root.has(f) && root.get(f).isBoolean()) {
                return root.get(f).asBoolean();
            }
        }
        int current = root.path("page").canConvertToInt() && root.has("page") ? root.get("page").asInt(page) : page;
        for (String f : new String[] {"totalPages", "total_pages", "pageCount", "pages"}) {
            if (root.path(f).isNumber()) {
                return current < root.get(f).asInt();
            }
        }
        int size = root.path("pageSize").isNumber() ? root.get("pageSize").asInt(asked) : root.path("page_size").isNumber() ? root.get("page_size").asInt(asked) : asked;
        for (String f : new String[] {"totalCount", "total_count", "total", "totalItems", "count"}) {
            if (root.path(f).isNumber() && size > 0 && root.get(f).asLong() >= 0 && !f.equals("count")) {
                return (long) current * size < root.get(f).asLong();
            }
        }
        return asked > 0 && got >= asked;
    }

    public static String id(JsonNode n, String... names) {
        for (String f : names) {
            JsonNode v = n.path(f);
            if (v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
            if (v.isNumber()) {
                return v.asText();
            }
        }
        return null;
    }

    public static String text(JsonNode n, String... names) {
        for (String f : names) {
            JsonNode v = n.path(f);
            if (v.isTextual()) {
                return v.asText();
            }
        }
        return null;
    }

    public static Integer integer(JsonNode n, String... names) {
        for (String f : names) {
            if (n.path(f).isNumber()) {
                return n.get(f).asInt();
            }
        }
        return null;
    }

    public static boolean bool(JsonNode n, String... names) {
        for (String f : names) {
            if (n.path(f).isBoolean()) {
                return n.get(f).asBoolean();
            }
        }
        return false;
    }

    /** A date as an ISO-8601 instant, from epoch seconds, epoch milliseconds or an ISO string. */
    public static String instant(JsonNode n, String... names) {
        for (String f : names) {
            JsonNode v = n.path(f);
            if (v.isNumber()) {
                long x = v.asLong();
                return (x > 100_000_000_000L ? Instant.ofEpochMilli(x) : Instant.ofEpochSecond(x)).toString();
            }
            if (v.isTextual() && !v.asText().isBlank()) {
                String s = v.asText().strip();
                if (s.matches("\\d{9,14}")) {
                    long x = Long.parseLong(s);
                    return (x > 100_000_000_000L ? Instant.ofEpochMilli(x) : Instant.ofEpochSecond(x)).toString();
                }
                try {
                    return Instant.parse(s).toString();
                } catch (RuntimeException ignored) {
                    // try the other usual forms
                }
                try {
                    return OffsetDateTime.parse(s).toInstant().toString();
                } catch (RuntimeException ignored) {
                    // try the other usual forms
                }
                try {
                    return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toString();
                } catch (RuntimeException ignored) {
                    // not a date we understand
                }
            }
        }
        return null;
    }

    /** Tags as names: ["a","b"], [{"name":"a"}], [{"tagName":"a"}] or "a;b". */
    public static List<String> tags(JsonNode n) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode t = n.path("tags");
        if (t.isArray()) {
            for (JsonNode x : t) {
                if (x.isTextual()) {
                    out.add(x.asText());
                } else if (x.isObject()) {
                    String name = text(x, "name", "tagName", "tag_name", "tag", "label", "title");
                    if (name != null && !name.isBlank()) {
                        out.add(name);
                    }
                }
            }
        } else if (t.isTextual()) {
            for (String s : t.asText().split("[;,|]")) {
                if (!s.isBlank()) {
                    out.add(s.strip());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The author's display name from owner / author / user objects. */
    public static String owner(JsonNode n) {
        for (String holder : new String[] {"owner", "author", "user", "createdBy", "created_by"}) {
            JsonNode o = n.path(holder);
            if (o.isObject()) {
                String name = text(o, "display_name", "displayName", "name", "fullName", "userName", "username");
                if (name != null) {
                    return name;
                }
            } else if (o.isTextual() && !o.asText().isBlank()) {
                return o.asText();
            }
        }
        return null;
    }
}
