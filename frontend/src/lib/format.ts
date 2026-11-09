import { cleanText, cleanTitle } from './cleanText'
/** Human-friendly formatting. Unknown values are said to be unknown, never invented. */
export function timeAgo(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'unknown time'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return 'unknown time'
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 45) return 'just now'
  const m = Math.round(s / 60)
  if (m < 60) return `${m} minute${m === 1 ? '' : 's'} ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} hour${h === 1 ? '' : 's'} ago`
  const d = Math.round(h / 24)
  if (d < 31) return `${d} day${d === 1 ? '' : 's'} ago`
  const mo = Math.round(d / 30.4)
  if (mo < 12) return `${mo} month${mo === 1 ? '' : 's'} ago`
  const y = Math.round((d / 365.25) * 10) / 10
  return `${y} year${y === 1 ? '' : 's'} ago`
}

export function dateOnly(iso: string | null | undefined): string {
  if (!iso) return 'unknown date'
  const t = new Date(iso)
  return Number.isNaN(t.getTime()) ? 'unknown date' : t.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

export const number = (n: number | null | undefined) => (n === null || n === undefined ? '–' : n.toLocaleString())

export function duration(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

/** Age steps used by the sliders and presets. value is what the backend understands. */
export const AGE_STEPS: { value: string; label: string; short: string }[] = [
  { value: '', label: 'Any time', short: 'Everything' },
  { value: '7d', label: 'Past week', short: '1 week' },
  { value: '30d', label: 'Past month', short: '1 month' },
  { value: '90d', label: 'Past 3 months', short: '3 months' },
  { value: '6m', label: 'Past 6 months', short: '6 months' },
  { value: '1y', label: 'Past year', short: '1 year' },
  { value: '2y', label: 'Past 2 years', short: '2 years' },
  { value: '5y', label: 'Past 5 years', short: '5 years' },
]

export function ageLabel(value: string): string {
  const step = AGE_STEPS.find((s) => s.value === value)
  if (step) return step.label
  const m = /^(\d+)([hdwmy])$/i.exec(value.trim())
  if (!m) return value
  const unit = { h: 'hour', d: 'day', w: 'week', m: 'month', y: 'year' }[m[2].toLowerCase() as 'h'] ?? ''
  return `Past ${m[1]} ${unit}${m[1] === '1' ? '' : 's'}`
}

export function plainCompleteness(raw: string): { label: string; tone: 'ok' | 'info' | 'warn' | 'bad'; text: string } {
  if (raw.startsWith('COMPLETE_FOR_ACCESSIBLE_SCOPE'))
    return { label: 'Everything we may see was checked', tone: 'ok', text: 'Every page of results was read and every question was verified. Content hidden from your account is not visible, so not counted.' }
  if (raw.startsWith('INCREMENTAL'))
    return { label: 'Quick update', tone: 'info', text: 'Only recent changes were looked at. Deleted or hidden questions are not noticed this way: run a Full check now and then.' }
  if (raw.startsWith('PARTIAL'))
    return { label: 'Finished with some problems', tone: 'warn', text: 'A few questions could not be downloaded. They will be retried automatically next time.' }
  return { label: 'Stopped early', tone: 'bad', text: 'The run did not finish. Starting again continues where it stopped.' }
}

/** "keyword[any]:deploy (+1.0)" -> "“deploy” anywhere". Rules are shown in plain words, never as code. */
export function friendlyRule(raw: string): string {
  const m = /^(keyword|regex|tag)\[(\w+)\]:(.*?)(?: \(\+[\d.]+\))?$/.exec(raw)
  if (!m) return raw
  const where = { any: 'anywhere', title: 'in the title', body: 'in the question', answers: 'in the answers', tags: 'in the tags' }[m[2] as 'any'] ?? m[2]
  if (m[1] === 'tag') return `tag “${m[3]}”`
  if (m[1] === 'regex') return `pattern ${m[3]} ${where}`
  return `“${m[3]}” ${where}`
}

/** Heading text without Markdown emphasis or links, the same way the search index names a section. */
export function plainHeading(text: string): string {
  return cleanTitle(cleanText(text).replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/[*`]/g, '')) // underscores stay: casc_upload_manifest
}
