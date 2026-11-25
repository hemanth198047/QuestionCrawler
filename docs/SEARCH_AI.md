# search-ai: get the same results as the search-ai UI, and store them

The search-ai UI is a web app on top of a REST API. QuestionCrawler calls that API directly (base path **`/searchapi`**, not `/api`)
and keeps every answer as a JSON file, so results can be re-read, compared and processed later without asking again.

## Set up

1. Have a Kerberos ticket. On Linux/macOS run `kinit` (a 401 means the ticket is missing or expired). On Windows, SSPI uses your sign-in; a 401 there is an IT ticket.
2. `curl` with Kerberos/GSS-API support must be installed (`curl -V` lists `GSS-API`/`SPNEGO`). The JDK's own HTTP client cannot do this sign-in, which is why curl is used.
3. Add the `searchai.*` block from `config/questioncrawler.example.properties` to your configuration file (all keys have defaults):

| Key | Default | Meaning |
|---|---|---|
| `searchai.baseUri` | `https://search-ai.webfarm.xx.com/searchapi` | API base. Must be `https://`. |
| `searchai.cookieJar` | `~/.cache/questioncrawler/search-cookies.jar` | curl cookie jar seeded by `/whoami`. Change the file name here. Created owner-only. |
| `searchai.curl` | `curl` | curl executable (name or full path). |
| `searchai.timeoutSeconds` | `60` | Whole-request timeout. |
| `searchai.useCase` | empty | `use_case` sent when a call gives none. |
| `searchai.rows` | `10` | `rows` sent to `/search` when a call gives none. |

## Command line

```bash
questioncrawler searchai whoami                         # GET /whoami: proves the sign-in works (not stored)
questioncrawler searchai usecases                       # GET /usecases: the use_case values you can filter by
questioncrawler searchai search "Claude Code" --rows 10 # POST /search, stored
questioncrawler searchai search "Claude Code" --rows 50 --pages 4 --use-case tech
questioncrawler searchai answer "how to deploy on kubernetes" --use-case tech   # POST /genairesponse, stored
questioncrawler searchai summary "Claude Code" --chunk-id 2083293356_0          # POST /summary, stored
questioncrawler searchai list --kind search --top 10    # stored answers, newest first
questioncrawler searchai show search/20261125T080000000Z-1a2b3c4d5e6f
```

Add `--json` for machine-readable output. `--config <file>` and `--storage <dir>` work as for every other command.
`/whoami` is called automatically once before the first POST of a run (a POST on a fresh session otherwise fails with HTTP 500).

## REST API of the QuestionCrawler backend

| Call | What it does |
|---|---|
| `GET /api/searchai/whoami` | Sign-in check |
| `POST /api/searchai/search?query=&rows=&start=&useCase=&sortBy=&pages=` | Search, stores each page, returns the stored answers |
| `POST /api/searchai/answer?query=&useCase=` | AI-synthesised answer plus sources |
| `POST /api/searchai/summary?query=&chunkId=` | Summary of one hit |
| `POST /api/searchai/usecases` | Fetch and store the use-case list |
| `GET /api/searchai/stored?kind=&top=` and `GET /api/searchai/stored/{kind}/{name}` | Read stored answers |

search-ai errors come back as problem responses: 401 stays 401 (run `kinit` on the machine running the backend), anything else is 502 with the reason.
The API uses the **server's** Kerberos ticket; like the rest of QuestionCrawler it binds to `127.0.0.1` and has no authentication of its own (see ARCHITECTURE.md, "Shared deployment").

## Where the answers are stored

```
<storage-root>/searchai/<kind>/<yyyyMMdd'T'HHmmssSSS'Z'>-<hash>.json      kind = search | answer | summary | usecases
```

Each file holds what was asked (endpoint, query-string parameters, base address), the HTTP status, the fetch time and the answer **unchanged**
(`response`, or `text` if the body was not JSON). Every call is a new file (a history); writes are atomic. The folder is not part of
`backup` (it is re-fetchable and not durable knowledge) and not read by `search`/`reindex`.

## Behaviour you should know

- **Parameters on the query string.** `/search` takes `query`, `rows`, `start`, `use_case`, `sort_by` there, not in a JSON body. The client always URL-encodes them.
- **`use_case`, not `usecases`**, and **`start`, not `page`/`currentPage`**: the wrong spellings are silently ignored by the server, so the client only ever sends the right ones.
- **`/genairesponse` needs a JSON body**, even an empty one (`{}`); without it the server answers 422. The client always sends it.
- **Paging (`--pages N`)** requests `start = 0, rows, 2*rows, ...`. The shape of a `/search` answer is not documented, so the client looks for the list of hits (a top-level array, or `results`/`docs`/`hits`/`items`/`data`/`response`/`documents`/`chunks`).
  It stops on a short page, an empty page, a page identical to the previous one (server ignoring `start`), or when no list can be found. At most 50 pages per call.
- **`--location-trusted`** is used as in the documented helper: Kerberos sign-in redirects between hosts and the credentials must follow. The ticket is negotiated by curl; nothing secret is written by QuestionCrawler.
- **TLS verification is never disabled.** If curl reports an untrusted certificate (exit 60), point `CURL_CA_BUNDLE` at your organization's CA bundle.
- Retrieved content is untrusted data. It is stored and printed as data; nothing in an answer is ever executed or followed.

## Not covered yet

- No UI screen: use the CLI or the REST calls above.
- Stored answers are not merged into the local Lucene search index.
- `summary` needs a `chunkid` taken from a stored search answer; its field name in the answer is not documented, so it is not extracted automatically.
