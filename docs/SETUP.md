# Setup (Windows and Linux)

## 1. Prerequisites

- JDK 21 (verify: `java -version`)
- Gradle, only once, to generate the wrapper (or copy a wrapper from another project). Your organization may require
  an internal Maven mirror: configure it in `~/.gradle/init.gradle` or `backend/build.gradle` `repositories`.
- Node.js 20+ only if you want the web UI.

## 2. Build the CLI

Linux/macOS (Bash):
```bash
cd backend
gradle wrapper --gradle-version 8.14     # one-time; creates gradlew and gradle-wrapper.jar
./gradlew :core:test                     # run the core tests
./gradlew :core:installDist              # builds backend/core/build/install/questioncrawler
cd ..
scripts/qc.sh help
```

Windows (PowerShell):
```powershell
cd backend
gradle wrapper --gradle-version 8.14
.\gradlew.bat :core:test
.\gradlew.bat :core:installDist
cd ..
scripts\qc.ps1 help
```

## 3. Initialize configuration and storage

```bash
scripts/qc.sh init --storage /approved/path/questioncrawler-data
```
```powershell
scripts\qc.ps1 init --storage D:\Approved\QuestionCrawlerData
```

`init` writes `~/.questioncrawler/config.properties` and `taxonomy.json` next to it (override with `--config`),
and creates the storage folders. Choose a storage path that is **organization-approved and outside this repository**.
Edit the config: `sync.tags`, `sync.tagMode` (ANY/ALL), source settings. Config lookup order: `--config`,
`$QUESTIONCRAWLER_CONFIG`, `./questioncrawler.properties`, `~/.questioncrawler/config.properties`.

## 4. Validate

```bash
scripts/qc.sh doctor            # configuration, storage, taxonomy, index
scripts/qc.sh doctor --connect  # also tests the source (reachability, TLS, auth)
scripts/qc.sh demo              # offline demo, no credentials
```

## 5. Collect and use

With `source.type=fixture` you can try everything on synthetic data. With the live site, finish
[LIVE_INTEGRATION.md](LIVE_INTEGRATION.md) first.

```bash
scripts/qc.sh sync                       # first run = initial collection; later runs = incremental
scripts/qc.sh sync --full                # full reconciliation (detects deletions, revoked access, tag changes)
scripts/qc.sh sync --tag orbit --tag-mode all --partial-tags   # explicit scope for this run
scripts/qc.sh search "connection timeout" --format json --top 5
scripts/qc.sh show 1001
scripts/qc.sh categories
scripts/qc.sh categories data-ingestion
scripts/qc.sh status
scripts/qc.sh reindex                    # rebuild classification, markdown and index (after editing the taxonomy)
scripts/qc.sh override set 1001 performance --note "tuning question"
```
```powershell
scripts\qc.ps1 sync
scripts\qc.ps1 search "connection timeout" --format json --top 5
scripts\qc.ps1 status
```

Exit codes: `0` ok, `1` error, `2` usage error, `3` partial sync / confirmation missing.

Age filters: `search ... --max-question-age 90d --max-answer-age 30d` (see [AGE_FILTERS.md](AGE_FILTERS.md)).
Stack Overflow test run: see [STACKOVERFLOW.md](STACKOVERFLOW.md).

### Authentication (live source)

Set the token in the **environment**, never in a file:
```bash
export INTERNAL_QA_TOKEN='...'          # Bash
```
```powershell
$env:INTERNAL_QA_TOKEN = '...'          # PowerShell (current session)
```
The config only names the variable (`source.auth.tokenEnv`). Use only approved authentication; MFA/SSO flows that need a
browser are not bypassed. If your site needs SSO, ask the platform team for an approved service token or export.

## 6. Web app (optional)

```bash
cd backend && ./gradlew :app:bootRun                 # http://127.0.0.1:8080 (set questioncrawler.config-file if needed)
cd frontend && npm install && npm run dev            # http://localhost:5173, proxies /api
```
The API is bound to localhost and unauthenticated; see "Shared deployment" in ARCHITECTURE.md. Production build of the
UI: `npm run build` (static files in `frontend/dist`; serve them from an approved web server).

## 7. Backup and restore

```bash
scripts/qc.sh backup --out /approved/backups/qc-2026-11-04.zip   # threads, classification, overrides, state
scripts/qc.sh restore /approved/backups/qc-2026-11-04.zip --yes  # REPLACES durable data
scripts/qc.sh reindex                                            # rebuild markdown + search index
```
Backups contain collected internal content: store them with the same protections as the store itself.
A plain copy of the storage root also works when no sync is running.

## 8. Copilot

See [COPILOT.md](COPILOT.md).
