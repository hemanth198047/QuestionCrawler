package com.questioncrawler.core.api;

import com.questioncrawler.core.api.ApiCatalog.Parameter;
import java.util.List;
import java.util.Locale;

/**
 * What the questions endpoint calls its parameters. Stack Exchange 2.3 style APIs use {@code page}, {@code pagesize}, {@code tagged}, {@code sort},
 * {@code order}, {@code fromdate}, {@code min}, {@code filter}; other APIs use {@code pageSize}, {@code tags}, {@code createdAfter} and so on. The names
 * (and for dates and sort values, the expected form) are read from the API's own documentation; a name that is not documented is not sent.
 *
 * @param documented true when the names come from a document (otherwise the Stack Exchange defaults are assumed to be right)
 */
public record ParamMap(String page, String size, String tag, String sort, String order, String from, String min, String filter,
                       List<String> sortValues, List<String> orderValues, boolean fromIsText, boolean minIsText, boolean documented) {

    private static final List<String> PAGE = List.of("page", "pagenumber", "pageindex", "pageno", "p");
    private static final List<String> SIZE = List.of("pagesize", "perpage", "pagelimit", "limit", "take");
    private static final List<String> TAG = List.of("tagged", "tags", "tag", "tagnames", "tagname", "taggedwith");
    private static final List<String> SORT = List.of("sort", "sortby", "orderby", "sortfield");
    private static final List<String> ORDER = List.of("order", "sortorder", "sortdirection", "direction");
    private static final List<String> FROM = List.of("fromdate", "createdafter", "createdsince", "createdfrom", "since", "from", "startdate", "datefrom", "afterdate");
    private static final List<String> MIN = List.of("min", "modifiedsince", "updatedsince", "lastactivityafter", "changedsince");
    private static final List<String> FILTER = List.of("filter");

    /** The Stack Exchange 2.3 names, used when nothing is documented. */
    public static ParamMap defaults() {
        return new ParamMap("page", "pagesize", "tagged", "sort", "order", "fromdate", "min", "filter", List.of(), List.of(), false, false, false);
    }

    public static ParamMap from(List<Parameter> params) {
        if (params == null || params.isEmpty()) {
            return defaults();
        }
        Parameter page = find(params, PAGE);
        Parameter size = find(params, SIZE);
        Parameter tag = find(params, TAG);
        Parameter sort = find(params, SORT);
        Parameter order = find(params, ORDER);
        Parameter from = find(params, FROM);
        Parameter min = find(params, MIN);
        Parameter filter = find(params, FILTER);
        return new ParamMap(page == null ? "page" : page.name(), size == null ? "pagesize" : size.name(), name(tag), name(sort), name(order), name(from), name(min), name(filter),
                sort == null ? List.of() : sort.allowed(), order == null ? List.of() : order.allowed(),
                from != null && textual(from), min != null && textual(min), true);
    }

    /** ";" for the Stack Exchange 'tagged' parameter, "," for the others. */
    public String tagSeparator() {
        return tag != null && squash(tag).equals("tagged") ? ";" : ",";
    }

    /** A sort value the API accepts that means "by activity" (or "by creation"): from the allowed values if they are listed. */
    public String sortFor(String preferred) {
        if (sortValues.isEmpty()) {
            return preferred;
        }
        String key = preferred.equals("activity") ? "activity" : "creat";
        for (String v : sortValues) {
            String q = squash(v);
            if (q.contains(key) || (preferred.equals("activity") && (q.contains("modif") || q.contains("updat"))) || (!preferred.equals("activity") && q.contains("date"))) {
                return v;
            }
        }
        return sortValues.get(0);
    }

    public String ascending() {
        for (String v : orderValues) {
            if (squash(v).startsWith("asc")) {
                return v;
            }
        }
        return "asc";
    }

    private static Parameter find(List<Parameter> params, List<String> candidates) {
        for (String c : candidates) {
            for (Parameter p : params) {
                if (squash(p.name()).equals(c)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static String name(Parameter p) {
        return p == null ? null : p.name();
    }

    private static boolean textual(Parameter p) {
        String t = p.type() == null ? "" : p.type().toLowerCase(Locale.ROOT);
        return t.equals("string");
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\-\\s]", "");
    }
}
