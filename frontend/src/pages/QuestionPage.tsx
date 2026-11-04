import { getQuestion } from '../api/knowledge'
import { safeUrl } from '../url'
import { ErrorBox } from '../components/Freshness'
import { useRemote } from '../hooks'

export default function QuestionPage({ id }: { id: string }) {
  const result = useRemote((signal) => getQuestion(id, signal), [id])
  if (result.kind === 'loading') return <p>Loading…</p>
  if (result.kind === 'error') return <ErrorBox message={result.message} />
  const view = result.data
  const q = view.thread.question
  if (!q) return <p>This question's content was purged by the retention policy.</p>
  const href = safeUrl(q.url)
  return (
    <section>
      <h2>{q.title}</h2>
      <p className="notice">{view.notice}</p>
      <p className="muted">
        Status {view.thread.status} · categories {view.categoryNames.join(', ')}{view.override ? ' (manual override)' : ''} ·
        tags {q.tags.join(', ')} · modified {q.modifiedAt ?? 'unknown'} · synced {view.thread.retrievedAt ?? 'unknown'}
        {href && <> · <a href={href} target="_blank" rel="noreferrer noopener">Original question</a></>}
      </p>
      {view.classification && (
        <details>
          <summary>Why these categories? (rule-based, not semantic)</summary>
          <ul>{view.classification.categories.map((c) => (
            <li key={c.categoryId}><strong>{c.categoryId}</strong> (score {c.score}): {c.matchedRules.join('; ')}</li>
          ))}</ul>
        </details>
      )}
      <pre className="passage">{q.body}</pre>
      <h3>{q.answers.length} answer(s)</h3>
      {q.answers.length === 0 && <p className="muted">No answers were available when this was collected.</p>}
      {q.answers.map((a) => {
        const aHref = safeUrl(a.url)
        return (
          <article className="card" key={a.id}>
            <header className="card-head">
              <span className="badge">Answer {a.id}</span>
              {a.accepted && <span className="badge ok">Accepted</span>}
              <span className="muted">votes {a.score ?? 'unknown'} · modified {a.modifiedAt ?? 'unknown'}</span>
            </header>
            <pre className="passage">{a.body}</pre>
            {aHref && <footer><a href={aHref} target="_blank" rel="noreferrer noopener">Original answer</a></footer>}
          </article>
        )
      })}
    </section>
  )
}
