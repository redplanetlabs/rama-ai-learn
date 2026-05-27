#!/usr/bin/env bash
# Hidden setup for diagnose-stream-runtime-fail.
# Starts a single-supervisor local cluster, builds the module jar,
# and deploys a module that succeeds at launch but fails persistently at runtime
# for a poison-pill stream event.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
MODULE_DIR="$REPO_ROOT/challenges/diagnose-stream-runtime-fail/test-resources/diagnose_stream_runtime_fail"
JAR="$MODULE_DIR/target/stream-ack-fail-1.0.0.jar"
MODULE_NAME="com.rpl.challenges.stream-ack-fail/StreamAckFailModule"
RAMA_BIN="$HOME/.rama/rama-local"

cd "$REPO_ROOT"

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif podman compose version >/dev/null 2>&1; then
  COMPOSE_CMD="podman compose"
else
  echo "Error: neither 'docker compose' nor 'podman compose' is available." >&2
  exit 1
fi

RAMA_VERSION=dev RAMA_JAVA_VERSION=25 $COMPOSE_CMD down >/dev/null 2>&1 || true
RAMA_VERSION=dev RAMA_JAVA_VERSION=25 $COMPOSE_CMD up -d zookeeper conductor supervisor

"$REPO_ROOT/challenges/cluster-shared/bin/wait-for-local-rama-cluster.sh" "$COMPOSE_CMD" 180

if ! "$REPO_ROOT/challenges/cluster-shared/bin/setup-local-rama-cli.sh" "$COMPOSE_CMD"; then
  echo "Error: failed to configure local rama CLI from conductor container." >&2
  exit 1
fi

ln -sf "$HOME/.rama/local/rama" "$RAMA_BIN"

if "$RAMA_BIN" moduleStatus "$MODULE_NAME" >/dev/null 2>&1; then
  "$RAMA_BIN" destroy --name "$MODULE_NAME" >/dev/null 2>&1 || true
fi

(cd "$MODULE_DIR" && rm -rf target .cpcache && clojure -T:build jar)

"$RAMA_BIN" deploy --action launch \
  --jar "$JAR" \
  --module "$MODULE_NAME" \
  --tasks 1 --threads 1 --workers 1 --replicationFactor 1

for _ in $(seq 1 120); do
  if "$RAMA_BIN" moduleStatus "$MODULE_NAME" 2>/dev/null | grep -q "RUNNING"; then
    echo "Deployed $MODULE_NAME for diagnose-stream-runtime-fail"
    exit 0
  fi
  sleep 1
done

echo "Error: module $MODULE_NAME did not reach RUNNING state." >&2
exit 1
