import React, { useState, useEffect, useCallback } from 'react';
import { getDocuments, documentPdfUrl, voidInvoice, authToken } from './api';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, useToast } from './kit/kit';
import { tableState, asArray, type ApiResult } from './state';
import { I } from './kit/icons';

// Documents (spec/ui/08-documents.md, doc 17): the WORM (object-locked, immutable) fiscal-document surface.
// Search + retrieve sealed legal records (invoices / credit notes / statements / commercial invoices) and
// issue voids/credit-notes/refunds as PAIRED reversing documents — the original is never deleted, only
// superseded by a linked reversal. The hero is WORM trust: sealed, final, corrections-as-new-documents.
// Document totals are the `commercial` layer — Money collapses (renders nothing) when withheld.

interface DocRow {
  id: string;
  formatted_number?: string;
  document_type?: string;
  status?: string;
  entity?: string;
  total_amount?: number | string | null;
  currency?: string;
  issued_at?: string;
  voided_at?: string | null;
  reverses_document_id?: string | null;
  reversed_by_document_id?: string | null;
}

const TYPE_LABEL: Record<string, string> = {
  invoice: 'Invoice',
  credit_note: 'Credit note',
  statement: 'Statement',
  commercial_invoice: 'Commercial invoice',
};

const VOID_KINDS = [
  { v: 'mistake', label: 'Mistake — re-issue' },
  { v: 'cancellation', label: 'Cancellation' },
  { v: 'refund', label: 'Refund (needs finance approval)' },
  { v: 'correction', label: 'Correction' },
];

