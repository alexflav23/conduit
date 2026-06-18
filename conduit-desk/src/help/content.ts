// The interactive user manual content (spec 38). One source of truth, bundled with the desk (no fetch, works
// offline, reviewed as code). A chapter is keyed to a real desk route so the parity test (parity.test.ts) fails
// the build if a screen has no chapter or a chapter points at a screen that no longer exists — the manual cannot
// drift from the product. `status` is an honesty badge (governed by the no-fake-data rule): never imply an
// unbuilt path works. `section` matches the nav group exactly so the Help index mirrors the rail. `screenshot`
// is a public/help-shots/<key>.png captured by e2e/manual-shots.spec.ts; `apiOps` lists the endpoints the screen
// calls (chapter↔API parity, M-Help.apiparity). Chapters authored against the live screens (2026-06-18).

export type ManualStatus = 'live' | 'partial' | 'shadow' | 'planned';

export interface ManualTask {
  title: string;
  steps: string[];
  note?: string;
}

export interface ManualChapter {
  id: string;            // stable slug, e.g. 'order-desk'
  route: string | null;  // the desk tab id it documents; null = a concept chapter
  section: string;       // 'Primer' | 'Sell' | 'Plan' | 'Supply' | 'Finance' | 'Treasury' | 'Govern'
  title: string;
  audience: string[];    // roles this is for (drives the training paths)
  status: ManualStatus;
  summary: string;
  concepts?: { term: string; def: string }[];
  tasks?: ManualTask[];
  related?: string[];    // related tab ids
  seeAlso?: string[];    // spec refs for the curious
  screenshot?: string;   // a /public/help-shots/<key>.png captured by e2e/manual-shots.spec.ts (docs-as-code)
  apiOps?: string[];     // endpoints this chapter documents (chapter↔API parity, M-Help.apiparity)
}

// ── Primer: how Conduit thinks (read first) ───────────────────────────────────────────────────────────
const PRIMER: ManualChapter[] = [
  {
    id: 'getting-started', route: null, section: 'Primer', title: 'Getting started — the desk',
    audience: ['all'], status: 'live',
    summary:
      'Conduit is Hypervolt’s master system of record. The desk is the back-office cockpit: a left rail of screens grouped by domain (Sell, Plan, Supply, Finance, Treasury, Govern, Learn), a top context bar (entity · market · period · scenario), and ⌘K to jump anywhere. What you can see and do is set by your role on the server — the rail only shows what you are granted.',
    concepts: [
      { term: 'Context bar', def: 'Entity, market, period and scenario at the top — every screen re-projects to it (it never mutates data).' },
      { term: '⌘K', def: 'Command palette — search screens, customers, and help chapters from anywhere.' },
      { term: 'Role', def: 'Your server-side grant. The desk reflects it; it never invents reach you do not have.' },
    ],
    tasks: [{ title: 'Find anything fast', steps: ['Press ⌘K', 'Type a screen name, a customer, or a serial', 'Enter to jump'] }],
    related: ['access'], seeAlso: ['doc 20 §2'],
  },
  {
    id: 'data-layers', route: null, section: 'Primer', title: 'Data layers — the layer wall',
    audience: ['all'], status: 'live',
    summary:
      'Conduit projects data by layer: volume, commercial, profitability, commission, inter_entity, pii. You see only the layers your role grants — and a layer you lack is ABSENT from the payload, not greyed out or zeroed. A volume-only viewer sees no money column at all (no £0, no blank). This is enforced on the server; the client never re-derives a hidden value.',
    concepts: [
      { term: 'Layer', def: 'A slice of fields (e.g. profitability = cost/margin). Grants are per role.' },
      { term: 'Absence, not hiding', def: 'Missing layers are not in the response — there is nothing to leak.' },
    ],
    related: ['access'], seeAlso: ['doc 05', 'doc 20 §10'],
  },
  {
    id: 'event-ledger-model', route: null, section: 'Primer', title: 'Events & the immutable ledger',
    audience: ['finance', 'auditor', 'admin'], status: 'live',
    summary:
      'Every business fact is an event. A write commits the business row AND an outbox row in one transaction; a relay publishes events to Pulsar in order; consumers react idempotently. Money posts to an immutable TigerBeetle ledger (one per currency) with deterministic transfer ids, so redelivery is a no-op and any figure re-derives by replay. Conduit observes via this log; it is how audit and reconstruction work.',
    concepts: [
      { term: 'Outbox', def: 'The business row + the event commit together — no dual-write drift.' },
      { term: 'Idempotent', def: 'Re-processing the same event changes nothing (keyed on event/natural id).' },
      { term: 'Replay', def: 'Re-run the log to reconstruct any projection or figure.' },
    ],
    related: ['audit', 'proof', 'lifecycle'], seeAlso: ['doc 01 §3a', 'doc 03', 'doc 14'],
  },
  {
    id: 'golden-record', route: null, section: 'Primer', title: 'The golden record (MDM topology)',
    audience: ['all'], status: 'live',
    summary:
      'Every customer the business has ever touched — across MRPeasy, HubSpot, the placement registry and Keycloak — is correlated into ONE master account (party), with its source identities linked (never silently merged below the confidence gate). Serials trace to the owner who holds them; orders carry a Conduit order id (CO-…) above their MRP/HubSpot source refs. The order is the top of the topology.',
    concepts: [
      { term: 'Master account', def: 'One party per real customer; source systems hang off it as links.' },
      { term: 'CO-ref', def: 'The Conduit order id — the golden record above MRP/PO/invoice/dispatch.' },
      { term: 'Serial → owner', def: 'A charger traces to the consumer who owns it, and back up the bulk-buyer chain.' },
    ],
    related: ['crm', 'batch'], seeAlso: ['doc 11', 'STATUS.md (CRM/MDM)'],
  },
  {
    id: 'money-fx-integrity', route: null, section: 'Primer', title: 'Money & FX integrity',
    audience: ['finance', 'auditor'], status: 'live',
    summary:
      'Money is a typed value with a currency — never a float (the build rejects floats in money paths). Cross-currency arithmetic is a compile error; only an explicit convert(rate) crosses, recording the rate + source. Costing is strict specific-identification (each serial carries its own lot’s landed cost — no averaging). FX on cost is spot by default, or the contracted rate when a hedge is designated (fx_basis=‘hedged’).',
    concepts: [
      { term: 'Specific-id cost', def: 'Each unit’s COGS = its own lot’s landed cost. No weighted average.' },
      { term: 'Rounding policy', def: 'Explicit per boundary (line/invoice/FX/posting), per tax regime.' },
      { term: 'fx_basis', def: 'spot (default) or hedged (a designated hedge’s contracted rate).' },
    ],
    related: ['treasury', 'batch'], seeAlso: ['doc 14 §1', 'doc 04 §FX'],
  },
  {
    id: 'shadow-mode', route: null, section: 'Primer', title: 'Shadow mode — how we run today',
    audience: ['all'], status: 'shadow',
    summary:
      'Conduit runs in shadow: it ingests everything from the live source systems, runs every engine and the immutable ledger, and reconciles against the sources — but takes NO outbound action (no write-back to HubSpot/ERP, no customer emails/invoices). Inbound data is never lost (durably captured before mapping; unmappable rows are quarantined, never dropped). Senior teams review the diffs for months before Conduit takes over as system of record.',
    concepts: [
      { term: 'Inbound never lost', def: 'Captured durably first; failures quarantine with the raw payload retained.' },
      { term: 'No outbound', def: 'Every business-affecting outward action is muted while in shadow.' },
      { term: 'Dual-run', def: 'Continuous derived-vs-source reconciliation is the product of the parallel window.' },
    ],
    related: ['sync', 'shadow'], seeAlso: ['doc 33', 'doc 36'],
  },
];

