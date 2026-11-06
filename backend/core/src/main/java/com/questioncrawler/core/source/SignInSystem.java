package com.questioncrawler.core.source;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Names the sign-in system a redirect chain ends at (Microsoft Entra ID, Okta, Keycloak, ...) from the addresses involved, and says what a
 * PROGRAM (which cannot complete a browser sign-in) usually needs from it. Only recognizes; never tries to sign in.
 */
public record SignInSystem(String name, String forPrograms, List<String> ask) {

    private static final List<String> COMMON = List.of(
            "A non-interactive way for a program to read this site's API: a personal access token or a service account (read-only is enough).",
            "The address of the API that accepts it (often a gateway or an /api address that differs from the web address people use).",
            "Whether the API also needs the API access key, and how a token is sent (usually an 'Authorization: Bearer' header).");

    public static Optional<SignInSystem> identify(String... addresses) {
        StringBuilder all = new StringBuilder();
        StringBuilder hosts = new StringBuilder();
        for (String a : addresses) {
            if (a == null || a.isBlank()) {
                continue;
            }
            all.append(' ').append(a.toLowerCase(Locale.ROOT));
            try {
                String h = URI.create(a.strip()).getHost();
                if (h != null) {
                    hosts.append(' ').append(h.toLowerCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException ignored) {
                // an unusable address contributes only its text
            }
        }
        String t = all.toString();
        String h = hosts.toString();
        if (has(h, "login.microsoftonline.com", "sts.windows.net", "login.windows.net", "login.microsoft.com") || t.contains("/oauth2/v2.0/authorize")) {
            return Optional.of(new SignInSystem("Microsoft Entra ID (Azure AD)",
                    "an app registration (service principal) for this API: a client ID and a client secret or certificate, used to obtain an access token without a browser (the client-credentials flow)",
                    with("The app registration's client ID, a client secret (or certificate), your tenant's token address and the API's scope / application ID URI.")));
        }
        if (t.contains("/adfs/")) {
            return Optional.of(new SignInSystem("Active Directory Federation Services (AD FS)",
                    "an AD FS application group with a server application: client ID and secret to obtain a token without a browser",
                    with("The AD FS application's client ID and secret, the token address and the resource / scope for this API.")));
        }
        if (has(h, "okta.com", "oktapreview.com", "okta-emea.com") || t.contains("/oauth2/default")) {
            return Optional.of(new SignInSystem("Okta",
                    "an Okta API service application (client credentials) or an API token issued for a service account",
                    with("The Okta service app's client ID and secret, the authorization server's token address and the scope for this API.")));
        }
        if (has(h, "auth0.com")) {
            return Optional.of(new SignInSystem("Auth0", "a machine-to-machine application (client credentials) authorized for this API",
                    with("The machine-to-machine application's client ID and secret, the token address and the API audience.")));
        }
        if (t.contains("/auth/realms/") || t.contains("/protocol/openid-connect")) {
            return Optional.of(new SignInSystem("Keycloak", "a service account (a client with 'service accounts enabled') using the client-credentials flow",
                    with("The Keycloak client's ID and secret, the realm's token address and the scope for this API.")));
        }
        if (has(h, "pingone.com", "pingidentity.com") || t.contains("/as/authorization.oauth2")) {
            return Optional.of(new SignInSystem("Ping Identity", "a worker application (client credentials) or a service account token", with("The application's client ID and secret and the token address.")));
        }
        if (has(h, "onelogin.com")) {
            return Optional.of(new SignInSystem("OneLogin", "an API credential (client ID and secret) created for a service", with("The API credential's client ID and secret.")));
        }
        if (has(h, "accounts.google.com")) {
            return Optional.of(new SignInSystem("Google sign-in", "a service account key", with("A service account and how it is allowed to call this API.")));
        }
        if (t.contains("/cas/login") || t.contains("/cas/v1")) {
            return Optional.of(new SignInSystem("CAS (Central Authentication Service)", "a service ticket or token arrangement for programs", COMMON));
        }
        if (t.contains("samlrequest") || t.contains("/saml") || t.contains("/sso/")) {
            return Optional.of(new SignInSystem("SAML single sign-on", "a token or service account: SAML itself is only for browsers", COMMON));
        }
        if (t.contains("response_type=") || t.contains("/authorize")) {
            return Optional.of(new SignInSystem("OpenID Connect / OAuth single sign-on", "an OAuth client (client credentials) or a personal access token", COMMON));
        }
        if (t.contains("/users/login") || t.contains("/account/login") || t.contains("/login") || t.contains("/signin") || t.contains("sign-in")) {
            return Optional.of(new SignInSystem("The site's own sign-in page", "a personal access token (API token) from your profile, or a service account", COMMON));
        }
        return Optional.empty();
    }

    private static boolean has(String hosts, String... needles) {
        for (String n : needles) {
            if (hosts.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> with(String specific) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        list.add(specific);
        list.addAll(COMMON);
        return List.copyOf(list);
    }

    /** One sentence for hints and messages. */
    public String sentence() {
        return "The sign-in system looks like " + name + ". A program cannot complete a browser sign-in: it needs " + forPrograms + ".";
    }
}
