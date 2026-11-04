#!/usr/bin/env bash
# Launcher for the QuestionCrawler CLI (Linux/macOS). Usage: scripts/qc.sh <command> [options]
# Looks for the built distribution (./gradlew :core:installDist) or $QUESTIONCRAWLER_HOME.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_DIR="${QUESTIONCRAWLER_HOME:-$ROOT/backend/core/build/install/questioncrawler}"
BIN="$HOME_DIR/bin/questioncrawler"
if [[ ! -x "$BIN" ]]; then
  echo "QuestionCrawler is not built yet: $BIN not found." >&2
  echo "Build it once:  cd \"$ROOT/backend\" && ./gradlew :core:installDist   (needs JDK 21)" >&2
  exit 1
fi
exec "$BIN" "$@"
