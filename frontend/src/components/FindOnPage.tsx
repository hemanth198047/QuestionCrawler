import { ChevronDown, ChevronUp, Crosshair, Eye, Filter, FileText, Search, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import type { FindMode, Hit } from '../lib/findOnPage'

/**
 * The bar that stays at the top of a page: what you are looking for (editable), how many places match, previous / next
 * (also the n and p keys), and how to show the page: spotlight on the matches, only the matches, or the whole page.
 */
export function FindBar({ query, onQuery, hits, mode, onMode, current, onGo }: {
  query: string; onQuery: (q: string) => void; hits: Hit[]; mode: FindMode; onMode: (m: FindMode) => void
  current: number; onGo: (index: number) => void
}) {
  const [draft, setDraft] = useState(query)
  useEffect(() => setDraft(query), [query])
  const box = useRef<HTMLInputElement>(null)
  const n = hits.length
  const step = useCallback((d: number) => { if (n > 0) onGo(((current < 0 ? (d > 0 ? -1 : 0) : current) + d + n) % n) }, [n, current, onGo])
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable || e.ctrlKey || e.metaKey || e.altKey) return
      if (e.key === 'n' || e.key === 'j') { e.preventDefault(); step(1) }
      else if (e.key === 'p' || e.key === 'k') { e.preventDefault(); step(-1) }
      else if (e.key === 'f' || e.key === '/') { e.preventDefault(); box.current?.focus() }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [step])
  const has = query.trim() !== ''
  return (
    <div className={`findbar rise ${has ? 'is-active' : ''}`} role="search">
      <form className="findbar-q" onSubmit={(e) => { e.preventDefault(); if (draft.trim() === query.trim()) step(1); else onQuery(draft.trim()) }}>
        <Search size={17} />
        <span className="findbar-label">{has ? 'Looking for' : 'Find on this page'}</span>
        <input ref={box} value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Type what you are looking for, press Enter" aria-label="Find on this page" />
        {has && <button type="button" className="findbar-x" aria-label="Stop finding" title="Show the page without finding" onClick={() => { setDraft(''); onQuery('') }}><X size={15} /></button>}
      </form>
      {has && (
        <div className="findbar-tools">
          <span className={`findbar-count ${n === 0 ? 'none' : ''}`}>{n === 0 ? 'No place on this page matches' : `${current >= 0 ? current + 1 : '–'} of ${n} place${n === 1 ? '' : 's'}`}</span>
          <div className="findbar-nav">
            <button type="button" onClick={() => step(-1)} disabled={n === 0} title="Previous place (p)" aria-label="Previous place"><ChevronUp size={16} /></button>
            <button type="button" onClick={() => step(1)} disabled={n === 0} title="Next place (n)" aria-label="Next place"><ChevronDown size={16} /></button>
          </div>
          <div className="seg" role="group" aria-label="How to show the page">
            <SegButton on={mode === 'spot'} onClick={() => onMode('spot')} icon={<Eye size={14} />} title="Matching places stand out, the rest is faded">Spotlight</SegButton>
            <SegButton on={mode === 'only'} onClick={() => onMode('only')} icon={<Filter size={14} />} title="Show only the matching places and their headings">Only matches</SegButton>
            <SegButton on={mode === 'all'} onClick={() => onMode('all')} icon={<FileText size={14} />} title="The whole page, words still highlighted">Whole page</SegButton>
          </div>
        </div>
      )}
    </div>
  )
}

function SegButton({ on, onClick, icon, title, children }: { on: boolean; onClick: () => void; icon: ReactNode; title: string; children: ReactNode }) {
  return <button type="button" className={on ? 'on' : ''} aria-pressed={on} title={title} onClick={onClick}>{icon}<span>{children}</span></button>
}

/** "Where this page answers it": the best places as short previews, each one click away. */
export function BestPlaces({ query, best, onJump, highlight }: { query: string; best: Hit[]; onJump: (h: Hit) => void; highlight: RegExp | null }) {
  if (best.length === 0) return null
  return (
    <div className="bestplaces rise">
      <div className="bestplaces-head"><Crosshair size={16} /> Where this page answers “{query}”</div>
      <ol>
        {best.map((h, i) => (
          <li key={h.order}>
            <button type="button" className="bestplace" onClick={() => onJump(h)}>
              <span className="bestplace-n">{i + 1}</span>
              <span className="bestplace-body">
                {h.section && <span className="bestplace-sec">§ {h.section}</span>}
                <span className="bestplace-text">{marked(preview(h.text), highlight)}</span>
              </span>
              <span className="bestplace-go">Jump to it</span>
            </button>
          </li>
        ))}
      </ol>
    </div>
  )
}

function preview(text: string): string {
  return text.length > 260 ? text.slice(0, 260).replace(/\s+\S*$/, '') + ' …' : text
}

function marked(text: string, re: RegExp | null): ReactNode[] {
  if (!re) return [text]
  const out: ReactNode[] = []
  let last = 0
  let n = 0
  for (const m of text.matchAll(re)) {
    if (m.index! > last) out.push(text.slice(last, m.index))
    out.push(<mark key={n++} className="hl">{m[0]}</mark>)
    last = m.index! + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}
