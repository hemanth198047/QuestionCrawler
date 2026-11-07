# Confluence: sign in with my browser (company login page / SSO)

Use this when calling Confluence from the app ends in **HTTP 302 to another site for login** (your company SSO:
Azure AD / Entra ID, Okta, ADFS, PingFederate, a gateway…), and after logging in in a browser you are sent back to
Confluence. Nothing has to be changed by administrators: the app uses the same login you use every day.

## How it works

1. The first time the app needs Confluence (Check access, Show my spaces, Collect), it opens **Edge or Chrome** at your
   Confluence site, in a window of its own.
2. Confluence redirects that window to your company login page. **You sign in there as usual** (password, MFA, Windows
   sign-in…). The app never sees or types your password.
3. Confluence sends the window back. The app notices that Confluence now accepts the session, takes that session's
   cookies **for the Confluence host only**, closes the window and continues. Read-only: nothing is created or changed.
4. When the session expires (Confluence redirects to the login page again, or answers 401), the window opens again,
   you sign in, and the collection carries on where it was.

The session cookies are kept in memory only. The browser uses its own profile folder
(`~/.questioncrawler/browser-profile`), so your company login can remember you: often the window opens and closes by
itself without asking anything. Delete that folder to forget the login.

## Settings

In the app: **Connect → Confluence (sign in with my browser)**, enter the site address, press **Sign in with my
browser**, then **Show my spaces** and tick the spaces. Or in `questioncrawler.properties`:

```properties
source.type=confluence
source.baseUrl=https://xxxx.atlassian.net
source.confluence.edition=cloud
source.confluence.apiVersion=v2
source.auth.method=browser
source.confluence.spaces=ENG,OPS
source.allowedPathPrefixes=/wiki/api/v2/
source.followRedirects=same-host
sync.tags=all
sync.maxQuestionAge=1y
# network.proxyHost / network.proxyPort if the app's own API calls must go through the web proxy

# Optional
# source.confluence.browser.path=C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe   # default: Edge or Chrome, found automatically
# source.confluence.browser.profileDir=                                                          # default: ~/.questioncrawler/browser-profile
# source.confluence.browser.loginUrl=                                                            # default: the Confluence site
# source.confluence.browser.timeoutSeconds=300                                                   # how long to wait for you to sign in
```

| Setting | What to put | Where to get it |
|---|---|---|
| `source.baseUrl` | `https://<site>.atlassian.net` (Server/DC: the wiki address) | The start of any Confluence page address. Pasting a whole page or space address also works: the site is taken from it, and its space is added to the spaces to collect |
| `source.confluence.apiVersion` | `v2` for Confluence Cloud, `v1` for Server/Data Center | Cloud addresses end in `atlassian.net` |
| `source.confluence.spaces` | Space keys (or pasted space addresses) | **Show my spaces** after signing in, or `questioncrawler spaces` |
| `network.proxyHost` / `Port` | Your web proxy, only if needed | The `--proxy` value you use with curl |

No secret is stored anywhere.

## Command line

`questioncrawler doctor --connect`, `questioncrawler spaces` and `questioncrawler sync` open the same browser window when a
sign-in is needed. This needs a desktop session (it does not work on a server without a screen; use a Token Vending
Machine or a token there).

## When something fails

| Message | Fix |
|---|---|
| `no Edge or Chrome was found` | Install one, or set `source.confluence.browser.path` |
| `the browser stopped right away` | Another window already uses the app's profile: close it, or set `source.confluence.browser.profileDir` |
| `The sign-in window was closed before Confluence accepted the login` | Open it again and finish signing in |
| `Nobody finished signing in within 5 minutes` | Sign in faster, or raise `source.confluence.browser.timeoutSeconds` |
| `Confluence sent the request to a sign-in page (...) even after signing in again` | The account you signed in with cannot read this site |
| Any other sign-in method: `Confluence sent the request to a sign-in page (login.example.com)` | Switch to `source.auth.method=browser` |

Use it only with your own account, the way your company already lets you open Confluence in a browser.
