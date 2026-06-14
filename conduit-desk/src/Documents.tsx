import React, { useState } from 'react';
import { getDocuments, documentPdfUrl, voidInvoice } from './api';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The Documents desk (M13 doc 17 §6 + §void / spec/ui/10-documents.md): the legal artefacts for an invoice —
// the invoice PDF and, once an invoice is invalidated, the credit note that supersedes it — plus the
// void/credit/refund action. Voiding is an immutable reversal: the original PDF is kept (WORM) but badged,
// and a credit note is minted. Ported to the desk kit (.tbl + Chip), testids preserved.

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
    <>
      <PageHead title="Documents" sub="Legal artefacts for an invoice (WORM) — PDF, credit notes, and immutable invalidation" />

      <Card title="Documents" icon={I.list} style={{ maxWidth: 900 }}
        aux={<LoadBar><span className="dim">Invoice no</span><input className="fld" style={{ width: 220 }} data-testid="doc-invoice-no" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} /><button className="btn primary sm" data-testid="doc-load" onClick={load}>Load</button></LoadBar>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="doc-table">
            <thead><tr><th>Number</th><th>Type</th><th className="num">Total</th><th>State</th><th>PDF</th></tr></thead>
            <tbody>
              {docs.map((d, i) => (
                <tr key={i} data-testid="doc-row">
                  <td className="mono">{d.formatted_number ?? '—'}</td>
                  <td>{d.document_type}{d.document_type === 'credit_note' && <span style={{ marginLeft: 6 }}><Chip s="accent">credit</Chip></span>}</td>
                  <td className="num">{m(d.total_amount)}</td>
                  <td>{d.voided_at ? <Chip s="danger"><span data-testid="doc-voided">voided</span></Chip> : <Chip s={d.status}>{d.status}</Chip>}</td>
                  <td><button className="btn sm" data-testid="doc-download" onClick={() => download(d.id)}>{I.download({ size: 13 })} Download</button></td>
                </tr>
              ))}
              {loaded && docs.length === 0 && <tr><td className="dim" colSpan={5} style={{ padding: '14px 12px' }}>No documents for this invoice yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Invalidate this invoice" icon={I.shield} style={{ maxWidth: 900 }} aux={<span className="dim" style={{ fontSize: 12 }}>reverses the ledger + mints a credit note</span>}>
        <div className="row g8" style={{ flexWrap: 'wrap' }}>
          <span className="dim">Kind</span>
          <select className="fld sel" data-testid="void-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="mistake">mistake</option>
            <option value="cancellation">cancellation</option>
            <option value="refund">refund (needs approval)</option>
            <option value="correction">correction</option>
          </select>
          <input className="fld" style={{ width: 320 }} data-testid="void-reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="reason" />
          <button className="btn danger" data-testid="void-submit" onClick={doVoid}>Void invoice</button>
          {status && <span className="dim" data-testid="void-status">{status}</span>}
        </div>
        <p className="dim" style={{ marginTop: 10, fontSize: 12.5 }}>The original PDF is kept (WORM) and badged; a credit note supersedes it. A refund returns the cash.</p>
      </Card>
    </>
  );
}
