# 05 — Access Control, Data Layers & Audit

A single server-side **policy layer** authorises every request and projects every response. UI hiding alone is non-compliant. Identity comes from Keycloak (OIDC); the JWT `sub` maps to `app_user.keycloak_id`.

## 1. Authorisation decision (per request)

```
authorize(principal, action, objectType, target, section=None):
  grants = role_assignments(principal)                  // all scoped assignments
  applicable = grants.flatMap(g => g.role.permissions
                  .filter(p => p.object_type==objectType
                            && p.action==action
                            && (p.section is None || p.section==section)))
  if applicable empty: DENY
  // scope: target must fall within at least one applicable assignment's scope
  ok = applicable.exists(p => scopeMatches(p.assignment, target))
  return ok ? ALLOW : DENY
```

```
scopeMatches(assignment, target):
  breadth = assignment.breadth_override ?? min(p.data_breadth)
  switch breadth:
    'all'    -> true
    'own'    -> target.owner_user_id == principal.id
    'team'   -> target.owner_user_id in team(principal).members
    'scoped' -> (assignment.scope_entities empty  || target.entity_id  in scope_entities)
             && (assignment.scope_markets  empty  || target.market_id  in scope_markets)
             && (assignment.scope_channels empty  || target.channel_id in scope_channels)
```

"UK only" ⇒ assignment with `scope_markets=[UK]`, others empty. "UK wholesale only" ⇒ `scope_markets=[UK], scope_channels=[distributor]`. A user may hold several assignments (e.g. *UK wholesale: full* + *IE wholesale: view*); they union.

## 2. Query-time scope filtering

Reads are filtered at the data layer, not in the UI. Each scoped repository method appends a predicate built from the principal's grants:

```
scopePredicate(principal, objectType):
  // OR over assignments that grant `view` on objectType
  build "(entity_id IN (:e) OR :e_empty) AND (market_id IN (:m) OR :m_empty) AND (channel_id IN (:c) OR :c_empty)"
  // 'own'/'team' breadth adds owner_user_id predicates
```
List endpoints return only rows passing the predicate; counts/aggregates respect it too (no leakage via totals).

## 3. Data-layer projection (the units-vs-margins rule)

Every sensitive field is tagged via `field_layer_map(object_type, field) → data_layer`. After authorisation, the response serialiser **strips fields whose layer is not in the principal's `viewable_layers`** for that object/section.

```
project(principal, objectType, row):
  allowedLayers = union(viewable_layers over applicable view-permissions for objectType)
  for field in row.fields:
     layer = field_layer_map[objectType, field]   // None = unclassified = always visible
     if layer is not None and layer not in allowedLayers:
        omit field from output
  return row
```

- Layers: `volume` (units, coverage %, sell-through, stock counts), `commercial` (price, revenue), `profitability` (cost, margin, GP), `commission` (agent amounts), `pii` (contact details), `inter_entity` (transfer prices, `price_rule.surface='inter_entity'`), `treasury` (FX hedges, hedged rates, consolidated-reporting figures).
- **Pricing wall example:** Deal Desk preset = `view` on `price_rule` with `viewable_layers=[volume,commercial]` and **no** grant on the `inter_entity` section/layer → customer pricing visible, inter-entity invisible and absent from the payload.
- **H6Q example:** a `volume`-only role sees unit forecasts/pipeline/coverage; granting `commercial` adds revenue; `profitability` adds margin/GP — same board, composed from grants.
- Editing a layer requires the layer in `editable_layers`.
- Projection applies to **events too** when surfaced to external consumers: external adapters subscribe to layer-filtered projections, never raw events carrying restricted layers.
- **Custom attributes are layer-aware.** A governed custom property (doc 02 §M) carries an optional `property_definition.data_layer`; the serialiser projects keys *inside* the `attributes` bag by that tag, exactly as it does typed fields — an unclassified property is always visible, a `profitability`-tagged one is stripped for volume-only principals. Flexibility at the edge never bypasses the access wall.

## 4. Preset roles (seed; cloneable/editable in the builder)

The seed set Flv specified, plus the two that the workflow requires (CEO as the sole ADLP approver; Treasury for hedges). All are just data — the builder composes more.

| Role (seed) | Object grants (summary) | Layers | Scope default |
|---|---|---|---|
| `retail_sales_agent` | view/create retail order; view company/contact/catalogue/deal; submit own H6Q forecasts; view **own** commission (real-time) | volume, commercial, commission(own) | own accounts, market-scoped |
| `customer_service_agent` | view orders/customers; raise refund/RMA + warranty claims; serial/activation lookup | volume, commercial(view), pii | market-scoped |
| `fulfilment_agent` | inventory, allocation, dispatch, serial capture, stock counts | volume | location/entity-scoped |
| `tax_specialist` | tax regimes + registrations, invoices, tax/customs, ledger tax views (read) | volume, commercial, profitability(read), inter_entity(read) | all (read) |
| `finance` | ledger/AR/AP/intercompany/commission views, rebate budgets, reporting; **period close** (propose) | all layers | all |
| `admin` | manage users/roles/config; **cannot** approve ADLP exceptions or edit audit | — | all |
| *CEO/CFO* (required) | approve `adlp_exception`, edit `price_rule(all)`, **approve period close/lock, prior-period adjustments, FX-rate entry**, view all | all layers | all |
| *Treasury* (required) | manage `fx_hedge` + `exchange_rate` entry; consolidated reporting | volume, commercial, treasury | all |
| *auditor* (read-only) | **view financial truth + lineage + controls/reconciliations + audit log; edits nothing** (doc 14 §6); cannot see PII unless granted | volume, commercial, profitability, commission, inter_entity, treasury (all **view**) | all (read) |

Roles are data; the **permission builder** UI composes `permission` rows (object × action × section × layers × breadth) and assigns scoped `role_assignment`s.

**Segregation of duties on financial controls (doc 14 §4):** period **close/lock**, **prior-period adjustments**, **manual journals**, **FX-rate entry**, and **credit-limit changes** are all maker-checker — the proposer (finance/Treasury) cannot be the approver (CFO/CEO). Posting to a `locked` accounting_period is rejected at the ledger boundary regardless of role.

## 5. Audit

- `audit_log` is the projection of the staff-action event stream plus field-level before/after captured by mutating services. Append-only; **Admin cannot edit it**.
- Material actions always audited: order create/**amend** (pre-dispatch, permission-gated)/cancel, pricing change, ADLP exception request/decision (with `approval_memo_ref`), rebate posting, **stock count / transfer / adjustment / write-off (maker-checker)**, transfer-price derivation, FX rate change, dispatch, permission/role grant or revoke, data-layer grant change, access denials on sensitive sections.
- **Maker-checker (segregation of duties):** order amendment requires `edit:order:amend` (elevated/admin); stock adjustments, write-offs and cycle-count corrections require `approve:stock_adjustment` held by **someone other than the requester**. Returns/RMA approval (doc 09) follows the same pattern. The system rejects self-approval.
- Financial actions additionally anchored in TigerBeetle (immutable by construction).
- Reconstruction guarantee: any change to a price, discount, exception, commission, stock figure, order or permission is reconstructable from `audit_log` + the event log + TigerBeetle alone.

## 6. Enforcement invariants (NFR)

- Deny-by-default; authorisation server-side on every read and write.
- Scope predicates indexed (`entity_id`, `market_id`, `channel_id`) so filtering stays within the order-capture latency budget (<300ms p95).
- Revocation effective on next request (no cached allow).
- Tests: every endpoint has authz tests for (in-scope allow / out-of-scope deny / layer-stripped projection).
