import { Globe2, List, Search, Tags } from 'lucide-react'
import { useState } from 'react'
import { searchTags } from '../api'
import type { TagSearch } from '../types/api'
import { Banner, Button, Card } from './ui'
import { TagList } from './TagList'

/** Looks tags up on the site itself, so the exact name (and its spelling) never has to be guessed. */
export function TagFinder({ values, chosen, onAdd, onUseAll }: { values: Record<string, string>; chosen: string[]; onAdd: (tag: string) => void; onUseAll: () => void }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<TagSearch | null>(null)
  const [error, setError] = useState<string | null>(null)

  const usingAll = chosen.some((c) => ['all', '*'].includes(c.toLowerCase()))
  const run = async (query = text) => {
    setBusy(true); setError(null)
    try { setResult(await searchTags(values, query)) } catch (e) { setError(e instanceof Error ? e.message : 'Could not search') } finally { setBusy(false) }
  }
  const isChosen = (name: string) => chosen.some((c) => c.toLowerCase().replace(/[\s_-]+/g, '') === name.toLowerCase().replace(/[\s_-]+/g, ''))
  return (
    <Card>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <Tags size={21} color="var(--brand)" style={{ marginTop: 3 }} />
        <div style={{ flex: 1 }}>
          <h3>Find tags on the site</h3>
          <p className="card-sub">Not sure how a tag is written? Type a few letters. We search the site&apos;s own tag list and show its exact spelling and how many questions each tag has.</p>
          <div className="row" style={{ marginTop: 14, flexWrap: 'wrap' }}>
            <input className="input" style={{ maxWidth: 320 }} value={text} placeholder="for example casc" aria-label="Tag to look for" onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && text.trim()) void run() }} />
            <Button variant="primary" icon={<Search size={16} />} busy={busy} disabled={!text.trim()} onClick={() => run()}>Find tags</Button>
            <Button icon={<List size={16} />} busy={busy} onClick={() => { setText(''); void run('') }}>Show all tags</Button>
          </div>
        </div>
      </div>
      <div className={`all-tags ${usingAll ? 'on' : ''}`}>
        <Globe2 size={18} />
        <div style={{ flex: 1 }}><strong>{usingAll ? 'Every tag is selected' : 'Want everything?'}</strong>
          <div className="muted small">{usingAll ? 'The tag “all” collects every question on the site, whatever its tags. Remove it above to name specific tags instead.' : 'Use the tag “all” to collect every question on the site, whatever its tags.'}</div></div>
        {!usingAll && <Button size="sm" onClick={onUseAll}>Collect every tag</Button>}
      </div>
      {error && <div style={{ marginTop: 14 }}><Banner tone="bad">{error}</Banner></div>}
      {result && !result.ok && <div style={{ marginTop: 14 }}><Banner tone="bad" title="Could not look tags up">{result.problem}</Banner></div>}
      {result && result.ok && (
        <div className="stack-sm" style={{ marginTop: 16 }}>
          {result.problem && <Banner tone="warn">{result.problem}</Banner>}
          {result.allTags && (
            <>
              <p className="small">The site lists <b>{result.tags.length.toLocaleString()}{result.truncated ? '+' : ''} tags</b>{result.siteQuestions >= 0 ? <> and holds about <b>{result.siteQuestions.toLocaleString()}</b> question{result.siteQuestions === 1 ? '' : 's'} in total.</> : '.'}</p>
              {result.truncated && <Banner tone="warn" title="Not every tag could be loaded">The site has more tags than were read (the reading stopped early, for example because of a limit or a slow answer). Use “Find tags” to look for a specific one.</Banner>}
              {result.tags.length > 0 && <TagList tags={result.tags} chosen={chosen} onAdd={onAdd} />}
            </>)}
          {!result.allTags && (
            <>
              {result.spellingDiffers && result.exact && <Banner tone="info" title={`The site writes this tag “${result.exact}”`}>You can type it either way: the site&apos;s own spelling is used automatically when collecting.</Banner>}
              <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
                {result.tags.map((t) => (
                  <button key={t.name} type="button" className={`chip${isChosen(t.name) ? ' active' : ''}`} disabled={isChosen(t.name)} onClick={() => onAdd(t.name)}
                    title={isChosen(t.name) ? 'Already chosen' : 'Click to add'}>{t.name}{t.count >= 0 ? ` · ${t.count.toLocaleString()}` : ''}{isChosen(t.name) ? ' ✓' : ''}</button>))}
              </div>
              {result.tags.length > 0 && <p className="muted small">Click a tag to add it to your tags above.</p>}
            </>)}
        </div>)}
    </Card>
  )
}
