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

## If the documentation asks for a sign-in (HTTP 401, SSO)
Many companies put the Swagger page behind single sign-on, while the API itself is used with an API key. QuestionCrawler cannot do an interactive SSO login
(a browser redirect with your company sign-in or MFA), but there are three ways forward, offered on the Connect screen when this happens:

1. **Upload a saved copy (easiest).** In your browser, where you are already signed in, open the documentation's JSON address (the link under the title of the Swagger page,
   often `/swagger/v1/swagger.json`), save it as `swagger.json` (Ctrl+S), and press **Choose swagger.json**. The copy is kept next to your settings file
   (`api-docs/swagger.json`) and used from then on, with no sign-in. If the file only gives a path such as `/api/2.3`, type your site's address in front of it in **API address**.
2. **Use a token.** If the site owners give you a token or service account for the documentation, enter the *name* of the environment variable holding it. It is sent as a Bearer token.
3. **Ask the site owners.** A ready-made message is one click away (a token, permission to use the API key on the documentation, or the JSON file).

The API key is also tried automatically on the documentation when it is refused. The collection itself needs only the API key, not the documentation: once the saved copy exists, no documentation requests are made.

## When the test call fails: "The API did not answer with JSON"
The API must answer with JSON. When it answers with something else, the error now says **what** it is, and the Connect screen has a card,
**See what the API really answers**, that makes one real call (default `/info`) and shows the raw answer: HTTP status, content type, the start of the body,
and what to check. Your key is never shown (it appears as `•••`). **Copy this report** gives you something safe to send to the site owners or to me.

| What came back | What it usually means | What to do |
|---|---|---|
| A **sign-in page** (title such as "Sign in") | The key was not sent or not accepted, or the address points at the website instead of the API, or the whole site is behind SSO | Check the key variable is set in the terminal that started the app; check the key parameter name and where it is sent; check the API address ends with the API path (for example `/api/2.3`); if SSO protects the API too, ask for a token or an API gateway address |
| A **redirect** (HTTP 301/302 to another address) | Often a redirect to a sign-in address, or http instead of https | Same as above; the address it redirects to is shown (without secrets) |
| The **documentation page** (Swagger UI) | The API address is the documentation address | Use the `servers` address from the Swagger document, for example `https://your-site/api/2.3` |
| A **proxy or firewall block page** | Your proxy blocked the request | Check proxy settings, or ask IT to allow the address |
| A **not found** page | Wrong path in front of `/questions` | Compare with the `servers` entry of the documentation |
| **Empty** | A wrong address or a gateway that swallows the request | Check the address and proxy |
| **"Missing bearer token"** (HTTP 401, often with a `WWW-Authenticate: Bearer` header) | The API also wants a token in an `Authorization: Bearer <token>` header, in addition to the API key | See "A Bearer token" below |
| JSON with an **error message** (HTTP 400/401/403) | The API itself refused: the message names the problem (for example `key_required`) | Fix what the message says |

Responses with a byte-order mark or a security prefix such as `)]}',` are cleaned automatically and are not an error.

## Collecting every tag ("all")
Type the tag **`all`** (or `*`) to collect **every question on the site, whatever its tags**. It works in the settings, on the Connect screen (**Collect every tag**) and on the command line (`--tag all`).

- It is **not** done tag by tag: the site is asked for questions *without* a tag filter. That is faster, uses far fewer requests, and downloads each question once, even though a question has several tags.
- If you type `all` together with other tags, `all` wins (the other tags would add nothing).
- **Find tags on the site → Show all tags** reads **every page** of the site's tag list and shows all of them in a scrollable window, with their question counts and (when the site says) how many questions it holds in total. Use the filter box to narrow the list, switch between *Most used* and *A–Z*, click a tag to add it, or **Copy list** to take the whole list (name and count) elsewhere. The window draws only the rows in view, so thousands of tags stay smooth. Reading stops (and the screen says so) after 500 pages or two minutes, or if a later page fails; whatever was read is kept.
- *Check access* ("Your topic") says how many questions the whole site holds, and advises a time window when that is large (over 20,000). **Start with a time window** such as 1 year, then widen it.
- On a **public Stack Exchange site** (for example stackoverflow.com) `all` is refused unless you choose a time window: it would be millions of questions and would use up the request allowance.
- A real tag literally named "all" cannot be requested by that name (it means every tag); use the site's tag finder to see if one exists.

## "The site has no tag named ..." (finding the exact tag name)
The tag you typed may exist on the site under a slightly different name (capitals such as `CASC`, hyphens, another spelling), or the site's tag-info endpoint may answer differently from the standard one.
The check no longer relies on one lookup. It tries, in order: the tag's own info (as typed and in other spellings), the site's **tag list** (searching by a few letters), and finally a real
**question search** by that tag (what a collection does). It reports how it was found and the **exact name the site uses**.

- **Connect screen: Find tags on the site.** Type a few letters (`casc`), and click the tag you mean. You see the site's own spelling and the number of questions of each tag.
- **Collecting uses the site's spelling automatically.** If asking for `casc` returns nothing, the app looks up how the site writes it (`CASC`) and asks again, so you can type the tag either way.
- **When nothing matches**, the *Your topic* step lists similar tags on the site instead of just saying "no such tag".
- If the tag list itself is not readable (an endpoint the site does not offer), the question search is still tried; if that also finds nothing, check the spelling with the site owners and whether your key can see questions with that tag.