// ── Sell ───────────────────────────────────────────────────────────────────────────────────────────────
const SELL: ManualChapter[] = [
  {
    id: 'order-desk', route: 'order', section: 'Sell', title: 'Order Desk',
    audience: ['sales', 'ceo', 'finance'], status: 'live',
    summary:
      'Place and manage trade orders. Prices come ONLY from a governed price tier — nobody types a price; a non-tier price is rejected by the server. A compliant multi-line order places keyboard-only; an exception line holds the order pending CEO approval (no allocation, no commission) until the Deal Desk clears it. Orders fan out as events and become a Conduit order golden record (CO-…) you can open to see its full topology.',
    concepts: [
      { term: 'Governed price', def: 'The price binds to a tier in a price agreement — the server rejects a typed price.' },
      { term: 'Tranche', def: 'A delivery schedule slice (e.g. 500 as 2×250) that allocates/dispatches/invoices independently.' },
      { term: 'Exception line', def: 'A line needing a non-tier price — routed to the Deal Desk (maker-checker → CEO).' },
    ],
    tasks: [
      { title: 'Place a compliant order', steps: ['Pick the customer (or ⌘K search)', 'Add lines — SKU + qty; the tier price fills in', 'Set the delivery schedule (tranches if split)', 'Place — it emits OrderPlaced and mints the CO-ref'], note: 'A non-tier price is rejected server-side — raise an exception via the Deal Desk instead.' },
      { title: 'Open an order’s full topology', steps: ['Open the order (or click it from a customer)', 'See source refs (MRP/PO), line items, invoices, dispatches + tranches, recognition'] },
    ],
    related: ['dealdesk', 'pricing', 'crm'], seeAlso: ['doc 07 M4', 'doc 24'], screenshot: 'order-desk',
    apiOps: ['POST /api/v1/pricing/quote', 'POST /api/v1/orders'],
  },
  {
    id: 'deal-desk', route: 'dealdesk', section: 'Sell', title: 'Deal Desk — ADLP exceptions',
    audience: ['sales', 'ceo'], status: 'live',
    summary:
      'The maker-checker workflow for price-exception requests. A worklist of pending exceptions shows order, party, requested price, and the deviation below the authorised band; the detail drawer leads with the % overshoot, captures the maker’s narrative (volume + strategic rationale), and presents CEO-only decision fields. Approving mints a governed tier (validity window + minimum volume) and releases the held order; rejecting cancels it. Self-approval is blocked.',
    concepts: [
      { term: 'Price-tier exception', def: 'A request to sell below the ADLP band floor — always a governed tier, never an ad-hoc number.' },
      { term: 'Deviation', def: 'How far the requested price undershoots the band floor — the hero metric.' },
      { term: 'Maker-checker', def: 'Proposer (narrative) ≠ approver (CEO memo); the approve control is absent for the proposer.' },
    ],
    tasks: [
      { title: 'Submit a price proposal (maker)', steps: ['Open the exception from the queue', 'Enter the volume band (P20/P50/P80) and the strategic rationale + narrative', 'Submit — it routes to the CEO'] },
      { title: 'Decide an exception (CEO)', steps: ['Open the drawer; read the deviation + maker narrative', 'Enter the decision memo (immutable)', 'Set valid-to + minimum volume, then Approve (mints the tier, releases the order) or Reject (cancels it)'] },
    ],
    related: ['order-desk', 'pricing'], seeAlso: ['doc 24 §3', 'doc 07 M10'], screenshot: 'deal-desk',
    apiOps: ['GET /api/v1/adlp/exceptions', 'POST /api/v1/adlp/exceptions/{id}/submit', 'POST /api/v1/adlp/exceptions/{id}/decision'],
  },
  {
    id: 'pricing', route: 'pricing', section: 'Sell', title: 'Pricing (ADLP & agreements)',
    audience: ['ceo', 'finance', 'sales'], status: 'live',
    summary:
      'The governed price book: every price is a tier inside a price agreement (validity window, customer scope, volume bands) — retail open-list, an installers segment, and per-customer contract sets (Octopus/YESSS/CEF/Rexel…). A quote returns the correct ex/inc-VAT, the volume break, and the ADLP category. Inter-entity rules are layer-walled. Changing a price is a governed, audited, immediately-effective action — never a migration.',
    concepts: [
      { term: 'Price agreement', def: 'A contract container of tiers with a validity window and customer scope.' },
      { term: 'Volume band', def: 'A quantity tier (per-order / cumulative / retrospective rebate).' },
      { term: 'ADLP', def: 'Approved Distributor List Price — the governed category a quote resolves.' },
    ],
    tasks: [{ title: 'Check a customer’s price', steps: ['Open Pricing', 'Find the agreement (grouped by customer/segment)', 'Read the tier + volume bands — this is what an order will bind to'] }],
    related: ['order-desk', 'dealdesk'], seeAlso: ['doc 24', 'doc 07 M3'], screenshot: 'pricing',
    apiOps: ['POST /api/v1/pricing/quote'],
  },
  {
    id: 'returns', route: 'returns', section: 'Sell', title: 'Returns / RMA',
    audience: ['ops', 'finance'], status: 'live',
    summary:
      'The full RMA lifecycle — raised → assessed → approved → received → dispositioned → refunded. The worklist shows the RMA, its order, reason, type and refund; the detail drawer renders an immutable lifecycle timeline, a per-line table (serial · grade · disposition · landed cost), and the money consequences (refund, commission claw-back, credit note). Returned serials never silently re-enter sellable stock; money reverses at the unit’s specific batch cost, never by overwriting.',
    concepts: [
      { term: 'Disposition', def: 'Per-line handling: A-grade may restock; B/C must refurbish or scrap.' },
      { term: 'Credit note', def: 'Issued on refund; reverses unit cost at batch level, links to the GL.' },
      { term: 'Claw-back', def: 'Commission on a returned unit reverses via the two-phase lifecycle.' },
    ],
    tasks: [
      { title: 'Process a return', steps: ['Open the RMA; read reason + lifecycle', 'Assess & grade → Approve (memo) → Receive into the bay', 'Disposition each line, then issue the refund / credit note'], note: 'Maker ≠ checker on approval; a locked period blocks reversals.' },
    ],
    related: ['order-desk', 'batch', 'warranty'], seeAlso: ['doc 09', 'doc 07 M9b'], screenshot: 'returns',
    apiOps: ['GET /api/v1/returns', 'POST /api/v1/returns/{id}/assess', 'POST /api/v1/returns/{id}/refund'],
  },
  {
    id: 'crm', route: 'crm', section: 'Sell', title: 'CRM — accounts & customers',
    audience: ['all'], status: 'live',
    summary:
      'Browse the master account book. Customer types are first-class subroutes (installers, wholesalers, retail, consumers) each paginated; ⌘K searches the whole master by name · email · phone. A master account opens to one unified view: its source lineage, branches, the deal/PO book, and — for an org — its end-customers (each a real individual you can open). Click a serial to deep-link into Batch & Genealogy.',
    concepts: [
      { term: 'Master account', def: 'One party per real customer; HubSpot + MRPeasy + placement identities unified.' },
      { term: 'Branch', def: 'A sub-account under a parent (CEF-style hierarchy).' },
      { term: 'sold_via', def: 'The installer that sold/fitted a consumer’s charger (incl. phone-bridged links).' },
    ],
    tasks: [
      { title: 'Find a customer', steps: ['Press ⌘K', 'Type a name, email, or phone', 'Open their master account'] },
      { title: 'See an installer’s customers', steps: ['Open the installer account', 'The Customers panel lists the end-customers who got a charger through them', 'Click any to open that individual'] },
    ],
    related: ['order-desk', 'batch'], seeAlso: ['doc 11'], screenshot: 'crm',
    apiOps: ['GET /api/v1/crm/accounts', 'GET /api/v1/crm/accounts/{id}', 'GET /api/v1/crm/accounts/{id}/customers'],
  },
  {
    id: 'reseller-portal', route: 'reseller', section: 'Sell', title: 'Reseller portal',
    audience: ['admin'], status: 'shadow',
    summary:
      'A scoped external partner surface (catalogue of tier-governed quotes; the reseller’s own orders & invoices, scope-walled to their party — no internal cost/margin, no other resellers’ data). The screen is designed and routed but the backend isn’t wired yet, so it renders an honest “not available in this environment yet” rather than failing — it lights up when ResellerRoutes land.',
    concepts: [
      { term: 'Scoped session', def: 'A reseller sees only their own party’s pricing + orders; internal data is absent entirely.' },
      { term: 'Tier-governed quote', def: 'A reseller request must respect the tier floor — no ad-hoc exceptions.' },
    ],
    related: ['pricing'], seeAlso: ['doc 06'], screenshot: 'reseller',
  },
];

