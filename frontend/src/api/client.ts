// Single place for backend HTTP access. Components never call fetch directly.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'Cannot reach the QuestionCrawler service. Is it running? Start everything with: npm start')
  }
  if (!response.ok) {
    let detail = `The service answered with an error (HTTP ${response.status}).`
    try {
      const problem = (await response.json()) as { detail?: string; message?: string }
      detail = problem.detail ?? problem.message ?? detail
    } catch {
      /* body was not JSON */
    }
    throw new ApiError(response.status, detail)
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : null) as T
}

export const get = <T,>(path: string, signal?: AbortSignal) => request<T>('GET', path, undefined, signal)
export const post = <T,>(path: string, body?: unknown) => request<T>('POST', path, body ?? {})
export const put = <T,>(path: string, body: unknown) => request<T>('PUT', path, body)
export const del = <T,>(path: string) => request<T>('DELETE', path)
