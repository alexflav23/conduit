# 11 — CRM (deep-dive)

Build-grade depth for the CRM subsystem. The **party/role model** is already specified in **doc 02 §C** — one `party`, data-driven `party_type`, attachable `billing_profile`/`credit_profile`, `parent_party_id` hierarchy (CEF master/branch), and `deal`/`pipeline`/`pipeline_stage`/`deal_line`/`activity`. This document **does not redefine** those tables; it specifies the *behaviour* layered on them and the few tables that extend them: deal **pipelines + probability weighting** feeding H6Q; **deal→order conversion** (close-won → `order.placed`, carrying ADLP pricing); the **account-history** projection built from the event stream; the **ownership model** (owner / account-manager per node + team rollup); party **merge/dedupe** (reattachment, audit, irreversibility); **promote-to-billable** validation policy per jurisdiction/`party_type`; and **consignment-stock-at-branch**. Events extend `crm.*` (doc 03); REST extends `/parties`, `/deals`, `/pipelines` (doc 06); replication to **HubSpot** closes the flow (doc 01 §2/§HubSpot).

Conventions per doc 00: every table has `id UUID PK DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL`, optional `deleted_at TIMESTAMPTZ`; money = `NUMERIC(18,4)` + `CHAR(3)`; `→ X` is an FK to `X.id`. `company_id` = a `party` of an organization `party_type`; `branch_company_id` = a branch `party` (doc 02 §C note). Custom attributes ride in the governed `attributes` JSONB bag (doc 02 §M); CRM money/state stays typed.

---

## A. Pipelines, stages & probability weighting

### A.1 Model (extends doc 02 §C `pipeline`/`pipeline_stage`/`deal`/`deal_line`)

