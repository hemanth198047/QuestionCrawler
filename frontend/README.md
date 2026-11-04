# QuestionCrawler – Frontend

React 19 + TypeScript + Vite. Talks to the backend only through `src/api/`.

```bash
npm install
npm run dev      # http://localhost:5173, proxies /api -> http://localhost:8080
npm run build    # type-check + production bundle in dist/
```

Layout: `src/api` (HTTP), `src/types` (DTOs), `src/pages`, `src/components`.
