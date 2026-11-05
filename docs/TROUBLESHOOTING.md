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
