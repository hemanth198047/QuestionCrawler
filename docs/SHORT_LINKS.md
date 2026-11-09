# Short links (go/xyz, bit.ly, aka.ms, ...)

There are two separate features:

- **The Links source** (a source of its own, like Confluence): it expands short links and **reads the pages they lead to**,
  signing in through a browser window when a site needs your company login.
- **Expanding short links** in settings and in collected content. This rewrites short links to their real addresses but does not
  read the pages behind them.

## The Links source: read the pages behind short links

*Connect → Source → **Links (short links and web pages)***.

| Setting | What to put |
|---|---|
| *Links to read* (`source.links.list`) | Short links or page addresses, for example `go/vpn`, `https://go.mycompany.com/onboarding`, `https://bit.ly/abc` or `https://intranet.mycompany.com/hr/leave` |
| *Also read short links found in what you collected* (`source.links.fromCollected`, on by default) | Also reads the pages behind every short link written in the pages, questions and answers already collected from your other sources. Collect those first |
| *Sign-in method* (`source.auth.method`) | **Open a browser window when a page needs a sign-in** (default), or **No sign-in** to skip those pages |

**Reading a page and the pages it links to.** Put a page address in *Pages to read* (for example a team's documentation start
page). With *Also read the pages they link to* on (the default), the app reads that page and then the pages it links to, like
clicking through the site:

| Setting | Default | Meaning |
|---|---|---|
| *How many clicks deep* (`source.links.depth`) | 1 | 1 = the page and the pages it links to; 2 = also the pages those link to |
| *Follow links to* (`source.links.scope`) | The same site only | or *The start page's section only* (same folder and below), or *Any site* |
| *At most this many pages* (`source.links.maxPages`) | 500 | safety limit per collection; what was left is reported |

- Links that sign out, edit, delete, show history or download files are never followed, and PDFs, images and documents are
  skipped, so nothing on the site is changed.
- Each page is downloaded once per run, and a page reached through several links is stored once.
- The pages are stored like wiki pages: search and the quoted answers on Explore use them.

**About signing in: one browser window, one sign-in.** No tokens, keys or settings are needed:
- Every page is first read directly. That is fast, and works for pages that open without a login.
- A page that sends you to the company login (for example SiteMinder `loginidp…/SmMakeCookie`), or that only your browser can
  reach, is opened in **one** Edge/Chrome window.
  - The window is kept minimised. It comes to the front only when the company login wants you to sign in.
  - You sign in once, and every other site behind the same company login opens without asking again.
  - The window closes by itself a few minutes after the last page.
  - It uses the app's own browser profile, so your login is remembered for next time.
- If you close the window, pages that need it are skipped until the next collection. No new windows keep popping up.
- Internal sites that the company web proxy cannot reach (the proxy answers 502/503/504) are tried directly, the way your browser
  reaches them.
- A page the app cannot open is skipped with the reason, and the rest continues.
- *Sign-in method: Don't use the browser* reads only pages that open without any login.
- *Check access* tries only the first 3 pages you listed. It never follows links and never uses links found in collected items.
- *Also read short links found in what you collected* is now **off** by default (Advanced settings). It can add many pages from
  other sites.

The app cannot read a page that you cannot open in your own browser.

```properties
source.type=links
source.links.list=https://intranet.mycompany.com/team/docs
source.links.followLinks=true
source.links.depth=1
source.links.scope=site
source.links.maxPages=500
```

**Plain http and names without http/https.** You can type a link the way people say it:

| You type | Read as |
|---|---|
| `casc-docs` or `casc-docs/` | `http://casc-docs/`, or `https://casc-docs/` if http does not answer |
| `casc-docs/guides/vpn` | `http://casc-docs/guides/vpn` (https if http does not answer) |
| `wiki.mycompany.com/hr` | `http://wiki.mycompany.com/hr`, then wherever the site redirects (often to https) |
| `http://casc-docs/x` | as typed: plain http is allowed |

- Redirects are followed between http and https in either direction.
- When collecting, a one-word intranet address written with `http://` (such as `http://casc-docs/x`) counts as a short link.
  Without `http://`, a name in the text (`casc-docs/`) counts only after you add it under *Your short-link services*.
