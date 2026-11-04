package com.questioncrawler.core.model;

import java.util.List;

/** A question with all of its answers. Unknown metadata is null, never invented. */
public record Question(
        String id,
        String title,
        String body,
        BodyFormat bodyFormat,
        String url,
        List<String> tags,
        String author,
        Integer score,
        String createdAt,
        String modifiedAt,
        List<Comment> comments,
        List<Answer> answers) {

    public Question {
        bodyFormat = bodyFormat == null ? BodyFormat.MARKDOWN : bodyFormat;
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        tags = tags == null ? List.of() : List.copyOf(tags);
        comments = comments == null ? List.of() : List.copyOf(comments);
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    public Question withAnswers(List<Answer> newAnswers) {
        return new Question(id, title, body, bodyFormat, url, tags, author, score, createdAt, modifiedAt, comments, newAnswers);
    }

    public Question withContent(String newBody, BodyFormat format, List<Comment> newComments, List<Answer> newAnswers) {
        return new Question(id, title, newBody, format, url, tags, author, score, createdAt, modifiedAt, newComments, newAnswers);
    }

    public boolean hasAcceptedAnswer() {
        return answers.stream().anyMatch(Answer::accepted);
    }
}
