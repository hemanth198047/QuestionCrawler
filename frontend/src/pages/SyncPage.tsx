import { useEffect, useState } from 'react'
import { getStatus, getSyncReports, getSyncState, reindex, startSync } from '../api/knowledge'
import { ErrorBox, FreshnessNote } from '../components/Freshness'
import { useRemote } from '../hooks'

export default function SyncPage() {
  const status = useRemote((signal) => getStatus(signal), [])
  const reports = useRemote((signal) => getSyncReports(signal), [])
  const state = useRemote((signal) => getSyncState(signal), [])
  const [message, setMessage] = useState<string | null>(null)
  const running = state.kind === 'ok' && state.data.running

  // Poll while a background synchronization is running.
  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => { state.reload(); status.reload(); reports.reload() }, 2000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running])

  const run = async (full: boolean) => {
    setMessage(null)
    try {
      await startSync(full)
      setMessage(full ? 'Full reconciliation started.' : 'Incremental synchronization started.')
      state.reload()
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Could not start synchronization')
    }
  }

  return (
    <section>
      <h2>Synchronization</h2>
      <p className="muted">Synchronization only happens when you start it here, from the CLI, or from the optional scheduler. Searching never triggers it.</p>
      <p>
        <button disabled={running} onClick={() => run(false)}>Sync now (incremental)</button>{' '}
        <button disabled={running} onClick={() => run(true)}>Full reconciliation</button>{' '}
        <button disabled={running} onClick={() => reindex().then((r) => setMessage(`Reindexed ${r.threads} threads.`)).then(() => status.reload())}>Reindex</button>
      </p>
      {running && <p>A synchronization is running…</p>}
      {message && <p className="notice">{message}</p>}
      {state.kind === 'ok' && state.data.lastError && <ErrorBox message={state.data.lastError} />}
      {status.kind === 'error' && <ErrorBox message={status.message} />}
      {status.kind === 'ok' && (
        <>
          <FreshnessNote f={status.data.freshness} />
          <p>{status.data.threadCount} threads · {status.data.answerCount} answers · index {status.data.indexedDocuments ?? 'missing'} ·
            {' '}{Object.entries(status.data.byStatus).map(([k, v]) => `${k}: ${v}`).join(', ')}</p>
          {status.data.notes.map((n) => <p key={n} className="notice warn">{n}</p>)}
        </>
      )}
      <h3>Recent runs</h3>
      {reports.kind === 'ok' && reports.data.map((r) => (
        <article className="card" key={r.runId}>
          <header className="card-head">
            <span className="badge">{r.mode}</span>
            <span className={`badge ${r.outcome === 'COMPLETE' ? 'ok' : 'warn'}`}>{r.outcome}</span>
            <span className="muted">{r.finishedAt}</span>
          </header>
          <p>Added {r.added}, updated {r.updated}, unchanged {r.unchanged}, status changes {r.statusChanged}, failures {r.failures.length}</p>
          {r.statusChanges.map((c) => <p key={c.questionId} className="muted">{c.questionId}: {c.from} → {c.to} ({c.reason})</p>)}
          {r.failures.map((f) => <p key={f.questionId} className="muted">FAILED {f.questionId}: {f.kind}</p>)}
          <p className="muted">{r.completeness}</p>
        </article>
      ))}
    </section>
  )
}