export function Documents({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [toastNode, kitToast] = useToast();
  const fire = useCallback((m: string, k?: string) => { toast(m, k); kitToast(m, (k as any) || 'ok'); }, [toast, kitToast]);

  const [query, setQuery] = useState('INV-FLOW');
  const [search, setSearch] = useState('INV-FLOW');
  const [res, setRes] = useState<ApiResult | null>(null);
  const [docs, setDocs] = useState<DocRow[]>([]);

  const [kind, setKind] = useState('mistake');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  // The viewer's own identity — refund approval is maker-checker; a finance user can request a refund but
  // cannot self-approve one they raised. The server enforces it; the UI mirrors the block with a tooltip.
  const selfRaised = role?.name && docs.some((d) => (d as any).requested_by === role.name && !d.voided_at);

  const load = useCallback(() => {
    const tok = role?.token || authToken();
    const looksLikeOrder = /^(ord|order)/i.test(search.trim());
    const params = looksLikeOrder ? { orderId: search.trim() } : { invoiceNo: search.trim() };
    setRes(null);
    getDocuments(tok, params).then((r) => {
      setRes(r);
      setDocs(asArray<DocRow>(r.json));
    });
  }, [role?.token, search]);

  // AUTO-LOAD: on mount + whenever the search target, entity or period context changes. No Load button.
  useEffect(load, [load, ctx?.entity, ctx?.period]);

  const st = tableState(res, docs);
  const locked = ctx?.period && /lock/i.test(String(ctx.period));

  const download = (d: DocRow) => {
    const tok = role?.token || authToken();
    fetch(documentPdfUrl(d.id), { headers: { Authorization: `Bearer ${tok}` } })
      .then((r) => (r.ok ? r.blob() : Promise.reject(new Error(String(r.status)))))
      .then((blob) => { window.open(URL.createObjectURL(blob), '_blank'); fire(`Opening ${d.formatted_number ?? d.id} — sealed PDF`, 'ok'); })
      .catch((e) => fire(`Download failed (${e.message})`, 'err'));
  };

  const submitVoid = () => {
    if (!reason.trim()) { fire('A reason is required to reverse a sealed document', 'warn'); return; }
    if (locked) { fire('Period is locked — cannot post a reversing document', 'warn'); return; }
    setBusy(true);
    const tok = role?.token || authToken();
    voidInvoice(tok, search.trim(), kind, reason).then((r) => {
      setBusy(false);
      if (r.status === 202 || r.status === 200) {
        fire(`${kind} processed — credit note minted, original kept (WORM)`, 'ok');
        setReason('');
        load();
      } else {
        fire(r.json?.message ?? `Reversal failed (${r.status})`, 'err');
      }
    });
  };

  const refundChosen = kind === 'refund';
  const refundBlocked = refundChosen && selfRaised;

  return (
    <div className="page">
      {toastNode}
      <PageHead
        crumb="Documents · WORM store"
        title="Documents"
        sub="Sealed, immutable fiscal records — invoice PDFs and the credit notes that supersede them. Corrections are new paired documents, never edits."
        right={
          <div className="row g8">
            <input
              className="fld"
              style={{ width: 200 }}
              data-testid="doc-invoice-no"
              value={query}
              placeholder="invoice no / order id"
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') setSearch(query); }}
            />
            <button className="btn sm" data-testid="doc-load" onClick={() => setSearch(query)}>{I.search({ size: 13 })} Find</button>
          </div>
        }
      />

      <Card
        title="Documents"
        icon={I.list}
        aux={<span className="row g6 dim" style={{ fontSize: 12 }}>{I.shield({ size: 13 })} object-locked · final · sealed</span>}
        className="tablewrap"
      >
        <table className="tbl" data-testid="doc-table">
          <thead>
            <tr>
              <th>Number</th>
              <th>Type</th>
              <th>Entity</th>
              <th className="num">Total</th>
              <th>Status</th>
              <th>PDF</th>
            </tr>
          </thead>
          <tbody>
            {st === 'loading' && <SkeletonRow cols={6} />}

            {st === 'forbidden' && (
              <tr><td colSpan={6}><LayerNote>Document totals are the <b>commercial</b> layer — hidden for your view.</LayerNote></td></tr>
            )}

            {st === 'error' && (
              <EmptyRow cols={6}>Couldn't load documents{(res?.json as any)?.message ? ` — ${(res!.json as any).message}` : ` (${res?.status})`}.</EmptyRow>
            )}

            {st === 'empty' && <EmptyRow cols={6}>No documents for this invoice or order yet.</EmptyRow>}

            {st === 'ready' && docs.map((d, i) => (
              <tr key={d.id ?? i} data-testid="doc-row">
                <td>
                  <span className="row g6">
                    <b className="mono" style={{ fontSize: 11 }}>{d.formatted_number ?? d.id ?? '—'}</b>
                    {d.reverses_document_id && <Chip s="accent">reversal</Chip>}
                  </span>
                  {d.reversed_by_document_id && <div className="dim" style={{ fontSize: 10.5, marginTop: 2 }}>superseded by reversal</div>}
                </td>
                <td>
                  <span className="row g6">
                    {TYPE_LABEL[d.document_type ?? ''] ?? d.document_type ?? '—'}
                    {d.document_type === 'credit_note' && <Chip s="accent">credit</Chip>}
                  </span>
                </td>
                <td className="dim" style={{ fontSize: 12 }}>{d.entity ?? '—'}</td>
                <td className="num"><Money value={d.total_amount ?? null} ccy={d.currency} layer="commercial" role={role} /></td>
                <td>
                  {d.voided_at
                    ? <Chip s="danger"><span data-testid="doc-voided">voided</span></Chip>
                    : <span className="row g6"><Chip s={d.status ?? 'neutral'}>{d.status ?? 'sealed'}</Chip>{!d.reverses_document_id && I.shield({ size: 12 })}</span>}
                </td>
                <td>
                  <button className="btn sm ghost" data-testid="doc-download" onClick={() => download(d)}>{I.download({ size: 12 })} Download</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Card
        title="Invalidate this document"
        icon={I.alert}
        aux={<span className="dim" style={{ fontSize: 12 }}>issues a paired reversing document — the original stays</span>}
      >
        {locked && <div className="banner warn" style={{ marginBottom: 12 }}>{I.clock({ size: 14 })} This period is locked — no reversing documents can be posted.</div>}
        <div className="loadbar">
          <span className="fldlabel">Kind</span>
          <select className="fld sel" data-testid="void-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
            {VOID_KINDS.map((k) => <option key={k.v} value={k.v}>{k.label}</option>)}
          </select>
          <input
            className="fld"
            style={{ flex: 1, minWidth: 240 }}
            data-testid="void-reason"
            value={reason}
            placeholder="reason (recorded on the reversing document)"
            onChange={(e) => setReason(e.target.value)}
          />
          <span title={refundBlocked ? 'Maker-checker: you raised this — a refund needs a different approver' : undefined}>
            <button
              className="btn danger"
              data-testid="void-submit"
              disabled={busy || st !== 'ready' || refundBlocked || !!locked}
              onClick={submitVoid}
            >
              {I.x({ size: 13 })} {refundChosen ? 'Issue refund' : 'Void invoice'}
            </button>
          </span>
        </div>
        <div className="dim" style={{ fontSize: 11.5, lineHeight: 1.5, marginTop: 4 }}>
          The original PDF is kept (WORM) and badged; a credit note supersedes it as a linked pair. A refund returns the cash and needs finance approval.
          {refundBlocked && <span className="row g6" style={{ marginTop: 6 }}>{I.shield({ size: 12 })} You raised this document — self-approval of a refund is blocked.</span>}
        </div>
        <div className="dim" style={{ fontSize: 11, marginTop: 10 }}>
          <span className="row g6">Every reversal drills to its ledger: <AuditRef id={search.trim() || 'invoice'} /></span>
        </div>
      </Card>
    </div>
  );
}
