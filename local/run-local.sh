#!/usr/bin/env bash
# One command to drive Conduit locally end-to-end: builds the API, brings up Postgres + TigerBeetle + the API in
# Docker, loads a demo H6Q + stock dataset, and starts the desk. Open the desk and explore every tab.
#
#   ./local/run-local.sh            # demo dataset (default)
#   ./local/run-local.sh --import   # load your real data: drop the finance workbook in as local/import/h6q.xlsx
#                                   # (parsed directly) or use h6q.csv / stock.csv — see local/README.md
#   ./local/run-local.sh --no-desk  # backend only (API on :8080), skip starting the Vite desk
#
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESK="$ROOT/conduit-desk"
DESK_PORT=3002
IMPORT=false; NODESK=false
for a in "$@"; do case "$a" in --import) IMPORT=true;; --no-desk) NODESK=true;; esac; done

say() { printf "\n\033[1;35m== %s\033[0m\n" "$1"; }

command -v docker >/dev/null || { echo "docker is required"; exit 1; }
command -v sbt >/dev/null || { echo "sbt is required to stage the API"; exit 1; }

say "Building the API (sbt api/stage)"
( cd "$ROOT" && sbt -batch "api/stage" ) || { echo "stage failed"; exit 1; }

say "Starting Postgres + TigerBeetle + API (docker compose)"
docker compose -f "$ROOT/docker-compose.local.yml" up --build -d

say "Waiting for the API to be healthy (Flyway migrates on boot)"
for i in $(seq 1 90); do
  if curl -sf http://localhost:8080/health >/dev/null 2>&1; then echo "API healthy"; break; fi
  sleep 2
  [ "$i" = 90 ] && { echo "API did not become healthy; see: docker logs conduit-local-api"; exit 1; }
done

if $IMPORT; then
  say "Importing your H6Q + stock from local/import/*.csv"
  bash "$ROOT/local/import.sh" || { echo "import failed (see local/README.md for the CSV format)"; exit 1; }
else
  say "Loading the demo H6Q + stock dataset"
  docker exec -i conduit-local-postgres psql -q -U conduit -d conduit < "$DESK/e2e/seed.sql"
fi

printf '\n\033[1;32mConduit is up.\033[0m\n'
cat <<'BANNER'
  API        : http://localhost:8080        (health: http://localhost:9990/health)
  Metrics    : http://localhost:9464
  Postgres   : localhost:5532  (user/pass/db = conduit)
  TigerBeetle: localhost:3033

  Auth tokens to paste into the desk's "Auth token" box:
    dev:finance-e2e   — full money + ledger + coverage board + supply window + shelf
    dev:agent-e2e     — a sales agent (capture own accounts; volume-only)
    dev:ceo-e2e       — the single price-deviation approver (Deal Desk)

  Try: H6Q tab (submit a forecast as dev:agent-e2e) -> watch the Coverage board + Supply
       proposals populate live -> Flow tab shows the variants over time + the ledger transfers.

BANNER

if $NODESK; then
  echo "Backend only. Stop with: docker compose -f docker-compose.local.yml down"
  exit 0
fi

say "Starting the desk (Vite on :$DESK_PORT)"
cd "$DESK"
[ -d node_modules ] || { echo "installing desk deps..."; yarn install; }
echo "Desk: http://localhost:$DESK_PORT   (Ctrl-C to stop the desk; stack stays up)"
echo "Stop the whole stack later with: docker compose -f $ROOT/docker-compose.local.yml down"
yarn start -- --port "$DESK_PORT"