// ── Plan / Forecasting ───────────────────────────────────────────────────────────────────────────────
const PLAN: ManualChapter[] = [
  {
    id: 'demand-h6q', route: 'h6q', section: 'Plan', title: 'Demand (H6Q)',
    audience: ['sales', 'ceo', 'finance'], status: 'live',
    summary:
      'The whole demand picture across SKUs and months. Toggle the Coverage board (a SKU × month matrix grouped by scenario, layering human submissions over the model forecast) and My Forecast (an owner’s bottom-up capture form). Coverage reconciles forecast against shipped and activated; branch and agent totals must agree.',
    concepts: [
      { term: 'Scenario', def: 'P20 / P50 / P80 — conservative / base / optimistic demand bands.' },
      { term: 'Source: model vs human', def: 'Each row is tagged — model = the engine’s prediction, human = an owner’s capture.' },
      { term: 'Coverage', def: 'Pro-rata attainment: prior quarters actual÷forecast, current quarter QTD→EOQ run-rate.' },
    ],
    tasks: [
      { title: 'Submit your forecast', steps: ['Open My Forecast', 'Pick your account', 'Enter units per SKU + the band (P20/P50/P80)', 'Submit — it rolls up bottom-up to channel/market'] },
      { title: 'Read the matrix', steps: ['Toggle SKU / Account / Sector / Market', 'Expand a quarter to its months, or collapse to the quarter total'] },
    ],
    related: ['flow', 'supply', 'shelf', 'engine'], seeAlso: ['doc 12', 'doc 07 M11'], screenshot: 'demand-h6q',
    apiOps: ['GET /api/v1/h6q/coverage/matrix', 'GET /api/v1/h6q/coverage/reconcile', 'POST /api/v1/h6q/my-forecasts/{company_id}/submit'],
  },
  {
    id: 'flow', route: 'flow', section: 'Plan', title: 'Flow — demand-to-cash waterfall',
    audience: ['finance', 'sales'], status: 'live',
    summary:
      'The 7-stage waterfall for one variant: forecast → CM-committed → produced → delivered → ordered → shipped → revenue, showing the conversion and the gaps between stages. An evolution table shows the same demand ageing across three months; an immutable ledger (commercial layer) lists every invoice with its TigerBeetle transfer ids as proof.',
    concepts: [
      { term: 'Waterfall', def: 'Seven sequential states; a gap is the units lost between two stages.' },
      { term: 'Conversion %', def: 'The rate from one stage to the next (e.g. forecast → committed).' },
      { term: 'Transfer id', def: 'The TigerBeetle posting reference — click it to see DR/CR legs (proof, not a flag).' },
    ],
    tasks: [
      { title: 'Read a variant’s flow', steps: ['Pick the month + the variant', 'Read the stages left-to-right; conversion % and gap badges show the friction'] },
    ],
    related: ['demand-h6q', 'supply-window', 'shelf'], seeAlso: ['doc 26'], screenshot: 'flow',
    apiOps: ['GET /api/v1/h6q/waterfall', 'GET /api/v1/h6q/ledger'],
  },
  {
    id: 'supply-window', route: 'supply', section: 'Plan', title: 'Supply window',
    audience: ['ops', 'finance'], status: 'live',
    summary:
      'The contract-manufacturer supply horizon: a commitment ladder (frozen / flex / indicative zones per SKU and week), auto-PO proposals awaiting human approval, and loud divergence warnings when frozen-window demand moves against a firm PO. Approving a proposal refreshes all three views.',
    concepts: [
      { term: 'Commitment zone', def: 'Frozen (locked) · Flex (±tolerance) · Indicative (no constraint).' },
      { term: 'Net need', def: 'Demand − committed; a proposed delta fills it if within headroom.' },
      { term: 'Divergence', def: 'Demand moved but a firm PO can’t — surfaced as a danger/warn alert.' },
    ],
    tasks: [
      { title: 'Approve an auto-PO proposal', steps: ['Pick the contract manufacturer', 'Find an actionable proposal (proposed delta > 0)', 'Approve — the commitment ladder + warnings refresh'] },
    ],
    related: ['demand-h6q', 'flow', 'shelf', 'purchasing'], seeAlso: ['doc 26'], screenshot: 'supply-window',
    apiOps: ['GET /api/v1/h6q/supply/commitments', 'GET /api/v1/h6q/supply/proposals', 'POST /api/v1/h6q/supply/approve'],
  },
  {
    id: 'shelf', route: 'shelf', section: 'Plan', title: 'Shelf — on-shelf inventory',
    audience: ['ops', 'sales', 'finance'], status: 'live',
    summary:
      'Real-time per-account on-shelf stock from the serial register (shipped − activated = on-shelf), with runway days to the reorder point and sell-through (activation ÷ shipment). The board auto-sorts by who crosses reorder next; the KPI summary shows the “ghost fleet” (unactivated capital) and the age distribution of stock.',
    concepts: [
      { term: 'Ghost fleet', def: 'Dispatched-but-not-activated units — working capital at risk.' },
      { term: 'Runway days', def: 'On-shelf ÷ weekly run-rate — days until the reorder point is breached.' },
      { term: 'Sell-through', def: 'Activation ÷ sell-in — the real consumption signal, consignment-aware.' },
    ],
    tasks: [
      { title: 'Spot accounts crossing reorder', steps: ['The board is already sorted by runway', 'Read the red “reorder” badge', 'Click the account to drill into it'] },
    ],
    related: ['demand-h6q', 'flow', 'activation'], seeAlso: ['doc 26'], screenshot: 'shelf',
    apiOps: ['GET /api/v1/h6q/shelf', 'GET /api/v1/h6q/shelf-summary'],
  },
  {
    id: 'forecast-engine', route: 'engine', section: 'Plan', title: 'Forecast Engine',
    audience: ['finance', 'ceo'], status: 'live',
    summary:
      'The self-improving forecast’s glass box (read-only). A rolling-origin backtest scores every model per origin; the champion is the lowest mean-absolute-error model in the bake-off. Immutable origin records show the accuracy trend (forecast vs actual), the champion board, and per-segment outturn.',
    concepts: [
      { term: 'Origin', def: 'An immutable, idempotent forecast record — trained ≤ Q, predicts Q+1.' },
      { term: 'Champion', def: 'The lowest mean-absolute-error model, chosen per origin (not hardcoded).' },
      { term: 'Mean-abs-error', def: 'The accuracy metric — <12% ok, <18% warn, ≥18% danger.' },
    ],
    tasks: [{ title: 'Assess forecast credibility', steps: ['Read the accuracy-over-time chart (error % per origin)', 'Check the champion board and the rivals it beat'] }],
    related: ['runs', 'demand-h6q', 'flow'], seeAlso: ['doc 26'], screenshot: 'forecast-engine',
    apiOps: ['GET /api/v1/forecast/runs', 'GET /api/v1/forecast/runs/{origin}/report'],
  },
  {
    id: 'forecast-runs', route: 'runs', section: 'Plan', title: 'Forecast Runs',
    audience: ['finance', 'ceo'], status: 'live',
    summary:
      'The tournament’s run history + evolution narrative. Each origin row is immutable, idempotent and reproducible (pinned by data SHA). Open a report for segment outturn, champion mix, the model bake-off and provenance; “compare two runs” diffs how the forecast evolved (a narrative + per-segment/account deltas + champion changes), all computed server-side.',
    concepts: [
      { term: 'RunDiff', def: 'A pure server-side diff between two origins — the UI never recomputes it.' },
      { term: 'Narrative', def: 'A human-readable list of the material forecast changes between two runs.' },
      { term: 'Champion change', def: 'An account that switched its winning model between runs — flagged material.' },
    ],
    tasks: [
      { title: 'Compare two runs', steps: ['Pick a From and a To origin', 'Read the narrative', 'Browse the delta by segment / channel / market / account; “Why” shows the bake-off + depletion snapshot'] },
    ],
    related: ['engine', 'demand-h6q'], seeAlso: ['doc 26', 'doc 35'], screenshot: 'forecast-runs',
    apiOps: ['GET /api/v1/forecast/runs', 'GET /api/v1/forecast/runs/diff', 'GET /api/v1/forecast/runs/{origin}/report'],
  },
];

