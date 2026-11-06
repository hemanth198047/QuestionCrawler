/** Plain-language explanations shown by the little (i) helpers across the app. */
export const GLOSSARY: Record<string, { title: string; text: string }> = {
  tag: { title: 'Tag', text: 'A label that people put on a question, such as "python" or "artificial-intelligence". We collect every question that carries your tags. Type "all" to collect every question on the site, whatever its tags.' },
  window: { title: 'Time window', text: 'Only questions asked within this period are downloaded. A small window is quick and keeps you inside request limits; you can widen it later.' },
  quick: { title: 'Quick update', text: 'Downloads only what is new or changed since the last time. Fast, but it cannot notice questions that were deleted or hidden.' },
  full: { title: 'Full check', text: 'Looks at everything in your window again and confirms each question still exists. Slower, but it keeps your knowledge honest.' },
  accepted: { title: 'Accepted answer', text: 'The person who asked marked this answer as the one that solved their problem. A helpful signal, not a guarantee.' },
  votes: { title: 'Votes', text: 'How many people found a post useful. Popular is not always correct: check the date and read other answers.' },
  confidence: { title: 'Confidence', text: 'A rough guide to how many of your search words the best result actually contains. It is not a probability of being right.' },
  stale: { title: 'Out of date', text: 'The last successful download is older than your limit, so newer answers may exist on the site. Run a Quick update.' },
  category: { title: 'Category', text: 'A topic group such as "Deployment" or "Performance". Questions are placed by simple rules you can see and edit, not by AI guesses.' },
  hidden: { title: 'Hidden', text: 'Questions deleted or made private on the site are removed from search immediately and erased after the retention period.' },
  quota: { title: 'Request allowance', text: 'Public sites limit how many requests anonymous visitors may make per day. A free application key raises the limit.' },
  untrusted: { title: 'Untrusted text', text: 'Everything collected comes from other people. We show it as reading material only and never follow instructions found inside it.' },
}
