# Answers from pages (Confluence, Jive, SharePoint)

Wiki and intranet pages don't contain questions and answers. They contain paragraphs, steps, tables and headings, and the
answer to a question is usually one paragraph or section of one page. QuestionCrawler finds that paragraph and shows it as
the answer, **without an AI model**.

## What you see in Explore

Ask the way you'd ask a colleague, for example *"how do I reset my VPN token"*, *"how long are audit logs kept"* or
*"who do I contact about VPN problems"*.

1. **Best answer from your pages**: the sentence(s), steps, table or code block that answer the question, **quoted word for
   word**, with the page and section it comes from (*VPN guide › Resetting your VPN token*). It's labelled *Clear*,
   *Likely* or *Possible*. "Open original at this section" opens the real page and, in Edge or Chrome, scrolls to and
   highlights that section.
2. **Where it comes from**: the best passages, one per page, each showing its section (`§ Troubleshooting › Slow connection`).
   Clicking a title opens the page in the app, scrolled to that section, with an *On this page* outline.
3. When nothing really answers the question, it says **"No page clearly answers this"** instead of guessing.

## How it chooses (no AI)

- Pages are searched **section by section**, so one useful paragraph isn't drowned out by the rest of a long page.
  Headings count extra.
- Related word forms and common synonyms match (configure/configuring, runbook/playbook).
- The kind of question matters:

| Question starts with… | Preferred answer |
|---|---|
| how do I / how to / steps | numbered steps or a list, instructions ("Open…", "Click…") |
| what is / what does / define | a sentence that defines it ("X is…", "X means…") |
| when / how long / how many / limits | a sentence with the number, date or duration |
| who / contact / owner | a sentence with a contact, team, mailbox or channel |
| where / link | a link, path or location |
| why | a sentence giving the reason ("because…", "so that…") |

- Nothing is rephrased or combined: every word shown is from your pages, with its link. Check the source before relying on it.

## Command line and Copilot

`questioncrawler search "how do I reset my VPN token"` prints **BEST ANSWER FROM YOUR PAGES** first, then the passages
with their sections. In JSON (`--format json`) the answer is in `answer.points[]` (`text`, `title`, `section`,
`sectionUrl`), and each passage has `section` and `sectionUrl`. The Copilot skill quotes those and cites the section links.

## After upgrading

The first search or collection after the upgrade rebuilds the search index once from the pages already stored
(nothing is downloaded again). With many pages this can take a minute. You can also start it yourself:
Settings → **Rebuild search index**, or `questioncrawler reindex`.
