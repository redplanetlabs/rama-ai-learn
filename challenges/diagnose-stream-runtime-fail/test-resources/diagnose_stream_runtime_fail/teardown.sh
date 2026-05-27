#!/usr/bin/env bash
# Hidden teardown for diagnose-stream-runtime-fail.
# Shuts down the local compose cluster started by reference_solution.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
MODULE_NAME="com.rpl.challenges.stream-ack-fail/StreamAckFailModule"
RAMA_BIN="${HOME}/.rama/rama-local"

cd "$REPO_ROOT"

if [[ -x "$RAMA_BIN" ]]; then
  "$RAMA_BIN" destroy --name "$MODULE_NAME" >/dev/null 2>&1 || true
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif podman compose version >/dev/null 2>&1; then
  COMPOSE_CMD="podman compose"
else
  echo "Error: neither 'docker compose' nor 'podman compose' is available." >&2
  exit 1
fi

RAMA_VERSION=dev RAMA_JAVA_VERSION=25 $COMPOSE_CMD down
