#!/usr/bin/env bash
# Copy transcripts from the rama Docker container to the host.
#
# Filenames are of the form:
#   {date}-{time}-{agent}[-{model}][-{reasoning}]-{challenge}-phase{N}[-attempt{K}].jsonl
# All transcripts of one challenge run share the same {date}-{time} prefix
# (the run-start-time stamped by the runner), so they can be grouped.
#
# Usage:
#   ./scripts/docker-copy-transcript.sh                     # copy all transcripts of the most recent run into latest-transcripts/
#   ./scripts/docker-copy-transcript.sh --phase N           # copy phase N of the most recent run to latest-transcript.jsonl
#   ./scripts/docker-copy-transcript.sh --all               # copy every transcript in the container
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="rama"

mode="run"     # default: copy all transcripts of the most recent run
phase=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --phase)
      mode="phase"
      phase="${2:-}"
      if [[ -z "$phase" ]]; then
        echo "ERROR: --phase requires an argument" >&2
        exit 2
      fi
      shift 2
      ;;
    --all)
      mode="all"
      shift
      ;;
    -h|--help)
      sed -n '2,11p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: unknown option: $1" >&2
      exit 2
      ;;
  esac
done

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "ERROR: Container '$CONTAINER' is not running." >&2
  exit 1
fi

# Most recently mtime'd transcript in the container.
LATEST=$(docker exec "$CONTAINER" bash -c 'ls -t /transcripts/*.jsonl 2>/dev/null | head -1')
if [[ -z "$LATEST" ]]; then
  echo "ERROR: No transcripts found in container." >&2
  exit 1
fi

LATEST_BASENAME=$(basename "$LATEST")

# Run prefix = filename up to and including the challenge name, before the
# `-phase{N}` suffix. Strip `-phaseN` and `-phaseN-attemptK` suffixes from the
# matched basename, leaving the shared run-start-time + agent + challenge
# prefix that all phases of the same run share.
RUN_PREFIX=$(echo "$LATEST_BASENAME" | sed -E 's/-phase[0-9]+(-attempt[0-9]+)?\.jsonl$//; s/\.jsonl$//')

case "$mode" in
  all)
    DEST="$REPO_ROOT/latest-transcripts"
    rm -rf "$DEST"
    mkdir -p "$DEST"
    docker exec "$CONTAINER" bash -c 'ls /transcripts/*.jsonl' | while read -r src; do
      docker cp "$CONTAINER:$src" "$DEST/$(basename "$src")"
    done
    echo "Copied all transcripts to: $DEST"
    ;;

  run)
    DEST="$REPO_ROOT/latest-transcripts"
    rm -rf "$DEST"
    mkdir -p "$DEST"
    docker exec "$CONTAINER" bash -c "ls /transcripts/${RUN_PREFIX}-phase*.jsonl 2>/dev/null" \
      | while read -r src; do
          docker cp "$CONTAINER:$src" "$DEST/$(basename "$src")"
        done
    n=$(ls "$DEST" | wc -l | tr -d ' ')
    echo "Copied $n transcripts (run: ${RUN_PREFIX}) to: $DEST"
    ;;

  phase)
    # Find the latest attempt of the requested phase within the most recent run.
    PHASE_PATTERN="/transcripts/${RUN_PREFIX}-phase${phase}*.jsonl"
    LATEST_PHASE=$(docker exec "$CONTAINER" bash -c "ls -t ${PHASE_PATTERN} 2>/dev/null | head -1")
    if [[ -z "$LATEST_PHASE" ]]; then
      echo "ERROR: No transcript for phase $phase of run ${RUN_PREFIX}." >&2
      exit 1
    fi
    docker cp "$CONTAINER:$LATEST_PHASE" "$REPO_ROOT/latest-transcript.jsonl"
    echo "Copied $(basename "$LATEST_PHASE") to $REPO_ROOT/latest-transcript.jsonl"
    ;;
esac
