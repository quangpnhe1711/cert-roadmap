#!/usr/bin/env bash
#
# Stops the local development stack.
#   --purge  also delete the PostgreSQL volume, so the next start migrates a
#            fresh database from V1.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ "${1:-}" = "--purge" ]; then
  echo '==> Stopping PostgreSQL and deleting its volume'
  ( cd "$repo" && docker compose down -v )
  echo '    Local database erased. The next backend start migrates from empty.'
else
  echo '==> Stopping PostgreSQL (data kept)'
  ( cd "$repo" && docker compose down )
fi

cat <<'NEXT'

Stop the backend and frontend with Ctrl+C in their own terminals.
Uploaded files remain in local-data/storage; delete that directory to clear them.
NEXT
