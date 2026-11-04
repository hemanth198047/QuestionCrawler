package com.questioncrawler.core.model;

import java.util.List;

public record Answer(
        String id,
        String body,
        BodyFormat bodyFormat,
        String url,
        boolean accepted,
        Integer score,
        String author,
        String createdAt,
        String modifiedAt,
        List<Comment> comments) {

    public Answer {
        bodyFormat = bodyFormat == null ? BodyFormat.MARKDOWN : bodyFormat;
        body = body == null ? "" : body;
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    public Answer withBody(String newBody, BodyFormat format, List<Comment> newComments) {
        return new Answer(id, newBody, format, url, accepted, score, author, createdAt, modifiedAt, newComments);
    }
}
