#!/usr/bin/env node
// One command to build and run everything:  npm start   (starts via scripts/launch.cjs, which checks the Node version first)
//   npm start               backend + frontend in development mode, opens the browser
//   npm start -- --prod     builds the UI and serves everything from one server (http://localhost:8080)
//   npm start -- --no-open  do not open a browser
//   npm start -- --test     run all backend and frontend checks, then exit
//   npm start -- --check    only check prerequisites
// No dependencies: plain Node 20+.
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const BACKEND = path.join(ROOT, 'backend')
const FRONTEND = path.join(ROOT, 'frontend')
const WIN = process.platform === 'win32'
const args = new Set(process.argv.slice(2))
const PREFERRED_API_PORT = Number(process.env.QC_API_PORT ?? 8080)
const PREFERRED_UI_PORT = Number(process.env.QC_UI_PORT ?? 5173)
let API_PORT = PREFERRED_API_PORT // the ports actually used: chosen in serve() if the preferred ones are busy
let UI_PORT = PREFERRED_UI_PORT
const PROD = args.has('--prod')

const useColor = process.stdout.isTTY && !process.env.NO_COLOR
const c = (code, s) => (useColor ? `\x1b[${code}m${s}\x1b[0m` : s)
const bold = (s) => c('1', s), dim = (s) => c('2', s), green = (s) => c('32', s), red = (s) => c('31', s), yellow = (s) => c('33', s), blue = (s) => c('34', s), magenta = (s) => c('35', s)
const say = (s = '') => console.log(s)
const step = (s) => say(`\n${bold(blue('▸'))} ${bold(s)}`)
const ok = (s) => say(`  ${green('✓')} ${s}`)
const warn = (s) => say(`  ${yellow('!')} ${s}`)
function fail(title, help) {
  say(`\n${red('✗')} ${bold(title)}`)
  for (const line of [].concat(help ?? [])) say(`  ${line}`)
  process.exit(1)
}

// ------------------------------------------------------------------ helpers
const children = []
function run(cmd, cmdArgs, opts = {}) {
  return new Promise((resolve) => {
    const p = spawn(cmd, cmdArgs, { cwd: opts.cwd ?? ROOT, shell: WIN || opts.shell, stdio: opts.quiet ? 'ignore' : 'inherit', env: { ...process.env, ...opts.env } })
    p.on('error', () => resolve(127))
    p.on('close', (code) => resolve(code ?? 1))
  })
}
/** Runs a command, shows its output live, and also returns it so errors can be explained. */
function runTee(cmd, cmdArgs, opts = {}) {
  return new Promise((resolve) => {
    let out = ''
    const p = spawn(cmd, cmdArgs, { cwd: opts.cwd ?? ROOT, shell: WIN, env: { ...process.env, ...opts.env } })
    const take = (stream, sink) => stream.on('data', (d) => { sink.write(d); out = (out + d.toString()).slice(-40000) })
    take(p.stdout, process.stdout); take(p.stderr, process.stderr)
    p.on('error', () => resolve({ code: 127, out }))
    p.on('close', (code) => resolve({ code: code ?? 1, out }))
  })
}
function capture(cmd, cmdArgs) {
  const r = spawnSync(cmd, cmdArgs, { encoding: 'utf8', shell: WIN })
  return { code: r.status ?? 127, out: `${r.stdout ?? ''}${r.stderr ?? ''}` }
}
function startService(label, color, cmd, cmdArgs, opts) {
  const p = spawn(cmd, cmdArgs, { cwd: opts.cwd, shell: WIN || opts.shell, detached: !WIN, env: { ...process.env, ...opts.env } })
  children.push(p)
  const tag = color(label.padEnd(4))
  const pipe = (stream) => {
    let buf = ''
    stream.on('data', (d) => {
      buf += d.toString()
      const lines = buf.split(/\r?\n/)
      buf = lines.pop() ?? ''
      for (const line of lines) if (line.trim()) console.log(`${tag} ${dim('│')} ${line}`)
    })
  }
  pipe(p.stdout); pipe(p.stderr)
  p.on('close', (code) => { if (!shuttingDown) { say(`\n${red('✗')} ${label} stopped unexpectedly (exit code ${code}).`); shutdown(1) } })
  return p
}
let shuttingDown = false
function killTree(p) {
  if (!p || p.exitCode !== null) return
  try {
    if (WIN) spawnSync('taskkill', ['/pid', String(p.pid), '/T', '/F'], { stdio: 'ignore' })
    else process.kill(-p.pid, 'SIGTERM')
  } catch { /* already gone */ }
}
function shutdown(code = 0) {
  if (shuttingDown) return
  shuttingDown = true
  say(`\n${dim('Stopping…')}`)
  children.forEach(killTree)
  setTimeout(() => process.exit(code), 600)
}
process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))

