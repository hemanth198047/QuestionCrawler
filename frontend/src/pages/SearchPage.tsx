import { useState } from 'react'
import { search } from '../api/knowledge'
import { EvidenceCard } from '../components/Evidence'
import { ErrorBox, FreshnessNote } from '../components/Freshness'
import { useRemote } from '../hooks'

export default function SearchPage({ params }: { params: URLSearchParams }) {
  const q = params.get('q') ?? ''
  const tag = params.get('tag') ?? ''
  const [input, setInput] = useState(q)
  const [tagInput, setTagInput] = useState(tag)
  const result = useRemote(
    (signal) => (q.trim() ? search({ q, tags: tag ? [tag] : [], category: '', top: 8 }, signal) : Promise.resolve(null)),
    [q, tag],
  )

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const next = new URLSearchParams({ q: input })
    if (tagInput.trim()) next.set('tag', tagInput.trim())
    window.location.hash = `/search?${next.toString()}`
  }

  return (
    <section>
      <form onSubmit={submit} className="search-form">
        <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Error message, identifier or question" aria-label="Search" />
        <input value={tagInput} onChange={(e) => setTagInput(e.target.value)} placeholder="tag (optional)" aria-label="Tag filter" className="narrow" />
        <button type="submit">Search</button>
      </form>
      {result.kind === 'loading' && q && <p>Searching…</p>}
      {result.kind === 'error' && <ErrorBox message={result.message} />}
      {result.kind === 'ok' && result.data && (
        <>
          <FreshnessNote f={result.data.freshness} />
          <p className="muted">{result.data.resultCount} evidence passage(s) · confidence {result.data.confidence} (heuristic)</p>
          <p className="notice">{result.data.notice}</p>
          {result.data.resultCount === 0 && <p>No sufficiently relevant evidence in the knowledge store.</p>}
          {result.data.evidence.map((e) => <EvidenceCard key={`${e.questionId}-${e.answerId ?? 'q'}`} e={e} />)}
          {result.data.truncated && <p className="muted">{result.data.omittedForBudget} lower-ranked passage(s) omitted (output budget).</p>}
        </>
      )}
    </section>
  )
}
