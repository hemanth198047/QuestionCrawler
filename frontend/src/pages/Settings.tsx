import { FileText, FolderOpen, Plug, RefreshCw, RotateCcw, Save, Tags } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { getConfig, saveConfig, validateConfig } from '../api'
import { IssueList, SettingField } from '../components/Fields'
import { PageHeader } from '../components/PageHeader'
import { useOverview } from '../components/OverviewContext'
import { Badge, Banner, Button, Card, CopyButton, Skeleton, useToast } from '../components/ui'
import { go, useRemote } from '../lib/useRemote'
import type { Issue } from '../types/api'

const ICONS: Record<string, React.ReactNode> = { plug: <Plug size={19} />, tags: <Tags size={19} />, refresh: <RefreshCw size={19} />, folder: <FolderOpen size={19} /> }

export default function SettingsPage({ section }: { section: string | null }) {
  const toast = useToast()
  const { reload } = useOverview()
  const cfg = useRemote((s) => getConfig(s), [])
  const [draft, setDraft] = useState<Record<string, string> | null>(null)
  const [saved, setSaved] = useState<Record<string, string> | null>(null)
  const [issues, setIssues] = useState<Issue[]>([])
  const [preview, setPreview] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => { if (cfg.kind === 'ok' && !draft) { setDraft(cfg.data.values); setSaved(cfg.data.values); setIssues(cfg.data.issues); setPreview(cfg.data.preview) } }, [cfg, draft])
  const dirty = useMemo(() => !!draft && !!saved && JSON.stringify(draft) !== JSON.stringify(saved), [draft, saved])
  useEffect(() => {
    if (!draft) return
    const t = window.setTimeout(() => { validateConfig(draft).then((r) => { setIssues(r.issues); setPreview(r.preview) }).catch(() => undefined) }, 300)
    return () => window.clearTimeout(t)
  }, [draft])

  if (cfg.kind === 'error') return <Banner tone="bad" title="Settings could not be loaded">{cfg.message}</Banner>
  if (cfg.kind === 'loading' || !draft) return <div className="stack"><Skeleton h={70} /><Skeleton h={420} r={18} /></div>

  const sections = cfg.data.sections
  const current = section === 'file' ? 'file' : sections.find((s) => s.id === section)?.id ?? sections[0].id
  const source = draft['source.type'] ?? 'fixture'
  const sec = sections.find((s) => s.id === current)
  const visible = (sec?.fields ?? []).filter((f) => (f.showForSources.length === 0 || f.showForSources.includes(source)) && (advanced || !f.advanced))
  const hiddenAdvanced = (sec?.fields ?? []).filter((f) => f.advanced && (f.showForSources.length === 0 || f.showForSources.includes(source))).length
  const countFor = (id: string) => issues.filter((i) => i.level !== 'info' && (sections.find((s) => s.id === id)?.fields ?? []).some((f) => f.key === i.key)).length
  const blocking = issues.some((i) => i.level === 'error')
  const save = async () => {
    setSaving(true)
    try {
      const r = await saveConfig(draft)
      setIssues(r.issues)
      if (r.saved) { setDraft(r.view.values); setSaved(r.view.values); setPreview(r.view.preview); toast('ok', 'Settings saved'); reload() } else toast('bad', 'Some settings need fixing first')
    } catch (e) { toast('bad', e instanceof Error ? e.message : 'Could not save') } finally { setSaving(false) }
  }

  return (
    <div className="stack" style={{ gap: 26 }}>
      <PageHeader eyebrow="Settings" title="Everything you can change" subtitle="Each setting says what it does in plain words. Changes are checked as you type, and nothing is saved until you press Save." />
      <div className="settings-layout">
        <nav className="subnav" aria-label="Settings sections">
          {sections.map((s) => (
            <a key={s.id} href={`#/settings/${s.id}`} className={current === s.id ? 'on' : ''}>{ICONS[s.icon]}<span>{s.title.replace(/\?$/, '')}</span>{countFor(s.id) > 0 && <b className="count">{countFor(s.id)}</b>}</a>
          ))}
          <a href="#/settings/file" className={current === 'file' ? 'on' : ''}><FileText size={19} /><span>Settings file</span></a>
          <div className="subnav-foot muted small">Saved in<br /><code>{cfg.data.file}</code></div>
        </nav>
        <div className="stack">
          {current === 'file' ? (
            <Card>
              <div className="spread"><div><h3>The settings file, in plain text</h3><p className="card-sub">This is exactly what is written to disk. Every line is explained, and secrets are never stored in it.</p></div><CopyButton text={preview} /></div>
              <pre className="template filepreview">{preview}</pre>
            </Card>
          ) : sec && (
            <Card>
              <div className="spread" style={{ marginBottom: 6 }}><div><h2>{sec.title}</h2><p className="muted" style={{ marginTop: 4 }}>{sec.description}</p></div>
                {hiddenAdvanced > 0 && <label className="switch"><input type="checkbox" checked={advanced} onChange={(e) => setAdvanced(e.target.checked)} /><span className="switch-track" /><span className="small">Show {hiddenAdvanced} advanced</span></label>}</div>
              <hr className="divider" />
              <div className="stack" style={{ gap: 30 }}>
                {visible.map((f) => <SettingField key={f.key} field={f} value={draft[f.key] ?? ''} onChange={(v) => setDraft({ ...draft, [f.key]: v })} issues={issues.filter((i) => i.key === f.key)} />)}
                {visible.length === 0 && <p className="muted">Nothing to set here for the chosen source.</p>}
                {current === 'source' && source === 'fixture' && <Banner tone="info" title="Demo data needs nothing else">Choose “Stack Overflow / Stack Exchange” or “Our internal site” above to see the options for reaching a real site.</Banner>}
              </div>
              {current === 'source' && source !== 'fixture' && <div style={{ marginTop: 26 }}><Banner tone="info" title="Not sure it will work?" action={<Button onClick={() => go('/connect')}>Check access</Button>}>The Connect page tests the whole path to the site and says what is missing.</Banner></div>}
            </Card>
          )}
          {issues.some((i) => !sections.flatMap((s) => s.fields).some((f) => f.key === i.key)) && <IssueList issues={issues.filter((i) => !sections.flatMap((s) => s.fields).some((f) => f.key === i.key))} />}
        </div>
      </div>
      <div className="savebar"><div className="savebar-inner">
        <div>{dirty ? <><strong>You have unsaved changes</strong> {blocking && <Badge tone="bad">Fix the red items to save</Badge>}</> : <span className="muted">All settings saved</span>}</div>
        <div className="row">{dirty && <Button variant="ghost" icon={<RotateCcw size={15} />} onClick={() => setDraft(saved)}>Discard</Button>}<Button variant="primary" icon={<Save size={16} />} busy={saving} disabled={!dirty || blocking} onClick={save}>Save settings</Button></div>
      </div></div>
    </div>
  )
}