- In the Connect address fields, an address typed without `http://` or `https://` is completed when you save or run
  Check access. https is used when the site only answers on https, or when it immediately redirects http to https.

What happens for each link:

1. The short link is expanded: every redirect, short link after short link, up to the real page.
2. The page is read.
   - **If the site sends you to its login** (SSO, SAML, Okta, Entra ID, `/login`, ...), your company login opens in Edge/Chrome,
     exactly as for Confluence. You sign in once for that site, the window closes by itself, and reading continues. The other
     links on that site use the same sign-in.
   - Sites that sign you in with your Windows login are read through the browser window itself, which is kept minimised.
   - If you close the window without signing in, that site's pages are skipped for the rest of the run and reported. The other
     sites continue.
3. The page's main content (`<main>`/`<article>`, without menus) is kept as a **page**. It records the real address, the link you
   gave, and the tags `link`, `site:<host>` and `short-link`. It can be searched, organised and quoted like a Confluence page.

**Check access** reads the first 5 links now (opening the sign-in window if needed) and shows, for each one, where it led and the
page title, or why it failed. Every run reads all the links again, so pages stay current. Only web pages are read; PDFs, images and
downloads are skipped with a note.

```properties
source.type=links
source.links.list=go/vpn,https://bit.ly/abc,https://intranet.mycompany.com/hr/leave
source.links.fromCollected=true
source.auth.method=browser
# optional, as for Confluence: source.browser.path, source.browser.profileDir, source.browser.timeoutSeconds
```

**Safety**
- Only GET requests are made.
- Cookies from a browser sign-in are sent only to the site they belong to.
- The short-link service gets no cookie, password or token.
- Nothing is ever written to any site.

## Expanding short links (settings and collected content)

QuestionCrawler expands short links in two places. Both are on by default (*Connect → Company network → Expand short links*).

## 1. Short links pasted into the settings

You can paste a short link wherever the Connect screen asks for an address. For example, paste `go/dgf-wiki` as the Confluence
*Site address*, or `https://go.mycompany.com/qa-api` as the internal site's API address.

- **Check access** looks the link up, checks the real address, and puts that address into the form. Save to keep it.
- **Save** stores the real address. A Confluence page or space address it leads to is handled as usual, so the site is kept and
  the space is chosen. A note under the field says where the link led.

## 2. Short links inside collected pages, questions and answers

While collecting, every short link in the text, answers and comments is looked up once:

- `go/vpn` becomes `[go/vpn](https://wiki.mycompany.com/display/IT/VPN+setup)`. The short name stays visible, the link opens the
  real page, and search finds the item by its real address too.
- `[the guide](go/vpn)` now points at the real address.
- Code blocks and inline code are left alone. A link that cannot be looked up stays exactly as it was.
- What was found is remembered (`state/short-links.json` in the data folder), so a link is looked up once, not on every run.
  Items already collected get expanded links the next time they change, or after a full collection.

The pages that short links lead to are **not** collected. Only the sources and spaces you chose are collected.

## Which links count as short links

- These are recognised without any setting: `go/…`, `goto/…`, `golinks/…`, any `go.<company>` or `goto.<company>` host, and
  `bit.ly`, `tinyurl.com`, `aka.ms`, `t.co`, `ow.ly`, `is.gd`, `buff.ly`, `rebrand.ly`, `tiny.cc`, `lnkd.in`, `goo.gl`, `t.ly`,
  `cutt.ly`, `shorturl.at`, `rb.gy`, `short.io` and `bl.ink`.
- To add your own service, list its host in *Your short-link services* (`network.shortLinkHosts`), for example `s.mycompany.com,link`.
  A host without dots, such as `link`, is also recognised when written as `link/xyz` without `http://`.

```properties
network.expandShortLinks=true
network.shortLinkHosts=s.mycompany.com,link
```

## Safety

- The app only asks the short-link service where a link leads, and reads at most 64 KB of its answer. Redirects are read one at
  a time and are not followed automatically.
- **No sign-in, token, key or cookie is sent** to the short-link service, and the real destination is never requested.
- If the service sends you to a sign-in page, the link is left as it is.
- Company short links (`go/…`) are reached directly on the intranet. Public ones go through the web proxy from the Company
  network settings, with your company certificate if one is set.
- If a service cannot be reached, it is not asked again in the same run, so collecting is not slowed down.
