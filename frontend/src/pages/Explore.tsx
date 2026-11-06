import { Copy, Filter, Lightbulb, MessageCircleQuestion, Search, SlidersHorizontal } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { getCategories, search, type AgeParams } from '../api'
import { AgeSlider, type AgeValue } from '../components/AgeSlider'
import { EvidenceCard } from '../components/Evidence'
import { PageHeader } from '../components/PageHeader'
import { useOverview } from '../components/OverviewContext'
import { Badge, Banner, Button, Card, Chip, CopyButton, EmptyState, InfoTip, Skeleton, TagInput } from '../components/ui'
import { ageLabel, timeAgo } from '../lib/format'
import { go, useRemote } from '../lib/useRemote'

const SUGGESTIONS: Record<string, string[]> = {
  fixture: ['ERR_CONN_TIMEOUT connection timed out', 'configure SSO login', 'helm upgrade deploy', 'ORB-1042 installer'],
  stackexchange: ['overfitting', 'neural network training', 'gradient descent', 'transformer attention'],
  confluence: ['runbook', 'onboarding', 'architecture', 'incident response'],
  jive: ['runbook', 'onboarding', 'howto', 'faq'],
  live: ['error message', 'how to configure', 'deployment failure'],
}

const toAge = (a: AgeValue) => (a.value ? a.value : '')
function fromParams(params: URLSearchParams, newer: string, older: string): AgeValue {
  return params.get(newer) ? { mode: 'newer', value: params.get(newer)! } : params.get(older) ? { mode: 'older', value: params.get(older)! } : { mode: 'newer', value: '' }
}

