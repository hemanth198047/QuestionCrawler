package com.questioncrawler.core.source;

import java.util.Arrays;
import java.util.List;

/**
 * Confluence-only connection options. Kept separate from Stack Exchange / live / fixture settings
 * so Cloud wiki keys never mix with Q&amp;A API fields. Secrets stay as environment-variable names.
 *
 * <p>Jive and SharePoint will be separate {@code source.type} values later — not extensions of this record.
 */
public record ConfluenceOptions(
        String edition,
        String apiRoot,
        List<String> spaces,
        String ancestorPageId,
        List<String> contentTypes,
        String bodyExpand,
        boolean collectComments,
        int pageSize,
        boolean includeSpaceTag,
        String email,
        String emailEnv) {

    public static final ConfluenceOptions DEFAULT = new ConfluenceOptions(
            "cloud", "", List.of(), "", List.of("page"), "body.storage", false, 25, true, "", "");

    public ConfluenceOptions {
        edition = edition == null || edition.isBlank() ? "cloud" : edition.strip().toLowerCase();
        apiRoot = apiRoot == null ? "" : apiRoot.strip();
        spaces = spaces == null ? List.of() : List.copyOf(spaces);
        ancestorPageId = ancestorPageId == null ? "" : ancestorPageId.strip();
        contentTypes = contentTypes == null || contentTypes.isEmpty() ? List.of("page") : List.copyOf(contentTypes);
        bodyExpand = bodyExpand == null || bodyExpand.isBlank() ? "body.storage" : bodyExpand.strip();
        pageSize = pageSize <= 0 ? 25 : Math.min(pageSize, 50);
        email = email == null ? "" : email.strip();
        emailEnv = emailEnv == null ? "" : emailEnv.strip();
    }

    public boolean cloud() {
        return !"server".equals(edition);
    }

    /** Default REST root for the edition when {@link #apiRoot} is empty. */
    public String defaultApiRootPath() {
        return cloud() ? "/wiki/rest/api" : "/rest/api";
    }

    /** Opaque fragment folded into the sync checkpoint scope (spaces + ancestor). */
    public String scopeFragment() {
        String spacesKey = String.join(",", spaces.stream().map(s -> s.toLowerCase()).sorted().toList());
        return "spaces=" + spacesKey + "|ancestor=" + ancestorPageId.toLowerCase();
    }

    public boolean hasBoundedScope() {
        return !spaces.isEmpty() || !ancestorPageId.isEmpty();
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
