# Short links (go/xyz, bit.ly, aka.ms, ...)

QuestionCrawler expands short links in two places. Both are on by default (*Connect → 4. Company network → Expand short links*).

## 1. Short links pasted into the settings

You can paste a short link wherever the Connect screen asks for an address. For example, paste `go/dgf-wiki` as the Confluence
*Site address*, or `https://go.mycompany.com/qa-api` as the internal site's API address.

- **Check access** looks the link up, checks the real address, and puts that address into the form. Save to keep it.
- **Save** stores the real address. A Confluence page or space address it leads to is handled as usual, so the site is kept and
  the space is chosen. A note under the field says where the link led.

## 2. Short links inside collected pages, questions and answers

While collecting, every short link in the text, answers and comments is looked up once:

- `go/vpn` becomes `[go/vpn](https://wiki.mycompany.com/display/IT/VPN+setup)`. The short name stays visible, the link opens the
  real page, and search finds the item by its real address too.
- `[the guide](go/vpn)` now points at the real address.
- Code blocks and inline code are left alone. A link that cannot be looked up stays exactly as it was.
- What was found is remembered (`state/short-links.json` in the data folder), so a link is looked up once, not on every run.
  Items already collected get expanded links the next time they change, or after a full collection.

The pages that short links lead to are **not** collected. Only the sources and spaces you chose are collected.

## Which links count as short links

- These are recognised without any setting: `go/…`, `goto/…`, `golinks/…`, any `go.<company>` or `goto.<company>` host, and
  `bit.ly`, `tinyurl.com`, `aka.ms`, `t.co`, `ow.ly`, `is.gd`, `buff.ly`, `rebrand.ly`, `tiny.cc`, `lnkd.in`, `goo.gl`, `t.ly`,
  `cutt.ly`, `shorturl.at`, `rb.gy`, `short.io` and `bl.ink`.
- To add your own service, list its host in *Your short-link services* (`network.shortLinkHosts`), for example `s.mycompany.com,link`.
  A host without dots, such as `link`, is also recognised when written as `link/xyz` without `http://`.

```properties
network.expandShortLinks=true
network.shortLinkHosts=s.mycompany.com,link
```

## Safety

- The app only asks the short-link service where a link leads, and reads at most 64 KB of its answer. Redirects are read one at
  a time and are not followed automatically.
- **No sign-in, token, key or cookie is sent** to the short-link service, and the real destination is never requested.
- If the service sends you to a sign-in page, the link is left as it is.
- Company short links (`go/…`) are reached directly on the intranet. Public ones go through the web proxy from the Company
  network settings, with your company certificate if one is set.
- If a service cannot be reached, it is not asked again in the same run, so collecting is not slowed down.
