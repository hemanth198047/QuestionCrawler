import { plainHeading } from '../lib/format'
import { Fragment, type ReactNode } from 'react'
import { cleanText, plainText } from '../lib/cleanText'
import { coverage, isMatch, type FindTerms } from '../lib/highlight'
import { Copy } from 'lucide-react'

/**
 * A small, SAFE Markdown renderer. Content comes from strangers, so nothing is ever injected as HTML:
 * everything becomes React elements, and only http(s)/mailto links are clickable.
 */
function safeHref(href: string): string | null {
  try {
    const u = new URL(href, 'https://placeholder.invalid')
    if (u.protocol === 'https:' || u.protocol === 'http:' || u.protocol === 'mailto:') return /^(https?:|mailto:)/i.test(href) ? href : null
  } catch { /* not a URL */ }
  return null
}

/** Words to highlight while a Markdown block renders (set by Markdown's `highlight` prop for that render only). */
let HIGHLIGHT: RegExp | null = null

function plain(raw: string, key: string): ReactNode[] {
  const text = plainText(raw)
  if (!HIGHLIGHT || !text) return [text]
  const out: ReactNode[] = []
  let last = 0
  let n = 0
  for (const m of text.matchAll(HIGHLIGHT)) {
    if (m.index! > last) out.push(text.slice(last, m.index))
    out.push(<mark key={`${key}h${n++}`} className="hl">{m[0]}</mark>)
    last = m.index! + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

function inline(text: string, keyBase = ''): ReactNode[] {
  const out: ReactNode[] = []
  const re = /(`[^`]+`)|(\*\*(?:[^*]|\*(?!\*)[^*]*\*)+?\*\*)|(\*[^*\s][^*]*\*)|(\[[^\]]+\]\([^)\s]+\))|(https?:\/\/[^\s<)]+)/g
  let last = 0
  let m: RegExpExecArray | null
  let i = 0
  while ((m = re.exec(text))) {
    const k = `${keyBase}${i++}`
    if (m.index > last) out.push(...plain(text.slice(last, m.index), `${k}p`))
    const tok = m[0]
    if (tok.startsWith('`')) out.push(<code key={k} className="md-code">{tok.slice(1, -1)}</code>)
    else if (tok.startsWith('**')) out.push(<strong key={k}>{inline(tok.slice(2, -2), `${k}s`)}</strong>)
    else if (tok.startsWith('*')) out.push(<em key={k}>{inline(tok.slice(1, -1), `${k}e`)}</em>)
    else if (tok.startsWith('[')) {
      const mm = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(tok)
      const href = mm ? safeHref(mm[2]) : null
      out.push(href ? <a key={k} href={href} target="_blank" rel="noreferrer noopener nofollow">{plain(mm![1], `${k}l`)}</a> : <span key={k}>{plain(mm ? mm[1] : tok, `${k}l`)}</span>)
    } else {
      const href = safeHref(tok)
      out.push(href ? <a key={k} href={href} target="_blank" rel="noreferrer noopener nofollow">{tok}</a> : tok)
    }
    last = m.index + tok.length
  }
  if (last < text.length) out.push(...plain(text.slice(last), `${keyBase}e`))
  return out
}

interface ListItem { level: number; ordered: boolean; num: number; text: string }

/** A list with its sub-lists: items indented under an item belong to it. */
function listTree(items: ListItem[], start: number, end: number, keyBase: string): ReactNode {
  const first = items[start]
  const Tag = first.ordered ? 'ol' : 'ul'
  const out: ReactNode[] = []
  let k = start
  while (k < end) {
    const it = items[k]
    let j = k + 1
    while (j < end && items[j].level > it.level) j++
    out.push(<li key={k}>{inline(it.text, `${keyBase}-${k}-`)}{j > k + 1 ? listTree(items, k + 1, j, `${keyBase}-${k}`) : null}</li>)
    k = j
  }
  return <Tag start={first.ordered && first.num !== 1 ? first.num : undefined}>{out}</Tag>
}

export function CodeBlock({ code, lang }: { code: string; lang?: string }) {
  return (
    <div className="codeblock">
      <div className="codeblock-bar"><span>{lang || 'code'}</span>
        <button type="button" aria-label="Copy code" onClick={() => void navigator.clipboard?.writeText(code)}><Copy size={13} /> Copy</button></div>
      <pre><code>{code}</code></pre>
    </div>
  )
}

/**
 * {@code find}: what the reader is looking for. Every paragraph, list, table and code block is then marked with how much
 * of it matches (data-score, class md-hit), so the page view can spotlight and jump between the matching places.
 */
export function Markdown({ text, compact, highlight, find }: { text: string; compact?: boolean; highlight?: RegExp | null; find?: FindTerms | null }) {
  HIGHLIGHT = highlight ?? find?.re ?? null
  FIND = find ?? null
  try {
    return renderMarkdown(cleanText(text), compact)
  } finally {
    HIGHLIGHT = null
    FIND = null
  }
}

let FIND: FindTerms | null = null

/** One block, marked with how well it matches what the reader looks for. */
function block(key: number, raw: string, node: ReactNode): ReactNode {
  if (!FIND) return node
  const score = coverage(plainText(raw), FIND)
  const hit = isMatch(score, FIND)
  return <div key={key} className={`md-b${hit ? ' md-hit' : ''}`} data-score={score.toFixed(2)}>{node}</div>
}

function renderMarkdown(text: string, compact?: boolean) {
  const lines = text.split('\n')
  const blocks: ReactNode[] = []
  let i = 0
  let key = 0
  while (i < lines.length) {
    const line = lines[i]
    const fence = /^\s*(```+|~~~+)\s*([\w+#.-]*)\s*$/.exec(line)
    if (fence) {
      const buf: string[] = []
      i++
      while (i < lines.length && !/^\s*(```+|~~~+)\s*$/.test(lines[i])) buf.push(lines[i++])
      i++
      blocks.push(block(key, buf.join('\n'), <CodeBlock key={key++} code={buf.join('\n')} lang={fence[2]} />))
      continue
    }
    if (/^\s*$/.test(line)) { i++; continue }
    const h = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line)
    if (h) {
      // a heading that matches what is looked for is itself a place to go (often the best one)
      const hs = FIND ? coverage(plainText(h[2]), FIND) : 0
      const hhit = FIND ? isMatch(hs, FIND) : false
      blocks.push(<p key={key++} className={`md-h md-h${Math.min(h[1].length, 4)}${hhit ? ' md-hit' : ''}`} data-heading={plainHeading(h[2])} data-score={FIND ? (hs + 0.01).toFixed(2) : undefined}>{inline(h[2])}</p>); i++; continue
    }
    if (/^\s*>/.test(line)) {
      const buf: string[] = []
      while (i < lines.length && /^\s*>/.test(lines[i])) buf.push(lines[i++].replace(/^\s*>\s?/, ''))
      blocks.push(block(key, buf.join(' '), <blockquote key={key++}>{inline(buf.join(' '))}</blockquote>))
      continue
    }
    if (/^\s*([-*]|\d+\.)\s+/.test(line)) {
      // Lists keep their nesting (2 spaces per level) and their numbers (step 3 stays 3 after a code block).
      const items: ListItem[] = []
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
        const m = /^(\s*)([-*]|(\d+)\.)\s+(.*)$/.exec(lines[i++])!
        items.push({ level: Math.floor(m[1].replace(/\t/g, '  ').length / 2), ordered: m[3] !== undefined, num: m[3] !== undefined ? Number(m[3]) : 1, text: m[4] })
      }
      blocks.push(block(key, items.map((it) => it.text).join('\n'), <Fragment key={key++}>{listTree(items, 0, items.length, `${key}`)}</Fragment>))
      continue
    }
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i + 1])) {
      const rows: string[][] = []
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        if (!/^\s*\|?\s*:?-{3,}/.test(lines[i])) rows.push(lines[i].trim().replace(/^\|/, '').replace(/(?<!\\)\|$/, '').split(/(?<!\\)\|/).map((c) => c.trim()))
        i++
      }
      blocks.push(block(key, rows.map((rw) => rw.join(' ')).join('\n'), <div key={key++} className="md-table"><table><thead><tr>{rows[0].map((c, n) => <th key={n}>{inline(c)}</th>)}</tr></thead>
        <tbody>{rows.slice(1).map((r, n) => <tr key={n}>{r.map((c, m) => <td key={m}>{inline(c)}</td>)}</tr>)}</tbody></table></div>))
      continue
    }
    const buf: string[] = []
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^\s*(```|~~~)/.test(lines[i]) && !/^\s*>/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i]) && !/^(#{1,6})\s/.test(lines[i])) buf.push(lines[i++])
    blocks.push(block(key, buf.join(' '), <p key={key++}>{buf.map((b, n) => <Fragment key={n}>{n > 0 && ' '}{inline(b, `${n}-`)}</Fragment>)}</p>))
  }
  return <div className={`md ${compact ? 'md-compact' : ''}`}>{blocks}</div>
}

