import type {
  CategoryListing, CategorySummary, SearchResponse, StatusReport, SyncReport, SyncState, ThreadView,
} from '../types/api'
import { apiDelete, apiGet, apiPost, apiPut } from './client'

export interface SearchParams { q: string; tags: string[]; category: string; top: number }

export function search(params: SearchParams, signal?: AbortSignal) {
  const qs = new URLSearchParams({ q: params.q, top: String(params.top) })
  params.tags.forEach((t) => qs.append('tag', t))
  if (params.category) qs.append('category', params.category)
  return apiGet<SearchResponse>(`/search?${qs.toString()}`, signal)
}

export const getQuestion = (id: string, signal?: AbortSignal) =>
  apiGet<ThreadView>(`/questions/${encodeURIComponent(id)}`, signal)
export const getCategories = (signal?: AbortSignal) => apiGet<CategorySummary[]>('/categories', signal)
export const getCategory = (id: string, signal?: AbortSignal) =>
  apiGet<CategoryListing>(`/categories/${encodeURIComponent(id)}`, signal)
export const getStatus = (signal?: AbortSignal) => apiGet<StatusReport>('/status', signal)
export const setOverride = (id: string, categories: string[], note: string) =>
  apiPut<ThreadView>(`/questions/${encodeURIComponent(id)}/categories`, { categories, note })
export const clearOverride = (id: string) => apiDelete<ThreadView>(`/questions/${encodeURIComponent(id)}/categories`)

export const startSync = (full: boolean) => apiPost<SyncState>('/sync', { full })
export const getSyncState = (signal?: AbortSignal) => apiGet<SyncState>('/sync/status', signal)
export const getSyncReports = (signal?: AbortSignal) => apiGet<SyncReport[]>('/sync/reports?limit=10', signal)
export const reindex = () => apiPost<{ threads: number; indexedDocuments: number }>('/reindex')
