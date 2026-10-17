#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
# Runs the full suite with coverage, then writes COVERAGE.json from the measured per-area line coverage.
if command -v node >/dev/null 2>&1; then
  cd backend && npm ci && npx jest --coverage && node scripts/coverage-to-json.js
else
  docker run --rm -v "$PWD":/w -w /w/backend node:20-alpine sh -c "npm ci && npx jest --coverage && node scripts/coverage-to-json.js"
fi
