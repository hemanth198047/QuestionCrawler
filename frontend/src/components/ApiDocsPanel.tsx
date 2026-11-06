import { BookOpenText, CheckCircle2, ChevronDown, CircleSlash, FileSearch, KeyRound, Search, Wand2, XCircle } from 'lucide-react'
import { useMemo, useState } from 'react'
import { discoverApiDocs } from '../api'
import type { ApiCapability, ApiDocsResult, ApiOperation } from '../types/api'
import { Badge, Banner, Button, Card, Chip, CopyButton } from './ui'

const METHOD_TONE: Record<string, 'ok' | 'info' | 'warn' | 'bad' | 'neutral'> = { GET: 'ok', POST: 'info', PUT: 'warn', PATCH: 'warn', DELETE: 'bad' }

function summaryText(r: ApiDocsResult): string {
  if (!r.catalog) return ''
  const lines = [`${r.catalog.title} ${r.catalog.version}`.trim(), `Documents: ${r.catalog.specUrls.join(', ')}`, `Suggested address: ${r.suggestedBaseUrl ?? '-'}`,
    `Access key: ${r.catalog.security.filter((s) => s.type === 'apiKey').map((s) => `${s.name} (${s.in})`).join(', ') || 'not described'}`, '']
  r.catalog.operations.forEach((o) => lines.push(`${o.method} ${o.path}  [${o.parameters.map((p) => p.name + (p.required ? '*' : '')).join(', ')}]  ${o.summary}`))
  return lines.join('\n')
}

function Capabilities({ caps }: { caps: ApiCapability[] }) {
  return (
    <ul className="caps">
      {caps.map((c) => (
        <li key={c.id} className={c.found ? 'found' : c.required ? 'missing' : 'optional'}>
          <span className="cap-ico">{c.found ? <CheckCircle2 size={19} /> : c.required ? <XCircle size={19} /> : <CircleSlash size={19} />}</span>
          <div><strong>{c.label}</strong> {c.required && <Badge tone="neutral">needed</Badge>}<div className="muted small">{c.purpose}</div></div>
          <code className="cap-path">{c.found ? `${c.method} ${c.path}` : c.required ? 'missing' : 'not offered'}</code>
        </li>
      ))}
    </ul>
  )
}

function Endpoint({ op, used }: { op: ApiOperation; used: boolean }) {
  return (
    <details className="endpoint">
      <summary>
        <Badge tone={METHOD_TONE[op.method] ?? 'neutral'}>{op.method}</Badge>
        <code>{op.path}</code>
        <span className="muted small ep-sum">{op.summary}</span>
        {used && <Badge tone="brand">used</Badge>}
        {op.deprecated && <Badge tone="warn">deprecated</Badge>}
        <ChevronDown size={15} className="ep-chev" />
      </summary>
      <div className="ep-body">
        {op.parameters.length === 0 ? <p className="muted small">No parameters.</p> : (
          <table className="ep-params"><thead><tr><th>Parameter</th><th>Where</th><th>Type</th><th>Meaning</th></tr></thead>
            <tbody>{op.parameters.map((p) => (
              <tr key={p.name + p.in}><td><code>{p.name}</code>{p.required && <b className="req"> *</b>}</td><td>{p.in}</td><td>{p.type || '–'}{p.allowed.length ? ` (${p.allowed.join(' | ')})` : ''}</td><td className="muted">{p.description || ''}</td></tr>))}</tbody></table>
        )}
        <div className="row small muted" style={{ marginTop: 8 }}>Answers: {op.responseCodes.map((c) => <Chip key={c}>{c}</Chip>)}</div>
      </div>
    </details>
  )
}

