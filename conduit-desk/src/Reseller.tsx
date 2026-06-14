import { Card } from './kit/kit';
import { I } from './kit/icons';

// Reseller portal (spec/ui/25) — the externally-facing, SCOPED, rate-limited surface (doc 19 §A.1).
// A reseller signs in with a scoped JWT and sees ONLY their own catalogue-for-me pricing (commercial layer
// only), places tier-governed orders and tracks their own orders/invoices.
//
// There is NO backend route for this surface in this environment: the api routes dir
// (api/src/main/scala/com/hypervolt/conduit/api/routes/) has no ResellerRoutes — no /api/v1/reseller/portal
// and no /api/v1/reseller/quote. The previous build guessed those paths with raw apiFetch. Rather than fire
// unbacked calls (which 404 into stuck skeletons), each data surface renders the honest, styled
// "Not available in this environment yet" panel. The calmer external partner-portal shell is preserved.

export function Reseller({ role: _role, ctx: _ctx, toast: _toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  return (
    <div className="page" style={{ maxWidth: 1080 }}>
      {/* Distinct, calmer branded header — the external "partner portal" feel, not the internal desk. */}
      <div
        className="row between"
        style={{
          padding: '18px 22px',
          borderRadius: 16,
          background: 'linear-gradient(135deg, var(--surface) 0%, var(--bg-2) 100%)',
          border: '1px solid var(--border)',
          marginBottom: 18,
        }}
      >
        <div className="row g12" style={{ alignItems: 'center' }}>
          <span
            style={{ width: 40, height: 40, borderRadius: 11, display: 'grid', placeItems: 'center', background: 'var(--brand-grad)', color: '#fff' }}
          >
            <I.bolt size={20} />
          </span>
          <div>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Hypervolt Partner Portal</div>
            <div className="dim" style={{ fontSize: 12 }}>scoped reseller session</div>
          </div>
        </div>
      </div>

      <div className="banner info" style={{ marginBottom: 18 }}>
        <I.shield />
        <div>
          You see <span className="bb">only your own</span> catalogue pricing, orders and invoices. Internal cost, margin and other resellers
          are <span className="bb">absent</span> from your session — not hidden behind a zero. Calls are rate-limited to your tier.
        </div>
      </div>

      <Card title="Catalogue — your pricing" icon={I.list} aux="your contracted tier prices · no internal cost or margin, ever" style={{ padding: 0, marginBottom: 18 }}>
        <NotAvailable line="The reseller catalogue-for-me pricing surface (tier-governed quotes, 422 floor enforcement, per-tier rate limiting) has no backend route in this environment yet." />
      </Card>

      <Card title="My orders & invoices" icon={I.sessions} aux="your orders only · scope-walled to your party" style={{ padding: 0 }}>
        <NotAvailable line="The reseller orders &amp; invoices feed (scope-walled to your party) has no backend route in this environment yet." />
      </Card>
    </div>
  );
}

function NotAvailable({ line }: { line: string }) {
  return (
    <div style={{ padding: '40px 24px', textAlign: 'center' }}>
      <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
        <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>
          <I.shield />
        </span>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
        <div className="dim" style={{ fontSize: 12.5, maxWidth: 520 }}>{line}</div>
      </div>
    </div>
  );
}
