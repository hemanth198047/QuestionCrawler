# Confluence with company sign-in (Kerberos + Token Vending Machine, REST API v2)

This is the setup for organizations where Confluence Cloud is read with a **short-lived Bearer token** issued by an
internal **Token Vending Machine (TVM)** that signs you in with **Kerberos/SPNEGO**, and the Confluence Cloud
**REST API v2** is reached through the **company web proxy**. QuestionCrawler only **reads** pages; it never creates or
updates anything.

It automates exactly these two documented calls:

```bash
# 1. Get a token (Kerberos sign-in, no password typed)
curl -u : --negotiate https://atlassian-tvm.mycompany.com/exchange/token/confluence/acme.atlassian.net
#    -> {"access_token":"...","token_type":"Bearer","expires_in":900}

# 2. Read pages with it, through the proxy
curl --proxy http://webproxy.mycompany.com:8080 \
     --header "Authorization: Bearer <access_token>" --header "Accept: application/json" \
     "https://acme.atlassian.net/wiki/api/v2/pages?body-format=storage"
```

The token lives about 15 minutes. QuestionCrawler keeps it **in memory only** (never on disk, in settings or in logs),
renews it 60 seconds before it expires, and renews it immediately if Confluence answers 401.

## Settings

In the app: **Connect a source → Confluence (company sign-in)**, then fill in the blanks. Or in `questioncrawler.properties`:

```properties
source.type=confluence
source.baseUrl=https://acme.atlassian.net
source.confluence.edition=cloud
source.confluence.apiVersion=v2

# Sign-in: Kerberos + Token Vending Machine
source.auth.method=tvm
source.confluence.tvm.url=https://atlassian-tvm.mycompany.com/exchange/token/confluence/acme.atlassian.net
source.confluence.tvm.client=curl
# source.confluence.tvm.curlPath=curl          # only if curl is not on the PATH
# source.confluence.tvm.useProxy=false         # the TVM is internal: called WITHOUT the proxy
# source.confluence.tvm.caCertFile=            # company CA for curl, only if needed

# What to read (pick at least one; spaces are the usual choice)
source.confluence.spaces=ENG
# source.confluence.pageIds=123456789,987654321
# source.confluence.ancestorPageId=123456789   # this page's whole subtree

# Web proxy for the Confluence calls
network.proxyHost=webproxy.mycompany.com
network.proxyPort=8080

source.allowedPathPrefixes=/wiki/api/v2/
source.followRedirects=same-host
sync.tags=all
sync.maxQuestionAge=1y
```

| Setting | What to put | Where to get it |
|---|---|---|
| `source.baseUrl` | `https://<your-site>.atlassian.net` (no `/wiki`, no page path) | The start of any Confluence page address in your browser |
| `source.confluence.tvm.url` | The full TVM exchange address | Your platform/identity team's documentation: the address in the `curl -u : --negotiate ...` command. It normally ends in `/exchange/token/confluence/<your-site>.atlassian.net` |
| `network.proxyHost`, `network.proxyPort` | Proxy host name and port, **without** `https://` | The `--proxy` value in the documented curl call (`https://webproxy-inet.mycompany.com:8080` → host `webproxy-inet.mycompany.com`, port `8080`) |
| `source.confluence.spaces` | Space keys, comma-separated | The page address: `.../wiki/spaces/`**`ENG`**`/pages/...`, or *Space settings → Space details* |
| `source.confluence.pageIds` | Page ids, comma-separated | The page address: `.../pages/`**`123456789`**`/Title` (or *••• → Page information*: `pageId=` in the address) |
| `source.confluence.ancestorPageId` | One page id: collect everything under it | As above |
| `sync.tags` | `all`, or Confluence labels (for example `runbook,how-to`) | The labels at the bottom of a page |
| `source.caCertFile` | Path to the company root CA `.pem`, **only** if you get certificate errors | Your IT/security team (needed when the proxy inspects HTTPS) |

No secret goes into the settings: the TVM signs you in with the Kerberos ticket you already have from logging in.

