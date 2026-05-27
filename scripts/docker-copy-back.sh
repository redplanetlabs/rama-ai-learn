#!/usr/bin/env bash
# Copy the repo from the container back to the host, overwriting the local repo.
# Usage: ./scripts/docker-copy-back.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="rama"

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "ERROR: Container '$CONTAINER' is not running." >&2
  exit 1
fi

echo "Copying container /work back to $REPO_ROOT ..."
docker exec "$CONTAINER" tar -C /work --exclude=.git -cf - . | tar -C "$REPO_ROOT" -xf -
echo "Done."
