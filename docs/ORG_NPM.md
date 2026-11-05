# npm behind a company network (403 Forbidden, login, proxy, certificates)

`npm start` installs the frontend packages with npm. In a company network that can fail in a few well-known ways. The launcher prints
which registry npm is using and explains the failure; this page has the full steps.

## First, see what npm is doing

```powershell
npm config get registry      # which registry npm uses (https://registry.npmjs.org/ = the public one)
npm ping                     # can it reach that registry?
npm view react version       # can it read a package?
```

## 403 Forbidden: the three usual causes

### 1. The public registry is blocked; your company has its own
Ask your platform team for the **internal npm registry URL** (often Artifactory or Nexus, such as `https://artifactory.mycompany.com/artifactory/api/npm/npm-virtual/`).
Hints for finding it yourself: another project on your machine may have a `.npmrc` with it, or the Artifactory/Nexus web page has a "Set Me Up" / "Connect" button.

Use it for one run:
```powershell
$env:QC_NPM_REGISTRY="https://artifactory.mycompany.com/artifactory/api/npm/npm-virtual/"; npm start
```
or for all projects (recommended, stored in your user file, not in the repository):
```powershell
npm config set registry https://artifactory.mycompany.com/artifactory/api/npm/npm-virtual/
```

### 2. The internal registry needs you to log in
Get an **access token** (or use `npm login --registry=<url>`). Put it in your **user** `.npmrc` (`%USERPROFILE%\.npmrc`, or `~/.npmrc` on Mac/Linux), never in the repository:
```ini
registry=https://artifactory.mycompany.com/artifactory/api/npm/npm-virtual/
//artifactory.mycompany.com/artifactory/api/npm/npm-virtual/:_authToken=YOUR_TOKEN
```
To keep the token out of the file, read it from an environment variable: `...:_authToken=${NPM_TOKEN}` and set `NPM_TOKEN` in your terminal.
Some Artifactory setups want `_auth` (base64 of `user:password`) plus `email=` instead; your platform team will know.

### 3. One specific package or version is blocked by a security policy
The error names it (for example `lucide-react-1.68.0.tgz`). Security tools such as Xray or Nexus Firewall often block **very new versions** for a
few days, or packages with known issues. Either ask for it to be allowed, or pin an older allowed version in `frontend/package.json` and run
`cd frontend && npm install`. If you tell me which package and version is blocked (and an older version that is allowed), I can pin it for you.

## Other npm problems
| Message | Fix |
|---|---|
| `ENOTFOUND`, `ETIMEDOUT`, `ECONNRESET` | A proxy is probably needed: `npm config set proxy http://proxy.mycompany.com:8080` and `npm config set https-proxy http://proxy.mycompany.com:8080` |
| `SELF_SIGNED_CERT_IN_CHAIN`, `UNABLE_TO_VERIFY_LEAF_SIGNATURE` | npm does not trust your company certificate. Get the root certificate (.pem) from IT: `npm config set cafile C:\certs\company-ca.pem`. Do not turn certificate checking off. |
| `E401 Unauthorized` | Missing or expired token: see "needs you to log in" above. |

## Notes
- The lock file lists the public registry address; npm automatically uses your configured registry instead (setting `replace-registry-host`).
  If it does not, run `cd frontend && npm install --replace-registry-host=always`.
- After a successful `npm install` once, `npm start` will not need the network for npm again.
- Gradle (the backend) has its own repository settings: see [ORG_GRADLE.md](ORG_GRADLE.md).
