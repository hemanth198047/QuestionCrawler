package com.questioncrawler.core.source;

public enum TagMode {
    /** A question matches if it has at least one of the target tags. */
    ANY,
    /** A question matches only if it has every target tag. */
    ALL
}