// ── Supply & Traceability ──────────────────────────────────────────────────────────────────────────────
const SUPPLY: ManualChapter[] = [
  {
    id: 'inventory', route: 'inventory', section: 'Supply', title: 'Inventory — ATP, serials, dispatch',
    audience: ['ops', 'finance'], status: 'partial',
    summary:
      'The operational stock + fulfilment surface across three views: the ATP board (on-hand − allocated = available, per variant × location), the serial register (serial-level genealogy by status), and the dispatch worklist (allocate serials → ship → deliver). A serialised line cannot ship without its serials. The dispatch worklist UI is in place; the ATP + serial views and the ship actions await the M6 backend (they render an honest “not available yet”).',
    concepts: [
      { term: 'ATP', def: 'Available-to-promise: on-hand minus allocated, per variant and location.' },
      { term: 'Serial lifecycle', def: 'in_stock → allocated → dispatched → delivered → returned.' },
      { term: 'Dispatch invariant', def: 'A serialised order can’t dispatch until specific serials are picked.' },
    ],
    tasks: [{ title: 'Search a serial', steps: ['Open the Serial view', 'Type the serial / batch', 'Filter by status; click a row for detail'] }],
    related: ['batch', 'purchasing', 'activation'], seeAlso: ['doc 07 M6'], screenshot: 'inventory',
    apiOps: ['GET /api/v1/inventory/availability', 'GET /api/v1/inventory/serials', 'POST /api/v1/orders/{id}/dispatch'],
  },
  {
    id: 'purchasing', route: 'purchasing', section: 'Supply', title: 'Purchasing — POs, receiving, stock ops',
    audience: ['ops', 'finance'], status: 'partial',
    summary:
      'The supply-in side: purchase orders to contract manufacturers (Volex/Luxshare), GRN receiving at rolled-forward landed cost, and governed stock operations (cycle-count / transfer / write-off) under two-person maker-checker, each immutably logged and ledger-posted. Freight and duty conserve into each unit’s landed cost, never averaged. PO list/detail are wired; the stock-ops maker-checker actions await the M9 backend surface.',
    concepts: [
      { term: 'Landed-cost roll-forward', def: 'Freight + duty allocated per unit of a tranche — specific-id, not averaged.' },
      { term: 'GRN', def: 'Goods-received note — books stock in at the tranche’s landed cost.' },
      { term: 'Maker-checker (SoD)', def: 'One proposes a stock op, a second approves; self-approval is hard-blocked.' },
    ],
    tasks: [{ title: 'Review a purchase order', steps: ['Open the POs view', 'Click a PO', 'Read its lines (expected vs received) + tranche freight/duty breakdown'] }],
    related: ['inventory', 'batch', 'supply-window'], seeAlso: ['doc 07 M9'], screenshot: 'purchasing',
    apiOps: ['GET /api/v1/purchasing/orders', 'GET /api/v1/purchasing/orders/{id}', 'POST /api/v1/purchasing/orders/{id}/receive'],
  },
  {
    id: 'batch-genealogy', route: 'batch', section: 'Supply', title: 'Batch & Genealogy',
    audience: ['ops', 'finance', 'auditor'], status: 'live',
    summary:
      'The bidirectional traceability spine — and the full unit page. Type a serial → walk it to its lot, CM purchase order, sales order, customer and activation, with its exact specific-id landed cost (factory USD ÷ FX → GBP + freight + duty), its lifecycle timeline, its replacement family and RMA tickets. Type a batch → see every unit it became (the recall roster). This answers the recall, warranty, and cost-of-this-exact-unit questions.',
    concepts: [
      { term: 'Specific-id cost', def: 'One unit’s exact landed cost — factory(USD) ÷ FX + freight + duty. No average.' },
      { term: 'Genealogy chain', def: 'serial → lot → CM PO → sales order → customer → activation → owner.' },
      { term: 'Replacement family', def: 'Units sharing one warranty window (original + replacements) and RMA history.' },
    ],
    tasks: [
      { title: 'Trace a unit', steps: ['Enter a serial in “Serial → genealogy”', 'Read the chain, the cost breakdown (incl. the FX step), the lifecycle, the family + RMAs'] },
      { title: 'Recall a batch', steps: ['Pick a batch', 'Read the serial roster — every unit the lot became, by status'] },
    ],
    related: ['inventory', 'warranty', 'activation'], seeAlso: ['doc 07 M7'], screenshot: 'batch-genealogy',
    apiOps: ['GET /api/v1/inventory/genealogy', 'GET /api/v1/serials/{serial}/lifecycle', 'GET /api/v1/inventory/batches'],
  },
  {
    id: 'activations', route: 'activation', section: 'Supply', title: 'Activations',
    audience: ['ops', 'sales', 'ceo'], status: 'live',
    summary:
      'The sell-through + after-sales surface. Charger activations ingest first-write-wins from the placement stream (the real “a unit went live”, distinct from dispatch); each opens a warranty provision that releases straight-line from the activation date. Three views: Capacity (connected-MW trend + forecast + a data-centre comparison), Live feed (a real-time SSE stream), and Analytics (day/week/month series + weekday pattern). The provision register itself awaits the M8 backend.',
    concepts: [
      { term: 'Sell-through vs sell-in', def: 'Dispatch is sell-in; activation is sell-through — the real demand signal.' },
      { term: 'Warranty clock', def: 'Starts at activation (not dispatch); releases straight-line over the term.' },
      { term: 'First-write-wins', def: 'The earliest activation timestamp wins; later versions never override it.' },
    ],
    tasks: [
      { title: 'Watch live activations', steps: ['Open the Live feed', 'Newest-first; click an owner to open their account', 'Browse past days with the day-navigation arrows'] },
      { title: 'Read capacity', steps: ['Open Capacity', 'Read connected-MW (actuals + forecast) and the “how fast is that?” comparison'] },
    ],
    related: ['batch', 'warranty', 'shelf'], seeAlso: ['doc 07 M8'], screenshot: 'activations',
    apiOps: ['GET /api/v1/activations', 'GET /api/v1/activations/capacity', 'GET /api/v1/activations/series'],
  },
  {
    id: 'warranty-rma', route: 'warranty', section: 'Supply', title: 'Warranty & RMA',
    audience: ['ops', 'finance'], status: 'live',
    summary:
      'The unit-replacement lifecycle, built from the real HubSpot RMA pipeline. A faulty unit → its exact replacement form a family that shares the original’s warranty window (the clock never resets); the browser tracks V2→V3, V3→V3 and V2→V2 replacements. It also classifies the free-shipment mix (COGS-without-revenue) and its monthly trend, and is the basis for the forward warranty liability (≈18% replacement rate).',
    concepts: [
      { term: 'RMA family', def: 'Original + all replacements share one warranty window — never reset.' },
      { term: 'V2→V3', def: 'A legacy unit replaced by the current product under warranty — a quality/cost signal.' },
      { term: 'Free-shipment class', def: 'Warranty / sample / R&D / return — COGS absorbed with no revenue, each by a transparent rule.' },
    ],
    tasks: [
      { title: 'Browse RMAs', steps: ['Search by ticket / serial / customer', 'Click a row → the unit’s full page (chain, cost, family, RMA history)'] },
    ],
    related: ['batch', 'activation', 'returns'], seeAlso: ['doc 07 M8', 'doc 14'], screenshot: 'warranty-rma',
    apiOps: ['GET /api/v1/warranty/rmas', 'GET /api/v1/warranty/rma-stats', 'GET /api/v1/warranty/provisions'],
  },
];

