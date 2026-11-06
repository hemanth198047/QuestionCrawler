package com.questioncrawler.core.source;

import com.questioncrawler.core.model.Answer;
import com.questioncrawler.core.model.Question;

/**
 * Boundary to a remote Q&A system. Implementations only talk to the source; they do not store,
 * classify, normalize or retry (the sync layer owns retry/backoff).
 *
 * <p>Questions and answers are paginated independently because real systems paginate them separately.
 * Bodies are returned raw with their {@code bodyFormat}; the normalizer converts them.
 */
public interface SourceAdapter {

    /** Stable identifier of the source system, used in storage paths and keys. */
    String systemId();

    /** Things the adapter noticed that the person should know (for example answers it received but could not read). */
    default java.util.List<String> notes() {
        return java.util.List.of();
    }

    /** Whether listQuestions can honour a modifiedSince lower bound server-side. */
    boolean supportsModifiedSince();

    /**
     * Lists questions matching the filter, one page at a time.
     *
     * @param modifiedSince ISO timestamp lower bound, or null; ignored when not supported
     * @param cursor opaque cursor from the previous page, or null for the first page
     */
    Page<QuestionSummary> listQuestions(TagFilter filter, String modifiedSince, String cursor) throws SourceException;

    /** Fetches question metadata and body. The returned question has NO answers; use listAnswers. */
    Question fetchQuestion(String id) throws SourceException;

    /** Lists the answers of one question (accepted and unaccepted), one page at a time. */
    Page<Answer> listAnswers(String questionId, String cursor) throws SourceException;

    /** Cheap reachability check used by the doctor command. Returns a human-readable description. */
    String ping() throws SourceException;
}
