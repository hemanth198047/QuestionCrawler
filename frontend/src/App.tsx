import { Compass, DownloadCloud, FolderTree, LayoutDashboard, LifeBuoy, Plug, Search, Settings as SettingsIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { OverviewProvider, useOverview } from './components/OverviewContext'
import { ToastProvider, Banner } from './components/ui'
import { useHashRoute } from './lib/useRemote'
import { number, timeAgo } from './lib/format'
import CollectPage from './pages/Collect'
import ConnectPage from './pages/Connect'
import ExplorePage from './pages/Explore'
import HelpPage from './pages/Help'
import OrganizePage from './pages/Organize'
import OverviewPage from './pages/OverviewPage'
import QuestionPage from './pages/Question'
import SettingsPage from './pages/Settings'

function NavItem({ to, icon, label, active, dot, badge }: { to: string; icon: ReactNode; label: string; active: boolean; dot?: boolean; badge?: string }) {
  return (
    <a href={`#${to}`} className={`nav-link ${active ? 'on' : ''}`} aria-current={active ? 'page' : undefined}>
      {icon}<span className="label">{label}</span>
      {dot && <span className="nav-dot" title="Needs your attention" />}
      {badge && <span className="nav-badge">{badge}</span>}
    </a>
  )
}

function Shell() {
  const { path } = useHashRoute()
  const { overview, error } = useOverview()
  const is = (p: string) => (p === '/' ? path === '/' : path.startsWith(p))
  const next = overview?.nextStep
  const fresh = overview?.freshness
  return (
    <div className="shell">
      <aside className="sidebar">
        <a href="#/" className="brand" style={{ textDecoration: 'none' }}>
          <span className="brand-mark"><Compass size={22} /></span>
          <span><div className="brand-name" style={{ color: 'var(--ink)' }}>QuestionCrawler</div><div className="brand-tag">Your team’s knowledge, ready to ask</div></span>
        </a>
        <div className="nav-group">Get started</div>
        <NavItem to="/" icon={<LayoutDashboard size={19} />} label="Overview" active={is('/')} />
        <NavItem to="/connect" icon={<Plug size={19} />} label="Connect a source" active={is('/connect')} dot={next === 'choose' || next === 'check'} />
        <div className="nav-group">Work</div>
        <NavItem to="/collect" icon={<DownloadCloud size={19} />} label="Collect" active={is('/collect')} dot={next === 'collect'} badge={overview?.syncRunning ? 'Running' : undefined} />
        <NavItem to="/explore" icon={<Search size={19} />} label="Explore" active={is('/explore') || is('/question')} />
        <NavItem to="/organize" icon={<FolderTree size={19} />} label="Organize" active={is('/organize')} />
        <div className="nav-group">System</div>
        <NavItem to="/settings" icon={<SettingsIcon size={19} />} label="Settings" active={is('/settings')} />
        <NavItem to="/help" icon={<LifeBuoy size={19} />} label="Help & concepts" active={is('/help')} />
        <div className="sidebar-foot">
          <div className="side-card">
            <div className="muted">Knowledge on this computer</div>
            <strong>{overview ? number(overview.totals.questions) : '–'} <span style={{ fontSize: 14, fontWeight: 550, color: 'var(--ink-3)' }}>questions</span></strong>
            <div className={`fresh ${!fresh || !fresh.lastSuccessfulSyncAt ? 'none' : fresh.stale ? 'stale' : ''}`}>
              <i />{!fresh || !fresh.lastSuccessfulSyncAt ? 'Not collected yet' : fresh.stale ? `Out of date · ${timeAgo(fresh.lastSuccessfulSyncAt)}` : `Updated ${timeAgo(fresh.lastSuccessfulSyncAt)}`}
            </div>
          </div>
        </div>
      </aside>
      <main className="main">
        {error && !overview && (
          <div style={{ marginBottom: 24 }}><Banner tone="bad" title="The QuestionCrawler service is not reachable">{error}</Banner></div>
        )}
        <Routes />
      </main>
    </div>
  )
}

function Routes() {
  const { path, params } = useHashRoute()
  if (path.startsWith('/connect')) return <ConnectPage />
  if (path.startsWith('/collect')) return <CollectPage />
  if (path.startsWith('/explore')) return <ExplorePage params={params} key={params.toString()} />
  if (path.startsWith('/question/')) return <QuestionPage id={decodeURIComponent(path.slice('/question/'.length))} focusAnswerId={params.get('answer')} focusSection={params.get('section')} key={path + '?' + params.toString()} />
  if (path.startsWith('/organize')) return <OrganizePage tab={path.startsWith('/organize/rules') ? 'rules' : path.startsWith('/organize/discover') ? 'discover' : 'browse'} selected={path.startsWith('/organize/browse/') ? decodeURIComponent(path.slice('/organize/browse/'.length)) : null} />
  if (path.startsWith('/settings')) return <SettingsPage section={path.split('/')[2] ?? null} />
  if (path.startsWith('/help')) return <HelpPage />
  return <OverviewPage />
}

export default function App() {
  return (
    <ToastProvider>
      <OverviewProvider>
        <Shell />
      </OverviewProvider>
    </ToastProvider>
  )
}
