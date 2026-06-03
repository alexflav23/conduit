# 06 — API Contracts

REST over HTTP, JSON, defined with tapir (auto-generates OpenAPI + the TS client). Base `/api/v1`. All endpoints require a Keycloak bearer token; authorisation per doc 05. Money fields are `{ "amount": "587.5000", "currency": "GBP" }`. Standard errors: `400` validation, `401` unauthenticated, `403` unauthorised (no body detail that leaks scope), `404`, `409` conflict (e.g. allocation race, ADLP hold), `422` domain rule (e.g. credit block). Errors: `{ "error": "code", "message": "...", "details": {...} }`.

Conventions: list endpoints support `?limit&cursor&q&entity_id&market_id&channel_id&...` and return scope-filtered, layer-projected rows + `next_cursor`.

## CRM (parties)
```
GET    /parties?q=&type=&parent_id=&role=&channel_id=&market_id=   → [Party]   (layer-projected; type=installer|wholesaler|individual…)
POST   /parties                { display_name, party_type, is_organization, parent_party_id?, channel_id, market_id, segment? }   → Party
GET    /parties/{id}                                  → Party (type, roles, profiles, hierarchy refs)
GET    /parties/{id}/children                         → [Party]   (branches/divisions)
GET    /parties/{id}/rollup?metric=orders|sell_through|coverage&period=   → child rows + parent total
POST   /parties/{id}/billing-profile  { billing_name, bill_to_address_id, tax_registration_number?, tax_regime_default, currency, payment_terms_days, invoice_locale, bills_to_party_id? }
                                  → BillingProfile   // promotes the party to billable; 422 if jurisdiction-required fields missing
POST   /parties/{id}/credit-profile   { credit_limit, currency, terms_days, policy, scope }   → CreditProfile
POST   /parties/{id}/contacts                         → Contact
GET    /parties/{id}/history?before=                  → [Activity]
GET    /deals?pipeline_id=&stage_id=&owner_id=        → [Deal]
POST   /deals                                         → Deal
PATCH  /deals/{id}            { stage_id?, value?, volume_p50?... }   → Deal   (emits stage_changed)
POST   /deals/{id}/win        { }                     → { order_id }  (emits deal.won + order.placed)
GET    /pipelines                                     → [Pipeline{stages[]}]
```

## Catalogue & Pricing
```
GET    /catalogue?channel_id=&market_id=&currency=    → [VariantWithResolvedPrice]
GET    /variants/{sku}                                → ProductVariant
GET    /pricing/rules?surface=&variant=&channel=      → [PriceRule]   (inter_entity rows require layer)
POST   /pricing/rules           { surface, scope, authorised_price, max_discount_pct, ... }
                                  → PriceRule (status=draft; activation may require approval)
POST   /pricing/rules/{id}/activate                   → PriceRule     (governed/audited; emits pricing.rule.changed)
POST   /pricing/quote           { entity_id, company_id, channel_id, market_id, currency, lines:[{sku,qty,unit_price_ex_vat?}] }
                                  → { lines:[{ sku, resolved_ex_vat, max_discount_pct, applied_discount_pct,
                                               adlp_category, vat, line_total_inc_vat, commission_preview? }],
                                      totals, requires_exception:bool }
```

## Orders
```
POST   /orders                  { type, entity_id,
                                   sold_to_party_id,                          // ordering party (branch for a wholesaler, or individual)
                                   bill_to_party_id?,                         // payer; defaults via sold_to billing_profile.bills_to (central billing)
                                   ship_to_address_id?, customer_po_number?,  // PO required if party.customer_po_required
                                   contact_id?, channel_id, market_id, agent_id?, deal_id?, currency, payment_method,
                                   lines:[{sku, qty, unit_price_ex_vat?, discount_pct?,
                                           schedule?:[{seq, qty, requested_date}]}] }   // schedule = tranches
                                  → Order   // 201 placed | 202 pending_ceo (exception) | 422 credit block / not-billable
GET    /orders/{id}                                   → Order (lines, tranches, allocations, dispatches, invoices)
POST   /orders/{id}/amend       { changes:{ lines?, schedule?, ship_to? }, reason }
                                  → Order   // requires edit:order:amend; 409 if past amend_cutoff / dispatched; re-prices/re-allocates; records order_amendment
POST   /orders/{id}/cancel                            → Order
POST   /orders/{id}/allocate    { tranche_id? }       → Order   (idempotent; per line or tranche)
POST   /orders/{id}/dispatch    { tranche_id?, carrier_id, lines:[{line_id, qty, serials?[]}], tracking_no? }
                                  → Dispatch          (422 if serialised line missing serials)
POST   /orders/{id}/deliver     { dispatch_id }        → Delivery (auto-issues invoice; recognises rev+COGS — ASC 606)
// returns/RMA — first-class, full surface in doc 09:
POST   /orders/{id}/returns     { type: full_unit|part_only|multi_unit|dead_on_arrival|warranty_replacement|goodwill,
                                   scope, serials?[], component_ref?, reason_code, disposition? }   → Rma  (lifecycle in 09)
GET    /returns?status=&order_id=                     → [Rma]
POST   /returns/{id}/approve                           → Rma   (maker≠checker)
```

