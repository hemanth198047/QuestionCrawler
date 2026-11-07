import { ArrowRight, BookOpenCheck, CheckCheck, Download, HelpCircle, Layers, Lock, MessageCircleQuestion, Plug, Search, ShieldCheck, Sparkles, TriangleAlert } from 'lucide-react'
import { reindex } from '../api'
import { Badge, Banner, Button, Card, Donut, EmptyState, InfoTip, PALETTE, Skeleton, Stat } from '../components/ui'
import { PageHeader } from '../components/PageHeader'
import { useOverview } from '../components/OverviewContext'
import { go } from '../lib/useRemote'
import { useToast } from '../components/ui'
import { number, timeAgo } from '../lib/format'
import { count, termsFor, termsForKind, useTerms } from '../lib/terms'
import type { Overview, SetupStep } from '../types/api'

const STEP_ICON: Record<string, React.ReactNode> = {
  choose: <Plug size={22} />, check: <ShieldCheck size={22} />, collect: <Download size={22} />, explore: <Search size={22} />,
}
const STEP_ROUTE: Record<string, string> = { connect: '/connect', collect: '/collect', explore: '/explore' }

function Journey({ steps, next }: { steps: SetupStep[]; next: string }) {
  return (
    <ol className="journey">
      {steps.map((s, i) => {
        const current = s.id === next && s.status !== 'DONE' && s.status !== 'READY'
        const locked = s.status === 'LOCKED'
        return (
          <li key={s.id} className={`journey-step st-${s.status.toLowerCase()} ${current ? 'current' : ''}`}>
            <a href={locked ? undefined : `#${STEP_ROUTE[s.action] ?? '/'}`} aria-disabled={locked} onClick={(e) => { if (locked) e.preventDefault() }}>
              <span className="journey-dot">{s.status === 'DONE' ? <CheckCheck size={22} /> : locked ? <Lock size={20} /> : s.status === 'ATTENTION' ? <TriangleAlert size={22} /> : STEP_ICON[s.id]}</span>
              <span className="journey-num">Step {i + 1}</span>
              <strong>{s.title}</strong>
              <span className="journey-desc">{s.description}</span>
              {current && <Badge tone="brand">Do this next</Badge>}
              {s.status === 'ATTENTION' && <Badge tone="warn">Needs attention</Badge>}
              {s.status === 'READY' && <Badge tone="ok">Ready</Badge>}
            </a>
          </li>
        )
      })}
    </ol>
  )
}

function Hero({ o }: { o: Overview }) {
  const q = o.totals.questions
  const t = termsFor(o.sourceType)
  let title = 'Welcome. Let’s connect your first source.'
  let text = 'Tell QuestionCrawler where your team’s knowledge lives: questions on a Q&A site, or pages on Confluence, Jive or SharePoint. It takes about two minutes, and you can try it with demo data first.'
  let cta = { label: 'Start setup', to: '/connect', icon: <Sparkles size={18} /> }
  if (o.nextStep === 'check') { title = 'One quick check before collecting.'; text = 'We’ll look at whether this computer can reach the site, and exactly what is needed if something is in the way.'; cta = { label: 'Check the connection', to: '/connect', icon: <ShieldCheck size={18} /> } }
  else if (o.nextStep === 'collect') { title = `Everything is set. Time to collect your first ${t.items}.`; text = t.hasAnswers && !t.pageLike ? 'We’ll download the questions and answers for your tags and organize them into topics. Start with a small time window.' : `We’ll download the ${t.items} you chose and organize them into topics.`; cta = { label: t.collect, to: '/collect', icon: <Download size={18} /> } }
  else if (q > 0) { title = 'Your knowledge is ready to ask.'; text = `${storedText(o)} ${o.sources.length === 1 && t.pageLike ? 'Ask in plain words: the best paragraph is quoted as the answer.' : 'Search by words, error messages, topic or age.'}`; cta = { label: 'Ask a question', to: '/explore', icon: <Search size={18} /> } }
  return (
    <Card className="hero rise" pad={false}>
      <div className="hero-copy">
        <Badge tone="brand" icon={<Sparkles size={13} />}>{q > 0 ? 'Ready' : 'Getting started'}</Badge>
        <h1>{title}</h1>
        <p className="subtitle">{text}</p>
        <div className="row" style={{ marginTop: 26 }}>
          <Button variant="primary" size="lg" icon={cta.icon} onClick={() => go(cta.to)}>{cta.label}</Button>
          {q > 0 && <Button size="lg" onClick={() => go('/collect')} icon={<Download size={18} />}>Update now</Button>}
        </div>
      </div>
      <div className="hero-art" aria-hidden>
        <svg viewBox="0 0 320 240" width="100%">
          <defs><linearGradient id="g1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#5668f0" /><stop offset="1" stopColor="#3345c9" /></linearGradient></defs>
          <rect x="30" y="34" width="190" height="62" rx="16" fill="#fff" stroke="#ebe6db" /><rect x="46" y="52" width="92" height="8" rx="4" fill="#e3e7fd" /><rect x="46" y="68" width="140" height="8" rx="4" fill="#f1eee6" />
          <rect x="70" y="112" width="220" height="62" rx="16" fill="url(#g1)" /><rect x="88" y="130" width="110" height="8" rx="4" fill="#fff" opacity=".85" /><rect x="88" y="146" width="160" height="8" rx="4" fill="#fff" opacity=".45" />
          <circle cx="258" cy="48" r="26" fill="#e5f5ed" /><path d="M246 48l8 8 14-16" stroke="#1d9a68" strokeWidth="5" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          <rect x="30" y="192" width="120" height="30" rx="15" fill="#fdf1d9" /><rect x="44" y="203" width="60" height="8" rx="4" fill="#f2d9a1" />
        </svg>
      </div>
    </Card>
  )
}

