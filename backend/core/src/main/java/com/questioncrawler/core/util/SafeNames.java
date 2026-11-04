package com.questioncrawler.core.util;

import java.util.regex.Pattern;

/**
 * Turns externally supplied identifiers into file-system-safe names. Identifiers come from a remote
 * system and must never be able to escape the storage root (path traversal).
 */
public final class SafeNames {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,100}$");

    private SafeNames() {}

    public static String fileName(String id) {
        if (id != null && SAFE.matcher(id).matches() && !id.contains("..")) {
            return id;
        }
        return "x-" + Hashes.sha256(String.valueOf(id)).substring(0, 24);
    }
}
