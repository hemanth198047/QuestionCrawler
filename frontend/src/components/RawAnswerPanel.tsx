import { CornerDownRight, Eye, KeyRound, Lightbulb, LogIn, Send } from 'lucide-react'
import { useState } from 'react'
import { probeApi } from '../api'
import type { ApiProbe } from '../types/api'
import { Badge, Banner, Button, Card, CopyButton } from './ui'

const QUICK = ['/info', '/questions?pagesize=15', '/tags?pagesize=15']

function tone(r: ApiProbe): 'ok' | 'warn' | 'bad' {
  if (r.kind === 'JSON') return r.status < 400 ? 'ok' : 'warn'
  return 'bad'
}

function itMessage(r: ApiProbe): string {
  return [`Hello,`, ``, `I am connecting an internal tool (QuestionCrawler) to our Stack Overflow site through its REST API, to collect questions and answers (read-only).`,
    `When the tool calls the API it is sent to a ${r.signInSystem ?? 'single sign-on'} sign-in page, which a program cannot complete.`,
    ...(r.redirects.length ? [``, `The redirects it follows:`, ...r.redirects.map((x) => `  ${x}`)] : []), ``,
    `Could you please provide ONE of these (read-only access is enough):`,
    `  1. A personal access token or a service account for the site's API, and how to send it (usually "Authorization: Bearer <token>").`,
    `  2. If you use OAuth client credentials: the client ID, client secret, token address and scope for this API.`,
    `  3. The address of the API or gateway that accepts a token or an API key (it may differ from the web address people use).`,
    ``, `Also: is the API access key enough on that address, or is a token needed as well?`, ``, `Thank you!`].join('\n')
}

function reportText(r: ApiProbe, path: string): string {
  return [`GET ${r.url ?? path}`, `HTTP ${r.status}  ${r.contentType ?? ''}  ${r.characters} characters`, `Kind: ${r.kind}`, r.summary ?? '', '', 'Beginning of the answer:', r.preview ?? '', '', ...r.hints.map((h) => `- ${h}`)].join('\n')
}

/** Makes one real call and shows exactly what the site answers (the key is hidden). */
export function RawAnswerPanel({ values, onUse }: { values: Record<string, string>; onUse: (patch: Record<string, string>) => void }) {
  const [path, setPath] = useState('/info')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ApiProbe | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = async (p = path, extra: Record<string, string> = {}) => {
    setBusy(true); setError(null)
    try { setResult(await probeApi({ ...values, ...extra }, p)) } catch (e) { setError(e instanceof Error ? e.message : 'Could not ask') } finally { setBusy(false) }
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
            <Badge tone={result.tokenSent ? 'ok' : 'neutral'}>{result.tokenSent ? 'Bearer token sent' : 'no Bearer token'}</Badge>
            {result.wwwAuthenticate && <Badge tone="info">server asks for: {result.wwwAuthenticate.split(' ')[0]}</Badge>}
            <span style={{ marginLeft: 'auto' }}><CopyButton text={reportText(result, path)} label="Copy this report" /></span>
          </div>
          <p className="muted small">Asked: <code>{result.url}</code></p>
          {result.redirects.length > 0 && (
            <div className="redirect-chain"><div className="row small"><CornerDownRight size={16} /><strong>{result.redirects.length === 1 ? 'The site redirected the request once' : `The site redirected the request ${result.redirects.length} times`}</strong><span className="muted">(followed; the answer below is from the last address)</span></div>
              <ol>{result.redirects.map((r) => <li key={r}><code>{r}</code></li>)}</ol></div>)}
          <Banner tone={tone(result) === 'ok' ? 'ok' : tone(result) === 'warn' ? 'warn' : 'bad'} title={result.summary ?? ''}>{result.kind === 'JSON' && result.status < 400 ? 'This is what a working API looks like.' : 'Here is the beginning of what the site sent:'}</Banner>
          <pre className="raw-preview">{result.preview || '(empty)'}</pre>
          {result.signInSystem && (
            <div className="signin-card recommended">
              <div className="row"><LogIn size={19} /><strong>Your site sends programs to {result.signInSystem} sign-in</strong></div>
              <p className="small muted">A browser sign-in (redirects, single sign-on, MFA) cannot be done by a program, and this app will not try to get around it. What works is a credential the site accepts <b>without a browser</b>:</p>
              <ol className="small"><li>A <b>personal access token</b> from your profile on the site (look for “API tokens” / “Personal access tokens”), or a <b>service account</b> from the owners.</li>
                <li>The <b>address of the API</b> that accepts it. It is often not the web address: an API gateway or an <code>/api</code> path.</li>
                <li>Then set the token as an environment variable and choose <b>Bearer token sign-in</b> (above). The API access key goes on top if it asks for one.</li></ol>
              <div><CopyButton text={itMessage(result)} label="Copy message for IT" /></div>
            </div>)}
          {result.suggestedPath && (
            <div className="signin-card recommended">
              <div className="row"><Lightbulb size={19} /><strong>The page size is not accepted</strong></div>
              <p className="small muted">This API only accepts certain page sizes. Collecting adapts to that by itself; only your test request needs the change.</p>
              <div><Button variant="primary" busy={busy} onClick={() => { setPath(result.suggestedPath ?? path); void run(result.suggestedPath ?? path) }}>Try <code>{result.suggestedPath}</code></Button></div>
            </div>)}
          {result.authNeeded === 'bearer' && (
            <div className="signin-card recommended">
              <div className="row"><KeyRound size={19} /><strong>This API wants a Bearer token</strong></div>
              <p className="small muted">Besides the API key, requests must carry a token in an <code>Authorization: Bearer</code> header. Get one from your profile on the site (often under “API tokens” or “Personal access tokens”) or from the site owners,
                set it as an environment variable in the terminal that starts the app, and enter that variable&apos;s <b>name</b> here. The token itself is never saved or shown.</p>
              <div className="row" style={{ flexWrap: 'wrap' }}>
                <input className="input" style={{ maxWidth: 300 }} aria-label="Bearer token variable name" value={values['source.auth.tokenEnv'] ?? ''} placeholder="INTERNAL_QA_TOKEN"
                  onChange={(e) => onUse({ 'source.auth.method': 'token', 'source.auth.tokenEnv': e.target.value })} />
                <Button variant="primary" busy={busy} onClick={() => run(path, { 'source.auth.method': 'token', 'source.auth.tokenEnv': values['source.auth.tokenEnv'] || 'INTERNAL_QA_TOKEN' })}>Use it and ask again</Button>
              </div>
              {values['source.auth.method'] === 'token' && <p className="small muted">Bearer token sign-in is on. If it still says “missing”, the variable is not set in the terminal that started the app.</p>}
            </div>)}
          {result.hints.length > 0 && (
            <div className="raw-hints"><div className="row" style={{ marginBottom: 6 }}><Lightbulb size={17} color="var(--brand)" /><strong>What to check</strong></div>
              <ul>{result.hints.map((h) => <li key={h}>{h}</li>)}</ul></div>)}
        </div>
      )}
    </Card>
  )
}
