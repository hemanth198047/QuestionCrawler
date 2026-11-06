import { CheckCheck, Download, Eye, FolderSync, Layers, ListChecks, Play, RefreshCw, Search, ShieldCheck, Zap } from 'lucide-react'
import { useEffect, useState } from 'react'
import { getSyncReports, getSyncState, startSync } from '../api'
import { PageHeader } from '../components/PageHeader'
import { useOverview } from '../components/OverviewContext'
import { Badge, Banner, Button, Card, Chip, EmptyState, InfoTip, ProgressBar, Segmented, Skeleton, useToast } from '../components/ui'
import { dateOnly, duration, number, plainCompleteness, timeAgo } from '../lib/format'
import { go, useRemote } from '../lib/useRemote'
import type { SyncProgress, SyncReport, SyncState } from '../types/api'

const WINDOWS = [
  { value: '7d', label: '1 week' }, { value: '30d', label: '1 month' }, { value: '90d', label: '3 months' },
  { value: '1y', label: '1 year' }, { value: '', label: 'Everything' },
]

const PHASES: { id: SyncProgress['phase'][]; label: string; icon: React.ReactNode }[] = [
  { id: ['LISTING'], label: 'Look up', icon: <Eye size={20} /> },
  { id: ['FETCHING'], label: 'Download', icon: <Download size={20} /> },
  { id: ['RECONCILING'], label: 'Verify', icon: <ShieldCheck size={20} /> },
  { id: ['FINISHING'], label: 'Organize', icon: <Layers size={20} /> },
]

function PhaseTracker({ p, full }: { p: SyncProgress | null; full: boolean }) {
  const order = ['LISTING', 'FETCHING', 'RECONCILING', 'FINISHING', 'DONE']
  const idx = p ? order.indexOf(p.phase === 'FAILED' ? 'FINISHING' : p.phase) : 0
  const phases = full ? PHASES : PHASES.filter((x) => x.label !== 'Verify')
  return (
    <ol className="phases">
      {phases.map((ph) => {
        const myIdx = order.indexOf(ph.id[0])
        const state = p?.phase === 'DONE' || idx > myIdx ? 'done' : idx === myIdx ? 'now' : 'next'
        return <li key={ph.label} className={`phase ${state}`}><span className="phase-dot">{state === 'done' ? <CheckCheck size={20} /> : ph.icon}</span><span>{ph.label}</span></li>
      })}
    </ol>
  )
}

function LivePanel({ state, startedAt }: { state: SyncState; startedAt: string | null }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => { const t = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(t) }, [])
  const p = state.progress
  const elapsed = startedAt ? now - Date.parse(startedAt) : 0
  const full = state.mode === 'FULL'
  const known = p && p.toFetch > 0
  return (
    <Card className="live rise">
      <div className="spread">
        <div className="row"><span className="live-pulse" /><h3>{full ? 'Full check' : 'Quick update'} in progress</h3></div>
        <span className="muted small">{duration(elapsed)} elapsed</span>
      </div>
      <PhaseTracker p={p} full={full} />
      <ProgressBar value={known ? p!.fetched : 0} max={known ? p!.toFetch : 100} striped={!known} />
      <p className="live-msg">{p?.message ?? 'Starting…'}</p>
      <div className="counters">
        <div><b>{number(p?.questionsFound ?? 0)}</b><span>questions found</span></div>
        <div><b>{number(p?.fetched ?? 0)}{known ? <small>/{p!.toFetch}</small> : null}</b><span>downloaded</span></div>
        <div><b>{number(p?.added ?? 0)}</b><span>new</span></div>
        <div><b>{number(p?.updated ?? 0)}</b><span>changed</span></div>
        <div className={p && p.failures > 0 ? 'bad' : ''}><b>{number(p?.failures ?? 0)}</b><span>problems</span></div>
      </div>
    </Card>
  )
}

function windowUsed(r: SyncReport): string {
  const l = r.limitations.find((x) => x.startsWith('Collection window'))
  const m = l ? /\((\w+) ago\)/.exec(l) : null
  return m ? `last ${m[1]}` : 'everything'
}

