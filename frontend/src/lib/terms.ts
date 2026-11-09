import { useOverview } from '../components/OverviewContext'

/**
 * The words for what was collected, following the source: Confluence and SharePoint have PAGES, Stack Exchange /
 * Stack Overflow and the internal Q&A site have QUESTIONS (with answers), Jive has POSTS (documents, discussions and
 * questions, with replies). Every screen uses these instead of a fixed "question".
 */
export interface Terms {
  /** 'page', 'question' or 'post' */
  item: string
  items: string
  Item: string
  Items: string
  /** Whether the source has answers at all (pages do not) */
  hasAnswers: boolean
  /** 'answer' or 'reply' */
  answer: string
  answers: string
  Answers: string
  /** "asked" for questions, "created" for pages and posts */
  created: string
  /** The verb for collecting, for buttons: "Collect pages" */
  collect: string
  /** Pages are wiki-like (headings, no votes or accepted answers) */
  pageLike: boolean
}

const QUESTIONS: Terms = {
  item: 'question', items: 'questions', Item: 'Question', Items: 'Questions', hasAnswers: true,
  answer: 'answer', answers: 'answers', Answers: 'Answers', created: 'asked', collect: 'Collect questions', pageLike: false,
}
const PAGES: Terms = {
  item: 'page', items: 'pages', Item: 'Page', Items: 'Pages', hasAnswers: false,
  answer: 'answer', answers: 'answers', Answers: 'Answers', created: 'created', collect: 'Collect pages', pageLike: true,
}
const POSTS: Terms = {
  item: 'post', items: 'posts', Item: 'Post', Items: 'Posts', hasAnswers: true,
  answer: 'reply', answers: 'replies', Answers: 'Replies', created: 'created', collect: 'Collect posts', pageLike: true,
}

/** Words for a source type (source.type: confluence, sharepoint, links, jive, stackexchange, live, fixture). */
export function termsFor(sourceType: string | null | undefined): Terms {
  const t = (sourceType ?? '').toLowerCase()
  if (t === 'confluence' || t === 'sharepoint' || t === 'links') return PAGES
  if (t === 'jive') return POSTS
  return QUESTIONS
}

/** Words for one stored item, from the system it was collected from (confluence, jive, sharepoint, or a site name). */
export function termsForSystem(systemId: string | null | undefined): Terms {
  const s = (systemId ?? '').toLowerCase()
  if (s.startsWith('confluence') || s.startsWith('sharepoint') || s.startsWith('links')) return PAGES
  if (s.startsWith('jive')) return POSTS
  return QUESTIONS
}

/** Words for a kind as the backend names it (pages, questions, posts). */
export function termsForKind(kind: string | null | undefined): Terms {
  return kind === 'pages' ? PAGES : kind === 'posts' ? POSTS : QUESTIONS
}

/** Words for one stored source system, using what the overview knows about it (falls back to its name). */
export function useSystemTerms(): (system: string | null | undefined) => Terms {
  const { overview } = useOverview()
  return (system) => {
    const known = overview?.sources?.find((s) => s.system === system)
    return known ? termsForKind(known.kind) : termsForSystem(system)
  }
}

/** Words for the source currently configured. */
export function useTerms(): Terms {
  const { overview } = useOverview()
  return termsFor(overview?.sourceType)
}

/** "1 page", "3 pages". */
export function count(n: number, singular: string, plural: string): string {
  return `${n.toLocaleString()} ${n === 1 ? singular : plural}`
}
