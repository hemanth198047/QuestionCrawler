import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { getOverview } from '../api'
import type { Overview } from '../types/api'

interface Ctx { overview: Overview | null; error: string | null; reload: () => void }
const OverviewCtx = createContext<Ctx>({ overview: null, error: null, reload: () => undefined })
export const useOverview = () => useContext(OverviewCtx)

/** Keeps one shared, regularly refreshed copy of the overview so every screen agrees about the current state. */
export function OverviewProvider({ children }: { children: ReactNode }) {
  const [overview, setOverview] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const timer = useRef<number | undefined>(undefined)
  const failing = error !== null
  useEffect(() => {
    let alive = true
    const load = () => getOverview().then((o) => { if (alive) { setOverview(o); setError(null) } }).catch((e: unknown) => { if (alive) setError(e instanceof Error ? e.message : 'Cannot load') })
    void load()
    timer.current = window.setInterval(load, overview?.syncRunning ? 2000 : failing ? 3000 : 15000) // retry quickly while the backend is still starting
    return () => { alive = false; window.clearInterval(timer.current) }
  }, [tick, overview?.syncRunning, failing])
  return <OverviewCtx.Provider value={{ overview, error, reload: () => setTick((t) => t + 1) }}>{children}</OverviewCtx.Provider>
}
