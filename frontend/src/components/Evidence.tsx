import type { Evidence } from '../types/api'
import { safeUrl } from '../url'

export function EvidenceCard({ e }: { e: Evidence }) {
  const href = safeUrl(e.sourceUrl)
  return (
    <article className="card">
      <header className="card-head">
        <span className="badge">{e.type === 'answer' ? `Answer ${e.answerId}` : 'Question'}</span>
        {e.accepted && <span className="badge ok">Accepted</span>}
        {e.threadStatus !== 'ACTIVE' && <span className="badge warn">{e.threadStatus}</span>}
        {e.flags.length > 0 && <span className="badge danger">Possible injected instructions</span>}
        <span className="muted">votes {e.votes ?? 'unknown'} · relevance {e.score} · coverage {e.termCoverage}</span>
      </header>
      <h3><a href={`#/question/${encodeURIComponent(e.questionId)}`}>{e.title}</a></h3>
      <pre className="passage" aria-label="Untrusted crawled passage">{e.passage}</pre>
      <footer className="muted">
        {href ? <a href={href} target="_blank" rel="noreferrer noopener">Original source</a> : 'Source URL unknown'}
        {' · '}{e.categoryNames.join(', ')}
        {' · '}modified {e.sourceModifiedAt ?? 'unknown'} · synced {e.lastSyncedAt ?? 'unknown'}
      </footer>
    </article>
  )
}