/** "312 questions and 1,204 answers from Stack Overflow and 115 pages from Confluence are stored on this computer." */
function storedText(o: Overview): string {
  const parts = o.sources.map((x) => {
    const w = termsForKind(x.kind)
    return `${count(x.count, w.item, w.items)}${w.hasAnswers && x.answers > 0 ? ` and ${count(x.answers, w.answer, w.answers)}` : ''} from ${x.label}`
  })
  if (parts.length === 0) return 'Nothing is stored on this computer yet.'
  const list = parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
  const total = o.sources.reduce((a, x) => a + x.count, 0)
  return `${list} ${total === 1 ? 'is' : 'are'} stored on this computer.`
}

function HowItWorks() {
  const t = useTerms()
  const items = [
    { icon: <Download size={22} />, title: 'Collect', text: t.pageLike ? `Downloads the ${t.items} of the spaces or places you choose. Everything is saved as plain files on this computer.` : 'Downloads questions and their answers for the tags you choose. Everything is saved as plain files on this computer.' },
    { icon: <Layers size={22} />, title: 'Organize', text: `Sorts ${t.items} into topics with simple rules you can read and change. No hidden AI guesswork.` },
    { icon: <MessageCircleQuestion size={22} />, title: 'Ask', text: t.pageLike ? `Ask in plain words. The paragraph of your ${t.items} that answers it is quoted, with a link to the exact section.` : 'Search in plain words, an error message or a function name, and filter by how recent the question or answer is.' },
  ]
  return (
    <div className="grid-3">
      {items.map((it) => (
        <Card key={it.title} className="how rise"><span className="how-icon">{it.icon}</span><h3>{it.title}</h3><p className="muted" style={{ marginTop: 6 }}>{it.text}</p></Card>
      ))}
    </div>
  )
}

