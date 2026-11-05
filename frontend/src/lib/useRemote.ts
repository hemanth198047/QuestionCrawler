import { useCallback, useEffect, useRef, useState } from 'react'

export type Remote<T> = { kind: 'loading' } | { kind: 'ok'; data: T } | { kind: 'error'; message: string }

/** Loads data when `deps` change, cancels the in-flight request, and can be reloaded without flashing a spinner. */
export function useRemote<T>(load: (signal: AbortSignal) => Promise<T>, deps: unknown[]): Remote<T> & { reload: () => void; refreshing: boolean } {
  const [state, setState] = useState<Remote<T>>({ kind: 'loading' })
  const [tick, setTick] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const first = useRef(true)
  const loader = useRef(load)
  loader.current = load
  useEffect(() => {
    const controller = new AbortController()
    if (first.current) setState({ kind: 'loading' })
    else setRefreshing(true)
    loader.current(controller.signal)
      .then((data) => { setState({ kind: 'ok', data }); setRefreshing(false); first.current = false })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setRefreshing(false)
        setState({ kind: 'error', message: error instanceof Error ? error.message : 'Something went wrong' })
      })
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { ...state, reload, refreshing }
}

/** Minimal hash router: "#/explore?q=x" -> { path: "/explore", params } */
export function useHashRoute() {
  const parse = () => {
    const raw = window.location.hash.replace(/^#/, '') || '/'
    const [path, query = ''] = raw.split('?')
    return { path, params: new URLSearchParams(query) }
  }
  const [route, setRoute] = useState(parse)
  useEffect(() => {
    const onChange = () => { setRoute(parse()); window.scrollTo({ top: 0 }) }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

export const go = (to: string) => { window.location.hash = to }
