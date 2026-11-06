import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { AlertTriangle, Check, CheckCircle2, Copy, Info, Loader2, X, XCircle } from 'lucide-react'
import { GLOSSARY } from '../../lib/glossary'

export type Tone = 'neutral' | 'ok' | 'warn' | 'bad' | 'info' | 'brand'

// ------------------------------------------------------------------ buttons & surfaces
export function Button({ variant = 'secondary', size = 'md', icon, busy, children, className = '', ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'ghost' | 'danger'; size?: 'sm' | 'md' | 'lg'; icon?: ReactNode; busy?: boolean }) {
  return (
    <button className={`btn btn-${variant} btn-${size} ${className}`} disabled={busy || rest.disabled} {...rest}>
      {busy ? <Loader2 size={16} className="spin" /> : icon}
      {children}
    </button>
  )
}

export function Card({ children, className = '', pad = true, ...rest }: { children: ReactNode; className?: string; pad?: boolean } & React.HTMLAttributes<HTMLDivElement>) {
  return <div className={`card ${pad ? 'card-pad' : ''} ${className}`} {...rest}>{children}</div>
}

export function Badge({ tone = 'neutral', icon, children }: { tone?: Tone; icon?: ReactNode; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{icon}{children}</span>
}

export function Chip({ children, onRemove, onClick, active, icon }: { children: ReactNode; onRemove?: () => void; onClick?: () => void; active?: boolean; icon?: ReactNode }) {
  const body = <>{icon}<span>{children}</span>{onRemove && <button type="button" className="chip-x" aria-label="Remove" onClick={(e) => { e.stopPropagation(); onRemove() }}><X size={12} /></button>}</>
  return onClick
    ? <button type="button" className={`chip chip-btn ${active ? 'chip-active' : ''}`} onClick={onClick}>{body}</button>
    : <span className={`chip ${active ? 'chip-active' : ''}`}>{body}</span>
}

export function Segmented<T extends string>({ options, value, onChange, label }: { options: { value: T; label: string; hint?: string }[]; value: T; onChange: (v: T) => void; label?: string }) {
  return (
    <div className="segmented" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" role="radio" aria-checked={o.value === value} title={o.hint} className={o.value === value ? 'on' : ''} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  )
}

export function Skeleton({ h = 16, w = '100%', r = 8 }: { h?: number; w?: number | string; r?: number }) {
  return <span className="skeleton" style={{ height: h, width: w, borderRadius: r }} />
}

export function EmptyState({ icon, title, children, action }: { icon: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty rise">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  )
}

export function Banner({ tone = 'info', title, children, action }: { tone?: Tone; title?: string; children?: ReactNode; action?: ReactNode }) {
  const icon = tone === 'ok' ? <CheckCircle2 size={20} /> : tone === 'bad' ? <XCircle size={20} /> : tone === 'warn' ? <AlertTriangle size={20} /> : <Info size={20} />
  return (
    <div className={`banner banner-${tone} rise`} role={tone === 'bad' ? 'alert' : 'status'}>
      <span className="banner-icon">{icon}</span>
      <div className="banner-body">{title && <strong>{title}</strong>}{children && <div>{children}</div>}</div>
      {action && <div className="banner-action">{action}</div>}
    </div>
  )
}

export function ProgressBar({ value, max = 100, tone = 'brand', striped }: { value: number; max?: number; tone?: Tone; striped?: boolean }) {
  const pct = max <= 0 ? 0 : Math.min(100, Math.round((value / max) * 100))
  return <div className="progress" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}><div className={`progress-bar tone-${tone} ${striped ? 'striped' : ''}`} style={{ width: `${pct}%` }} /></div>
}

export function ScoreRing({ value, size = 96, tone = 'brand', label }: { value: number; size?: number; tone?: Tone; label?: string }) {
  const r = (size - 12) / 2
  const c = 2 * Math.PI * r
  return (
    <div className="ring" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden>
        <circle cx={size / 2} cy={size / 2} r={r} className="ring-track" strokeWidth="9" fill="none" />
        <circle cx={size / 2} cy={size / 2} r={r} className={`ring-fill tone-${tone}`} strokeWidth="9" fill="none" strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={c * (1 - Math.min(100, Math.max(0, value)) / 100)} transform={`rotate(-90 ${size / 2} ${size / 2})`} />
      </svg>
      <div className="ring-center"><strong>{Math.round(value)}</strong>{label && <span>{label}</span>}</div>
    </div>
  )
}

export const PALETTE = ['#3d4fe0', '#1d9a68', '#e08a1e', '#c2417a', '#2a78d2', '#7a5af8', '#12a3a3', '#d0443a', '#8a9a1d', '#b5651d', '#5b6b8a', '#a3a9b8']

export function Donut({ slices, size = 180, center }: { slices: { label: string; value: number; color: string }[]; size?: number; center?: ReactNode }) {
  const total = slices.reduce((a, s) => a + s.value, 0)
  const r = (size - 28) / 2
  const c = 2 * Math.PI * r
  let offset = 0
  return (
    <div className="donut" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label="Share by category">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--mute-bg)" strokeWidth="22" />
        {total > 0 && slices.map((s) => {
          const len = (s.value / total) * c
          const el = <circle key={s.label} cx={size / 2} cy={size / 2} r={r} fill="none" stroke={s.color} strokeWidth="22"
            strokeDasharray={`${Math.max(0, len - 2)} ${c}`} strokeDashoffset={-offset} transform={`rotate(-90 ${size / 2} ${size / 2})`}><title>{s.label}: {s.value}</title></circle>
          offset += len
          return el
        })}
      </svg>
      <div className="donut-center">{center}</div>
    </div>
  )
}

export function Stat({ icon, label, value, hint, tone = 'brand' }: { icon: ReactNode; label: string; value: ReactNode; hint?: string; tone?: Tone }) {
  return (
    <Card className="stat rise">
      <span className={`stat-icon tone-bg-${tone}`}>{icon}</span>
      <div><div className="stat-label">{label}</div><div className="stat-value">{value}</div>{hint && <div className="stat-hint">{hint}</div>}</div>
    </Card>
  )
}

// ------------------------------------------------------------------ helpers & tips
export function InfoTip({ term, text, title }: { term?: keyof typeof GLOSSARY; text?: string; title?: string }) {
  const id = useId()
  const entry = term ? GLOSSARY[term] : undefined
  const body = text ?? entry?.text
  const head = title ?? entry?.title
  if (!body) return null
  return (
    <span className="tip">
      <button type="button" className="tip-btn" aria-describedby={id} aria-label={`What is ${head ?? 'this'}?`}><Info size={14} /></button>
      <span role="tooltip" id={id} className="tip-pop">{head && <strong>{head}</strong>}{body}</span>
    </span>
  )
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false)
  return (
    <Button size="sm" variant="secondary" icon={done ? <Check size={15} /> : <Copy size={15} />} onClick={async () => {
      try { await navigator.clipboard.writeText(text) } catch { /* clipboard may be blocked */ }
      setDone(true); window.setTimeout(() => setDone(false), 1600)
    }}>{done ? 'Copied' : label}</Button>
  )
}

