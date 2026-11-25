import { ExternalLink, History, Search, ShieldCheck, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { getSearchAiHistory, getSearchAiStatus, getSearchAiStored, saveSearchAiAddress, searchAiAnswer, searchAiSearch, searchAiSummary, searchAiUseCases } from '../api'
import { ApiError } from '../api/client'
import { Markdown } from '../components/Markdown'
import { PageHeader } from '../components/PageHeader'
import { Badge, Banner, Button, Card, Chip, EmptyState, Skeleton, Spinner } from '../components/ui'
import { timeAgo } from '../lib/format'
import { type AnswerView, type Hit, extractAnswer, extractHits, extractUseCases, plain, safeUrl } from '../lib/searchai'
import { useRemote } from '../lib/useRemote'
import type { SearchAiStatus, SearchAiStored } from '../types/api'

const PAGE_SIZES = [10, 25, 50]
const message = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong')

export default function SearchAiPage() {
  const status = useRemote((s) => getSearchAiStatus(s), [])
  const history = useRemote((s) => getSearchAiHistory(s), [])
  const [address, setAddress] = useState('')
  const [changing, setChanging] = useState(false)
  const [saving, setSaving] = useState(false)
  const [addressError, setAddressError] = useState('')
  const [live, setLive] = useState<SearchAiStatus | null>(null) // newer than the first status after saving the address
  const [query, setQuery] = useState('')
  const [asked, setAsked] = useState('') // the question the shown results belong to
  const [useCase, setUseCase] = useState('')
  const [useCases, setUseCases] = useState<string[]>([])
  const [rows, setRows] = useState(10)
  const [pages, setPages] = useState<Hit[][]>([])
  const [rawPages, setRawPages] = useState<unknown[]>([])
  const [answer, setAnswer] = useState<AnswerView | null>(null)
  const [busy, setBusy] = useState<'' | 'search' | 'answer' | 'skill' | 'more'>('')
  const [error, setError] = useState('')
  const [summaries, setSummaries] = useState<Record<string, string>>({})
  const [summarizing, setSummarizing] = useState('')
  const loadedUseCases = useRef(false)

  const current: SearchAiStatus | null = live ?? (status.kind === 'ok' ? status.data : null)
  const ready = !!current?.signedIn

  useEffect(() => {
    if (!ready || loadedUseCases.current) return
    loadedUseCases.current = true
    searchAiUseCases().then((r) => setUseCases(extractUseCases(r.response))).catch(() => undefined) // optional: the box just stays hidden
  }, [ready])

  const recheck = () => { setLive(null); status.reload() }
  const fail = (e: unknown) => { setError(message(e)); if (e instanceof ApiError && (e.status === 401 || e.status === 502)) recheck() }

  async function saveAddress() {
    setSaving(true); setAddressError('')
    try {
      const s = await saveSearchAiAddress(address.trim())
      setLive(s); setChanging(false); setAddress('')
    } catch (e) { setAddressError(message(e)) } finally { setSaving(false) }
  }

  function showSearch(stored: SearchAiStored, append: boolean) {
    const hits = extractHits(stored.response)
    setRawPages((p) => (append ? [...p, stored.response ?? stored.text] : [stored.response ?? stored.text]))
    setPages((p) => (append ? [...p, hits ?? []] : [hits ?? []]))
  }

  async function runSearch() {
    const q = query.trim(); if (!q) return
    setBusy('search'); setError(''); setAnswer(null); setSummaries({}); setPages([]); setRawPages([]); setAsked(q)
    try {
      const [stored] = await searchAiSearch({ query: q, rows, start: 0, useCase })
      showSearch(stored, false); history.reload()
    } catch (e) { fail(e) } finally { setBusy('') }
  }

  async function runMore() {
    setBusy('more'); setError('')
    try {
      const [stored] = await searchAiSearch({ query: asked, rows, start: pages.length * rows, useCase })
      showSearch(stored, true); history.reload()
    } catch (e) { fail(e) } finally { setBusy('') }
  }

  async function runAnswer() {
    const q = query.trim(); if (!q) return
    setBusy('answer'); setError(''); setAsked(q)
    try {
      setAnswer(extractAnswer((await searchAiAnswer(q, useCase)).response)); history.reload()
    } catch (e) { fail(e) } finally { setBusy('') }
  }

  /** "Run skill": the search results and the AI answer for the same question, requested together and shown together. */
  async function runSkill() {
    const q = query.trim(); if (!q) return
    setBusy('skill'); setError(''); setAsked(q); setSummaries({}); setPages([]); setRawPages([])
    try {
      const [searchStored, answerStored] = await Promise.all([
        searchAiSearch({ query: q, rows, start: 0, useCase }),
        searchAiAnswer(q, useCase),
      ])
      showSearch(searchStored[0], false)
      setAnswer(extractAnswer(answerStored.response))
      history.reload()
    } catch (e) { fail(e) } finally { setBusy('') }
  }

  async function summarize(h: Hit) {
    if (!h.chunkId) return
    setSummarizing(h.chunkId)
    try {
      const r = await searchAiSummary(asked, h.chunkId)
      const a = extractAnswer(r.response)
      setSummaries((s) => ({ ...s, [h.chunkId!]: a.text ?? JSON.stringify(r.response, null, 2) })); history.reload()
    } catch (e) { fail(e) } finally { setSummarizing('') }
  }

  async function reopen(id: string) {
    setError('')
    try {
      const s = await getSearchAiStored(id)
      setQuery(s.params.query ?? ''); setAsked(s.params.query ?? ''); setSummaries({})
      if (s.kind === 'search') { setAnswer(null); showSearch(s, false) }
      else { setPages([]); setRawPages([]); setAnswer(extractAnswer(s.response ?? s.text)) }
    } catch (e) { setError(message(e)) }
  }

  // One chip per kind + question (every "show more" or summary call is saved, but one chip is enough to reopen it).
  const recent = history.kind === 'ok'
    ? history.data.filter((h, i, all) => h.kind !== 'usecases' && all.findIndex((o) => o.kind === h.kind && o.query === h.query) === i).slice(0, 12)
    : []
  const total = pages.reduce((n, p) => n + p.length, 0)
  const hasMore = pages.length > 0 && pages[pages.length - 1].length >= rows
  const host = current?.baseUri.replace(/^https?:\/\//, '').replace(/\/.*$/, '')

  return (
    <div className="stack" style={{ gap: 26 }}>
      <PageHeader eyebrow="MSSearch-AI" title="Search the company's MSSearch-AI"
        subtitle="Use Search, Ask AI, or Run skill. All responses are fetched by the backend and saved on this computer so you can open them again." />

      {status.kind === 'loading' && !live && <Skeleton h={90} r={18} />}
      {status.kind === 'error' && !live && <Banner tone="bad" title="Could not check Search-AI">{status.message}</Banner>}

      {current && (!current.configured || changing) && (
        <Card className="rise">
          <h3>{current.configured ? 'Change the MSSearch-AI address' : 'One-time step: where is MSSearch-AI?'}</h3>
          <p className="muted" style={{ margin: '6px 0 14px' }}>Ask your team for the MSSearch-AI web address, or copy it from the address bar of the search API. You only do this once.</p>
          <form className="row" onSubmit={(e) => { e.preventDefault(); void saveAddress() }}>
            <input className="input" style={{ flex: 1, minWidth: 260 }} type="url" required value={address} onChange={(e) => setAddress(e.target.value)}
              placeholder="https://mssearch-ai.webfarm.ms.com/searchapi" aria-label="MSSearch-AI address" />
            <Button type="submit" variant="primary" busy={saving}>Save and connect</Button>
            {changing && <Button type="button" variant="ghost" onClick={() => { setChanging(false); setAddressError('') }}>Cancel</Button>}
          </form>
          {addressError && <p className="small" style={{ color: 'var(--bad)', marginTop: 10 }}>{addressError}</p>}
        </Card>
      )}

      {current?.configured && !changing && (current.signedIn
        ? <div className="row spread" style={{ flexWrap: 'wrap' }}>
            <div className="row"><Badge tone="ok" icon={<ShieldCheck size={14} />}>Connected</Badge><span className="muted small">{host}{current.principal ? ` · ${current.principal}` : ''}</span></div>
            <Button variant="ghost" size="sm" onClick={() => setChanging(true)}>Change address</Button>
          </div>
        : <Banner tone="bad" title="Not connected to MSSearch-AI yet"
            action={<div className="row"><Button variant="primary" busy={status.refreshing} onClick={recheck}>Check again</Button><Button variant="ghost" onClick={() => setChanging(true)}>Change address</Button></div>}>
            <div>{current.hint ?? current.message}</div>
            <details style={{ marginTop: 8 }}><summary className="small muted">Technical details</summary><div className="small" style={{ marginTop: 6 }}>{current.message}</div></details>
          </Banner>)}

      <Card className="searchcard" style={ready ? undefined : { opacity: 0.55, pointerEvents: 'none' }} aria-disabled={!ready}>
        <form onSubmit={(e) => { e.preventDefault(); void runSearch() }}>
          <div className="searchbar">
            <Search size={22} color="var(--ink-3)" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="What do you want to find or ask?" aria-label="Search-AI query" disabled={!ready} />
            <Button type="submit" variant="primary" size="lg" busy={busy === 'search'} disabled={!ready || !query.trim() || busy !== ''}>Search</Button>
            <Button type="button" size="lg" icon={<Sparkles size={17} />} busy={busy === 'answer'} disabled={!ready || !query.trim() || busy !== ''} onClick={() => void runAnswer()}>Ask AI</Button>
            <Button type="button" size="lg" busy={busy === 'skill'} disabled={!ready || !query.trim() || busy !== ''} onClick={() => void runSkill()}>Run skill</Button>
          </div>
          <div className="row" style={{ marginTop: 12, flexWrap: 'wrap' }}>
            {useCases.length > 0 && (
              <label className="row small muted">Area&nbsp;
                <select className="select" value={useCase} onChange={(e) => setUseCase(e.target.value)} aria-label="Area to search">
                  <option value="">All areas</option>{useCases.map((u) => <option key={u} value={u}>{u}</option>)}
                </select></label>)}
            <label className="row small muted">Results per page&nbsp;
              <select className="select" value={rows} onChange={(e) => setRows(Number(e.target.value))} aria-label="Results per page">
                {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
              </select></label>
          </div>
        </form>
      </Card>

      {error && <Banner tone="bad" title="That did not work">{error}</Banner>}
      {(busy === 'search' || busy === 'answer' || busy === 'skill') && <div className="row muted"><Spinner label={busy === 'answer' ? 'Asking MSSearch-AI…' : busy === 'skill' ? 'Running mssearch skill…' : 'Searching…'} /></div>}

      {answer && (
        <Card className="rise">
          <div className="row" style={{ marginBottom: 10 }}><Sparkles size={19} color="var(--brand)" /><h3>AI answer</h3><Badge tone="info">Written by an AI model</Badge></div>
          {answer.text ? <Markdown text={answer.text} /> : <p className="muted">Search-AI answered, but not with text we could recognise. The full answer is below.</p>}
          {answer.sources.length > 0 && (<>
            <h4 style={{ margin: '16px 0 8px' }} className="muted">Sources</h4>
            <div className="stack-sm">{answer.sources.map((h) => <HitCard key={h.key} hit={h} />)}</div></>)}
          <Raw value={answer.raw} />
        </Card>
      )}

      {pages.length > 0 && (
        <section className="stack" aria-live="polite">
          <h2>{total === 0 ? 'No results' : `${total} result${total === 1 ? '' : 's'} for “${asked}”`}</h2>
          {total === 0
            ? <EmptyState icon={<Search size={30} />} title="MSSearch-AI found nothing for that">Try different words or choose “All areas”. If you expected results, open “Show full answer” to see exactly what came back.</EmptyState>
            : <div className="stack-sm">{pages.flat().map((h) => (
                <HitCard key={h.key} hit={h} summary={h.chunkId ? summaries[h.chunkId] : undefined} summarizing={summarizing === h.chunkId} onSummarize={h.chunkId ? () => void summarize(h) : undefined} />))}</div>}
          {hasMore && <div><Button busy={busy === 'more'} disabled={busy !== ''} onClick={() => void runMore()}>Show more results</Button></div>}
          <Raw value={rawPages.length === 1 ? rawPages[0] : rawPages} label="Show full answer" />
          <p className="muted small">Everything shown comes from MSSearch-AI and is displayed as reading material only. Check the original source before relying on it.</p>
        </section>
      )}

      <Card className="rise">
        <div className="row" style={{ marginBottom: 12 }}><History size={19} color="var(--ink-3)" /><h3>Saved on this computer</h3></div>
        {recent.length === 0
          ? <p className="muted small">Your searches appear here automatically. Click one to open it again without asking Search-AI.</p>
          : <div className="row" style={{ flexWrap: 'wrap' }}>{recent.map((h) => (
              <Chip key={h.id} onClick={() => void reopen(h.id)}>
                {h.kind === 'answer' ? '✨ ' : h.kind === 'summary' ? '📝 ' : ''}{plain(h.query ?? h.kind)}<span className="muted small"> · {timeAgo(h.fetchedAt)}</span>
              </Chip>))}</div>}
      </Card>
    </div>
  )
}

function HitCard({ hit, summary, summarizing, onSummarize }: { hit: Hit; summary?: string; summarizing?: boolean; onSummarize?: () => void }) {
  const url = safeUrl(hit.url)
  return (
    <Card className="rise">
      <div className="spread" style={{ alignItems: 'flex-start', gap: 12 }}>
        <div style={{ minWidth: 0 }}>
          <h3 style={{ margin: 0, overflowWrap: 'anywhere' }}>{hit.title}</h3>
          {url && <a href={url} target="_blank" rel="noopener noreferrer" className="small row" style={{ gap: 4, overflowWrap: 'anywhere' }}><ExternalLink size={13} />{url.replace(/^https?:\/\//, '')}</a>}
        </div>
        {onSummarize && <Button size="sm" busy={summarizing} onClick={onSummarize}>Summarize</Button>}
      </div>
      {hit.text && <p style={{ margin: '10px 0 0', overflowWrap: 'anywhere' }}>{hit.text.length > 600 ? `${hit.text.slice(0, 600)}…` : hit.text}</p>}
      {summary && <div style={{ marginTop: 12 }}><Badge tone="info">Summary</Badge><div style={{ marginTop: 6 }}><Markdown text={summary} compact /></div></div>}
      {hit.meta.length > 0 && <div className="row small muted" style={{ marginTop: 10, flexWrap: 'wrap' }}>{hit.meta.map(([k, v]) => <span key={k}>{k}: {v}</span>)}</div>}
    </Card>
  )
}

function Raw({ value, label = 'Show full answer' }: { value: unknown; label?: string }) {
  return (
    <details style={{ marginTop: 12 }}>
      <summary className="small muted" style={{ cursor: 'pointer' }}>{label}</summary>
      <pre className="template" style={{ marginTop: 8, maxHeight: 360, overflow: 'auto' }}>{typeof value === 'string' ? value : JSON.stringify(value, null, 2)}</pre>
    </details>
  )
}
