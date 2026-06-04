#!/usr/bin/env bash
# Import your REAL H6Q forecast + stock positions into the local stack so you can verify and simulate against
# live data instead of the demo seed. Reads, in order of preference, from local/import/ (see local/README.md):
#
#   local/import/h6q.xlsx   the finance workbook itself — parsed directly (channel volume x SKU mix -> per-SKU)
#   local/import/h6q.csv    market,channel,sku,period_month,qty   (one row per SKU per market per month)
#   local/import/stock.csv  serial_no,sku,account,status          (one row per serial; status drives on-shelf/activated)
#
# All optional — import whichever you have. The .xlsx is the real model with no conversion step; if both an
# .xlsx and an h6q.csv are present, the .xlsx wins. CSVs are copied into the container and staged in a TEMP
# table within a SINGLE psql session (temp tables are session-scoped), then INSERT...SELECT resolves the ids —
# so a bad row fails loudly rather than silently corrupting the board. Re-running is idempotent.
#
# Set H6Q_EX_MOTABILITY=1 to import the workbook's "Ex Motability" sheet into the P50 ex_motability ex-cut.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMP="$ROOT/local/import"
CTR=conduit-local-postgres
PSQL=(docker exec -i "$CTR" psql -v ON_ERROR_STOP=1 -q -U conduit -d conduit)
XLSX=""
for f in "$IMP"/*.xlsx; do [ -f "$f" ] && XLSX="$f" && break; done

[ -n "$XLSX" ] || [ -f "$IMP/h6q.csv" ] || [ -f "$IMP/stock.csv" ] || {
  echo "Nothing to import in $IMP. Drop the finance workbook in as h6q.xlsx (or an h6q.csv / stock.csv —"
  echo "see local/README.md), then re-run with --import."
  exit 1
}

# Ensure openpyxl is available for the .xlsx parser, in a local venv (gitignored) so we don't touch system python.
ensure_openpyxl() {
  python3 -c "import openpyxl" 2>/dev/null && { PY=python3; return; }
  PY="$ROOT/local/.venv/bin/python"
  [ -x "$PY" ] && "$PY" -c "import openpyxl" 2>/dev/null && return
  echo "Setting up a local Python venv for the .xlsx parser (one-off)..."
  python3 -m venv "$ROOT/local/.venv" && "$PY" -m pip -q install openpyxl
}

# Always start from the demo catalogue + reference data (markets, channels, scenarios, roles) so SKUs and
# markets referenced by your data resolve. Your rows then layer on top.
echo "Loading reference data (catalogue, markets, channels, scenarios) from the demo seed..."
"${PSQL[@]}" < "$ROOT/conduit-desk/e2e/seed.sql" >/dev/null

if [ -n "$XLSX" ]; then
  echo "Importing the H6Q workbook directly: $(basename "$XLSX")"
  ensure_openpyxl
  EXFLAG=""; [ "${H6Q_EX_MOTABILITY:-0}" = "1" ] && EXFLAG="--ex-motability"
  "$PY" "$ROOT/local/import_xlsx.py" "$XLSX" $EXFLAG | "${PSQL[@]}"
  "${PSQL[@]}" -c "SELECT count(*) || ' H6Q coverage rows present (' || count(DISTINCT product_variant_id) || ' SKUs, ' || count(DISTINCT period_month) || ' months)' FROM pipeline_coverage WHERE level='market' AND product_variant_id IS NOT NULL;"
elif [ -f "$IMP/h6q.csv" ]; then
  echo "Importing H6Q forecast rows from h6q.csv ..."
  docker cp "$IMP/h6q.csv" "$CTR:/tmp/h6q.csv"
  "${PSQL[@]}" <<'SQL'
CREATE TEMP TABLE _h6q (market text, channel text, sku text, period_month date, qty int);
COPY _h6q(market,channel,sku,period_month,qty) FROM '/tmp/h6q.csv' WITH (FORMAT csv, HEADER true);
-- Local mode is single-market (year-1 = UK only); everything imports into the demo market the desk pins
-- ('22222222-...') so it is immediately visible on the Flow / Coverage board. Resolve sku -> product_variant,
-- default to the P50 base scenario. Market-level rows are the same shape the CoverageProjector emits.
-- Delete-then-insert on the imported (sku, month) keys keeps re-runs idempotent without depending on the
-- exact column list of the partial unique index (which spans every coverage dimension).
DELETE FROM pipeline_coverage pc USING _h6q h, product_variant pv
 WHERE pc.level='market' AND pc.market_id = '22222222-2222-2222-2222-222222222222'
   AND pc.product_variant_id = pv.id AND pv.sku = h.sku AND pc.period_month = h.period_month;

INSERT INTO pipeline_coverage (level, market_id, product_variant_id, period_month, scenario_id, forecast_qty)
SELECT 'market', '22222222-2222-2222-2222-222222222222', pv.id, h.period_month, sc.id, h.qty
FROM _h6q h
JOIN product_variant pv ON pv.sku = h.sku
CROSS JOIN LATERAL (SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL LIMIT 1) sc;

SELECT count(*) || ' H6Q coverage rows present' FROM pipeline_coverage WHERE level='market' AND product_variant_id IS NOT NULL;
SQL
fi

if [ -f "$IMP/stock.csv" ]; then
  echo "Importing stock / serial positions from stock.csv ..."
  docker cp "$IMP/stock.csv" "$CTR:/tmp/stock.csv"
  "${PSQL[@]}" <<'SQL'
CREATE TEMP TABLE _stock (serial_no text, sku text, account text, status text);
COPY _stock(serial_no,sku,account,status) FROM '/tmp/stock.csv' WITH (FORMAT csv, HEADER true);
-- Create any referenced accounts (parties) we haven't seen, then attribute each serial to its account. This is
-- the Conduit-owned attribution that replaces the MRPeasy serial->customer lookup (every serial knows its owner).
INSERT INTO party (display_name, party_type, is_organization)
SELECT DISTINCT s.account, 'wholesaler', true FROM _stock s
WHERE NOT EXISTS (SELECT 1 FROM party p WHERE p.display_name = s.account);

INSERT INTO serial_unit (serial_no, generation, product_variant_id, company_id, status)
SELECT s.serial_no, pv.generation, pv.id, p.id, COALESCE(NULLIF(s.status,''),'dispatched')
FROM _stock s
JOIN product_variant pv ON pv.sku = s.sku
JOIN party p ON p.display_name = s.account
ON CONFLICT (serial_no) DO UPDATE SET company_id = EXCLUDED.company_id, status = EXCLUDED.status;
SELECT count(*) || ' serials attributed across ' || count(DISTINCT company_id) || ' accounts' FROM serial_unit;
SQL
fi

echo "Import complete."
