// Single place for backend HTTP access. Components must not call fetch directly.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    headers: { Accept: 'application/json' },
    signal,
  })
  if (!response.ok) {
    throw new Error(`Request to ${path} failed with HTTP ${response.status}`)
  }
  return (await response.json()) as T
}
