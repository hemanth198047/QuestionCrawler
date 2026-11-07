import type { ConfigField } from '../types/api'

/** True when a setting matters for the chosen system and for the other choices made (for example the sign-in method). */
export function isShown(field: ConfigField, values: Record<string, string>): boolean {
  const source = values['source.type'] ?? 'fixture'
  if (field.showForSources.length > 0 && !field.showForSources.includes(source)) return false
  return Object.entries(field.showWhen ?? {}).every(([key, allowed]) => allowed.includes(values[key] ?? ''))
}

/** The choices of a setting that work for the chosen system. */
export function optionsFor(field: ConfigField, source: string) {
  return field.options.filter((o) => !o.sources || o.sources.length === 0 || o.sources.includes(source))
}
