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
    el.querySelectorAll<HTMLElement>('.md-h, .md-b').forEach((node) => {
      if (node.classList.contains('md-h')) { heading = node; return }
      if (!node.classList.contains('md-hit')) return
      const section = heading?.dataset.heading ?? null
      if (heading) heading.classList.add('md-h-hit')
      if (section) counts[section] = (counts[section] ?? 0) + 1
      found.push({ el: node, score: Number(node.dataset.score ?? 0), order: order++, section, text: (node.innerText || '').replace(/\s+/g, ' ').trim() })
    })
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
  hit.el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
