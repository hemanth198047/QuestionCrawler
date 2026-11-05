# The app, screen by screen

Run `npm start` and open http://localhost:5173. The left bar always shows how many questions are stored and how fresh they are.

| Screen | Purpose | Highlights |
|---|---|---|
| **Overview** | Where things stand, what next | Journey of four steps with the next one highlighted; counts; topic donut; recent activity; plain-language warnings with a one-click *Re-sort* |
| **Connect a source** | Choose a site and prove we can reach it | Starting-point cards; plain forms; **route map** of the six stages; verdict with score ring; recommended route; requirements checklist; message template for the site owners |
| **Collect** | Download questions and answers | Time window (1 week to everything); *Quick update* vs *Full check*; live phase tracker with counters; plain-language result and history |
| **Explore** | Ask your knowledge | One search box; filters for topic, tag and **age of question / age of answer** (sliders); match strength; evidence cards with age, votes, accepted badge and source link; copy a prompt for Copilot |
| **Question** | The full thread | Accepted answer pinned and highlighted; ages everywhere; topic chips you can change; "why these topics?" |
| **Organize** | Topics and their rules | Browse topics by size; edit rules visually; *Try it* shows which topics a sample question gets and which rules matched; re-sort existing questions |
| **Settings** | Every setting | Four sections, plain explanations, problems shown beside the field with a fix, *Show advanced*, and a live preview of the settings file |
| **Help** | Concepts | Plain-word glossary and common questions; `(i)` hints appear across the app |

## The Connect screen's six stages

| Stage | Question it answers | Typical "what is needed" |
|---|---|---|
| Reach the site | Can this computer reach it? | The right host name, VPN, proxy settings, firewall access |
| Secure connection | Is the certificate trusted? | The company root certificate (a `.pem` file) |
| Sign-in | Do we need to log in? | A read-only token or service account (we never bypass logins or MFA) |
| Way to get the data | Is there an API or export? | API documentation, sample responses or a data export |
| Limits and protection | Are there rate limits or bot protection? | An application key, or an approved route if bots are blocked |
| Your topic | Does the tag exist? | The exact tag name |

Each needed item says **why** and **who** can provide it. The *route* summarizes what to do: official API (supported),
API found but connector not built, sign-in required, readable pages only, script-built pages, or discouraged.

## API (what the screens call)

All under `/api`. The Spring controllers and the test-scope dev server (`DevApiServer`) expose the same routes.

| Route | Used by |
|---|---|
| `GET /overview` | Overview, sidebar |
| `GET /config`, `POST /config/validate`, `PUT /config`, `GET /config/presets` | Settings, Connect |
| `POST /connection/check`, `GET /connection/last` | Connect |
| `POST /sync`, `GET /sync/status`, `GET /sync/reports` | Collect |
| `GET /search`, `GET /questions/{id}`, `PUT/DELETE /questions/{id}/categories` | Explore, Question |
| `GET /categories`, `GET /categories/{id}` | Organize, Explore |
| `GET/PUT /taxonomy`, `POST /taxonomy/try`, `POST /taxonomy/reset`, `POST /reindex` | Organize |

## Developing the UI without the backend

`backend/core/src/test/java/.../DevApiServer.java` serves the same routes over the real logic without Spring. Compile the
core module, then run `DevApiServer <port> <configFile>` and start Vite with `VITE_BACKEND_URL=http://127.0.0.1:<port>`.
