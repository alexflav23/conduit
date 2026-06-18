// The interactive user manual content (spec 38). One source of truth, bundled with the desk (no fetch, works
// offline, reviewed as code). A chapter is keyed to a real desk route so the parity test (parity.test.ts) fails
// the build if a screen has no chapter or a chapter points at a screen that no longer exists — the manual cannot
// drift from the product. `status` is an honesty badge (governed by the no-fake-data rule): never imply an
// unbuilt path works. `section` matches the nav group exactly so the Help index mirrors the rail.

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
}

// ── Primer: how Conduit thinks (read first) ───────────────────────────────────────────────────────────
const PRIMER: ManualChapter[] = [
  {
    id: 'getting-started', route: null, section: 'Primer', title: 'Getting started — the desk',
    audience: ['all'], status: 'live',
    summary:
      'Conduit is Hypervolt’s master system of record. The desk is the back-office cockpit: a left rail of screens grouped by domain (Sell, Plan, Supply, Finance, Treasury, Govern), a top context bar (entity · market · period · scenario), and ⌘K to jump anywhere. What you can see and do is set by your role on the server — the rail only shows what you are granted.',
    concepts: [
      { term: 'Context bar', def: 'Entity, market, period and scenario at the top — every screen re-projects to it (it never mutates data).' },
      { term: '⌘K', def: 'Command palette — search screens, customers, and (soon) help chapters from anywhere.' },
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
    related: ['audit', 'proof'], seeAlso: ['doc 01 §3a', 'doc 03', 'doc 14'],
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
    related: ['crm'], seeAlso: ['doc 11', 'STATUS.md (CRM/MDM)'],
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
    related: ['dealdesk', 'pricing', 'crm'], seeAlso: ['doc 07 M4', 'doc 24'],
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
    related: ['order-desk', 'dealdesk'], seeAlso: ['doc 24', 'doc 07 M3'],
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
    related: ['order-desk', 'batch'], seeAlso: ['doc 11'],
  },
];

export const CHAPTERS: ManualChapter[] = [...PRIMER, ...SELL];

// Tabs not yet documented (shrinks to empty at M-Help.content). The parity test allows these; everything else
// must have a chapter. Keep this list honest — removing an id here without adding its chapter fails CI.
export const PENDING_CHAPTERS: string[] = [
  'dealdesk', 'returns', 'reseller',
  'h6q', 'flow', 'supply', 'shelf', 'engine', 'runs',
  'inventory', 'purchasing', 'batch', 'activation', 'warranty',
  'finance', 'commission', 'docs', 'lifecycle', 'tax', 'backlog',
  'intercompany', 'procurement', 'treasury',
  'audit', 'period', 'sync', 'shadow', 'proof', 'access', 'notifications',
];

export const SECTION_ORDER = ['Primer', 'Sell', 'Plan', 'Supply', 'Finance', 'Treasury', 'Govern'];
