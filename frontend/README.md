# QuestionCrawler: frontend

React 19 + TypeScript + Vite. A visual, self-explaining app: Overview, Connect, Collect, Explore, Question, Organize, Settings, Help.

```bash
npm install
npm run dev      # http://localhost:5173, proxies /api to the backend (VITE_BACKEND_URL, default http://localhost:8080)
npm run build    # type-check + production bundle in dist/
npm run lint
```
Normally you start everything from the repository root with `npm start`.

Structure: `src/api` (all HTTP), `src/types` (DTOs), `src/lib` (formatting, glossary), `src/components` (UI kit in `ui/`, route map,
evidence cards, safe Markdown), `src/pages` (one file per screen), `src/styles` (design tokens, components, layout, pages).
Design tokens (milk-white canvas, ink text, one indigo accent) live in `src/styles/tokens.css`.
Crawled text is never injected as HTML: it is rendered through a small safe Markdown renderer.
