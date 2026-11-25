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

## In the app (no terminal, no configuration files)

Open **Search-AI** in the left menu.

1. **First time only:** paste the Search-AI address (for example `https://search-ai.yourcompany.com`; `/searchapi` is added if you leave it out). It is saved for you.
2. The page checks the sign-in by itself and shows **Connected**, or says in plain words what to do (be on the company network/VPN, sign in to your computer with your company account) and offers **Check again**. The screen uses the Kerberos ticket of the computer that runs QuestionCrawler; it does not open a browser to sign in. (The backend still has a browser sign-in, `POST /api/searchai/sign-in`, but the screen does not use it.)
3. Type a question and press **Search** (the results list) or **Ask AI** (a written answer with its sources). **Summarize** on a result gives a short summary, **Show more results** loads the next page, and **Area** limits the search to one use case.
4. Everything is saved on this computer automatically. **Saved on this computer** at the bottom reopens an earlier search without asking Search-AI again.

The app never asks for or stores a password. Results are shown without
knowing their exact format: titles, links and text are picked out where they are usually found, and **Show full answer** always shows exactly what Search-AI returned.

## Sign-in is automated (setup and start)

| Command | What it does with search-ai |
|---|---|
| `npm start` (or `./start.sh`, `start.cmd`) | Before the backend starts: checks curl has Kerberos support, checks the ticket with `klist -s`, runs `kinit` if there is none (asks for your password in the terminal). After the backend is up it calls `/whoami` once and prints the result. A problem is a warning only: the rest of the app still starts. |
| `npm start -- --check` | Prerequisites plus the curl and ticket check. |
| `npm run searchai` | The full test: curl check, ticket (kinit if needed), builds the CLI, runs `questioncrawler searchai whoami`. Exit code 0 only if signed in. |
| `--skip-searchai` or `QC_SKIP_SEARCHAI=1` | Skips all of the above. |

Unattended use: set `QC_KINIT_PRINCIPAL=user@REALM` and `QC_KINIT_KEYTAB=/path/user.keytab` and `kinit -kt` runs without a prompt.
With only `QC_KINIT_PRINCIPAL` set, `kinit <principal>` asks for the password. Windows skips kinit (SSPI uses your Windows sign-in).

## How to test it, step by step

1. Enter the address once in the app (**Search-AI** screen). For the command line only: run with `QC_SEARCHAI_BASE_URI=https://.../searchapi npm run searchai` (written to your config), or edit `searchai.baseUri` by hand (the key is case-sensitive). No config file yet? Run `scripts/qc.sh init` first.
2. Run `npm run searchai`. Expect: curl ✓, Kerberos ticket ✓, `Signed in (HTTP 200): ...`, `search-ai sign-in works`.
3. Run `scripts/qc.sh searchai usecases`, then `scripts/qc.sh searchai search "Claude Code" --rows 5`. Open the stored file (the command prints the id and the folder) and check it matches what the UI shows for the same query.
4. Try `--use-case <one from usecases>`, `--rows 50 --pages 2` (second file should have `start=50`), `searchai answer "..."`, and `searchai summary "Claude Code" --chunk-id <id from the stored search file>`.
5. `scripts/qc.sh searchai list` shows everything stored. If paging stops after one page, send me one stored `search` file (remove anything private) so the hit list can be located.

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


## Using your own signed-in browser (no Kerberos or curl needed)

If Search-AI answers in your browser (for example `/searchapi/whoami`) but the app's own Kerberos call fails, the app can make
every call from inside your Edge/Chrome window instead. The browser brings its own sign-in, so the app does no authentication.

- `searchai.transport=auto` (default): curl first; if it is refused or unavailable, the app opens one browser window on
  Search-AI, reads through it (kept minimised, closed with the app) and keeps using it.
- `searchai.transport=browser`: always through the browser window.
- `searchai.transport=curl`: Kerberos/curl only.

The first call may show the window briefly; sign in there if the company page asks. The Search-AI screen also has
"Open this search in Search-AI", which opens the website's own results page (`/search?query=...&usecases=default&date_range=ALLTIME`)
in your browser. Not verified here against the real site: this sandbox has no desktop browser or Search-AI access.


## Saving the pages behind results (on demand)

Search-AI shows links and short snippets. Press **Save page** on a result, or **Save these pages for search** above the list
(the first 25 results), to read the page the link leads to and keep its text on this computer. Nothing is read until you press it.

- Pages behind the company login open the browser window once for sign-in, the same way the Links source works.
- Saved pages are searched together with everything else the app collected (Confluence, Jive, Stack Overflow...), also when
  Search-AI or the VPN is not available. They appear under the source name `searchai-pages`.
- Pressing Save again refreshes the stored copy. A page that cannot be read shows the reason on its result; the others are still saved.