## "The site sends visitors to a sign-in page" (single sign-on)
A program cannot complete a browser sign-in (redirects, an SSO page, MFA), and this app deliberately does not try to get around one. The sign-in step and the raw-answer card
name the system they end at (Microsoft Entra ID / Azure AD, AD FS, Okta, Keycloak, Auth0, Ping, SAML, ... ) and say what a program needs from it.

**First, which case is it?**
- **Only the website redirects, the API does not.** Many sites put the *web pages* behind SSO while the REST API accepts an access key. If the real test call with your key succeeds
  (Check access, step "Way to get the data": *Test call succeeded*), the Sign-in step now reports **PASS** and you need nothing more.
- **The API calls themselves end at the sign-in page** (the raw-answer card shows the redirect chain and a sign-in page). The API will not talk to your program without a credential it accepts.

**What you need (ask the site owners or your IT identity team; read-only is enough):**
1. A **personal access token** from your profile on the site (look under "API tokens" or "Personal access tokens"; the name varies), **or a service account** with a token. This is the simplest and what the app supports today (*Bearer token sign-in*).
2. The **address of the API** that accepts it. It is often not the web address people use: an API gateway, or an `/api` path.
3. Whether the **API access key** is also needed (then both are sent), and how the token is sent (usually `Authorization: Bearer <token>`).
4. If your company uses **OAuth client credentials** (an app registration / service app): the client ID, client secret, token address and scope. The app does not request such tokens by itself yet; if this is what you get, say so and it can be added.

**How to get it:** the card on the Connect screen has **Copy message for IT** with the redirect chain and exactly what to ask for. Send it to the site owners or your identity team.

**Then:** set the token in an environment variable in the terminal that starts the app, choose *Bearer token sign-in* and name the variable (see "A Bearer token" below), and run *Check access* again.

**Not supported on purpose:** copying a browser session or cookie, typing a password into a form, bypassing MFA. They break within hours, and bypassing a company's sign-in is not what a data collection tool should do.

## Redirects (HTTP 301 / 302 / 307 / 308)
Internal sites and API gateways redirect routinely (http to https, a trailing slash, a gateway path, another host). QuestionCrawler **follows redirects by default**, in the
connection check (the *Sign-in* step), in the raw-answer card and in the real collection. What you get:

- Up to **5 redirects in a row**; every hop is shown (without query strings or secrets), for example `307 to https://gateway.corp.com/api/2.3/info`.
- When the API address redirects to another place, that final place is **used directly from then on** (no repeated redirect on every call).
- If a redirect on the same host leaves out the query string (where the API key lives), the original one is carried over.
- The chain can end at a **sign-in page** (single sign-on): the *Sign-in* step then reports "sends visitors to a sign-in page" and what is needed, instead of an unclear redirect message.
- If a redirect leads to an address this computer cannot reach, the message says which redirect led there.

Safety rules that stay on: a redirect **from https down to plain http is never followed**; redirects in a circle stop; credentials (the key and token) **are sent along** to wherever a followed redirect leads,
so if you do not trust a redirect target, set **Follow redirects** (Connect screen, step 2) to **Same host only** or **No**.

## A Bearer token (HTTP 401 "Missing bearer token")
Some sites require both the API key **and** a token sent as `Authorization: Bearer <token>`. When the answer says so, the *See what the API really answers* card shows
**"This API wants a Bearer token"** with the fix:

1. **Get a token.** Look in your profile settings on the site for **API tokens** or **Personal access tokens** (the name varies). If you cannot find one, ask the site owners for a token or a service account.
2. **Put it in an environment variable** in the terminal that starts the app: `$env:INTERNAL_QA_TOKEN="your-token"` (PowerShell) and start the app from that same terminal. The token is never saved to a file or shown.
3. On the Connect screen set **Bearer token sign-in** to *Bearer token* and enter the variable's **name** (for example `INTERNAL_QA_TOKEN`), or type the name into the card and press **Use it and ask again**.
4. If it then says "A Bearer token WAS sent but was not accepted", the token is expired, for another site, or lacks permission: create a new one.

If the Swagger documentation lists a Bearer scheme, *Read the documentation* shows "Bearer token required" and **Use these settings** switches Bearer sign-in on.
The same token is used for the documentation, the connection check and the collection. If your gateway expects another header name than `Authorization`, change *Bearer header name* under advanced settings.
A server that asks for **Windows sign-in** (NTLM/Kerberos) cannot be used directly: ask for a token.

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
- **Page size and up to 100 ids per call**: ids are sent in batches of up to 100. The page size is 100 unless the API only allows certain sizes: it is taken from the Swagger document when that lists them, otherwise learned from the API's own error (for example `Invalid page size, use 15, 30, 50 or 100`) and remembered for the rest of the run, with no setting to change.
- **Dates are Unix epoch seconds**: used for the time window (`fromdate`) and quick updates (`min`).
- **Custom filters**: the standard `withbody` filter is tried first; if the API does not know it, or returns no text, a filter is created with `/filters/create`.
- **Missing features**: if `tagged` or `fromdate` is not documented, tags/dates are applied after download (slower, but correct).

## Topics identified from the context
Topics are discovered from the questions themselves (titles, tags, text, answers): see the *Discover topics* tab. Words that characterize each group name it and become
readable rules you can rename and edit. It runs on this computer, needs no AI service, and gives the same result every time. Needs at least 8 questions; more questions give better topics.

## What is verified and what is not
Tested against a **mock** of such a site (Swashbuckle-style Swagger page, key on every request, unknown `withbody`, header-less gzip, comments). Not yet tested against your real site.
If something does not match: press **Copy summary** on the Connect screen (it contains endpoints and parameters, never keys) and send it, together with the first lines of `logs/backend.log`.
