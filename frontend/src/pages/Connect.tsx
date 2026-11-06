import { Building2, Globe, Layers, Play, Save, ShieldCheck, Sparkles } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { checkConnection, getConfig, getLastConnection, getPresets, saveConfig, validateConfig } from '../api'
import { ApiDocsPanel } from '../components/ApiDocsPanel'
import { CollectTestPanel } from '../components/CollectTestPanel'
import { RawAnswerPanel } from '../components/RawAnswerPanel'
import { TagFinder } from '../components/TagFinder'
import { IssueList, Section, SettingField } from '../components/Fields'
import { PageHeader } from '../components/PageHeader'
import { useOverview } from '../components/OverviewContext'
import { RequestTemplate, RequirementsCard, RouteCard, RouteMap, StageDetail, Verdict, useReveal } from '../components/RouteMap'
import { Banner, Button, Card, Skeleton, useToast } from '../components/ui'
import { go, useRemote } from '../lib/useRemote'
import type { ConfigField, ConnectionReport, Issue, Preset } from '../types/api'

const ICONS: Record<string, React.ReactNode> = { sparkles: <Sparkles size={20} />, globe: <Globe size={20} />, layers: <Layers size={20} />, building: <Building2 size={20} /> }

export default function ConnectPage() {
  const toast = useToast()
  const { reload } = useOverview()
  const cfg = useRemote((s) => getConfig(s), [])
  const presets = useRemote((s) => getPresets(s), [])
  const last = useRemote((s) => getLastConnection(s), [])
  const [draft, setDraft] = useState<Record<string, string> | null>(null)
  const [saved, setSaved] = useState<Record<string, string> | null>(null)
  const [report, setReport] = useState<ConnectionReport | null>(null)
  const [checking, setChecking] = useState(false)
  const [selected, setSelected] = useState('network')
  const [issues, setIssues] = useState<Issue[]>([])
  const [saving, setSaving] = useState(false)
  const [checkError, setCheckError] = useState<string | null>(null)

  useEffect(() => { if (cfg.kind === 'ok' && !draft) { setDraft(cfg.data.values); setSaved(cfg.data.values); setIssues(cfg.data.issues) } }, [cfg, draft])
  useEffect(() => { if (last.kind === 'ok' && last.data && !report) setReport(last.data) }, [last, report])

  const dirty = useMemo(() => !!draft && !!saved && JSON.stringify(draft) !== JSON.stringify(saved), [draft, saved])
  const reveal = useReveal(report?.stages.length ?? 0, report?.checkedAt)

  useEffect(() => {
    if (!draft) return
    const t = window.setTimeout(() => { validateConfig(draft).then((r) => setIssues(r.issues)).catch(() => undefined) }, 350)
    return () => window.clearTimeout(t)
  }, [draft])

  if (cfg.kind === 'error') return <Banner tone="bad" title="Settings could not be loaded">{cfg.message}</Banner>
  if (cfg.kind === 'loading' || !draft || !cfg.data) return <div className="stack"><Skeleton h={80} /><Skeleton h={260} r={18} /><Skeleton h={320} r={18} /></div>

  const sections = cfg.data.sections
  const source = draft['source.type'] ?? 'fixture'
  const fieldsFor = (id: string): ConfigField[] => (sections.find((s) => s.id === id)?.fields ?? []).filter((f) => (f.showForSources.length === 0 || f.showForSources.includes(source)) && !f.advanced)
  const set = (key: string, v: string) => setDraft({ ...draft, [key]: v })
  const issuesFor = (key: string) => issues.filter((i) => i.key === key)
  const blocking = issues.some((i) => i.level === 'error')

  const applyPreset = (p: Preset) => { setDraft({ ...draft, ...p.values }); setReport(null) }
  const runCheck = async () => {
    setChecking(true); setCheckError(null); setReport(null)
    try {
      const r = await checkConnection(draft)
      setReport(r); setSelected(r.stages.find((s) => s.status === 'FAIL' || s.status === 'WARN')?.id ?? r.stages[0].id)
    } catch (e) { setCheckError(e instanceof Error ? e.message : 'The check could not run') }
    finally { setChecking(false); reload() }
  }
  const save = async (thenCollect: boolean) => {
    setSaving(true)
    try {
      const r = await saveConfig(draft)
      setIssues(r.issues)
      if (r.saved) { setDraft(r.view.values); setSaved(r.view.values); toast('ok', 'Settings saved'); reload(); if (thenCollect) go('/collect') }
      else toast('bad', 'Some settings need fixing first')
    } catch (e) { toast('bad', e instanceof Error ? e.message : 'Could not save') } finally { setSaving(false) }
  }
  const stage = report?.stages.find((s) => s.id === selected) ?? report?.stages[0]
  const activePreset = presets.kind === 'ok' ? presets.data.find((p) => p.values['source.type'] === source && (p.values['source.siteUrl'] ?? '') === (source === 'stackexchange' ? draft['source.siteUrl'] : '')) : undefined

  return (
    <div className="stack" style={{ gap: 28 }}>
      <PageHeader eyebrow="Connect a source" title="Where do the questions live?" subtitle="Choose a site, say what you want from it, and we’ll show you step by step whether we can reach it, and what is needed if not." />

      <Section num={1} title="Pick a starting point" hint="You can change this any time.">
        <div className="choice-grid">
          {presets.kind === 'ok' && presets.data.map((p) => (
            <button type="button" key={p.id} className={`choice ${activePreset?.id === p.id ? 'on' : ''}`} onClick={() => applyPreset(p)} aria-pressed={activePreset?.id === p.id}>
              <span className="choice-icon">{ICONS[p.icon] ?? <Globe size={20} />}</span><strong>{p.title}</strong><span className="hint">{p.description}</span>
            </button>
          ))}
        </div>
      </Section>

      <Section num={2} title="Describe what to collect" hint="Plain choices, with an explanation under each.">
        {['source', 'topic'].flatMap((id) => fieldsFor(id)).filter((f) => f.key !== 'source.type').map((f) => (
          <SettingField key={f.key} field={f} value={draft[f.key] ?? ''} onChange={(v) => set(f.key, v)} issues={issuesFor(f.key)} />
        ))}
        {source === 'live' && <ApiDocsPanel values={draft} onUse={(patch) => { setDraft({ ...draft, ...patch }); toast('ok', 'Settings filled in from the documentation') }} />}
        {(source === 'live' || source === 'stackexchange') && <TagFinder values={draft} chosen={(draft['sync.tags'] ?? '').split(',').map((x) => x.trim()).filter(Boolean)}
          onUseAll={() => { setDraft({ ...draft, 'sync.tags': 'all' }); toast('ok', 'Every tag is selected: all questions on the site will be collected') }}
          onAdd={(tag) => { const cur = (draft['sync.tags'] ?? '').split(',').map((x) => x.trim()).filter(Boolean); setDraft({ ...draft, 'sync.tags': [...cur, tag].join(',') }); toast('ok', `Added ${tag}`) }} />}
        {(source === 'live' || source === 'stackexchange') && <CollectTestPanel values={draft} />}
        {source === 'live' && <RawAnswerPanel values={draft} onUse={(patch) => setDraft({ ...draft, ...patch })} />}
        {source === 'fixture' && <Banner tone="info" title="Demo data needs nothing else">Sample questions are built into the app. Choose a real site above whenever you’re ready.</Banner>}
      </Section>

      <Section num={3} title="Can we reach it?" hint="We test the whole path and explain each step in plain language. Nothing is changed or downloaded.">
        <div className="row">
          <Button variant="primary" size="lg" icon={<ShieldCheck size={18} />} busy={checking} onClick={runCheck}>{report ? 'Check again' : 'Check access'}</Button>
          {report && <span className="muted small">Last checked {new Date(report.checkedAt).toLocaleTimeString()}</span>}
        </div>
        {checkError && <Banner tone="bad" title="The check could not run">{checkError}</Banner>}
        {checking && <div className="stack-sm"><Skeleton h={120} r={18} /></div>}
        {report && !checking && (
          <div className="stack">
            <Verdict report={report} />
            <Card className="rise">
              <h3>The path to your questions</h3>
              <p className="card-sub">Each step must be open for collecting to work. Click a step to see what we found.</p>
              <RouteMap report={report} selected={selected} onSelect={setSelected} revealed={reveal} />
            </Card>
            {stage && reveal >= report.stages.length && <StageDetail stage={stage} />}
            <div className="grid-2">
              <RouteCard report={report} />
              <RequirementsCard report={report} />
            </div>
            <RequestTemplate report={report} />
          </div>
        )}
      </Section>

      <div className="savebar">
        <div className="savebar-inner">
          <div>{dirty ? <strong>You have unsaved changes</strong> : <span className="muted">All settings saved</span>}{blocking && <span className="small" style={{ color: 'var(--bad)', marginLeft: 12 }}>Fix the red items to save</span>}</div>
          <div className="row">
            {dirty && <Button variant="ghost" onClick={() => { setDraft(saved); setReport(null) }}>Discard</Button>}
            <Button icon={<Save size={16} />} busy={saving} disabled={!dirty || blocking} onClick={() => save(false)}>Save settings</Button>
            <Button variant="primary" icon={<Play size={16} />} busy={saving} disabled={blocking} onClick={() => save(true)}>{dirty ? 'Save & go collect' : 'Go collect'}</Button>
          </div>
        </div>
      </div>
      <IssueList issues={[]} />
    </div>
  )
}
