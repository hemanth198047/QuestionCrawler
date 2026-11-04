package com.questioncrawler.core.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** One page of results. nextCursor == null means this was the last page. */
public record Page<T>(List<T> items, String nextCursor) {
    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @JsonIgnore
    public boolean isLast() {
        return nextCursor == null;
    }
}
