# Confluence: sign in with my browser (company login page / SSO)

For **Confluence** only (the internal Q&A site / Stack Overflow uses its REST API with an access key or token). Use it when calling Confluence from the app ends in **HTTP 302 to another site for login** (your company SSO:
Azure AD / Entra ID, Okta, ADFS, PingFederate, a gateway…), and after logging in in a browser you are sent back to
Confluence. Nothing has to be changed by administrators: the app uses the same login you use every day.

## Already signed in (Windows sign-in)

Some company sites sign you in with your **Windows login** (Kerberos/NTLM) or a certificate: the window opens and the site
shows its content at once, without any login screen. Such a session often does not work outside the browser. The app then
**reads the site through that browser window**: it minimises the window (it does not close it), loads the pages inside the
browser, and closes the window when the app stops. If you close the minimised window, the next request opens a new one.
Leave the sign-in window open until it closes or minimises by itself.

## You do not have to choose it

When **Check access** finds that the site sends visitors to a sign-in page and no sign-in is set yet, the app opens the
browser sign-in by itself and selects *Sign in with my browser* in the form (save to keep it).

- The sign-in window is brought **to the front** when it opens (and again shortly after), because a window opened by the
  app can appear behind the one you are looking at. While it is open, the app shows *Finish signing in in the browser
  window* with a **Show the sign-in window** button that brings it forward again.
- As soon as the site lets you in, the window **closes by itself** and the app returns to where you were.
- If an earlier sign-in window still holds the app's browser profile, a spare profile is used so a window always opens.

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
# Shared by every source that signs in with the browser (the older source.confluence.browser.* names still work)
# source.browser.path=C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe   # default: Edge or Chrome, found automatically
# source.browser.profileDir=                                                          # default: ~/.questioncrawler/browser-profile
# source.browser.loginUrl=                                                            # default: the site itself
# source.browser.timeoutSeconds=300                                                   # how long to wait for you to sign in
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
| Check access: *The site needs your company login, but the sign-in browser could not be used: …* | The reason follows; the rows below explain each one. The `npm start` window also logs each step (`Opening the browser sign-in window …`, `Browser for sign-in: …`) |
| `no Edge or Chrome was found` | Set **Browser program** (Settings → Signing in, `source.browser.path`) to `msedge.exe` or `chrome.exe` |
| `the app runs inside WSL` | Start the app from a normal Windows terminal (PowerShell / Command Prompt): `npm start` |
| `does not let the app take over the login (… DevTools port stayed off …)` | Company policy blocks the browser's remote control (often for Edge): set **Browser program** to Chrome, or ask IT to allow it for the app's own profile |
| `the browser stopped right away` | Another window already uses the app's profile: close it, or set `source.confluence.browser.profileDir` |
| `The sign-in window was closed before Confluence accepted the login` | Open it again and finish signing in |
| `Nobody finished signing in within 5 minutes` | Sign in faster, or raise `source.confluence.browser.timeoutSeconds` |
| `Confluence sent the request to a sign-in page (...) even after signing in again` | The account you signed in with cannot read this site |
| Any other sign-in method: `Confluence sent the request to a sign-in page (login.example.com)` | Switch to `source.auth.method=browser` |

Use it only with your own account, the way your company already lets you open Confluence in a browser.
