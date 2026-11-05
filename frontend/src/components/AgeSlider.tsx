import { CalendarClock } from 'lucide-react'
import { AGE_STEPS } from '../lib/format'
import { Segmented } from './ui'

export interface AgeValue { mode: 'newer' | 'older'; value: string }

/** A stepped slider for "how old": Any time ... 5 years. */
export function AgeSlider({ label, hint, state, onChange }: { label: string; hint: string; state: AgeValue; onChange: (v: AgeValue) => void }) {
  const idx = Math.max(0, AGE_STEPS.findIndex((s) => s.value === state.value))
  const step = AGE_STEPS[idx]
  const pct = (idx / (AGE_STEPS.length - 1)) * 100
  return (
    <div className="ageslider">
      <div className="spread">
        <div className="row" style={{ gap: 8 }}><CalendarClock size={17} color="var(--brand)" /><strong>{label}</strong></div>
        <Segmented<'newer' | 'older'> label={label} value={state.mode} onChange={(mode) => onChange({ ...state, mode })} options={[{ value: 'newer', label: 'Newer than' }, { value: 'older', label: 'Older than' }]} />
      </div>
      <input type="range" min={0} max={AGE_STEPS.length - 1} step={1} value={idx} aria-label={`${label}: ${step.label}`}
        style={{ background: `linear-gradient(90deg, var(--brand) ${pct}%, var(--line-strong) ${pct}%)` }}
        onChange={(e) => onChange({ ...state, value: AGE_STEPS[Number(e.target.value)].value })} />
      <div className="ticks">{AGE_STEPS.map((s, i) => <span key={s.value || 'any'} className={i === idx ? 'on' : ''}>{s.short}</span>)}</div>
      <p className="muted small">{idx === 0 ? hint : `${state.mode === 'newer' ? 'Only items from the' : 'Only items older than the'} ${step.label.toLowerCase().replace('past ', state.mode === 'newer' ? 'past ' : '')}.`}</p>
    </div>
  )
}
