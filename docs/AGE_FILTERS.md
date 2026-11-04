# Filtering by age of questions and answers

Ages are relative: `12h`, `30d`, `2w`, `6m` (calendar months), `1y`. They are measured **from now back to the post's creation time**.
Posts whose creation time is unknown are excluded whenever a filter on that kind of post is active (an age is never guessed).

| Option (CLI) / parameter (API, UI) | Keeps |
|---|---|
| `--max-question-age 90d` / `maxQuestionAge` | questions created **within** the last 90 days (newer than) |
| `--min-question-age 1y` / `minQuestionAge` | questions **older than** 1 year |
| `--max-answer-age 30d` / `maxAnswerAge` | answers created within the last 30 days |
| `--min-answer-age 6m` / `minAnswerAge` | answers older than 6 months |

Rules:
- A **question** filter applies to the whole thread: the question and all of its answers are excluded together.
- An **answer** filter removes only answer evidence; the question itself still appears (it may carry the problem statement).
- Filters combine (AND). Min and max together make a range, e.g. `--max-question-age 1y --min-question-age 90d`.
- Filters are applied inside the search query (numeric range fields), so you still get the top results from the matching posts,
  not a truncated list that was filtered afterwards.
- Every evidence record shows `createdAt` and `questionCreatedAt`, and the response echoes the active `ageFilter`.

```bash
scripts/qc.sh search "token refresh" --max-question-age 1y --max-answer-age 6m
scripts/qc.sh search "deprecated api" --min-question-age 2y            # old discussions, to spot outdated guidance
scripts/qc.sh categories data-ingestion --max-question-age 90d         # browse recent questions of a category
```
API: `GET /api/search?q=token%20refresh&maxQuestionAge=1y&maxAnswerAge=6m`, `GET /api/categories/{id}?maxQuestionAge=90d`.
The web UI has an "Age filters" section on the search page and a "newer than" box on category pages.

## Collection window (a different thing)

`sync --max-question-age 30d` (or `sync.maxQuestionAge=30d`) limits what is **collected**: only questions created in that window are
fetched. Older questions are out of scope (not deleted: already stored ones stay, and a full reconciliation does not treat them as
missing). Reports state the window, and completeness is never claimed for the full history.
Use retrieval filters (above) to narrow what is *shown*; use the collection window to bound what is *stored* (for example with a small API quota).

## Index format

Age filtering needs a newer index format. An index built by an older version is refused with "run `reindex`".
