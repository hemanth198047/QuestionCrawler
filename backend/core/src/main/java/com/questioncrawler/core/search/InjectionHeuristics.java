package com.questioncrawler.core.search;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Flags text that looks like an attempt to instruct an AI agent. This is a heuristic warning only:
 * flagged text is still returned unchanged as DATA, and unflagged text is never "safe". The real
 * defence is that consumers (the Copilot skill) treat all retrieved content as untrusted.
 */
public final class InjectionHeuristics {

    public static final String FLAG = "possible-instruction-injection";

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts?|rules)"),
            Pattern.compile("(?i)disregard\\s+.{0,40}(instructions|rules|guidelines)"),
            Pattern.compile("(?i)(reveal|print|show|send|exfiltrate)\\s+.{0,40}(password|token|secret|credential|api[ _-]?key)"),
            Pattern.compile("(?i)(curl|wget)\\s+[^\\n|]{0,200}\\|\\s*(sudo\\s+)?(sh|bash|zsh|powershell|pwsh)"),
            Pattern.compile("(?i)powershell(\\.exe)?\\s+.{0,20}-enc"),
            Pattern.compile("(?i)you\\s+(are|must)\\s+now\\s+(a|an|in|the)\\b"),
            Pattern.compile("(?i)system\\s+prompt"),
            Pattern.compile("(?i)<\\s*/?\\s*(system|assistant|instructions?)\\s*>"),
            Pattern.compile("(?i)(assistant|copilot|ai)\\s*[:,]?\\s*(please\\s+)?(run|execute|call|invoke)\\s+(the\\s+following|this)")
    );

    private InjectionHeuristics() {}

    public static boolean suspicious(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
    }
}
