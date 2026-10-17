#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
if command -v node >/dev/null 2>&1; then
  cd backend && npm ci && npx jest --coverage
else
  docker run --rm -v "$PWD":/w -w /w/backend node:20-alpine sh -c "npm ci && npx jest --coverage"
fi
