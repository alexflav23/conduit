#!/bin/bash
# The dual-run refresher (doc 26 §6): incremental pulls from every live source -> git snapshots -> reload ->
# rescore -> republish. Idempotent end to end; safe to run any time the tunnel is up.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source ~/projects/hypervolt/athena/.env 2>/dev/null; source ~/projects/hypervolt/ghost-busters/.env 2>/dev/null; set +a

echo "[1/4] activations delta (prod Athena via tunnel)"
if nc -z localhost 15432 2>/dev/null; then
  docker exec -e PGPASSWORD="$ATHENA_DB_PASSWORD" conduit-postgres psql -h host.docker.internal -p 15432 \
    -U "$ATHENA_DB_USER" -d pricing -c "COPY (
      SELECT serial, min(created_at) FROM charger_activation
      WHERE serial LIKE '0301%' AND serial NOT LIKE '%-rtn' AND created_at > '2021-01-01'
      GROUP BY serial) TO STDOUT WITH (FORMAT csv)" \
  | python3 -c "
import sys, json
with open('ingest/ghostbusters/activations.ndjson', 'w') as f:
    for line in sys.stdin:
        line = line.strip()
        if not line: continue
        serial, activated = line.split(',', 1)
        f.write(json.dumps({'serial': serial, 'activated_at': activated.replace(' ', 'T') + 'Z'}) + chr(10))
" && echo "  activations refreshed"
else
  echo "  tunnel down — skipped"
fi

echo "[2/4] MRPeasy refresh (orders + shipments)"
python3 scripts/mrpeasy_scrape.py 2>&1 | tail -2 || echo "  mrpeasy scrape failed — keeping prior snapshot"

echo "[3/4] reload snapshots (idempotent upserts)"
docker restart conduit-api >/dev/null
until docker logs conduit-api --since 1m 2>&1 | grep -q "Snapshot ingest"; do sleep 10; done
echo "  reloaded"

echo "[4/4] rescore + republish"
sbt -batch "scripting/runMain com.hypervolt.conduit.scripting.RealBacktest" \
    "scripting/runMain com.hypervolt.conduit.scripting.LivePublish" 2>&1 | grep -E "complete|published"
echo "refresh done $(date)"
