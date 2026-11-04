package com.questioncrawler.core.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Entry point: {@code questioncrawler <command> [options]}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(new Cli(out, err).run(args));
    }
}
