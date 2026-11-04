import { useState } from 'react'
import { search, type AgeParams } from '../api/knowledge'
import { EvidenceCard } from '../components/Evidence'
import { ErrorBox, FreshnessNote } from '../components/Freshness'
import { useRemote } from '../hooks'

const AGE_FIELDS: { key: keyof AgeParams; label: string }[] = [
  { key: 'maxQuestionAge', label: 'Questions newer than' },
  { key: 'minQuestionAge', label: 'Questions older than' },
  { key: 'maxAnswerAge', label: 'Answers newer than' },
  { key: 'minAnswerAge', label: 'Answers older than' },
]

export default function SearchPage({ params }: { params: URLSearchParams }) {
  const q = params.get('q') ?? ''
  const tag = params.get('tag') ?? ''
  const age: AgeParams = {
    maxQuestionAge: params.get('maxQuestionAge') ?? '',
    minQuestionAge: params.get('minQuestionAge') ?? '',
    maxAnswerAge: params.get('maxAnswerAge') ?? '',
    minAnswerAge: params.get('minAnswerAge') ?? '',
  }
  const [input, setInput] = useState(q)
  const [tagInput, setTagInput] = useState(tag)
  const [ageInput, setAgeInput] = useState<AgeParams>(age)
  const result = useRemote(
    (signal) => (q.trim() ? search({ q, tags: tag ? [tag] : [], category: '', top: 8, age }, signal) : Promise.resolve(null)),
    [params.toString()],
  )

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const next = new URLSearchParams({ q: input })
    if (tagInput.trim()) next.set('tag', tagInput.trim())
    AGE_FIELDS.forEach(({ key }) => { if (ageInput[key].trim()) next.set(key, ageInput[key].trim()) })
    window.location.hash = `/search?${next.toString()}`
  }

  const hasAge = AGE_FIELDS.some(({ key }) => age[key])

  return (
    <section>
      <form onSubmit={submit} className="search-form">
        <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Error message, identifier or question" aria-label="Search" />
        <input value={tagInput} onChange={(e) => setTagInput(e.target.value)} placeholder="tag (optional)" aria-label="Tag filter" className="narrow" />
        <button type="submit">Search</button>
        <details className="age-filters" open={hasAge}>
          <summary>Age filters</summary>
          <p className="muted">Use 12h, 30d, 2w, 6m or 1y, measured back from today to when the post was created.
            A question filter removes the whole thread; an answer filter removes only answers. Posts with an unknown creation date are excluded.</p>
          <div className="age-grid">
            {AGE_FIELDS.map(({ key, label }) => (
              <label key={key}>{label}
                <input value={ageInput[key]} onChange={(e) => setAgeInput({ ...ageInput, [key]: e.target.value })} placeholder="e.g. 90d" />
              </label>
            ))}
          </div>
        </details>
      </form>
      {result.kind === 'loading' && q && <p>Searching…</p>}
      {result.kind === 'error' && <ErrorBox message={result.message} />}
      {result.kind === 'ok' && result.data && (
        <>
          <FreshnessNote f={result.data.freshness} />
          <p className="muted">{result.data.resultCount} evidence passage(s) · confidence {result.data.confidence} (heuristic)</p>
          <p className="notice">{result.data.notice}</p>
          {result.data.resultCount === 0 && <p>No sufficiently relevant evidence in the knowledge store{hasAge ? ' for this age range' : ''}.</p>}
          {result.data.evidence.map((e) => <EvidenceCard key={`${e.questionId}-${e.answerId ?? 'q'}`} e={e} />)}
          {result.data.truncated && <p className="muted">{result.data.omittedForBudget} lower-ranked passage(s) omitted (output budget).</p>}
        </>
      )}
    </section>
  )
}
