package com.questioncrawler.core.sync;

/**
 * Asked every {@link #EVERY} questions and answers collected in one run: whether to go on. There is no other limit on a
 * collection; this only lets a person decide, at each 100,000, whether to continue.
 */
@FunctionalInterface
public interface ContinueGate {

    long EVERY = 100_000;

    /** Always continue (for example the command line with --yes). */
    ContinueGate ALWAYS = collected -> true;

    /** @return true to continue collecting; false to stop (everything collected so far is kept). */
    boolean proceed(long collected) throws InterruptedException;
}
