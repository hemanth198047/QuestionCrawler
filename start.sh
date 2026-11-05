#!/usr/bin/env bash
# Build and start QuestionCrawler (macOS / Linux).
cd "$(dirname "$0")" && exec node scripts/launch.cjs "$@"
