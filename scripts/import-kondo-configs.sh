#!/usr/bin/env bash
# Import clj-kondo configs from classpath (including Rama hooks) for a challenge.
# Usage: import-kondo-configs.sh <challenge-name>
# Always re-imports to pick up hook updates.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: import-kondo-configs.sh <challenge-name>" >&2
  exit 1
fi

NAME="$1"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CHALLENGE_DIR="$ROOT/challenges/$NAME"
KONDO_DIR="$CHALLENGE_DIR/.clj-kondo"

# Get the challenge classpath
CP=$(cd "$CHALLENGE_DIR" && clojure -Spath 2>/dev/null)

# Clear old imports and re-import
rm -rf "$KONDO_DIR"
mkdir "$KONDO_DIR"
cd "$CHALLENGE_DIR"
clj-kondo --copy-configs --dependencies --lint "$CP" > /dev/null 2>&1 || true
echo "Imported kondo configs for $NAME"
