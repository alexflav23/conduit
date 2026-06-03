#!/usr/bin/env bash
# Orchestrates the desk e2e: start the Conduit API (migrates local pg), seed, run Playwright, tear down.
set -uo pipefail
ROOT="$HOME/projects/hypervolt/conduit"
DESK="$ROOT/conduit-desk"
PGBIN=/opt/homebrew/opt/postgresql@15/bin

kill_port() { for pid in $(lsof -ti tcp:"$1" 2>/dev/null); do kill "$pid" 2>/dev/null || true; done; }

echo "== freeing ports =="
kill_port 8080; kill_port 9990; kill_port 3002
pkill -f "conduit-api" 2>/dev/null || true
sleep 1

echo "== starting Conduit API (migrates local conduit db on :5432) =="
cd "$ROOT"
CONDUIT_DB_PORT=5432 HYPERVOLT_ENV=local sbt -batch -no-colors "api/run" > /tmp/conduit_e2e_api.log 2>&1 &
for i in $(seq 1 90); do
  if curl -sf http://localhost:8080/health >/dev/null 2>&1; then echo "backend healthy"; break; fi
  sleep 2
done

echo "== seeding =="
"$PGBIN/psql" -h 127.0.0.1 -p 5432 -d conduit -U conduit -v ON_ERROR_STOP=1 -f "$DESK/e2e/seed.sql"

echo "== running Playwright =="
cd "$DESK"
npx playwright test
RESULT=$?

echo "== teardown =="
kill_port 8080; kill_port 9990; kill_port 3002
pkill -f "conduit-api" 2>/dev/null || true
exit $RESULT
