#!/usr/bin/env bash
# The whole knowledge-base pipeline: fetch the sources, cut them into chunks, embed and store
# them through the worker, and print what went in.
#
#   ./scripts/kb/build.sh            # everything
#   ./scripts/kb/build.sh --no-fetch # re-chunk and re-upload what is already in scripts/kb/data
#
# Needs: python3, the worker deployed with its AI and KB bindings, and KB_ADMIN_SECRET
# (environment or scripts/kb/.admin_secret). Optional: GITHUB_TOKEN for GitHub's rate limit.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [[ "${1:-}" != "--no-fetch" ]]; then
  python3 scripts/kb/fetch.py all
fi
python3 scripts/kb/chunk.py
python3 scripts/kb/upload.py

echo
echo "index:"
(cd worker && npx --no-install wrangler vectorize info heylana-kb 2>/dev/null | grep -iE 'vector|dimension' || true)
