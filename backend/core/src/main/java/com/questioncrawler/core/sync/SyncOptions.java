package com.questioncrawler.core.sync;

import com.questioncrawler.core.source.TagMode;
import java.time.Duration;
import java.util.List;

public record SyncOptions(
        List<String> tags,
        TagMode tagMode,
        boolean partialTags,
        SyncMode mode,
        Duration overlap,
        int concurrency,
        int retentionDays,
        String maxQuestionAge) {

    public SyncOptions {
        tags = tags == null ? List.of() : List.copyOf(tags);
        tagMode = tagMode == null ? TagMode.ANY : tagMode;
        mode = mode == null ? SyncMode.INCREMENTAL : mode;
        overlap = overlap == null ? Duration.ofMinutes(60) : overlap;
        concurrency = Math.max(1, Math.min(concurrency, 16));
        retentionDays = Math.max(0, retentionDays);
        maxQuestionAge = maxQuestionAge == null || maxQuestionAge.isBlank() ? null : maxQuestionAge.trim();
        if (maxQuestionAge != null && !com.questioncrawler.core.util.AgeSpec.isValid(maxQuestionAge)) {
            // throws with a clear message
            com.questioncrawler.core.util.AgeSpec.before(maxQuestionAge, java.time.Instant.now());
        }
    }

    /** Without a collection-age window. */
    public SyncOptions(List<String> tags, TagMode tagMode, boolean partialTags, SyncMode mode, Duration overlap,
                       int concurrency, int retentionDays) {
        this(tags, tagMode, partialTags, mode, overlap, concurrency, retentionDays, null);
    }
}
