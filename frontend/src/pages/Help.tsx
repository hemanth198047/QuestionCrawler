import { Clock, FolderTree, Hash, Lock, RefreshCw, ShieldCheck, Tag } from 'lucide-react'
import { PageHeader } from '../components/PageHeader'
import { Card } from '../components/ui'

const CONCEPTS = [
  { icon: <Tag size={22} />, title: 'Tags', text: 'Labels people attach to a question, like “python” or “artificial-intelligence”. They decide what we collect.' },
  { icon: <Clock size={22} />, title: 'Time window', text: 'How far back to download. Small windows are quick and stay inside request limits; widen later.' },
  { icon: <RefreshCw size={22} />, title: 'Quick update vs Full check', text: 'Quick fetches what changed. Full re-verifies everything, so deleted or hidden questions disappear from your results.' },
  { icon: <FolderTree size={22} />, title: 'Topics', text: 'Groups like “Deployment”. Placed by readable rules you control on the Organize page.' },
  { icon: <Hash size={22} />, title: 'Votes & accepted answers', text: 'Helpful hints about what others found useful. They are not proof: always look at the date and other answers.' },
  { icon: <Lock size={22} />, title: 'Where your data lives', text: 'Everything is saved as ordinary files in one folder on this computer. Nothing is sent to an AI service.' },
]
const FAQ = [
  ['Why does the check say “blocked”?', 'Something between this computer and the site is in the way: often a VPN, a firewall or a company certificate. Open the red step on the Connect page: it lists exactly what is needed and who can provide it, and writes a message you can send.'],
  ['The site needs a login. Can you just use mine?', 'We never type passwords, bypass multi-factor sign-in or solve CAPTCHAs. Ask the site owners for a read-only token or service account, store it in an environment variable, and run the check again.'],
  ['Why is a Stack Overflow search finding nothing?', 'A 30-day window of a small tag may hold only a handful of questions. Widen the time window on the Collect page and run a Full check. Search matches words, so try the exact words used in the questions.'],
  ['What does “out of date” mean?', 'The last successful download is older than your limit (Settings → How should collecting behave). Run a Quick update.'],
  ['Is it safe to read what other people wrote?', 'Yes: it is shown as plain reading material and never acted on. If a passage looks like it is trying to instruct an AI, it is flagged in red.'],
  ['Can I use this with GitHub Copilot?', 'Yes. After collecting, open VS Code Copilot Chat in Agent mode and ask it to search your internal Q&A. It uses the same knowledge and cites the original links.'],
] as const

export default function HelpPage() {
  return (
    <div className="stack" style={{ gap: 30 }}>
      <PageHeader eyebrow="Help" title="Concepts, in plain words" subtitle="Hover the small (i) icons anywhere in the app for quick explanations. Here is the bigger picture." />
      <div className="grid-3">{CONCEPTS.map((c) => <Card key={c.title} className="how rise"><span className="how-icon">{c.icon}</span><h3>{c.title}</h3><p className="muted" style={{ marginTop: 6 }}>{c.text}</p></Card>)}</div>
      <section><h2 style={{ marginBottom: 14 }}>Common questions</h2>
        <Card pad={false}><ul className="history">{FAQ.map(([q, a]) => (
          <li key={q}><details><summary><strong>{q}</strong></summary><div className="hist-body"><p>{a}</p></div></details></li>))}</ul></Card></section>
      <Card className="copilot"><div className="row"><ShieldCheck size={22} color="var(--brand)" /><div><h3>Your privacy</h3><p className="muted">Collected content stays on this computer. Tokens are read from environment variables and never written to files, logs or this screen.</p></div></div></Card>
    </div>
  )
}
