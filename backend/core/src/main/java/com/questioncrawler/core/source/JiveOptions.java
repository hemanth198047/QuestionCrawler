package com.questioncrawler.core.source;

import java.util.Arrays;
import java.util.List;

/**
 * Jive-only connection options. Kept separate from Stack Exchange / Confluence / live / fixture settings
 * so community keys never mix with wiki or Q&amp;A API fields. Secrets stay as environment-variable names.
 *
 * <p>SharePoint will be a separate {@code source.type} later — not an extension of this record.
 */
public record JiveOptions(
        String apiRoot,
        List<String> places,
        List<String> contentTypes,
        boolean collectComments,
        int pageSize,
        boolean includePlaceTag,
        String username,
        String usernameEnv) {

    public static final JiveOptions DEFAULT = new JiveOptions(
            "", List.of(), List.of("document", "discussion", "question"), false, 25, true, "", "");

    public JiveOptions {
        apiRoot = apiRoot == null ? "" : apiRoot.strip();
        places = places == null ? List.of() : List.copyOf(places);
        contentTypes = contentTypes == null || contentTypes.isEmpty()
                ? List.of("document", "discussion", "question")
                : List.copyOf(contentTypes);
        pageSize = pageSize <= 0 ? 25 : Math.min(pageSize, 100);
        username = username == null ? "" : username.strip();
        usernameEnv = usernameEnv == null ? "" : usernameEnv.strip();
    }

    /** Default Core REST root when {@link #apiRoot} is empty. */
    public String defaultApiRootPath() {
        return "/api/core/v3";
    }

    /** Opaque fragment folded into the sync checkpoint scope (places + content types). */
    public String scopeFragment() {
        String placesKey = String.join(",", places.stream().map(s -> s.toLowerCase()).sorted().toList());
        String typesKey = String.join(",", contentTypes.stream().map(s -> s.toLowerCase()).sorted().toList());
        return "places=" + placesKey + "|types=" + typesKey;
    }

    public boolean hasBoundedScope() {
        return !places.isEmpty();
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
