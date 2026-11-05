package com.questioncrawler.core.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Entry point: {@code questioncrawler <command> [options]}. */
public final class Main {

    private Main() {}

    // Strong reference so the logger configuration is not garbage collected.
    private static final java.util.logging.Logger LUCENE_VECTOR_LOG =
            java.util.logging.Logger.getLogger("org.apache.lucene.internal.vectorization");

    public static void main(String[] args) {
        // Lucene logs a harmless "vector incubator module" notice on every start; the vector API is not used here.
        LUCENE_VECTOR_LOG.setLevel(java.util.logging.Level.SEVERE);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(new Cli(out, err).run(args));
    }
}
