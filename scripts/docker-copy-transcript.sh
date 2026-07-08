#!/usr/bin/env bash
# Copy transcripts from the rama Docker container to the host.
#
# With the dynamic workflow, transcripts are:
#   /transcripts/*.jsonl                           top-level session (one per challenge run)
#   ~/.claude/projects/-work/*/subagents/workflows/wf_*/   per-phase subagent transcripts + journal
#
# Usage:
#   ./scripts/docker-copy-transcript.sh            # copy the most recent workflow run
#   ./scripts/docker-copy-transcript.sh --all      # copy every transcript in the container
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="rama"

mode="run"
while [[ $# -gt 0 ]]; do
  case "$1" in
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

DEST="$REPO_ROOT/latest-transcripts"
rm -rf "$DEST"
mkdir -p "$DEST"

# Copy top-level transcript(s) from /transcripts/
TOP_COUNT=$(docker exec "$CONTAINER" bash -c 'ls /transcripts/*.jsonl 2>/dev/null | wc -l' | tr -d ' ')
if [[ "$TOP_COUNT" -gt 0 ]]; then
  if [[ "$mode" == "all" ]]; then
    docker exec "$CONTAINER" bash -c 'ls /transcripts/*.jsonl' | while read -r src; do
      docker cp "$CONTAINER:$src" "$DEST/$(basename "$src")"
    done
    echo "Copied $TOP_COUNT top-level transcript(s)"
  else
    LATEST_TOP=$(docker exec "$CONTAINER" bash -c 'ls -t /transcripts/*.jsonl 2>/dev/null | head -1')
    if [[ -n "$LATEST_TOP" ]]; then
      docker cp "$CONTAINER:$LATEST_TOP" "$DEST/$(basename "$LATEST_TOP")"
      echo "Copied top-level: $(basename "$LATEST_TOP")"
    fi
  fi
fi

# Find workflow run dir(s)
CLAUDE_PROJECTS="/home/agent/.claude/projects"

if [[ "$mode" == "all" ]]; then
  # Copy all wf_* dirs
  WF_DIRS=$(docker exec "$CONTAINER" bash -c "find $CLAUDE_PROJECTS -type d -name 'wf_*' 2>/dev/null" || true)
else
  # Find the most recently modified wf_* dir
  WF_DIRS=$(docker exec "$CONTAINER" bash -c "
    find $CLAUDE_PROJECTS -type d -name 'wf_*' 2>/dev/null \
      | while read d; do
          latest=\$(find \"\$d\" -type f -printf '%T@\n' 2>/dev/null | sort -rn | head -1)
          echo \"\${latest:-0} \$d\"
        done \
      | sort -rn | head -1 | cut -d' ' -f2-
  " || true)
fi

if [[ -z "$WF_DIRS" ]]; then
  echo "No workflow run directories found."
else
  for wf_dir in $WF_DIRS; do
    wf_name=$(basename "$wf_dir")
    wf_dest="$DEST/$wf_name"
    mkdir -p "$wf_dest"

    # Copy all files from the wf dir
    docker exec "$CONTAINER" bash -c "ls '$wf_dir'/*.jsonl '$wf_dir'/*.json 2>/dev/null" \
      | while read -r src; do
          docker cp "$CONTAINER:$src" "$wf_dest/$(basename "$src")"
        done

    n=$(ls "$wf_dest" | wc -l | tr -d ' ')
    echo "Copied $n files from $wf_name"
  done
fi

echo "All transcripts saved to: $DEST"
