// Mirrors the backend JSON. Timestamps are ISO strings; null means "unknown" (never guessed).
export interface Freshness { lastSuccessfulSyncAt: string | null; ageHours: number | null; stale: boolean; note: string }

export interface SetupStep { id: string; title: string; description: string; status: 'DONE' | 'TODO' | 'ATTENTION' | 'READY' | 'LOCKED'; action: string }
export interface Overview {
  configFile: string; configExists: boolean; sourceType: string; sourceLabel: string; tags: string[]; window: string | null
  steps: SetupStep[]; nextStep: string
  totals: { questions: number; answers: number; accepted: number; unanswered: number; hidden: number; byStatus: Record<string, number> }
  categories: { id: string; name: string; count: number }[]
  recentRuns: { runId: string; mode: string; outcome: string; finishedAt: string; added: number; updated: number; failures: number }[]
  freshness: Freshness; syncRunning: boolean; notes: string[]
}

// ---- configuration
export type FieldType = 'TEXT' | 'NUMBER' | 'SWITCH' | 'SELECT' | 'TAGS' | 'PATH' | 'AGE' | 'ENV_NAME' | 'URL'
export interface ConfigField {
  key: string; label: string; help: string; type: FieldType; options: { value: string; label: string; hint: string }[]
  defaultValue: string; placeholder: string | null; showForSources: string[]; advanced: boolean; unit: string | null
}
export interface ConfigSection { id: string; title: string; description: string; icon: string; fields: ConfigField[] }
export interface Issue { key: string; level: 'error' | 'warning' | 'info'; message: string; fix: string | null }
export interface ConfigView { file: string; exists: boolean; values: Record<string, string>; sections: ConfigSection[]; issues: Issue[]; preview: string }
export interface SaveResult { saved: boolean; view: ConfigView; issues: Issue[] }
export interface Preset { id: string; title: string; description: string; icon: string; values: Record<string, string> }

// ---- connection check
export type StageStatus = 'PASS' | 'WARN' | 'FAIL' | 'INFO' | 'SKIPPED'
export interface Stage {
  id: string; title: string; question: string; status: StageStatus; summary: string
  facts: { label: string; value: string }[]; needs: { what: string; why: string; who: string }[]; millis: number
}
export interface ConnectionReport {
  checkedAt: string; target: string; sourceType: string; verdict: 'READY' | 'NEEDS_ATTENTION' | 'BLOCKED' | 'NOT_SUPPORTED_YET'
  headline: string; detail: string; score: number; stages: Stage[]
  route: { kind: string; label: string; explanation: string; supported: boolean; nextSteps: string[] }
  requirements: { id: string; label: string; status: 'MET' | 'MISSING' | 'UNKNOWN'; detail: string }[]
}

// ---- sync
export interface SyncProgress {
  runId: string; phase: 'LISTING' | 'FETCHING' | 'RECONCILING' | 'FINISHING' | 'DONE' | 'FAILED'; message: string
  pagesListed: number; questionsFound: number; toFetch: number; fetched: number; added: number; updated: number; unchanged: number; failures: number
}
export interface SyncReport {
  runId: string; mode: string; outcome: string; startedAt: string; finishedAt: string; resumed: boolean; tags: string[]
  pagesListed: number; questionsListed: number; added: number; updated: number; unchanged: number; statusChanged: number; purged: number
  failures: { questionId: string; kind: string; message: string }[]
  statusChanges: { questionId: string; from: string; to: string; reason: string }[]
  warnings: string[]; completeness: string; limitations: string[]; abortReason: string | null
}
export interface SyncState { running: boolean; startedAt: string | null; mode: string | null; progress: SyncProgress | null; lastReport: SyncReport | null; lastError: string | null }

// ---- search & questions
export interface AgeFilter { maxQuestionAge: string | null; minQuestionAge: string | null; maxAnswerAge: string | null; minAnswerAge: string | null }
export interface Evidence {
  rank: number; type: 'question' | 'answer'; questionId: string; answerId: string | null; title: string; passage: string
  sourceUrl: string | null; questionUrl: string | null; categories: string[]; categoryNames: string[]; tags: string[]
  accepted: boolean; votes: number | null; createdAt: string | null; questionCreatedAt: string | null
  sourceModifiedAt: string | null; lastSyncedAt: string | null; score: number; termCoverage: number
  threadStatus: string; flags: string[]; untrusted: boolean
}
export interface SearchResponse {
  query: string; ageFilter: AgeFilter | null; resultCount: number; confidence: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'
  truncated: boolean; omittedForBudget: number; freshness: Freshness | null; notice: string; evidence: Evidence[]
}
export interface Comment { id: string | null; author: string | null; body: string; createdAt: string | null }
export interface Answer { id: string; body: string; url: string | null; accepted: boolean; score: number | null; author: string | null; createdAt: string | null; modifiedAt: string | null; comments: Comment[] }
export interface Question { id: string; title: string; body: string; url: string | null; tags: string[]; score: number | null; author: string | null; createdAt: string | null; modifiedAt: string | null; comments: Comment[]; answers: Answer[] }
export interface ThreadView {
  thread: { sourceSystem: string; question: Question | null; status: string; statusReason: string | null; retrievedAt: string | null }
  categories: string[]; categoryNames: string[]
  classification: { method: string; categories: { categoryId: string; score: number; matchedRules: string[] }[] } | null
  override: { categories: string[]; note: string | null } | null; untrusted: boolean; notice: string
}
export interface CategorySummary { id: string; name: string; description: string; questionCount: number }
export interface QuestionRef { source: string; id: string; title: string; url: string | null; createdAt: string | null; status: string; answerCount: number; hasAcceptedAnswer: boolean; tags: string[] }
export interface CategoryListing { category: CategorySummary; questions: QuestionRef[] }
export interface DeleteByTagResult { tag: string; deletedThreads: number; deletedAnswers: number; deletedQuestionIds: string[] }