### ADLP exceptions
```
GET    /adlp/exceptions?status=pending_ceo            → [AdlpException]
POST   /adlp/exceptions/{id}/submit  { justification, volume_expectation, volume_denomination, strategic_importance, doc_refs }
POST   /adlp/exceptions/{id}/decision { decision: approve|reject, memo_ref }   // CEO only (403 otherwise)
```

## Reseller API (separate, JWT service principal)
```
POST   /v1/resellers/orders     (scoped service JWT; same order semantics, reseller pricing/tier)
```

## Inventory, Serials, Activation
```
GET    /stock?entity_id=&location_id=&variant=        → [StockItem{on_hand,allocated,available,incoming}]
POST   /stock/counts            { location_id, type, lines:[{variant, counted_qty, serials?[]}] }   → StockCount (status=pending_approval)
POST   /stock/counts/{id}/approve                     → StockCount  (maker≠checker; posts count_correction movements + ledger)
POST   /stock/transfers         { from_location_id, to_location_id, variant, qty, serials?[] }   → StockTransfer
POST   /stock/transfers/{id}/approve | /dispatch | /receive          → StockTransfer  (out → in_transit → in)
POST   /stock/adjustments       { variant, location_id, serials?[], qty, kind, reason_code, evidence? }   → StockAdjustment (status=pending_approval)
POST   /stock/adjustments/{id}/approve | /reject      → StockAdjustment  (maker≠checker; posts movement + ledger write-down)
GET    /serials/{serial}                              → SerialUnit + lifecycle[] + genealogy(batch, order, customer, activation)
GET    /batches/{batch_no}/serials                    → [SerialUnit] (recall/warranty lookup)
POST   /purchase-orders                               → PurchaseOrder
POST   /purchase-orders/{id}/receive { lines:[{po_line_id, qty, serials[], batch}] , landed_costs:[{type,amount}] }
                                  → GoodsReceipt   (lands cost; auto-allocates backorders per tranche)
// activations are ingested from Pulsar, not via REST
```

## H6Q
```
GET    /h6q/my-forecasts?cycle=current             → [{ company/branch, status, lines:[{variant, period, scenario, qty}] }]
       // the owner's accounts for this weekly cycle, pre-filled with last estimate + live catalogue (new SKUs included)
POST   /h6q/my-forecasts/{company_id}/submit       { cycle, lines:[{variant, period_month, scenario, qty}] }   → ForecastSubmission
GET    /h6q/cycles?status=open                      → [ForecastCycle]
GET    /h6q/outstanding?cycle=&market=&channel=     → [{ forecaster, accounts_outstanding }]   // who still owes
GET    /h6q/accuracy?forecaster=&company=&period=   → [{ forecast_qty, actual_qty, error, bias, mape }]
GET    /h6q/coverage?period=&scenario=P50&market=&group_by=&key=&ex_account=
       // group_by ∈ channel|sub_channel|segment|company|branch|agent ; key = the id at that level
                                  → [{ group_by, key, label, market, period, forecast, weighted_pipeline,
                                       shipped, activated, coverage_pct, coverage_ex_account_pct, wow_delta }]   (layer-projected)
GET    /h6q/coverage/{group_by}/{key}/children?period=&scenario=   // drill down one level (e.g. wholesaler → branches)
POST   /h6q/forecast            { channel_id, sub_channel_id?, segment?, company_id?, branch_company_id?, agent_user_id?, market_id, variant?, period_month, scenario, qty, source? }
                                  → ForecastEntry   (audited)
GET    /h6q/sell-through?company_id=|branch_id=&period=          → { sell_in, sell_through, overhang }
GET    /h6q/export?period=&format=xlsx                → file (output-only spreadsheet; layer-respecting)
```

## Warranty
```
GET    /warranty/exposure?entity_id=&as_of=&group_by=entity|family   → [{ key, open_units, outstanding, currency }]
GET    /warranty/provisions?serial=|batch=|status=    → [WarrantyProvision]
POST   /warranty/claims         { serial, description, cost, resolution }   → WarrantyClaim (draws down provision)
POST   /warranty/backfill       { from?, to? }         → job  (replay activations → rebuild register; admin)
```

## Treasury (hedge admin permission)
```
GET    /treasury/hedges?pair=&status=&entity_id=       → [FxHedge]   (treasury layer)
POST   /treasury/hedges         { pair_from, pair_to, instrument, contracted_rate, notional, entity_id, valid_from, valid_to, counterparty?, reference? }
PATCH  /treasury/hedges/{id}    { notional?, valid_to?, status? }
GET    /treasury/consolidated?period=&presentation=USD → consolidated exposure/translation (treasury layer)
```

## Commission
```
GET    /commission/entries?agent_id=&period=&status=  → [CommissionEntry]   (commission layer)
GET    /commission/statements/{agent_id}?period=      → Statement
```

## Admin / Access
```
GET    /admin/roles ; POST /admin/roles ; PATCH /admin/roles/{id}        (permission builder)
POST   /admin/users/{id}/assignments  { role_id, scope_entities[], scope_markets[], scope_channels[], breadth_override? }
GET    /admin/data-layers ; PATCH /admin/field-layers
GET    /audit?entity_type=&entity_id=&from=&to=        → [AuditLog]   (read-only)
```
