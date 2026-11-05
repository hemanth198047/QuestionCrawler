# Testing against Stack Overflow (public Stack Exchange API)

`source.type=stackexchange` collects from **stackoverflow.com through its public, documented API**
(`https://api.stackexchange.com/2.3`). It does not scrape HTML and needs no login. It is meant for testing and for
other *public* Stack Exchange sites; your private internal site still needs its own adapter ([LIVE_INTEGRATION.md](LIVE_INTEGRATION.md)).

## Run it

Config for this test: [`config/stackoverflow-test.properties`](../config/stackoverflow-test.properties)
(tag "Artificial Intelligence" -> `artificial-intelligence`, last 30 days).

```bash
# Linux/macOS
scripts/qc.sh --config config/stackoverflow-test.properties doctor --connect   # reachability + remaining API quota
scripts/qc.sh --config config/stackoverflow-test.properties sync               # first run: collects the last 30 days
scripts/qc.sh --config config/stackoverflow-test.properties status
scripts/qc.sh --config config/stackoverflow-test.properties search "overfitting validation loss"
scripts/qc.sh --config config/stackoverflow-test.properties search "overfitting" --max-question-age 14d --max-answer-age 7d
```
```powershell
# Windows PowerShell
scripts\qc.ps1 --config config\stackoverflow-test.properties doctor --connect
scripts\qc.ps1 --config config\stackoverflow-test.properties sync
scripts\qc.ps1 --config config\stackoverflow-test.properties search "overfitting validation loss"
```
Override the window for one run: `sync --max-question-age 7d` (smaller) or `sync --max-question-age 1y` (bigger; use an application key).
To use the tag as typed: `sync --tag "Artificial Intelligence"`.

## Getting enough data for real search results

A 30-day window of a niche tag can hold only a handful of questions (a first live test collected 4), so most searches find nothing.
Widen the window, then search for words that actually occur in the posts:

```bash
scripts/qc.sh --config config/stackoverflow-test.properties sync --full --max-question-age 1y
scripts/qc.sh --config config/stackoverflow-test.properties status                       # how many threads/answers
scripts/qc.sh --config config/stackoverflow-test.properties categories                   # what was collected, by category
scripts/qc.sh --config config/stackoverflow-test.properties search "neural network training"
```
Use `--full` the first time you widen a window that was already synced with an older build (it ignores the "changed since" bound);
with the current build a plain `sync` after widening also backfills automatically. A year of this tag costs roughly 2-3 API requests
per 100 questions plus the answer batches, well inside the anonymous quota; `doctor --connect` shows what is left.

## What the adapter does (from the API documentation)

- Lists `GET /questions?site=stackoverflow&tagged=artificial-intelligence&sort=activity&order=asc&filter=withbody&pagesize=100`.
  `fromdate` carries the collection window (creation date) and `min` carries the incremental "changed since" (last activity).
- Question `modifiedAt` = last activity date, so a **new answer on an old question** is noticed by incremental syncs.
- Quota-friendly: question bodies come from the listing, and the answers of a whole listing page are fetched in **one batched call**
  (`/questions/id1;id2;.../answers`). A page of 100 questions costs about 2-3 requests instead of 200.
- Honours the API's `backoff` field, handles throttling (`error_id` 502) with Retry-After, and **stops with a clear message
  when the daily quota is exhausted** instead of sleeping for hours.
- Tags: exact match. Several tags: `ANY` runs one pass per tag, `ALL` uses the API's AND list (max 5). Partial matching is not supported.

## Limits to be aware of

| Item | Detail |
|---|---|
| Quota | About 300 requests/day anonymous, 10,000 with a free application key (`STACKEXCHANGE_KEY` environment variable). `doctor --connect` shows the remaining quota. |
| Comments | Not collected (extra quota). |
| Deleted questions | The API returns an empty result for them, which cannot be told apart from "never existed", so a full reconciliation marks them `UNKNOWN` (kept, flagged), never `DELETED`. |
| Size | The tag has many thousands of questions. Use the age window; a full history needs a key and several runs. |
| Verification | The adapter is tested against a local mock of the documented API. It has **not** been run against the live service from the authoring sandbox (no outbound access). The first live run is the real test: send me the report if anything differs. |

## Licensing and terms

Stack Overflow content is licensed **CC BY-SA** and the API has its own terms of use. Keep the attribution links (every evidence
record carries its original URL), and review the Stack Exchange API and data-use terms before going beyond a small test or any
shared use. This project uses the data for local retrieval only (no model training).
