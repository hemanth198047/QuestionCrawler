# Verified capabilities and remaining work

## Verified by running (this repository, offline)

Environment used: JDK 21 on Linux, **Lucene 9.10, Jackson 2.14, JUnit 5.10 from Ubuntu packages** (the sandbox could not reach Maven Central).

- `backend/core` compiled with `javac`; **103 automated tests passed**, 0 failed (stable over repeated runs): question/answer pagination, accepted and unaccepted
  answers, unanswered questions, code-block/link/table preservation, duplicate IDs, incremental updates, interrupted-sync resume,
  rate limits (Retry-After, exponential backoff) and auth failures, per-item failures and checkpoint behaviour, deleted /
  inaccessible / no-longer-matching handling, retention, concurrency lock, classification + explanations + persistent overrides,
  search for exact errors/codes/identifiers, evidence URLs, filters, output budget, hostile query text, prompt-injection handling,
  HTTP transport (status mapping, host/path allow-list, redirects, token handling), storage (atomic writes, schema version,
  path safety, backup/restore, zip-slip), CLI workflow, and the retrieval eval (hit@3 = 1.00, MRR = 1.00 on 13 answerable queries;
  2 no-answer queries correctly return nothing).
- Age filters (question/answer, min/max, combined, unknown dates, browsing, collection window) and the **Stack Exchange adapter**
  (documented parameters, batching, pagination, backoff, error mapping, gzip, key handling, end-to-end sync) are tested against a
  **local mock** of the documented API.
- The offline demo (`demo`) runs end to end.
- `frontend` installs, type-checks, builds (Vite) and lints with no warnings.

## Written but NOT verified here

- **Live Stack Overflow access.** The sandbox has no outbound access to api.stackexchange.com, so the adapter has never talked to the real
  service; request shapes follow the official API documentation and a mock. See STACKOVERFLOW.md.
- **Gradle build** (multi-module, wrapper properties): not run; no Gradle/Maven access in the sandbox. Versions (Spring Boot 3.5.0,
  Lucene 9.12.0, Gradle 8.14) were chosen from knowledge, not checked against a registry. The wrapper jar must be generated (`gradle wrapper`).
- **Spring Boot app** (`backend/app`) and its MockMvc tests: written, not compiled or run. Lucene 9.12 (vs 9.10 tested) and Jackson 2.19 (vs 2.14) are expected to be API-compatible but unconfirmed.
- **Web UI against the live backend** (only built, not exercised in a browser).
- **Windows**: PowerShell launcher, Task Scheduler commands and path handling were written for Windows but not executed on it.
- **Copilot**: the skill follows the documented Agent Skills format (checked against GitHub documentation), but activation in your VS Code
  / managed environment, and the quality of Copilot's answers, were not tested.

## Remaining live-site integration work (your internal site)

1. Implement `LiveSourceAdapter` (needs API docs or sanitized samples and the approved auth method): see LIVE_INTEGRATION.md.
2. Confirm pagination, modified-since support, deleted/forbidden semantics and rate limits against the real site.
3. Tune `taxonomy.json` to your organization's categories; extend the retrieval eval with real questions.
4. Decide on shared deployment (per-group stores + authenticating proxy), see ARCHITECTURE.md.
5. Optional: approved semantic retrieval behind `SearchService`.

## Known limitations

- Search is lexical (no stemming/synonyms): "times out" will not match "timed out" unless one of the other words matches.
- Incremental sync may miss answers added without a question timestamp change; full reconciliation fixes that.
- Injection detection is a heuristic; the real defence is the skill's rule to treat all retrieved text as data.
- Scores and "confidence" are heuristics, not probabilities.
