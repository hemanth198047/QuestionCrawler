# SharePoint source

QuestionCrawler can collect **SharePoint Online** site pages (and optionally list items) into the same local knowledge store used for Stack Exchange, Confluence, Jive, and demo data.

`source.type=sharepoint` is a **first-class, separate** source. It does **not** share settings with Stack Exchange, Confluence, Jive, or the internal live Q&A adapter.

## What you get

- Site pages via **Microsoft Graph** (`/sites/{id}/pages/microsoft.graph.sitePage`)
- Optional list items via Graph (`/sites/{id}/lists/{listId}/items`) when `contentMode` is `listItems` or `both`
- Stored under `threads/<systemId>/<id>.json` (default `systemId=sharepoint`)
- Synthetic `site:…` tags (optional); list items also get `list:{listId}` and Category/Keywords when present
- Answers stay empty (SharePoint pages are not Q&A)
- Incremental sync using Graph `lastModifiedDateTime` `$filter` when the tenant supports it

## Auth (secrets stay in environment variables)

| Method | Config | Env var (name only in config) |
|---|---|---|
| Bearer access token | `source.auth.method=token` | `SHAREPOINT_ACCESS_TOKEN` |
| Azure AD client credentials (app-only) | `source.sharepoint.tenantId` + `clientId` + `clientSecretEnv` | `SHAREPOINT_CLIENT_SECRET` |

Client credentials: the adapter POSTs to `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token` with scope `https://graph.microsoft.com/.default`, then calls Graph with the minted Bearer token. A pre-minted token in `SHAREPOINT_ACCESS_TOKEN` is used when tenant/client id are left empty.

Never put secrets in properties files, fixtures, or logs. Cookie scraping and browser SSO automation are not supported.

Grant the Entra ID app (application permissions) at least **Sites.Read.All** (admin consent) for app-only access, or use a delegated token with Sites.Read.All / equivalent.

Live testing needs operator-supplied credentials against a real tenant (`doctor --connect`, then `sync`, then `search`).

## Minimal Bearer example

```properties
source.type=sharepoint
source.baseUrl=https://contoso.sharepoint.com/sites/Engineering
source.sharepoint.contentMode=pages
source.auth.method=token
source.auth.tokenEnv=SHAREPOINT_ACCESS_TOKEN
source.allowedHosts=contoso.sharepoint.com,graph.microsoft.com
source.allowedPathPrefixes=/v1.0/
source.followRedirects=same-host
sync.tags=all
sync.maxQuestionAge=1y
```

Then:

```bash
export SHAREPOINT_ACCESS_TOKEN='…'   # Graph access token with Sites.Read.All
questioncrawler doctor --connect
questioncrawler sync
questioncrawler search "your query"
```

## Minimal client-credentials example

```properties
source.type=sharepoint
source.baseUrl=https://contoso.sharepoint.com/sites/Engineering
source.sharepoint.tenantId=00000000-0000-0000-0000-000000000000
source.sharepoint.clientId=11111111-1111-1111-1111-111111111111
source.sharepoint.clientSecretEnv=SHAREPOINT_CLIENT_SECRET
source.auth.method=token
source.auth.tokenEnv=SHAREPOINT_ACCESS_TOKEN
source.allowedHosts=contoso.sharepoint.com,graph.microsoft.com
source.allowedPathPrefixes=/v1.0/
sync.tags=all
sync.maxQuestionAge=1y
```

```bash
export SHAREPOINT_CLIENT_SECRET='…'
questioncrawler doctor --connect
questioncrawler sync
```

## Scope rules

Collecting `sync.tags=all` without a **site URL**, **site id**, or **time window** is refused.

For `contentMode=listItems`, set `source.sharepoint.listIds` (Graph list ids).

National clouds: override `source.sharepoint.graphBaseUrl` (for example `https://graph.microsoft.us/v1.0`).

## UI

Connect and Settings expose a SharePoint preset and SharePoint-only fields (tenant, client id, secret env name, site id, lists, content mode). Collect/Explore work once content is synced.

## Separation

Do **not** reuse Confluence (`source.confluence.*`) or Jive (`source.jive.*`) keys for SharePoint. Stack Overflow / Stack Exchange remains its own `source.type` as well.
