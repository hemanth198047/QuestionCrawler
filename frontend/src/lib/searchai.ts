// Search-AI answers are shown without knowing their exact shape: lists of hits and answer text are looked for under the usual
// names, and the raw answer is always one click away. Everything here is read-only display logic; nothing is executed.

export interface Hit {
  key: string
  title: string
  url?: string
  text?: string
  chunkId?: string
  meta: [string, string][]
  raw: unknown
}
export interface AnswerView { text: string | null; sources: Hit[]; raw: unknown }

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const LIST_NAMES = ['results', 'docs', 'hits', 'items', 'data', 'response', 'documents', 'chunks', 'sources', 'citations', 'references']
const TITLE = ['title', 'name', 'heading', 'page_title', 'doc_title', 'subject', 'question']
const URL_KEYS = ['url', 'link', 'source_url', 'href', 'uri', 'permalink', 'source']
const TEXT = ['snippet', 'text', 'content', 'summary', 'description', 'highlight', 'body', 'chunk_text', 'excerpt', 'passage']
const CHUNK = ['chunkid', 'chunkId', 'chunk_id']
const META = ['use_case', 'source', 'author', 'date', 'updated', 'created', 'last_modified', 'score', 'type', 'space']
const ANSWER = ['answer', 'genai_response', 'generated_answer', 'response', 'overview', 'summary', 'text', 'content', 'message', 'result']

/** Search results sometimes carry highlight markup (<em>); it is removed, never rendered. */
export const plain = (v: string) => v.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
const str = (v: unknown): string | undefined => {
  if (typeof v === 'string') return plain(v) || undefined
  if (typeof v === 'number') return String(v)
  if (Array.isArray(v)) return plain(v.filter((x) => typeof x === 'string').join(' … ')) || undefined
  return undefined
}
const pick = (o: Obj, keys: string[]) => { for (const k of keys) { const v = str(o[k]); if (v) return v } return undefined }

/** Only web links are made clickable (no javascript: or data: addresses from an answer). */
export const safeUrl = (u?: string) => (u && /^https?:\/\//i.test(u) ? u : undefined)

function findList(node: unknown, depth = 0): unknown[] | null {
  if (depth > 3) return null
  if (Array.isArray(node)) return node
  if (!isObj(node)) return null
  for (const name of LIST_NAMES) {
    if (name in node) { const f = findList(node[name], depth + 1); if (f) return f }
  }
  for (const v of Object.values(node)) if (Array.isArray(v) && v.length > 0 && isObj(v[0])) return v
  return null
}

function toHit(item: unknown, index: number): Hit {
  if (!isObj(item)) return { key: String(index), title: str(item) ?? `Result ${index + 1}`, meta: [], raw: item }
  const url = safeUrl(pick(item, URL_KEYS))
  const text = pick(item, TEXT)
  const title = pick(item, TITLE) ?? (url ? url.replace(/^https?:\/\//, '') : (text ? `${text.slice(0, 80)}${text.length > 80 ? '…' : ''}` : `Result ${index + 1}`))
  const chunkId = CHUNK.map((k) => str(item[k])).find(Boolean)
  const meta = META.flatMap((k): [string, string][] => { const v = str(item[k]); return v && v !== url && v !== title ? [[k.replace(/_/g, ' '), v.slice(0, 80)]] : [] }).slice(0, 4)
  return { key: chunkId ?? `${index}-${title}`, title, url, text: text && text !== title ? text : undefined, chunkId, meta, raw: item }
}

/** The list of hits in a search answer, or null when none can be found. */
export function extractHits(json: unknown): Hit[] | null {
  const list = findList(json)
  return list ? list.map(toHit) : null
}

/** The answer text and the sources of an AI answer. */
export function extractAnswer(json: unknown): AnswerView {
  let text: string | null = null
  const scan = (o: unknown, depth: number) => {
    if (text || depth > 2) return
    if (typeof o === 'string') { text = o.trim() || null; return }
    if (!isObj(o)) return
    for (const k of ANSWER) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) { text = v.trim(); return }
      if (isObj(v)) { scan(v, depth + 1); if (text) return }
    }
  }
  scan(json, 0)
  const sources = isObj(json) ? ['sources', 'citations', 'references', 'documents', 'results', 'chunks'].flatMap((k) => (Array.isArray(json[k]) ? (json[k] as unknown[]) : [])).map(toHit) : []
  return { text, sources, raw: json }
}

/** The use-case names from /usecases (a list of strings, of objects with a name, or an object keyed by name). */
export function extractUseCases(json: unknown): string[] {
  const list = Array.isArray(json) ? json : findList(json)
  if (list) return list.map((x) => (isObj(x) ? pick(x, ['name', 'use_case', 'id', 'label', 'title']) : str(x))).filter((x): x is string => !!x)
  if (isObj(json)) return Object.keys(json)
  return []
}
