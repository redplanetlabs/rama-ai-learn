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
docker run -d \
  --name "$CONTAINER" \
  -e CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_CODE_OAUTH_TOKEN" \
  -v "$HOME/.m2:/home/agent/.m2" \
  -v rama-gitlibs:/home/agent/.gitlibs \
  rama-challenges sleep infinity

# Copy minimal Claude config (no conversation history, memory, or session state)
echo "Copying Claude config into container..."
docker exec -u root "$CONTAINER" mkdir -p /home/agent/.claude
docker cp "$HOME/.claude/settings.json" "$CONTAINER:/home/agent/.claude/settings.json"
docker exec -u root "$CONTAINER" chown -R agent:agent /home/agent/.claude

echo "Attaching to container..."
docker exec -it -u agent "$CONTAINER" bash

echo "Container exited. To copy results back: ./scripts/docker-copy-back.sh"
