// Minimal API client for the Conduit desk. Bearer token = `dev:<keycloak_id>` against the dev backend.
const DEMO_CHANNEL = '11111111-1111-1111-1111-111111111111';
const DEMO_MARKET = '22222222-2222-2222-2222-222222222222';

async function call(path: string, token: string, method: string, body?: unknown): Promise<{ status: number; json: any }> {
  const res = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

export interface QuoteLine {
  sku: string;
  qty: number;
  unitPriceExVat?: string;
}

export function quote(token: string, lines: QuoteLine[]) {
  return call('/api/v1/pricing/quote', token, 'POST', {
    channelId: DEMO_CHANNEL,
    marketId: DEMO_MARKET,
    currency: 'GBP',
    lines,
  });
}

export function listExceptions(token: string, status = 'pending_ceo') {
  return call(`/api/v1/adlp/exceptions?status=${status}`, token, 'GET');
}

export function getException(token: string, id: string) {
  return call(`/api/v1/adlp/exceptions/${id}`, token, 'GET');
}

export function submitNarrative(token: string, id: string, body: unknown) {
  return call(`/api/v1/adlp/exceptions/${id}/submit`, token, 'POST', body);
}

export function decide(token: string, id: string, body: unknown) {
  return call(`/api/v1/adlp/exceptions/${id}/decision`, token, 'POST', body);
}

// ----- H6Q forecasting -----

export function getVariants(token: string) {
  return call('/api/v1/h6q/variants', token, 'GET');
}

export function getScenarios(token: string) {
  return call('/api/v1/h6q/scenarios', token, 'GET');
}

export function getMyForecasts(token: string) {
  return call('/api/v1/h6q/my-forecasts', token, 'GET');
}

export interface ForecastLine {
  variant: string;
  period: string;
  scenario: string;
  qty: number;
}

export function submitForecast(token: string, companyId: string, cycle: string, lines: ForecastLine[]) {
  return call(`/api/v1/h6q/my-forecasts/${companyId}/submit`, token, 'POST', { cycle, lines });
}

export function getCoverage(token: string, market: string, period: string, scenario: string, groupBy: string) {
  return call(`/api/v1/h6q/coverage?market=${market}&period=${period}&scenario=${scenario}&group_by=${groupBy}`, token, 'GET');
}

// The per-SKU market breakdown (what an imported H6Q populates — quantities per SKU per month).
export function getCoverageBySku(token: string, market: string, period: string, scenario: string) {
  return call(`/api/v1/h6q/coverage/by-sku?market=${market}&period=${period}&scenario=${scenario}&group_by=market`, token, 'GET');
}

// The full demand matrix: all SKUs across all months for one scenario (the spreadsheet view).
export function getCoverageMatrix(token: string, market: string, scenario: string) {
  return call(`/api/v1/h6q/coverage/matrix?market=${market}&scenario=${scenario}`, token, 'GET');
}

export function getReconcile(token: string, market: string, period: string, scenario: string) {
  return call(`/api/v1/h6q/coverage/reconcile?market=${market}&period=${period}&scenario=${scenario}`, token, 'GET');
}

export function getOutstanding(token: string, cycle: string) {
  return call(`/api/v1/h6q/outstanding?cycle=${cycle}`, token, 'GET');
}

export function getNotifications(token: string) {
  return call('/api/v1/h6q/notifications', token, 'GET');
}

export function getWaterfall(token: string, variant: string, period: string) {
  return call(`/api/v1/h6q/waterfall?variant=${variant}&period=${period}`, token, 'GET');
}

export function getLedger(token: string, market: string, period: string) {
  return call(`/api/v1/h6q/ledger?market=${market}&period=${period}`, token, 'GET');
}

export function getContractManufacturers(token: string) {
  return call('/api/v1/h6q/suppliers', token, 'GET');
}
export function getSupplyCommitments(token: string, supplier: string) {
  return call(`/api/v1/h6q/supply/commitments?supplier=${supplier}`, token, 'GET');
}
export function getProposals(token: string, supplier: string) {
  return call(`/api/v1/h6q/supply/proposals?supplier=${supplier}`, token, 'GET');
}
export function getSupplyWarnings(token: string, supplier: string) {
  return call(`/api/v1/h6q/supply/warnings?supplier=${supplier}`, token, 'GET');
}
export function approvePo(token: string, supplier: string, variant: string, target: string) {
  return call('/api/v1/h6q/supply/approve', token, 'POST', { supplier, variant, target });
}
export function getShelfBoard(token: string) {
  return call('/api/v1/h6q/shelf', token, 'GET');
}

export const H6Q_MARKET = DEMO_MARKET;

export async function placeOrder(token: string, lines: QuoteLine[]) {
  const soldTo = await call('/api/v1/parties', token, 'POST', {
    displayName: 'Demo Branch',
    partyType: 'wholesaler',
    isOrganization: true,
  });
  const billTo = await call('/api/v1/parties', token, 'POST', {
    displayName: 'Demo Master',
    partyType: 'wholesaler',
    isOrganization: true,
  });
  return call('/api/v1/orders', token, 'POST', {
    type: 'trade',
    soldToPartyId: soldTo.json.id,
    billToPartyId: billTo.json.id,
    channelId: DEMO_CHANNEL,
    marketId: DEMO_MARKET,
    currency: 'GBP',
    paymentMethod: 'stripe',
    lines,
  });
}

// ----- M13 finance read-models (P&L / cash waterfall / credit terms) -----

export function getPnl(token: string, market: string, period: string) {
  return call(`/api/v1/finance/pnl?market=${market}&period=${period}`, token, 'GET');
}

export function getCashWaterfall(token: string, currency?: string) {
  const q = currency ? `?currency=${currency}` : '';
  return call(`/api/v1/finance/cash-waterfall${q}`, token, 'GET');
}

export function getCreditTerms(token: string, partyId: string) {
  return call(`/api/v1/parties/${partyId}/credit-terms`, token, 'GET');
}

export function setCreditTerms(token: string, partyId: string, paymentTermsDays: number, creditLimit?: number) {
  return call(`/api/v1/parties/${partyId}/credit-terms`, token, 'PUT', {
    payment_terms_days: paymentTermsDays,
    credit_limit: creditLimit,
  });
}

export const FINANCE_MARKET = DEMO_MARKET;

// ----- M13b auditability center (close board / controls / lineage) -----

export function getPeriods(token: string) {
  return call('/api/v1/finance/periods', token, 'GET');
}
export function getPeriodReconciliations(token: string, periodId: string) {
  return call(`/api/v1/finance/periods/${periodId}/reconciliations`, token, 'GET');
}
export function closePeriod(token: string, periodId: string) {
  return call(`/api/v1/finance/periods/${periodId}/close`, token, 'POST');
}
export function lockPeriod(token: string, periodId: string) {
  return call(`/api/v1/finance/periods/${periodId}/lock`, token, 'POST');
}
export function getControls(token: string) {
  return call('/api/v1/finance/controls', token, 'GET');
}
export function runControl(token: string, code: string) {
  return call(`/api/v1/finance/controls/${code}/run`, token, 'POST');
}
export function getLineage(token: string, invoiceNo: string) {
  return call(`/api/v1/finance/lineage?invoice_no=${invoiceNo}`, token, 'GET');
}

// M-Period (doc 32): the period investigation view + the group roll-up lock.
export function investigatePeriod(token: string, periodKey: string, entity?: string) {
  const q = entity ? `?entity=${entity}` : '';
  return call(`/api/v1/finance/periods/${encodeURIComponent(periodKey)}/investigation${q}`, token, 'GET');
}
export function lockGroupPeriod(token: string, periodKey: string) {
  return call(`/api/v1/finance/group-periods/${encodeURIComponent(periodKey)}/lock`, token, 'POST');
}

// M-Ingest (doc 33 §7): the shadow dual-run sync-health board.
export function getSyncState(token: string) {
  return call('/api/v1/finance/sync-state', token, 'GET');
}

// ----- M13 documents + invoice invalidation (void / credit note / refund) -----

export function getDocuments(token: string, params: { invoiceNo?: string; orderId?: string }) {
  const q = params.invoiceNo
    ? `invoice_no=${encodeURIComponent(params.invoiceNo)}`
    : `order_id=${encodeURIComponent(params.orderId ?? '')}`;
  return call(`/api/v1/documents?${q}`, token, 'GET');
}

// Direct link to the PDF bytes (served from the WORM store). Bearer auth is applied by the browser fetch when
// rendered as a link with the token in the path is NOT used — finance opens it via the authenticated client.
export function documentPdfUrl(id: string): string {
  return `/api/v1/documents/${id}/pdf`;
}

export function voidInvoice(token: string, invoiceNo: string, kind: string, reason: string) {
  return call(`/api/v1/invoices/${encodeURIComponent(invoiceNo)}/void`, token, 'POST', { kind, reason });
}

// ----- M13 order collection ledger (lifecycle replay) -----

export function getOrderLifecycle(token: string, orderId: string) {
  return call(`/api/v1/orders/${encodeURIComponent(orderId)}/lifecycle`, token, 'GET');
}

// ----- M13-Tax (doc 16): determination engine + rate-table admin (effective-dated, multi-level) -----

// A seeded operating entity (the liable taxpayer) so the quote tester has a valid entity_id to persist against.
export const TAX_DEMO_ENTITY = '33333333-3333-3333-3333-333333333333';

export interface TaxQuoteInput {
  shipFromJurisdiction: string;
  shipToJurisdiction: string;
  shipToRegion?: string;
  shipToPostcode?: string;
  partyTaxStatus: string;
  buyerTaxId?: string;
  currency: string;
  taxableAmount: string;
}

export function taxQuote(token: string, q: TaxQuoteInput) {
  return call('/api/v1/tax/quote', token, 'POST', {
    context: 'quote_preview',
    entityId: TAX_DEMO_ENTITY,
    shipFrom: { jurisdiction: q.shipFromJurisdiction, region: null, postcode: null },
    shipTo: { jurisdiction: q.shipToJurisdiction, region: q.shipToRegion || null, postcode: q.shipToPostcode || null },
    partyTaxStatus: q.partyTaxStatus,
    buyerTaxId: q.buyerTaxId || null,
    incoterm: null,
    currency: q.currency,
    asOf: '2026-06-01',
    lines: [{ ref: 'l1', productVariantId: null, taxCategoryCode: 'goods_standard', hsCode: null, qty: 1, taxableAmount: q.taxableAmount }],
  });
}

export function getTaxRates(token: string, jurisdiction?: string) {
  const q = jurisdiction ? `?jurisdiction=${encodeURIComponent(jurisdiction)}` : '';
  return call(`/api/v1/tax/rates${q}`, token, 'GET');
}

export function proposeTaxRate(token: string, body: unknown) {
  return call('/api/v1/tax/rates', token, 'POST', body);
}

export function activateTaxRate(token: string, id: string) {
  return call(`/api/v1/tax/rates/${id}/activate`, token, 'POST');
}

export function getTaxRouting(token: string) {
  return call('/api/v1/tax/routing', token, 'GET');
}

export function getTaxNexus(token: string, entityId?: string) {
  const q = entityId ? `?entity_id=${encodeURIComponent(entityId)}` : '';
  return call(`/api/v1/tax/nexus${q}`, token, 'GET');
}

// ----- M13-VAT: seller-of-record entity map + per-jurisdiction VAT exposure / remittance -----

export function getSellingEntities(token: string) {
  return call('/api/v1/tax/selling-entities', token, 'GET');
}
export function proposeSellingEntity(token: string, jurisdiction: string, entityId: string, effectiveFrom: string) {
  return call('/api/v1/tax/selling-entities', token, 'POST', { jurisdiction, entity_id: entityId, effective_from: effectiveFrom });
}
export function activateSellingEntity(token: string, id: string) {
  return call(`/api/v1/tax/selling-entities/${id}/activate`, token, 'POST');
}

// ----- M-Proof (doc 31): the Proof Center — laws, the journal walk, ASC-606, reconcile, the tamper sandbox -----

export function getProofLaws(token: string) {
  return call('/api/v1/proof/laws', token, 'GET');
}
export function runProofControl(token: string, code: string) {
  return call(`/api/v1/proof/controls/${code}/run`, token, 'POST');
}
export function getProofTrialBalance(token: string, entityId: string) {
  return call(`/api/v1/proof/trial-balance/${entityId}`, token, 'GET');
}
export function getProofAsc606(token: string, orderId: string) {
  return call(`/api/v1/proof/asc606/${orderId}`, token, 'GET');
}
export function getProofJournal(token: string, invoiceNo: string) {
  return call(`/api/v1/proof/journal/${encodeURIComponent(invoiceNo)}`, token, 'GET');
}
export function proofTamper(token: string, kind: string) {
  return call(`/api/v1/proof/tamper/${kind}`, token, 'POST');
}
export function proofTamperRestore(token: string) {
  return call('/api/v1/proof/tamper-restore', token, 'POST');
}
// The journal walk reuses the lineage explorer's data (figure → ledger transfers → events → document).

export function getVatExposure(token: string, entityId?: string) {
  const q = entityId ? `?entity_id=${encodeURIComponent(entityId)}` : '';
  return call(`/api/v1/tax/vat/exposure${q}`, token, 'GET');
}
export function requestVatRemittance(
  token: string,
  body: { entity_id: string; jurisdiction: string; period_key: string; amount: number; currency: string; reference?: string },
) {
  return call('/api/v1/tax/vat/remittances', token, 'POST', body);
}
