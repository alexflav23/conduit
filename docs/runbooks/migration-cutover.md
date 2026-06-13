# Runbook — Migration & cutover (MRPeasy / Ghost Busters / Athena → Conduit)

Operator's hands-on companion to **spec/18** (the full field-level mapping, opening-balance derivation, and
go/no-go gates live there — read §6 and §7 before a real cutover). This is the sequence + the concrete
operations. Backed by `MigrationService` + `SyntheticOpeningLots` and proven by `MigrationCutoverSuite`.

## Principles (spec/18)
- **Idempotent backfill, dedupe on source id** (`migration_record`, spec/18 §3): every backfill is re-runnable;
  a re-run is a no-op. Backfill flows through the same write paths as live (no shadow path).
- **Opening balances are derived into TigerBeetle** (spec/18 §2) against `OPENING_BALANCE_EQUITY:<entity>` — the
  trial balance must net to zero (`MigrationService.openingTrialBalanceResidual` == 0).
- **Conserving allocation** (`SyntheticOpeningLots.reconcile`): when a reported stock total must be spread across
  synthesized opening lots, the parts sum to the reported total exactly (largest-remainder — no penny lost).
- **Cutover ties to a physical count to the penny** (spec/18 §5, `MigrationService.cutoverStockValidation`).

> Entrypoint note: `MigrationService` (`backfill` / `postOpeningBalances` / `ensureAccounts` /
> `openingTrialBalanceResidual` / `cutoverStockValidation`) is invoked from the `scripting/` module against the
> env transactor + TB client (spec/18 §3.4 "CLI surface"). Confirm the script exists before a live run; if not,
> it is a thin `scripting/` wrapper over these methods.

## Phases (with go/no-go gates — spec/18 §6)
1. **Extract & map.** Pull source extracts (MRPeasy customers/articles/lots/shipments, Athena product/order/serial,
   Ghost Busters activations). Build the `mrp_sku` ↔ `product_variant` map. *Gate:* every source row maps or is
   explicitly excluded with a reason.
2. **Backfill (idempotent).** Run `MigrationService.backfill` in dependency order (spec/18 §3.2): parties →
   catalogue/variants → lots/landed-cost → serials/genealogy → activations/warranty → shipments/orders → open orders.
   *Gate:* re-running the backfill changes nothing (dedupe on source id holds).
3. **Opening balances.** `ensureAccounts` then `postOpeningBalances` per entity/currency. *Gate:*
   `openingTrialBalanceResidual(accounts) == 0` — the opening trial balance nets to zero.
4. **Dual-run reconciliation** (spec/18 §4). Run Conduit in parallel with legacy; compare AR/inventory/activation
   counts against `precision` (the read-only ground-truth tool). *Gate:* drift within tolerance, explained.
5. **Cutover validation.** A physical stock count → `cutoverStockValidation` must tie to the penny.
   *Gate:* zero unexplained variance. **This is the point-of-no-return gate (spec/18 §7.3).**
6. **Go live.** Freeze legacy writes, flip the desk/API to Conduit, run `scripts/provision-pulsar.sh`, confirm the
   first live `OrderPlaced` flows end-to-end.

## Verify
- Opening trial balance residual = 0; `gl_vs_tb` control ties (the GL mirror == TigerBeetle, no drift).
- Backfilled counts match `precision` within the agreed tolerance (dual-run §4.2 comparison).
- A migrated invoice walks end-to-end in the Proof Center / lineage explorer (figure → ledger → events → document).

## Rollback (spec/18 §7)
- **Before the point-of-no-return** (phase 5 gate): stop, keep legacy authoritative, fix the mapping/backfill,
  re-run (idempotent). Conduit state is disposable up to this point.
- **After cutover:** forward-fix only — legacy is frozen. Use the [dlq-replay](./dlq-replay.md) and
  [projection-rebuild](./projection-rebuild.md) runbooks for any event-processing issues; correct data via the
  governed, audited write paths (never hand-edit the ledger).