export default function ExplorePage({ params }: { params: URLSearchParams }) {
  const { overview } = useOverview()
  const q = params.get('q') ?? ''
  const tags = useMemo(() => params.getAll('tag'), [params])
  const category = params.get('category') ?? ''
  const qAge = useMemo(() => fromParams(params, 'maxQuestionAge', 'minQuestionAge'), [params])
  const aAge = useMemo(() => fromParams(params, 'maxAnswerAge', 'minAnswerAge'), [params])
  const [input, setInput] = useState(q)
  const [tagDraft, setTagDraft] = useState(tags)
  const [catDraft, setCatDraft] = useState(category)
  const [qDraft, setQDraft] = useState<AgeValue>(qAge)
  const [aDraft, setADraft] = useState<AgeValue>(aAge)
  const [open, setOpen] = useState(qAge.value !== '' || aAge.value !== '' || tags.length > 0 || category !== '')
  const box = useRef<HTMLInputElement>(null)
  const cats = useRemote((s) => getCategories(s), [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === '/' && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') { e.preventDefault(); box.current?.focus() } }
    window.addEventListener('keydown', onKey); return () => window.removeEventListener('keydown', onKey)
  }, [])

  const ageParams: AgeParams = {
    maxQuestionAge: qAge.mode === 'newer' ? toAge(qAge) : '', minQuestionAge: qAge.mode === 'older' ? toAge(qAge) : '',
    maxAnswerAge: aAge.mode === 'newer' ? toAge(aAge) : '', minAnswerAge: aAge.mode === 'older' ? toAge(aAge) : '',
  }
  const result = useRemote((s) => (q.trim() ? search({ q, tags, category, top: 10, age: ageParams }, s) : Promise.resolve(null)), [params.toString()])

  const navigate = (over: Partial<{ q: string; tags: string[]; category: string; qAge: AgeValue; aAge: AgeValue }> = {}) => {
    const n = new URLSearchParams()
    const text = over.q ?? input
    if (text.trim()) n.set('q', text.trim())
    ;(over.tags ?? tagDraft).forEach((t) => n.append('tag', t))
    const c = over.category ?? catDraft; if (c) n.set('category', c)
    const qa = over.qAge ?? qDraft; if (qa.value) n.set(qa.mode === 'newer' ? 'maxQuestionAge' : 'minQuestionAge', qa.value)
    const aa = over.aAge ?? aDraft; if (aa.value) n.set(aa.mode === 'newer' ? 'maxAnswerAge' : 'minAnswerAge', aa.value)
    go(`/explore?${n.toString()}`)
  }
  const activeFilters = [
    ...tags.map((t) => ({ k: `tag-${t}`, label: `tag: ${t}`, clear: () => navigate({ tags: tags.filter((x) => x !== t) }) })),
    ...(category ? [{ k: 'cat', label: `topic: ${cats.kind === 'ok' ? cats.data.find((c) => c.id === category)?.name ?? category : category}`, clear: () => navigate({ category: '' }) }] : []),
    ...(qAge.value ? [{ k: 'qa', label: `questions ${qAge.mode === 'newer' ? 'from the' : 'older than the'} ${ageLabel(qAge.value).toLowerCase().replace('past ', qAge.mode === 'newer' ? 'past ' : '')}`, clear: () => navigate({ qAge: { mode: 'newer', value: '' } }) }] : []),
    ...(aAge.value ? [{ k: 'aa', label: `answers ${aAge.mode === 'newer' ? 'from the' : 'older than the'} ${ageLabel(aAge.value).toLowerCase().replace('past ', aAge.mode === 'newer' ? 'past ' : '')}`, clear: () => navigate({ aAge: { mode: 'newer', value: '' } }) }] : []),
  ]
  const hasData = (overview?.totals.questions ?? 0) > 0
  const examples = SUGGESTIONS[overview?.sourceType ?? 'fixture'] ?? SUGGESTIONS.fixture

  return (
    <div className="stack" style={{ gap: 26 }}>
      <PageHeader eyebrow="Explore" title="Ask your knowledge" subtitle="Type a question, an error message or a function name. We’ll show the passages that match, with where they came from and how old they are." />
      {overview && !hasData && <Banner tone="warn" title="There is nothing to search yet" action={<Button onClick={() => go('/collect')}>Collect questions</Button>}>Collect some questions first, then come back.</Banner>}

      <Card className="searchcard">
        <form onSubmit={(e) => { e.preventDefault(); navigate() }}>
          <div className="searchbar">
            <Search size={22} color="var(--ink-3)" />
            <input ref={box} value={input} onChange={(e) => setInput(e.target.value)} placeholder="What are you trying to solve?" aria-label="Search" autoFocus />
            <kbd>/</kbd>
            <Button type="submit" variant="primary" size="lg">Search</Button>
          </div>
          <div className="spread" style={{ marginTop: 14 }}>
            <div className="row" style={{ gap: 8 }}>
              <Button type="button" size="sm" variant={open ? 'primary' : 'secondary'} icon={<SlidersHorizontal size={15} />} onClick={() => setOpen(!open)}>Filters{activeFilters.length ? ` · ${activeFilters.length}` : ''}</Button>
              {activeFilters.map((f) => <Chip key={f.k} onRemove={f.clear}>{f.label}</Chip>)}
            </div>
          </div>
          {open && (
            <div className="filters rise">
              <div className="grid-2" style={{ gap: 28 }}>
                <AgeSlider label="How old may the question be?" hint="No limit on the question’s age." state={qDraft} onChange={setQDraft} />
                <AgeSlider label="How old may the answer be?" hint="No limit on the answer’s age. Questions stay visible even if their answers are filtered out." state={aDraft} onChange={setADraft} />
              </div>
              <div className="grid-2" style={{ gap: 28 }}>
                <div className="field"><div className="field-label">Topic <InfoTip term="category" /></div>
                  <select className="select" value={catDraft} onChange={(e) => setCatDraft(e.target.value)}>
                    <option value="">Any topic</option>{cats.kind === 'ok' && cats.data.filter((c) => c.questionCount > 0).map((c) => <option key={c.id} value={c.id}>{c.name} ({c.questionCount})</option>)}
                  </select></div>
                <div className="field"><div className="field-label">Only questions tagged <InfoTip term="tag" /></div><TagInput value={tagDraft} onChange={setTagDraft} placeholder="Type a tag, press Enter" /></div>
              </div>
              <div className="row"><Button type="submit" variant="primary">Apply filters</Button><Button type="button" variant="ghost" onClick={() => { setQDraft({ mode: 'newer', value: '' }); setADraft({ mode: 'newer', value: '' }); setTagDraft([]); setCatDraft('') }}>Clear all</Button></div>
            </div>
          )}
        </form>
      </Card>

      {!q.trim() && (
        <Card className="rise"><div className="row" style={{ marginBottom: 12 }}><Lightbulb size={19} color="var(--warn)" /><h3>Not sure what to ask? Try one of these</h3></div>
          <div className="row">{examples.map((s) => <Chip key={s} onClick={() => { setInput(s); navigate({ q: s }) }}>{s}</Chip>)}</div>
          <p className="muted small" style={{ marginTop: 14 }}>Tip: paste an error message exactly as you see it. Exact wording is matched first.</p></Card>
      )}

      {q.trim() && result.kind === 'loading' && <div className="stack-sm"><Skeleton h={150} r={18} /><Skeleton h={150} r={18} /></div>}
      {result.kind === 'error' && <Banner tone="bad" title="Search could not run">{result.message}</Banner>}
      {result.kind === 'ok' && result.data && (
        <section className="stack" aria-live="polite">
          <div className="spread">
            <div className="row"><h2>{result.data.resultCount === 0 ? 'No matches' : `${result.data.resultCount} passage${result.data.resultCount === 1 ? '' : 's'}`}</h2>
              <Confidence level={result.data.confidence} /></div>
            {result.data.freshness && <span className={`small ${result.data.freshness.stale ? 'stale-note' : 'muted'}`}>{result.data.freshness.lastSuccessfulSyncAt ? `Knowledge updated ${timeAgo(result.data.freshness.lastSuccessfulSyncAt)}` : 'Never updated'}{result.data.freshness.stale ? ' · may be out of date' : ''}</span>}
          </div>
          {result.data.resultCount === 0 ? (
            <EmptyState icon={<MessageCircleQuestion size={30} />} title="Nothing matches that" action={<>
              {activeFilters.length > 0 && <Button onClick={() => go(`/explore?q=${encodeURIComponent(q)}`)} icon={<Filter size={16} />}>Remove all filters</Button>}
              <Button onClick={() => go('/collect')}>Collect more questions</Button></>}>
              Try different words, an exact error message, or a wider age range. This search matches words, not meaning, so related wording may need a second try.</EmptyState>
          ) : (
            <div className="stack-sm">{result.data.evidence.map((e) => <EvidenceCard key={`${e.questionId}-${e.answerId ?? 'q'}`} e={e} onCategory={(id) => navigate({ category: id })} />)}</div>
          )}
          {result.data.truncated && <p className="muted small">{result.data.omittedForBudget} lower-ranked passage(s) were left out to keep this readable.</p>}
          <p className="muted small"><InfoTip term="untrusted" /> Everything shown was written by other people and is displayed as reading material only. Check the original source before relying on it.</p>
          {result.data.resultCount > 0 && <CopilotCard q={q} />}
        </section>
      )}
    </div>
  )
}

