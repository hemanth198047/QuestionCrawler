import { FileText, FolderOpen, Globe, Layers, Lock, Plug, RefreshCw, RotateCcw, Save, Tags, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { deleteByTag, getConfig, reindex, saveConfig, validateConfig } from '../api'
import { IssueList, SettingField } from '../components/Fields'
import { PageHeader } from '../components/PageHeader'
import { useOverview } from '../components/OverviewContext'
import { Badge, Banner, Button, Card, CopyButton, Skeleton, useToast } from '../components/ui'
import { isShown } from '../lib/fields'
import { go, useRemote } from '../lib/useRemote'
import type { Issue } from '../types/api'

const ICONS: Record<string, React.ReactNode> = { plug: <Plug size={19} />, lock: <Lock size={19} />, tags: <Tags size={19} />, globe: <Globe size={19} />, refresh: <RefreshCw size={19} />, folder: <FolderOpen size={19} /> }

export default function SettingsPage({ section }: { section: string | null }) {
  const toast = useToast()
  const { overview, reload } = useOverview()
  const cfg = useRemote((s) => getConfig(s), [])
  const [draft, setDraft] = useState<Record<string, string> | null>(null)
  const [saved, setSaved] = useState<Record<string, string> | null>(null)
  const [issues, setIssues] = useState<Issue[]>([])
  const [preview, setPreview] = useState('')
  const [advanced, setAdvanced] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deleteTag, setDeleteTag] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [confirmReindex, setConfirmReindex] = useState(false)
  const [reindexing, setReindexing] = useState(false)

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
  const visible = (sec?.fields ?? []).filter((f) => isShown(f, draft) && (advanced || !f.advanced))
  const hiddenAdvanced = (sec?.fields ?? []).filter((f) => f.advanced && isShown(f, draft)).length
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
  const runDeleteByTag = async () => {
    const tag = deleteTag.trim()
    if (!tag) { toast('bad', 'Type a tag to delete'); return }
    setDeleting(true)
    try {
      const r = await deleteByTag(tag)
      setConfirmDelete(false)
      setDeleteTag('')
      toast('ok', r.deletedThreads === 0
        ? `No stored questions carried the tag “${r.tag}”`
        : `Removed ${r.deletedThreads} question${r.deletedThreads === 1 ? '' : 's'} (and ${r.deletedAnswers} answer${r.deletedAnswers === 1 ? '' : 's'}) for “${r.tag}”`)
      reload()
    } catch (e) { toast('bad', e instanceof Error ? e.message : 'Could not delete') } finally { setDeleting(false) }
  }
  const runReindex = async () => {
    setReindexing(true)
    try {
      const r = await reindex()
      setConfirmReindex(false)
      toast('ok', r.threads === 0
        ? 'Nothing to rebuild yet — collect some content first'
        : `Rebuilt search for ${r.threads} item${r.threads === 1 ? '' : 's'} (${r.indexedDocuments} index document${r.indexedDocuments === 1 ? '' : 's'})`)
      reload()
    } catch (e) { toast('bad', e instanceof Error ? e.message : 'Could not rebuild the search index') } finally { setReindexing(false) }
  }
  const storedCount = overview?.totals.questions ?? 0
  const isWiki = source === 'confluence' || source === 'jive'

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
                {visible.map((f) => <SettingField key={f.key} field={f} value={draft[f.key] ?? ''} onChange={(v) => setDraft({ ...draft, [f.key]: v })} issues={issues.filter((i) => i.key === f.key)} source={source} />)}
                {visible.length === 0 && source !== 'fixture' && <p className="muted">Nothing to set here for the chosen system.</p>}
                {current === 'source' && source === 'fixture' && <Banner tone="info" title="Demo data needs nothing else">Choose a real system above to see its address, sign-in and collection settings.</Banner>}
                {current !== 'source' && current !== 'storage' && current !== 'collection' && source === 'fixture' && <p className="muted">Nothing to set here for demo data.</p>}
              </div>
              {current === 'source' && source !== 'fixture' && <div style={{ marginTop: 26 }}><Banner tone="info" title="Not sure it will work?" action={<Button onClick={() => go('/connect')}>Check access</Button>}>The Connect page tests the whole path to the site and says what is missing.</Banner></div>}
            </Card>
          )}
          {current === 'storage' && (
            <>
              <Card>
                <div className="spread" style={{ marginBottom: 6 }}>
                  <div>
                    <h3>Rebuild search index</h3>
                    <p className="card-sub">
                      Re-reads every locally stored {isWiki ? 'page' : 'question'} and rebuilds classification, Markdown exports, and the search index.
                      Use this after a search upgrade (for example when Explore says the index is an older version), or if search results look stale.
                      The remote site is not contacted — nothing is downloaded again.
                    </p>
                  </div>
                </div>
                <hr className="divider" />
                <p className="muted small" style={{ marginBottom: 14 }}>
                  {storedCount > 0
                    ? `${storedCount} stored ${isWiki ? 'page' : 'question'}${storedCount === 1 ? '' : 's'} on this computer.`
                    : 'No stored content yet — collect first, then rebuild if needed.'}
                </p>
                <div className="row" style={{ gap: 10 }}>
                  {confirmReindex ? (
                    <>
                      <span className="small muted">Rebuild classification and search for everything already saved here?</span>
                      <Button size="sm" variant="primary" icon={<Layers size={14} />} busy={reindexing} onClick={runReindex}>Yes, rebuild</Button>
                      <Button size="sm" variant="ghost" disabled={reindexing} onClick={() => setConfirmReindex(false)}>Cancel</Button>
                    </>
                  ) : (
                    <Button size="sm" variant="primary" icon={<Layers size={14} />} disabled={storedCount === 0} onClick={() => setConfirmReindex(true)}>Rebuild search index…</Button>
                  )}
                </div>
              </Card>
              <Card>
                <div className="spread" style={{ marginBottom: 6 }}>
                  <div>
                    <h3>Remove {isWiki ? 'pages' : 'questions'} by tag</h3>
                    <p className="card-sub">Permanently deletes matching {isWiki ? 'pages' : 'questions and answers'} from this computer’s knowledge folder (JSON files and the search index). The remote site is not contacted. The special tag “all” is refused.</p>
                  </div>
                </div>
                <hr className="divider" />
                <div className="field">
                  <div className="field-label">{isWiki ? 'Label / tag to remove' : 'Tag to remove'}</div>
                  <input className="input" value={deleteTag} onChange={(e) => { setDeleteTag(e.target.value); setConfirmDelete(false) }} placeholder="for example timeout" aria-label="Tag to delete from the local store" />
                </div>
                <div className="row" style={{ marginTop: 16, gap: 10 }}>
                  {confirmDelete ? (
                    <>
                      <span className="small muted">Delete every locally stored {isWiki ? 'page' : 'question'} tagged “{deleteTag.trim()}”?</span>
                      <Button size="sm" variant="danger" icon={<Trash2 size={14} />} busy={deleting} onClick={runDeleteByTag}>Yes, delete</Button>
                      <Button size="sm" variant="ghost" disabled={deleting} onClick={() => setConfirmDelete(false)}>Cancel</Button>
                    </>
                  ) : (
                    <Button size="sm" variant="danger" icon={<Trash2 size={14} />} disabled={!deleteTag.trim()} onClick={() => setConfirmDelete(true)}>Delete by tag…</Button>
                  )}
                </div>
              </Card>
            </>
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
