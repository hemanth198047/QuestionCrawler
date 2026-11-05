# Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `npm start` says Java/Node not found or too old | Install JDK 21 and Node 20+, open a NEW terminal, run `npm run check`. |
| `Gradle is needed once` | Install Gradle once (https://gradle.org/install); the launcher then creates the wrapper. |
| `Port 8080 / 5173 is already in use` | Another copy is running. Close it, or set `QC_API_PORT` / `QC_UI_PORT`. |
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