/** Reads the site's Swagger documentation and shows every endpoint, which ones will be used, and what to fill in. */
export function ApiDocsPanel({ values, onUse }: { values: Record<string, string>; onUse: (patch: Record<string, string>) => void }) {
  const [state, setState] = useState<'idle' | 'loading' | 'done'>('idle')
  const [result, setResult] = useState<ApiDocsResult | null>(null)
  const [filter, setFilter] = useState('')
  const usedKeys = useMemo(() => new Set((result?.capabilities?.capabilities ?? []).filter((c) => c.found).map((c) => `${c.method} ${c.path}`)), [result])
  const grouped = useMemo(() => {
    const groups = new Map<string, ApiOperation[]>()
    const q = filter.trim().toLowerCase()
    for (const o of result?.catalog?.operations ?? []) {
      if (q && !`${o.method} ${o.path} ${o.summary} ${o.tags.join(' ')}`.toLowerCase().includes(q)) continue
      const g = o.tags[0] ?? 'Other'
      groups.set(g, [...(groups.get(g) ?? []), o])
    }
    return [...groups.entries()]
  }, [result, filter])

  const read = async () => {
    setState('loading')
    try { setResult(await discoverApiDocs(values)) } catch (e) { setResult({ ok: false, problem: e instanceof Error ? e.message : 'Could not read it', catalog: null, capabilities: null, tried: [], suggestedBaseUrl: null, suggestedKeyName: null, suggestedKeyIn: null }) }
    setState('done')
  }
  const fill = () => {
    if (!result) return
    const patch: Record<string, string> = {}
    if (result.suggestedBaseUrl && !values['source.baseUrl']) patch['source.baseUrl'] = result.suggestedBaseUrl
    if (result.suggestedKeyName) patch['api.keyName'] = result.suggestedKeyName
    if (result.suggestedKeyIn) patch['api.keyIn'] = result.suggestedKeyIn
    onUse(patch)
  }

  return (
    <Card className="apidocs">
      <div className="spread">
        <div className="row"><BookOpenText size={21} color="var(--brand)" /><div><h3>Read the API documentation</h3>
          <p className="card-sub">We open your Swagger page, find every endpoint, and work out which ones to use. Nothing is changed.</p></div></div>
        <Button variant="primary" icon={<FileSearch size={17} />} busy={state === 'loading'} disabled={!values['api.swaggerUrl']} onClick={read}>{result ? 'Read it again' : 'Read the documentation'}</Button>
      </div>
      {!values['api.swaggerUrl'] && <p className="muted small" style={{ marginTop: 10 }}>Enter the documentation address above first.</p>}

      {state === 'done' && result && !result.ok && (
        <div style={{ marginTop: 16 }} className="stack-sm">
          <Banner tone="bad" title="We could not read the documentation">{result.problem}</Banner>
          {result.tried.length > 0 && <details><summary className="small">Addresses we tried ({result.tried.length})</summary><ul className="plainlist">{result.tried.map((t) => <li key={t}><code>{t}</code></li>)}</ul></details>}
        </div>
      )}

      {state === 'done' && result?.ok && result.catalog && result.capabilities && (
        <div className="stack" style={{ marginTop: 18 }}>
          <div className="apidocs-head">
            <div><strong style={{ fontSize: 17 }}>{result.catalog.title}</strong> <Badge tone="neutral">{result.catalog.version || 'no version'}</Badge>
              <div className="muted small">{result.catalog.operations.length} endpoints · {result.catalog.specUrls.length} document{result.catalog.specUrls.length === 1 ? '' : 's'}</div></div>
            <div className="row">
              <Badge tone={result.capabilities.ready ? 'ok' : 'bad'}>{result.capabilities.ready ? 'Everything needed is there' : 'Something needed is missing'}</Badge>
              <CopyButton text={summaryText(result)} label="Copy summary" />
            </div>
          </div>

          <div className="found-grid">
            <div className="found-card"><KeyRound size={18} /><div><strong>Access key</strong>
              <div className="muted small">{result.catalog.security.filter((s) => s.type === 'apiKey').map((s) => `Sent as “${s.name}” in the ${s.in === 'header' ? 'request header' : 'address'}`).join('; ') || 'The documentation does not say how the key is sent. The usual “key” in the address is used.'}</div></div></div>
            <div className="found-card"><Search size={18} /><div><strong>API address</strong>
              <div className="muted small">{result.suggestedBaseUrl ?? 'The documentation does not give one: enter it above.'}</div></div></div>
          </div>
          <div><Button size="sm" variant="primary" icon={<Wand2 size={15} />} onClick={fill}>Use these settings</Button>
            <span className="muted small" style={{ marginLeft: 10 }}>fills in the API address (if empty) and how the key is sent</span></div>

          {result.capabilities.problems.map((p) => <Banner key={p} tone={p.startsWith('The documentation lists no') ? 'bad' : 'warn'}>{p}</Banner>)}
          <div><h4 style={{ marginBottom: 8 }}>What QuestionCrawler will use</h4><Capabilities caps={result.capabilities.capabilities} /></div>

          <div>
            <div className="spread" style={{ marginBottom: 8 }}><h4>All endpoints ({result.catalog.operations.length})</h4>
              <input className="input" style={{ maxWidth: 260 }} placeholder="Filter endpoints…" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter endpoints" /></div>
            {grouped.map(([group, ops]) => (
              <div key={group} className="ep-group"><div className="ep-group-title">{group} <span className="muted">· {ops.length}</span></div>
                {ops.map((o) => <Endpoint key={o.method + o.path} op={o} used={usedKeys.has(`${o.method} ${o.path}`)} />)}</div>))}
            {grouped.length === 0 && <p className="muted">No endpoint matches “{filter}”.</p>}
          </div>
          {result.catalog.warnings.map((w) => <Banner key={w} tone="info">{w}</Banner>)}
        </div>
      )}
    </Card>
  )
}
