import { LogIn } from 'lucide-react'
import { useState } from 'react'
import { signInWithBrowser } from '../api'
import { Banner, Button } from './ui'

/** "Sign in with my browser": opens the company login page in Edge/Chrome and waits until the site lets you in. */
export function BrowserSignIn({ values, what }: { values: Record<string, string>; what: string }) {
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null)
  const run = async () => {
    setBusy(true); setResult(null)
    try { setResult(await signInWithBrowser(values)) } catch (e) { setResult({ ok: false, message: e instanceof Error ? e.message : 'Signing in did not work' }) } finally { setBusy(false) }
  }
  return (
    <div className="stack-sm">
      <Banner tone="info" title="Nothing else to fill in">When the app needs {what}, it opens your company login page in Edge or Chrome. Sign in as usual (password, MFA); the window closes by itself and the app carries on. Try it now:</Banner>
      <div className="row"><Button icon={<LogIn size={16} />} busy={busy} onClick={run}>Sign in with my browser</Button></div>
      {busy && <Banner tone="info" title="Finish signing in in the browser window">A browser window opened. Sign in there as you usually do. This page continues by itself once the site lets you in.</Banner>}
      {result && <Banner tone={result.ok ? 'ok' : 'bad'} title={result.ok ? 'Signed in' : 'Not signed in'}>{result.message}</Banner>}
    </div>
  )
}