const canListen = (port, host) => new Promise((resolve) => {
  const s = net.createServer().once('error', () => resolve(false)).once('listening', () => s.close(() => resolve(true)))
  host ? s.listen(port, host) : s.listen(port)
})
/** Free only if nothing holds it on loopback or on any interface (Windows allows some overlaps that later fail). */
const portFree = async (port) => (await canListen(port, '127.0.0.1')) && (await canListen(port))
/** The preferred port if free, otherwise the next free one. */
async function pickPort(preferred, label, avoid = []) {
  for (let p = preferred; p < preferred + 200 && p < 65536; p++) {
    if (avoid.includes(p)) continue
    if (await portFree(p)) {
      if (p !== preferred) warn(`Port ${preferred} is already in use, so the ${label} will use port ${p} instead.`)
      return p
    }
  }
  return fail(`No free port found near ${preferred}`, ['Close some programs, or set QC_API_PORT / QC_UI_PORT to a free port range.'])
}
const httpOk = (url) => new Promise((resolve) => {
  const req = http.get(url, { timeout: 2500 }, (res) => { res.resume(); resolve(res.statusCode !== undefined && res.statusCode < 500) })
  req.on('error', () => resolve(false)); req.on('timeout', () => { req.destroy(); resolve(false) })
})
async function waitFor(label, url, seconds, isAlive) {
  const start = Date.now()
  let tick = 0
  while ((Date.now() - start) / 1000 < seconds) {
    if (await httpOk(url)) return true
    if (isAlive && !isAlive()) return false
    if (++tick % 10 === 0) say(`  ${dim(`still waiting for ${label}… ${Math.round((Date.now() - start) / 1000)}s`)}`)
    await new Promise((r) => setTimeout(r, 1000))
  }
  return false
}
function openBrowser(url) {
  try {
    if (WIN) spawn('cmd', ['/c', 'start', '', url], { stdio: 'ignore', detached: true }).unref()
    else spawn(process.platform === 'darwin' ? 'open' : 'xdg-open', [url], { stdio: 'ignore', detached: true }).on('error', () => undefined).unref()
  } catch { /* the URL is printed anyway */ }
}

// ------------------------------------------------------------------ 1. prerequisites
async function prerequisites() {
  step('Checking your computer')
  const nodeMajor = Number(process.versions.node.split('.')[0])
  if (nodeMajor < 20) fail(`Node.js ${process.versions.node} is too old`, ['Install Node.js 20 or newer: https://nodejs.org', 'Then run this command again.'])
  ok(`Node.js ${process.versions.node}`)

  const java = capture('java', ['-version'])
  const m = /version "(\d+)/.exec(java.out)
  if (java.code !== 0 || !m) fail('Java was not found', ['Install JDK 21 (for example Eclipse Temurin): https://adoptium.net', 'Windows:  winget install EclipseAdoptium.Temurin.21.JDK', 'Then open a NEW terminal and run this command again.'])
  if (Number(m[1]) < 21) fail(`Java ${m[1]} is too old`, ['QuestionCrawler needs JDK 21 or newer.', 'Install it, make sure JAVA_HOME points to it, open a NEW terminal and try again.'])
  ok(`Java ${m[1]}`)

  if (!existsSync(path.join(ROOT, 'frontend', 'package.json')) || !existsSync(path.join(BACKEND, 'settings.gradle'))) fail('This does not look like the QuestionCrawler folder', [`Run the command from: ${ROOT}`])
}

