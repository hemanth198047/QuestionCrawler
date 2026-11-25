import type { Evidence, SearchResponse } from '../types/api'
import { sourceLabel } from './sources'
import { type AnswerView, safeUrl } from './searchai'
import { safeUrl as safeStoredUrl } from './url'

/** True when what is stored on this computer does not answer the question well: nothing found, or only weak matches. */
export const needsAi = (r: SearchResponse | null | undefined): boolean =>
  !!r && (r.resultCount === 0 || r.confidence === 'NONE' || r.confidence === 'LOW')

const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n).trimEnd()}…` : s)
const oneLine = (s: string) => s.replace(/\s+/g, ' ').trim()

/** The part of a stored result that answers the question, in a few lines. */
export const excerpt = (e: Evidence): string => oneLine((e.relevant && e.relevant.length > 0 ? e.relevant.slice(0, 2).join(' … ') : e.passage) ?? '')

/** One text with the AI answer, what was already stored, and where each part comes from (to paste into a chat or a ticket). */
export function answerWithSources(question: string, ai: AnswerView | null, local: Evidence[]): string {
  const out: string[] = [`Question: ${question}`, '']
  if (ai) {
    out.push('Answer from Search-AI (written by an AI model):', ai.text ?? '(no text answer)', '')
    const links = ai.sources.map((h) => ({ h, url: safeUrl(h.url) })).filter((x) => x.url)
    if (links.length > 0) out.push('Search-AI sources:', ...links.map((x, i) => `[A${i + 1}] ${x.h.title} — ${x.url}`), '')
  }
  if (local.length > 0) {
    out.push('Already stored on this computer:')
    local.forEach((e, i) => {
      const url = safeStoredUrl(e.sectionUrl) ?? safeStoredUrl(e.sourceUrl)
      const by = e.verified ? ` — confirmed by ${e.verified.by}${e.verified.note ? `: ${e.verified.note}` : ''}` : ''
      out.push(`[S${i + 1}] ${e.title} (${sourceLabel(e.source) || 'stored'})${by}${url ? ` — ${url}` : ''}`, `    ${clip(excerpt(e), 300)}`)
    })
  }
  return out.join('\n')
}
