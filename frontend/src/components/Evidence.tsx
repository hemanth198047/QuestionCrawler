import { CheckCircle2, ExternalLink, HelpCircle, MessageSquareText, ShieldAlert, ThumbsUp, User } from 'lucide-react'
import { safeUrl } from '../lib/url'
import { dateOnly, timeAgo } from '../lib/format'
import type { Evidence } from '../types/api'
import { Badge, Chip, InfoTip } from './ui'
import { Markdown } from './Markdown'

export function EvidenceCard({ e, onCategory, pageMode }: { e: Evidence; onCategory?: (id: string) => void; pageMode?: boolean }) {
  const href = safeUrl(e.sourceUrl)
  const isAnswer = e.type === 'answer'
  const cov = Math.round(e.termCoverage * 100)
  return (
    <article className={`evidence rise ${e.accepted ? 'accepted' : ''}`}>
      <div className={`ev-icon ${isAnswer ? 'ans' : 'q'}`}>{isAnswer ? (e.accepted ? <CheckCircle2 size={20} /> : <MessageSquareText size={20} />) : <HelpCircle size={20} />}</div>
      <div className="ev-main">
        <div className="ev-badges">
          <Badge tone={isAnswer ? 'info' : 'neutral'}>{pageMode && !isAnswer ? 'Page' : isAnswer ? 'Answer' : 'Question'}</Badge>
          {!pageMode && e.accepted && <Badge tone="ok" icon={<CheckCircle2 size={12} />}>Accepted</Badge>}
          {!pageMode && <Badge tone="neutral" icon={<ThumbsUp size={12} />}>{e.votes ?? '?'} votes</Badge>}
          <span className="ev-age muted"><User size={12} style={{ verticalAlign: -1 }} /> {e.author ?? 'unknown'}</span>
          <span className="ev-age" title={dateOnly(e.createdAt)}>{pageMode && !isAnswer ? 'updated' : isAnswer ? 'answered' : 'asked'} {timeAgo(e.createdAt)}</span>
          {isAnswer && <span className="ev-age muted">· question asked {timeAgo(e.questionCreatedAt)}</span>}
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
        <div className="ev-passage"><Markdown text={e.passage} compact /></div>
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
