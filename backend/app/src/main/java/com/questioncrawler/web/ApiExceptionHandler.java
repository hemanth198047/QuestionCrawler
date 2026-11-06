package com.questioncrawler.web;

import com.questioncrawler.core.storage.StoreException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns core exceptions into RFC 7807 problem responses with actionable messages. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** The search library (Lucene) cannot start because of a conflicting jar on the classpath: say which one and how to remove it. */
    @ExceptionHandler({java.util.ServiceConfigurationError.class, LinkageError.class})
    public ProblemDetail searchLibrary(Error e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                com.questioncrawler.core.search.LuceneDoctor.isLuceneProblem(e) ? com.questioncrawler.core.search.LuceneDoctor.explain(e)
                        : "A required library could not be loaded: " + e);
    }

    /** Missing store, schema problems, running CLI sync, corrupt files: the message says what to do. */
    @ExceptionHandler(StoreException.class)
    public ProblemDetail store(StoreException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
