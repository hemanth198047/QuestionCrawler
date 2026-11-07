import { ArrowLeft, Award, CalendarDays, ExternalLink, Pencil, RotateCcw, ShieldAlert, ThumbsUp, User } from 'lucide-react'
import { useEffect, useState } from 'react'
import { clearOverride, getCategories, getQuestion, setOverride } from '../api'
import { Markdown } from '../components/Markdown'
import { Badge, Banner, Button, Card, Chip, EmptyState, InfoTip, Skeleton, useToast } from '../components/ui'
import { dateOnly, friendlyRule, plainHeading, timeAgo } from '../lib/format'
import { go, useRemote } from '../lib/useRemote'
import { safeUrl } from '../lib/url'
import { termsForSystem } from '../lib/terms'
import type { Answer, ThreadView } from '../types/api'

function AnswerCard({ a }: { a: Answer }) {
  const href = safeUrl(a.url)
  const comments = a.comments ?? []
  return (
    <Card className={`answer rise ${a.accepted ? 'is-accepted' : ''}`} id={a.id ? `answer-${a.id}` : undefined}>
      {a.accepted && <div className="ribbon"><Award size={14} /> Accepted answer <InfoTip term="accepted" /></div>}
      <div className="spread">
        <div className="row" style={{ gap: 10 }}><Badge tone="neutral" icon={<ThumbsUp size={12} />}>{a.score ?? '?'} votes</Badge>
          <span className="muted small"><User size={13} style={{ verticalAlign: -2 }} /> {a.author ?? 'unknown'} · <CalendarDays size={13} style={{ verticalAlign: -2 }} /> answered {timeAgo(a.createdAt)} ({dateOnly(a.createdAt)})</span></div>
        {href && <a className="small" href={href} target="_blank" rel="noreferrer noopener">Original <ExternalLink size={12} style={{ verticalAlign: -1 }} /></a>}
      </div>
      <div style={{ marginTop: 14 }}><Markdown text={a.body ?? ''} /></div>
      {comments.length > 0 && <div className="comments"><h4>Comments</h4>{comments.map((c, i) => <p key={i} className="small"><b>{c.author ?? 'unknown'}:</b> {c.body}</p>)}</div>}
    </Card>
  )
}

/** Scroll to (and briefly highlight) the page section a search result came from: the deepest heading of its path. */
function FocusSection({ section, ready }: { section?: string | null; ready: boolean }) {
  useEffect(() => {
    if (!ready || !section) return
    const own = section.split(' › ').pop() ?? section
    const t = window.setTimeout(() => {
      const el = Array.from(document.querySelectorAll<HTMLElement>('[data-heading]')).find((h) => h.dataset.heading === own)
      if (!el) return
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
      el.classList.add('md-h-focus')
      window.setTimeout(() => el.classList.remove('md-h-focus'), 2500)
    }, 80)
    return () => window.clearTimeout(t)
  }, [section, ready])
  return null
}

/** "On this page": the page's headings, so a long wiki page can be read section by section. */
function PageOutline({ body }: { body: string }) {
  const headings: { level: number; text: string }[] = []
  let fence = false
  for (const line of body.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fence = !fence
    const m = fence ? null : /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line)
    if (m) headings.push({ level: m[1].length, text: plainHeading(m[2]) })
  }
  if (headings.length < 3) return null
  const top = Math.min(...headings.map((h) => h.level))
  const jump = (text: string) => Array.from(document.querySelectorAll<HTMLElement>('[data-heading]')).find((h) => h.dataset.heading === text)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  return (
    <Card className="rise">
      <h3>On this page</h3>
      <div className="stack-sm" style={{ marginTop: 8, gap: 4 }}>
        {headings.map((h, i) => <button key={i} type="button" className="linklike small" style={{ paddingLeft: (h.level - top) * 14, textAlign: 'left' }} onClick={() => jump(h.text)}>{h.text}</button>)}
      </div>
    </Card>
  )
}