function Result({ r, onEverything }: { r: SyncReport; onEverything: () => void }) {
  const c = plainCompleteness(r.completeness)
  const nothing = r.questionsListed === 0 && r.outcome !== 'FAILED'
  const limited = windowUsed(r) !== 'everything'
  const tone = r.outcome === 'COMPLETE' ? 'ok' : r.outcome === 'PARTIAL' ? 'warn' : 'bad'
  return (
    <Card className={`result result-${tone} rise`} id="collect-result">
      <div className="spread">
        <div className="row"><Badge tone={nothing ? 'warn' : tone}>{nothing ? 'Nothing collected' : r.outcome === 'COMPLETE' ? 'Finished' : r.outcome === 'PARTIAL' ? 'Finished with problems' : 'Stopped'}</Badge><span className="muted small">{r.mode === 'FULL' ? 'Full check' : 'Quick update'} · {timeAgo(r.finishedAt)} · time window: {windowUsed(r)} · tags: {r.tags.join(', ')}</span></div>
        <div className="row"><Button size="sm" icon={<Search size={15} />} onClick={() => go('/explore')}>Explore the results</Button></div>
      </div>
      <div className="counters" style={{ marginTop: 14 }}>
        <div><b>{r.added}</b><span>new</span></div><div><b>{r.updated}</b><span>changed</span></div><div><b>{r.unchanged}</b><span>unchanged</span></div>
        <div><b>{r.statusChanged}</b><span>no longer visible</span></div><div className={r.failures.length ? 'bad' : ''}><b>{r.failures.length}</b><span>problems</span></div>
      </div>
      {nothing && (
        <Banner tone="warn" title="The site returned no questions"
          action={<div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            {limited && <Button size="sm" variant="primary" onClick={onEverything}>Choose Everything</Button>}
            <Button size="sm" onClick={() => go('/connect')}>Find the tag on the site</Button></div>}>
          <p>Nothing matched the tag{r.tags.length === 1 ? '' : 's'} <b>{r.tags.join(', ')}</b>{limited ? <> within the <b>{windowUsed(r)}</b> window</> : ' (no time limit)'}.</p>
          <ul className="plainlist">
            {limited && <li>A time window hides older questions. Choose <b>Everything</b> above and start again.</li>}
            <li>The tag may be spelled differently on the site (for example in capitals). Use <b>Find tags on the site</b> on the Connect screen.</li>
            <li>Check what the site really answers with <b>See what the API really answers</b> (try <code>/questions?pagesize=15</code>).</li>
          </ul>
        </Banner>)}
      {!(nothing && c.tone === 'ok') && <Banner tone={c.tone === 'bad' ? 'bad' : c.tone === 'ok' ? 'ok' : c.tone === 'warn' ? 'warn' : 'info'} title={c.label}>{c.text}</Banner>}
      {r.abortReason && <Banner tone="bad" title="Why it stopped">{r.abortReason}</Banner>}
      {r.warnings.filter((w) => !w.startsWith('No questions were returned')).map((w) => <Banner key={w} tone="info">{w}</Banner>)}
      {r.limitations.filter((l) => l.startsWith('Collection window')).map((l) => <Banner key={l} tone="info" title="Time window">{l}</Banner>)}
      {r.statusChanges.length > 0 && (<div><h4>Questions that changed visibility</h4><ul className="plainlist">{r.statusChanges.map((s) => <li key={s.questionId}><b>#{s.questionId}</b> {s.from.toLowerCase().replace('_', ' ')} → <b>{s.to.toLowerCase().replace('_', ' ')}</b> <span className="muted">· {s.reason}</span></li>)}</ul></div>)}
      {r.failures.length > 0 && (<div><h4>Questions that could not be downloaded</h4><ul className="plainlist">{r.failures.slice(0, 8).map((f) => <li key={f.questionId}><b>#{f.questionId}</b> {f.message}</li>)}</ul><p className="muted small">They are retried automatically on the next update.</p></div>)}
    </Card>
  )
}

export default function CollectPage() {
  const { overview: o, reload } = useOverview()
  const toast = useToast()
  const [mode, setMode] = useState<'quick' | 'full'>('quick')
  const [win, setWin] = useState<string | null>(null)
  const [state, setState] = useState<SyncState | null>(null)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const reports = useRemote((s) => getSyncReports(s), [state?.running])

  const finished = (s: SyncState) => {
    if (s.lastError && !s.lastReport) toast('bad', `The run could not finish: ${s.lastError}`)
    else if (s.lastReport) {
      const r = s.lastReport
      if (r.outcome === 'FAILED') toast('bad', `The run stopped: ${r.abortReason ?? 'see the details below'}`)
      else if (r.questionsListed === 0) toast('info', 'The run finished, but the site returned no questions. See the explanation below.')
      else toast(r.outcome === 'COMPLETE' ? 'ok' : 'info', r.outcome === 'COMPLETE' ? `Collection finished: ${r.added} new, ${r.updated} changed` : 'Collection ended with problems')
    }
    window.setTimeout(() => document.getElementById('collect-result')?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 250)
  }
  useEffect(() => { getSyncState().then(setState).catch(() => undefined) }, [])
  useEffect(() => { if (o && win === null) setWin(o.window ?? '') }, [o, win])
  useEffect(() => {
    if (!state?.running) return
    const t = window.setInterval(() => getSyncState().then((s) => { setState(s); if (!s.running) { reload(); reports.reload(); finished(s) } }).catch(() => undefined), 900)
    return () => window.clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state?.running])

  if (!o) return <div className="stack"><Skeleton h={80} /><Skeleton h={300} r={18} /></div>
  const noTags = o.tags.length === 0
  const allTags = o.tags.some((t) => ['all', '*'].includes(t.toLowerCase()))
  const isSE = o.sourceType === 'stackexchange'
  const start = async () => {
    setStarting(true); setStartError(null)
    try {
      const s = await startSync(mode === 'full', win ?? '')
      setState(s); reload()
      if (!s.running) finished(s) // it ended before the first progress check
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Could not start'
      setStartError(message); toast('bad', message)
    } finally { setStarting(false) }
  }
  const running = state?.running ?? false

  return (
    <div className="stack" style={{ gap: 28 }}>
      <PageHeader eyebrow="Collect" title="Download questions & answers" subtitle="Pick how far back to look and how thorough to be. You can watch it happen, and leave this page: it keeps running." />
      {noTags && <Banner tone="warn" title="No tags chosen yet" action={<Button onClick={() => go('/connect')}>Choose tags</Button>}>We need at least one tag to know which questions to collect.</Banner>}

      <Card>
        <div className="row" style={{ marginBottom: 18 }}><FolderSync size={20} color="var(--brand)" /><h3>What will be collected</h3></div>
        <div className="scope">
          <div><span className="muted small">Source</span><strong>{o.sourceLabel}</strong></div>
          <div><span className="muted small">Tags <InfoTip term="tag" /></span><div className="row" style={{ gap: 6 }}>{o.tags.length ? o.tags.map((t) => <Chip key={t} active={['all', '*'].includes(t.toLowerCase())}>{['all', '*'].includes(t.toLowerCase()) ? 'All tags (every question)' : t}</Chip>) : <span className="muted">none yet</span>}</div></div>
          <div><span className="muted small">Questions from <InfoTip term="window" /></span>
            <Segmented label="Time window" value={WINDOWS.some((w) => w.value === (win ?? '')) ? (win ?? '') : '__custom'} onChange={(v) => setWin(v)} options={WINDOWS} /></div>
        </div>
        {allTags && (win ?? '') === '' && !isSE && <div style={{ marginTop: 14 }}><Banner tone="info" title="Every question, from all time">That can be a lot. To try it out first, choose a shorter time window (for example 1 year); you can widen it later.</Banner></div>}
        {isSE && (win ?? '') === '' && <div style={{ marginTop: 14 }}><Banner tone="warn" title="Everything could be a lot">Popular tags can hold many thousands of questions, and anonymous use is limited to about 300 requests a day. A shorter window is a good first step.</Banner></div>}

        <hr className="divider" />
        <h4 style={{ marginBottom: 12 }}>How thorough?</h4>
        <div className="choice-grid">
          <button type="button" className={`choice ${mode === 'quick' ? 'on' : ''}`} onClick={() => setMode('quick')} aria-pressed={mode === 'quick'}>
            <span className="choice-icon"><Zap size={20} /></span><strong>Quick update <InfoTip term="quick" /></strong>
            <span className="hint">Fetches only what is new or changed since last time. The first time, it downloads everything in your window.</span>
          </button>
          <button type="button" className={`choice ${mode === 'full' ? 'on' : ''}`} onClick={() => setMode('full')} aria-pressed={mode === 'full'}>
            <span className="choice-icon"><ListChecks size={20} /></span><strong>Full check <InfoTip term="full" /></strong>
            <span className="hint">Re-reads everything in the window and notices deleted, hidden or re-tagged questions. Slower; do it now and then.</span>
          </button>
        </div>
        <div className="row" style={{ marginTop: 22 }}>
          <Button variant="primary" size="lg" icon={<Play size={18} />} busy={starting || running} disabled={noTags} onClick={start}>{running ? 'Running…' : mode === 'full' ? 'Start full check' : 'Start quick update'}</Button>
          {o.freshness.lastSuccessfulSyncAt && <span className="muted small">Last successful update {timeAgo(o.freshness.lastSuccessfulSyncAt)}</span>}
        </div>
        <p className="muted small" style={{ marginTop: 12 }}>This will collect {allTags ? <b>every question on the site (all tags)</b> : <>questions tagged <b>{o.tags.join(', ') || '…'}</b></>} from <b>{(win ?? '') === '' ? 'all time' : `the last ${win}`}</b> as a <b>{mode === 'full' ? 'full check' : 'quick update'}</b>.</p>
        {startError && <div style={{ marginTop: 12 }}><Banner tone="bad" title="The run could not be started">{startError}</Banner></div>}
      </Card>

      {state && running && <LivePanel state={state} startedAt={state.startedAt} />}
      {state?.lastError && !running && <Banner tone="bad" title="The last run could not finish">{state.lastError}</Banner>}
      {state?.lastReport && !running && <Result r={state.lastReport} onEverything={() => { setWin(''); window.scrollTo({ top: 0, behavior: 'smooth' }); toast('info', 'Time window set to Everything. Press Start again.') }} />}

      <section>
        <div className="spread" style={{ marginBottom: 14 }}><h2>History</h2>{reports.refreshing && <RefreshCw size={16} className="spin" color="var(--ink-3)" />}</div>
        {reports.kind === 'ok' && reports.data.length === 0 && <EmptyState icon={<Download size={28} />} title="No runs yet">Start your first update above. Each run will be listed here with what changed.</EmptyState>}
        {reports.kind === 'ok' && reports.data.length > 0 && (
          <Card pad={false}>
            <ul className="history">
              {reports.data.map((r) => (
                <li key={r.runId}>
                  <details>
                    <summary>
                      <Badge tone={r.outcome === 'COMPLETE' ? 'ok' : r.outcome === 'PARTIAL' ? 'warn' : 'bad'}>{r.outcome === 'COMPLETE' ? 'Finished' : r.outcome === 'PARTIAL' ? 'Problems' : 'Stopped'}</Badge>
                      <strong>{r.mode === 'FULL' ? 'Full check' : 'Quick update'}</strong>
                      <span className="muted">{dateOnly(r.finishedAt)} · {timeAgo(r.finishedAt)}</span>
                      <span className="hist-nums">{r.added} new · {r.updated} changed{r.failures.length ? ` · ${r.failures.length} problems` : ''}</span>
                    </summary>
                    <div className="hist-body"><p className="muted small">{plainCompleteness(r.completeness).text}</p>{r.statusChanges.map((s) => <p key={s.questionId} className="small">#{s.questionId}: {s.from.toLowerCase().replace('_', ' ')} → {s.to.toLowerCase().replace('_', ' ')}</p>)}</div>
                  </details>
                </li>
              ))}
            </ul>
          </Card>
        )}
      </section>
    </div>
  )
}
