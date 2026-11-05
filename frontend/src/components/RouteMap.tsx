import { CheckCircle2, CircleDashed, Compass, Database, KeyRound, Laptop, Lock, MinusCircle, Network, ShieldAlert, Tag, TriangleAlert, XCircle } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { ConnectionReport, Stage, StageStatus } from '../types/api'
import { Badge, Card, CopyButton, ScoreRing, type Tone } from './ui'

const ICONS: Record<string, React.ReactNode> = {
  network: <Network size={22} />, secure: <Lock size={22} />, access: <KeyRound size={22} />, data: <Database size={22} />, limits: <ShieldAlert size={22} />, scope: <Tag size={22} />,
}
const STATUS: Record<StageStatus, { tone: Tone; label: string; icon: React.ReactNode }> = {
  PASS: { tone: 'ok', label: 'Open', icon: <CheckCircle2 size={14} /> },
  INFO: { tone: 'info', label: 'Good, with a note', icon: <CheckCircle2 size={14} /> },
  WARN: { tone: 'warn', label: 'Needs attention', icon: <TriangleAlert size={14} /> },
  FAIL: { tone: 'bad', label: 'Blocked', icon: <XCircle size={14} /> },
  SKIPPED: { tone: 'neutral', label: 'Not checked', icon: <MinusCircle size={14} /> },
}
const VERDICT_TONE: Record<string, Tone> = { READY: 'ok', NEEDS_ATTENTION: 'warn', BLOCKED: 'bad', NOT_SUPPORTED_YET: 'warn' }

/** The path from "this computer" to "the questions", drawn as a chain. Everything after the first blocked node is greyed. */
export function RouteMap({ report, selected, onSelect, revealed }: { report: ConnectionReport; selected: string; onSelect: (id: string) => void; revealed: number }) {
  const firstBlock = report.stages.findIndex((s) => s.status === 'FAIL')
  return (
    <div className="routemap" role="list" aria-label="Path from this computer to the site's questions">
      <div className="rm-node rm-start" role="listitem"><span className="rm-dot start"><Laptop size={22} /></span><span className="rm-title">This computer</span><span className="rm-sub">start</span></div>
      {report.stages.map((s, i) => {
        const shown = i < revealed
        const cut = firstBlock >= 0 && i > firstBlock
        const st = STATUS[s.status]
        const cls = !shown ? 'pending' : cut ? 'cut' : s.status.toLowerCase()
        return (
          <div key={s.id} className="rm-seg" role="listitem">
            <span className={`rm-link ${shown && !cut ? s.status.toLowerCase() : ''}`} />
            <button type="button" className={`rm-node ${cls} ${selected === s.id ? 'sel' : ''}`} onClick={() => onSelect(s.id)} aria-pressed={selected === s.id}>
              <span className={`rm-dot ${cls}`}>{!shown ? <CircleDashed size={22} className="spin" /> : ICONS[s.id] ?? <Compass size={22} />}</span>
              <span className="rm-title">{s.title}</span>
              {shown ? <Badge tone={cut ? 'neutral' : st.tone} icon={st.icon}>{cut ? 'Waiting' : st.label}</Badge> : <span className="rm-sub">checking…</span>}
            </button>
          </div>
        )
      })}
    </div>
  )
}

export function StageDetail({ stage }: { stage: Stage }) {
  const st = STATUS[stage.status]
  return (
    <Card className="stagedetail rise" key={stage.id}>
      <div className="spread">
        <div className="row"><span className={`rm-dot small ${stage.status.toLowerCase()}`}>{ICONS[stage.id]}</span>
          <div><div className="muted small">{stage.question}</div><h3>{stage.title}</h3></div></div>
        <Badge tone={st.tone} icon={st.icon}>{st.label}</Badge>
      </div>
      <p className="stage-summary">{stage.summary}</p>
      {stage.facts.length > 0 && (
        <dl className="facts">{stage.facts.map((f, i) => <div key={i}><dt>{f.label}</dt><dd>{f.value}</dd></div>)}</dl>
      )}
      {stage.needs.length > 0 && (
        <div className="needs">
          <h4>What is needed</h4>
          {stage.needs.map((n, i) => (
            <div className="need" key={i}>
              <span className="need-n">{i + 1}</span>
              <div><strong>{n.what}</strong><div className="muted small">{n.why}</div><Badge tone="brand">Ask: {n.who}</Badge></div>
            </div>
          ))}
        </div>
      )}
      {stage.needs.length === 0 && (stage.status === 'PASS' || stage.status === 'INFO') && <p className="muted small">Nothing is needed here.</p>}
    </Card>
  )
}

