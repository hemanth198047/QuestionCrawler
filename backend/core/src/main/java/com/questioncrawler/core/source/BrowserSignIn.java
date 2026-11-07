package com.questioncrawler.core.source;

/** A source that can sign in through the person's browser (company login page) and reuse that session. */
public interface BrowserSignIn {

    /** Opens the browser sign-in now if there is no session yet; returns a status line. */
    String signIn() throws SourceException;

    /** The current session's Cookie header (never shown), or null. */
    String sessionCookie();
}
