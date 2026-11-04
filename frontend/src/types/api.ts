// DTOs mirroring the backend JSON. Timestamps are ISO strings or null (= unknown).
export interface Evidence {
  rank: number
  type: 'question' | 'answer'
  questionId: string
  answerId: string | null
  title: string
  passage: string
  sourceUrl: string | null
  questionUrl: string | null
  categories: string[]
  categoryNames: string[]
  tags: string[]
  accepted: boolean
  votes: number | null
  sourceModifiedAt: string | null
  lastSyncedAt: string | null
  score: number
  termCoverage: number
  threadStatus: string
  flags: string[]
  untrusted: boolean
}

export interface Freshness {
  lastSuccessfulSyncAt: string | null
  ageHours: number | null
  stale: boolean
  note: string
}

export interface SearchResponse {
  query: string
  resultCount: number
  confidence: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'
  truncated: boolean
  omittedForBudget: number
  freshness: Freshness | null
  notice: string
  evidence: Evidence[]
}

export interface Comment { id: string | null; author: string | null; body: string; createdAt: string | null }
export interface Answer {
  id: string; body: string; url: string | null; accepted: boolean; score: number | null
  author: string | null; createdAt: string | null; modifiedAt: string | null; comments: Comment[]
}
export interface Question {
  id: string; title: string; body: string; url: string | null; tags: string[]; score: number | null
  author: string | null; createdAt: string | null; modifiedAt: string | null; comments: Comment[]; answers: Answer[]
}
export interface ThreadView {
  thread: { sourceSystem: string; question: Question | null; status: string; statusReason: string | null; retrievedAt: string | null }
  categories: string[]
  categoryNames: string[]
  classification: { method: string; categories: { categoryId: string; score: number; matchedRules: string[] }[] } | null
  override: { categories: string[]; note: string | null } | null
  untrusted: boolean
  notice: string
}

export interface CategorySummary { id: string; name: string; description: string; questionCount: number }
export interface QuestionRef {
  source: string; id: string; title: string; url: string | null; status: string
  answerCount: number; hasAcceptedAnswer: boolean; tags: string[]
}
export interface CategoryListing { category: CategorySummary; questions: QuestionRef[] }

export interface SyncReport {
  runId: string; mode: string; outcome: string; startedAt: string; finishedAt: string; resumed: boolean
  tags: string[]; pagesListed: number; questionsListed: number
  added: number; updated: number; unchanged: number; statusChanged: number; purged: number
  failures: { questionId: string; kind: string; message: string }[]
  statusChanges: { questionId: string; from: string; to: string; reason: string }[]
  warnings: string[]; completeness: string; limitations: string[]; abortReason: string | null
}
export interface SyncState { running: boolean; startedAt: string | null; mode: string | null; lastReport: SyncReport | null; lastError: string | null }

export interface StatusReport {
  storageRoot: string; initialized: boolean; schemaVersion: number | null; threadCount: number
  byStatus: Record<string, number>; answerCount: number; indexedDocuments: number | null
  interruptedRunPending: boolean; freshness: Freshness; notes: string[]
  lastSync: { runId: string; mode: string; outcome: string; finishedAt: string; completeness: string } | null
}
