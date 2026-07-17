#!/usr/bin/env bash
# Start an interactive Docker container for running challenges.
# Usage: ./scripts/docker-start.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="rama"

# Remove stale container if exists
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "Removing existing '$CONTAINER' container..."
  docker rm -f "$CONTAINER"
fi

if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
  echo "ERROR: CLAUDE_CODE_OAUTH_TOKEN is not set." >&2
  exit 1
fi

echo "Starting container '$CONTAINER'..."
# No host bind mounts: Docker file sharing is disabled, so host paths
# cannot be mounted. Maven/gitlibs caches live in named volumes that
# persist across containers; data moves in/out only via docker cp.
docker run -d \
  --name "$CONTAINER" \
  -e CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_CODE_OAUTH_TOKEN" \
  -v rama-m2:/root/.m2 \
  -v rama-gitlibs:/root/.gitlibs \
  rama-challenges sleep infinity

# Seed the Maven repository volume from the host on first use (docker cp
# streams via the API and needs no file sharing). One-time: the rama-m2
# volume persists, so later starts skip this. A sentinel file marks the
# seed — mere existence of /root/.m2/repository doesn't, because the
# image's own build-time downloads pre-populate the volume.
if [ -d "$HOME/.m2/repository" ] && \
   ! docker exec "$CONTAINER" test -f /root/.m2/.host-seeded; then
  echo "Seeding Maven repository into rama-m2 volume (one-time, may take a few minutes)..."
  docker cp "$HOME/.m2/repository" "$CONTAINER:/root/.m2/"
  docker exec "$CONTAINER" touch /root/.m2/.host-seeded
fi

# Copy minimal Claude config (no conversation history, memory, or session state)
echo "Copying Claude config into container..."
docker exec "$CONTAINER" mkdir -p /root/.claude
docker cp "$HOME/.claude/settings.json" "$CONTAINER:/root/.claude/settings.json"

echo "Attaching to container..."
docker exec -it "$CONTAINER" bash

echo "Container exited. To copy results back: ./scripts/docker-copy-back.sh"
