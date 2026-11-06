# Confluence source

QuestionCrawler can collect **Confluence pages** into the same local knowledge store used for Stack Exchange and demo data.

`source.type=confluence` is a **first-class, separate** source. It does **not** share settings with Stack Exchange, the internal live Q&A adapter, **Jive**, or future **SharePoint** connectors.

## What you get

- Pages (and optionally page comments) via the classic Confluence **Content REST API** and **CQL** search
- Stored under `threads/<systemId>/<pageId>.json` (default `systemId=confluence`)
- Labels become tags; answers stay empty (wiki pages are not Q&A)
- Incremental sync using CQL `lastmodified` when the site supports it

## Auth (secrets stay in environment variables)

| Edition | Method | Config | Env var (name only in config) |
|---|---|---|---|
| Cloud | Basic (`email:api_token`) | `source.auth.method=basic-env`, `source.confluence.email=…` | `CONFLUENCE_API_TOKEN` |
| Server / Data Center | Bearer PAT | `source.auth.method=token` | `CONFLUENCE_PAT` |

Never put tokens in properties files, fixtures, or logs. Cookie scraping and browser SSO automation are not supported.

## Minimal Cloud example

```properties
source.type=confluence
source.baseUrl=https://acme.atlassian.net
source.confluence.edition=cloud
source.confluence.email=you@example.com
source.confluence.spaces=ENG
source.auth.method=basic-env
source.auth.tokenEnv=CONFLUENCE_API_TOKEN
source.allowedHosts=acme.atlassian.net
source.allowedPathPrefixes=/wiki/rest/api/
source.followRedirects=same-host
sync.tags=runbook
sync.maxQuestionAge=1y
```

Then:

```bash
export CONFLUENCE_API_TOKEN='…'   # Atlassian API token for that email
questioncrawler doctor --connect
questioncrawler sync
questioncrawler search "your query"
```

## Scope rules

Collecting `sync.tags=all` without a **space**, **ancestor page**, or **time window** is refused — a whole Cloud tenant can be huge.

## UI

Connect and Settings expose a Confluence preset and Confluence-only fields (edition, email, spaces, labels-as-tags). Collect/Explore work once pages are synced.
