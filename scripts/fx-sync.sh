#!/usr/bin/env bash
# Sync REAL FX reference rates into Conduit's exchange_rate register from the ECB (served key-free by the
# Frankfurter feed). No fabricated rates — every row is a published ECB reference rate with its real as_of date
# and source 'ecb'. Writes the durable ingest snapshot (so a fresh `docker compose up` reloads it via the
# SnapshotLoader fx handler) AND upserts into the running DB for immediate effect. Re-run any time (idempotent) /
# from cron for a live feed.
#
#   ./scripts/fx-sync.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

FEED="https://api.frankfurter.dev/v1/latest"
PAIRS=("GBP:USD,EUR" "USD:GBP,EUR" "EUR:GBP,USD")   # the currencies Conduit reports/rolls up in
OUT="ingest/fx/rates.ndjson"
mkdir -p ingest/fx
: > "$OUT"

echo "→ fetching real ECB reference rates"
for p in "${PAIRS[@]}"; do
  base="${p%%:*}"; symbols="${p#*:}"
  curl -sL --max-time 20 "$FEED?base=$base&symbols=$symbols" \
    | python3 -c "import json,sys
d=json.load(sys.stdin); base=d['base']; date=d['date']
for q,r in d['rates'].items():
    print(json.dumps({'base':base,'quote':q,'rate':r,'as_of':date,'rate_type':'spot','source':'ecb'}))" >> "$OUT"
done
echo "→ wrote $(wc -l < "$OUT" | tr -d ' ') rates to $OUT (as of $(head -1 "$OUT" | python3 -c 'import json,sys;print(json.load(sys.stdin)["as_of"])'))"

# Upsert into the running register now (same idempotent insert the SnapshotLoader uses).
if docker ps --format '{{.Names}}' | grep -q '^conduit-postgres$'; then
  echo "→ upserting into the exchange_rate register"
  while IFS= read -r row; do
    eval "$(echo "$row" | python3 -c "import json,sys;d=json.load(sys.stdin);print(f\"B={d['base']};Q={d['quote']};R={d['rate']};A={d['as_of']};S={d['source']}\")")"
    docker exec conduit-postgres psql -qtA -U conduit -d conduit -c \
      "INSERT INTO exchange_rate (base,quote,rate,rate_type,as_of,source) SELECT '$B','$Q',$R,'spot','$A','$S' WHERE NOT EXISTS (SELECT 1 FROM exchange_rate WHERE base='$B' AND quote='$Q' AND as_of='$A' AND rate_type='spot');" >/dev/null
  done < "$OUT"
  echo "✓ register now holds $(docker exec conduit-postgres psql -qtA -U conduit -d conduit -c 'select count(*) from exchange_rate' | tr -d ' ') rates"
fi
