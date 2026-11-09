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
| **Settings** | Every setting | Four sections, plain explanations, problems shown beside the field with a fix, *Show advanced*, a live preview of the settings file, plus **Rebuild search index** and delete-by-tag under storage |
| **Help** | Concepts | Plain-word glossary and common questions; `(i)` hints appear across the app |

## How Connect and Settings are organised

Both follow the same order, and show only what applies to the chosen system and choices:

1. **Your source**: which system (Demo, Stack Overflow / Stack Exchange, Confluence, Jive, SharePoint, internal Q&A), and its address.
   For Confluence any page or space address can be pasted.
2. **Signing in**: only the sign-in methods that work for that system (Confluence: *Sign in with my browser* (recommended),
   *Company token service (Kerberos)*, *Email + API token*, *Access token*). Only the fields of the chosen method are shown.
3. **What to collect**: spaces (Confluence, with *Show my spaces*), places (Jive), tags/labels, and how far back.
4. **Company network** (folded away; only if needed): web proxy and company certificate.

Settings adds *How collecting behaves* and *Where everything is saved*. Rarely needed options stay behind *Show advanced*.

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
| `GET/PUT /taxonomy`, `POST /taxonomy/try`, `POST /taxonomy/reset`, `POST /reindex` | Organize; Settings → Where is everything saved? (Rebuild search index) |
| `POST /store/delete-by-tag` (`{ "tag", "confirm": true }`) | Settings → Where is everything saved? |

## Developing the UI without the backend

`backend/core/src/test/java/.../DevApiServer.java` serves the same routes over the real logic without Spring. Compile the
core module, then run `DevApiServer <port> <configFile>` and start Vite with `VITE_BACKEND_URL=http://127.0.0.1:<port>`.

## Find mode: opening a page from a search

When you open a result from **Explore**, the page opens in *find mode* with your question:

- **The "Looking for" bar** stays at the top of the page while you scroll. It shows how many places on the page match ("1 of 3
  places"). Use ▲ ▼, or the keys `n` / `p`, to jump between them. You can change the words in the bar (press Enter), or clear
  them with ✕. The `f` key puts the cursor in the bar.
- **"Where this page answers …"** lists the 3 best places as short previews, each with its section. Click one to jump to it.
- The page opens at the best place, in the section the search result came from.
- **Spotlight** (the default): matching paragraphs, steps, tables and code have a yellow edge and your words are highlighted;
  the rest of the page is faded. Hover over faded text to read it.
- **Only matches** shows just the matching places and their headings. **Whole page** shows everything with your words still
  highlighted.
- **On this page** marks the sections with matches and how many each one has; the other sections are faded.

The bar is also on pages you open from Organize. Type what you're looking for in it to use find mode there.
