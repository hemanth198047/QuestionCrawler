import { ExternalLink, History, Search, ShieldCheck, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { NO_AGE, getSearchAiHistory, getSearchAiPages, search as searchStored, getSearchAiStatus, saveSearchAiPages, getSearchAiStored, saveSearchAiAddress, searchAiAnswer, searchAiSearch, searchAiSummary, searchAiUseCases } from '../api'
import { ApiError } from '../api/client'
import { LocalFindings } from '../components/LocalFindings'
import { Markdown } from '../components/Markdown'
import { PageHeader } from '../components/PageHeader'
import { Badge, Banner, Button, Card, Chip, CopyButton, EmptyState, Skeleton, Spinner } from '../components/ui'
import { answerWithSources } from '../lib/combined'
import { timeAgo } from '../lib/format'
import { type AnswerView, type Hit, extractAnswer, extractHits, extractUseCases, plain, safeUrl, searchPageUrl } from '../lib/searchai'
import { useRemote } from '../lib/useRemote'
import type { Evidence, SearchAiStatus, SearchAiStored } from '../types/api'

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
  const [local, setLocal] = useState<Evidence[] | null>(null) // what is already stored on this computer for the asked question
  const [busy, setBusy] = useState<'' | 'search' | 'answer' | 'more'>('')
  const [error, setError] = useState('')
  const [summaries, setSummaries] = useState<Record<string, string>>({})
  const [summarizing, setSummarizing] = useState('')
  const loadedUseCases = useRef(false)
  const savedPages = useRemote((sig) => getSearchAiPages(sig), [])
  const [savingUrls, setSavingUrls] = useState<string[]>([])
  const [saveErrors, setSaveErrors] = useState<Record<string, string>>({})
  const [saveNote, setSaveNote] = useState('')
  const savedSet = new Set(savedPages.kind === 'ok' ? savedPages.data.map((p) => p.url) : [])

  const current: SearchAiStatus | null = live ?? (status.kind === 'ok' ? status.data : null)
  // The sign-in check is advice, not a gate: once the address is set the person can search, and a failure shows its real reason.
  const ready = !!current?.configured

  useEffect(() => {
    if (!ready || loadedUseCases.current) return
    loadedUseCases.current = true
    searchAiUseCases().then((r) => setUseCases(extractUseCases(r.response))).catch(() => undefined) // optional: the box just stays hidden
  }, [ready])

  const pageState = (h: Hit): PageState => {
    const url = safeUrl(h.url)
    return !url ? undefined : savingUrls.includes(url) ? 'saving' : savedSet.has(url) ? 'saved' : saveErrors[url] ? { error: saveErrors[url] } : 'new'
  }
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
    setBusy('search'); setError(''); setAnswer(null); setLocal(null); setSummaries({}); setPages([]); setRawPages([]); setAsked(q)
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
    setBusy('answer'); setError(''); setAsked(q); setLocal(null)
    try {
      // The AI answer and what is already stored on this computer are asked together; the stored part never blocks the answer.
      const [ai, stored] = await Promise.all([
        searchAiAnswer(q, useCase),
        searchStored({ q, tags: [], category: '', top: 5, age: NO_AGE }).then((r) => r.evidence, () => [] as Evidence[]),
      ])
      setAnswer(extractAnswer(ai.response)); setLocal(stored); history.reload()
    } catch (e) { fail(e) } finally { setBusy('') }
  }

  /** On demand only: reads the pages these results lead to and stores their content for later searches. */
  async function savePages(hits: Hit[]) {
    const wanted = hits.map((h) => ({ h, url: safeUrl(h.url) })).filter((x): x is { h: Hit; url: string } => !!x.url && !savedSet.has(x.url) && !savingUrls.includes(x.url))
    if (wanted.length === 0) { setSaveNote('Nothing new to save: these pages are already saved or have no web address.'); return }
    const urls = wanted.map((x) => x.url)
    setSavingUrls((u) => [...u, ...urls]); setSaveNote(''); setSaveErrors((e) => { const n = { ...e }; urls.forEach((u) => delete n[u]); return n })
    try {
      const results = await saveSearchAiPages(wanted.map((x) => ({ url: x.url, title: x.h.title, query: asked })))
      const errors: Record<string, string> = {}
      results.forEach((r) => { if (r.error) errors[r.url] = r.error })
      setSaveErrors((e) => ({ ...e, ...errors }))
      const ok = results.filter((r) => !r.error).length
      setSaveNote(ok > 0 ? `Saved ${ok} page${ok === 1 ? '' : 's'}. Search on the Search page now finds their content.` : 'No page could be saved. The reason is shown on each result.')
      savedPages.reload()
    } catch (e) { fail(e) } finally { setSavingUrls((u) => u.filter((x) => !urls.includes(x))) }
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
      else { setPages([]); setRawPages([]); setLocal(null); setAnswer(extractAnswer(s.response ?? s.text)) }
    } catch (e) { setError(message(e)) }
  }

  // One chip per kind + question (every "show more" or summary call is saved, but one chip is enough to reopen it).
  const recent = history.kind === 'ok'
    ? history.data.filter((h, i, all) => h.kind !== 'usecases' && all.findIndex((o) => o.kind === h.kind && o.query === h.query) === i).slice(0, 12)
    : []
  const total = pages.reduce((n, p) => n + p.length, 0)
  const hasMore = pages.length > 0 && pages[pages.length - 1].length >= rows
  const pageUrl = searchPageUrl(current?.baseUri, query, useCase)
  const host = current?.baseUri.replace(/^https?:\/\//, '').replace(/\/.*$/, '')

  return (
    <div className="stack" style={{ gap: 26 }}>
      <PageHeader eyebrow="MSSearch-AI" title="Search the company's MSSearch-AI"
        subtitle="Use Search or Ask AI. All responses are fetched by the backend and saved on this computer so you can open them again." />

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
        : <Banner tone="warn" title="MSSearch-AI connection not confirmed yet"
            action={<div className="row"><Button variant="primary" busy={status.refreshing} onClick={recheck}>Check again</Button><Button variant="ghost" onClick={() => setChanging(true)}>Change address</Button></div>}>
            <div>You can still use Search and Ask AI: they connect on their own and show the exact reason here if something fails.</div>
            <div className="small muted" style={{ marginTop: 6 }}>{current.hint ?? current.message}</div>
            <details style={{ marginTop: 8 }}><summary className="small muted">Technical details</summary><div className="small" style={{ marginTop: 6 }}>{current.message}</div></details>
          </Banner>)}

      <Card className="searchcard" style={ready ? undefined : { opacity: 0.55, pointerEvents: 'none' }} aria-disabled={!ready}>
        <form onSubmit={(e) => { e.preventDefault(); void runSearch() }}>
          <div className="searchbar">
            <Search size={22} color="var(--ink-3)" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="What do you want to find or ask?" aria-label="Search-AI query" disabled={!ready} />
            <Button type="submit" variant="primary" size="lg" busy={busy === 'search'} disabled={!ready || !query.trim() || busy !== ''}>Search</Button>
            <Button type="button" size="lg" icon={<Sparkles size={17} />} busy={busy === 'answer'} disabled={!ready || !query.trim() || busy !== ''} onClick={() => void runAnswer()}>Ask AI</Button>
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
            {pageUrl && <a className="small" href={pageUrl} target="_blank" rel="noopener noreferrer">Open this search in Search-AI</a>}
          </div>
        </form>
      </Card>

      {error && <Banner tone="bad" title="That did not work">{error}</Banner>}
      {(busy === 'search' || busy === 'answer') && <div className="row muted"><Spinner label={busy === 'answer' ? 'Asking MSSearch-AI…' : 'Searching…'} /></div>}

      {answer && (
        <Card className="rise">
          <div className="row" style={{ marginBottom: 10 }}><Sparkles size={19} color="var(--brand)" /><h3>AI answer</h3><Badge tone="info">Written by an AI model</Badge></div>
          {answer.text ? <Markdown text={answer.text} /> : <p className="muted">Search-AI answered, but not with text we could recognise. The full answer is below.</p>}
          {answer.sources.length > 0 && (<>
            <h4 style={{ margin: '16px 0 8px' }} className="muted">Sources</h4>
            <div className="stack-sm">{answer.sources.map((h) => <HitCard key={h.key} hit={h} page={pageState(h)} onSave={() => void savePages([h])} />)}</div></>)}
          <Raw value={answer.raw} />
        </Card>
      )}

      {answer && local !== null && (local.length > 0
        ? <LocalFindings evidence={local} query={asked} title="Also found in what is already stored on this computer" />
        : <p className="muted small">Nothing stored on this computer matches this question yet. Use <strong>Save page</strong> on the sources above to keep them for next time.</p>)}
      {answer && <div><CopyButton text={answerWithSources(asked, answer, local ?? [])} label="Copy answer with sources" /></div>}

      {pages.length > 0 && (
        <section className="stack" aria-live="polite">
          <div className="spread" style={{ flexWrap: 'wrap', gap: 8 }}>
            <h2>{total === 0 ? 'No results' : `${total} result${total === 1 ? '' : 's'} for “${asked}”`}</h2>
            {total > 0 && <Button size="sm" busy={savingUrls.length > 0} disabled={savingUrls.length > 0} onClick={() => void savePages(pages.flat().slice(0, 25))}
              title="Reads the pages these results lead to (the first 25) and keeps their content on this computer so your searches can use it">Save these pages for search</Button>}
          </div>
          {saveNote && <Banner tone="info" title="Saved pages">{saveNote}</Banner>}
          {total === 0
            ? <EmptyState icon={<Search size={30} />} title="MSSearch-AI found nothing for that">Try different words or choose “All areas”. If you expected results, open “Show full answer” to see exactly what came back.</EmptyState>
            : <div className="stack-sm">{pages.flat().map((h) => (
                <HitCard key={h.key} hit={h} summary={h.chunkId ? summaries[h.chunkId] : undefined} summarizing={summarizing === h.chunkId} onSummarize={h.chunkId ? () => void summarize(h) : undefined}
                  page={pageState(h)} onSave={() => void savePages([h])} />))}</div>}
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

type PageState = undefined | 'new' | 'saving' | 'saved' | { error: string }

function HitCard({ hit, summary, summarizing, onSummarize, page, onSave }: { hit: Hit; summary?: string; summarizing?: boolean; onSummarize?: () => void; page?: PageState; onSave?: () => void }) {
  const url = safeUrl(hit.url)
  return (
    <Card className="rise">
      <div className="spread" style={{ alignItems: 'flex-start', gap: 12 }}>
        <div style={{ minWidth: 0 }}>
          <h3 style={{ margin: 0, overflowWrap: 'anywhere' }}>{hit.title}</h3>
          {url && <a href={url} target="_blank" rel="noopener noreferrer" className="small row" style={{ gap: 4, overflowWrap: 'anywhere' }}><ExternalLink size={13} />{url.replace(/^https?:\/\//, '')}</a>}
        </div>
        <div className="row" style={{ flexShrink: 0 }}>
          {onSummarize && <Button size="sm" busy={summarizing} onClick={onSummarize}>Summarize</Button>}
          {page === 'saved' && <Badge tone="ok">Page saved</Badge>}
          {page && page !== 'saved' && onSave && <Button size="sm" busy={page === 'saving'} disabled={page === 'saving'} onClick={onSave} title="Read this page and keep its content on this computer for later searches">Save page</Button>}
        </div>
      </div>
      {page && typeof page === 'object' && <p className="small" style={{ margin: '8px 0 0', color: 'var(--bad, #b42318)' }}>Could not save this page: {page.error}</p>}
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
