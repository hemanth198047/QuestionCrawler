#!/usr/bin/env node
// One command to build and run everything:  npm start
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
const API_PORT = Number(process.env.QC_API_PORT ?? 8080)
const UI_PORT = Number(process.env.QC_UI_PORT ?? 5173)
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

const portFree = (port) => new Promise((resolve) => {
  const s = net.createServer().once('error', () => resolve(false)).once('listening', () => s.close(() => resolve(true)))
  s.listen(port, '127.0.0.1')
})
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
const gradlew = () => (WIN ? path.join(BACKEND, 'gradlew.bat') : path.join(BACKEND, 'gradlew'))

async function prepareBackend() {
  step('Preparing the backend')
  if (!existsSync(gradlew())) {
    warn('The Gradle wrapper is missing (one-time setup).')
    const g = capture('gradle', ['-v'])
    if (g.code !== 0) fail('Gradle is needed once, to create the wrapper', ['Install Gradle: https://gradle.org/install  (Windows: choco install gradle, or scoop install gradle)', 'Then run this command again. After that Gradle is no longer needed.'])
    const code = await run('gradle', ['wrapper', '--gradle-version', '8.14'], { cwd: BACKEND })
    if (code !== 0 || !existsSync(gradlew())) fail('Could not create the Gradle wrapper', ['See the message above. On a company network you may need to set a proxy or an internal Maven mirror.'])
    ok('Gradle wrapper created')
  } else ok('Gradle wrapper present')
}

/** Names of declared dependencies that are not installed yet (an old node_modules from an earlier version is common). */
function missingPackages() {
  try {
    const pkg = JSON.parse(readFileSync(path.join(FRONTEND, 'package.json'), 'utf8'))
    return Object.keys({ ...pkg.dependencies, ...pkg.devDependencies })
      .filter((name) => !existsSync(path.join(FRONTEND, 'node_modules', ...name.split('/'), 'package.json')))
  } catch { return ['(package.json unreadable)'] }
}

async function prepareFrontend() {
  step('Preparing the frontend')
  const missing = existsSync(path.join(FRONTEND, 'node_modules')) ? missingPackages() : ['everything (first run)']
  if (missing.length > 0) {
    say(`  ${dim(`Installing packages: ${missing.slice(0, 4).join(', ')}${missing.length > 4 ? '…' : ''}`)}`)
    const code = await run('npm', ['install', '--no-fund', '--no-audit'], { cwd: FRONTEND })
    if (code !== 0) fail('Installing the frontend packages failed', ['Check your internet connection or npm proxy settings, then try again.',
      'You can also run it yourself:  cd frontend && npm install'])
    const still = missingPackages()
    if (still.length > 0) fail('Some frontend packages are still missing', [still.join(', '), 'Try:  cd frontend && npm install'])
  }
  ok('Frontend packages ready')
}

// ------------------------------------------------------------------ 3. modes
async function testMode() {
  step('Backend tests'); let code = await run(gradlew(), ['test'], { cwd: BACKEND })
  if (code !== 0) fail('Backend tests failed', ['Scroll up for the failing test.'])
  ok('Backend tests passed')
  step('Frontend build and checks'); code = await run('npm', ['run', 'build'], { cwd: FRONTEND })
  if (code === 0) code = await run('npm', ['run', 'lint'], { cwd: FRONTEND })
  if (code !== 0) fail('Frontend checks failed')
  ok('Frontend build and lint passed')
  say(`\n${green('All checks passed.')}`)
}

async function serve() {
  const apiBusy = !(await portFree(API_PORT))
  const uiBusy = !PROD && !(await portFree(UI_PORT))
  if (apiBusy || uiBusy) {
    fail(`Port ${apiBusy ? API_PORT : UI_PORT} is already in use`, [
      'Another copy of QuestionCrawler (or another program) is running.',
      'Close it and try again, or pick other ports:',
      WIN ? '  $env:QC_API_PORT=8081; $env:QC_UI_PORT=5174; npm start' : '  QC_API_PORT=8081 QC_UI_PORT=5174 npm start'])
  }
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
    : startService('api', magenta, gradlew(), [':app:bootRun', `--args=--server.port=${API_PORT}`, '--console=plain'], { cwd: BACKEND })
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
