# QuestionCrawler

Internal Q&A knowledge assistant. It collects questions and answers from an internal
Stack-Overflow-style site by tag, classifies them by functionality, stores them as plain files
(no database), keeps them in sync incrementally, and lets **GitHub Copilot (VS Code agent mode)**
or a web UI search them with source-linked evidence.

> **Status:** the offline vertical slice (fixture source -> sync -> classify -> store -> search -> CLI -> Copilot skill -> web API/UI)
> is implemented and tested. The **live internal-site adapter is not implemented** because the site's API is unknown
> (nothing is guessed). See [docs/CAPABILITIES.md](docs/CAPABILITIES.md) for exactly what is verified and what remains.

## Repository layout

| Path | What | Stack |
|---|---|---|
| `backend/core/` | Collection, sync, normalization, classification, file storage, search, **CLI**. No Spring. | Java 21, Gradle, Jackson, Lucene |
| `backend/app/` | REST API and optional scheduler over `core` | Spring Boot 3.5 |
| `frontend/` | Web UI (search, question view, categories, sync status) | React 19, TypeScript, Vite |
| `.github/skills/internal-qa-knowledge/` | The Copilot Agent Skill | Markdown |
| `scripts/` | `qc.sh` / `qc.ps1` launchers used by the skill | Bash / PowerShell |
| `config/` | Example configuration and taxonomy (no secrets) | |
| `eval/` | Retrieval evaluation set | JSON |
| `docs/` | Architecture, setup, Copilot usage, sync, troubleshooting | |

The three runtimes are independent; the only contracts are the CLI (for Copilot) and the `/api` HTTP API (for the UI).

## Quick start (offline demo, no credentials)

Prerequisites: JDK 21. Gradle is only needed once to create the wrapper.

```bash
# Linux / macOS
cd backend
gradle wrapper --gradle-version 8.14      # one-time
./gradlew :core:installDist
cd ..
scripts/qc.sh demo
```

```powershell
# Windows PowerShell
cd backend
gradle wrapper --gradle-version 8.14      # one-time
.\gradlew.bat :core:installDist
cd ..
scripts\qc.ps1 demo
```

The demo collects synthetic data into a temp folder, classifies it, runs searches (exact error, natural
language, code identifier, a question with no answer), simulates the site changing, and shows how an
incremental sync differs from a full reconciliation.

Then read [docs/SETUP.md](docs/SETUP.md) to configure your own storage location and tags, and
[docs/COPILOT.md](docs/COPILOT.md) to use it from Copilot.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - design, data layout, dependencies, shared-deployment guidance
- [docs/SETUP.md](docs/SETUP.md) - Windows and Linux setup, web app, backup/restore
- [docs/COPILOT.md](docs/COPILOT.md) - using the skill, permissions, sample outputs
- [docs/SYNC.md](docs/SYNC.md) - incremental vs full, statuses, retention, scheduling (Task Scheduler / cron)
- [docs/LIVE_INTEGRATION.md](docs/LIVE_INTEGRATION.md) - what is needed to connect the real site
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)
- [docs/CAPABILITIES.md](docs/CAPABILITIES.md) - verified capabilities and remaining work
