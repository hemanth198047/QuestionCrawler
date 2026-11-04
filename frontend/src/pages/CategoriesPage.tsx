import { useState } from 'react'
import { getCategories, getCategory } from '../api/knowledge'
import { ErrorBox } from '../components/Freshness'
import { useRemote } from '../hooks'

export default function CategoriesPage({ selected }: { selected: string | null }) {
  const list = useRemote((signal) => getCategories(signal), [])
  const [maxAge, setMaxAge] = useState('')
  const detail = useRemote((signal) => (selected ? getCategory(selected, maxAge, signal) : Promise.resolve(null)), [selected, maxAge])
  return (
    <section className="two-col">
      <div>
        <h2>Categories</h2>
        <p className="muted">Assigned by configurable rules (see the taxonomy file), not by semantic understanding.</p>
        {list.kind === 'error' && <ErrorBox message={list.message} />}
        {list.kind === 'ok' && (
          <ul className="plain">
            {list.data.map((c) => (
              <li key={c.id}>
                <a href={`#/categories/${c.id}`} className={c.id === selected ? 'active' : ''}>{c.name}</a>{' '}
                <span className="muted">({c.questionCount})</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div>
        {detail.kind === 'ok' && detail.data && (
          <>
            <h2>{detail.data.category.name}</h2>
            <p className="muted">{detail.data.category.description}</p>
            <label className="muted">Questions newer than{' '}
              <input value={maxAge} onChange={(e) => setMaxAge(e.target.value.trim())} placeholder="e.g. 90d" size={8} />
            </label>
            {detail.data.questions.map((q) => (
              <p key={q.id}>
                <a href={`#/question/${encodeURIComponent(q.id)}`}>{q.title}</a>{' '}
                <span className="muted">asked {q.createdAt ?? 'unknown'} · {q.answerCount} answer(s){q.hasAcceptedAnswer ? ', accepted' : ''}{q.status !== 'ACTIVE' ? `, ${q.status}` : ''}</span>
              </p>
            ))}
            {detail.data.questions.length === 0 && <p className="muted">No questions in this category.</p>}
          </>
        )}
        {detail.kind === 'error' && <ErrorBox message={detail.message} />}
      </div>
    </section>
  )
}
