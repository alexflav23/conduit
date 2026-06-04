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
