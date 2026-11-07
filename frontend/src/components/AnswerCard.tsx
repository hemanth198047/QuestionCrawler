import { BookOpenCheck, ExternalLink, SearchX } from 'lucide-react'
import { safeUrl } from '../lib/url'
import type { DirectAnswer } from '../types/api'
import { Badge, Card } from './ui'
import { Markdown } from './Markdown'

const CONFIDENCE: Record<string, { label: string; tone: 'ok' | 'info' | 'warn' | 'neutral' }> = {
  HIGH: { label: 'Clear answer', tone: 'ok' },
  MEDIUM: { label: 'Likely answer', tone: 'info' },
  LOW: { label: 'Possible answer', tone: 'warn' },
  NONE: { label: 'No clear answer', tone: 'neutral' },
}

/** The best answer found in the collected pages: quoted word for word, each piece with the page and section it comes from. */
export function AnswerCard({ answer }: { answer: DirectAnswer }) {
  const conf = CONFIDENCE[answer.confidence] ?? CONFIDENCE.NONE
  if (answer.status !== 'FOUND' || answer.points.length === 0) {
    return (
      <Card className="rise answer-card">
        <div className="row" style={{ gap: 10 }}><SearchX size={20} color="var(--ink-3)" /><strong>No page clearly answers this</strong><Badge tone="neutral">{conf.label}</Badge></div>
        <p className="muted small" style={{ marginTop: 8 }}>{answer.note}</p>
      </Card>
    )
  }
  return (
    <Card className="rise answer-card">
      <div className="spread">
        <div className="row" style={{ gap: 10 }}><BookOpenCheck size={21} color="var(--brand)" /><h3 style={{ margin: 0 }}>Best answer from your pages</h3></div>
        <Badge tone={conf.tone}>{conf.label}</Badge>
      </div>
      <div className="stack-sm" style={{ marginTop: 14 }}>
        {answer.points.map((p, i) => {
          const original = safeUrl(p.sectionUrl ?? p.sourceUrl)
          const inApp = `#/question/${encodeURIComponent(p.questionId)}${p.answerId ? `?answer=${encodeURIComponent(p.answerId)}` : p.section ? `?section=${encodeURIComponent(p.section)}` : ''}`
          return (
            <div key={i} className="answer-point">
              <blockquote style={{ margin: 0 }}><Markdown text={p.text} compact /></blockquote>
              <div className="row small muted" style={{ gap: 12, marginTop: 8, flexWrap: 'wrap' }}>
                <span>From <a href={inApp}><b>{p.title}</b>{p.section ? ` › ${p.section}` : ''}</a>{p.evidenceRank > 0 ? ` · result ${p.evidenceRank} below` : ''}</span>
                {original && <a href={original} target="_blank" rel="noreferrer noopener">Open original{p.section ? ' at this section' : ''} <ExternalLink size={12} style={{ verticalAlign: -1 }} /></a>}
              </div>
            </div>
          )
        })}
      </div>
      <p className="muted small" style={{ marginTop: 12 }}>{answer.note}</p>
    </Card>
  )
}