/** Scroll to a linked answer once the thread has loaded (hash routing cannot use #answer-… fragments). */
function FocusAnswer({ answerId, ready }: { answerId?: string | null; ready: boolean }) {
  useEffect(() => {
    if (!ready || !answerId) return
    const t = window.setTimeout(() => {
      document.getElementById(`answer-${answerId}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 50)
    return () => window.clearTimeout(t)
  }, [answerId, ready])
  return null
}

function CategoryEditor({ view, onDone }: { view: ThreadView; onDone: () => void }) {
  const toast = useToast()
  const cats = useRemote((s) => getCategories(s), [])
  const [sel, setSel] = useState<string[]>(view.categories)
  const [busy, setBusy] = useState(false)
  if (cats.kind !== 'ok') return <Skeleton h={80} />
  const id = view.thread.question!.id
  return (
    <div className="stack-sm">
      <div className="row" style={{ gap: 6 }}>{cats.data.map((c) => (
        <Chip key={c.id} active={sel.includes(c.id)} onClick={() => setSel(sel.includes(c.id) ? sel.filter((x) => x !== c.id) : [...sel, c.id])}>{c.name}</Chip>))}</div>
      <div className="row">
        <Button size="sm" variant="primary" busy={busy} disabled={sel.length === 0} onClick={async () => { setBusy(true); try { await setOverride(id, sel, 'Set in the app'); toast('ok', 'Topics updated'); onDone() } catch (e) { toast('bad', e instanceof Error ? e.message : 'Failed') } finally { setBusy(false) } }}>Save topics</Button>
        {view.override && <Button size="sm" icon={<RotateCcw size={14} />} onClick={async () => { await clearOverride(id); toast('ok', 'Back to automatic topics'); onDone() }}>Use automatic</Button>}
        <Button size="sm" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </div>
  )
}

export default function QuestionPage({ id, focusAnswerId, focusSection }: { id: string; focusAnswerId?: string | null; focusSection?: string | null }) {
  const r = useRemote((s) => getQuestion(id, s), [id])
  const [editing, setEditing] = useState(false)
  if (r.kind === 'loading') return <div className="stack"><Skeleton h={50} w={420} /><Skeleton h={220} r={18} /><Skeleton h={160} r={18} /></div>
  if (r.kind === 'error') return <div className="stack"><Button variant="ghost" icon={<ArrowLeft size={16} />} onClick={() => history.back()}>Back</Button><Banner tone="bad" title="That item could not be opened">{r.message}</Banner></div>
  const v = r.data
  const q = v.thread.question
  if (!q) return <EmptyState icon={<ShieldAlert size={30} />} title={`This ${termsForSystem(v.thread.sourceSystem).item} was removed`}>Its content was erased after the retention period because the site deleted or hid it.</EmptyState>
  const href = safeUrl(q.url)
  const answers = [...(q.answers ?? [])].sort((a, b) => Number(b.accepted) - Number(a.accepted) || (b.score ?? 0) - (a.score ?? 0))
  const sourceSystem = (v.thread.sourceSystem ?? '').toLowerCase()
  const isConfluence = sourceSystem.startsWith('confluence')
  const isJive = sourceSystem.startsWith('jive')
  const isSharePoint = sourceSystem.startsWith('sharepoint')
  const t = termsForSystem(v.thread.sourceSystem)
  const isWiki = isConfluence || isJive || isSharePoint
  const wikiKind = isJive ? 'Jive' : isConfluence ? 'Confluence' : isSharePoint ? 'SharePoint' : 'Wiki'
  const showWikiReplies = isJive && answers.length > 0
  return (
    <div className="stack" style={{ gap: 24 }}>
      <FocusAnswer answerId={focusAnswerId} ready={r.kind === 'ok'} />
      <FocusSection section={focusSection} ready={r.kind === 'ok'} />
      <div><Button variant="ghost" size="sm" icon={<ArrowLeft size={16} />} onClick={() => history.back()}>Back</Button></div>
      <header className="rise">
        <div className="row" style={{ marginBottom: 12 }}>{v.thread.status !== 'ACTIVE' && <Badge tone="warn">{v.thread.status.toLowerCase().replace('_', ' ')}</Badge>}
          {v.categoryNames.map((n) => <Badge key={n} tone="brand">{n}</Badge>)}</div>
        <h1 style={{ fontSize: 30 }}>{q.title}</h1>
        <p className="muted" style={{ marginTop: 12 }}>{isWiki
          ? <>Created {timeAgo(q.createdAt)} ({dateOnly(q.createdAt)}) by {q.author ?? 'unknown'} · {wikiKind} {t.item}{showWikiReplies ? ` · ${answers.length} repl${answers.length === 1 ? 'y' : 'ies'}` : ''}</>
          : <>Asked {timeAgo(q.createdAt)} ({dateOnly(q.createdAt)}) by {q.author ?? 'unknown'} · {q.score ?? '?'} votes · {answers.length} answer{answers.length === 1 ? '' : 's'}</>}</p>
      </header>
      {v.thread.statusReason && <Banner tone="warn" title="Status">{v.thread.statusReason}</Banner>}
      <div className="qlayout">
        <div className="stack">
          <Card className="rise">
            <div className="ribbon neutral">{t.Item}</div>
            <Markdown text={q.body} />
            <div className="row" style={{ marginTop: 18, gap: 6 }}>{q.tags.map((t) => <Chip key={t} onClick={() => go(`/explore?q=${encodeURIComponent(q.title)}&tag=${encodeURIComponent(t)}`)}>{t}</Chip>)}</div>
          </Card>
          {!isWiki && <h2 id="answers">{answers.length === 0 ? 'No answers yet' : `${answers.length} answer${answers.length === 1 ? '' : 's'}`}</h2>}
          {!isWiki && answers.length === 0 && <Banner tone="info">Nobody had answered this when it was last collected.</Banner>}
          {isWiki && (q.comments?.length ?? 0) > 0 && (
            <Card className="rise">
              <h3>Page comments</h3>
              <div className="comments">{q.comments!.map((c, i) => <p key={i} className="small"><b>{c.author ?? 'unknown'}:</b> {c.body}</p>)}</div>
            </Card>
          )}
          {showWikiReplies && <h2 id="answers">{answers.length} repl{answers.length === 1 ? 'y' : 'ies'}</h2>}
          {showWikiReplies && answers.map((a, i) => <AnswerCard key={a.id ?? `answer-${i}`} a={a} />)}
          {!isWiki && answers.map((a, i) => <AnswerCard key={a.id ?? `answer-${i}`} a={a} />)}
        </div>
        <aside className="stack-sm qside">
          {isWiki && <PageOutline body={q.body ?? ''} />}
          <Card>
            <h4>About this {t.item}</h4>
            <dl className="facts one">
              <div><dt>{isWiki ? 'Created' : 'Asked'}</dt><dd>{dateOnly(q.createdAt)}</dd></div>
              <div><dt>Last changed on the site</dt><dd>{dateOnly(q.modifiedAt)} ({timeAgo(q.modifiedAt)})</dd></div>
              <div><dt>Saved on this computer</dt><dd>{timeAgo(v.thread.retrievedAt)}</dd></div>
            </dl>
            {href && <Button style={{ marginTop: 14, width: '100%' }} icon={<ExternalLink size={15} />} onClick={() => window.open(href, '_blank', 'noopener,noreferrer')}>Open original</Button>}
          </Card>
          <Card>
            <div className="spread"><h4>Topics <InfoTip term="category" /></h4>{!editing && <Button size="sm" variant="ghost" icon={<Pencil size={14} />} onClick={() => setEditing(true)}>Change</Button>}</div>
            {editing ? <div style={{ marginTop: 12 }}><CategoryEditor view={v} onDone={() => { setEditing(false); r.reload() }} /></div> : (
              <>
                <div className="row" style={{ gap: 6, margin: '10px 0' }}>{v.categoryNames.map((n) => <Chip key={n}>{n}</Chip>)}</div>
                {v.override ? <Badge tone="info">Chosen by a person</Badge> : <Badge tone="neutral">Chosen by rules</Badge>}
                {v.classification && (
                  <details className="why"><summary>Why these topics?</summary>
                    {v.classification.categories.map((c) => <div key={c.categoryId} className="small"><b>{v.categoryNames[v.categories.indexOf(c.categoryId)] ?? c.categoryId}</b> ({c.score} points)<ul>{c.matchedRules.map((m) => <li key={m}>{friendlyRule(m)}</li>)}</ul></div>)}</details>)}
              </>
            )}
          </Card>
          <Banner tone="neutral">Everything here was written by other people and is shown as reading material only. Always check the original source before relying on it.</Banner>
        </aside>
      </div>
    </div>
  )
}
