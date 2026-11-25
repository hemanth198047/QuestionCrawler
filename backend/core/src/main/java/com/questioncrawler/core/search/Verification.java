package com.questioncrawler.core.search;

/** A person's confirmation that a result is correct: who, when (ISO time) and an optional short note (for example what worked). */
public record Verification(String by, String at, String note) {}
