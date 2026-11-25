/** A plain name for the source an item was collected from (the id is the name given in the settings, or a built-in one). */
export function sourceLabel(id: string | null | undefined): string {
  if (!id) return ''
  if (id === 'searchai-pages') return 'Search-AI (saved page)'
  return id.replace(/[-_]+/g, ' ').replace(/^./, (c) => c.toUpperCase())
}
