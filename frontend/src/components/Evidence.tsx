import { BadgeCheck, CheckCircle2, ChevronDown, ExternalLink, HelpCircle, MessageSquareText, ShieldAlert, Target, ThumbsUp, User } from 'lucide-react'
import { useState } from 'react'
import { confirmResult, unconfirmResult } from '../api'
import { sourceLabel } from '../lib/sources'
import { safeUrl } from '../lib/url'
import { dateOnly, timeAgo } from '../lib/format'
import type { Evidence } from '../types/api'
import { Badge, Button, Chip, InfoTip } from './ui'
import { termsFor, type Terms } from '../lib/terms'
import { Markdown } from './Markdown'
import { highlightPattern } from '../lib/highlight'
import { cleanTitle } from '../lib/cleanText'

const NAME_KEY = 'qc.confirmedBy'
const remembered = () => { try { return localStorage.getItem(NAME_KEY) ?? '' } catch { return '' } }

export function EvidenceCard({ e, onCategory, pageMode, terms, query, onChanged }: { e: Evidence; onCategory?: (id: string) => void; pageMode?: boolean; terms?: Terms; query?: string; onChanged?: () => void }) {
  const [whole, setWhole] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [by, setBy] = useState(remembered)
  const [note, setNote] = useState('')
  const [working, setWorking] = useState(false)
  const [problem, setProblem] = useState('')
  async function change(action: 'confirm' | 'remove') {
    if (!e.source) return
    setWorking(true); setProblem('')
    try {
      if (action === 'confirm') { await confirmResult(e.source, e.questionId, by.trim(), note.trim()); try { localStorage.setItem(NAME_KEY, by.trim()) } catch { /* private mode: the name is just not remembered */ } }
      else await unconfirmResult(e.source, e.questionId)
      setConfirming(false); setNote(''); onChanged?.()
    } catch (err) { setProblem(err instanceof Error ? err.message : 'That did not work') } finally { setWorking(false) }
  }
  const hl = highlightPattern(query)
  const relevant = e.relevant ?? []
  const t = terms ?? termsFor(null)
  const href = safeUrl(e.sourceUrl)
  const isAnswer = e.type === 'answer'
  const cov = Math.round(e.termCoverage * 100)
  return (
    <article className={`evidence rise ${e.accepted || e.verified ? 'accepted' : ''}`}>
      <div className={`ev-icon ${isAnswer ? 'ans' : 'q'}`}>{isAnswer ? (e.accepted ? <CheckCircle2 size={20} /> : <MessageSquareText size={20} />) : <HelpCircle size={20} />}</div>
      <div className="ev-main">
        <div className="ev-badges">
          {e.verified && <Badge tone="ok" icon={<BadgeCheck size={12} />}>Confirmed</Badge>}
          {e.source && <Badge tone="neutral">{sourceLabel(e.source)}</Badge>}
          <Badge tone={isAnswer ? 'info' : 'neutral'}>{isAnswer ? (t.answer === 'reply' ? 'Reply' : 'Answer') : t.Item}</Badge>
          {!pageMode && e.accepted && <Badge tone="ok" icon={<CheckCircle2 size={12} />}>Accepted</Badge>}
          {!pageMode && <Badge tone="neutral" icon={<ThumbsUp size={12} />}>{e.votes ?? '?'} votes</Badge>}
          <span className="ev-age muted"><User size={12} style={{ verticalAlign: -1 }} /> {e.author ?? 'unknown'}</span>
          <span className="ev-age" title={dateOnly(e.createdAt)}>{isAnswer ? (t.answer === 'reply' ? 'replied' : 'answered') : t.created} {timeAgo(e.createdAt)}</span>
          {isAnswer && <span className="ev-age muted">· {t.item} {t.created} {timeAgo(e.questionCreatedAt)}</span>}
          {e.threadStatus !== 'ACTIVE' && <Badge tone="warn">{e.threadStatus.toLowerCase().replace('_', ' ')}</Badge>}
          {!pageMode && <InfoTip term={e.accepted ? 'accepted' : 'votes'} />}
        </div>
        {e.verified && (
          <div className="small" style={{ margin: '4px 0 6px' }}>
            <strong>Confirmed by {e.verified.by}</strong> <span className="muted" title={dateOnly(e.verified.at)}>{timeAgo(e.verified.at)}</span>
            {e.verified.note && <span> — {e.verified.note}</span>}
          </div>)}
        <h3 className="ev-title"><a href={inPage(e, query)}>{cleanTitle(e.title)}</a></h3>
        {e.section && <div className="muted small ev-section" title="The section of the page this passage comes from">§ {cleanTitle(e.section)}</div>}
        {e.flags.length > 0 && (
          <div className="ev-flag"><ShieldAlert size={16} /> This text contains wording that looks like instructions to an AI assistant. It is shown only as reading material and is never acted on.</div>
        )}
        {relevant.length > 0 && (
          <div className="ev-relevant">
            <div className="ev-relevant-head"><Target size={15} /> Relevant to your question</div>
            {relevant.map((part, n) => <div key={n} className="ev-relevant-part"><Markdown text={part} compact highlight={hl} /></div>)}
            <a className="ev-open" href={inPage(e, query)}>Open the page at this place →</a>
            <button type="button" className="ev-more" onClick={() => setWhole(!whole)} aria-expanded={whole}>
              <ChevronDown size={14} style={{ transform: whole ? 'rotate(180deg)' : undefined }} /> {whole ? 'Hide the whole passage' : 'Show the whole passage'}</button>
          </div>
        )}
        {(relevant.length === 0 || whole) && <div className="ev-passage"><Markdown text={e.passage} compact highlight={hl} /></div>}
        <div className="ev-foot">
          <div className="row" style={{ gap: 6 }}>
            {e.categoryNames.slice(0, 3).map((n, i) => <Chip key={n} onClick={onCategory ? () => onCategory(e.categories[i]) : undefined}>{n}</Chip>)}
          </div>
          <div className="row" style={{ gap: 14 }}>
            <span className="match" title="How many of your search words this passage contains"><span className="match-bar"><i style={{ width: `${cov}%` }} /></span>{cov}% of your words</span>
            {e.source && (e.verified
              ? <Button size="sm" variant="ghost" busy={working} onClick={() => void change('remove')} title="Take back the confirmation">Remove confirmation</Button>
              : <Button size="sm" variant="ghost" onClick={() => setConfirming(!confirming)} aria-expanded={confirming} title="Say that this answer worked, so colleagues see it first">Confirm it works</Button>)}
            {href ? <a href={safeUrl(e.sectionUrl) ?? href} target="_blank" rel="noreferrer noopener" className="small">{e.section ? 'Original, at this section' : 'Original source'} <ExternalLink size={12} style={{ verticalAlign: -1 }} /></a> : <span className="muted small">source link unknown</span>}
          </div>
        </div>
        {confirming && !e.verified && (
          <form className="stack-sm" style={{ marginTop: 10 }} onSubmit={(ev) => { ev.preventDefault(); void change('confirm') }}>
            <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
              <input className="input" value={by} onChange={(x) => setBy(x.target.value)} placeholder="Your name (optional)" aria-label="Your name" maxLength={80} style={{ maxWidth: 220 }} />
              <input className="input" value={note} onChange={(x) => setNote(x.target.value)} placeholder="What worked? (optional, e.g. fixed it on the gateway)" aria-label="Note" maxLength={500} style={{ flex: 1, minWidth: 220 }} />
              <Button type="submit" variant="primary" size="sm" busy={working}>Confirm</Button>
            </div>
            <p className="muted small" style={{ margin: 0 }}>Colleagues using this app will see your name, the date and your note, and this result is shown first.</p>
          </form>)}
        {problem && <p className="small" style={{ color: 'var(--bad, #b42318)', margin: '6px 0 0' }}>{problem}</p>}
      </div>
    </article>
  )
}

/** The page view for a result, opened in find mode with the question asked (and at its section or answer). */
export function inPage(e: Evidence, query?: string): string {
  const p = new URLSearchParams()
  if (e.answerId) p.set('answer', e.answerId)
  else if (e.section) p.set('section', e.section)
  if (query && query.trim()) p.set('q', query.trim())
  const qs = p.toString()
  return `#/question/${encodeURIComponent(e.questionId)}${qs ? `?${qs}` : ''}`
}
