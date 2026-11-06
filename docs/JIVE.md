# Jive source

QuestionCrawler can collect **Jive community content** into the same local knowledge store used for Stack Exchange, Confluence, and demo data.

`source.type=jive` is a **first-class, separate** source. It does **not** share settings with Stack Exchange, Confluence, the internal live Q&A adapter, or future **SharePoint** connectors.

## What you get

- Documents, discussions, and questions (configurable) via the **Jive Core REST API v3** (`/api/core/v3/contents`, search/filters)
- Stored under `threads/<systemId>/<contentId>.json` (default `systemId=jive`)
- Content tags stay tags; optional synthetic `place:ID` and `type:…` tags
- Discussion/question replies are fetched as answers when the messages endpoint is available; optional comments
- Incremental sync using the `after(…)` filter when the site supports it

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

## UI

Connect and Settings expose a Jive preset and Jive-only fields (username, places, content types). Collect/Explore work once content is synced.

## Next

**SharePoint** remains a separate future `source.type` with its own config keys and Settings section — do not reuse Jive or Confluence fields for it.
