import { BadgeCheck, Database, ExternalLink } from 'lucide-react'
import { excerpt } from '../lib/combined'
import { cleanTitle } from '../lib/cleanText'
import { sourceLabel } from '../lib/sources'
import { safeUrl } from '../lib/url'
import type { Evidence } from '../types/api'
import { inPage } from './Evidence'
import { Badge, Card } from './ui'

/** What is already stored on this computer for a question, numbered like citations, each with its source and a link to the original. */
export function LocalFindings({ evidence, query, title = 'Already stored on this computer' }: { evidence: Evidence[]; query: string; title?: string }) {
  if (evidence.length === 0) return null
  return (
    <Card className="rise">
      <div className="row" style={{ marginBottom: 10 }}><Database size={19} color="var(--brand)" /><h3>{title}</h3><Badge tone="neutral">{evidence.length} source{evidence.length === 1 ? '' : 's'}</Badge></div>
      <ol className="stack-sm" style={{ margin: 0, paddingLeft: 22 }}>
        {evidence.map((e, i) => {
          const original = safeUrl(e.sectionUrl) ?? safeUrl(e.sourceUrl)
          return (
            <li key={`${e.source}-${e.questionId}-${e.answerId ?? 'q'}-${e.sectionIndex}`} id={`source-${i + 1}`}>
              <div className="row" style={{ flexWrap: 'wrap', gap: 6 }}>
                <strong>[S{i + 1}]</strong>
                <a href={inPage(e, query)}>{cleanTitle(e.title)}</a>
                {e.source && <Badge tone="neutral">{sourceLabel(e.source)}</Badge>}
                {e.verified && <Badge tone="ok" icon={<BadgeCheck size={12} />}>Confirmed by {e.verified.by}</Badge>}
                {original && <a href={original} target="_blank" rel="noreferrer noopener" className="small">Original <ExternalLink size={12} style={{ verticalAlign: -1 }} /></a>}
              </div>
              {e.verified?.note && <div className="small muted">“{e.verified.note}”</div>}
              <div className="small" style={{ overflowWrap: 'anywhere' }}>{excerpt(e).slice(0, 400)}</div>
            </li>)
        })}
      </ol>
    </Card>
  )
}
