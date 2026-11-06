import { Eye, Lightbulb, Send } from 'lucide-react'
import { useState } from 'react'
import { probeApi } from '../api'
import type { ApiProbe } from '../types/api'
import { Badge, Banner, Button, Card, CopyButton } from './ui'

const QUICK = ['/info', '/questions?pagesize=1', '/tags?pagesize=1']

function tone(r: ApiProbe): 'ok' | 'warn' | 'bad' {
  if (r.kind === 'JSON') return r.status < 400 ? 'ok' : 'warn'
  return 'bad'
}

function reportText(r: ApiProbe, path: string): string {
  return [`GET ${r.url ?? path}`, `HTTP ${r.status}  ${r.contentType ?? ''}  ${r.characters} characters`, `Kind: ${r.kind}`, r.summary ?? '', '', 'Beginning of the answer:', r.preview ?? '', '', ...r.hints.map((h) => `- ${h}`)].join('\n')
}

/** Makes one real call and shows exactly what the site answers (the key is hidden). */
export function RawAnswerPanel({ values }: { values: Record<string, string> }) {
  const [path, setPath] = useState('/info')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ApiProbe | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = async (p = path) => {
    setBusy(true); setError(null)
    try { setResult(await probeApi(values, p)) } catch (e) { setError(e instanceof Error ? e.message : 'Could not ask') } finally { setBusy(false) }
  }
  return (
    <Card>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <Eye size={21} color="var(--brand)" style={{ marginTop: 3 }} />
        <div style={{ flex: 1 }}>
          <h3>See what the API really answers</h3>
          <p className="card-sub">Makes one real call, the way a collection would, and shows the raw answer: status, type, and the start of the body. Your key is never shown. Use it when a test call fails with &ldquo;not valid JSON&rdquo;.</p>
          <div className="row" style={{ marginTop: 14, flexWrap: 'wrap' }}>
            <input className="input" style={{ maxWidth: 380 }} value={path} onChange={(e) => setPath(e.target.value)} aria-label="API path" placeholder="/info"
              onKeyDown={(e) => { if (e.key === 'Enter') void run() }} />
            <Button variant="primary" icon={<Send size={16} />} busy={busy} onClick={() => run()}>Ask the API</Button>
            {QUICK.map((q) => <button key={q} className="chip" onClick={() => { setPath(q); void run(q) }}>{q}</button>)}
          </div>
        </div>
      </div>
      {error && <div style={{ marginTop: 14 }}><Banner tone="bad">{error}</Banner></div>}
      {result && !result.reached && <div style={{ marginTop: 14 }}><Banner tone="bad" title="No answer">{result.problem}</Banner>{result.url && <p className="muted small" style={{ marginTop: 8 }}>Asked: <code>{result.url}</code></p>}</div>}
      {result && result.reached && (
        <div className="stack" style={{ marginTop: 16 }}>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Badge tone={tone(result)}>HTTP {result.status}</Badge>
            <Badge tone="neutral">{result.contentType?.split(';')[0] ?? 'no content type'}</Badge>
            <Badge tone="neutral">{result.characters} characters</Badge>
            <Badge tone={result.keySent ? 'ok' : 'warn'}>{result.keySent ? `key sent ${result.keyPlacement ?? ''}` : 'no key sent'}</Badge>
            <span style={{ marginLeft: 'auto' }}><CopyButton text={reportText(result, path)} label="Copy this report" /></span>
          </div>
          <p className="muted small">Asked: <code>{result.url}</code></p>
          <Banner tone={tone(result) === 'ok' ? 'ok' : tone(result) === 'warn' ? 'warn' : 'bad'} title={result.summary ?? ''}>{result.kind === 'JSON' && result.status < 400 ? 'This is what a working API looks like.' : 'Here is the beginning of what the site sent:'}</Banner>
          <pre className="raw-preview">{result.preview || '(empty)'}</pre>
          {result.hints.length > 0 && (
            <div className="raw-hints"><div className="row" style={{ marginBottom: 6 }}><Lightbulb size={17} color="var(--brand)" /><strong>What to check</strong></div>
              <ul>{result.hints.map((h) => <li key={h}>{h}</li>)}</ul></div>)}
        </div>
      )}
    </Card>
  )
}
