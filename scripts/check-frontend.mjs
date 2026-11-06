#!/usr/bin/env node
// Finds out WHY the frontend cannot find a file: missing, in the wrong folder, a hidden extension (X.tsx.txt), a different
// capitalization, a web page saved instead of the source, an older copy without the expected exports, or a missing package.
//   node scripts/check-frontend.mjs               (from the repository folder)
//   node check-frontend.mjs C:\path\to\frontend   (the script can live anywhere)
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const FRONTEND = path.resolve(process.argv[2] ?? [path.join(here, '..', 'frontend'), path.join(process.cwd(), 'frontend'), process.cwd()].find((p) => existsSync(path.join(p, 'src'))) ?? '.')
const SRC = path.join(FRONTEND, 'src')
if (!existsSync(SRC)) { console.error(`No 'src' folder in ${FRONTEND}. Pass the frontend folder: node check-frontend.mjs <path>`); process.exit(2) }

const EXT = ['', '.ts', '.tsx', '.d.ts', '.js', '.jsx', '.css', '.json', '/index.ts', '/index.tsx']
const problems = []
const problem = (file, line, text, fix) => problems.push({ file: path.relative(FRONTEND, file), line, text, fix })

const walk = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? (e.name === 'node_modules' ? [] : walk(path.join(dir, e.name))) : [path.join(dir, e.name)]))
const files = walk(SRC)
const sourceFiles = files.filter((f) => /\.(tsx?|jsx?)$/.test(f))
const lowerIndex = new Map(files.map((f) => [path.basename(f).toLowerCase(), f]))

/** Case-sensitive existence (Windows and macOS would otherwise hide a wrong capitalization). */
function exactExists(p) {
  if (!existsSync(p)) return false
  const dir = path.dirname(p)
  return readdirSync(dir).includes(path.basename(p))
}
function resolveRelative(fromFile, spec) {
  const base = path.resolve(path.dirname(fromFile), spec)
  for (const e of EXT) { const c = base + e; if (exactExists(c) && statSync(c).isFile()) return c }
  return null
}
function looksLikeWebPage(text) { return /^\s*<!doctype html|^\s*<html|<title>[^<]*GitHub|id="js-repo-pjax-container"/i.test(text.slice(0, 2000)) }
function exportsOf(text) {
  const names = new Set()
  for (const m of text.matchAll(/export\s+(?:default\s+)?(?:async\s+)?(?:function\*?|const|let|var|class|interface|type|enum)\s+([A-Za-z0-9_$]+)/g)) names.add(m[1])
  for (const m of text.matchAll(/export\s*\{([^}]*)\}/g)) m[1].split(',').forEach((x) => { const n = x.trim().split(/\s+as\s+/).pop(); if (n) names.add(n.trim()) })
  return names
}

function explainMissing(importer, line, spec) {
  const base = path.resolve(path.dirname(importer), spec)
  const name = path.basename(base)
  const dir = path.dirname(base)
  // different capitalization
  if (existsSync(dir)) {
    const hit = readdirSync(dir).find((f) => f.toLowerCase().replace(/\.(tsx?|jsx?)$/, '') === name.toLowerCase())
    if (hit) return problem(importer, line, `'${spec}' is imported, but the file is named '${hit}' (different capitalization).`, `Rename '${hit}' to '${name}.tsx' exactly as written.`)
    const odd = readdirSync(dir).find((f) => f.toLowerCase().startsWith(name.toLowerCase() + '.') || f.toLowerCase().startsWith(name.toLowerCase() + ' '))
    if (odd) return problem(importer, line, `'${spec}' is imported, but the folder has '${odd}' instead of '${name}.tsx'. A hidden extension (.txt, .download) or a copy suffix like "(1)" is the usual reason.`, `In Explorer turn on View > File name extensions, then rename '${odd}' to '${name}.tsx'.`)
  } else {
    return problem(importer, line, `'${spec}' is imported, but the folder '${path.relative(FRONTEND, dir)}' does not exist.`, `Create it and copy the file there.`)
  }
  // right file, wrong folder
  const elsewhere = [...lowerIndex.entries()].find(([n]) => n.replace(/\.(tsx?|jsx?)$/, '') === name.toLowerCase())
  if (elsewhere) return problem(importer, line, `'${spec}' is imported, but the file was found somewhere else: ${path.relative(FRONTEND, elsewhere[1])}.`, `Move it to ${path.relative(FRONTEND, dir)}${path.sep}.`)
  // outside src entirely
  const outside = ['', '..'].map((d) => path.join(FRONTEND, d, 'components', name + '.tsx')).find((p) => existsSync(p))
  if (outside) return problem(importer, line, `'${spec}' is imported, but the file is outside 'src': ${outside}.`, `Move it into ${path.relative(FRONTEND, dir)}${path.sep}.`)
  return problem(importer, line, `'${spec}' is imported, but the file does not exist: ${path.relative(FRONTEND, base)}(.tsx).`, `Copy ${name}.tsx from the repository into ${path.relative(FRONTEND, dir)}${path.sep}.`)
}