## Before you start: what you need on the computer

1. **A Kerberos ticket.**
   - Windows (domain-joined, logged in on the company network or VPN): you already have one. Nothing to do.
   - macOS / Linux: run `kinit your.name@MYCOMPANY.COM`, then check it with `klist`.
2. **curl with SPNEGO support** (default sign-in method). Windows 10/11 have `curl.exe` built in. Check with
   `curl --version`: the *Features* line must include `SPNEGO` (or `GSS-API`/`Kerberos`).
3. **JDK 21** and **Node 20+**, as for the rest of the app.

## Check it step by step

Run the same two calls by hand first. If these work, the app will work.

```bash
curl -u : --negotiate https://atlassian-tvm.mycompany.com/exchange/token/confluence/acme.atlassian.net
```

```bash
curl --proxy http://webproxy.mycompany.com:8080 --header "Authorization: Bearer PASTE_TOKEN" --header "Accept: application/json" "https://acme.atlassian.net/wiki/api/v2/spaces?limit=1"
```

Note the second call uses `http://` for the proxy: Java talks to a proxy with a plain HTTP `CONNECT` (the
connection to Confluence is still encrypted end to end). If that call only works with `--proxy https://...`, tell
your network team; most corporate proxies accept both.

Then in the app: **Connect → Check access**. The *Sign-in* step shows `Token Vending Machine: token received from ...`
(or the exact reason it failed). From the command line: `questioncrawler doctor --connect`, then `questioncrawler sync`.

## When something fails

| Message | Meaning and fix |
|---|---|
| `The token service answered HTTP 401: Kerberos sign-in was not accepted` | No or expired Kerberos ticket. Windows: lock/unlock or reconnect to the VPN. macOS/Linux: `kinit` again, check `klist` |
| `Could not run 'curl'` | curl is not on the PATH. Set `source.confluence.tvm.curlPath` (for example `C:\Windows\System32\curl.exe`) |
| `curl could not reach the token service (exit 6/7/28)` | Name or network problem to the TVM. If the TVM must go through the proxy, set `source.confluence.tvm.useProxy=true` |
| `curl ... (exit 60)` | curl does not trust the TVM certificate: set `source.confluence.tvm.caCertFile` |
| `did not return JSON` | The TVM returned a sign-in web page instead of a token: Kerberos was not used. Check the TVM address and your ticket |
| `HTTP 407 from the web proxy` | The proxy wants a sign-in: set `network.proxyUserEnv` / `network.proxyPasswordEnv`, or ask IT for an allow-listed route |
| `HTTP 403` from Confluence | The token works but your account cannot see that space or page |
| `None of the Confluence spaces [...] were found` | Wrong space key, or no access to it |

### Without curl (`source.confluence.tvm.client=java`)

The JDK's built-in Kerberos (SPNEGO) can call the TVM directly. On Windows it uses your Windows sign-in
(`sun.security.jgss.native=true` is set for you). On macOS/Linux it reads the `kinit` ticket cache and needs
`/etc/krb5.conf` (or `-Djava.security.krb5.conf=...`). If it fails, go back to `curl`, which is the documented method.

## What is collected

- Pages via `GET /wiki/api/v2/pages` (newest changes first), filtered by space, page ids or subtree.
- Each page via `GET /wiki/api/v2/pages/{id}?body-format=storage`: `body.storage.value` (Confluence XHTML) is converted to Markdown.
- Labels become tags; `space:KEY` is added. Optional footer comments (`source.confluence.collectComments=true`).
- Quick updates only re-read pages changed since the last run (the listing is sorted by modification date).
- The author is stored as the Atlassian account id (REST API v2 does not return display names).

## Why not the Atlassian MCP tools?

The Atlassian MCP server is meant for Claude Code sessions: it uses your interactive Atlassian OAuth session and
exposes tools to the assistant. QuestionCrawler is a standalone Java service that collects on a schedule without an
assistant, so it uses the documented automation path: TVM token + REST API through the proxy.