// ---- categories (taxonomy)
export interface Rule { type: 'keyword' | 'regex' | 'tag'; field: 'title' | 'body' | 'answers' | 'tags' | 'any'; pattern: string; weight: number }
export interface Category { id: string; name: string; description: string; rules: Rule[] }
export interface Taxonomy { version: number; minScore: number; fallbackCategory: string; categories: Category[] }
export interface TaxonomyView { custom: boolean; file: string; hash: string; taxonomy: Taxonomy; problems: string[] }
export interface TryResult { matches: { categoryId: string; name: string; score: number; matchedRules: string[] }[]; fallback: boolean; explanation: string }

// ---- the site's API documentation (Swagger)
export interface ApiParameter { name: string; in: string; required: boolean; type: string; description: string; allowed: string[] }
export interface ApiOperation { method: string; path: string; operationId: string | null; summary: string; tags: string[]; parameters: ApiParameter[]; responseCodes: string[]; deprecated: boolean }
export interface ApiSecurity { id: string; type: string; in: string | null; name: string | null; scheme: string | null; description: string }
export interface ApiCatalog { title: string; version: string; specUrls: string[]; suggestedBaseUrl: string | null; security: ApiSecurity[]; operations: ApiOperation[]; warnings: string[] }
export interface ApiCapability { id: string; label: string; purpose: string; required: boolean; found: boolean; method: string | null; path: string | null; parameters: string[] }
export interface ApiCapabilities { capabilities: ApiCapability[]; ready: boolean; problems: string[]; taggedFilter: boolean; fromDate: boolean; minActivity: boolean; customFilter: boolean; createFilter: boolean; otherEndpoints: number }
export interface ApiDocsResult { ok: boolean; problem: string | null; catalog: ApiCatalog | null; capabilities: ApiCapabilities | null; tried: string[]; suggestedBaseUrl: string | null; suggestedKeyName: string | null; suggestedKeyIn: string | null; needsSignIn: boolean; suggestedBasePath: string | null; bearerRequired: boolean }
export interface SavedDocs { ok: boolean; problem: string | null; path: string | null; operations: number }

// ---- topics discovered from the questions
export interface DiscoveredTopic { id: string; name: string; description: string; size: number; matches: number; topTerms: string[]; topTags: string[]; samples: { id: string; title: string }[]; rules: Rule[] }
export interface TopicProposal { topics: DiscoveredTopic[]; documents: number; unplaced: number; placedShare: number; method: string; notes: string[]; taxonomy: Taxonomy }
export interface ApiProbe { reached: boolean; problem: string | null; url: string | null; status: number; contentType: string | null; characters: number; kind: string | null; summary: string | null; preview: string | null; hints: string[]; keySent: boolean; keyPlacement: string | null; authNeeded: string | null; wwwAuthenticate: string | null; tokenSent: boolean; suggestedPath: string | null; redirects: string[]; signInSystem: string | null }
export interface TagHit { name: string; count: number }
export interface TagSearch { ok: boolean; problem: string | null; tags: TagHit[]; exact: string | null; spellingDiffers: boolean; allTags: boolean; siteQuestions: number; truncated: boolean }
export interface TagListingState { id: string | null; running: boolean; finished: boolean; loaded: number; pages: number; pageSize: number; complete: boolean; stopReason: string | null; problem: string | null; hasMoreUnreliable: boolean; siteQuestions: number }
export interface TagChunk { state: TagListingState; from: number; tags: TagHit[] }
export interface CollectionSample { id: string; title: string; tags: string[]; createdAt: string | null; answers: number; bodyCharacters: number }
export interface CollectionTest { ok: boolean; problem: string | null; tags: string[]; found: number; shown: number; request: string | null; samples: CollectionSample[]; notes: string[]; tagsAll: boolean }
