# Reaching a site through a company web proxy

Most company networks do not allow direct connections to the internet: traffic must go through a **web proxy**. QuestionCrawler supports this
in two places: the real downloader and the *Check access* screen (which used to try a direct connection and report "connection refused").

## Set it (pick one)

1. **In the app** (Connect screen, step 2, or Settings): fill in **Web proxy address** (for example `webproxy.mycompany.com`) and **Web proxy port** (for example `8080`).
2. **Or with the standard environment variables**, set in the terminal before `npm start`:
   ```powershell
   $env:HTTPS_PROXY="http://webproxy.mycompany.com:8080"
   $env:NO_PROXY="intranet.mycompany.com,.corp.mycompany.com"
   ```
   Settings in the app win over the environment. Local addresses (`localhost`, `127.*`) never use the proxy.
3. In the settings file: `network.proxyHost=...`, `network.proxyPort=...`, `network.noProxy=...`.

## What *Check access* tells you with a proxy

| Message | Meaning | What is needed |
|---|---|---|
| Reachable through your web proxy | Tunnel (HTTP CONNECT) to the site works | nothing |
| We cannot reach your web proxy | Wrong proxy address/port, or you are off the company network | Correct host and port; VPN |
| The proxy refused (HTTP 403) / blocks this site | The proxy's policy blocks the site (often a website category) | IT / network security allows the host |
| The proxy wants you to sign in (HTTP 407) | The proxy needs credentials | See below |
| Security certificate not trusted | Proxies that inspect secure traffic present the company's own certificate | The company root certificate (`.pem`), set as *Company certificate file* |

## If the proxy asks you to sign in (407)
- **User name and password:** set the *names* of two environment variables under the advanced settings (`network.proxyUserEnv`, `network.proxyPasswordEnv`)
  and set those variables in your terminal. The values are never written to a file or shown.
- **Windows sign-in (NTLM/Kerberos):** Java cannot do this by itself. Ask IT for a local helper such as **CNTLM**, then point the proxy address at it (`127.0.0.1` and its port).
- Only HTTP proxies are supported (not SOCKS).

## Gradle and npm need the proxy too
They are separate programs: Gradle reads `systemProp.https.proxyHost` / `systemProp.https.proxyPort` in `~/.gradle/gradle.properties`, and npm reads
`npm config set proxy ...` / `npm config set https-proxy ...` (see [ORG_NPM.md](ORG_NPM.md) and [ORG_GRADLE.md](ORG_GRADLE.md)).