// ── Finance ────────────────────────────────────────────────────────────────────────────────────────────
const FINANCE: ManualChapter[] = [
  {
    id: 'finance', route: 'finance', section: 'Finance', title: 'Finance — P&L & cash',
    audience: ['finance', 'ceo', 'auditor'], status: 'partial',
    summary:
      'P&L projections by period and market (revenue ex-VAT, VAT, COGS, gross margin) plus a forward cash waterfall bucketing expected collections by due date. P&L sits behind the commercial layer; margin + COGS behind profitability — both collapse entirely (no £0) for a viewer without the layer. The credit-control terms editor is a stub for now.',
    concepts: [
      { term: 'Revenue ex-VAT', def: 'Recognised on dispatch (ASC-606) — the commercial-layer base.' },
      { term: 'Cash waterfall', def: 'Forward collections bucketed by each customer’s contractual due date.' },
      { term: 'Layer collapse', def: 'Absent a layer, the figure is gone — not shown as zero.' },
    ],
    tasks: [
      { title: 'Read the P&L', steps: ['It loads for the context period', 'Read line · layer · period amount'] },
      { title: 'Check forward cash', steps: ['Open the cash-waterfall card', 'Read expected receipts by due month + invoice count'] },
    ],
    related: ['backlog', 'commission', 'tax', 'audit'], seeAlso: ['doc 07 M13'], screenshot: 'finance',
    apiOps: ['GET /api/v1/finance/pnl', 'GET /api/v1/finance/cash-waterfall'],
  },
  {
    id: 'commission', route: 'commission', section: 'Finance', title: 'Commission',
    audience: ['sales', 'finance'], status: 'shadow',
    summary:
      'The two-phase commission engine — accrued (order placed) → posted (earned on dispatch) → clawed (unit returned, reversing in the current period). Agents see their own scope; finance/CEO see all, plus rebate accrual (ASC-606 variable consideration). The engine is wired but DORMANT: there are no real sales agents or schemes ingested yet, so it accrues £0 until a real comp-plan source lands — the screen says so honestly rather than showing fake numbers.',
    concepts: [
      { term: 'Accrued → posted → clawed', def: 'Booked at order, earned on dispatch, reversed on return (current period only).' },
      { term: 'Rebate accrual', def: 'Variable consideration — accrued against the floor, applied on true-up.' },
      { term: 'Dormant (honest)', def: 'No agents/schemes ingested yet → £0, shown as such, not a fabricated figure.' },
    ],
    tasks: [{ title: 'View a statement', steps: ['Open Commission', 'Own scope = your entries; all scope = grouped by agent', '(Lights up when a real agent/scheme source is ingested — S4)'] }],
    related: ['finance', 'backlog'], seeAlso: ['doc 07 M5', 'doc 36 §S4.1'], screenshot: 'commission',
    apiOps: ['GET /api/v1/commission/statement/{agentId}', 'GET /api/v1/commission/entries'],
  },
  {
    id: 'documents', route: 'docs', section: 'Finance', title: 'Documents (WORM)',
    audience: ['finance', 'auditor'], status: 'live',
    summary:
      'The write-once-read-many fiscal document store. Search invoices by number or order id and retrieve the sealed PDF; issue voids/credit-notes/refunds as paired reversing documents (the original is never edited — a linked reversal supersedes it). Refunds are maker-checker (you can’t approve your own); a locked period blocks all reversals.',
    concepts: [
      { term: 'WORM', def: 'Immutable, object-locked store — the original is never edited, only superseded.' },
      { term: 'Paired reversal', def: 'A credit note/void is a new linked document, preserving the audit trail.' },
      { term: 'Gapless numbering', def: 'Fiscal documents carry a gapless series (no skipped numbers).' },
    ],
    tasks: [
      { title: 'Find documents', steps: ['Enter an invoice number or an order id', 'Download the sealed PDF'] },
      { title: 'Void / refund', steps: ['Select the document', 'Pick the kind + reason', 'Issue — it mints a paired credit note (WORM); a second person approves a refund'] },
    ],
    related: ['finance', 'lifecycle'], seeAlso: ['doc 17', 'doc 07 M13-Docs'], screenshot: 'documents',
    apiOps: ['GET /api/v1/documents', 'GET /api/v1/documents/{id}/pdf', 'POST /api/v1/invoices/{invoice_no}/void'],
  },
  {
    id: 'lifecycle', route: 'lifecycle', section: 'Finance', title: 'Lifecycle — order event replay',
    audience: ['finance', 'auditor'], status: 'live',
    summary:
      'Event-sourced order reconstruction. Search an order id to replay its immutable event stream into two views: per-invoice collection cycles (total · paid · refunded · outstanding · void/replacement) and a chronological event timeline. Each event carries its origin (user/consumer/relay) and expands to its payload + any money figures (layer-tagged). The whole order rebuilds from events alone.',
    concepts: [
      { term: 'Collection cycle', def: 'One per invoice — total, paid, refunded, outstanding, void/replacement state.' },
      { term: 'Event origin', def: 'user (person) · consumer (machine) · relay (machine) — clarifies causation.' },
      { term: 'Timeline', def: 'Append-only, chronological, immutable — the single source of truth.' },
    ],
    tasks: [{ title: 'Replay an order', steps: ['Enter the order id', 'Read the cycles + the timeline', 'Expand an event for its money figures + payload'] }],
    related: ['documents', 'commission', 'event-ledger-model'], seeAlso: ['doc 01 §3a'], screenshot: 'lifecycle',
    apiOps: ['GET /api/v1/orders/{id}/lifecycle'],
  },
  {
    id: 'tax', route: 'tax', section: 'Finance', title: 'Tax — determination & governance',
    audience: ['finance', 'auditor'], status: 'partial',
    summary:
      'Explainable tax determination + governance. A tester resolves place-of-supply with a multi-level jurisdiction breakdown + reasoning (supply kind, reverse charge); an effective-dated rate table (propose → activate, two-person, self-activation blocked); provider routing; an economic-nexus board; a seller-of-record map; and a VAT exposure board (accrued − reversed − remitted = outstanding). The determination engine is built; the live surfaces render placeholders pending data.',
    concepts: [
      { term: 'Place of supply', def: 'Where the sale legally occurs — sets the liability + rate.' },
      { term: 'Reverse charge', def: 'VAT withheld; the buyer self-accounts in their jurisdiction.' },
      { term: 'Effective-dated rate', def: 'Rates are dated rows, never edited in place — a change is a new row.' },
    ],
    tasks: [{ title: 'Run a determination', steps: ['Enter ship-from / ship-to + buyer status + amount', 'Quote → read supply kind, reverse-charge, the per-component breakdown'] }],
    related: ['finance', 'documents'], seeAlso: ['doc 16', 'doc 07 M13-Tax'], screenshot: 'tax',
    apiOps: ['POST /api/v1/tax/quote', 'GET /api/v1/tax/rates', 'GET /api/v1/tax/vat/exposure'],
  },
  {
    id: 'backlog', route: 'backlog', section: 'Finance', title: 'Backlog — commitment ledger',
    audience: ['finance', 'ceo'], status: 'live',
    summary:
      'The sales-order commitment ledger (M4): every placed order commits revenue, recognition draws it down on dispatch, so committed = recognised + open. Open backlog is the order book yet to ship — the forward-revenue view. Read-only: a market-wide commitment table by entity, plus a per-order commitment lookup.',
    concepts: [
      { term: 'Committed', def: 'Total revenue from all placed orders (ex-VAT) — the upper bound on future recognition.' },
      { term: 'Recognised', def: 'Revenue drawn down at dispatch — the shipped portion.' },
      { term: 'Open backlog', def: 'Committed − recognised — the order book not yet shipped.' },
    ],
    tasks: [{ title: 'Check an order’s commitment', steps: ['Enter the order id in the lookup', 'Read committed / recognised / open + the status chip'] }],
    related: ['finance', 'commission', 'order-desk'], seeAlso: ['doc 07 M4'], screenshot: 'backlog',
    apiOps: ['GET /api/v1/finance/backlog', 'GET /api/v1/orders/{id}/commitment'],
  },
];

