import { ExternalLink, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { getSearchAiConfigured, getSearchAiPages, saveSearchAiPages, searchAiAnswer } from '../api'
import { answerWithSources } from '../lib/combined'
import { type AnswerView, extractAnswer, safeUrl } from '../lib/searchai'
import type { Evidence } from '../types/api'
import { Markdown } from './Markdown'
import { Badge, Banner, Button, Card, CopyButton, Spinner } from './ui'

type Phase = 'idle' | 'loading' | 'ok' | 'off' | 'error'

/**
 * Search-AI as the fallback for a search: when what is stored does not answer the question ({@code auto}) it is asked at once, otherwise
 * only on request. The answer is labelled as written by an AI model, its sources are linked and can be saved for later searches.
 */
export function SearchAiFallback({ q, local, auto }: { q: string; local: Evidence[]; auto: boolean }) {
  const [phase, setPhase] = useState<Phase>('idle')
  const [answer, setAnswer] = useState<AnswerView | null>(null)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState<string[]>([])
  const [saving, setSaving] = useState<string[]>([])
  const [saveErrors, setSaveErrors] = useState<Record<string, string>>({})
  const started = useRef(false)

  async function ask() {
    setPhase('loading'); setError('')
    try {
      if (!(await getSearchAiConfigured()).configured) { setPhase('off'); return }
      setAnswer(extractAnswer((await searchAiAnswer(q.slice(0, 2000), '')).response))
      setPhase('ok')
      getSearchAiPages().then((p) => setSaved(p.map((x) => x.url))).catch(() => undefined)
    } catch (e) { setError(e instanceof Error ? e.message : 'Search-AI could not be asked'); setPhase('error') }
  }

  useEffect(() => {
    if (auto && !started.current) { started.current = true; void ask() }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auto])

  async function save(url: string, title: string) {
    setSaving((s) => [...s, url]); setSaveErrors((e) => { const n = { ...e }; delete n[url]; return n })
    try {
      const [r] = await saveSearchAiPages([{ url, title, query: q }])
      if (r?.error) setSaveErrors((e) => ({ ...e, [url]: r.error ?? '' })); else setSaved((s) => [...s, url])
    } catch (e) { setSaveErrors((x) => ({ ...x, [url]: e instanceof Error ? e.message : 'Could not save' })) } finally { setSaving((s) => s.filter((u) => u !== url)) }
  }

  if (phase === 'idle') {
    return auto ? null : <div><Button icon={<Sparkles size={16} />} onClick={() => void ask()}>Also ask Search-AI</Button></div>
  }
  if (phase === 'loading') return <div className="row muted"><Spinner label={auto ? 'Nothing strong is stored here: asking Search-AI…' : 'Asking Search-AI…'} /></div>
  if (phase === 'off') return <Banner tone="info" title="Search-AI is not set up yet" action={<Button onClick={() => { window.location.hash = '#/searchai' }}>Set it up</Button>}>Nothing strong is stored here for this question. Once Search-AI is connected, it is asked automatically in this case.</Banner>
  if (phase === 'error') return <Banner tone="warn" title="Search-AI could not answer" action={<Button onClick={() => void ask()}>Try again</Button>}>{error}</Banner>

  const sources = (answer?.sources ?? []).map((h) => ({ h, url: safeUrl(h.url) })).filter((x): x is { h: typeof x.h; url: string } => !!x.url)
  return (
    <Card className="rise">
      <div className="spread" style={{ flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
        <div className="row" style={{ flexWrap: 'wrap' }}>
          <Sparkles size={19} color="var(--brand)" /><h3>Answer from Search-AI</h3><Badge tone="info">Written by an AI model</Badge>
          {auto && <Badge tone="warn">Not found in what is stored here</Badge>}
        </div>
        <CopyButton text={answerWithSources(q, answer, local)} label="Copy with sources" />
      </div>
      {answer?.text ? <Markdown text={answer.text} /> : <p className="muted">Search-AI answered, but not with text we could recognise.</p>}
      {sources.length > 0 && (<>
        <h4 style={{ margin: '14px 0 8px' }} className="muted">Search-AI's sources</h4>
        <ol className="stack-sm" style={{ margin: 0, paddingLeft: 22 }}>{sources.map(({ h, url }, i) => (
          <li key={h.key}>
            <div className="row" style={{ flexWrap: 'wrap', gap: 6 }}>
              <strong>[A{i + 1}]</strong>
              <a href={url} target="_blank" rel="noreferrer noopener">{h.title} <ExternalLink size={12} style={{ verticalAlign: -1 }} /></a>
              {saved.includes(url) ? <Badge tone="ok">Page saved</Badge>
                : <Button size="sm" variant="ghost" busy={saving.includes(url)} onClick={() => void save(url, h.title)} title="Read this page and keep it on this computer so the next search finds it here">Save page</Button>}
            </div>
            {saveErrors[url] && <div className="small" style={{ color: 'var(--bad, #b42318)' }}>Could not save this page: {saveErrors[url]}</div>}
          </li>))}
        </ol></>)}
      <p className="muted small" style={{ marginTop: 12 }}>Written by an AI model from Search-AI's documents. Check the sources before relying on it.</p>
    </Card>
  )
}
