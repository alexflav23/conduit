#!/usr/bin/env bash
# Checkout → run → seeded (doc 26 §3a): stages both apps, builds the images, brings the whole stack up with
# persistent volumes (no data loss across restarts) and loads the git-committed NDJSON snapshots on boot.
set -euo pipefail
cd "$(dirname "$0")"
sbt api/stage consumer/stage
docker compose up -d --build
echo "Conduit up: api http://localhost:8080  health http://localhost:9990/health  metrics http://localhost:9464/metrics"
