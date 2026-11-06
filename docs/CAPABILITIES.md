# Verified capabilities and remaining work

## Verified by running (this repository, offline)

Environment used: JDK 21 on Linux, **Lucene 9.10, Jackson 2.14, JUnit 5.10 from Ubuntu packages** (the sandbox could not reach Maven Central).

- `backend/core` compiled with `javac`; **172 automated tests passed**, 0 failed (stable over repeated runs): question/answer pagination, accepted and unaccepted
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
- The access diagnostics (network, TLS explanations, sign-in redirects, 401 methods, published API descriptions, robots.txt, script-built
  pages, bot protection, Stack Exchange quota and tag size) run against local servers that play each kind of site, plus the settings
  service (every setting explained, validation with fixes, readable file round trip), sync progress events, the workspace/overview,
  and the editable category rules with the "try it" tester.
- The **launcher** (`npm start`) was run end to end with the dev API standing in for Spring: it started both servers, waited for health,
  the UI proxied to the API, and Ctrl+C left nothing running.
- The **UI** was built and rendered in headless Firefox against real data (Overview, Connect with a blocked route map, Collect, Explore with
  age sliders, Question, Organize with the rule tester, Settings, Help, and the first-run state); the 32 expectations of the new Spring API
  tests were replayed against the same logic and all held.
- The **internal REST API route** was tested against a mock site (Swashbuckle-style Swagger page, key on every request, unknown `withbody` filter, gzip without the header,
  comments, no `site` parameter, missing `tagged`): documentation found (page, initializer script, usual locations), 17 endpoints listed, endpoints matched to purposes,
  key placement detected, collection with comments, wrong/missing key reported as an authentication problem.
- **Topic discovery** recovers four known subjects from synthetic questions by their words alone (purity at least 85%), is deterministic, and refuses to invent topics from too little data.
- The offline demo (`demo`) runs end to end.
- `frontend` installs, type-checks, builds (Vite) and lints with no warnings.

## Written but NOT verified here

- **Live Stack Overflow access.** The sandbox has no outbound access to api.stackexchange.com, so the adapter has never talked to the real
  service; request shapes follow the official API documentation and a mock. See STACKOVERFLOW.md.
- **Gradle build** (multi-module, wrapper properties): not run; no Gradle/Maven access in the sandbox. Versions (Spring Boot 3.3.1 after a company mirror only offered that; the Spring Boot and dependency-management Gradle *plugins* were removed because company mirrors lack plugin markers (`bootRun` is defined by hand, and `bootJar` is no longer available),
  Lucene 9.11.1) live in `backend/gradle.properties`; the build files were checked to resolve those versions with a real Gradle (9.3.1) that had no network, so downloading and compiling remain unverified. The wrapper jar must be generated (`gradle wrapper`).
- **Your real internal site:** never reached from here. The Swagger reader, endpoint matching and API calls follow your description and the Stack Exchange 2.3 conventions; real-world differences are possible.
- **Spring Boot app** (`backend/app`) and its MockMvc tests: the controllers were only **type-checked against stand-ins for the Spring annotations**, never compiled or run with real Spring, and the MockMvc tests have not been run. This is the riskiest part of a first `npm start`. Lucene 9.12 (vs 9.10 tested) and Jackson 2.19 (vs 2.14) are expected to be API-compatible but unconfirmed.
- **Web UI against the real Spring backend** (it was exercised against an equivalent test-scope server, in Firefox only; no Chrome/Safari/Edge).
- **The launcher on Windows and macOS** (run on Linux only), and the Gradle bootRun and static-UI packaging steps.
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
