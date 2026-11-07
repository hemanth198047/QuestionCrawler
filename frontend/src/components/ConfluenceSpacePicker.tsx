import { BookOpen, CheckSquare, List, LogIn, Search, XSquare } from 'lucide-react'
import { useMemo, useState } from 'react'
import { listConfluenceSpaces, signInConfluence } from '../api'
import type { ConfluenceSpaces } from '../types/api'
import { Banner, Button, Card } from './ui'
import { SignInWindowHint } from './SignInWindowHint'

/** Lists the Confluence spaces the account can read, so whole spaces are ticked instead of page ids typed. */
export function ConfluenceSpacePicker({ values, chosen, onChange }: { values: Record<string, string>; chosen: string[]; onChange: (keys: string[]) => void }) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ConfluenceSpaces | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [hideArchived, setHideArchived] = useState(true)
  const [hidePersonal, setHidePersonal] = useState(true)
  const [signing, setSigning] = useState(false)
  const [signedIn, setSignedIn] = useState<{ ok: boolean; message: string } | null>(null)
  const browserSignIn = values['source.auth.method'] === 'browser'

  const signIn = async () => {
    setSigning(true); setSignedIn(null)
    try { setSignedIn(await signInConfluence(values)) } catch (e) { setSignedIn({ ok: false, message: e instanceof Error ? e.message : 'Signing in did not work' }) } finally { setSigning(false) }
  }

  const load = async () => {
    setBusy(true); setError(null)
    try { setResult(await listConfluenceSpaces(values)) } catch (e) { setError(e instanceof Error ? e.message : 'Could not list the spaces') } finally { setBusy(false) }
  }
  const isOn = (key: string) => chosen.some((c) => c.toUpperCase() === key.toUpperCase())
  const toggle = (key: string) => onChange(isOn(key) ? chosen.filter((c) => c.toUpperCase() !== key.toUpperCase()) : [...chosen, key])

  const shown = useMemo(() => {
    if (!result?.ok) return []
    const f = filter.trim().toLowerCase()
    return result.spaces.filter((s) => (!hideArchived || (s.status ?? 'current') !== 'archived')
      && (!hidePersonal || (s.type ?? 'global') !== 'personal')
      && (!f || s.key.toLowerCase().includes(f) || (s.name ?? '').toLowerCase().includes(f)))
  }, [result, filter, hideArchived, hidePersonal])

  const selectShown = () => onChange([...chosen, ...shown.map((s) => s.key).filter((k) => !isOn(k))])
  const clearShown = () => onChange(chosen.filter((c) => !shown.some((s) => s.key.toUpperCase() === c.toUpperCase())))

  return (
    <Card>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <BookOpen size={21} color="var(--brand)" style={{ marginTop: 3 }} />
        <div style={{ flex: 1 }}>
          <h3>Choose the spaces to collect</h3>
          <p className="card-sub">Every page in a ticked space is collected, so you never have to list pages one by one. We sign in with your settings above and show only the spaces your account can read.</p>
          <div className="row" style={{ marginTop: 14, flexWrap: 'wrap' }}>
            {browserSignIn && <Button icon={<LogIn size={16} />} busy={signing} onClick={signIn}>Sign in with my browser</Button>}
            <Button variant="primary" icon={<List size={16} />} busy={busy} onClick={load}>{result ? 'Reload my spaces' : 'Show my spaces'}</Button>
            <span className="muted small">{chosen.length === 0 ? 'No space chosen yet' : `${chosen.length} space${chosen.length === 1 ? '' : 's'} chosen: ${chosen.join(', ')}`}</span>
          </div>
        </div>
      </div>
      <div style={{ marginTop: 14 }}><SignInWindowHint active={signing || busy} /></div>
      {signedIn && <div style={{ marginTop: 14 }}><Banner tone={signedIn.ok ? 'ok' : 'bad'} title={signedIn.ok ? 'Signed in' : 'Not signed in'}>{signedIn.message}</Banner></div>}
      {error && <div style={{ marginTop: 14 }}><Banner tone="bad">{error}</Banner></div>}
      {result && !result.ok && <div style={{ marginTop: 14 }}><Banner tone="bad" title="Could not list the spaces">{result.problem}</Banner></div>}
      {result && result.ok && (
        <div className="stack-sm" style={{ marginTop: 16 }}>
          {result.problem && <Banner tone="warn">{result.problem}</Banner>}
          {!result.complete && <Banner tone="warn" title="Not every space was listed">There are more spaces than could be shown. You can still type a space key in “Spaces to collect” above.</Banner>}
          <div className="row" style={{ flexWrap: 'wrap', gap: 10 }}>
            <Search size={16} />
            <input className="input" style={{ maxWidth: 280 }} value={filter} placeholder="Filter by key or name" aria-label="Filter spaces" onChange={(e) => setFilter(e.target.value)} />
            <label className="small"><input type="checkbox" checked={hidePersonal} onChange={(e) => setHidePersonal(e.target.checked)} /> Hide personal spaces</label>
            <label className="small"><input type="checkbox" checked={hideArchived} onChange={(e) => setHideArchived(e.target.checked)} /> Hide archived</label>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <Button size="sm" icon={<CheckSquare size={14} />} disabled={shown.length === 0} onClick={selectShown}>Tick all shown ({shown.length})</Button>
            <Button size="sm" variant="ghost" icon={<XSquare size={14} />} disabled={shown.length === 0} onClick={clearShown}>Untick all shown</Button>
            <span className="muted small">{result.spaces.length.toLocaleString()} space{result.spaces.length === 1 ? '' : 's'} visible to this account</span>
          </div>
          <div style={{ maxHeight: 340, overflowY: 'auto', border: '1px solid var(--line, rgba(127,127,127,.25))', borderRadius: 12 }}>
            {shown.map((s) => (
              <label key={s.key} className="row" style={{ padding: '8px 12px', gap: 10, cursor: 'pointer', borderBottom: '1px solid var(--line, rgba(127,127,127,.12))' }}>
                <input type="checkbox" checked={isOn(s.key)} onChange={() => toggle(s.key)} />
                <strong style={{ minWidth: 90 }}>{s.key}</strong>
                <span style={{ flex: 1 }}>{s.name ?? ''}</span>
                {s.type && s.type !== 'global' && <span className="muted small">{s.type}</span>}
                {s.status === 'archived' && <span className="muted small">archived</span>}
                {s.url && <a className="small" href={s.url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>open</a>}
              </label>))}
            {shown.length === 0 && <p className="muted small" style={{ padding: 12 }}>No space matches.</p>}
          </div>
          <p className="muted small">Ticking a space adds it to “Spaces to collect” above. Save the settings, then collect.</p>
        </div>)}
    </Card>
  )
}