export function TagInput({ value, onChange, placeholder }: { value: string[]; onChange: (v: string[]) => void; placeholder?: string }) {
  const [draft, setDraft] = useState('')
  const add = () => {
    const parts = draft.split(',').map((s) => s.trim()).filter(Boolean)
    if (parts.length) onChange([...value, ...parts.filter((p) => !value.includes(p))])
    setDraft('')
  }
  return (
    <div className="taginput" onClick={(e) => (e.currentTarget.querySelector('input') as HTMLInputElement | null)?.focus()}>
      {value.map((t) => <Chip key={t} onRemove={() => onChange(value.filter((x) => x !== t))}>{t}</Chip>)}
      <input value={draft} placeholder={value.length ? '' : placeholder} aria-label="Add a tag"
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } else if (e.key === 'Backspace' && !draft && value.length) onChange(value.slice(0, -1)) }}
        onBlur={add} />
    </div>
  )
}

export function Spinner({ label }: { label?: string }) {
  return <span className="inline-spinner"><Loader2 size={18} className="spin" />{label}</span>
}

// ------------------------------------------------------------------ toasts
interface ToastItem { id: number; tone: Tone; text: string }
const ToastContext = createContext<(tone: Tone, text: string) => void>(() => undefined)
export const useToast = () => useContext(ToastContext)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const seq = useRef(0)
  const push = useCallback((tone: Tone, text: string) => {
    const id = ++seq.current
    setItems((x) => [...x, { id, tone, text }])
    // Errors stay (30 s, or until dismissed) so they can be read; everything else disappears quickly.
    window.setTimeout(() => setItems((x) => x.filter((t) => t.id !== id)), tone === 'bad' ? 30000 : 4200)
  }, [])
  const dismiss = (id: number) => setItems((x) => x.filter((t) => t.id !== id))
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={`toast toast-${t.tone}`} role={t.tone === 'bad' ? 'alert' : 'status'}>
            {t.tone === 'ok' ? <CheckCircle2 size={18} /> : t.tone === 'bad' ? <XCircle size={18} /> : <Info size={18} />}<span>{t.text}</span>
            {t.tone === 'bad' && <button type="button" className="toast-close" aria-label="Dismiss" onClick={() => dismiss(t.id)}>✕</button>}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

/** Closes on Escape and outside click. */
export function useDismiss(open: boolean, close: () => void) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, close])
}
