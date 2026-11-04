package com.questioncrawler.core.util;

import java.time.Duration;

/** Injectable sleep so retry/backoff logic is testable without real waiting. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = duration -> Thread.sleep(duration.toMillis());
}
