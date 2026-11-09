/** Highlighting the question's words in results. */

const STOP = new Set(['the', 'and', 'for', 'how', 'what', 'when', 'where', 'who', 'why', 'which', 'with', 'can', 'does', 'do', 'is', 'are', 'was', 'our', 'your', 'you', 'this', 'that', 'from', 'into', 'about', 'there', 'have', 'has', 'get', 'any', 'all', 'not', 'use'])

/** A pattern for the question's words and their other forms (configure, configuring, configuration). */
export function highlightPattern(query: string | null | undefined): RegExp | null {
  const stems = (query ?? '').toLowerCase().split(/[^\p{L}\p{N}_-]+/u).filter((w) => w.length >= 3 && !STOP.has(w))
    .map((w) => w.replace(/(ations?|ions?|ing|ed|es|s)$/, '')).filter((w) => w.length >= 3)
  if (stems.length === 0) return null
  const esc = [...new Set(stems)].sort((a, b) => b.length - a.length).map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return new RegExp(`\\b(?:${esc.join('|')})[\\p{L}\\p{N}_-]*`, 'giu')
}