// ── Treasury / Group ───────────────────────────────────────────────────────────────────────────────────
const TREASURY: ManualChapter[] = [
  {
    id: 'intercompany', route: 'intercompany', section: 'Treasury', title: 'Intercompany — IC pairs & TP',
    audience: ['finance', 'ceo', 'auditor'], status: 'partial',
    summary:
      'The inter-entity cockpit (operating entity ↔ principal), walled to the inter_entity layer. It tracks bilateral IC pairs with dual-leg FX remeasurement (ASC-830 at spot) and transfer-pricing policy tiers under maker-checker approval. The IC-pairs + TP-policy surfaces are live; the hedge book (ASC-815 MTM) and §482 true-ups are planned (placeholders). Dormant on real data until procurement entities exist.',
    concepts: [
      { term: 'IC pair', def: 'Bilateral dispatch legs between entities; each remeasures at spot into its functional currency.' },
      { term: 'Transfer price', def: 'The per-unit cost the principal charges the operating entity — a dated policy tier.' },
      { term: 'FX basis', def: 'The rate’s source (spot ECB / broker / future), appended to every FX figure for audit.' },
    ],
    tasks: [{ title: 'Approve a TP tier (CFO)', steps: ['Open TP policy', 'Find the proposed tier (proposer ≠ you)', 'Approve — a new dated tier goes active'] }],
    related: ['procurement', 'treasury'], seeAlso: ['doc 13', 'doc 07 M12'], screenshot: 'intercompany',
    apiOps: ['GET /api/v1/intercompany/movements', 'GET /api/v1/intercompany/policies', 'POST /api/v1/intercompany/policies/{id}/approve'],
  },
  {
    id: 'procurement', route: 'procurement', section: 'Treasury', title: 'Procurement — principal/LRD',
    audience: ['finance', 'auditor'], status: 'partial',
    summary:
      'The SG-principal / LRD-operating-entity topology, walled to inter_entity. A central catalogue of per-variant transfer prices is enforced at dispatch; title flashes through the principal, an uplift margin is booked, and it unwinds to exactly zero on void/return (the conservation proof). The entity graph + catalogue (draft→activate) are live; the flash-title ledger lights up where procurement data exists (dormant — no procurement entities yet).',
    concepts: [
      { term: 'Procurement principal', def: 'Buys from the CM and on-sells at uplift to each LRD; holds title for an instant.' },
      { term: 'Flash-title', def: 'A matched pair of ledger legs at dispatch that cancel to zero on void.' },
      { term: 'Elimination group', def: 'The internal grouping that proves dual-entry + unwinding in the audit ref.' },
    ],
    tasks: [{ title: 'Activate a catalogue (checker)', steps: ['Open the central catalogue', 'Find the proposed version (you ≠ proposer)', 'Activate'] }],
    related: ['intercompany', 'treasury'], seeAlso: ['doc 28', 'doc 13'], screenshot: 'procurement',
    apiOps: ['GET /api/v1/group/structure', 'GET /api/v1/procurement/price-lists', 'POST /api/v1/procurement/price-lists/{id}/activate'],
  },
  {
    id: 'fx-hedging', route: 'treasury', section: 'Treasury', title: 'FX Hedging (Treasury)',
    audience: ['finance', 'ceo'], status: 'live',
    summary:
      'The provider-agnostic USD/GBP forward-contract program (Ebury today), protecting against payables volatility to the contract manufacturers. It runs on real production data: the facility (credit limit, pair, margin-call %), the hedge contracts (rates, notional, validity), the effectiveness stream (hedged vs counterfactual all-spot, monthly), and the policy (hedge ratios per exposure type). Effectiveness is rate STABILITY, not guaranteed savings.',
    concepts: [
      { term: 'Hedge ratio', def: 'The % of forecast USD exposure locked forward, by exposure type.' },
      { term: 'Effective rate', def: 'The blended GBP cost after hedge contracts + spot fills.' },
      { term: 'Volatility cut', def: '(1 − σ_hedged/σ_spot) — the stability the hedge bought, even if P&L is near-zero.' },
    ],
    tasks: [{ title: 'Review hedge performance', steps: ['Read the effectiveness table — spot £ vs hedged £ per month', 'Check the headline coverage + volatility-cut'] }],
    related: ['intercompany', 'money-fx-integrity'], seeAlso: ['doc 07 M12-Treasury', 'doc 04 §FX'], screenshot: 'fx-hedging',
    apiOps: ['GET /api/v1/treasury/program', 'GET /api/v1/treasury/effectiveness'],
  },
];

