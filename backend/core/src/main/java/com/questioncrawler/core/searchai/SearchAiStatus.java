package com.questioncrawler.core.searchai;

/**
 * What the screen needs to know in one answer: is an address set, is there a ticket, does search-ai let us in.
 * Never throws: every problem becomes a plain-language {@code message} and a {@code hint} on what to do.
 */
public record SearchAiStatus(boolean configured, String baseUri, boolean signedIn, String ticket, String principal, String message, String hint) {

    public static SearchAiStatus check(SearchAiOptions options, SearchAiClient client, KerberosTicket ticket) {
        String t = ticket.state();
        if (options.isPlaceholderAddress()) {
            return new SearchAiStatus(false, options.baseUri(), false, t, ticket.principal(),
                    "Search-AI's address is not set yet.", "Enter the address once below (it ends with /searchapi).");
        }
        try {
            client.whoami();
            return new SearchAiStatus(true, options.baseUri(), true, t, ticket.principal(), "Connected to Search-AI.", null);
        } catch (SearchAiException e) {
            return new SearchAiStatus(true, options.baseUri(), false, t, ticket.principal(), e.getMessage(), hint(e, t));
        }
    }

    private static String hint(SearchAiException e, String ticket) {
        int status = e.httpStatus();
        if (status == 401 || status == 403) {
            return switch (ticket) {
                case "missing" -> "You are not signed in with your company account on this computer. Sign in to your computer or VPN (so your Kerberos ticket is renewed), then press Check again.";
                case "not-needed" -> "Windows could not sign you in to Search-AI. Make sure you are on the company network or VPN and signed in to Windows with your company account. If it still fails, raise an IT ticket for Search-AI access.";
                default -> "Search-AI did not accept your company sign-in. Make sure you are on the company network or VPN and signed in with your company account, then press Check again.";
            };
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("Could not start")) {
            return "The program 'curl' is missing on the computer that runs QuestionCrawler. Ask IT to install curl with Kerberos support.";
        }
        if (m.contains("could not be resolved") || m.contains("Could not connect") || m.contains("timed out")) {
            return "Search-AI cannot be reached. Check that you are on the company network or VPN and that the address is right.";
        }
        if (m.contains("certificate")) {
            return "This computer does not trust the company certificate. Ask IT to set CURL_CA_BUNDLE to the company certificate bundle.";
        }
        return "Check the address and your network, then press Check again.";
    }
}
