import { useEffect, useState } from 'react'

export type Remote<T> = { kind: 'loading' } | { kind: 'ok'; data: T } | { kind: 'error'; message: string }

/** Loads data when `deps` change; aborts the in-flight request on change/unmount. */
export function useRemote<T>(load: (signal: AbortSignal) => Promise<T>, deps: unknown[]): Remote<T> & { reload: () => void } {
  const [state, setState] = useState<Remote<T>>({ kind: 'loading' })
  const [tick, setTick] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setState({ kind: 'loading' })
    load(controller.signal)
      .then((data) => setState({ kind: 'ok', data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ kind: 'error', message: error instanceof Error ? error.message : 'Unknown error' })
      })
    return () => controller.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { ...state, reload: () => setTick((t) => t + 1) }
}

/** Minimal hash router: "#/search?q=x" -> { path: "/search", params } */
export function useHashRoute() {
  const parse = () => {
    const raw = window.location.hash.replace(/^#/, '') || '/search'
    const [path, query = ''] = raw.split('?')
    return { path, params: new URLSearchParams(query) }
  }
  const [route, setRoute] = useState(parse)
  useEffect(() => {
    const onChange = () => setRoute(parse())
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}