// ------------------------------------------------------------------ 2. prepare
const wrapperPath = () => (WIN ? path.join(BACKEND, 'gradlew.bat') : path.join(BACKEND, 'gradlew'))
const extraGradleArgs = () => (process.env.QC_GRADLE_ARGS ?? '').split(/\s+/).filter(Boolean)
const quote = (p) => (WIN && /\s/.test(p) ? `"${p}"` : p)
let GRADLE = null // { cmd, source, version }

/** Finds the Gradle to use. The organization's own Gradle is fine: no wrapper and no download is required. */
function findGradle() {
  const candidates = []
  if (process.env.QC_GRADLE) candidates.push({ cmd: process.env.QC_GRADLE, source: 'the QC_GRADLE setting' })
  if (existsSync(wrapperPath())) candidates.push({ cmd: wrapperPath(), source: 'the Gradle wrapper in backend/' })
  candidates.push({ cmd: 'gradle', source: 'Gradle on your PATH' })
  if (process.env.GRADLE_HOME) candidates.push({ cmd: path.join(process.env.GRADLE_HOME, 'bin', WIN ? 'gradle.bat' : 'gradle'), source: 'GRADLE_HOME' })
  for (const c of candidates) {
    const r = capture(quote(c.cmd), ['-v'])
    const m = /Gradle (\d+)\.(\d+)/.exec(r.out)
    if (r.code === 0 && m) return { ...c, version: `${m[1]}.${m[2]}`, major: Number(m[1]), minor: Number(m[2]) }
  }
  return null
}

async function prepareBackend() {
  step('Preparing the backend')
  GRADLE = findGradle()
  if (!GRADLE) {
    fail('Gradle was not found', [
      'QuestionCrawler builds its backend with Gradle 8.5 or newer. Use the Gradle your organization provides:',
      '',
      '  1. If it is installed, put its "bin" folder on your PATH, open a NEW terminal, and check:  gradle -v',
      '  2. Or tell the launcher where it is (the full path to gradle / gradle.bat):',
      WIN ? '       $env:QC_GRADLE="C:\\tools\\gradle-8.14\\bin\\gradle.bat"; npm start' : '       QC_GRADLE=/opt/gradle-8.14/bin/gradle npm start',
      '  3. Or set GRADLE_HOME to the Gradle folder.',
      '',
      'No Gradle wrapper is needed. If dependencies cannot be downloaded on your network, see docs/ORG_GRADLE.md.'])
  }
  if (GRADLE.major < 8 || (GRADLE.major === 8 && GRADLE.minor < 5)) {
    fail(`Gradle ${GRADLE.version} is too old`, ['Java 21 needs Gradle 8.5 or newer.', `Found via ${GRADLE.source}. Ask for a newer Gradle, or point QC_GRADLE at one.`])
  }
  ok(`Gradle ${GRADLE.version} (${GRADLE.source})`)
}

/** Names of declared dependencies that are not installed yet (an old node_modules from an earlier version is common). */
function missingPackages() {
  try {
    const pkg = JSON.parse(readFileSync(path.join(FRONTEND, 'package.json'), 'utf8'))
    return Object.keys({ ...pkg.dependencies, ...pkg.devDependencies })
      .filter((name) => !existsSync(path.join(FRONTEND, 'node_modules', ...name.split('/'), 'package.json')))
  } catch { return ['(package.json unreadable)'] }
}

