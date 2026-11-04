package com.questioncrawler.core.model;

/** Optional supplementary context. Any field may be null (unknown). */
public record Comment(String id, String author, String body, String createdAt) {}
