package com.questioncrawler.core.source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains what an unexpected answer from a web API is: a sign-in page, a redirect, the documentation page, a proxy block page,
 * an empty answer, or broken JSON. Secrets (keys, tokens) are always removed from anything shown.
 */
public final class ResponseInspector {

    /** kind: JSON, EMPTY, REDIRECT, HTML_LOGIN, HTML_DOCS, HTML_BLOCKED, HTML_NOTFOUND, HTML_OTHER, XML, TEXT, BROKEN_JSON. */
    public record Inspection(String kind, String summary, String preview, List<String> hints) {}

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern LOGIN = Pattern.compile("(?i)sign[ -]?in|log[ -]?in|single sign|\\bsso\\b|saml|oauth|okta|adfs|azure ad|authenticat|enter your password|credentials");
    private static final Pattern DOCS = Pattern.compile("(?i)swagger[- ]?ui|redoc|openapi|api documentation");
    private static final Pattern BLOCKED = Pattern.compile("(?i)access denied|blocked|not allowed|policy|firewall|websense|zscaler|bluecoat|forcepoint|web filter|category|proxy");
    private static final Pattern NOT_FOUND = Pattern.compile("(?i)\\b404\\b|not found|no such page|page.{0,12}exist");
    private static final int PREVIEW_CHARS = 700;

    private ResponseInspector() {}

    /** Removes a byte-order mark and common "anti-hijacking" prefixes that make otherwise valid JSON unreadable. */
    public static String stripNoise(String body) {
        if (body == null) {
            return "";
        }
        String s = body;
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        s = s.stripLeading();
        for (String prefix : new String[] {")]}',", ")]}'", "while(1);", "for(;;);"}) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length()).stripLeading();
                break;
            }
        }
        return s;
    }

    public static String redact(String text, Collection<String> secrets) {
        String out = text == null ? "" : text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                out = out.replace(secret, "•••");
                try {
                    out = out.replace(java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8), "•••");
                } catch (RuntimeException ignored) {
                    // not encodable: nothing more to hide
                }
            }
        }
        return out;
    }

    /** Address without its query string (which may hold a key) — for messages. */
    public static String withoutQuery(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    public static Inspection inspect(int status, String contentType, String location, String rawBody, Collection<String> secrets) {
        String body = stripNoise(rawBody);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String preview = redact(body.length() > PREVIEW_CHARS ? body.substring(0, PREVIEW_CHARS) + " …" : body, secrets);
        String where = "HTTP " + status + (type.isBlank() ? "" : ", " + type.split(";")[0].strip()) + ", " + (rawBody == null ? 0 : rawBody.length()) + " characters";
        List<String> hints = new ArrayList<>();

        if (status >= 300 && status < 400) {
            String target = location == null ? "an unknown address" : redact(withoutQuery(location), secrets);
            boolean login = location != null && LOGIN.matcher(location).find();
            hints.add(login ? "That address looks like a sign-in page: the API wants credentials that were not accepted or not sent."
                    : "Redirects are not followed automatically, for safety. The API address may be wrong (for example http instead of https, or a missing /api path).");
            hints.add("Check that the API access key variable is set in the terminal that started the app, and the key parameter name matches the documentation.");
            return new Inspection("REDIRECT", "The site redirected the request (" + where + ") to " + target + ".", preview, hints);
        }
        if (body.isBlank()) {
            hints.add("The API returned nothing. A wrong address, a gateway that swallows the request, or a proxy that blocked it are the usual reasons.");
            return new Inspection("EMPTY", "The answer was empty (" + where + ").", "", hints);
        }
        boolean html = type.contains("html") || body.stripLeading().toLowerCase(Locale.ROOT).startsWith("<!doctype html") || body.stripLeading().toLowerCase(Locale.ROOT).startsWith("<html");
        if (html) {
            Matcher t = TITLE.matcher(body);
            String title = t.find() ? t.group(1).replaceAll("\\s+", " ").strip() : "";
            String shown = title.isEmpty() ? "" : " titled “" + redact(title.length() > 80 ? title.substring(0, 80) + "…" : title, secrets) + "”";
            String haystack = title + " " + body.substring(0, Math.min(body.length(), 4000));
            if (DOCS.matcher(haystack).find()) {
                hints.add("This address is the documentation page, not the API. The API address is usually the documentation's address with something like /api/2.3 (see the 'servers' entry of the Swagger document).");
                return new Inspection("HTML_DOCS", "The answer is a web page" + shown + " (" + where + "): it looks like the API documentation.", preview, hints);
            }
            if (LOGIN.matcher(haystack).find()) {
                hints.add("The API address returned a sign-in page instead of data. Either the API access key was not sent or not accepted, or the address points at the website instead of the API.");
                hints.add("Check: the key variable is set in the terminal that started the app; the key parameter name and where it is sent (query or header) match the documentation; the API address ends with the API path.");
                hints.add("If the whole site, including the API, is protected by company single sign-on, ask the site owners for a token or an API gateway address that accepts the key.");
                return new Inspection("HTML_LOGIN", "The answer is a sign-in page" + shown + " (" + where + ").", preview, hints);
            }
            if (BLOCKED.matcher(haystack).find() && !NOT_FOUND.matcher(title).find()) {
                hints.add("This looks like a page from a web proxy or firewall that blocked the request. Check the proxy settings, or ask IT to allow this address.");
                return new Inspection("HTML_BLOCKED", "The answer is a web page" + shown + " (" + where + "): it looks like a proxy or firewall block page.", preview, hints);
            }
            if (status == 404 || NOT_FOUND.matcher(title).find()) {
                hints.add("The address does not exist on the site. Check the API address (the path in front of /questions) against the documentation.");
                return new Inspection("HTML_NOTFOUND", "The answer is a 'not found' web page" + shown + " (" + where + ").", preview, hints);
            }
            hints.add("The API address returned a web page, not data. It probably points at the website instead of the API: compare it with the 'servers' address in the documentation.");
            return new Inspection("HTML_OTHER", "The answer is a web page" + shown + " (" + where + ").", preview, hints);
        }
        String first = body.substring(0, 1);
        if (first.equals("{") || first.equals("[")) {
            hints.add("It starts like JSON but cannot be read: it may be cut off (a timeout or a proxy size limit) or contain extra text. Try again; if it repeats, send me the raw answer.");
            return new Inspection("BROKEN_JSON", "The answer looks like JSON but is damaged (" + where + ").", preview, hints);
        }
        if (first.equals("<")) {
            hints.add("The API answered with XML. This app needs JSON: check the 'Accept' behaviour in the documentation, or the address.");
            return new Inspection("XML", "The answer is XML (" + where + ").", preview, hints);
        }
        hints.add("The answer is plain text, not JSON. The text above is what the site said; it often explains the problem.");
        return new Inspection("TEXT", "The answer is plain text (" + where + ").", preview, hints);
    }
}
