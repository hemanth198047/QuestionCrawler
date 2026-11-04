import type { Freshness } from '../types/api'

export function FreshnessNote({ f }: { f: Freshness | null }) {
  if (!f) return null
  return (
    <p className={f.stale ? 'notice warn' : 'muted'}>
      {f.lastSuccessfulSyncAt ? `Last successful sync: ${f.lastSuccessfulSyncAt} (${f.ageHours}h ago). ` : ''}
      {f.note}
    </p>
  )
}

export function ErrorBox({ message }: { message: string }) {
  return <p role="alert" className="notice danger">{message}</p>
}
