# Confluence source

QuestionCrawler can collect **Confluence pages** into the same local knowledge store used for Stack Exchange and demo data.

`source.type=confluence` is a **first-class, separate** source. It does **not** share settings with Stack Exchange, the internal live Q&A adapter, **Jive**, or **SharePoint**.

## What you get

- Pages (and optionally page comments) via the classic Confluence **Content REST API** and **CQL** search
- Stored under `threads/<systemId>/<pageId>.json` (default `systemId=confluence`)
- Labels become tags; answers stay empty (wiki pages are not Q&A)
- Incremental sync using CQL `lastmodified` when the site supports it

> **Sent to a company login page (HTTP 302 to SSO)?** See [CONFLUENCE_BROWSER_LOGIN.md](CONFLUENCE_BROWSER_LOGIN.md): `source.auth.method=browser`.
>
> **Company sign-in (Kerberos + Token Vending Machine, REST API v2 through the web proxy)?** See
> [CONFLUENCE_TVM.md](CONFLUENCE_TVM.md): `source.auth.method=tvm`, `source.confluence.apiVersion=v2`.

## Auth (secrets stay in environment variables)

| Edition | Method | Config | Env var (name only in config) |
|---|---|---|---|
| Cloud | Basic (`email:api_token`) | `source.auth.method=basic-env`, `source.confluence.email=…` | `CONFLUENCE_API_TOKEN` |
| Server / Data Center | Bearer PAT | `source.auth.method=token` | `CONFLUENCE_PAT` |
| Cloud (company sign-in) | Kerberos → Token Vending Machine → short-lived Bearer | `source.auth.method=tvm`, `source.confluence.tvm.url=…` | none (see [CONFLUENCE_TVM.md](CONFLUENCE_TVM.md)) |

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

## Search (plain English, no LLM)

After sync (or `reindex`), every collected page is in the same Lucene index as other sources. Explore accepts everyday questions such as “how do I configure SSO”:

- Additive English stems so related word forms meet
- A small curated synonym map (runbook↔playbook, k8s↔kubernetes, …)
- Page titles boosted; page comments indexed when collected
- Still local lexical search — not an AI model and not semantic embeddings

If search says the index is an older version, rebuild once: Settings → Where is everything saved? → **Rebuild search index**, or CLI `questioncrawler reindex`.

## UI

Connect and Settings expose a Confluence preset and Confluence-only fields (edition, email, spaces, labels-as-tags). Collect/Explore work once pages are synced; Explore copy treats Confluence hits as pages, not Q&A.
