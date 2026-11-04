# Using the knowledge store from GitHub Copilot

## How it works

The repository contains an Agent Skill at `.github/skills/internal-qa-knowledge/SKILL.md`. When you ask Copilot (VS Code
**agent mode**) something that matches the skill's description, Copilot loads the skill, which tells it to run
`scripts/qc.sh` / `scripts/qc.ps1` (search, show, status), read the returned evidence, and answer with citations.

The skill is instructions only. It does not run continuously, does not synchronize on its own and is **not guaranteed to
activate on every prompt** (Copilot decides from the skill's description). You can always say "use the internal-qa-knowledge skill".

> Format check: `.github/skills/<name>/SKILL.md` with `name` (matching the folder) and `description` frontmatter matches
> GitHub's documented Agent Skills format. Skills are supported in VS Code agent mode, Copilot CLI and the cloud agent;
> depending on your VS Code version a setting may need enabling and your organization may restrict it.
> **Verify in your own VS Code** (see step 3 below): compatibility with your managed environment is not verified here.

## Step by step

1. Build the CLI and create the store ([SETUP.md](SETUP.md)); run `scripts/qc.sh demo` or a real `sync`.
2. Open this repository in VS Code on the workstation that holds the store.
3. In Copilot Chat choose **Agent** mode. Check the skill is discovered (VS Code lists loaded skills in the chat
   references/ "Used skills"; if absent, check your Copilot/VS Code version and organization policy for Agent Skills).
4. Ask, for example:
   - "Search our internal Q&A for ERR_CONN_TIMEOUT."
   - "How do I configure SSO login according to our internal discussions?"
   - "Find discussions tagged `helm`."
   - "What solutions exist for this deployment failure?" (paste the error)
   - "Browse questions about data ingestion."
5. When Copilot asks to run a terminal command, approve `scripts/qc.sh ...` (or `scripts\qc.ps1 ...`). Prefer approving this
   command pattern rather than blanket terminal approval.
6. Check the answer's links. Every claim should link to an original question/answer URL.
7. Refresh data yourself when needed: `scripts/qc.sh sync` (Copilot will not do this unless you explicitly ask).

## Required permissions and environment

| Need | Why |
|---|---|
| Terminal tool permission for `scripts/qc.sh` / `qc.ps1` | The skill runs the CLI |
| Read access to the storage root | Search reads local files |
| JDK 21 and a built CLI | The CLI is Java |
| Internal-network access | **Only** for `sync`; search/show/status are offline |

A **cloud-hosted Copilot** environment (for example the cloud coding agent) runs on GitHub-hosted machines: it cannot reach
your intranet or your local storage root. Use VS Code agent mode on your workstation.

## Sample retrieval output (real output from the offline demo)

`scripts/qc.sh search "ERR_CONN_TIMEOUT" --format json --top 2` (passages shortened here):

```json
{
 "query": "ERR_CONN_TIMEOUT",
 "resultCount": 2,
 "confidence": "HIGH",
 "freshness": { "lastSuccessfulSyncAt": "2026-11-04T16:56:12Z", "ageHours": 0, "stale": false },
 "evidence": [
  {
   "rank": 1, "type": "answer", "questionId": "1001", "answerId": "2002",
   "title": "Connection timeout when ingesting via the REST API",
   "passage": "In our case the corporate proxy dropped idle connections. Setting `NO_PROXY=orbit.internal` fixed `ERR_CONN_TIMEOUT` for us.",
   "sourceUrl": "https://qa.example.internal/questions/1001#answer-2002",
   "questionUrl": "https://qa.example.internal/questions/1001",
   "categories": ["data-ingestion", "apis-integrations", "troubleshooting-errors"],
   "accepted": false, "votes": 2, "sourceModifiedAt": "2025-01-15T10:00:00Z",
   "score": 23.443, "termCoverage": 1.0, "threadStatus": "ACTIVE", "flags": [], "untrusted": true
  }
 ]
}
```
(`https://qa.example.internal/...` is synthetic demo data.)

## Illustrative Copilot answer (an example of the intended style, NOT real model output)

> **Direct answer:** `ERR_CONN_TIMEOUT` on large ingest batches is most often the client's default 30 s timeout.
>
> **Steps:** raise it in `orbit.yaml` (`ingest.http.timeout: 120s`) - [accepted answer](https://qa.example.internal/questions/1001#answer-2001).
>
> **Other causes mentioned:** a corporate proxy dropping idle connections; the fix reported was `NO_PROXY=orbit.internal`
> - [answer](https://qa.example.internal/questions/1001#answer-2002) (not accepted).
>
> **Caveats:** one answer says the server-side limit is 60 s in Orbit 2.x, so a client timeout above 60 s alone would not
> help ([answer](https://qa.example.internal/questions/1001#answer-2003)); a newer answer says Orbit 2.4 moved it to
> `server.requestTimeout`. My inference, not stated in the sources: check your Orbit version first. Store last synchronized: 2 days ago.

## If there is no evidence

Copilot should say the store lacks sufficient evidence (for example `confidence: NONE`) and not invent an answer.
