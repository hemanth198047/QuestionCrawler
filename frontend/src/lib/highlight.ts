/** Highlighting the question's words in results. */

const STOP = new Set(['the', 'and', 'for', 'how', 'what', 'when', 'where', 'who', 'why', 'which', 'with', 'can', 'does', 'do', 'is', 'are', 'was', 'our', 'your', 'you', 'this', 'that', 'from', 'into', 'about', 'there', 'have', 'has', 'get', 'any', 'all', 'not', 'use'])

/** A pattern for the question's words and their other forms (configure, configuring, configuration). */
export function highlightPattern(query: string | null | undefined): RegExp | null {
  const stems = stemsOf(query)
  if (stems.length === 0) return null
  const esc = [...new Set(stems)].sort((a, b) => b.length - a.length).map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return new RegExp(`\\b(?:${esc.join('|')})[\\p{L}\\p{N}_-]*`, 'giu')
}

/** The question's meaningful words, reduced to a common stem (configur, request, vpn). */
export function stemsOf(query: string | null | undefined): string[] {
  return [...new Set((query ?? '').toLowerCase().split(/[^\p{L}\p{N}_-]+/u).filter((w) => w.length >= 3 && !STOP.has(w))
    .map((w) => w.replace(/(ations?|ions?|ing|ed|es|s)$/, '')).filter((w) => w.length >= 3))]
}

/** What the page is searched for: the words to highlight and their stems. */
export interface FindTerms { re: RegExp; stems: string[]; tests: RegExp[] }

export function findTerms(query: string | null | undefined): FindTerms | null {
  const re = highlightPattern(query)
  const stems = stemsOf(query)
  if (!re || stems.length === 0) return null
  return { re, stems, tests: stems.map((st) => new RegExp(`\\b${st.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`, 'iu')) }
}

/** How much of the question a piece of text covers: 0 (none of its words) to 1 (all of them). */
export function coverage(text: string, terms: FindTerms | null): number {
  if (!terms || !text) return 0
  let found = 0
  for (const t of terms.tests) if (t.test(text)) found++
  return found / terms.tests.length
}

/** A block counts as a match when it has the only word asked, or at least half of several words (and 2+). */
export function isMatch(score: number, terms: FindTerms | null): boolean {
  if (!terms) return false
  const n = terms.tests.length
  return n === 1 ? score > 0 : score >= 0.5 && score * n >= 2 - 1e-9 || score >= 0.99
}
