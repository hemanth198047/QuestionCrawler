import { CheckCircle2, ChevronDown, ExternalLink, HelpCircle, MessageSquareText, ShieldAlert, Target, ThumbsUp, User } from 'lucide-react'
import { useState } from 'react'
import { safeUrl } from '../lib/url'
import { dateOnly, timeAgo } from '../lib/format'
import type { Evidence } from '../types/api'
import { Badge, Chip, InfoTip } from './ui'
import { termsFor, type Terms } from '../lib/terms'
import { Markdown } from './Markdown'
import { highlightPattern } from '../lib/highlight'

export function EvidenceCard({ e, onCategory, pageMode, terms, query }: { e: Evidence; onCategory?: (id: string) => void; pageMode?: boolean; terms?: Terms; query?: string }) {
  const [whole, setWhole] = useState(false)
  const hl = highlightPattern(query)
  const relevant = e.relevant ?? []
  const t = terms ?? termsFor(null)
  const href = safeUrl(e.sourceUrl)
  const isAnswer = e.type === 'answer'
  const cov = Math.round(e.termCoverage * 100)
  return (
    <article className={`evidence rise ${e.accepted ? 'accepted' : ''}`}>
      <div className={`ev-icon ${isAnswer ? 'ans' : 'q'}`}>{isAnswer ? (e.accepted ? <CheckCircle2 size={20} /> : <MessageSquareText size={20} />) : <HelpCircle size={20} />}</div>
      <div className="ev-main">
        <div className="ev-badges">
          <Badge tone={isAnswer ? 'info' : 'neutral'}>{isAnswer ? (t.answer === 'reply' ? 'Reply' : 'Answer') : t.Item}</Badge>
          {!pageMode && e.accepted && <Badge tone="ok" icon={<CheckCircle2 size={12} />}>Accepted</Badge>}
          {!pageMode && <Badge tone="neutral" icon={<ThumbsUp size={12} />}>{e.votes ?? '?'} votes</Badge>}
          <span className="ev-age muted"><User size={12} style={{ verticalAlign: -1 }} /> {e.author ?? 'unknown'}</span>
          <span className="ev-age" title={dateOnly(e.createdAt)}>{isAnswer ? (t.answer === 'reply' ? 'replied' : 'answered') : t.created} {timeAgo(e.createdAt)}</span>
          {isAnswer && <span className="ev-age muted">· {t.item} {t.created} {timeAgo(e.questionCreatedAt)}</span>}
          {e.threadStatus !== 'ACTIVE' && <Badge tone="warn">{e.threadStatus.toLowerCase().replace('_', ' ')}</Badge>}
          {!pageMode && <InfoTip term={e.accepted ? 'accepted' : 'votes'} />}
        </div>
        <h3 className="ev-title"><a href={e.answerId
          ? `#/question/${encodeURIComponent(e.questionId)}?answer=${encodeURIComponent(e.answerId)}`
          : `#/question/${encodeURIComponent(e.questionId)}${e.section ? `?section=${encodeURIComponent(e.section)}` : ''}`}>{e.title}</a></h3>
        {e.section && <div className="muted small ev-section" title="The section of the page this passage comes from">§ {e.section}</div>}
        {e.flags.length > 0 && (
          <div className="ev-flag"><ShieldAlert size={16} /> This text contains wording that looks like instructions to an AI assistant. It is shown only as reading material and is never acted on.</div>
        )}
        {relevant.length > 0 && (
          <div className="ev-relevant">
            <div className="ev-relevant-head"><Target size={15} /> Relevant to your question</div>
            {relevant.map((part, n) => <div key={n} className="ev-relevant-part"><Markdown text={part} compact highlight={hl} /></div>)}
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
            {href ? <a href={safeUrl(e.sectionUrl) ?? href} target="_blank" rel="noreferrer noopener" className="small">{e.section ? 'Original, at this section' : 'Original source'} <ExternalLink size={12} style={{ verticalAlign: -1 }} /></a> : <span className="muted small">source link unknown</span>}
          </div>
        </div>
      </div>
    </article>
  )
}
