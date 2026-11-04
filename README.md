# QuestionCrawler

Internal Q&A knowledge assistant: collect questions/answers from an internal
Stack-Overflow-style site by tag, classify them by functionality, store them, and
make them searchable with source-linked evidence.

## Repository layout

| Directory   | Contents                                   | Stack                             | Build/run from |
|-------------|--------------------------------------------|-----------------------------------|----------------|
| `backend/`  | REST API, collection, storage, search      | Java 21, Spring Boot 3.5, Gradle  | `backend/`     |
| `frontend/` | Web UI                                     | React 19, TypeScript, Vite        | `frontend/`    |

The two projects are fully independent: separate build tools, separate dependencies,
no shared source. The only contract between them is the HTTP API under `/api`.

## Quick start

Prerequisites: JDK 21, Node.js 20+ (Gradle is only needed once, to create the wrapper).

```bash
# Terminal 1 – backend
cd backend
gradle wrapper --gradle-version 8.14   # one-time
./gradlew bootRun                      # Windows: .\gradlew.bat bootRun

# Terminal 2 – frontend
cd frontend
npm install
npm run dev                            # http://localhost:5173
```

Open http://localhost:5173 – the home page shows the result of `GET /api/health`.

## Status

Scaffold only. Implementation (source adapters, sync, classification, storage,
search, UI) is the next phase.
