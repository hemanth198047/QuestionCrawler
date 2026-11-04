package com.questioncrawler.core.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal argument parser: positional words plus --flag [value] options (repeatable). */
final class Args {

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static final Set<String> BOOLEAN = Set.of("--full", "--json", "--partial-tags", "--connect", "--force", "--keep", "--yes", "--help", "-h");
    private static final Set<String> VALUE = Set.of("--config", "--storage", "--tag", "--tag-mode", "--category", "--top", "--max-chars",
            "--max-passage-chars", "--format", "--out", "--note",
            "--max-question-age", "--min-question-age", "--max-answer-age", "--min-answer-age");

    final List<String> positional = new ArrayList<>();
    private final Map<String, List<String>> values = new HashMap<>();
    private final Set<String> flags = new java.util.HashSet<>();

    static Args parse(String[] args, int from) {
        Args a = new Args();
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (BOOLEAN.contains(arg)) {
                a.flags.add(arg);
            } else if (VALUE.contains(arg)) {
                if (i + 1 >= args.length) {
                    throw new UsageException("Option " + arg + " needs a value");
                }
                a.values.computeIfAbsent(arg, k -> new ArrayList<>()).add(args[++i]);
            } else if (arg.startsWith("--")) {
                throw new UsageException("Unknown option: " + arg);
            } else {
                a.positional.add(arg);
            }
        }
        return a;
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    String value(String name) {
        List<String> v = values.get(name);
        return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
    }

    List<String> all(String name) {
        return values.getOrDefault(name, List.of());
    }

    int intValue(String name, int fallback) {
        String v = value(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new UsageException("Option " + name + " must be a number: " + v);
        }
    }

    boolean json() {
        return flag("--json") || "json".equalsIgnoreCase(value("--format"));
    }
}
