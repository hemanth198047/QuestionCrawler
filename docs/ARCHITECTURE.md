# Architecture

## Responsibilities (kept separate)

```
 Source adapter  -->  Sync engine  -->  Normalizer  -->  File store  -->  Classifier  -->  Search index
 (SourceAdapter)     (SyncService)     (ContentNorm.)    (JSON files)    (rules)          (Lucene files)
                                                              |
                                          CLI  /  REST API + React UI  /  Copilot skill (calls the CLI)
```

| Concern | Code (`backend/core/.../core/`) |
|---|---|
| Source adapter + auth | `source/` (`SourceAdapter`, `FixtureSourceAdapter`, `HttpSourceClient`, `LiveSourceAdapter`) |
| Collection + synchronization | `sync/` |
| Normalization + content preservation | `normalize/` |
| Functional classification | `classification/` |
| Persistent storage | `storage/`, derived Markdown in `service/MarkdownExporter` |
| Search + evidence | `search/` |
| Facade used by CLI and web | `service/KnowledgeBase` |
| CLI | `cli/` |

`service/Workspace` is the running application's single point of truth: where the settings file is, the current `KnowledgeBase`, the sync
runner with live progress, the connection diagnostics (`SiteDiagnostics`), the settings service (`ConfigService`) and category editing.
Saving settings reloads everything. The Spring Boot app (`backend/app`) only wires `Workspace` into HTTP endpoints (and serves the built UI). The Copilot skill only
teaches Copilot to run the CLI. The skill provides no scheduler, authentication or background execution.

## Storage: files, no database

Everything lives under one configurable **storage root** (default `~/.questioncrawler/data`, outside the repository):

```
<storage-root>/
  storage-manifest.json          schema version
  threads/<source>/<id>.json     SOURCE OF TRUTH: normalized question + all answers + bookkeeping
  classification/<source>/<id>.json   generated categories + matched rules (provenance)
  classification/overrides.json  manual overrides (never touched by sync)
  state/checkpoint-<scope>.json  high-water mark per collection scope
  state/run-<scope>.json         in-flight run (exists only if a run was interrupted)
  state/reports/<runId>.json     synchronization reports
  state/sync.lock                OS file lock: one sync at a time
  markdown/questions/...         human-readable export (derived)
  markdown/categories/*.md       category indexes that LINK to question files (derived)
  index/                         Lucene search index (derived)
  backups/
```

- Every write is temp-file + atomic rename. A killed process cannot leave a half-written record.
- Original normalized content (`threads/`) is separate from generated data (`classification/`, `index/`, `markdown/`).
  Derived data can always be rebuilt with `reindex`.
- Schema is versioned; a newer-than-supported store is refused with a clear message.
- Identifiers from the source are mapped to safe file names (no path traversal).

## Search

Embedded Lucene (BM25) over one document per question and per answer. Text is tokenized so error codes
(`ORB-1042`), identifiers (`ManifestLoader.loadFromPath`) and camelCase symbols are findable whole and by parts.
User text is turned into term/phrase queries directly, never passed to a query parser, so it cannot inject query syntax.
Ranking = BM25 with title and exact-phrase boosts, a small bounded adjustment for accepted status and votes
(signals, not proof), and a term-coverage filter that drops weak lexical overlap. Output is bounded
(`--top`, `--max-chars`, `--max-passage-chars`). No vectors/embeddings/LLM; semantic retrieval can later be added behind
`SearchService` (extension point) if an approved service exists.


### Pages without questions and answers (Confluence, Jive, SharePoint)

Pages are prose, not Q&A. At index time `PageSections` cuts each page's Markdown at its headings into sections, and long
sections into ~1,200-character passages at paragraph boundaries (one short paragraph of overlap; code blocks, tables and
lists are not cut). Each passage is its own Lucene document with its heading path (`Install › Windows › Proxy`) in a
boosted `heading` field. Comments become a final "Comments" section. The stored page is never changed; only the index is.

`AnswerComposer` then builds a **direct answer without an AI model**: from the top passages it scores sentences, lists,
tables and code blocks by how many question words they contain, how relevant their passage was, and the kind of question
(how → steps/lists, what → defining sentences, when/how long → numbers, who → contacts, where → links/locations,
why → reasons). The best one or two pieces are **quoted word for word** with page, section and a link that opens the
original page at that section (`#:~:text=Heading`, supported by Edge and Chrome). Below a confidence floor it answers
"no page clearly answers this". Text flagged as possible instruction injection is never quoted as the answer.

The index format is versioned (`INDEX_VERSION` 4). An older index is rebuilt automatically from the stored threads the
first time it is searched or synced (nothing is downloaded again).

## Classification

Rule-based and deterministic. The taxonomy (categories, descriptions, keyword/regex/tag rules, weights, `minScore`) is a
JSON file you own (`config/taxonomy.example.json`). A thread can have several categories, each with the rules that matched,
and falls back to `uncategorized`. **This is not AI semantic understanding.** Manual overrides (`override set/clear`) win
over generated results and survive synchronization and reindex.

## Security model

- Never bypasses authentication, MFA or access controls. Token auth reads the token from an **environment variable
  whose name** is in the config; credentials are never in code, config, fixtures, exports or logs.
- TLS verification is always on; `source.caCertFile` adds an organization CA to the trust set.
- Only configured hosts and path prefixes are contacted; redirects are not followed; links inside posts are never followed.
- Retrieved content is untrusted. CLI output wraps it in explicit markers, flags likely injection text
  (`possible-instruction-injection`, a heuristic) and the skill forbids following embedded instructions.
- Backup restore rejects archives that write outside the store.

## Shared deployment

Version 1 is a **local** store per workstation. The web API binds to `127.0.0.1` and has **no authentication**.
One account's collection may include content other people are not allowed to read, so do not expose it as is. For sharing:
(1) run the collector per team/permission group with a service account that sees only that group's content and keep one
store per group; (2) put the web app behind your approved authenticating reverse proxy and authorize per store;
(3) or have each user run their own sync with their own credentials. Never serve one broadly-privileged collection to everyone.

## Dependencies (kept minimal)

| Dependency | Why |
|---|---|
| Jackson (`jackson-databind`) | JSON persistence of all stored records and CLI/API output |
| Lucene (`lucene-core`, `lucene-analysis-common`) | Embedded full-text index in plain files; avoids a database/server and gives real ranking |
| jsoup | Website mode: reads questions and answers from a Stack Overflow-style website's pages when its API cannot be used |
| Spring Boot (`app` only) | REST API, config binding, optional scheduler |
| React + Vite + TypeScript (frontend only) | UI; no router or state library (hash routing in ~20 lines) |
| JUnit 5 (tests) | Tests |

HTTP uses the JDK's `java.net.http`; everything else is the standard library.
