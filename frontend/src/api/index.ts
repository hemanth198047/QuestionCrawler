import type {
  ApiDocsResult, SavedDocs, CategoryListing, CategorySummary, ConfigView, ConnectionReport, Issue, Overview, Preset, SaveResult, SearchResponse,
  SyncReport, SyncState, Taxonomy, TaxonomyView, ThreadView, TopicProposal, TryResult,
} from '../types/api'
import { del, get, post, put } from './client'

export const getOverview = (s?: AbortSignal) => get<Overview>('/overview', s)

// settings
export const getConfig = (s?: AbortSignal) => get<ConfigView>('/config', s)
export const getPresets = (s?: AbortSignal) => get<Preset[]>('/config/presets', s)
export const validateConfig = (values: Record<string, string>) => post<{ issues: Issue[]; preview: string }>('/config/validate', { values })
export const saveConfig = (values: Record<string, string>) => put<SaveResult>('/config', { values })

// connection
export const checkConnection = (values?: Record<string, string>) => post<ConnectionReport>('/connection/check', { values: values ?? {} })
export const getLastConnection = (s?: AbortSignal) => get<ConnectionReport | null>('/connection/last', s)

// collecting
export interface AgeParams { maxQuestionAge: string; minQuestionAge: string; maxAnswerAge: string; minAnswerAge: string }
export const NO_AGE: AgeParams = { maxQuestionAge: '', minQuestionAge: '', maxAnswerAge: '', minAnswerAge: '' }
export const startSync = (full: boolean, maxQuestionAge: string) => post<SyncState>('/sync', { full, maxQuestionAge: maxQuestionAge || undefined })
export const getSyncState = (s?: AbortSignal) => get<SyncState>('/sync/status', s)
export const getSyncReports = (s?: AbortSignal) => get<SyncReport[]>('/sync/reports?limit=12', s)

// exploring
export function search(p: { q: string; tags: string[]; category: string; top: number; age: AgeParams }, signal?: AbortSignal) {
  const qs = new URLSearchParams({ q: p.q, top: String(p.top) })
  p.tags.forEach((t) => qs.append('tag', t))
  if (p.category) qs.append('category', p.category)
  ;(Object.keys(p.age) as (keyof AgeParams)[]).forEach((k) => { if (p.age[k]) qs.append(k, p.age[k]) })
  return get<SearchResponse>(`/search?${qs.toString()}`, signal)
}
export const getQuestion = (id: string, s?: AbortSignal) => get<ThreadView>(`/questions/${encodeURIComponent(id)}`, s)
export const setOverride = (id: string, categories: string[], note: string) => put<ThreadView>(`/questions/${encodeURIComponent(id)}/categories`, { categories, note })
export const clearOverride = (id: string) => del<ThreadView>(`/questions/${encodeURIComponent(id)}/categories`)
export const getCategories = (s?: AbortSignal) => get<CategorySummary[]>('/categories', s)
export function getCategory(id: string, maxQuestionAge: string, s?: AbortSignal) {
  const qs = maxQuestionAge ? `?maxQuestionAge=${encodeURIComponent(maxQuestionAge)}` : ''
  return get<CategoryListing>(`/categories/${encodeURIComponent(id)}${qs}`, s)
}

// organizing
export const getTaxonomy = (s?: AbortSignal) => get<TaxonomyView>('/taxonomy', s)
export const saveTaxonomy = (t: Taxonomy) => put<TaxonomyView>('/taxonomy', t)
export const resetTaxonomy = () => post<TaxonomyView>('/taxonomy/reset')
export const tryTaxonomy = (taxonomy: Taxonomy | null, title: string, body: string, tags: string[]) => post<TryResult>('/taxonomy/try', { taxonomy, title, body, tags })
export const reindex = () => post<{ threads: number; indexedDocuments: number }>('/reindex')

// the site's API documentation and topic discovery
export const discoverApiDocs = (values: Record<string, string>) => post<ApiDocsResult>('/api-docs/discover', { values })
export const discoverTopics = (k: number) => post<TopicProposal>('/topics/discover', { k })
export const uploadApiDocs = (content: string) => post<SavedDocs>('/api-docs/file', { content })
