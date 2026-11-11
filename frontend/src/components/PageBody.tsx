import { memo, useEffect, useMemo, useState } from 'react'
import type { FindTerms } from '../lib/highlight'
import { Markdown } from './Markdown'

/** Splits a long page at blank lines (never inside code) into pieces of about {@code size} characters. */
function piecesOf(text: string, first: number, size: number): string[] {
  if (text.length <= first + size) return [text]
  const out: string[] = []
  let cur: string[] = []
  let len = 0
  let fence = false
  for (const line of text.split('\n')) {
    if (/^\s*(```|~~~)/.test(line)) fence = !fence
    cur.push(line)
    len += line.length + 1
    if (!fence && line.trim() === '' && len >= (out.length === 0 ? first : size)) {
      out.push(cur.join('\n'))
      cur = []
      len = 0
    }
  }
  if (cur.length) out.push(cur.join('\n'))
  return out
}

const Piece = memo(function Piece({ text, find }: { text: string; find: FindTerms | null }) {
  return <Markdown text={text} find={find} />
})

/**
 * A page's text, drawn once and, for long pages, in pieces: the first piece at once, the rest one by one in the
 * background, so the screen never freezes on a very large page (API references, indexes). {@code onDone} is called
 * when the whole page is on screen (find mode then lists its places).
 */
export function PageBody({ text, find, onDone }: { text: string; find: FindTerms | null; onDone: () => void }) {
  // a small first piece shows at once; the rest comes in a few large pieces (each piece has a fixed cost)
  const pieces = useMemo(() => piecesOf(text, 25000, 600000), [text])
  const [shown, setShown] = useState(1)
  useEffect(() => {
    if (shown < pieces.length) {
      const t = window.setTimeout(() => setShown((n) => n + 1), 0) // one piece per turn: the screen stays responsive
      return () => window.clearTimeout(t)
    }
    onDone()
  }, [shown, pieces.length, onDone])
  return (
    <>
      {pieces.slice(0, shown).map((p, i) => <Piece key={i} text={p} find={find} />)}
      {shown < pieces.length && <p className="muted small page-loading">Loading the rest of the page… {Math.round((shown / pieces.length) * 100)}%</p>}
    </>
  )
}