const checkedFiles = new Set()
function checkContent(file) {
  if (checkedFiles.has(file)) return true
  checkedFiles.add(file)
  const text = readFileSync(file, 'utf8')
  if (text.trim().length < 20) { problem(file, 0, 'The file is empty.', 'Copy it again from the repository.'); return false }
  if (looksLikeWebPage(text)) { problem(file, 0, 'This is a saved web page (HTML), not the source code. Saving a GitHub page gives HTML.', 'Open the file on GitHub, press the "Raw" button (or Copy raw file) and save/copy THAT.'); return false }
  return true
}

for (const file of sourceFiles) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split(/\r?\n/)
  lines.forEach((lineText, i) => {
    const m = /^\s*(?:import|export)\s+(type\s+)?(?:([\s\S]*?)\s+from\s+)?['"]([^'"]+)['"]/.exec(lineText) // single-line imports
    if (!m) return
    const spec = m[3]
    if (spec.startsWith('.')) {
      const target = resolveRelative(file, spec)
      if (!target) return explainMissing(file, i + 1, spec)
      if (!/\.(tsx?|jsx?)$/.test(target)) return
      if (!checkContent(target)) return
      const clause = m[2]
      if (!clause) return
      const named = /\{([^}]*)\}/.exec(clause)
      if (named) {
        const have = exportsOf(readFileSync(target, 'utf8'))
        for (const raw of named[1].split(',')) {
          const n = raw.trim().replace(/^type\s+/, '').split(/\s+as\s+/)[0].trim()
          if (n && !have.has(n)) problem(file, i + 1, `'${path.relative(FRONTEND, target)}' does not export '${n}'. The copy is probably an older version.`, `Copy the latest '${path.basename(target)}' from the repository.`)
        }
      }
    } else if (!spec.startsWith('node:') && !spec.startsWith('@/')) {
      const pkg = spec.startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0]
      if (!existsSync(path.join(FRONTEND, 'node_modules', ...pkg.split('/')))) problem(file, i + 1, `The package '${pkg}' is not installed.`, `Run 'npm install' in ${FRONTEND}.`)
    }
  })
}

const required = ['ApiDocsPanel', 'RawAnswerPanel', 'TagFinder'].filter((n) => !existsSync(path.join(SRC, 'components', n + '.tsx')) && !problems.some((p) => p.text.includes(n)))
required.forEach((n) => problem(path.join(SRC, 'components', n + '.tsx'), 0, `Expected component ${n}.tsx is not in src/components.`, 'Copy it from the repository.'))

console.log(`Checked ${sourceFiles.length} source files in ${FRONTEND}`)
if (problems.length === 0) { console.log('\nOK: every import resolves, every imported name is exported, nothing looks damaged.\nIf your editor still shows errors, restart its TypeScript server (VS Code: Ctrl+Shift+P, "TypeScript: Restart TS Server").'); process.exit(0) }
console.log(`\n${problems.length} problem${problems.length === 1 ? '' : 's'} found:\n`)
problems.forEach((p, i) => console.log(`${i + 1}. ${p.file}${p.line ? ':' + p.line : ''}\n   ${p.text}\n   → ${p.fix}\n`))
process.exit(1)
