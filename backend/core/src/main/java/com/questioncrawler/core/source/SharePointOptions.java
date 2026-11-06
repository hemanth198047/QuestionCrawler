package com.questioncrawler.core.source;

import java.util.Arrays;
import java.util.List;

/**
 * SharePoint-only connection options. Kept separate from Stack Exchange / Confluence / Jive / live /
 * fixture settings so Microsoft Graph keys never mix with wiki or Q&amp;A API fields. Secrets stay as
 * environment-variable names.
 *
 * <p>Confluence and Jive are separate {@code source.type} values — not extensions of this record.
 */
public record SharePointOptions(
        String tenantId,
        String clientId,
        String clientSecretEnv,
        String siteId,
        List<String> listIds,
        String contentMode,
        String graphBaseUrl,
        boolean collectComments,
        int pageSize,
        boolean includeSiteTag) {

    public static final SharePointOptions DEFAULT = new SharePointOptions(
            "", "", "SHAREPOINT_CLIENT_SECRET", "", List.of(), "pages",
            "https://graph.microsoft.com/v1.0", false, 25, true);

    public SharePointOptions {
        tenantId = tenantId == null ? "" : tenantId.strip();
        clientId = clientId == null ? "" : clientId.strip();
        clientSecretEnv = clientSecretEnv == null ? "" : clientSecretEnv.strip();
        siteId = siteId == null ? "" : siteId.strip();
        listIds = listIds == null ? List.of() : List.copyOf(listIds);
        contentMode = contentMode == null || contentMode.isBlank() ? "pages" : contentMode.strip().toLowerCase();
        graphBaseUrl = graphBaseUrl == null || graphBaseUrl.isBlank()
                ? "https://graph.microsoft.com/v1.0"
                : trimSlash(graphBaseUrl.strip());
        pageSize = pageSize <= 0 ? 25 : Math.min(pageSize, 100);
    }

    public boolean pagesEnabled() {
        return "pages".equals(contentMode) || "both".equals(contentMode) || "all".equals(contentMode);
    }

    public boolean listItemsEnabled() {
        return "listitems".equals(contentMode) || "list-items".equals(contentMode)
                || "both".equals(contentMode) || "all".equals(contentMode);
    }

    /** True when Azure AD client-credentials fields are present (secret stays in an env var). */
    public boolean usesClientCredentials() {
        return !tenantId.isEmpty() && !clientId.isEmpty() && !clientSecretEnv.isEmpty();
    }

    /** Opaque fragment folded into the sync checkpoint scope (site + lists + mode). */
    public String scopeFragment() {
        String listsKey = String.join(",", listIds.stream().map(s -> s.toLowerCase()).sorted().toList());
        return "site=" + siteId.toLowerCase() + "|lists=" + listsKey + "|mode=" + contentMode;
    }

    /**
     * A SharePoint site URL (or explicit site id) bounds collection. List-item mode additionally
     * needs at least one list id unless a time window is used.
     */
    public boolean hasBoundedScope() {
        return !siteId.isEmpty() || pagesEnabled();
    }

    public boolean hasBoundedListScope() {
        return !listIds.isEmpty();
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
