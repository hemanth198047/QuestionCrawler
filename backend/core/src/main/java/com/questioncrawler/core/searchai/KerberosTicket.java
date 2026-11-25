package com.questioncrawler.core.searchai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether this computer holds a valid Kerberos ticket (Linux/macOS: {@code klist -s}). Windows uses SSPI with the Windows
 * sign-in, so there is nothing to check. Only reads state; it never asks for or handles a password.
 *
 * @param state     valid | missing | unknown (klist not installed) | not-needed (Windows)
 * @param principal e.g. {@code user@REALM} when known
 */
public record KerberosTicket(String state, String principal) {

    private static final Pattern PRINCIPAL = Pattern.compile("(?:Default principal|Principal):\\s*(\\S+)");

    public static KerberosTicket probe() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new KerberosTicket("not-needed", null);
        }
        return probe(args -> {
            try {
                Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new String[] {"-1", ""};
                }
                return new String[] {String.valueOf(p.exitValue()), out};
            } catch (IOException e) {
                return null; // klist is not installed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
    }

    /** Test seam: the runner returns {exitCode, output}, or null when the program does not exist. */
    public static KerberosTicket probe(java.util.function.Function<List<String>, String[]> run) {
        String[] quick = run.apply(List.of("klist", "-s"));
        if (quick == null || "-1".equals(quick[0])) {
            return new KerberosTicket("unknown", null);
        }
        if (!"0".equals(quick[0])) {
            return new KerberosTicket("missing", null);
        }
        String[] detail = run.apply(List.of("klist"));
        Matcher m = detail == null ? null : PRINCIPAL.matcher(detail[1]);
        return new KerberosTicket("valid", m != null && m.find() ? m.group(1) : null);
    }
}
