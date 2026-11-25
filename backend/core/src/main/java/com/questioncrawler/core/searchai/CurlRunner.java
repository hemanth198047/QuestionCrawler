package com.questioncrawler.core.searchai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the curl executable. An interface so tests can answer without a network or a Kerberos ticket. */
public interface CurlRunner {

    /** @param exitCode curl's exit code; stdout is the response body followed by the status marker; stderr is curl's message */
    record Result(int exitCode, String stdout, String stderr) {}

    Result run(List<String> command, int timeoutSeconds);

    /** Starts curl directly (no shell, so a query can never be interpreted as a command). */
    final class Process implements CurlRunner {
        @Override
        public Result run(List<String> command, int timeoutSeconds) {
            try {
                java.lang.Process process = new ProcessBuilder(new ArrayList<>(command)).start();
                process.getOutputStream().close();
                // stderr is read on its own thread so a full pipe can never block curl.
                StringBuilder err = new StringBuilder();
                Thread errReader = new Thread(() -> {
                    try {
                        err.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // process ended
                    }
                }, "searchai-curl-stderr");
                errReader.setDaemon(true);
                errReader.start();
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(timeoutSeconds + 10L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new SearchAiException("curl did not finish within " + timeoutSeconds + " seconds.");
                }
                errReader.join(2000);
                return new Result(process.exitValue(), out, err.toString());
            } catch (IOException e) {
                throw new SearchAiException("Could not start '" + command.get(0) + "': " + e.getMessage()
                        + ". Install curl (with Kerberos/GSS-API support) or set searchai.curl to its full path.", 0, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchAiException("Interrupted while waiting for curl.", 0, e);
            }
        }
    }
}