`pipeline` and `pipeline_stage` already exist (doc 02 §C). CRM runs **one pipeline per channel** by default (`pipeline.channel_id`) — wholesale, distributor, energy, automotive and retail each get a stage set with its own `probability_pct` curve — and pipelines are **runtime data** (a new channel's pipeline is rows, not code, à la the channel taxonomy #12). The stage's `probability_pct` is the **deal-stage weight** that flows into H6Q (doc 04 §H6Q `weightedPipeline`).

Two extension columns are added to `pipeline_stage` for governance and lifecycle (additive; the core columns in doc 02 §C stand):

### `pipeline_stage` — added columns
| column | type | constraints | notes |
|---|---|---|---|
| stage_type | TEXT | NOT NULL DEFAULT 'open' | `open`/`won`/`lost` — the terminal kinds drive deal closure |
| is_default_open | BOOLEAN | NOT NULL DEFAULT false | the stage a new deal lands in |
| forecast_category | TEXT | NULL | `pipeline`/`best_case`/`commit`/`closed` — HubSpot-aligned rollup band (separate from `probability_pct`) |
| version | INTEGER | NOT NULL DEFAULT 1 | probability curves are versioned (changing a weight is audited, not silent) |

UNIQUE(pipeline_id, position). Exactly one stage per pipeline has `is_default_open=true`; at least one `stage_type='won'` and one `stage_type='lost'`. Index(pipeline_id, position).

### `deal` — added columns (extends doc 02 §C)
The doc 02 §C `deal` carries `pipeline_id`, `stage_id`, `owner_user_id`, `value`, `currency`, `expected_close`, `volume_p20/p50/p80`, `status`, `won_order_id`, scope. The deep-dive adds:
| column | type | constraints | notes |
|---|---|---|---|
| account_manager_user_id | UUID | → app_user NULL | inherited from `party.account_manager_user_id` at create; overridable per deal (ownership §D) |
| weighted_value | NUMERIC(18,4) | NULL | = `value × stage.probability_pct/100`, recomputed on stage change (cached projection, also derivable) |
| stage_entered_at | TIMESTAMPTZ | NOT NULL DEFAULT now() | resets on each stage change (drives age-in-stage / stalled-deal views) |
| lost_reason | TEXT | NULL | required when moving to a `stage_type='lost'` stage |
| close_won_at | TIMESTAMPTZ | NULL | set on win |
| source | TEXT | NULL | lead source (`hubspot`/`inbound`/`referral`/`manual`) — also mirrorable as an `attributes` key |

Index(stage_id), (owner_user_id, status), (account_manager_user_id), (company_id, status), (expected_close).

### A.2 Stage state machine

```
        ┌──────────────── (any open stage) ────────────────┐
new ──► default_open ──advance/regress──► open stage N ──► won  (stage_type='won')
            │                                   │
            └───────────────────────────────────┴────────► lost (stage_type='lost', lost_reason required)
```
- Transitions are **stage moves** within the deal's `pipeline`; a deal cannot move to a stage of another pipeline (re-pipeline = a distinct, audited action that resets `stage_entered_at`).
- Entering `stage_type='won'` runs **deal→order conversion** (§B) — it is the *only* path that sets `deal.status='won'` and `won_order_id`. A deal is never "won" without a placed order.
- Entering `stage_type='lost'` requires `lost_reason`; sets `status='lost'`. Lost deals are excluded from `weightedPipeline` but retained for accuracy/win-rate analysis.
- Re-opening a lost deal is a new stage move back to an open stage (audited; `close_won_at`/`lost_reason` cleared), not an edit of history.

### A.3 Probability weighting → H6Q

Each `deal_line` (doc 02 §C: `product_variant_id`, `qty`, `unit_price`, `currency`) contributes **per-SKU weighted pipeline qty** to H6Q coverage. This is the exact term doc 04 §H6Q `coverage()` already sums; CRM is its producer:

```
weightedPipelineContribution(deal):
  if deal.status != 'open': return []          // won → becomes actual orders; lost → excluded
  p = pipeline_stage[deal.stage_id].probability_pct / 100
  return deal.lines.map(dl =>
     WeightedDemand(
        variant      = dl.product_variant_id,
        channel      = deal.channel_id,
        sub_channel  = company(deal.company_id).attributes->>'sub_channel',
        segment      = company(deal.company_id).segment,
        market       = deal.market_id,
        company      = enclosingCustomer(deal.company_id),    // walk parent_party_id to the customer node
        branch       = deal.company_id,                       // the transacting node (may be a branch)
        agent        = deal.owner_user_id,
        period_month = monthOf(deal.expected_close),
        qty          = round(dl.qty × p)))                    // probability-weighted units
```

`crm.deal.created` / `crm.deal.stage_changed` / `crm.deal.won` / `crm.deal.lost` (doc 03) drive the **H6Q pipeline projection** consumer, which folds `weightedPipelineContribution` into `pipeline_coverage.weighted_pipeline_qty` (doc 02 §K) at every rollup level — dual-aggregated **by branch** (`parent_party_id` chain) and **by agent** (`owner_user_id`), exactly as doc 04 §H6Q `coverage()` specifies. Stage probability changes (versioned via `pipeline_stage.version`) re-weight all open deals on that stage by **replay** of `crm.deal.*` — no manual recompute. The deal scenario columns `volume_p20/p50/p80` (doc 02 §C) feed the P20/P50/P80 **scenario** lines independently of stage weighting (a deal owner's explicit scenario call), per doc 04 §H6Q `forecast_scenario`.

---

## B. Deal → Order conversion (close-won)

Winning a deal converts it to a **placed order** through the *same* order path as any other order (doc 04 §Orders) — no parallel codepath, so ADLP, credit, allocation, commission and ledger all behave identically. `deal_line` maps to `order_line`, carrying the **ADLP-resolved** pricing.

### B.1 Algorithm

```
convertDealToOrder(deal, actor):                          // POST /deals/{id}/win
  require authz(create, 'order', scope=deal.scope)        // doc 05; same gate as POST /orders
  require deal.status == 'open' AND targetStage.stage_type == 'won'
  require deal.lines.nonEmpty

  // 1. Resolve the order parties from the deal's company (CEF-aware, doc 02 §C / §F)
  soldTo  = deal.company_id                                // the transacting node — a branch for a wholesaler
  billTo  = resolveBillTo(soldTo)                          // soldTo.billing_profile.bills_to_party_id ?? soldTo (central billing)
  require billable(billTo)                                 // §E promote-to-billable; else 422 not-billable
  shipTo  = defaultShipToAddress(soldTo)

  // 2. Build order lines from deal lines, RE-PRICING through ADLP at conversion time (not the deal's stale price)
  orderLines = []
  for dl in deal.lines:
     pr = resolvePrice(dl.product_variant_id, deal.channel_id, deal.market_id,
                       deal.currency, deal.entity_id, dl.qty, now)          // doc 04 §Pricing
     line = priceLine({ variant: dl.product_variant_id, qty: dl.qty,
                        unit_price_ex_vat: dl.unit_price ?? pr.exVat },      // honour negotiated deal price if set, else list
                      account=soldTo, agent=deal.owner_user_id)             // doc 04 §ADLP — sets adlp_category, price_rule_id, vat
     orderLines.append(line)

  // 3. Place via the canonical order path — inherits the ADLP hold + credit gate verbatim
  order = placeOrder({ type: orderTypeFor(deal.channel_id),                 // trade/reseller/agent by channel
                       entity_id: deal.entity_id,
                       sold_to_party_id: soldTo, bill_to_party_id: billTo,
                       ship_to_address_id: shipTo,
                       customer_po_number: required?(soldTo) ? demand() : null,   // party.customer_po_required
                       channel_id: deal.channel_id, market_id: deal.market_id,
                       agent_id: agentFor(deal.owner_user_id), deal_id: deal.id,
                       currency: deal.currency, payment_method: paymentMethodFor(billTo),
                       lines: orderLines })                                  // doc 04 §ADLP placeOrder: ADLP hold + creditCheck

  // 4. Close the deal ONLY if the order actually placed (not held pending_ceo)
  if order.status == 'placed':
     deal.status = 'won'; deal.stage_id = targetStage.id; deal.won_order_id = order.id; deal.close_won_at = now
     emit crm.deal.won { deal_id, order_id: order.id, ... }                 // doc 03 carries order_id
  else:  // order.status == 'pending_ceo' (an ADLP exception line) — deal stays open, order held
     deal.attributes['pending_order_id'] = order.id                        // surfaced in UI; deal flips to won on exception approval
     // CEO approval of the exception releases placeOrder → a post-approval hook flips the deal to won + emits crm.deal.won
  return { order_id: order.id, status: order.status }
```

### B.2 Mapping table (`deal_line` → `order_line`)

| `deal_line` | → `order_line` | rule |
|---|---|---|
| `product_variant_id` | `product_variant_id` | direct |
| `qty` | `qty` | direct (single-shot; a scheduled call-off can be added at conversion via `schedule[]`, doc 04 §Orders tranches) |
| `unit_price` | `unit_price_ex_vat` | the **negotiated** price if set on the deal, else the ADLP-resolved `authorised_price`; either way re-validated against the live `price_rule` |
| `currency` | (order `txn_currency`) | from `deal.currency` |
| — | `discount_pct` | computed = `(pr.exVat − unit_price_ex_vat)/pr.exVat × 100` (doc 04 §ADLP) |
| — | `adlp_category` | `standard`/`exception` per band (doc 04 §ADLP) — an over-band deal line holds the *order* `pending_ceo` |
| — | `price_rule_id` | provenance from `resolvePrice` |
| — | `tax_regime`,`vat_amount`,`line_total_inc_vat` | computed at conversion |
| — | `commission_entry_id` | set by the commission consumer on `order.placed` (doc 04 §Commission) |

### B.3 Invariants
- **Re-price at conversion**, never trust the deal's cached `value`/`unit_price` blindly — the live `price_rule` governs `adlp_category` (a deal negotiated months ago may now breach the band → `pending_ceo`). `weighted_value` on the deal is a *forecast* cache, not the order total.
- A deal converts **at most once**: `won_order_id` is set-once; re-running `/win` on a won deal is a no-op returning the existing `order_id` (idempotent).
- The order carries `deal_id` (doc 02 §F) so H6Q can move the deal's weighted pipeline to **actual** on `order.placed` (doc 04 §H6Q pipeline→actual) and `pipeline_coverage` reconciles (no double-count: open weighted-pipeline minus, shipped/actual plus).
- Conversion is the **only** writer of `deal.status='won'`; manual status edits to `won` are rejected at the service layer.

---

## C. Account history (the `activity` timeline projection)

`activity` already exists (doc 02 §C: `subject_type`, `subject_id`, `kind`, `body`, `actor_user_id`, `occurred_at`, `event_id`). The deep-dive specifies it as a **projection of the event stream** plus first-class manual entries — the unified, scope-respecting timeline behind `GET /parties/{id}/history`.

### C.1 Two sources, one timeline
1. **Manual** activities: notes/calls/emails/meetings logged by staff (`event_id IS NULL`, `kind ∈ {note,call,email,meeting}`), created via `POST /parties/{id}/activities`.
2. **System-projected** activities: the **account-history consumer** subscribes to the event spine and writes `kind='system'` rows with `event_id` set (idempotent on `event_id` — doc 03 §3). Mapping:

| source event | activity row written |
|---|---|
| `crm.deal.created/stage_changed/won/lost` | subject=deal+company; body = "Deal moved to {stage}" / "Won → order {order_no}" / "Lost: {reason}" |
| `order.placed` | subject=company (sold-to); body = "Order {order_no} placed — {n} lines, {total}" (commercial layer) |
| `dispatch.created/delivered` | subject=company; body = "Dispatched {n} units / Delivered {tracking}" |
| `order.invoiced` | subject=company; body = "Invoice {invoice_no} issued" (commercial) |
| `return.raised/approved/refunded` | subject=company; body = "{type} return {status}" |
| `activation.recorded` | subject=company (resolved account); body = "Serial {serial} activated" (volume) |
| `crm.company.updated` | subject=company; body = field-level diff (mirrors `audit_log`) |
| party merge (§F) | subject=surviving company; body = "Merged {n} parties in" (audit-linked) |

The consumer is **idempotent on `event_id`**; replaying the log rebuilds the entire timeline (no manual rows lost — they have no `event_id` and are never re-derived).

### C.2 Read path & projection

```
accountHistory(principal, party_id, before, limit):       // GET /parties/{id}/history
  rows = activity WHERE subject_type='company' AND subject_id IN closure(party_id)   // node + branches (§D rollup)
                  AND occurred_at < before
                  ORDER BY occurred_at DESC LIMIT limit
  rows = rows.filter(r => layerVisible(principal, r))      // doc 05 §3: a 'commercial' body is stripped for volume-only viewers
  return project(principal, 'activity', rows) + next_cursor
```
- `closure(party_id)` walks `parent_party_id` **downward** so a master's history includes its branches' activity (toggle `?scope=node|tree`, default `tree` for the customer node, `node` for a branch).
- **Layering:** activity bodies carry an implicit data layer by `kind`/origin (order/invoice bodies = `commercial`; activation/dispatch counts = `volume`; commission notes = `commission`; contact bodies = `pii`). The serialiser strips non-viewable layers exactly as for typed fields (doc 05 §3) — a volume-only viewer sees the activation events but not the order totals.
- Index(subject_type, subject_id, occurred_at DESC) (already in doc 02 §C) carries the read; `(event_id)` UNIQUE WHERE event_id IS NOT NULL enforces idempotency.

---

## D. Ownership model (owner / account-manager per node, team rollup)

`party` already carries **`owner_user_id`** and **`account_manager_user_id`** *per node* (doc 02 §C) — a branch has its own. The deep-dive defines what they mean, how deals inherit them, and how they roll up.

### D.1 Semantics
- **`owner_user_id`** — the **commercial owner** of the relationship (the salesperson). Drives "my accounts" surfaces, the **by-agent** H6Q aggregation (doc 04 §H6Q), and `scopeMatches` breadth `own`/`team` (doc 05 §1).
- **`account_manager_user_id`** — the **servicing AM** (post-sale relationship). May differ from owner. Drives the AM's book-of-business view and weekly H6Q submission ownership (doc 04 §H6Q `openCycle` enumerates `accountsOwnedBy(owner)` over `owner_user_id` **or** `account_manager_user_id`).
- **Per-node:** a CEF master and each CEF branch can have *different* owners/AMs (a national-account owner on the master, regional AMs on branches). Deals inherit the *transacting node's* owner at create (`deal.owner_user_id ← party.owner_user_id`, overridable).

### D.2 Rollup & reassignment

```
ownershipRollup(customer_node):                  // for the "team book" and by-agent coverage
  nodes = closure(customer_node)                 // master + all branches (parent_party_id tree)
  byAgent = nodes.groupBy(n => n.owner_user_id)   // each branch counts to its own owner
  // H6Q by-agent (doc 04 §H6Q) sums orders/deals on owner_user_id; branch & agent views reconcile
  //   because every branch maps to exactly one owner.

reassignOwner(party_id, new_owner, actor, cascade):     // POST /parties/{id}/owner
  require authz(edit, 'party', scope=party.scope) AND actor holds edit:party:ownership
  old = party.owner_user_id
  party.owner_user_id = new_owner
  if cascade: for branch in children(party_id): branch.owner_user_id = new_owner   // optional subtree reassign
  // OPEN deals on the node follow ownership unless pinned: re-point deal.owner_user_id where deal.owner==old
  insert audit_log(party, 'owner_reassigned', before={old}, after={new_owner}, actor)
  emit crm.company.updated { ownership diff }            // → H6Q re-aggregates by-agent on replay; account-history row
```
- **Team rollup** uses `team.member_user_ids` (doc 02 §B): a manager with breadth `team` sees every account owned by a team member (doc 05 §1 `scopeMatches 'team'`). No separate ownership table — ownership is the two columns + the team membership array.
- Reassignment is **audited** (`audit_log` + `crm.company.updated`); H6Q by-agent coverage re-aggregates by replay, so the new owner's book and the old owner's book both reconcile to the same branch totals.
- Ownership changes do **not** rewrite historical commission (already-accrued entries keep their `agent_id`); they change *future* attribution and the live coverage views only.

---

## E. Promote-to-billable validation policy (per jurisdiction / party_type)

A party becomes billable by attaching a **valid** `billing_profile` (doc 02 §C). *Storage* is the fixed `billing_profile` schema; *what counts as valid* is a **data-driven policy** that varies by jurisdiction and `party_type` (e.g. tax-registration number mandatory where the jurisdiction requires it). This is enforced at promote time — `POST /parties/{id}/billing-profile` returns **422** if required fields are missing (doc 06).

### `promote_policy` — the validation registry (data, not code)
| column | type | constraints | notes |
|---|---|---|---|
| jurisdiction | CHAR(2) | NOT NULL | ISO country (matches `entity.jurisdiction`/`market.jurisdiction`) |
| party_type | TEXT | → party_type NULL | NULL = applies to all types in the jurisdiction |
| required_fields | TEXT[] | NOT NULL DEFAULT '{}' | `billing_profile` columns that must be present & non-empty (e.g. `{tax_registration_number, bill_to_address_id, payment_terms_days}`) |
| tax_reg_required | BOOLEAN | NOT NULL DEFAULT false | hard requirement for a tax-registration number |
| tax_reg_format | TEXT | NULL | regex the number must match (e.g. GB VAT, EU VIES shape) |
| min_payment_terms_days / max_payment_terms_days | INTEGER | NULL | term bounds (credit governance) |
| requires_credit_profile | BOOLEAN | NOT NULL DEFAULT false | some types/jurisdictions can't trade on terms without a credit profile (ties to `party_type.required_profiles`, doc 02 §C) |
| effective_from / effective_to | TIMESTAMPTZ | NOT NULL / NULL | versioned (a rule change is audited, not retro-applied) |

UNIQUE(jurisdiction, party_type, effective_from). Resolution: most-specific `party_type` wins over the NULL (all-types) row; latest effective.

### Algorithm

```
promoteToBillable(party, profileInput, actor):            // POST /parties/{id}/billing-profile
  require authz(edit, 'party', section='billing', scope=party.scope)
  juris  = entity(party.default_entity_id).jurisdiction
  policy = resolvePromotePolicy(juris, party.party_type, now)   // specificity + validity

  errors = []
  for f in policy.required_fields:
     if blank(profileInput[f]): errors.append({field:f, code:'required_for_jurisdiction'})
  if policy.tax_reg_required and blank(profileInput.tax_registration_number):
     errors.append({field:'tax_registration_number', code:'tax_registration_mandated', jurisdiction:juris})
  if profileInput.tax_registration_number and policy.tax_reg_format
       and !matches(profileInput.tax_registration_number, policy.tax_reg_format):
     errors.append({field:'tax_registration_number', code:'tax_registration_format'})
  if policy.requires_credit_profile and not hasCreditProfile(party) and not bills_to_other(profileInput):
     errors.append({field:'credit_profile', code:'credit_profile_required'})
  // party_type governance: required_profiles must be satisfiable (doc 02 §C)
  if party.party_type.required_profiles includes 'billing' is being satisfied here — ok
  if errors.nonEmpty: raise UnprocessableEntity(422, errors)   // doc 06 standard error

  bp = insert billing_profile(party_id=party, ...profileInput, status='active')
  party.roles = party.roles ∪ {'bill_to'}                  // capability tag now backed by a valid profile (doc 02 §C)
  insert audit_log(party,'promoted_to_billable', after={profile snapshot, policy_version}, actor)
  emit crm.company.updated { billable: true, profile_id: bp.id }
  return bp
```

- **Central billing (CEF):** a branch need not hold its own profile — it can set `bills_to_party_id` to the master (doc 02 §C). The policy then validates the **master's** profile completeness; the branch is "billable via parent". `resolveBillTo` (doc 04 §Credit/§Orders) walks to the payer.
- **Year-1 (UK only):** seed one `promote_policy(jurisdiction='GB', tax_reg_required=true, tax_reg_format=<GB VAT regex>, required_fields={tax_registration_number,bill_to_address_id,payment_terms_days})`. New markets are rows as they open (US sales-tax registration, EU VIES VAT, etc.) — no code change, mirroring the entity/tax seeding (doc 02 §A, decision #11).
- The same policy gate is invoked by **deal→order conversion** (§B `require billable(billTo)`) and by `placeOrder`'s credit path (doc 04 §Credit: "a party with no `credit_profile` and no card cannot place a credit order"). Promote-to-billable is the single chokepoint.

---

## F. Party merge / dedupe

Two `party` rows that turn out to be the same real-world entity (duplicate created at different touchpoints, a HubSpot import collision, a branch entered twice) are **merged** into one surviving party. Merge **reattaches** all transactional history, is **fully audited**, and is **irreversible** (you can re-split by creating new parties, but the merge itself is not undone — financial lineage must stay stable).

### `party_merge` — audit record (append-only)
| column | type | constraints | notes |
|---|---|---|---|
| surviving_party_id | UUID | → party NOT NULL | the winner (kept) |
| merged_party_id | UUID | → party NOT NULL | the loser (closed) |
| strategy | JSONB | NOT NULL | field-level survivorship decisions (which `display_name`, which profiles, etc.) |
| reattached | JSONB | NOT NULL | counts per object reattached `{orders:n, serials:n, deals:n, activities:n, contacts:n, ...}` |
| performed_by | UUID | → app_user NOT NULL | maker |
| approved_by | UUID | → app_user NULL | checker (maker≠checker; merge is permission-gated) |
| occurred_at | TIMESTAMPTZ | NOT NULL DEFAULT now() | |
| reversible | BOOLEAN | NOT NULL DEFAULT false | always false — recorded for clarity |

Index(surviving_party_id), (merged_party_id). `party.status` of the merged loser → `'merged'` (a new terminal value alongside `active/on_hold/closed`), `party.attributes['merged_into'] = surviving_party_id` so any stale reference resolves forward.

### Algorithm

```
mergeParties(survivor, loser, strategy, actor):           // POST /parties/{id}/merge  (id = survivor)
  require authz(edit, 'party', section='merge', scope) AND actor holds edit:party:merge
  require survivor != loser
  require not posted-financial-divergence:                // both must be reconcilable; see invariants
       survivor.default_entity_id == loser.default_entity_id   // never merge across legal entities
  require approval present (maker≠checker) — merge is maker-checker (doc 05 §5)

  in ONE transaction:
    // 1. Reattach every reference from loser → survivor
    UPDATE "order"        SET sold_to_party_id = survivor WHERE sold_to_party_id = loser
    UPDATE "order"        SET bill_to_party_id = survivor WHERE bill_to_party_id = loser
    UPDATE order_invoice  (via order) -- AR follows bill_to; ledger AR:<company> accounts re-tagged by projection, NOT rewritten in TB
    UPDATE serial_unit    SET company_id      = survivor WHERE company_id      = loser   // installed-base reattach
    UPDATE deal           SET company_id      = survivor WHERE company_id      = loser
    UPDATE contact        SET party_id        = survivor WHERE party_id        = loser
    UPDATE address        SET owner_id        = survivor WHERE owner_id        = loser
    UPDATE activity       SET subject_id      = survivor WHERE subject_type='company' AND subject_id = loser
    UPDATE forecast_*     SET company_id      = survivor WHERE company_id      = loser   // H6Q history follows
    UPDATE party          SET parent_party_id = survivor WHERE parent_party_id = loser   // re-parent the loser's branches
    UPDATE credit_profile -- if both held one: survivor's kept per strategy; loser's archived (audited), exposure recomputed (doc 04 §Credit)

    // 2. Apply field-level survivorship to the survivor (display_name, segment, roles ∪, attributes merged per strategy)
    apply strategy to survivor; survivor.roles = survivor.roles ∪ loser.roles
    survivor.external_refs = merge(survivor.external_refs, loser.external_refs)   // keep both HubSpot/UFE ids

    // 3. Close the loser, leave a forwarding pointer
    loser.status = 'merged'; loser.attributes['merged_into'] = survivor.id; loser.deleted_at = now

    // 4. Audit + history + counts
    counts = { orders, serials, deals, activities, contacts, ... }
    insert party_merge(survivor, loser, strategy, reattached=counts, performed_by=actor, approved_by=...)
    insert audit_log(party, 'merged', before={loser snapshot}, after={survivor snapshot})
    insert activity(subject=survivor, kind='system', body="Merged {loser.display_name} in ({counts})")

  emit crm.company.merged { survivor_id, merged_id, reattached: counts }   // → projections rebind, HubSpot dedupes, audit
  // H6Q / sell-through / coverage projections rebind by replay; ledger AR is re-tagged at the projection layer (TB transfers immutable)
```

### Invariants
- **Never across legal entities.** Merge requires the same `default_entity_id` — a UK party and a DE party are *not* the same SoR subject; merging would corrupt AR/tax. Cross-entity duplicates are handled by re-pointing orders, not party merge.
- **Irreversible by design.** TigerBeetle transfers are immutable (doc 04 §Ledger); merge re-tags the **Postgres AR projection** to the survivor but never rewrites posted transfers. `party_merge` + `audit_log` make the merge fully reconstructable (doc 05 §5 reconstruction guarantee). To "undo", create a fresh party and re-point — a new audited action, not a rollback.
- **Maker-checker.** Merge is permission-gated (`edit:party:merge`) and the approver ≠ performer (doc 05 §5), like stock adjustments — because it moves money lineage (AR per company, commission attribution).
- **Forwarding.** Every stale `merged_party_id` reference resolves via `attributes['merged_into']`; APIs return `301`-style `{merged_into}` on a GET of a merged party so clients re-point.
- **First-write-wins data (activations)** are reattached by `serial_unit.company_id` only; the immutable `activation` PK (`serial`, doc 02 §G) is untouched.

---

## G. Consignment stock at branch (ownership vs sell-through)

Some wholesaler/distributor **branches** hold **consignment stock**: physical units sit at the branch (or a customer site) but **Hypervolt retains ownership** until the branch **sells through** (draws the unit). Revenue/COGS recognise at **draw-down**, not at the physical move to the branch (ASC 606 — control has not transferred on consignment placement). This reuses the inventory spine (doc 02 §G, doc 04 §Ledger) — consignment is a **location owned by the entity but sited at the party**, plus a draw-down event that behaves like a delivery.

### `consignment_agreement` — the contract that authorises a consignment location
| column | type | constraints | notes |
|---|---|---|---|
| party_id | UUID | → party NOT NULL | the branch/customer holding the stock |
| location_id | UUID | → location NOT NULL | the consignment `location` (see below) |
| entity_id | UUID | → entity NOT NULL | owning Hypervolt entity (retains title) |
| max_value | NUMERIC(18,4) | NULL | optional consignment cap (credit-like) |
| currency | CHAR(3) | NULL | |
| replenish_policy | TEXT | NULL | `min_max`/`manual` — drives replenishment suggestions to top the branch back up |
| count_cadence | TEXT | NOT NULL DEFAULT 'monthly' | reconciliation/cycle-count cadence at the branch |
| status | TEXT | NOT NULL DEFAULT 'active' | `active`/`suspended`/`closed` |
| effective_from / effective_to | TIMESTAMPTZ | NOT NULL / NULL | |

The consignment site is a `location` (doc 02 §G) with `type='site'` (or a new `type='consignment'`), `entity_id` = the **owning Hypervolt entity** (so stock there is still *our* asset). Stock at it is ordinary `stock_item` / `serial_unit` — **owned by us** (`serial_unit.entity_id` = owner, `location_id` = the consignment site).

### Lifecycle (placement → draw-down → reconcile)

```
placeConsignment(agreement, variant, qty/serials):        // physical move, NO revenue, NO COGS
  // stock TRANSFER from a warehouse to the consignment location (doc 04 §Stock ops transfer)
  stock_movement(type='transfer_out', warehouse) + stock_movement(type='transfer_in', consignment_loc)
  serial_unit.location_id = consignment_loc   (entity_id UNCHANGED = still ours)
  // NO ledger revenue/COGS — title retained; inventory asset just relocates within the entity
  emit inventory.transfer.dispatched/received   (doc 03)   // location change only

drawConsignment(agreement, serials/qty, actor):           // the branch "sells through" → recognise
  // this is the consignment analogue of dispatch.delivered (doc 04 §Ledger single recognition point)
  create order(type='trade', sold_to=agreement.party, bill_to=resolveBillTo(party),
               ship_to = consignment_loc, lines from drawn serials/qty)   // priced via ADLP (§B path)
  for serial in drawn:
     serial_unit.status='dispatched'/'delivered'; relieve from consignment_loc
     DR COS_CLEARING:<entity>, CR INV:<entity> at serial's batch landed cost   // COGS recognised NOW
  DR AR:<bill_to>, CR VAT + revenue                                            // revenue recognised NOW
  auto-issue invoice (ASC 606 — recognition = draw-down)   (doc 04 §Orders/§Ledger)
  emit order.placed + dispatch.delivered + order.invoiced   // draw-down rides the standard order recognition path

reconcileConsignment(agreement, cycle):                    // periodic, maker-checker (doc 04 §Stock ops)
  cycle-count the consignment location; variance → stock_adjustment (shrinkage/found), audited
```

### Rules
- **Ownership vs sell-through is explicit:** units at the consignment location are **on Hypervolt's balance sheet** (inventory asset, `INV:<entity>`) until drawn. Sell-**in** (the physical placement) is **not** a sale and does **not** count to coverage as shipped revenue; **sell-through** (draw-down + downstream activation) is the sale. This sharpens doc 04 §H6Q `sellThrough`: for consignment branches, `sell_in` = *drawn* qty (not placed qty), so `overhang` measures stock physically at the branch but not yet drawn.
- **No double recognition:** placement emits only `inventory.transfer.*` (location change); draw-down emits `order.placed`/`dispatch.delivered`/`order.invoiced` (the *single* recognition point, doc 04 §Ledger). Costing stays specific-identification — the drawn serial carries its own lot's landed cost into COGS.
- **Replenishment & counts:** `replenish_policy` feeds `replenishment_suggestion` (doc 02 §H) to top branches back up; `count_cadence` drives maker-checker cycle counts at the consignment location (doc 04 §Stock ops) — shrinkage there is a `stock_adjustment`, not a silent loss.
- **Credit interaction:** `consignment_agreement.max_value` is a soft cap on un-drawn consignment exposure, complementary to the `credit_profile` limit (which governs *drawn*/invoiced exposure, doc 04 §Credit).
- **Reuses the spine** — no parallel inventory model. Consignment = (a) a location owned by the entity but sited at the party, (b) a draw-down that is an ordinary order through the §B/ADLP/ledger path.

---

## H. Events (extends doc 03 §CRM)

All CRM events publish to topic `conduit.crm`, partition by the listed key, idempotent on `event_id` (doc 03 §3). Custom `attributes` ride as the Avro `map<string,string>` field (doc 03 §2). Existing `crm.company.created/updated` and `crm.deal.created/stage_changed/won/lost` stand (doc 03); this section pins payloads and adds the merge event.

| event · key | payload (key fields) | producers | consumers |
|---|---|---|---|
| `crm.company.created` · `company_id` | `{ company_id, display_name, party_type, parent_party_id?, channel_id, market_id, segment?, owner_user_id, account_manager_user_id, roles[], attributes }` | party service | account-history projection, HubSpot replication, audit |
| `crm.company.updated` · `company_id` | `{ company_id, before, after (diffed fields incl. ownership/roles/billable flag), attributes }` | party service (incl. promote-to-billable §E, ownership reassign §D) | account-history, H6Q by-agent re-aggregate, HubSpot, audit |
| `crm.company.merged` · `survivor_id` | `{ survivor_id, merged_id, strategy, reattached:{orders,serials,deals,activities,contacts,...} }` | merge service (§F) | projection rebind (H6Q/coverage/sell-through), HubSpot dedupe, account-history, audit |
| `crm.deal.created` · `deal_id` | `{ deal_id, company_id, pipeline_id, stage_id, probability_pct, value, currency, volume_p20/50/80, owner_user_id, account_manager_user_id, expected_close, lines:[{variant, qty, unit_price}], channel_id, market_id, entity_id }` | deal service | H6Q pipeline projection (§A.3), account-history, audit |
| `crm.deal.stage_changed` · `deal_id` | `{ deal_id, from_stage, to_stage, probability_pct, weighted_value, stage_entered_at, actor }` | deal service | H6Q pipeline projection (re-weight), account-history, audit |
| `crm.deal.won` · `deal_id` | `{ deal_id, company_id, order_id, close_won_at, value, currency }` | deal conversion (§B) | H6Q (pipeline→actual), account-history, commission (via the resulting `order.placed`), HubSpot, audit |
| `crm.deal.lost` · `deal_id` | `{ deal_id, company_id, lost_reason, stage_id }` | deal service | H6Q (drop from weighted pipeline), win-rate/accuracy projection, account-history, audit |

> `crm.deal.won` carries `order_id` (doc 03 already states this); the resulting `order.placed` (doc 03 §Orders) is what actually drives allocation/commission/ledger — conversion does **not** invent a second order path. **Consignment** placement/draw-down reuse `inventory.transfer.*` and `order.placed`/`dispatch.delivered`/`order.invoiced` (doc 03 §Orders/§Inventory) — no new consignment events; the `consignment_agreement` is referenced via `ref_type='consignment'` on movements.

---

## I. REST (extends doc 06 §CRM)

Base `/api/v1`; auth + layer projection per doc 05; standard errors per doc 06. The existing `/parties`, `/deals`, `/pipelines` surface (doc 06) stands — these are the **additions/refinements** this deep-dive introduces.

```
# Pipelines & stages (§A)
GET    /pipelines                                     → [Pipeline{ channel_id, stages:[{id,name,position,probability_pct,stage_type,forecast_category,version}] }]
POST   /pipelines               { name, channel_id, stages:[{name, position, probability_pct, stage_type, forecast_category?}] }   → Pipeline   (governed; admin)
PATCH  /pipelines/{id}/stages/{stage_id}  { probability_pct?, position?, name? }   → Stage   (versioned + audited; emits no money — re-weights open deals by replay)

# Deals (§A/§B) — extends doc 06
GET    /deals?pipeline_id=&stage_id=&owner_id=&status=&company_id=     → [Deal]   (layer-projected; weighted_value on commercial layer)
POST   /deals                   { company_id, pipeline_id, owner_user_id?, value, currency, expected_close, volume_p20/50/80?, lines:[{sku, qty, unit_price?}], channel_id, market_id, entity_id, source? }   → Deal
PATCH  /deals/{id}              { stage_id?, value?, volume_p50?, lost_reason?, ... }   → Deal   (emits crm.deal.stage_changed; lost_reason required → lost stage)
POST   /deals/{id}/win          { schedule?:[{seq,qty,requested_date}] }   → { order_id, status }   // §B; 201 won | 202 pending_ceo | 422 not-billable
GET    /deals/{id}/lines ; POST /deals/{id}/lines ; DELETE /deals/{id}/lines/{line_id}        → DealLine[]

# Account history & activities (§C)
GET    /parties/{id}/history?before=&scope=node|tree    → [Activity]   (layer-projected timeline; system + manual)
POST   /parties/{id}/activities { kind: note|call|email|meeting, body, occurred_at? }   → Activity   (manual; event_id null)

# Ownership (§D)
POST   /parties/{id}/owner      { owner_user_id, cascade?:bool }            → Party   (edit:party:ownership; audited; re-aggregates by-agent)
POST   /parties/{id}/account-manager { account_manager_user_id }           → Party   (audited)

# Promote-to-billable (§E) — refines doc 06 POST /parties/{id}/billing-profile
POST   /parties/{id}/billing-profile  { billing_name, bill_to_address_id, tax_registration_number?, tax_regime_default, currency, payment_terms_days, invoice_locale, bills_to_party_id? }
                                  → BillingProfile   // 422 { errors:[{field, code, jurisdiction?}] } when promote_policy unmet
GET    /admin/promote-policies?jurisdiction=&party_type=     → [PromotePolicy]
POST   /admin/promote-policies  { jurisdiction, party_type?, required_fields[], tax_reg_required, tax_reg_format?, requires_credit_profile?, effective_from }   → PromotePolicy   (governed; admin)

# Merge / dedupe (§F)
GET    /parties/duplicates?q=&channel_id=&market_id=       → [{ candidate_group:[Party], score, reasons[] }]   // dedupe suggestions
POST   /parties/{id}/merge      { merged_party_id, strategy:{ field survivorship }, approved_by }   → PartyMerge   // id=survivor; edit:party:merge; maker≠checker; 409 if cross-entity
GET    /parties/{id}                                       → Party   // a merged party returns { status:'merged', merged_into } so clients re-point

# Consignment (§G)
GET    /parties/{id}/consignment                          → [ConsignmentAgreement{ location, on_hand, drawn_to_date, value }]
POST   /parties/{id}/consignment  { location_id?, max_value?, replenish_policy?, count_cadence? }   → ConsignmentAgreement   (creates/links the consignment location)
POST   /consignment/{id}/place    { variant?, qty?, serials?[] }          → StockTransfer   // location move only; NO revenue/COGS
POST   /consignment/{id}/draw     { serials?[], qty?, customer_po_number? }   → Order          // draw-down = recognition; rides order/ADLP/ledger path
POST   /consignment/{id}/reconcile { lines:[{variant, counted_qty, serials?[]}] }   → StockCount   (pending_approval; maker≠checker)
```

---

## J. HubSpot replication (closing the flow)

Conduit is the **source of truth**; HubSpot is a **consumer**, replicated to **at the end of the flow**, and **retained until Conduit is proven, then retired** (doc 01 §2/§3, decision #16). Replication is an **external adapter** subscribing to the layer-filtered `conduit.crm` projection (doc 05 §3 — external adapters never see restricted layers; PII flows only if the adapter is granted the `pii` layer).

```
hubspotReplicator (consumer group 'hubspot-crm', idempotent on event_id):
  on crm.company.created/updated → upsert HubSpot Company
       map: party.display_name→name, legal_name, party_type→type prop, segment, channel/market,
            owner_user_id→HubSpot owner, attributes→custom properties (governed keys only),
            external_refs.hubspot_id used as the upsert key (created on first push, stored back)
  on crm.deal.created/stage_changed/won/lost → upsert HubSpot Deal
       map: deal.stage_id→HubSpot dealstage (per-pipeline mapping table), value, currency,
            probability_pct→hs probability, expected_close→closedate, won→dealstage 'closedwon' + amount,
            owner_user_id→HubSpot deal owner; deal.company_id→associated Company
  on crm.company.merged → call HubSpot merge API on the two hs ids (survivor wins), or mark loser merged
  // contacts/activities replicate analogously; pii-layer fields (contact email/phone) only if adapter granted pii
```

- **One-directional, end-of-flow:** Conduit emits → projection → HubSpot. Conduit does **not** consume HubSpot mutations as truth (HubSpot is being retired); an optional inbound *import* path exists only for the **migration/dedupe** seed (parties created from a HubSpot export get `external_refs.hubspot_id`, then `/parties/duplicates` + `/merge` clean collisions — §F).
- **Idempotent & replayable:** keyed on `external_refs.hubspot_id`; redelivery upserts the same record. Rebuild = replay `conduit.crm` (doc 03 §3). When HubSpot is retired, the adapter is simply unsubscribed — **no core change** (doc 01 §2 external-adapter pattern).
- **Layer-respecting:** the adapter subscribes to a projection stripped to its granted layers (doc 05 §3) — margins/commission/inter-entity never leave for HubSpot; only CRM-appropriate `volume`/`commercial`/`pii` (if granted) data replicates.
- **Mapping contract** (deal stage ↔ HubSpot dealstage, custom-property names, owner-id mapping) is the per-integration contract flagged in doc 10 §E — pinned here as the mapping table above, finalised at M14.

---

## Acceptance

A CRM build is **done** when:

1. **Pipelines & weighting** — a deal on a stage with `probability_pct=40` contributes `round(line.qty × 0.40)` per-SKU into `pipeline_coverage.weighted_pipeline_qty` at every rollup level; changing a stage's probability (versioned, audited) re-weights all open deals on that stage **by replay** with no manual recompute; deal scenario `volume_p20/50/80` feed the P20/P50/P80 lines independently.
2. **Deal→order conversion** — `POST /deals/{id}/win` re-prices every `deal_line` through live ADLP, maps it to an `order_line`, and places via the **canonical** `placeOrder` path; a within-band deal places (`201`, `crm.deal.won` carries `order_id`); an over-band line holds the order `pending_ceo` and the deal flips to won only on CEO approval; a second `/win` is an idempotent no-op; H6Q moves the deal from weighted-pipeline to actual on `order.placed` with no double-count.
3. **Account history** — `GET /parties/{id}/history` returns one timeline of system-projected (event-derived, idempotent on `event_id`) + manual activities, scope-walked over branches, **layer-projected** (a volume-only viewer sees activations but not order totals); replaying the log rebuilds the system rows without losing manual ones.
4. **Ownership** — owner/AM are per-node; deals inherit the transacting node's owner; reassignment is audited and H6Q **by-agent** re-aggregates by replay so branch and agent views reconcile; team breadth surfaces a manager's whole team book (doc 05).
5. **Promote-to-billable** — attaching a `billing_profile` in a jurisdiction whose `promote_policy` mandates a tax-registration number returns **422** with field-level errors until supplied and format-valid; a branch with `bills_to_party_id` set is billable via a complete master profile; UK is seeded year-1, new markets are policy rows (no code).
6. **Merge/dedupe** — merging two same-entity parties reattaches orders/serials/deals/activities/contacts/forecasts to the survivor, closes the loser to `status='merged'` with a forwarding pointer, is **maker-checker** and **audited** via `party_merge` + `audit_log`, **never crosses legal entities** (409), never rewrites posted TigerBeetle transfers (AR re-tagged at the projection), and is reconstructable; H6Q/coverage rebind by replay.
7. **Consignment** — placing stock at a consignment location is a **location move only** (no revenue/COGS; stock stays the entity's asset); **draw-down** recognises revenue + COGS at the unit's specific batch landed cost through the standard order/ledger path and auto-invoices (ASC 606); sell-through counts **drawn** qty (placement is not a sale); reconciliation at the branch is maker-checker.
8. **HubSpot** — `crm.company.*`/`crm.deal.*` replicate one-directionally at end-of-flow via an idempotent, layer-respecting adapter keyed on `external_refs.hubspot_id`; margins/commission/inter-entity never leave; the adapter unsubscribes cleanly on retirement with no core change.

> Supports **M4-depth** (CRM/order-capture depth) and **M11** — CRM pipelines + stage probability feed **H6Q** (`weighted_pipeline_qty`), and deal→order conversion turns won deals into the actual orders that drive coverage, commission and the ledger.
