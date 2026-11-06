# Connecting to your internal Stack Overflow site (REST API)

Your site publishes a REST API and documents it with Swagger. QuestionCrawler reads that documentation, finds every endpoint, and uses
them to collect questions and answers the proper, permitted way. Nothing is scraped and nothing is guessed.

## Set it up in the app (Connect screen)

1. Choose **Our internal Q&A site**.
2. **API documentation (Swagger) address**: the link to the Swagger page, for example `https://so.mycompany.com/swagger/index.html`.
3. Press **Read the documentation**. You see every endpoint, grouped by area, with its parameters; which ones QuestionCrawler will use for what
   (list questions, answers, comments, tags, ...); how the API key must be sent; and the suggested API address. Press **Use these settings** to fill them in.
4. **API access key variable name**: the *name* of an environment variable holding your key (for example `INTERNAL_QA_API_KEY`). Set it in the terminal before `npm start`:
   `$env:INTERNAL_QA_API_KEY="your-key"`. The key itself is never saved in a file or shown.
5. Add your tags, press **Check access**, then **Collect**.
6. In **Organize > Discover topics**, let the app find the topics from your questions.

If your company needs a web proxy, see [ORG_PROXY.md](ORG_PROXY.md).

## How the documentation is found
The Swagger *page* only draws the documentation; the data is in a JSON file it references (for Swashbuckle: `/swagger/v1/swagger.json`). QuestionCrawler looks for that
file in the page, in its small script files and at the usual locations. If it cannot, paste the JSON address (click the link under the title in your browser) instead.
Swagger 2.0 and OpenAPI 3 (JSON) are supported; YAML is not.

## Which endpoints are used for what

| Purpose | Endpoint (matched by its documented path) | Needed |
|---|---|---|
| Find questions by tag, date, activity | `GET /questions` (`tagged`, `fromdate`, `min`, `sort`, `order`, `page`, `pagesize`, `filter`) | yes |
| Answers of up to 100 questions per call | `GET /questions/{ids}/answers` | yes |
| One question (notice deletions) | `GET /questions/{ids}` | no |
| Comments on questions / answers | `GET /questions/{ids}/comments`, `GET /answers/{ids}/comments` | no (switch *Also collect comments*) |
| Tag picker and tag size | `GET /tags`, `GET /tags/{tags}/info` | no |
| Include question/answer text | `GET /filters/create` | no |
| Connection check and request allowance | `GET /info` | no |

Other endpoints (users, search, ...) are listed in the explorer but not used. If a required endpoint is missing from the documentation, the Connect screen says so.

## How the documented behaviour is followed
- **API key on every request**: sent the way the documentation says (`key` in the address, or a header), read from your environment variable.
- **Wrapper object** (`items`, `has_more`, `quota_remaining`, `backoff`): paging follows `has_more`; the API's `backoff` is obeyed; an exhausted allowance stops the run cleanly.
- **Compressed responses**: decoded even when a proxy strips the `Content-Encoding` header (the gzip signature is detected).
- **Page size 100 and up to 100 ids per call**: answers and comments are fetched in batches of 100.
- **Dates are Unix epoch seconds**: used for the time window (`fromdate`) and quick updates (`min`).
- **Custom filters**: the standard `withbody` filter is tried first; if the API does not know it, or returns no text, a filter is created with `/filters/create`.
- **Missing features**: if `tagged` or `fromdate` is not documented, tags/dates are applied after download (slower, but correct).

## Topics identified from the context
Topics are discovered from the questions themselves (titles, tags, text, answers): see the *Discover topics* tab. Words that characterize each group name it and become
readable rules you can rename and edit. It runs on this computer, needs no AI service, and gives the same result every time. Needs at least 8 questions; more questions give better topics.

## What is verified and what is not
Tested against a **mock** of such a site (Swashbuckle-style Swagger page, key on every request, unknown `withbody`, header-less gzip, comments). Not yet tested against your real site.
If something does not match: press **Copy summary** on the Connect screen (it contains endpoints and parameters, never keys) and send it, together with the first lines of `logs/backend.log`.
