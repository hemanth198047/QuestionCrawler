import { useEffect, useState } from 'react'
import { getHealth } from '../api/health'
import type { HealthResponse } from '../types/health'

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; data: HealthResponse }
  | { kind: 'error'; message: string }

export default function HomePage() {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    getHealth(controller.signal)
      .then((data) => setState({ kind: 'ok', data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ kind: 'error', message: error instanceof Error ? error.message : 'Unknown error' })
      })
    return () => controller.abort()
  }, [])

  return (
    <section>
      <h2>Backend connection</h2>
      {state.kind === 'loading' && <p>Checking backend…</p>}
      {state.kind === 'error' && <p role="alert">Backend unreachable: {state.message}</p>}
      {state.kind === 'ok' && (
        <dl>
          <dt>Status</dt>
          <dd>{state.data.status}</dd>
          <dt>Application</dt>
          <dd>{state.data.application}</dd>
          <dt>Java</dt>
          <dd>{state.data.javaVersion}</dd>
        </dl>
      )}
    </section>
  )
}
