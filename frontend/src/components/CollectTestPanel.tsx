import { ListChecks, Play } from 'lucide-react'
import { useState } from 'react'
import { testCollection } from '../api'
import type { CollectionTest } from '../types/api'
import { Badge, Banner, Button, Card, Chip, CopyButton } from './ui'

function reportText(r: CollectionTest): string {
  return [`Tags: ${r.tags.join(', ')}`, `Asked: ${r.request ?? '(nothing)'}`, `Returned: ${r.found} question(s)`, ...r.samples.map((s) => `- ${s.id}: ${s.title} [${s.tags.join(', ')}] ${s.createdAt ?? ''} answers=${s.answers} text=${s.bodyCharacters}`),
    ...r.notes.map((n) => `NOTE: ${n}`), r.problem ? `PROBLEM: ${r.problem}` : ''].filter(Boolean).join('\n')
}

/** Asks for a first page of questions exactly as a collection does and shows what came back and what was understood. */
export function CollectTestPanel({ values }: { values: Record<string, string> }) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<CollectionTest | null>(null)
  const [error, setError] = useState<string | null>(null)
  const noTags = !(values['sync.tags'] ?? '').trim()

  const run = async () => {
    setBusy(true); setError(null)
    try { setResult(await testCollection(values)) } catch (e) { setError(e instanceof Error ? e.message : 'Could not test') } finally { setBusy(false) }
  }
  return (
    <Card>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <ListChecks size={21} color="var(--brand)" style={{ marginTop: 3 }} />
        <div style={{ flex: 1 }}>
          <h3>Test the collection</h3>
          <p className="card-sub">Asks the site for the first page of questions <b>exactly as a collection will</b>, with your tags, and shows what was understood: how many questions, their tags, dates, text and answers. Nothing is stored. Use it when “See what the API really answers” looks fine but collecting finds nothing.</p>
          <div className="row" style={{ marginTop: 14 }}>
            <Button variant="primary" icon={<Play size={16} />} busy={busy} disabled={noTags} onClick={run}>Test with my tags</Button>
            {noTags && <span className="muted small">Choose a tag first (or “Collect every tag”).</span>}
          </div>
        </div>
      </div>
      {error && <div style={{ marginTop: 14 }}><Banner tone="bad">{error}</Banner></div>}
      {result && !result.ok && <div style={{ marginTop: 14 }}><Banner tone="bad" title="The test could not run">{result.problem}</Banner></div>}
      {result && result.ok && (
        <div className="stack" style={{ marginTop: 16 }}>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Badge tone={result.found > 0 ? 'ok' : 'warn'}>{result.found} question{result.found === 1 ? '' : 's'} on the first page</Badge>
            <Badge tone="neutral">{result.tagsAll ? 'tags: all' : `tags: ${result.tags.join(', ')}`}</Badge>
            <span style={{ marginLeft: 'auto' }}><CopyButton text={reportText(result)} label="Copy this report" /></span>
          </div>
          {result.request && <p className="muted small">Asked: <code>{result.request}</code></p>}
          {result.notes.map((n) => <Banner key={n} tone="warn" title="Something could not be understood">{n}</Banner>)}
          {result.found === 0 && result.notes.length === 0 && (
            <Banner tone="warn" title="The site returned no questions for these tags">Check the tag's exact spelling with “Find tags on the site”, and compare with “See what the API really answers” (try the same tag there). If you chose a tag from the list and this is still empty, send me this report.</Banner>)}
          {result.samples.length > 0 && (
            <div className="stack-sm">
              {result.samples.map((s) => (
                <div key={s.id} className="sample">
                  <strong>{s.title || '(no title)'}</strong>
                  <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>{s.tags.map((t) => <Chip key={t}>{t}</Chip>)}</div>
                  <div className="muted small">#{s.id} · {s.createdAt ? new Date(s.createdAt).toLocaleDateString() : 'no date'} · {s.answers} answer{s.answers === 1 ? '' : 's'} · {s.bodyCharacters > 0 ? `${s.bodyCharacters.toLocaleString()} characters of text` : 'no text'}</div>
                </div>))}
            </div>)}
          {result.found > 0 && result.notes.length === 0 && <Banner tone="ok" title="This is what a collection will store">The questions above were found and read. Press Collect to download them all.</Banner>}
        </div>)}
    </Card>
  )
}
