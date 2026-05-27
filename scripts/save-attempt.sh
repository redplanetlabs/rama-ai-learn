#!/usr/bin/env bash
# Save current implementation as an attempt snapshot.
# Usage: save-attempt.sh <challenge-name>
#
# Copies implementations/<name>/src/<name_underscored>/module.clj to:
#   implementations/<name>/attempts/attempt<N>.clj
# where N is auto-incremented based on existing files.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: save-attempt.sh <challenge-name>" >&2
  exit 1
fi

NAME="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

UNDERSCORE_NAME="${NAME//-/_}"
IMPL_DIR="$ROOT/implementations/$NAME/src/$UNDERSCORE_NAME"
ATTEMPTS_DIR="$ROOT/implementations/$NAME/attempts"

# Find the implementation file: module.clj (protocol challenges) or solution.clj (LCB challenges)
if [[ -f "$IMPL_DIR/module.clj" ]]; then
  IMPL_FILE="$IMPL_DIR/module.clj"
elif [[ -f "$IMPL_DIR/solution.clj" ]]; then
  IMPL_FILE="$IMPL_DIR/solution.clj"
else
  echo "No implementation found in: $IMPL_DIR" >&2
  exit 1
fi

mkdir -p "$ATTEMPTS_DIR"
NEXT=$(find "$ATTEMPTS_DIR" -maxdepth 1 -name 'attempt*.clj' 2>/dev/null | wc -l | tr -d ' ')
cp "$IMPL_FILE" "$ATTEMPTS_DIR/attempt${NEXT}.clj"
echo "Saved attempt ${NEXT}"
