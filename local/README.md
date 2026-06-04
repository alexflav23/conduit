# Conduit — local mode (hands-on validation)

One command brings up the whole backend in Docker (Postgres + TigerBeetle + the Conduit API with Flyway
migrating on boot), loads a dataset, and opens the desk so you can drive H6Q end to end and give feedback.

```bash
./local/run-local.sh            # demo dataset (default) — everything pre-wired to explore immediately
./local/run-local.sh --import   # load YOUR real H6Q + stock from local/import/*.csv instead
./local/run-local.sh --no-desk  # backend only (API on :8080), don't start the Vite desk
```

Requirements: Docker (Desktop running), `sbt`, and `yarn` (only if you want the desk). The runner builds the
API with `sbt api/stage` once, bakes it into a thin JRE image, and starts the stack via
`docker-compose.local.yml`.

## What comes up

| Thing        | URL / address                          | Notes |
|--------------|----------------------------------------|-------|
| API          | http://localhost:8080                  | the desk proxies `/api` here |
| Health       | http://localhost:9990/health           | returns `OK` once migrated |
| Metrics      | http://localhost:9464                  | Prometheus |
| Postgres     | localhost:5532 (user/pass/db = conduit)| `psql postgres://conduit:conduit@localhost:5532/conduit` |
| TigerBeetle  | localhost:3033                         | the immutable ledger |
| Desk         | http://localhost:3002                  | Vite dev server |

### Auth tokens

Local dev accepts `dev:<keycloak_id>` bearer tokens (no Keycloak round-trip). Paste one into the desk's
**Auth token** box:

- `dev:finance-e2e` — full money + ledger + coverage board + supply window + shelf (start here)
- `dev:agent-e2e` — a retail sales agent: captures their own accounts, sees volume only (no margin)
- `dev:ceo-e2e` — the single price-deviation approver (Deal Desk)

### A tour worth taking

1. **H6Q** — as `dev:agent-e2e`, submit a forecast for the open weekly cycle. The reconciliation invariant
   (Σ agent ≡ Σ branch ≡ market) holds on every submit.
2. **Flow** — as `dev:finance-e2e`, watch the variants evolve over time: **forecast → committed → delivered →
   shipped → revenue**. The ledger panel shows the actual TigerBeetle transfer ids for the ASC-606 posting.
3. **Supply** — the firm-commitment horizon per contract manufacturer (frozen / flex / free zones), the
   auto-PO proposals filled within headroom, and the divergence warnings against the frozen window.
4. **Shelf** — per-account serial positions (the Conduit-owned attribution that replaces the MRPeasy lookup):
   every serial knows its owner; activated units show as on-shelf.

Stop the stack: `docker compose -f docker-compose.local.yml down` (add `-v` to also wipe the volumes).

## Importing your real data (`--import`)

Two ways, in order of preference:

### A. Drop the finance workbook in directly (no conversion) — recommended

```bash
cp /path/to/H6Q.xlsx  local/import/h6q.xlsx
./local/run-local.sh --import
```

The importer reads the workbook *as-is*. It pulls the **monthly P50 volume per sales channel** (sheet
`Monthly P50 Inc Motability`) and the **SKU mix** (sheet `Overall Product Sales Mix`: 5m/7.5m/10m ×
White/Black/Grey), then does exactly the "unit count → per-SKU via historical mix" split you described: each
channel's monthly volume is allocated across the 9 SKUs by that channel's mix using the same **conserving
largest-remainder** allocation Conduit uses (the parts sum to the channel total, exactly), summed to a
market-level per-SKU monthly forecast. It auto-creates the 9 real SKUs (`HV-5M-W` … `HV-10M-G`) under a
`Hypervolt Charger` family and writes the `pipeline_coverage` rows the board reads.

- Top channels taken = the `x`-marked roll-ups (Retail, Installers, Energy, Distributors, Automotive) — they
  sum to UK Total, so leaves aren't double-counted. Verified: Sep-2026 splits to **14,132** across SKUs,
  matching the workbook's UK Total.
- **Inc vs Ex Motability**: by default the `Inc Motability` sheet → the P50 base scenario. Set
  `H6Q_EX_MOTABILITY=1 ./local/run-local.sh --import` to load `Ex Motability` → the **P50 ex_motability**
  ex-cut scenario (toggle on the board).
- First run creates a small Python venv at `local/.venv` (for `openpyxl`); both it and your `*.xlsx` are
  gitignored.

> The workbook is forecast-only — it has no serial numbers, so per-account **stock** comes from `stock.csv`
> (below). The demo `HV-310` forecast + its full forecast→shipped→revenue waterfall stay loaded alongside
> your real SKUs, so the Flow/ledger story still works (your imported SKUs are forecast-only until you also
> load shipments).

### B. CSVs (if you'd rather hand-curate)

Copy the templates (`local/import/h6q.csv.example`, `stock.csv.example`) to `h6q.csv` / `stock.csv`, fill in
your data, then run `./local/run-local.sh --import` (real `*.csv` are gitignored). If an `h6q.xlsx` is also
present, the workbook wins. The demo catalogue/markets/channels/scenarios load first so your rows resolve by
human-readable key; your data layers on top. Re-running is idempotent.

> CSV SKUs must already exist in the catalogue. Add variants to `conduit-desk/e2e/seed.sql` (or via the API)
> before importing CSV rows that reference them. (The `.xlsx` path creates its SKUs automatically.)

### `local/import/h6q.csv` — forecast quantities per SKU

One row per SKU per market per month. Becomes market-level coverage (the same shape the projector emits), so
it shows immediately on the Flow / Coverage board.

```csv
market,channel,sku,period_month,qty
United Kingdom,Retail,HV-310,2026-07-01,1200
United Kingdom,Wholesale,HV-310,2026-08-01,800
United Kingdom,Wholesale,HV-310,2026-09-01,950
```

- `market`, `channel` — informational only in local mode. Year-1 is UK-only and the desk pins a single
  demo market, so every imported row lands on that market (and is therefore visible on the board). Keep the
  columns for your own clarity / future multi-market.
- `sku` — must match a `product_variant.sku`.
- `period_month` — any date in the month; the first of the month is canonical.
- `qty` — integer units (the per-SKU raw quantity; H6Q always records per SKU).

### `local/import/stock.csv` — serial positions per account

One row per serial number. Attributes each serial to its account (party), creating the account if it doesn't
exist. This is the per-account real-time stock position that powers the Shelf tab.

```csv
serial_no,sku,account,status
HV310-0001,HV-310,Acme Electrical,activated
HV310-0002,HV-310,Acme Electrical,dispatched
HV310-0003,HV-310,Northern Wholesale,dispatched
```

- `serial_no` — unique; re-importing the same serial updates its account/status.
- `sku` — must match a `product_variant.sku`.
- `account` — the owning party; created as a wholesaler party if not already present.
- `status` — `dispatched` (in the field) or `activated` (live, shows on-shelf). Defaults to `dispatched`.

## Troubleshooting

- **API never goes healthy** — `docker logs conduit-local-api` (usually a migration or DB-connect issue).
- **Port already in use** — Athena occupies the defaults; this stack uses 5532/6651/3033/8080/9990/9464/3002.
  Stop the conflicting service or edit `docker-compose.local.yml`.
- **Rebuild after code changes** — re-run `./local/run-local.sh`; it re-stages and rebuilds the image.
- **Reset all data** — `docker compose -f docker-compose.local.yml down -v` then re-run.
