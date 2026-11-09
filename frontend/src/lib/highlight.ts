/** Highlighting the question's words in results. */

const STOP = new Set(['the', 'and', 'for', 'how', 'what', 'when', 'where', 'who', 'why', 'which', 'with', 'can', 'does', 'do', 'is', 'are', 'was', 'our', 'your', 'you', 'this', 'that', 'from', 'into', 'about', 'there', 'have', 'has', 'get', 'any', 'all', 'not', 'use'])

/** A pattern for the question's words and their other forms (configure, configuring, configuration), and symbols as typed. */
export function highlightPattern(query: string | null | undefined): RegExp | null {
  return findTerms(query)?.re ?? null
}

/** What the page is searched for: the words to highlight and how to test each one. */
export interface FindTerms { re: RegExp; stems: string[]; tests: RegExp[] }

const ESCAPE = (w: string) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/**
 * The question as things to find. Plain words match their other forms (request, requested, requesting); words with
 * symbols (C++, c#, [x], 50%, __init__, a.b(), --force) and identifiers match exactly as typed, case ignored. A question
 * that is only symbols ("_", "->", "$") is matched as a whole.
 */
export function findTerms(query: string | null | undefined): FindTerms | null {
  const q = (query ?? '').trim().slice(0, 500)
  if (!q) return null
  const parts: { test: string; mark: string; key: string }[] = []
  for (const raw of q.split(/\s+/).slice(0, 32)) {
    const token = raw.replace(/^[,;:!?"'()]+|[,;:!?"'()]+$/g, '') || raw
    const lower = token.toLowerCase()
    if (/^[\p{L}\p{N}]+$/u.test(token)) {
      if (lower.length < 3 || STOP.has(lower)) continue
      const stem = lower.replace(/(ations?|ions?|ing|ed|es|s)$/, '')
      if (stem.length < 3) continue
      // a word starts after anything but a letter or digit, so seg12 is found in seg11_seg12 and job in run_job
      parts.push({ test: `(?<![\\p{L}\\p{N}])${ESCAPE(stem)}`, mark: `(?<![\\p{L}\\p{N}])${ESCAPE(stem)}[\\p{L}\\p{N}]*`, key: stem })
    } else if (token.length >= 1) {
      // exactly as typed; a word boundary only on a side that is a letter or digit
      const before = /^[\p{L}\p{N}]/u.test(token) ? '(?<![\\p{L}\\p{N}])' : ''
      const after = /[\p{L}\p{N}]$/u.test(token) ? '(?![\\p{L}\\p{N}])' : ''
      const exact = `${before}${ESCAPE(token)}${after}`
      parts.push({ test: exact, mark: exact, key: lower })
    }
  }
  if (parts.length === 0) {
    // only short or common words, or only symbols: the whole question as typed
    const exact = ESCAPE(q)
    parts.push({ test: exact, mark: exact, key: q.toLowerCase() })
  }
  const unique = parts.filter((p, i) => parts.findIndex((o) => o.key === p.key) === i)
  const marks = [...unique].sort((x, y) => y.mark.length - x.mark.length).map((p) => p.mark)
  return { re: new RegExp(marks.join('|'), 'giu'), stems: unique.map((p) => p.key), tests: unique.map((p) => new RegExp(p.test, 'iu')) }
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
