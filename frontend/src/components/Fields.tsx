import { AlertTriangle, Check, Info, XCircle } from 'lucide-react'
import type { ReactNode } from 'react'
import { AGE_STEPS } from '../lib/format'
import type { ConfigField, Issue } from '../types/api'
import { Chip, InfoTip, TagInput } from './ui'

export function IssueList({ issues }: { issues: Issue[] }) {
  if (!issues.length) return null
  return (
    <div className="stack-sm">
      {issues.map((i, n) => (
        <div key={n} className={`issue issue-${i.level}`} role={i.level === 'error' ? 'alert' : undefined}>
          {i.level === 'error' ? <XCircle size={16} /> : i.level === 'warning' ? <AlertTriangle size={16} /> : <Info size={16} />}
          <div>{i.message}{i.fix && <small>{i.fix}</small>}</div>
        </div>
      ))}
    </div>
  )
}

/** Renders one setting from its metadata: label, plain-language help, the right control, and its problems. */
export function SettingField({ field, value, onChange, issues }: { field: ConfigField; value: string; onChange: (v: string) => void; issues: Issue[] }) {
  const hasError = issues.some((i) => i.level === 'error')
  const control = (() => {
    switch (field.type) {
      case 'SELECT':
        return (
          <div className="choice-grid">
            {field.options.map((o) => (
              <button type="button" key={o.value} className={`choice ${value === o.value ? 'on' : ''}`} onClick={() => onChange(o.value)} aria-pressed={value === o.value}>
                {value === o.value && <span className="choice-check"><Check size={13} /></span>}
                <strong>{o.label}</strong><span className="hint">{o.hint}</span>
              </button>
            ))}
          </div>
        )
      case 'SWITCH':
        return (
          <label className="switch">
            <input type="checkbox" checked={value === 'true'} onChange={(e) => onChange(e.target.checked ? 'true' : 'false')} />
            <span className="switch-track" /><span>{value === 'true' ? 'On' : 'Off'}</span>
          </label>
        )
      case 'TAGS':
        return <TagInput value={value.split(',').map((s) => s.trim()).filter(Boolean)} onChange={(v) => onChange(v.join(','))} placeholder={field.placeholder ?? 'Type and press Enter'} />
      case 'NUMBER':
        return (
          <div className="input-wrap">
            <input className="input" type="number" inputMode="numeric" value={value} onChange={(e) => onChange(e.target.value)} placeholder={field.placeholder ?? undefined} style={{ maxWidth: 220 }} />
            {field.unit && <span className="input-unit" style={{ position: 'static', marginLeft: 10 }}>{field.unit}</span>}
          </div>
        )
      case 'AGE':
        return (
          <div className="stack-sm">
            <div className="row" style={{ gap: 8 }}>
              {AGE_STEPS.map((s) => <Chip key={s.value || 'any'} active={value === s.value} onClick={() => onChange(s.value)}>{s.short}</Chip>)}
            </div>
            <input className="input" value={value} onChange={(e) => onChange(e.target.value)} placeholder="or type your own, e.g. 45d" style={{ maxWidth: 280 }} />
          </div>
        )
      default:
        return <input className="input" type="text" value={value} onChange={(e) => onChange(e.target.value)} placeholder={field.placeholder ?? undefined} spellCheck={false} />
    }
  })()
  return (
    <div className={`field ${hasError ? 'field-error' : ''}`}>
      <div className="field-label">{field.label}{field.key === 'sync.tags' && <InfoTip term="tag" />}{field.key === 'sync.maxQuestionAge' && <InfoTip term="window" />}</div>
      <div className="field-help">{field.help}</div>
      {control}
      <IssueList issues={issues} />
    </div>
  )
}

export function Section({ icon, title, children, num, hint }: { icon?: ReactNode; title: string; children: ReactNode; num?: number; hint?: string }) {
  return (
    <section className="card card-pad section">
      <div className="section-head">
        {num !== undefined ? <span className="section-num">{num}</span> : icon}
        <div><h3>{title}</h3>{hint && <p className="card-sub">{hint}</p>}</div>
      </div>
      <div className="stack">{children}</div>
    </section>
  )
}
