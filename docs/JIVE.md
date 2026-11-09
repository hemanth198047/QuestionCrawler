# Jive source

QuestionCrawler can collect **Jive community content** into the same local knowledge store used for Stack Exchange, Confluence, and demo data.

`source.type=jive` is a **first-class, separate** source. It does **not** share settings with Stack Exchange, Confluence, the internal live Q&A adapter, or **SharePoint**.

## What you get

- Documents, discussions, and questions (configurable) via the **Jive Core REST API v3** (`/api/core/v3/contents`, search/filters)
- Stored under `threads/<systemId>/<contentId>.json` (default `systemId=jive`)
- Content tags stay tags; optional synthetic `place:ID` and `type:…` tags
- Discussion/question replies are fetched as answers when the messages endpoint is available; optional comments
- Incremental sync using the `after(…)` filter when the site supports it

## Without the API: read Jive's web pages by tag (no token)

When the API is not available, or you don't have a token, Jive can be read the way you read it yourself, through its
search page. Go to *Connect → Jive* and set:

- *Site address*: your Jive, for example `https://jive.xx.com`
- *Sign-in method*: **Read the web pages through my browser (no API, no token)**
- *Tags*: what to search for, for example `casc` (press Enter after each tag)

On *Collect*, the time window becomes Jive's `after=`: 1 day → `day`, 7 days → `week`, 1 month → `month`, 3 months →
`quarter`, 1 year → `year`. With *Everything*, `after` is left out.

For each tag, the app:

1. Opens `https://jive.xx.com/search.jspa?q=casc&after=year` in **one** Edge/Chrome window. It uses your usual company
   login: if the window asks, sign in once. The window stays minimised otherwise.
2. Reads the results to the **very end**, scrolling and clicking *More* / *Next* until no new results appear.
3. Takes the links of the **Content** results only: documents, discussions, messages, blog posts, ideas, polls, videos and
   questions. People, places, the search itself, tags, profile and sign-out links are never followed.
4. Reads every one of those pages, directly when possible and through the browser when needed. It keeps the page's own text
   without Jive's header, sidebar and footer, and stores it as a Jive **post**. Tags are the search tag, `jive` and
   `type:document` / `type:discussion`.

The same page found under several tags is stored once, carrying all of its tags. The search's tracking parameters
(`?sr=search&searchId=…`) are removed from the stored address.

**Check access** runs the search for the first tag now: it shows the search address, how many content pages it lists, a few
examples, and whether the first page could be read.

If your Jive's search page has another address, set *Jive search address* (advanced, `source.jive.searchUrl`). `{site}`,
`{tag}` and `{after}` are filled in:

```properties
source.type=jive
source.baseUrl=https://jive.xx.com
source.auth.method=browser
sync.tags=casc
sync.maxQuestionAge=1y
# source.jive.searchUrl={site}/search.jspa?q={tag}&after={after}
```

'all' is not possible in this mode: the search needs a word to search for. Place ids, content types and the API settings
are not used, and are hidden on the Connect screen.

## Auth (secrets stay in environment variables)

| Method | Config | Env var (name only in config) |
|---|---|---|
| Basic (username + password or API token) | `source.auth.method=basic-env`, `source.jive.username=…` | `JIVE_PASSWORD` |
| OAuth Bearer access token | `source.auth.method=token` | `JIVE_ACCESS_TOKEN` |

Never put passwords or tokens in properties files, fixtures, or logs. Cookie scraping and browser SSO automation are not supported. Federated/SSO-only user accounts often cannot use Basic auth — use a non-federated service account or an OAuth access token.

Live testing needs operator-supplied credentials against a real Jive site (`doctor --connect`, then `sync`, then `search`).

## Minimal Basic example

```properties
source.type=jive
source.baseUrl=https://community.example.com
source.jive.username=svc-questioncrawler
source.jive.places=1006
source.auth.method=basic-env
source.auth.tokenEnv=JIVE_PASSWORD
source.allowedHosts=community.example.com
source.allowedPathPrefixes=/api/core/v3/
source.followRedirects=same-host
sync.tags=runbook
sync.maxQuestionAge=1y
```

Then:

```bash
export JIVE_PASSWORD='…'   # password or API token for that username
questioncrawler doctor --connect
questioncrawler sync
questioncrawler search "your query"
```

## Scope rules

Collecting `sync.tags=all` without a **place id** or **time window** is refused — a whole community can be huge.

## Search (plain English, no LLM)

After sync (or `reindex`), every collected document/discussion/question is in the same Lucene index as other sources. Explore accepts everyday questions such as “how do I configure contractor VPN”:

- Additive English stems so related word forms meet (configure / configuring / configuration)
- A small curated synonym map (runbook↔playbook, SSO↔sign-in, howto↔guide, …)
- Chatty prefixes like “how do I…” are ignored for matching; page titles are boosted
- Optional comments are indexed when `source.jive.collectComments=true`
- Discussion/question replies (answers) are searchable when the messages endpoint returns them
- Still **local lexical** search — not an AI model and not semantic embeddings. It ranks real passages from content you collected; it does not invent answers.

```bash
questioncrawler search "how do I configure contractor VPN"
```

If search says the index is an older version, rebuild once: Settings → Where is everything saved? → **Rebuild search index**, or CLI `questioncrawler reindex` (index format version 3).

## UI

Connect and Settings expose a Jive preset and Jive-only fields (username, places, content types). Collect/Explore work once content is synced; Explore treats Jive hits as pages (and shows discussion replies when present).

## Related

**SharePoint** is a separate `source.type` (`docs/SHAREPOINT.md`) with its own config keys and Settings section — do not reuse Jive or Confluence fields for it.
