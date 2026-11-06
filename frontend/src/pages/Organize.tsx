import { ArrowRight, FolderTree, Layers, Lightbulb, Plus, RotateCcw, Save, Sparkles, Trash2, Wand2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { discoverTopics, getCategories, getCategory, getTaxonomy, reindex, resetTaxonomy, saveTaxonomy, tryTaxonomy } from '../api'
import { PageHeader } from '../components/PageHeader'
import { Badge, Banner, Button, Card, Chip, EmptyState, InfoTip, PALETTE, Segmented, Skeleton, TagInput, useToast } from '../components/ui'
import { AGE_STEPS, dateOnly, friendlyRule, timeAgo } from '../lib/format'
import { go, useRemote } from '../lib/useRemote'
import type { Category, Rule, Taxonomy, TopicProposal, TryResult } from '../types/api'

export default function OrganizePage({ tab, selected }: { tab: string; selected: string | null }) {
  return (
    <div className="stack" style={{ gap: 26 }}>
      <PageHeader eyebrow="Organize" title="Topics" subtitle="Every question is placed into one or more topics by simple rules that you can read, test and change. There is no hidden AI judging them."
        actions={<Segmented<string> label="View" value={tab} onChange={(v) => go(v === 'rules' ? '/organize/rules' : v === 'discover' ? '/organize/discover' : '/organize')}
          options={[{ value: 'browse', label: 'Browse topics' }, { value: 'discover', label: 'Discover topics' }, { value: 'rules', label: 'Edit rules' }]} />} />
      {tab === 'rules' ? <RulesEditor /> : tab === 'discover' ? <DiscoverTopics /> : <Browse selected={selected} />}
    </div>
  )
}

// ------------------------------------------------------------------ browsing
function Browse({ selected }: { selected: string | null }) {
  const cats = useRemote((s) => getCategories(s), [])
  const [age, setAge] = useState('')
  const listing = useRemote((s) => (selected ? getCategory(selected, age, s) : Promise.resolve(null)), [selected, age])
  if (cats.kind === 'loading') return <div className="grid-3"><Skeleton h={130} r={18} /><Skeleton h={130} r={18} /><Skeleton h={130} r={18} /></div>
  if (cats.kind === 'error') return <Banner tone="bad" title="Topics could not be loaded">{cats.message}</Banner>
  const max = Math.max(1, ...cats.data.map((c) => c.questionCount))
  const total = cats.data.reduce((a, c) => a + c.questionCount, 0)
  if (total === 0) return <EmptyState icon={<FolderTree size={30} />} title="No questions to organize yet" action={<Button variant="primary" onClick={() => go('/collect')}>Collect questions</Button>}>Topics appear here as soon as questions are collected.</EmptyState>
  return (
    <div className="stack" style={{ gap: 24 }}>
      <div className="catgrid">
        {cats.data.map((c, i) => (
          <a key={c.id} href={`#/organize/browse/${c.id}`} className={`catcard ${c.id === selected ? 'on' : ''} ${c.questionCount === 0 ? 'empty' : ''}`}>
            <span className="cat-dot" style={{ background: PALETTE[i % PALETTE.length] }} />
            <strong>{c.name}</strong>
            <span className="muted small cat-desc">{c.description}</span>
            <div className="cat-meter"><i style={{ width: `${(c.questionCount / max) * 100}%`, background: PALETTE[i % PALETTE.length] }} /></div>
            <span className="cat-count">{c.questionCount} question{c.questionCount === 1 ? '' : 's'}</span>
          </a>
        ))}
      </div>
      {selected && listing.kind === 'ok' && listing.data && (
        <Card className="rise">
          <div className="spread" style={{ marginBottom: 6 }}>
            <div><h2>{listing.data.category.name}</h2><p className="muted">{listing.data.category.description}</p></div>
            <div className="row"><span className="muted small">Asked within</span>
              <select className="select" style={{ width: 'auto' }} value={age} onChange={(e) => setAge(e.target.value)}>{AGE_STEPS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}</select></div>
          </div>
          {listing.data.questions.length === 0 ? <p className="muted" style={{ padding: '24px 0' }}>No questions in this topic{age ? ' for that time range' : ''}.</p> : (
            <ul className="qlist">
              {listing.data.questions.map((q) => (
                <li key={q.id}><a href={`#/question/${encodeURIComponent(q.id)}`}>
                  <strong>{q.title}</strong>
                  <span className="muted small">asked {timeAgo(q.createdAt)} · {dateOnly(q.createdAt)} · {q.answerCount} answer{q.answerCount === 1 ? '' : 's'}</span>
                  <span className="row" style={{ gap: 6 }}>{q.hasAcceptedAnswer && <Badge tone="ok">Accepted answer</Badge>}{q.answerCount === 0 && <Badge tone="warn">Unanswered</Badge>}{q.status !== 'ACTIVE' && <Badge tone="warn">{q.status.toLowerCase().replace('_', ' ')}</Badge>}</span>
                  <ArrowRight size={16} className="qarrow" /></a></li>
              ))}
            </ul>
          )}
        </Card>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ rule editor
const RULE_TYPES = [
  { value: 'keyword', label: 'Word or phrase' }, { value: 'tag', label: 'Exact tag' }, { value: 'regex', label: 'Pattern (advanced)' },
] as const
const FIELDS = [
  { value: 'any', label: 'Anywhere' }, { value: 'title', label: 'Title' }, { value: 'body', label: 'Question text' }, { value: 'answers', label: 'Answers' }, { value: 'tags', label: 'Tags' },
] as const

function RulesEditor() {
  const toast = useToast()
  const tax = useRemote((s) => getTaxonomy(s), [])
  const [draft, setDraft] = useState<Taxonomy | null>(null)
  const [orig, setOrig] = useState('')
  const [selId, setSelId] = useState<string | null>(null)
  const [problems, setProblems] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  const [reorg, setReorg] = useState(false)
  const [confirmReset, setConfirmReset] = useState(false)

  useEffect(() => { if (tax.kind === 'ok' && !draft) { setDraft(tax.data.taxonomy); setOrig(JSON.stringify(tax.data.taxonomy)); setSelId(tax.data.taxonomy.categories[0]?.id ?? null) } }, [tax, draft])
  const dirty = useMemo(() => !!draft && JSON.stringify(draft) !== orig, [draft, orig])

  if (tax.kind === 'loading' || !draft) return <div className="grid-2"><Skeleton h={360} r={18} /><Skeleton h={360} r={18} /></div>
  if (tax.kind === 'error') return <Banner tone="bad" title="Rules could not be loaded">{tax.message}</Banner>

  const cat = draft.categories.find((c) => c.id === selId) ?? draft.categories[0]
  const patch = (id: string, change: Partial<Category>) => setDraft({ ...draft, categories: draft.categories.map((c) => (c.id === id ? { ...c, ...change } : c)) })
  const setRule = (ci: string, ri: number, change: Partial<Rule>) => patch(ci, { rules: draft.categories.find((c) => c.id === ci)!.rules.map((r, i) => (i === ri ? { ...r, ...change } : r)) })
  const addCategory = () => {
    let n = 1; let id = 'new-topic'
    while (draft.categories.some((c) => c.id === id)) id = `new-topic-${++n}`
    const created: Category = { id, name: 'New topic', description: 'What belongs here?', rules: [{ type: 'keyword', field: 'any', pattern: '', weight: 2 }] }
    const fb = draft.categories.findIndex((c) => c.id === draft.fallbackCategory)
    const cats = [...draft.categories]; cats.splice(fb < 0 ? cats.length : fb, 0, created)
    setDraft({ ...draft, categories: cats }); setSelId(id)
  }
  const removeCategory = (id: string) => { setDraft({ ...draft, categories: draft.categories.filter((c) => c.id !== id) }); setSelId(draft.categories.find((c) => c.id !== id)?.id ?? null) }
  const save = async () => {
    setSaving(true)
    try {
      const v = await saveTaxonomy(draft)
      setProblems(v.problems)
      if (v.problems.length === 0) { setOrig(JSON.stringify(v.taxonomy)); setDraft(v.taxonomy); setReorg(true); toast('ok', 'Rules saved') }
    } catch (e) { toast('bad', e instanceof Error ? e.message : 'Could not save') } finally { setSaving(false) }
  }
  const isFallback = cat && cat.id === draft.fallbackCategory

  return (
    <div className="stack" style={{ gap: 22 }}>
      {reorg && !dirty && (
        <Banner tone="info" title="Your rules changed. Re-sort the questions you already have?" action={<Button variant="primary" icon={<Layers size={15} />} onClick={async () => { const r = await reindex(); setReorg(false); toast('ok', `Re-sorted ${r.threads} questions`) }}>Re-sort now</Button>}>
          New rules apply to newly collected questions automatically. Existing ones keep their old topics until you re-sort them.</Banner>
      )}
      {problems.length > 0 && <Banner tone="bad" title="These rules need fixing before they can be saved">{problems.map((p) => <div key={p}>• {p}</div>)}</Banner>}

      <Card>
        <div className="spread">
          <div><h3>How a topic is chosen <InfoTip term="category" /></h3><p className="card-sub">Each matching rule adds its points. A question joins a topic when the points reach the minimum below. If no topic reaches it, the question goes to “{draft.categories.find((c) => c.id === draft.fallbackCategory)?.name ?? 'Uncategorized'}”.</p></div>
          <div className="minscore"><span className="muted small">Minimum points</span>
            <div className="row"><input type="range" min={1} max={6} step={0.5} value={draft.minScore} onChange={(e) => setDraft({ ...draft, minScore: Number(e.target.value) })} /><b>{draft.minScore}</b></div></div>
        </div>
      </Card>

      <div className="rules-layout">
        <Card pad={false} className="catlist">
          <div className="catlist-head"><strong>Topics</strong><Button size="sm" icon={<Plus size={14} />} onClick={addCategory}>Add</Button></div>
          <ul>{draft.categories.map((c, i) => (
            <li key={c.id}><button type="button" className={c.id === cat?.id ? 'on' : ''} onClick={() => setSelId(c.id)}><i style={{ background: PALETTE[i % PALETTE.length] }} /><span>{c.name}</span><small>{c.rules.length}</small></button></li>))}</ul>
        </Card>

        {cat && (
          <div className="stack" style={{ gap: 18 }}>
            <Card className="rise" key={cat.id}>
              <div className="stack">
                <div className="grid-2" style={{ gap: 18 }}>
                  <div className="field"><div className="field-label">Name</div><input className="input" value={cat.name} onChange={(e) => patch(cat.id, { name: e.target.value })} /></div>
                  <div className="field"><div className="field-label">Short code <InfoTip text="Used behind the scenes. It cannot change after questions have been placed here, so pick it once." title="Short code" /></div><input className="input" value={cat.id} readOnly style={{ background: 'var(--bg-sunken)' }} /></div>
                </div>
                <div className="field"><div className="field-label">What belongs here?</div><input className="input" value={cat.description} onChange={(e) => patch(cat.id, { description: e.target.value })} /></div>
                {isFallback ? <Banner tone="info" title="This is the catch-all topic">It receives questions that match nothing else, so it has no rules.</Banner> : (
                  <div className="stack-sm">
                    <div className="spread"><h4>Rules</h4><Button size="sm" icon={<Plus size={14} />} onClick={() => patch(cat.id, { rules: [...cat.rules, { type: 'keyword', field: 'any', pattern: '', weight: 1 }] })}>Add rule</Button></div>
                    {cat.rules.length === 0 && <p className="muted small">No rules yet: nothing will ever be placed in this topic.</p>}
                    {cat.rules.map((r, i) => (
                      <div className="rule" key={i}>
                        <select className="select" aria-label="Rule type" value={r.type} onChange={(e) => setRule(cat.id, i, { type: e.target.value as Rule['type'] })}>{RULE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}</select>
                        <span className="rule-word">found in</span>
                        <select className="select" aria-label="Where" value={r.type === 'tag' ? 'tags' : r.field} disabled={r.type === 'tag'} onChange={(e) => setRule(cat.id, i, { field: e.target.value as Rule['field'] })}>{FIELDS.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}</select>
                        <input className="input" aria-label="Text to look for" value={r.pattern} placeholder={r.type === 'regex' ? 'e.g. \\bORA-\\d+\\b' : r.type === 'tag' ? 'exact tag, e.g. helm' : 'word or phrase, e.g. timeout'} onChange={(e) => setRule(cat.id, i, { pattern: e.target.value })} spellCheck={false} />
                        <label className="pts"><input type="number" min={0.5} step={0.5} value={r.weight} aria-label="Points" onChange={(e) => setRule(cat.id, i, { weight: Number(e.target.value) })} /><span>pts</span></label>
                        <button type="button" className="icon-btn" aria-label="Remove rule" onClick={() => patch(cat.id, { rules: cat.rules.filter((_, n) => n !== i) })}><Trash2 size={16} /></button>
                      </div>
                    ))}
                  </div>
                )}
                {!isFallback && <div><Button size="sm" variant="danger" icon={<Trash2 size={14} />} onClick={() => removeCategory(cat.id)}>Delete this topic</Button></div>}
              </div>
            </Card>
            <TryPanel draft={draft} />
          </div>
        )}
      </div>

      <div className="savebar"><div className="savebar-inner">
        <div>{dirty ? <strong>You have unsaved changes</strong> : <span className="muted">All changes saved{tax.data.custom ? '' : ' · using the built-in starter topics'}</span>}</div>
        <div className="row">
          {confirmReset ? (<><span className="small muted">Replace with the starter topics?</span><Button size="sm" variant="danger" onClick={async () => { const v = await resetTaxonomy(); setDraft(v.taxonomy); setOrig(JSON.stringify(v.taxonomy)); setConfirmReset(false); setReorg(true); toast('ok', 'Starter topics restored') }}>Yes, restore</Button><Button size="sm" variant="ghost" onClick={() => setConfirmReset(false)}>Cancel</Button></>)
            : (<Button variant="ghost" icon={<RotateCcw size={15} />} onClick={() => setConfirmReset(true)}>Restore starter topics</Button>)}
          {dirty && <Button variant="ghost" onClick={() => { setDraft(JSON.parse(orig) as Taxonomy); setProblems([]) }}>Discard</Button>}
          <Button variant="primary" icon={<Save size={16} />} busy={saving} disabled={!dirty} onClick={save}>Save rules</Button>
        </div></div></div>
    </div>
  )
}

function TryPanel({ draft }: { draft: Taxonomy }) {
  const [title, setTitle] = useState('Helm chart fails to deploy to the cluster')
  const [body, setBody] = useState('The kubernetes rollout failed with a timeout error after the helm upgrade.')
  const [tags, setTags] = useState<string[]>(['helm'])
  const [res, setRes] = useState<TryResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const run = async () => { setBusy(true); setErr(null); try { setRes(await tryTaxonomy(draft, title, body, tags)) } catch (e) { setErr(e instanceof Error ? e.message : 'Failed') } finally { setBusy(false) } }
  return (
    <Card className="try">
      <div className="row" style={{ marginBottom: 12 }}><Wand2 size={19} color="var(--brand)" /><div><h3>Try it</h3><p className="card-sub">Type an example question to see which topics it gets, and exactly which rules matched. Uses your unsaved changes.</p></div></div>
      <div className="grid-2" style={{ gap: 18 }}>
        <div className="stack-sm">
          <div className="field"><div className="field-label">Title</div><input className="input" value={title} onChange={(e) => setTitle(e.target.value)} /></div>
          <div className="field"><div className="field-label">Question text</div><textarea className="textarea" value={body} onChange={(e) => setBody(e.target.value)} /></div>
          <div className="field"><div className="field-label">Tags</div><TagInput value={tags} onChange={setTags} placeholder="optional" /></div>
          <Button variant="primary" icon={<Sparkles size={16} />} busy={busy} onClick={run}>Test it</Button>
        </div>
        <div>
          {err && <Banner tone="bad">{err}</Banner>}
          {!res && !err && <div className="try-empty muted">The result appears here.</div>}
          {res && (
            <div className="stack-sm rise">
              <p className="muted small">{res.explanation}</p>
              {res.matches.map((m) => (
                <div key={m.categoryId} className="tryres"><div className="spread"><strong>{m.name}</strong><Badge tone={res.fallback ? 'neutral' : 'ok'}>{m.score} pts</Badge></div>
                  <div className="row" style={{ gap: 6, marginTop: 6 }}>{m.matchedRules.map((r) => <Chip key={r}>{friendlyRule(r)}</Chip>)}</div></div>))}
            </div>
          )}
        </div>
      </div>
    </Card>
  )
}


// ------------------------------------------------------------------ discovering topics from the questions
function DiscoverTopics() {
  const toast = useToast()
  const [k, setK] = useState(0)
  const [busy, setBusy] = useState(false)
  const [applying, setApplying] = useState(false)
  const [proposal, setProposal] = useState<TopicProposal | null>(null)
  const [names, setNames] = useState<Record<string, string>>({})
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const [mode, setMode] = useState<'replace' | 'add'>('replace')
  const [error, setError] = useState<string | null>(null)

  const run = async () => {
    setBusy(true); setError(null)
    try {
      const p = await discoverTopics(k)
      setProposal(p); setNames(Object.fromEntries(p.topics.map((t) => [t.id, t.name]))); setChosen(new Set(p.topics.map((t) => t.id)))
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not discover topics') } finally { setBusy(false) }
  }
  const apply = async () => {
    if (!proposal) return
    setApplying(true)
    try {
      const fallbackId = proposal.taxonomy.fallbackCategory
      const picked: Category[] = proposal.taxonomy.categories.filter((c) => chosen.has(c.id)).map((c) => ({ ...c, name: (names[c.id] ?? c.name).trim() || c.name }))
      let categories = picked
      if (mode === 'add') {
        const current = (await getTaxonomy()).taxonomy
        const keep = current.categories.filter((c) => c.id !== current.fallbackCategory)
        const used = new Set(keep.map((c) => c.id))
        const renamed = picked.map((c) => { let id = c.id; let n = 2; while (used.has(id)) id = `${c.id}-${n++}`; used.add(id); return { ...c, id } })
        categories = [...keep, ...renamed]
      }
      const fallback = proposal.taxonomy.categories.find((c) => c.id === fallbackId)!
      const saved = await saveTaxonomy({ ...proposal.taxonomy, categories: [...categories, fallback] })
      if (saved.problems.length) { setError(saved.problems.join('; ')); return }
      const r = await reindex()
      toast('ok', `${picked.length} topics saved and ${r.threads} questions sorted`)
      go('/organize')
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not save') } finally { setApplying(false) }
  }
  const toggle = (id: string) => { const n = new Set(chosen); if (n.has(id)) n.delete(id); else n.add(id); setChosen(n) }

  return (
    <div className="stack" style={{ gap: 22 }}>
      <Card className="discover-intro">
        <div className="row" style={{ alignItems: 'flex-start', gap: 18 }}>
          <span className="how-icon"><Lightbulb size={22} /></span>
          <div style={{ flex: 1, minWidth: 260 }}>
            <h3>Let your questions tell you the topics</h3>
            <p className="muted" style={{ marginTop: 6 }}>We read the titles, tags, text and answers of everything you collected, group questions that are about the same thing, and name each group from the words
              that set it apart. Then you review, rename and choose. It runs on this computer, with no AI service, and gives the same result every time.</p>
            <ol className="discover-steps"><li><b>1</b> Find</li><li><b>2</b> Review and rename</li><li><b>3</b> Use them</li></ol>
          </div>
          <div className="stack-sm" style={{ minWidth: 220 }}>
            <label className="field-label" htmlFor="k">How many topics?</label>
            <select id="k" className="select" value={k} onChange={(e) => setK(Number(e.target.value))}>
              <option value={0}>Let the app decide</option>{[3, 4, 5, 6, 7, 8, 10, 12].map((n) => <option key={n} value={n}>{n} topics</option>)}
            </select>
            <Button variant="primary" icon={<Sparkles size={16} />} busy={busy} onClick={run}>{proposal ? 'Find again' : 'Find topics'}</Button>
          </div>
        </div>
      </Card>
      {error && <Banner tone="bad" title="Something went wrong">{error}</Banner>}

      {proposal && proposal.topics.length === 0 && <EmptyState icon={<Lightbulb size={30} />} title="Not enough to go on yet">{proposal.notes[0] ?? 'Collect more questions first.'}</EmptyState>}
      {proposal && proposal.topics.length > 0 && (
        <>
          <div className="spread">
            <h2>{proposal.topics.length} topics found in {proposal.documents} questions</h2>
            <Badge tone={proposal.placedShare >= 0.8 ? 'ok' : 'warn'}>{Math.round(proposal.placedShare * 100)}% of questions would be placed</Badge>
          </div>
          {proposal.notes.map((n) => <Banner key={n} tone={n.startsWith('Only') ? 'warn' : 'info'}>{n}</Banner>)}
          <div className="topic-grid">
            {proposal.topics.map((t, i) => (
              <Card key={t.id} className={`topic ${chosen.has(t.id) ? 'on' : 'off'}`}>
                <div className="topic-head">
                  <label className="switch"><input type="checkbox" checked={chosen.has(t.id)} onChange={() => toggle(t.id)} aria-label={`Use ${names[t.id]}`} /><span className="switch-track" /></label>
                  <i className="topic-dot" style={{ background: PALETTE[i % PALETTE.length] }} />
                  <input className="input topic-name" value={names[t.id] ?? t.name} onChange={(e) => setNames({ ...names, [t.id]: e.target.value })} aria-label="Topic name" />
                </div>
                <p className="muted small">{t.description}</p>
                <div className="topic-stats"><b>{t.size}</b> grouped · <b>{t.matches}</b> placed by the rules</div>
                <div className="row" style={{ gap: 6 }}>{t.topTerms.slice(0, 6).map((w) => <Chip key={w}>{w}</Chip>)}{t.topTags.map((g) => <Chip key={g} active>tag: {g}</Chip>)}</div>
                <div className="topic-samples">{t.samples.map((sm) => <a key={sm.id} href={`#/question/${encodeURIComponent(sm.id)}`}>{sm.title}</a>)}</div>
                <details className="small"><summary>Rules that will be used</summary>
                  <ul className="plainlist">{t.rules.map((r, n) => <li key={n}>{r.type === 'tag' ? `tag “${r.pattern}”` : `the word “${r.pattern}”`} <span className="muted">({r.weight} pts)</span></li>)}</ul></details>
              </Card>
            ))}
          </div>
          <div className="savebar"><div className="savebar-inner">
            <div className="row"><Segmented<'replace' | 'add'> label="How to use" value={mode} onChange={setMode} options={[{ value: 'replace', label: 'Replace my topics' }, { value: 'add', label: 'Add to my topics' }]} />
              <span className="muted small">{chosen.size} of {proposal.topics.length} selected</span></div>
            <Button variant="primary" icon={<Wand2 size={16} />} busy={applying} disabled={chosen.size === 0} onClick={apply}>Use these topics</Button>
          </div></div>
        </>
      )}
    </div>
  )
}