// ── Govern & Admin ─────────────────────────────────────────────────────────────────────────────────────
const GOVERN: ManualChapter[] = [
  {
    id: 'auditability', route: 'audit', section: 'Govern', title: 'Auditability Center',
    audience: ['finance', 'ceo', 'auditor'], status: 'live',
    summary:
      'The control room: period close (a two-step open→closed→locked gate), automated reconciliations, a SOX control register (controls re-perform live on click — green is earned, never cached), and a lineage explorer (invoice → ledger transfers → events → document). A period can’t lock with an open reconciliation, and the closer cannot also lock it (segregation of duties).',
    concepts: [
      { term: 'Close board', def: 'open → closed → locked; lock needs clean reconciliations + a different locker than closer.' },
      { term: 'Control register', def: 'Re-performable evidence; pass/fail history; green is earned on click, not cached.' },
      { term: 'Lineage', def: 'Figure → transfers → events → document (WORM) — and it re-derives by replay.' },
    ],
    tasks: [
      { title: 'Lock a period', steps: ['Select the period', 'Verify all reconciliations are matched', 'Lock (blocked if any are open, or if you closed it)'] },
      { title: 'Re-run a control', steps: ['Open the control register', 'Open a control', 'Re-perform now — the result + violations update live'] },
    ],
    related: ['period', 'proof', 'shadow', 'event-ledger-model'], seeAlso: ['doc 14 §6', 'doc 32'], screenshot: 'auditability',
    apiOps: ['GET /api/v1/finance/periods', 'POST /api/v1/finance/periods/{id}/lock', 'GET /api/v1/finance/controls', 'GET /api/v1/finance/lineage'],
  },
  {
    id: 'period', route: 'period', section: 'Govern', title: 'Period — group close roll-up',
    audience: ['finance', 'ceo', 'auditor'], status: 'live',
    summary:
      'The group close roll-up (ASC-810 coterminous): one accounting period end-to-end — the entity close gate (how many of N entities are locked), the trial-balance shape, business events, controls, reconciliations, documents issued, and the CM→PO journal walks. The group can’t lock until every operating entity is locked; the laggards are named upfront. Conservation (Σdebits = Σcredits) is recomputed in the browser, not trusted from a flag.',
    concepts: [
      { term: 'Roll-up gate', def: 'The group period can’t lock until 100% of entities are locked — laggards named.' },
      { term: 'Period assignment', def: 'Re-projected from the UTC instant, never a stored stamp.' },
      { term: 'Conservation check', def: 'Σ debits = Σ credits, recomputed client-side as proof.' },
    ],
    tasks: [{ title: 'Check group readiness', steps: ['Pick the period', 'Read entities-locked N / M', 'If < M, read the laggards and chase their close boards'] }],
    related: ['audit', 'proof'], seeAlso: ['doc 32', 'doc 14 §6'], screenshot: 'period',
    apiOps: ['GET /api/v1/finance/periods/{key}/investigation', 'POST /api/v1/finance/group-periods/{key}/lock', 'GET /api/v1/finance/lineage'],
  },
  {
    id: 'sync', route: 'sync', section: 'Govern', title: 'Sync — parallel-run health',
    audience: ['finance', 'auditor', 'admin'], status: 'live',
    summary:
      'The shadow parallel-run health monitor. Per source/dataset (Xero, HubSpot, MRPeasy, Athena, Stripe): cursor position, lag, records written, consecutive failures, last error — auto-polled every 30s. A hero card reads “in step with reality” (all green) or “drift detected”. A stale stream (lag > 1h) or rising failures are the early warning that precedes a reconciliation exception.',
    concepts: [
      { term: 'Stream health', def: 'ok / stale / error — ok = last status ok, no failures, lag < 1h.' },
      { term: 'Lag', def: 'Seconds since the last successful run for that source/dataset.' },
      { term: 'Cursor', def: 'The ingest checkpoint — the resume position in the source stream.' },
    ],
    tasks: [{ title: 'Spot a stale stream', steps: ['Read the hero card', 'Scan for lag > 1h or fails > 0 (bold red)', 'Open the row, read the last error, run the dlq-replay/rebuild runbook'] }],
    related: ['shadow', 'audit'], seeAlso: ['doc 33', 'doc 36'], screenshot: 'sync',
    apiOps: ['GET /api/v1/finance/sync-state'],
  },
  {
    id: 'shadow-validation', route: 'shadow', section: 'Govern', title: 'Shadow validation',
    audience: ['finance', 'auditor', 'ceo'], status: 'live',
    summary:
      'The cutover gate — the shadow validation battery. Discrepancies between Conduit’s computed reality and the source figures, ranked by severity (critical/high/medium/low) and triaged by status (open/investigating/accepted/resolved). Run on demand; the consumer re-runs every 6h. The queue is worked to zero before go-live; each finding shows the check, the scope, the money variance and the triage actions.',
    concepts: [
      { term: 'Finding', def: 'One discrepancy: expected vs actual, a severity, a status, a detected-at.' },
      { term: 'Severity', def: 'critical/high block the gate; medium/low are for review/accept.' },
      { term: 'Variance', def: 'Actual − expected; critical/high money variances must be resolved before cutover.' },
    ],
    tasks: [
      { title: 'Triage a finding', steps: ['Filter to critical + open', 'Investigate → Accept (known) or Resolve (fixed)', 'Re-run validation to re-check'] },
    ],
    related: ['sync', 'audit'], seeAlso: ['doc 33 §5', 'doc 36 §S3'], screenshot: 'shadow-validation',
    apiOps: ['GET /api/v1/shadow/summary', 'GET /api/v1/shadow/findings', 'POST /api/v1/shadow/validate', 'POST /api/v1/shadow/findings/{id}/triage'],
  },
  {
    id: 'proof-center', route: 'proof', section: 'Govern', title: 'Proof Center',
    audience: ['auditor', 'finance', 'ceo'], status: 'live',
    summary:
      'Interactive formal proof. The law register (controls re-run live on click — green is earned, never cached); the ASC-606 five-step recognition walk for an order (each step pinned to a law/control, with a principal/LRD overlay); the journal walk with conservation recomputed in the browser; trial-balance ties per entity; and a tamper sandbox (admin, non-prod only) — corrupt the books, watch a control catch and name the break, then restore to green.',
    concepts: [
      { term: 'Law pin', def: 'A re-performable control within a law — result + violations + ran-at, earned on click.' },
      { term: 'Conservation', def: 'Σ debits = Σ credits, recomputed in the browser from the legs — proof, not a flag.' },
      { term: 'Tamper sandbox', def: 'Non-prod: inject corruption → the control fails and names it → restore reverses it.' },
    ],
    tasks: [
      { title: 'Walk recognition for an order', steps: ['Open the ASC-606 walk', 'Enter the order id', 'Read the five steps + their law pins (+ inter-entity overlay if granted)'] },
    ],
    related: ['audit', 'period'], seeAlso: ['doc 31', 'doc 30'], screenshot: 'proof-center',
    apiOps: ['GET /api/v1/proof/laws', 'POST /api/v1/proof/controls/{code}/run', 'GET /api/v1/proof/asc606/{orderId}', 'POST /api/v1/proof/tamper/{kind}'],
  },
  {
    id: 'access', route: 'access', section: 'Govern', title: 'Access — permissions',
    audience: ['admin', 'ceo'], status: 'live',
    summary:
      'The permission governance room. The preset role catalogue (admin-only read); the effective-policy matrix for the signed-in principal (CRUD actions × object types, with the structural rule that edit ⊆ view); the data layers this role sees (money collapses, never £0, for a withheld layer); and a view-as preview of what a session can open. The matrix always shows YOUR grants — server-side truth, not a client guess.',
    concepts: [
      { term: 'Permission', def: 'An action:object grant (e.g. view:invoice, edit:period).' },
      { term: 'edit ⊆ view', def: 'Any edit/approve/delete requires the matching view — enforced server-side.' },
      { term: 'Preset role', def: 'An immutable policy template; custom roles are mutable; composed into the wall.' },
    ],
    tasks: [{ title: 'Check my permissions', steps: ['Open Access', 'Read the effective matrix (your grants)', 'Read the data layers — which money you see; withheld = absent'] }],
    related: ['audit', 'getting-started', 'data-layers'], seeAlso: ['doc 05', 'doc 06'], screenshot: 'access',
    apiOps: ['GET /api/v1/access/me', 'GET /api/v1/admin/roles'],
  },
  {
    id: 'notifications', route: 'notifications', section: 'Govern', title: 'Notifications',
    audience: ['all'], status: 'partial',
    summary:
      'The in-app notification feed (the bell’s full surface): recent rows with subscription, channel, event type, subject, body and status. Only events above a server-side materiality threshold reach the bell (it never cries wolf); a status of “suppressed · shadow” means it was muted in the shadow run. The feed is live; the subscriptions admin and the delivery worklist are not built yet (honest placeholders).',
    concepts: [
      { term: 'Materiality threshold', def: 'A server gate — only events above it reach the bell.' },
      { term: 'Channel', def: 'in_app / email / webhook.' },
      { term: 'suppressed · shadow', def: 'A notification muted because Conduit is in shadow (no outbound).' },
    ],
    tasks: [{ title: 'Read the feed', steps: ['Open Notifications', 'Read the rows (subject, body, event type, status)'] }],
    related: ['shadow-mode'], seeAlso: ['doc 21', 'doc 10 §B'], screenshot: 'notifications',
    apiOps: ['GET /api/v1/h6q/notifications'],
  },
];

