#!/usr/bin/env bash
# Build the Docker image for running challenges.
set -euo pipefail

cd "$(dirname "$0")/.."
docker build -t rama-challenges .
