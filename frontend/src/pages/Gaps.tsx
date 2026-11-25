import { SearchX } from 'lucide-react'
import { useState } from 'react'
import { clearSearchLog, getGaps } from '../api'
import { PageHeader } from '../components/PageHeader'
import { Badge, Banner, Button, Card, CopyButton, EmptyState, Segmented, Skeleton } from '../components/ui'
import { timeAgo } from '../lib/format'
import { go, useRemote } from '../lib/useRemote'

const WINDOWS = [{ value: '7', label: '7 days' }, { value: '30', label: '30 days' }, { value: '90', label: '90 days' }]

/** Questions people asked that found nothing (or only weak matches): where knowledge is missing. */
export default function GapsPage() {
  const [days, setDays] = useState('30')
  const report = useRemote((s) => getGaps(Number(days), s), [days])
  const [clearing, setClearing] = useState(false)

  async function clear() {
    if (!window.confirm('Forget everything that was searched on this computer? This cannot be undone.')) return
    setClearing(true)
    try { await clearSearchLog(); report.reload() } finally { setClearing(false) }
  }

  const data = report.kind === 'ok' ? report.data : null
  const asText = data ? data.gaps.map((g) => `- ${g.query} (asked ${g.timesAsked}x, ${g.where === 'search-ai' ? 'Search-AI' : 'stored knowledge'}, last ${timeAgo(g.lastAskedAt)})`).join('\n') : ''

  return (
    <div className="stack" style={{ gap: 26 }}>
      <PageHeader eyebrow="Insights" title="Knowledge gaps"
        subtitle="Questions that found nothing, or only weak matches, and were not answered by a later search. Each one is a page or an answer that is missing. Kept on this computer only."
        actions={<Segmented<string> label="Period" value={days} onChange={setDays} options={WINDOWS} />} />
      {report.kind === 'loading' && <Skeleton h={120} />}
      {report.kind === 'error' && <Banner tone="bad" title="The report could not be loaded">{report.message}</Banner>}
      {data && (<>
        <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
          <Badge tone="neutral">{data.searches} searches</Badge>
          <Badge tone="ok">{data.answered} questions answered</Badge>
          <Badge tone={data.gapCount > 0 ? 'warn' : 'ok'}>{data.gapCount} unanswered</Badge>
        </div>
        {data.gaps.length === 0
          ? <EmptyState icon={<SearchX size={30} />} title={data.searches === 0 ? 'Nothing searched yet' : 'No gaps in this period'}>
              {data.searches === 0 ? 'Every search on the Explore page and every Search-AI search is noted here, so the questions nobody could answer show up.' : 'Everything that was searched found a good match.'}</EmptyState>
          : <Card>
              <div className="spread" style={{ marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
                <h3 style={{ margin: 0 }}>Most-asked unanswered questions</h3>
                <CopyButton text={asText} label="Copy as a list for the documentation owners" />
              </div>
              <div className="stack-sm">{data.gaps.map((g) => (
                <div key={`${g.where}-${g.query}`} className="spread" style={{ gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
                  <div style={{ minWidth: 0 }}>
                    <strong style={{ overflowWrap: 'anywhere' }}>{g.query}</strong>
                    <div className="small muted">asked {g.timesAsked} time{g.timesAsked === 1 ? '' : 's'} · {g.where === 'search-ai' ? 'Search-AI found nothing' : g.bestResults === 0 ? 'nothing stored matches' : 'only weak matches stored'} · last {timeAgo(g.lastAskedAt)}</div>
                  </div>
                  <Button size="sm" onClick={() => go(`/${g.where === 'search-ai' ? 'searchai' : 'explore?q=' + encodeURIComponent(g.query)}`)}>{g.where === 'search-ai' ? 'Open Search-AI' : 'Search again'}</Button>
                </div>))}</div>
            </Card>}
        <p className="muted small">When someone saves a page or confirms an answer and the question is searched again with a good result, it leaves this list by itself.
          <Button variant="ghost" size="sm" busy={clearing} onClick={() => void clear()}>Forget what was searched</Button></p>
      </>)}
    </div>
  )
}
