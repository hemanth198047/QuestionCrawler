/**
 * Plain-language explanations shown by the little (i) helpers across the app. {item}, {items}, {Items} and {created} are
 * filled in with the words of the configured source (page / question / post), see lib/terms.
 */
export const GLOSSARY: Record<string, { title: string; text: string }> = {
  tag: { title: 'Tag', text: 'A label that people put on a {item}, such as "python" or "runbook". We collect every {item} that carries your tags. Type "all" to collect every {item}, whatever its tags.' },
  window: { title: 'Time window', text: 'Only {items} {created} within this period are downloaded. A small window is quick and keeps you inside request limits; you can widen it later.' },
  quick: { title: 'Quick update', text: 'Downloads only what is new or changed since the last time. Fast, but it cannot notice {items} that were deleted or hidden.' },
  full: { title: 'Full check', text: 'Looks at everything in your window again and confirms each {item} still exists. Slower, but it keeps your knowledge honest.' },
  accepted: { title: 'Accepted answer', text: 'The person who asked marked this answer as the one that solved their problem. A helpful signal, not a guarantee.' },
  votes: { title: 'Votes', text: 'How many people found a post useful. Popular is not always correct: check the date and read other answers.' },
  confidence: { title: 'Confidence', text: 'A rough guide to how many of your search words the best result actually contains. It is not a probability of being right.' },
  stale: { title: 'Out of date', text: 'The last successful download is older than your limit, so newer {items} may exist on the site. Run a Quick update.' },
  category: { title: 'Category', text: 'A topic group such as "Deployment" or "Performance". {Items} are placed by simple rules you can see and edit, not by AI guesses.' },
  hidden: { title: 'Hidden', text: '{Items} deleted or made private on the site are removed from search immediately and erased after the retention period.' },
  quota: { title: 'Request allowance', text: 'Public sites limit how many requests anonymous visitors may make per day. A free application key raises the limit.' },
  untrusted: { title: 'Untrusted text', text: 'Everything collected comes from other people. We show it as reading material only and never follow instructions found inside it.' },
}
