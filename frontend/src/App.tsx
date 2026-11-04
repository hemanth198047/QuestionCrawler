import { useHashRoute } from './hooks'
import CategoriesPage from './pages/CategoriesPage'
import QuestionPage from './pages/QuestionPage'
import SearchPage from './pages/SearchPage'
import SyncPage from './pages/SyncPage'

export default function App() {
  const { path, params } = useHashRoute()
  const questionId = path.startsWith('/question/') ? decodeURIComponent(path.slice('/question/'.length)) : null
  const categoryId = path.startsWith('/categories/') ? path.slice('/categories/'.length) : null

  return (
    <main className="app">
      <header className="top">
        <h1>QuestionCrawler</h1>
        <nav>
          <a href="#/search">Search</a>
          <a href="#/categories">Categories</a>
          <a href="#/sync">Sync</a>
        </nav>
      </header>
      {path.startsWith('/search') && <SearchPage key={params.toString()} params={params} />}
      {questionId && <QuestionPage id={questionId} />}
      {(path === '/categories' || categoryId) && <CategoriesPage selected={categoryId} />}
      {path === '/sync' && <SyncPage />}
    </main>
  )
}
