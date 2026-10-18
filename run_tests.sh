#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
# Runs the full suite with coverage, then writes COVERAGE.json from the measured per-area line coverage.
# COVERAGE.json is generated even when tests fail; the script exits with jest's status.
if command -v node >/dev/null 2>&1; then
  cd backend
  npm ci
  code=0
  npx jest --coverage || code=$?
  node scripts/coverage-to-json.js || true
  exit "$code"
else
  docker run --rm -v "$PWD":/w -w /w/backend node:20-alpine sh -c 'npm ci; code=0; npx jest --coverage || code=$?; node scripts/coverage-to-json.js || true; exit $code'
fi