export default function OverviewPage() {
  const { overview: o, reload } = useOverview()
  const toast = useToast()
  if (!o) {
    return (<div className="stack"><Skeleton h={260} r={26} /><div className="grid-4"><Skeleton h={96} r={18} /><Skeleton h={96} r={18} /><Skeleton h={96} r={18} /><Skeleton h={96} r={18} /></div></div>)
  }
  const hasData = o.totals.questions > 0
  const t = termsFor(o.sourceType)
  const slices = o.categories.slice().sort((a, b) => b.count - a.count).map((c, i) => ({ label: c.name, value: c.count, color: PALETTE[i % PALETTE.length], id: c.id }))
  const pctAnswered = hasData ? Math.round((o.totals.accepted / o.totals.questions) * 100) : 0
  return (
    <div className="stack">
      <PageHeader eyebrow="Overview" title="Home" subtitle="Where things stand, and what to do next." />
      {o.notes.map((n) => (
        <Banner key={n} tone="warn" title="Heads up"
          action={/Re-sort/.test(n) ? <Button size="sm" onClick={async () => { const r = await reindex(); toast('ok', `Re-sorted ${count(r.threads, t.item, t.items)}`); reload() }}>Re-sort now</Button> : undefined}>{n}</Banner>
      ))}
      <Hero o={o} />
      <section aria-labelledby="journey-h">
        <h2 id="journey-h" style={{ marginBottom: 14 }}>Your journey</h2>
        <Journey steps={o.steps} next={o.nextStep} />
      </section>
      {!hasData && (<section><h2 style={{ marginBottom: 14 }}>How it works</h2><HowItWorks /></section>)}
      {hasData && (
        <>
          <div className="grid-4">
            {o.sources.length > 1
              ? <Stat icon={<BookOpenCheck size={22} />} label="Stored" value={number(o.totals.questions)} hint={o.sources.map((x) => `${number(x.count)} ${x.count === 1 ? termsForKind(x.kind).item : termsForKind(x.kind).items} · ${x.label}`).join(' — ')} />
              : <Stat icon={<BookOpenCheck size={22} />} label={t.Items} value={number(o.totals.questions)} hint={o.window ? `from the past ${o.window}` : 'all collected'} />}
            {t.hasAnswers && <Stat tone="info" icon={<MessageCircleQuestion size={22} />} label={t.Answers} value={number(o.totals.answers)} hint={`across all ${t.items}`} />}
            {t.hasAnswers && !t.pageLike && <Stat tone="ok" icon={<CheckCheck size={22} />} label="With accepted answer" value={`${pctAnswered}%`} hint={count(o.totals.accepted, t.item, t.items)} />}
            {t.hasAnswers && !t.pageLike && <Stat tone="warn" icon={<HelpCircle size={22} />} label="Still unanswered" value={number(o.totals.unanswered)} hint="worth a look" />}
            {!t.hasAnswers && <Stat tone="info" icon={<Layers size={22} />} label="Topics" value={number(o.categories.filter((c) => c.count > 0).length)} hint={`that your ${t.items} fall into`} />}
          </div>
          <div className="grid-2">
            <Card>
              <div className="spread"><h3>What’s inside <InfoTip term="category" /></h3><a href="#/organize" className="small">Browse topics <ArrowRight size={13} style={{ verticalAlign: -2 }} /></a></div>
              <div className="inside">
                <Donut slices={slices} size={190} center={<><strong style={{ fontSize: 30, letterSpacing: '-0.03em' }}>{number(o.totals.questions)}</strong><span className="muted small">{t.items}</span></>} />
                <ul className="legend">
                  {slices.slice(0, 8).map((s) => (
                    <li key={s.id}><a href={`#/organize/browse/${s.id}`}><i style={{ background: s.color }} />{s.label}<b>{s.value}</b></a></li>
                  ))}
                </ul>
              </div>
            </Card>
            <Card>
              <div className="spread"><h3>Recent activity</h3><a href="#/collect" className="small">All runs <ArrowRight size={13} style={{ verticalAlign: -2 }} /></a></div>
              <p className="card-sub" style={{ marginBottom: 10 }}>{o.freshness.lastSuccessfulSyncAt ? `Last successful update ${timeAgo(o.freshness.lastSuccessfulSyncAt)}.` : 'No successful update yet.'} {o.freshness.stale && o.freshness.lastSuccessfulSyncAt ? 'That is older than your limit.' : ''}</p>
              {o.recentRuns.length === 0 ? <p className="muted">Nothing yet.</p> : (
                <ul className="timeline">
                  {o.recentRuns.map((r) => (
                    <li key={r.runId} className={r.outcome === 'COMPLETE' ? 'ok' : r.outcome === 'PARTIAL' ? 'warn' : 'bad'}>
                      <i /><div><strong>{r.mode === 'FULL' ? 'Full check' : 'Quick update'}</strong> <Badge tone={r.outcome === 'COMPLETE' ? 'ok' : r.outcome === 'PARTIAL' ? 'warn' : 'bad'}>{r.outcome === 'COMPLETE' ? 'Finished' : r.outcome === 'PARTIAL' ? 'Some problems' : 'Stopped'}</Badge>
                        <div className="muted small">{timeAgo(r.finishedAt)} · {r.added} new, {r.updated} changed{r.failures ? `, ${r.failures} failed` : ''}</div></div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>
        </>
      )}
      {!hasData && o.nextStep !== 'collect' && (<EmptyState icon={<Plug size={30} />} title="Nothing collected yet">Complete the steps above and your knowledge will appear here.</EmptyState>)}
    </div>
  )
}