function Confidence({ level }: { level: string }) {
  const n = { NONE: 0, LOW: 1, MEDIUM: 2, HIGH: 3 }[level] ?? 0
  const label = { NONE: 'No match', LOW: 'Weak match', MEDIUM: 'Good match', HIGH: 'Strong match' }[level]
  return <span className="conf" title="Rough guide to how many of your words the best passage contains"><span className="conf-bars">{[1, 2, 3].map((i) => <i key={i} className={i <= n ? `on l${n}` : ''} />)}</span>{label}<InfoTip term="confidence" /></span>
}

function CopilotCard({ q }: { q: string }) {
  const prompt = `Search our internal Q&A for: ${q}`
  return (
    <Card className="copilot">
      <div className="spread"><div className="row"><Copy size={18} color="var(--brand)" /><div><strong>Want Copilot to answer in plain words?</strong><div className="muted small">In VS Code Copilot Chat (Agent mode), paste:</div></div></div><CopyButton text={prompt} label="Copy prompt" /></div>
      <pre className="template" style={{ marginTop: 12 }}>{prompt}</pre>
      <div className="row small muted" style={{ marginTop: 8 }}><Badge tone="neutral">Tip</Badge>Copilot will search this same knowledge and cite the original links.</div>
    </Card>
  )
}
