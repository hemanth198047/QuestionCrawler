# QuestionCrawler

Your team's questions and answers, collected from a site, organized into topics, and ready to ask: in a clean,
visual app that explains itself. Everything is saved as plain files on your computer (no database), and
GitHub Copilot can search it too.

![Overview](docs/images/overview.png)

## Start it: one command

You need **JDK 21** and **Node.js 20+**. Then, in this folder:

```bash
npm start
```

That checks your setup, prepares the backend and the frontend, starts both, waits until they are healthy and opens
**http://localhost:5173**. Press `Ctrl+C` to stop both. (Windows: you can also double-click `start.cmd`.)

| Command | What it does |
|---|---|
| `npm start` | Backend + frontend in development mode, browser opens |
| `npm run start:prod` | Builds the UI and serves everything from one server at http://localhost:8080 |
| `npm test` | Runs all backend tests and the frontend build and lint |
| `npm run check` | Only checks that Java and Node are installed |
| `npm run backend` | Runs **only the backend**, so its output is not mixed with the frontend's |
| `npm run build:backend` | Only **compiles** the backend and shows code errors (fast) |
| `npm start -- --no-open` | Start without opening a browser |

The first start downloads and compiles the backend, so it can take a few minutes; later starts take seconds.
It uses whatever Gradle 8.5+ you already have (including your organization's: see [docs/ORG_GRADLE.md](docs/ORG_GRADLE.md)); no wrapper is required. It prefers ports 8080 (backend) and 5173 (interface). If either is busy it **picks the next free port automatically** and tells you; the
address it opens is always printed. To prefer other ports, set `QC_API_PORT` and `QC_UI_PORT`.

## What you will see

The app guides you; you never need a manual.

1. **Overview**: where things stand and the one thing to do next.
2. **Connect a source**: choose a site and tags, then press *Check access*. A map shows the path from your computer to the
   questions and **exactly where it is blocked and what is needed** (network, certificate, sign-in, API, limits, topic),
   plus a ready-to-send message for the site owners.
3. **Collect**: pick how far back and how thorough, and watch it happen live.
4. **Explore**: ask in plain words or paste an error message. Filter by topic, tag, and the **age of questions and answers**
   with sliders. Every result shows where it came from, how old it is, and how well it matches.
5. **Organize**: topics are assigned by readable rules that you can edit, with a "try it" tester.
6. **Settings**: every setting explained in plain words, checked as you type, with a live preview of the settings file.

| Connect | Explore |
|---|---|
| ![Connect](docs/images/connect.png) | ![Explore](docs/images/explore.png) |

## Try it without any account

Open **Connect a source** and choose *Try it with demo data*, then **Collect**, then **Explore**. It uses built-in sample
questions, so it needs no internet and no login. To try a real site, choose *Stack Overflow: Artificial Intelligence*
and press *Check access*.

## Repository layout

| Path | What | Stack |
|---|---|---|
| `frontend/` | The visual app | React 19, TypeScript, Vite |
| `backend/app/` | REST API, optional scheduler, serves the built UI | Spring Boot 3.3+, Java 21 |
| `backend/core/` | Collecting, syncing, categories, search, diagnostics, **CLI**. No Spring. | Java 21, Jackson, Lucene |
| `scripts/start.mjs` | The one-command launcher | Node (no packages) |
| `scripts/qc.sh`, `qc.ps1` | Command-line launchers used by Copilot | |
| `.github/skills/internal-qa-knowledge/` | The GitHub Copilot skill | Markdown |
| `config/`, `eval/`, `docs/` | Examples, retrieval evaluation set, documentation | |

## Documentation

- [docs/INTERNAL_API.md](docs/INTERNAL_API.md): connecting to your internal site through its Swagger-documented REST API, and discovering topics
- [docs/PAGE_ANSWERS.md](docs/PAGE_ANSWERS.md): how questions are answered from pages (paragraphs, not Q&A), quoted word for word, no AI model
- [docs/CONFLUENCE_BROWSER_LOGIN.md](docs/CONFLUENCE_BROWSER_LOGIN.md): Confluence behind a company login page (SSO): sign in with your own browser
- [docs/CONFLUENCE_TVM.md](docs/CONFLUENCE_TVM.md): Confluence with company sign-in (Kerberos + Token Vending Machine, REST API v2, web proxy)
- [docs/UI_GUIDE.md](docs/UI_GUIDE.md): each screen, and the API behind it
- [docs/STACKOVERFLOW.md](docs/STACKOVERFLOW.md): trying a real public site
- [docs/AGE_FILTERS.md](docs/AGE_FILTERS.md): filtering by age of questions and answers
- [docs/COPILOT.md](docs/COPILOT.md): using it from GitHub Copilot
- [docs/SYNC.md](docs/SYNC.md): quick update vs full check, statuses, scheduling
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/SETUP.md](docs/SETUP.md) (manual and CLI setup), [docs/LIVE_INTEGRATION.md](docs/LIVE_INTEGRATION.md) (your internal site)
- [docs/ORG_PROXY.md](docs/ORG_PROXY.md) (web proxy), [docs/ORG_GRADLE.md](docs/ORG_GRADLE.md) and [docs/ORG_NPM.md](docs/ORG_NPM.md) (company Gradle / npm, internal repositories, tokens, proxies), [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md), [docs/CAPABILITIES.md](docs/CAPABILITIES.md) (what is verified and what is not)

## Honest status

Collecting from the public Stack Exchange API, from the built-in demo data, and from an internal site's Swagger-documented REST API (tested against a mock of such a site, not yet against a real one) works. See [docs/CAPABILITIES.md](docs/CAPABILITIES.md).
