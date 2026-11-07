import { plainHeading } from '../lib/format'
import { Fragment, type ReactNode } from 'react'
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

function inline(text: string, keyBase = ''): ReactNode[] {
  const out: ReactNode[] = []
  const re = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*\s][^*]*\*)|(\[[^\]]+\]\([^)\s]+\))|(https?:\/\/[^\s<)]+)/g
  let last = 0
  let m: RegExpExecArray | null
  let i = 0
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index))
    const tok = m[0]
    const k = `${keyBase}${i++}`
    if (tok.startsWith('`')) out.push(<code key={k} className="md-code">{tok.slice(1, -1)}</code>)
    else if (tok.startsWith('**')) out.push(<strong key={k}>{tok.slice(2, -2)}</strong>)
    else if (tok.startsWith('*')) out.push(<em key={k}>{tok.slice(1, -1)}</em>)
    else if (tok.startsWith('[')) {
      const mm = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(tok)
      const href = mm ? safeHref(mm[2]) : null
      out.push(href ? <a key={k} href={href} target="_blank" rel="noreferrer noopener nofollow">{mm![1]}</a> : <span key={k}>{mm ? mm[1] : tok}</span>)
    } else {
      const href = safeHref(tok)
      out.push(href ? <a key={k} href={href} target="_blank" rel="noreferrer noopener nofollow">{tok}</a> : tok)
    }
    last = m.index + tok.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
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

export function Markdown({ text, compact }: { text: string; compact?: boolean }) {
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
      blocks.push(<CodeBlock key={key++} code={buf.join('\n')} lang={fence[2]} />)
      continue
    }
    if (/^\s*$/.test(line)) { i++; continue }
    const h = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line)
    if (h) { blocks.push(<p key={key++} className="md-h" data-heading={plainHeading(h[2])}>{inline(h[2])}</p>); i++; continue }
    if (/^\s*>/.test(line)) {
      const buf: string[] = []
      while (i < lines.length && /^\s*>/.test(lines[i])) buf.push(lines[i++].replace(/^\s*>\s?/, ''))
      blocks.push(<blockquote key={key++}>{inline(buf.join(' '))}</blockquote>)
      continue
    }
    if (/^\s*([-*]|\d+\.)\s+/.test(line)) {
      const ordered = /^\s*\d+\./.test(line)
      const items: string[] = []
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*([-*]|\d+\.)\s+/, ''))
      const Tag = ordered ? 'ol' : 'ul'
      blocks.push(<Tag key={key++}>{items.map((it, n) => <li key={n}>{inline(it)}</li>)}</Tag>)
      continue
    }
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i + 1])) {
      const rows: string[][] = []
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        if (!/^\s*\|?\s*:?-{3,}/.test(lines[i])) rows.push(lines[i].trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim()))
        i++
      }
      blocks.push(<div key={key++} className="md-table"><table><thead><tr>{rows[0].map((c, n) => <th key={n}>{inline(c)}</th>)}</tr></thead>
        <tbody>{rows.slice(1).map((r, n) => <tr key={n}>{r.map((c, m) => <td key={m}>{inline(c)}</td>)}</tr>)}</tbody></table></div>)
      continue
    }
    const buf: string[] = []
    while (i < lines.length && !/^\s*$/.test(lines[i]) && !/^\s*(```|~~~)/.test(lines[i]) && !/^\s*>/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i]) && !/^(#{1,6})\s/.test(lines[i])) buf.push(lines[i++])
    blocks.push(<p key={key++}>{buf.map((b, n) => <Fragment key={n}>{n > 0 && ' '}{inline(b, `${n}-`)}</Fragment>)}</p>)
  }
  return <div className={`md ${compact ? 'md-compact' : ''}`}>{blocks}</div>
}