/** Turns an npm failure into plain steps. */
function npmHelp(out, registry) {
  const lines = []
  if (/E403|\b403\b|Forbidden/i.test(out)) {
    lines.push('npm was refused (HTTP 403 Forbidden) by the registry it is using:', `    ${registry}`, '',
      'This usually means one of these:',
      '  1. Your company blocks the public npm registry and expects you to use its internal one.',
      '     Ask your platform team for the internal npm registry URL, then run (use your URL):',
      WIN ? '       $env:QC_NPM_REGISTRY="https://npm.mycompany.com/"; npm start' : '       QC_NPM_REGISTRY=https://npm.mycompany.com/ npm start',
      '     To make it permanent for all projects:   npm config set registry https://npm.mycompany.com/',
      '  2. The internal registry needs a login/token. Get an access token from your platform team and add it to YOUR user file',
      '     (not the repository):  ' + (WIN ? '%USERPROFILE%\\.npmrc' : '~/.npmrc'),
      '       //npm.mycompany.com/:_authToken=YOUR_TOKEN',
      '  3. Only one package is blocked by a security policy (the error above names it). Ask for it to be allowed.',
      '', 'Full steps: docs/ORG_NPM.md')
  } else if (/E401|\b401\b|Unauthorized/i.test(out)) {
    lines.push('The npm registry asked for a login (HTTP 401).', 'Add your access token to your user .npmrc (not the repository). See docs/ORG_NPM.md.')
  } else if (/SELF_SIGNED|UNABLE_TO_VERIFY|CERT_|certificate/i.test(out)) {
    lines.push('npm does not trust your company\'s security certificate.', 'Ask IT for the company root certificate (.pem), then run:   npm config set cafile C:\\path\\company-ca.pem', 'Full steps: docs/ORG_NPM.md')
  } else if (/ENOTFOUND|ETIMEDOUT|ECONNREFUSED|EAI_AGAIN|ECONNRESET|proxy/i.test(out)) {
    lines.push('npm could not reach the registry (a network or proxy problem).', 'If your company uses a proxy:   npm config set proxy http://proxy.mycompany.com:8080   and   npm config set https-proxy http://proxy.mycompany.com:8080', 'Full steps: docs/ORG_NPM.md')
  } else {
    lines.push('Read the npm error above. If it mentions a registry, proxy or certificate, see docs/ORG_NPM.md.')
  }
  lines.push('', 'To retry only this step:  cd frontend && npm install')
  return lines
}

async function prepareFrontend() {
  step('Preparing the frontend')
  const missing = existsSync(path.join(FRONTEND, 'node_modules')) ? missingPackages() : ['everything (first run)']
  if (missing.length > 0) {
    const registry = process.env.QC_NPM_REGISTRY || capture('npm', ['config', 'get', 'registry']).out.trim().split(/\r?\n/).pop()
    say(`  ${dim(`Installing packages: ${missing.slice(0, 4).join(', ')}${missing.length > 4 ? '…' : ''}`)}`)
    say(`  ${dim(`npm registry: ${registry}`)}`)
    const npmArgs = ['install', '--no-fund', '--no-audit']
    if (process.env.QC_NPM_REGISTRY) npmArgs.push('--registry', process.env.QC_NPM_REGISTRY)
    const r = await runTee('npm', npmArgs, { cwd: FRONTEND })
    if (r.code !== 0) fail('Installing the frontend packages failed', npmHelp(r.out, registry))
    const still = missingPackages()
    if (still.length > 0) fail('Some frontend packages are still missing', [still.join(', '), 'Try:  cd frontend && npm install'])
  }
  ok('Frontend packages ready')
}

