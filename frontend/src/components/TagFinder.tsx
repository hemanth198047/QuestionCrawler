import { Globe2, List, Search, Square, Tags } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { cancelTagListing, getTagListing, searchTags, startTagListing } from '../api'
import type { TagHit, TagListingState, TagSearch } from '../types/api'
import { Banner, Button, Card } from './ui'
import { TagList } from './TagList'

/** Looks tags up on the site itself, so the exact name (and its spelling) never has to be guessed. */
export function TagFinder({ values, chosen, onAdd, onUseAll }: { values: Record<string, string>; chosen: string[]; onAdd: (tag: string) => void; onUseAll: () => void }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<TagSearch | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [listing, setListing] = useState<{ state: TagListingState; tags: TagHit[] } | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true // development mode mounts, unmounts and mounts again: the flag must be set on every mount
    return () => { alive.current = false; window.clearTimeout(timer.current) }
  }, [])

  /** Every tag of the site: a background job on the server, polled for progress and for the tags read so far. */
  const showAll = async () => {
    window.clearTimeout(timer.current)
    setResult(null); setError(null)
    setListing({ state: { id: null, running: true, finished: false, loaded: 0, pages: 0, pageSize: 0, complete: false, stopReason: null, problem: null, hasMoreUnreliable: false, siteQuestions: -1 }, tags: [] })
    try {
      await startTagListing(values)
      let from = 0
      let acc: TagHit[] = []
      const poll = async () => {
        const chunk = await getTagListing(from)
        if (!alive.current) return
        if (chunk.tags.length > 0) { acc = acc.concat(chunk.tags); from += chunk.tags.length }
        setListing({ state: chunk.state, tags: acc })
        if (!chunk.state.finished) timer.current = window.setTimeout(() => { void poll().catch((e) => setError(e instanceof Error ? e.message : 'Lost contact with the backend')) }, 700)
      }
      await poll()
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not read the tags'); setListing(null) }
  }
  const stop = () => { void cancelTagListing() }
  const reading = listing?.state.running === true
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
            <Button icon={<List size={16} />} busy={reading} onClick={() => { setText(''); void showAll() }}>Show all tags</Button>
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
      {listing && (
        <div className="stack-sm" style={{ marginTop: 16 }}>
          {listing.state.problem && <Banner tone="bad" title="Could not read the site's tags">{listing.state.problem}</Banner>}
          {reading && (
            <div className="listing-progress" role="status">
              <div className="row spread"><span><b>Reading the site&apos;s tag list…</b> {listing.state.loaded.toLocaleString()} tags so far{listing.state.pages ? ` · ${listing.state.pages.toLocaleString()} pages` : ''}</span>
                <Button size="sm" icon={<Square size={14} />} onClick={stop}>Stop</Button></div>
              <div className="progress-bar"><i /></div>
              <p className="muted small">A big site can have many thousands of tags and this takes a while. You can already filter and pick from what has arrived; Stop keeps what was read.</p>
            </div>)}
          {listing.state.finished && !listing.state.problem && (
            <p className="small">The site lists <b>{listing.tags.length.toLocaleString()}{listing.state.complete ? '' : '+'} tags</b> (read in {listing.state.pages.toLocaleString()} pages of up to {listing.state.pageSize}){listing.state.siteQuestions >= 0 ? <> and holds about <b>{listing.state.siteQuestions.toLocaleString()}</b> question{listing.state.siteQuestions === 1 ? '' : 's'} in total.</> : '.'}</p>)}
          {listing.state.finished && !listing.state.complete && !listing.state.problem && (
            <Banner tone="warn" title="The list may not be complete">Reading stopped because {listing.state.stopReason ?? 'of an unknown reason'}. What was read is shown below. Collecting does not need this list: use “Find tags” for a specific tag, or the tag “all” for every question.</Banner>)}
          {listing.state.finished && listing.state.hasMoreUnreliable && <Banner tone="info">This site says “no more tags” even on full pages, so every page was requested anyway to be sure.</Banner>}
          {listing.tags.length > 0 && <TagList tags={listing.tags} chosen={chosen} onAdd={onAdd} />}
        </div>)}
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
