// Single place for backend HTTP access. Components must not call fetch directly.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal,
  })
  if (!response.ok) {
    let detail = `HTTP ${response.status}`
    try {
      const problem = (await response.json()) as { detail?: string; message?: string }
      detail = problem.detail ?? problem.message ?? detail
    } catch {
      /* body was not JSON */
    }
    throw new ApiError(response.status, detail)
  }
  return (await response.json()) as T
}

export const apiGet = <T,>(path: string, signal?: AbortSignal) => request<T>('GET', path, undefined, signal)
export const apiPost = <T,>(path: string, body?: unknown) => request<T>('POST', path, body ?? {})
export const apiPut = <T,>(path: string, body: unknown) => request<T>('PUT', path, body)
export const apiDelete = <T,>(path: string) => request<T>('DELETE', path)
