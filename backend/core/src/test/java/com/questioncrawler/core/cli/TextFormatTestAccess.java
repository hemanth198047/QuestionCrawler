package com.questioncrawler.core.cli;

/** Test-only bridge to the package-private text formatter. */
public final class TextFormatTestAccess {
    private TextFormatTestAccess() {}

    public static String wrap(String content) {
        return TextFormat.wrapUntrusted(content, "");
    }
}
