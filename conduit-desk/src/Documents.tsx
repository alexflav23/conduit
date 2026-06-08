import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getDocuments, documentPdfUrl, voidInvoice } from './api';

// The Documents desk (M13 doc 17 §6 + §void): the legal artefacts for an invoice — the invoice PDF and, once an
// invoice is invalidated, the credit note that supersedes it — plus the void/credit/refund action. Voiding an
// invoice is an immutable reversal: the original PDF is kept (WORM) but badged, and a credit note is minted.
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '900px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.7rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  danger: { backgroundColor: '#b3261e' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right' },
  link: { color: colors.accent, cursor: 'pointer', textDecoration: 'underline', background: 'none', border: 'none', padding: 0, font: 'inherit' },
  badge: { fontSize: '0.68rem', fontWeight: 700, padding: '0.1rem 0.45rem', borderRadius: '999px', textTransform: 'uppercase', letterSpacing: '0.04em' },
  badgeCredit: { backgroundColor: 'rgba(150,45,255,0.18)', color: colors.accent },
  badgeVoid: { backgroundColor: 'rgba(179,38,30,0.18)', color: '#ff6b6b' },
  select: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
});

export function Documents({ token }: { token: string }) {
  const [invoiceNo, setInvoiceNo] = useState('INV-FLOW');
  const [docs, setDocs] = useState<any[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [kind, setKind] = useState('mistake');
  const [reason, setReason] = useState('');
  const [status, setStatus] = useState<string | null>(null);

  const load = async () => {
    const r = await getDocuments(token, { invoiceNo });
    setDocs(Array.isArray(r.json) ? r.json : []);
    setLoaded(true);
  };

  const download = async (id: string) => {
    const res = await fetch(documentPdfUrl(id), { headers: { Authorization: `Bearer ${token}` } });
    if (!res.ok) { setStatus(`download failed (${res.status})`); return; }
    const blob = await res.blob();
    window.open(URL.createObjectURL(blob), '_blank');
  };

  const doVoid = async () => {
    if (!reason.trim()) { setStatus('a reason is required'); return; }
    const r = await voidInvoice(token, invoiceNo, kind, reason);
    setStatus(r.status === 202 ? `${kind} requested for ${invoiceNo}` : `failed (${r.status}: ${r.json?.message ?? ''})`);
    if (r.status === 202) await load();
  };

  const m = (v: any) => (v == null ? '—' : `£${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);

  return (
    <div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Documents — legal artefacts for an invoice (WORM)</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Invoice no</span>
          <input {...stylex.props(styles.input)} data-testid="doc-invoice-no" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} style={{ width: '280px' }} />
          <button {...stylex.props(styles.button)} data-testid="doc-load" onClick={load}>Load documents</button>
        </div>
        <table {...stylex.props(styles.table)} data-testid="doc-table">
          <thead><tr>
            <th {...stylex.props(styles.th)}>Number</th>
            <th {...stylex.props(styles.th)}>Type</th>
            <th {...stylex.props(styles.th, styles.num)}>Total</th>
            <th {...stylex.props(styles.th)}>State</th>
            <th {...stylex.props(styles.th)}>PDF</th>
          </tr></thead>
          <tbody>
            {docs.map((d, i) => (
              <tr key={i} data-testid="doc-row">
                <td {...stylex.props(styles.td)}>{d.formatted_number ?? '—'}</td>
                <td {...stylex.props(styles.td)}>
                  {d.document_type}
                  {d.document_type === 'credit_note' && <span {...stylex.props(styles.badge, styles.badgeCredit)} style={{ marginLeft: '0.4rem' }}>credit</span>}
                </td>
                <td {...stylex.props(styles.td, styles.num)}>{m(d.total_amount)}</td>
                <td {...stylex.props(styles.td)}>
                  {d.voided_at ? <span {...stylex.props(styles.badge, styles.badgeVoid)} data-testid="doc-voided">voided</span> : d.status}
                </td>
                <td {...stylex.props(styles.td)}>
                  <button {...stylex.props(styles.link)} data-testid="doc-download" onClick={() => download(d.id)}>Download</button>
                </td>
              </tr>
            ))}
            {loaded && docs.length === 0 && <tr><td {...stylex.props(styles.td)} colSpan={5} style={{ color: colors.muted }}>No documents for this invoice yet.</td></tr>}
          </tbody>
        </table>
      </div>

      {/* Invalidation — void / cancel / refund / correct (immutable reversal) */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Invalidate this invoice (reverses the ledger + mints a credit note)</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Kind</span>
          <select {...stylex.props(styles.select)} data-testid="void-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="mistake">mistake</option>
            <option value="cancellation">cancellation</option>
            <option value="refund">refund (needs approval)</option>
            <option value="correction">correction</option>
          </select>
          <input {...stylex.props(styles.input)} data-testid="void-reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="reason" style={{ width: '320px' }} />
          <button {...stylex.props(styles.button, styles.danger)} data-testid="void-submit" onClick={doVoid}>Void invoice</button>
          {status && <span {...stylex.props(styles.label)} data-testid="void-status">{status}</span>}
        </div>
        <span {...stylex.props(styles.label)}>The original PDF is kept (WORM) and badged; a credit note supersedes it. A refund returns the cash.</span>
      </div>
    </div>
  );
}
