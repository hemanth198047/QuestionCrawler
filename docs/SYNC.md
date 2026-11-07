# Synchronization

## Modes

| Command | What it does | What it cannot do |
|---|---|---|
| `sync` (no checkpoint yet) | Initial collection: lists the whole tag scope, fetches every question and all answer pages | |
| `sync` (checkpoint exists) | Incremental: lists items changed since `high-water mark - overlap`, re-fetches only those whose modified time differs | Cannot see deletions, revoked access, tag removals, or answers added without a question timestamp change |
| `sync --full` | Full reconciliation: re-reads the whole scope, compares content hashes, **probes stored questions missing from the listing** | Still limited to what the credentials can see |

If the source cannot filter by modified time, the engine lists everything and skips unchanged items by comparing timestamps.
Run `sync --full` regularly (for example weekly).

## Resumability

Listing progress is saved after every page (`state/run-<scope>.json`); each thread is saved as soon as it is fetched.
After a crash or Ctrl-C, run `sync` again: it resumes the same run, does not repeat the listing, and skips threads already
fetched by that run. The checkpoint advances only after a run with **zero failures**, so failed items are retried next time.

## Statuses (never inferred from absence alone)

| Status | Meaning | Searchable |
|---|---|---|
| `ACTIVE` | Present and matching the scope | yes |
| `NO_LONGER_MATCHING` | Still readable but tags no longer match the scope | yes |
| `UNAVAILABLE` | Temporary failure while probing; old content kept | yes (flagged) |
| `UNKNOWN` | Source said "not found" without saying deleted or hidden | yes (flagged) |
| `DELETED` | Source explicitly reported deletion (for example HTTP 410) | **no** |
| `INACCESSIBLE` | Source explicitly refused access (for example HTTP 403) | **no** |

`DELETED` and `INACCESSIBLE` content leaves search immediately and is purged after `sync.retentionDays`
(0 = immediately; the thread file keeps only a tombstone).

## Reports and completeness

Each run writes `state/reports/<runId>.json` and prints a summary: scope, pages, counts, failures, status changes, warnings,
limitations and a **completeness statement**. "Complete for the accessible scope" is only claimed after a full reconciliation
that read every listing page and verified every question with no failures. Otherwise the report says `INCREMENTAL`, `PARTIAL`
or `NOT_COMPLETE`.

## Optional automatic refresh

Nothing is scheduled automatically. Choose one:

**Windows Task Scheduler** (daily incremental at 06:00; weekly full on Sunday 05:00). Run in a PowerShell with the token
provided in a way your organization approves (for example a machine-scoped environment variable for the task's account):
```powershell
$qc = "C:\path\to\repo\scripts\qc.ps1"
schtasks /Create /TN "QuestionCrawler Incremental" /SC DAILY /ST 06:00 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File `"$qc`" sync"
schtasks /Create /TN "QuestionCrawler Full" /SC WEEKLY /D SUN /ST 05:00 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File `"$qc`" sync --full"
schtasks /Delete /TN "QuestionCrawler Incremental" /F    # to remove
```

**Linux cron** (`crontab -e`; token from a root-readable env file, not the crontab):
```cron
0 6 * * *  . /etc/questioncrawler.env && /opt/repo/scripts/qc.sh sync        >> /var/log/questioncrawler.log 2>&1
0 5 * * 0  . /etc/questioncrawler.env && /opt/repo/scripts/qc.sh sync --full >> /var/log/questioncrawler.log 2>&1
```

**Built-in scheduler of the web app:** set `sync.scheduler.enabled=true` and `sync.scheduler.intervalMinutes`; it runs
incremental syncs and a full reconciliation every 7 days while the web app is running. Off by default.

Only one sync can run at a time per store (OS file lock); a second one fails immediately with a clear message.


## No limit on how much is collected

A collection reads **every** question and answer that matches (every page of the list, every answer page). There is no
page or item limit. Instead, after every **100,000 questions and answers** the collection pauses and asks:

- **In the app (Collect):** *"100,000 questions and answers collected so far. Continue?"* with **Continue collecting** and
  **Stop here**. Stopping keeps everything collected; starting Collect again continues where it stopped. An unanswered
  question stops the run after 24 hours.
- **On the command line:** `sync` asks `Continue? [y/N]`; `sync --yes` always continues (for scripts and schedules).
