import type {
  CategoryListing, CategorySummary, SearchResponse, StatusReport, SyncReport, SyncState, ThreadView,
} from '../types/api'
import { apiDelete, apiGet, apiPost, apiPut } from './client'

/** Ages look like 12h, 30d, 2w, 6m, 1y and are measured back from now to creation time. */
export interface AgeParams { maxQuestionAge: string; minQuestionAge: string; maxAnswerAge: string; minAnswerAge: string }
export const NO_AGE: AgeParams = { maxQuestionAge: '', minQuestionAge: '', maxAnswerAge: '', minAnswerAge: '' }
export interface SearchParams { q: string; tags: string[]; category: string; top: number; age: AgeParams }

export function search(params: SearchParams, signal?: AbortSignal) {
  const qs = new URLSearchParams({ q: params.q, top: String(params.top) })
  params.tags.forEach((t) => qs.append('tag', t))
  if (params.category) qs.append('category', params.category)
  ;(Object.keys(params.age) as (keyof AgeParams)[]).forEach((k) => { if (params.age[k].trim()) qs.append(k, params.age[k].trim()) })
  return apiGet<SearchResponse>(`/search?${qs.toString()}`, signal)
}

export const getQuestion = (id: string, signal?: AbortSignal) =>
  apiGet<ThreadView>(`/questions/${encodeURIComponent(id)}`, signal)
export const getCategories = (signal?: AbortSignal) => apiGet<CategorySummary[]>('/categories', signal)
export function getCategory(id: string, maxQuestionAge: string, signal?: AbortSignal) {
  const qs = maxQuestionAge.trim() ? `?maxQuestionAge=${encodeURIComponent(maxQuestionAge.trim())}` : ''
  return apiGet<CategoryListing>(`/categories/${encodeURIComponent(id)}${qs}`, signal)
}
export const getStatus = (signal?: AbortSignal) => apiGet<StatusReport>('/status', signal)
export const setOverride = (id: string, categories: string[], note: string) =>
  apiPut<ThreadView>(`/questions/${encodeURIComponent(id)}/categories`, { categories, note })
export const clearOverride = (id: string) => apiDelete<ThreadView>(`/questions/${encodeURIComponent(id)}/categories`)

export const startSync = (full: boolean, maxQuestionAge: string) =>
  apiPost<SyncState>('/sync', { full, maxQuestionAge: maxQuestionAge.trim() || undefined })
export const getSyncState = (signal?: AbortSignal) => apiGet<SyncState>('/sync/status', signal)
export const getSyncReports = (signal?: AbortSignal) => apiGet<SyncReport[]>('/sync/reports?limit=10', signal)
export const reindex = () => apiPost<{ threads: number; indexedDocuments: number }>('/reindex')