export function Verdict({ report }: { report: ConnectionReport }) {
  const tone = VERDICT_TONE[report.verdict] ?? 'neutral'
  const ringTone: Tone = report.score >= 85 ? 'ok' : report.score >= 50 ? 'warn' : 'bad'
  return (
    <Card className={`verdict verdict-${tone} rise`}>
      <ScoreRing value={report.score} tone={ringTone} label="open" size={104} />
      <div style={{ flex: 1, minWidth: 220 }}>
        <Badge tone={tone}>{report.verdict === 'READY' ? 'Ready' : report.verdict === 'BLOCKED' ? 'Blocked' : report.verdict === 'NOT_SUPPORTED_YET' ? 'Reachable, not supported yet' : 'Almost there'}</Badge>
        <h2 style={{ marginTop: 8 }}>{report.headline}</h2>
        <p className="muted" style={{ marginTop: 6 }}>{report.detail}</p>
      </div>
    </Card>
  )
}

export function RouteCard({ report }: { report: ConnectionReport }) {
  const r = report.route
  return (
    <Card className="rise">
      <div className="spread"><h3>Recommended route</h3><Badge tone={r.supported ? 'ok' : 'warn'}>{r.supported ? 'Supported by this app' : 'Not available yet'}</Badge></div>
      <h2 style={{ fontSize: 19, marginTop: 10 }}>{r.label}</h2>
      <p className="muted" style={{ marginTop: 6 }}>{r.explanation}</p>
      <ol className="steps-list">{r.nextSteps.map((s, i) => <li key={i}>{s}</li>)}</ol>
    </Card>
  )
}

export function RequirementsCard({ report }: { report: ConnectionReport }) {
  const ico = { MET: <CheckCircle2 size={19} />, MISSING: <XCircle size={19} />, UNKNOWN: <CircleDashed size={19} /> }
  const tone = { MET: 'ok', MISSING: 'bad', UNKNOWN: 'neutral' } as const
  return (
    <Card className="rise">
      <h3>What collecting from this site needs</h3>
      <p className="card-sub" style={{ marginBottom: 14 }}>A plain checklist, so nobody has to guess what is missing.</p>
      <ul className="reqs">
        {report.requirements.map((r) => (
          <li key={r.id} className={`req-${r.status.toLowerCase()}`}>
            <span className={`req-ico tone-bg-${tone[r.status]}`}>{ico[r.status]}</span>
            <div><strong>{r.label}</strong><div className="muted small">{r.detail}</div></div>
            <Badge tone={tone[r.status]}>{r.status === 'MET' ? 'Have it' : r.status === 'MISSING' ? 'Missing' : 'To confirm'}</Badge>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** A ready-to-send message listing exactly what is missing, so the person does not have to work out what to ask for. */
export function RequestTemplate({ report }: { report: ConnectionReport }) {
  // Only things somebody else has to provide belong in a message to someone else.
  const missing = report.stages.flatMap((s) => s.needs.map((n) => ({ ...n, stage: s.title }))).filter((n) => !/^You( |$)/.test(n.who))
  if (missing.length === 0) return null
  const text = [
    'Hello,',
    '',
    `I would like to build a searchable, internal copy of the questions and answers on ${report.target || 'your Q&A site'} (read-only, for our team's use).`,
    'While checking access I found these things are needed:',
    '',
    ...missing.map((m, i) => `${i + 1}. ${m.what}\n   Why: ${m.why}`),
    '',
    'Could you help, or point me to the right person? I only need read access to questions, answers and tags. I will store any token safely (never in files) and respect your rate limits.',
    '',
    'Thank you.',
  ].join('\n')
  return (
    <Card className="rise">
      <div className="spread"><div><h3>Ask the right people</h3><p className="card-sub">Copy this message and send it to the site owners or your IT team.</p></div><CopyButton text={text} label="Copy message" /></div>
      <pre className="template">{text}</pre>
    </Card>
  )
}

/** Reveals stages one by one so the check feels like a journey, not a wall of results. */
export function useReveal(total: number, trigger: unknown) {
  const [n, setN] = useState(total)
  useEffect(() => {
    setN(0)
    let i = 0
    const t = window.setInterval(() => { i += 1; setN(i); if (i >= total) window.clearInterval(t) }, 260)
    return () => window.clearInterval(t)
  }, [trigger, total])
  return n
}
