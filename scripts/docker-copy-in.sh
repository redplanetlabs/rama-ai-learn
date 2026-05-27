#!/usr/bin/env bash
# Copy the repo into the running container.
# Usage: ./scripts/docker-copy-in.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="rama"

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "ERROR: Container '$CONTAINER' is not running." >&2
  echo "Start it first with: ./scripts/docker-start.sh" >&2
  exit 1
fi

echo "Copying repo into container..."
tar -C "$REPO_ROOT" --exclude=.git --exclude=NOTES -cf - . | docker exec -i "$CONTAINER" tar -C /work -xf -
echo "Done."
