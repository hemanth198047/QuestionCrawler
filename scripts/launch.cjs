// Tiny starter written in old-style JavaScript on purpose: it must be parseable by ANY Node.js version, so that
// an outdated Node produces a clear message instead of "SyntaxError: Unexpected token" from the real launcher.
'use strict'
var major = parseInt(String(process.versions.node).split('.')[0], 10)
if (major < 20) {
  console.error('')
  console.error('x Your Node.js is version ' + process.versions.node + ', but QuestionCrawler needs Node.js 20 or newer.')
  console.error('')
  console.error('  How to fix:')
  console.error('   1. Install Node.js 20 or newer (LTS): https://nodejs.org')
  console.error('      Windows:  winget install OpenJS.NodeJS.LTS')
  console.error('   2. Close this terminal, open a NEW one, and check:  node -v')
  console.error('   3. If it still shows an old version, another Node is first on your PATH.')
  console.error('      Windows:  where node      (the first line is the one that runs)')
  console.error('      Using nvm?  nvm install 20  then  nvm use 20')
  console.error('')
  process.exit(1)
}
import('./start.mjs').catch(function (error) {
  console.error('')
  console.error('x The launcher could not start: ' + (error && error.message ? error.message : error))
  console.error('  If this mentions a syntax error, check that scripts/start.mjs was not changed or damaged (run: git status).')
  process.exit(1)
})
