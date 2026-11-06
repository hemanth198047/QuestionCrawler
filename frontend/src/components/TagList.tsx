import { Search } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import type { TagHit } from '../types/api'
import { CopyButton, Segmented } from './ui'

const ROW = 40 // px: fixed, so only the rows in view need to be drawn (thousands of tags stay smooth)
const HEIGHT = 380
const OVERSCAN = 6

/** Every tag of the site in a scrollable window, with a filter and a choice of order. Clicking a tag adds it. */
export function TagList({ tags, chosen, onAdd }: { tags: TagHit[]; chosen: string[]; onAdd: (tag: string) => void }) {
  const [filter, setFilter] = useState('')
  const [order, setOrder] = useState<'count' | 'name'>('count')
  const [top, setTop] = useState(0)
  const box = useRef<HTMLDivElement>(null)

  const squash = (s: string) => s.toLowerCase().replace(/[\s_-]+/g, '')
  const chosenSet = useMemo(() => new Set(chosen.map(squash)), [chosen])
  const shown = useMemo(() => {
    const q = squash(filter)
    const list = q ? tags.filter((t) => squash(t.name).includes(q)) : tags.slice()
    return order === 'name' ? list.sort((a, b) => a.name.localeCompare(b.name)) : list.sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
  }, [tags, filter, order])

  const first = Math.max(0, Math.floor(top / ROW) - OVERSCAN)
  const last = Math.min(shown.length, Math.ceil((top + HEIGHT) / ROW) + OVERSCAN)
  const copyText = shown.map((t) => (t.count >= 0 ? `${t.name}\t${t.count}` : t.name)).join('\n')

  return (
    <div className="taglist">
      <div className="taglist-tools">
        <div className="taglist-filter"><Search size={15} /><input value={filter} placeholder="Filter these tags…" aria-label="Filter the tag list"
          onChange={(e) => { setFilter(e.target.value); setTop(0); box.current?.scrollTo({ top: 0 }) }} /></div>
        <Segmented<'count' | 'name'> label="Order" value={order} onChange={(v) => { setOrder(v); setTop(0); box.current?.scrollTo({ top: 0 }) }}
          options={[{ value: 'count', label: 'Most used' }, { value: 'name', label: 'A–Z' }]} />
        <CopyButton text={copyText} label="Copy list" />
      </div>
      <div className="taglist-count muted small">{filter ? `${shown.length.toLocaleString()} of ${tags.length.toLocaleString()} tags match` : `${tags.length.toLocaleString()} tags`} · scroll to see them all · click a tag to add it</div>
      <div ref={box} className="taglist-scroll" style={{ height: HEIGHT }} onScroll={(e) => setTop(e.currentTarget.scrollTop)} role="region" aria-label="Tags on the site" tabIndex={0}>
        <div style={{ height: shown.length * ROW, position: 'relative' }}>
          {shown.slice(first, last).map((t, i) => {
            const taken = chosenSet.has(squash(t.name))
            return (
              <button key={t.name} type="button" className={`tagrow${taken ? ' taken' : ''}`} style={{ top: (first + i) * ROW, height: ROW }} disabled={taken}
                onClick={() => onAdd(t.name)} title={taken ? 'Already chosen' : 'Click to add'}>
                <span className="tagrow-name">{t.name}</span>
                <span className="tagrow-count">{t.count >= 0 ? t.count.toLocaleString() : ''}</span>
                <span className="tagrow-act">{taken ? '✓ added' : '+ add'}</span>
              </button>)
          })}
          {shown.length === 0 && <div className="muted" style={{ padding: 18 }}>No tag matches “{filter}”.</div>}
        </div>
      </div>
    </div>
  )
}