// ------------------------------------------------------------------ 3. modes
async function testMode() {
  step('Backend tests'); let code = await run(quote(GRADLE.cmd), ['test', ...extraGradleArgs()], { cwd: BACKEND })
  if (code !== 0) fail('Backend tests failed', ['Scroll up for the failing test.'])
  ok('Backend tests passed')
  step('Frontend build and checks'); code = await run('npm', ['run', 'build'], { cwd: FRONTEND })
  if (code === 0) code = await run('npm', ['run', 'lint'], { cwd: FRONTEND })
  if (code !== 0) fail('Frontend checks failed')
  ok('Frontend build and lint passed')
  say(`\n${green('All checks passed.')}`)
}

async function serve() {
  step('Choosing ports')
  API_PORT = await pickPort(PREFERRED_API_PORT, 'backend')
  UI_PORT = PROD ? API_PORT : await pickPort(PREFERRED_UI_PORT, 'interface', [API_PORT])
  ok(`Backend port ${API_PORT}${PROD ? '' : `, interface port ${UI_PORT}`}`)
  if (PROD) {
    step('Building the user interface')
    const code = await run('npm', ['run', 'build'], { cwd: FRONTEND })
    if (code !== 0) fail('The frontend build failed', ['Scroll up for the error.'])
    ok('Interface built')
  }
  step('Starting')
  say(`  ${dim('The first start downloads and compiles the backend: this can take a few minutes. Later starts take seconds.')}`)
  const backendCmd = process.env.QC_BACKEND_CMD
  const api = backendCmd
    ? startService('api', magenta, backendCmd, [], { cwd: ROOT, shell: true, env: { PORT: String(API_PORT) } })
    : startService('api', magenta, quote(GRADLE.cmd), [':app:bootRun', `--args=--server.port=${API_PORT}`, '--console=plain', ...extraGradleArgs()], { cwd: BACKEND })
  let ui = null
  if (!PROD) {
    const uiCmd = process.env.QC_FRONTEND_CMD
    ui = uiCmd
      ? startService('ui', blue, uiCmd, [], { cwd: ROOT, shell: true, env: { PORT: String(UI_PORT) } })
      : startService('ui', blue, 'npm', ['run', 'dev', '--', '--port', String(UI_PORT), '--strictPort', '--host', '127.0.0.1'], { cwd: FRONTEND, env: { VITE_BACKEND_URL: `http://127.0.0.1:${API_PORT}` } })
  }
  const apiUp = await waitFor('the backend', `http://127.0.0.1:${API_PORT}/api/health`, Number(process.env.QC_WAIT_SECONDS ?? 420), () => api.exitCode === null)
  if (!apiUp) { shutdown(1); return fail('The backend did not start', ['Scroll up for the error from the [api] lines. A common cause is a Gradle download blocked by a proxy.']) }
  ok(`Backend is up at http://127.0.0.1:${API_PORT}`)
  if (ui) {
    const uiUp = await waitFor('the interface', `http://127.0.0.1:${UI_PORT}/`, 90, () => ui.exitCode === null)
    if (!uiUp) { shutdown(1); return fail('The interface did not start', ['Scroll up for the error from the [ui] lines.']) }
    ok(`Interface is up at http://localhost:${UI_PORT}`)
  }
  const url = PROD ? `http://localhost:${API_PORT}/` : `http://localhost:${UI_PORT}/`
  say(`\n${green('●')} ${bold('QuestionCrawler is running')}   ${bold(url)}`)
  say(`  ${dim('Press Ctrl+C to stop both.')}`)
  if (!args.has('--no-open')) openBrowser(url)
}

// ------------------------------------------------------------------ main
say(bold('QuestionCrawler'))
if (!process.env.QC_BACKEND_CMD) await prerequisites()
if (args.has('--check')) { say(`\n${green('Everything needed is installed.')}`); process.exit(0) }
if (!process.env.QC_BACKEND_CMD) await prepareBackend()
if (!process.env.QC_FRONTEND_CMD) await prepareFrontend()
if (args.has('--test')) await testMode()
else await serve()
