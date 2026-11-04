package com.questioncrawler.core.source;

/** Listing entry: just enough to decide whether the full question must be fetched. */
public record QuestionSummary(String id, String modifiedAt, String url) {}