export const CHAPTERS: ManualChapter[] = [...PRIMER, ...SELL, ...PLAN, ...SUPPLY, ...FINANCE, ...TREASURY, ...GOVERN];

// Every desk screen now has a chapter — the PENDING allowlist is empty (route↔chapter parity is total).
export const PENDING_CHAPTERS: string[] = [];

export const SECTION_ORDER = ['Primer', 'Sell', 'Plan', 'Supply', 'Finance', 'Treasury', 'Govern'];

// Role-based training curriculum (spec 38 §5): ordered chapter sequences per role. Paths reuse chapters, never
// duplicate. The Help screen tracks completion locally and exports any path (or the whole book) to PDF.
export interface LearningPath {
  id: string;
  role: string;
  blurb: string;
  chapters: string[]; // chapter ids, in teaching order
}

// Guided tours (spec 38 §M-Help.3): a scripted walkthrough that NAVIGATES the real desk screen-by-screen with
// an explanatory step card. Non-mutating — tours explain the flow on the live screens; they never place an order
// or post anything. Driven by the TourOverlay mounted in the shell.
export interface TourStep { route: string; title: string; body: string }
export interface GuidedTour { id: string; title: string; steps: TourStep[] }

export const TOURS: GuidedTour[] = [
  { id: 'place-order', title: 'Place a compliant order', steps: [
    { route: 'crm', title: 'Find the customer', body: 'Start in CRM — ⌘K or browse to the account you’re selling to. Everything ties back to this master record.' },
    { route: 'order', title: 'Build the order', body: 'On the Order Desk, add a SKU + qty. The governed tier price fills in automatically — nobody types a price.' },
    { route: 'order', title: 'Schedule & place', body: 'Set the delivery schedule (tranches if it’s split), then Place. It mints the CO- order id and fans out events.' },
    { route: 'dealdesk', title: 'Exceptions live here', body: 'A non-tier price is never typed on the order — it becomes a Deal Desk exception (maker-checker → CEO).' },
  ]},
  { id: 'close-period', title: 'Close & lock a period', steps: [
    { route: 'finance', title: 'Read the P&L', body: 'Finance shows the period’s P&L and the forward cash waterfall.' },
    { route: 'audit', title: 'Reconcile first', body: 'In the Auditability Center, every reconciliation must be matched before a lock is allowed.' },
    { route: 'period', title: 'Group roll-up', body: 'The group period can’t lock until every operating entity is locked — laggards are named upfront.' },
    { route: 'audit', title: 'Lock (segregation of duties)', body: 'Lock the period — blocked if any reconciliation is open, or if you’re the one who closed it.' },
  ]},
  { id: 'trace-unit', title: 'Trace a unit end-to-end', steps: [
    { route: 'batch', title: 'Serial → genealogy', body: 'On Batch & Genealogy, enter a serial to walk it to its lot, CM PO, sales order, customer and activation.' },
    { route: 'batch', title: 'Its exact cost', body: 'The cost breakdown is specific-identification: factory USD ÷ FX → GBP + freight + duty — no averaging.' },
    { route: 'warranty', title: 'Its RMA family', body: 'Warranty & RMA shows the faulty→replacement family that shares one warranty window (the clock never resets).' },
  ]},
];

export const LEARNING_PATHS: LearningPath[] = [
  { id: 'exec', role: 'CEO / exec', blurb: 'The shape of the business and the levers you approve.',
    chapters: ['getting-started', 'data-layers', 'golden-record', 'demand-h6q', 'finance', 'deal-desk', 'auditability'] },
  { id: 'finance', role: 'Finance', blurb: 'The ledger, the close, and the books that must tie.',
    chapters: ['getting-started', 'money-fx-integrity', 'event-ledger-model', 'finance', 'documents', 'tax', 'period', 'auditability', 'shadow-validation'] },
  { id: 'sales', role: 'Sales / commercial', blurb: 'From a customer to a placed, governed order.',
    chapters: ['getting-started', 'data-layers', 'crm', 'order-desk', 'pricing', 'deal-desk', 'commission', 'demand-h6q'] },
  { id: 'ops', role: 'Ops / supply', blurb: 'Stock, traceability, and after-sales.',
    chapters: ['getting-started', 'inventory', 'purchasing', 'batch-genealogy', 'activations', 'warranty-rma', 'supply-window'] },
  { id: 'auditor', role: 'Auditor', blurb: 'How every figure re-derives, by replay.',
    chapters: ['getting-started', 'event-ledger-model', 'auditability', 'proof-center', 'period', 'lifecycle'] },
  { id: 'shadow', role: 'Shadow dual-run owner', blurb: 'Run Conduit in parallel and watch it converge.',
    chapters: ['shadow-mode', 'sync', 'shadow-validation'] },
];
