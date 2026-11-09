import { useLayoutEffect, useState, type RefObject } from 'react'
import type { FindTerms } from './highlight'

/** One matching place on the page. */
export interface Hit { el: HTMLElement; score: number; order: number; section: string | null; text: string }

export type FindMode = 'spot' | 'only' | 'all'

/**
 * Reads the rendered page for the places that match (blocks the Markdown renderer marked md-hit), notes which section
 * each is in, and marks the headings of sections that have matches (md-h-hit) so "only the matching parts" keeps them.
 */
export function useHits(root: RefObject<HTMLElement | null>, terms: FindTerms | null, version: unknown) {
  const [hits, setHits] = useState<Hit[]>([])
  const [sections, setSections] = useState<Record<string, number>>({})
  useLayoutEffect(() => {
    const el = root.current
    if (!el) return
    el.querySelectorAll('.md-h-hit').forEach((h) => h.classList.remove('md-h-hit'))
    if (!terms) { setHits([]); setSections({}); return }
    const found: Hit[] = []
    const counts: Record<string, number> = {}
    let heading: HTMLElement | null = null
    let order = 0
    const headingsWithHits = new Set<HTMLElement>()
    // Read first, change classes after: reading text this way never makes the browser lay the page out again,
    // so pages with thousands of matches stay fast. At most 3000 places are listed.
    for (const node of el.querySelectorAll<HTMLElement>('.md-h, .md-b')) {
      if (node.classList.contains('md-h')) heading = node
      if (!node.classList.contains('md-hit')) continue
      if (found.length >= 3000) break
      const section = heading?.dataset.heading ?? null
      if (heading) headingsWithHits.add(heading)
      if (section) counts[section] = (counts[section] ?? 0) + 1
      found.push({ el: node, score: Number(node.dataset.score ?? 0), order: order++, section, text: (node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 400) })
    }
    if (found.length === 0) {
      // Nothing has enough of the question (a long pasted log, for example): show the places that have the most of it.
      const partial = [...el.querySelectorAll<HTMLElement>('.md-b')].filter((b) => Number(b.dataset.score ?? 0) > 0)
        .sort((a, b) => Number(b.dataset.score) - Number(a.dataset.score)).slice(0, 20)
      const keep = new Set(partial)
      let current: HTMLElement | null = null
      for (const node of el.querySelectorAll<HTMLElement>('.md-h, .md-b')) {
        if (node.classList.contains('md-h')) { current = node; continue }
        if (!keep.has(node)) continue
        node.classList.add('md-hit')
        const section = current?.dataset.heading ?? null
        if (current) headingsWithHits.add(current)
        if (section) counts[section] = (counts[section] ?? 0) + 1
        found.push({ el: node, score: Number(node.dataset.score ?? 0), order: order++, section, text: (node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 400) })
      }
    }
    headingsWithHits.forEach((h) => h.classList.add('md-h-hit'))
    setHits(found)
    setSections(counts)
  }, [root, terms, version])
  return { hits, sections }
}

/** The best places first (most of the question's words), earlier on the page breaking ties. */
export function bestOf(hits: Hit[], n: number): Hit[] {
  return [...hits].sort((a, b) => b.score - a.score || a.order - b.order).slice(0, n)
}

/** Scrolls to a place and marks it as the current one. */
export function showHit(hit: Hit | undefined) {
  if (!hit) return
  document.querySelectorAll('.md-current').forEach((e) => e.classList.remove('md-current'))
  hit.el.classList.add('md-current')
  hit.el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  // Blocks far away are laid out only when they come near the screen, which can move the target: keep settling on it
  // until it stays put (or the reader scrolls elsewhere).
  let tries = 0
  let last = NaN
  const settle = () => {
    if (!hit.el.classList.contains('md-current') || tries++ > 12) return
    const top = hit.el.getBoundingClientRect().top
    if (Math.abs(top - last) < 2 && top > 60 && top < window.innerHeight * 0.5) return
    last = top
    if (top < 100 || top > window.innerHeight * 0.45) hit.el.scrollIntoView({ behavior: 'auto', block: 'start' })
    window.setTimeout(settle, 200)
  }
  window.setTimeout(settle, 600)
}
