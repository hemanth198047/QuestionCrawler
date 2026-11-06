# Troubleshooting

## Seeing backend errors clearly

When the backend fails, the launcher prints a box titled **THE FIRST PROBLEM IN THE BACKEND OUTPUT** (the first Java compile error, Gradle's
"What went wrong", or Spring's "APPLICATION FAILED TO START") followed by what to do. The complete output of each run is saved in:

- `logs/backend.log` (Gradle + Spring)
- `logs/frontend.log` (Vite)

To see the backend alone, unmixed: `npm run backend`. To only compile it (fast, shows code errors): `npm run build:backend`.
When asking for help, send the box and the first ~30 lines of `logs/backend.log`.

| Symptom | Cause / fix |
|---|---|
| `npm start` says Java/Node not found or too old | Install JDK 21 and Node 20+, open a NEW terminal, run `npm run check`. |
| npm says `403 Forbidden` / `E401` / certificate or proxy errors while installing the frontend | Your company network controls npm. See [ORG_NPM.md](ORG_NPM.md): set the internal registry (`QC_NPM_REGISTRY` or `npm config set registry`), add your token to your user `.npmrc`, or ask for a blocked package to be allowed. |
| *Check access* says the connection to the site was refused / `Network access ... is required` | Your company probably needs a **web proxy**. Enter its address and port on the Connect screen (or set `HTTPS_PROXY`). See [ORG_PROXY.md](ORG_PROXY.md). |
| `AUTH_FAILED ... access key` / error 405 `key_required` | The API wants its access key: set the variable named under *API access key variable name* in the terminal, and check *Key parameter name* / *Send the key in* (Connect > Read the documentation fills them in). |
| `[vite] http proxy error ... ECONNREFUSED 127.0.0.1:8081` | The interface was forwarding requests to a backend that was not running yet (the first start compiles it) or had stopped. Fixed: the interface now starts only after the backend is healthy, and the page retries by itself. If it persists, the backend is down: read the `api │` lines or `logs/backend.log`, and check `curl http://127.0.0.1:8081/api/health` (use the port shown by the launcher). |
| The editor or `npm run dev` says a component (for example `RawAnswerPanel`, `TagFinder`) is not found / not available although you copied the files | Run `node scripts/check-frontend.mjs` (or `npm run check:frontend`). It reports the exact cause: file missing, wrong folder, hidden `.txt` extension, different capitalization, a saved GitHub web page instead of the raw file, an older copy without the expected export, or a missing package. If it says OK and the editor still complains, restart its TypeScript server (VS Code: Ctrl+Shift+P, *TypeScript: Restart TS Server*). |
| `We could not find a tag named 'x' on the site` although the tag exists | The site may spell it differently (capitals, hyphens). Use **Find tags on the site** on the Connect screen to see the site's own spelling and counts; collecting uses that spelling automatically. See [INTERNAL_API.md](INTERNAL_API.md). |
| *Sign-in* step: `The site sends visitors to a sign-in page` | Your site uses single sign-on (the step names the system). A program cannot complete a browser sign-in: you need a token or service account and the API address that accepts it. If a real API call with your key already succeeds, the step shows PASS. See [INTERNAL_API.md](INTERNAL_API.md), section on single sign-on, and use **Copy message for IT** on the Connect screen. |
| *Sign-in* step says the address redirects elsewhere / `redirect ... was not followed` | Redirects are followed by default. This message appears only if **Follow redirects** is set to *No* or *Same host only*, the redirect would go from https to plain http, loops, or leads somewhere unreachable; the message names the reason. See [INTERNAL_API.md](INTERNAL_API.md). |
| `Invalid page size, use 15, 30, 50 or 100` (HTTP 400) | Your test request used a page size the API refuses (for example `pagesize=1`). Use one of the listed sizes, or press the suggested corrected request on the Connect screen. Collecting adapts by itself. |
| `The API rejected the request (HTTP 400): <field>: <reason>` | The site refused a parameter; the reason is the site's own words (for example an unknown tag). Fix what it names. |
| `Missing bearer token` (HTTP 401) | The API also wants a Bearer token. On Connect, set **Bearer token sign-in** and name the variable that holds your token (set it in the terminal that starts the app). See [INTERNAL_API.md](INTERNAL_API.md). |
| `The API did not answer with JSON` / `Response is not valid JSON` | Open the Connect screen and use **See what the API really answers**: it shows the raw answer (sign-in page, redirect, documentation page, proxy block page ...) and what to check. See [INTERNAL_API.md](INTERNAL_API.md). |
| `API documentation needs a sign-in (HTTP 401 ...)` | The Swagger page is behind SSO. On the Connect screen use **Upload a saved copy**: open the JSON address in your browser, save it as swagger.json, upload it. See [INTERNAL_API.md](INTERNAL_API.md). |
| `We could not read the API documentation` | Paste the JSON address behind the Swagger page (the link under its title, often `/swagger/v1/swagger.json`). See [INTERNAL_API.md](INTERNAL_API.md). |
| Discover topics says `collect more` | It needs at least 8 questions; collect more first. |
| `Gradle was not found` / `Gradle is too old` | No wrapper is needed. Use your organization's Gradle (8.5+): put it on PATH or set `QC_GRADLE`. See [ORG_GRADLE.md](ORG_GRADLE.md). |
| `Plugin ... was not found` (Gradle) | Fixed by design: the build no longer uses Gradle plugins. If you still see it, run `git pull` (you may have an older copy). |
| The backend fails while resolving plugins or libraries | Your network blocks Maven Central / the plugin portal. Use an internal mirror: see [ORG_GRADLE.md](ORG_GRADLE.md). |
| `Port 8080 is already in use ... will use port 8081 instead` | Not a problem: the launcher picked a free port and opens the right address. (It only happens when something else, or an older copy, is using 8080.) |
| The backend does not start (long wait, then error) | Read the `api │` lines. Usually a blocked Gradle/Maven download: set a proxy or an internal mirror. Send me the first error line. |
| The page says the service is not reachable | The backend is still starting or stopped; watch the `api │` lines in the terminal. |
| `QuestionCrawler is not built yet` | Run `./gradlew :core:installDist` in `backend/` (JDK 21). |
| `Knowledge store not found at ...` | Run `init` and `sync` (or `demo`), or fix `storage.root`/`--storage`. |
| `Search index not found` | Run `reindex` (threads exist) or `sync`. |
| `Another synchronization is already running` | Wait for it; the lock is released automatically if the process dies. |
| `NOT_CONFIGURED: Live source integration is not implemented` | Expected until the live adapter is done: see LIVE_INTEGRATION.md. Use `source.type=fixture`. |
| `AUTH_FAILED` | Token variable unset/expired/rejected. Check `doctor` ("credentials"), re-issue the token. Auth failures are not retried. |
| `Host not in source.allowedHosts` / `Path not in ...` | Add the host/prefix to the config. This allow-list is deliberate. |
| `HTTP 302 redirect ... was not followed` | Usually a login redirect: you are not authenticated for this URL. |
| TLS / certificate errors | Provide your organization CA in `source.caCertFile` (PEM). TLS verification cannot be disabled. |
| `QUOTA_EXHAUSTED` / "request quota is used up" | Stack Exchange anonymous quota (~300/day) is spent. Wait, shorten `sync.maxQuestionAge`, or set an application key in the variable named by `source.stackexchange.keyEnv`. |
| Stack Overflow sync finds 0 questions | Check the tag slug (`artificial-intelligence`), the `sync.maxQuestionAge` window, and `doctor --connect`. |
| `The search index was built by an older version` | Run `reindex` (age filters need the new index format). |
| Age filter returns nothing | Remember ages count back from today to creation; posts without a creation date are excluded. Try a wider age or remove the filter. |
| `Invalid age 'x'` | Use a number and a unit: h, d, w, m, y (for example `30d`, `6m`). |
| Sync `PARTIAL` | Some items failed after retries; see the report. The checkpoint did not advance, so the next sync retries them. |
| Sync was interrupted | Run `sync` again; it resumes (`status` shows "interrupted run pending"). |
| Search finds nothing but the topic exists | Search is lexical: use the exact error text, identifiers or other wording; check `--tag/--category` filters; check `status` freshness. |
| Many results `uncategorized` | Refine `taxonomy.json`, then `reindex`. |
| `status` says threads classified with an older taxonomy | Run `reindex`. |
| Copilot does not use the skill | Use agent mode, confirm Agent Skills are enabled/allowed in your VS Code/org, mention the skill by name, check `.github/skills/internal-qa-knowledge/SKILL.md` exists. |
| Copilot cannot run the command | Approve the terminal command, or run it yourself and paste the output. A cloud-hosted Copilot cannot see local files. |
| Garbled characters in Windows PowerShell | CLI output is UTF-8; use Windows Terminal or `[Console]::OutputEncoding = [Text.Encoding]::UTF8`. `--format json` is ASCII-escaped. |
| Web UI shows "Backend unreachable" | Start `:app:bootRun`; check the proxy target (`VITE_BACKEND_URL`). |
| Search index corrupt | Delete `<storage-root>/index` and run `reindex`. Thread files are the source of truth. |
