import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Warranty & RMA — the unit replacement lifecycle and warranty picture, built from the real HubSpot RMA pipeline
// (doc 08 / M8). A faulty unit (charger_id) → its replacement (rma_serial_number) forms a family that shares the
// ORIGINAL's warranty window (the clock never resets). V2→V3 = a legacy unit replaced by the current product under
// warranty — the quality/cost signal. Three reads, all gated view:pipeline_coverage:
//   GET /warranty/rma-stats · GET /free-shipments/summary · GET /serials/{serial}/lifecycle

interface RmaStats {
  total_rma_tickets: number; matched_faulty_units: number; matched_replacements: number;
  v2_to_v3_replacements: number; v3_to_v3_replacements: number; v2_to_v2_replacements: number;
  faulty_v2: number; faulty_v3: number;
}
interface FreeCat { category: string; shipments: number; cogs_absorbed: number; avg_cogs: number }
interface LifeUnit { serial: string; status: string; activated_at: string | null; warranty_end: string | null; is_replacement: boolean }
interface Ticket { ticket_ref: string; original_serial: string | null; replacement_serial: string | null; type: string | null; reason: string | null; opened_at: string | null; status: string | null }
interface Lifecycle { serial: string; root_serial: string; warranty_end: string | null; family_size: number; timeline: LifeUnit[]; rma_tickets: Ticket[] }

const day = (s: string | null | undefined) => (s ? String(s).slice(0, 10) : '—');
const inWarranty = (end: string | null) => !!end && new Date(end) >= new Date();

function Stat({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <Card style={{ padding: '14px 16px' }}>
      <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.4 }}>{label}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 4 }}>{value}</div>
      {sub && <div className="dim" style={{ fontSize: 12, marginTop: 2 }}>{sub}</div>}
    </Card>
  );
}

interface TrendRow { month: string; category: string; shipments: number; cogs: number }
interface RmaRow { ticket_ref: string; type?: string | null; reason?: string | null; status?: string | null; opened_at?: string | null;
  original_serial?: string | null; original_product?: string | null; replacement_serial?: string | null; replacement_product?: string | null;
  warranty_end?: string | null; owner?: string | null; owner_id?: string | null }
const RMA_PAGE = 50;

export function Warranty(_props: any) {
  const navigate = useNavigate();
  const stats = useApi<RmaStats>(['rma-stats'], '/api/v1/warranty/rma-stats');
  const free = useApi<FreeCat[]>(['free-ship-summary'], '/api/v1/free-shipments/summary');
  const trend = useApi<TrendRow[]>(['free-ship-trend'], '/api/v1/free-shipments/trend');
  const [input, setInput] = useState('');
  const [serial, setSerial] = useState('');
  const life = useApi<Lifecycle>(['serial-life', serial], `/api/v1/serials/${encodeURIComponent(serial)}/lifecycle`, { enabled: !!serial });
  // Browse the RMA pipeline — every faulty → replacement, paginated + searchable; click one to trace its family below.
  const [rmaQ, setRmaQ] = useState('');
  const [rmaPage, setRmaPage] = useState(0);
  const rmas = useApi<{ rows?: RmaRow[]; total?: number }>(
    ['warranty-rmas', rmaQ, rmaPage],
    `/api/v1/warranty/rmas?limit=${RMA_PAGE}&offset=${rmaPage * RMA_PAGE}${rmaQ.trim() ? `&q=${encodeURIComponent(rmaQ.trim())}` : ''}`,
  );
  const rmaRows = rmas.data?.rows ?? [];
  const rmaTotal = rmas.data?.total ?? 0;
  const openSerial = (sn?: string | null) => { if (sn) { setInput(sn); setSerial(sn); window.scrollTo({ top: 9999, behavior: 'smooth' }); } };

  const sErr = stats.error as ApiError | null;
  const forbidden = !!sErr?.forbidden;
  const s = stats.data;
  const cats = Array.isArray(free.data) ? free.data : [];
  const lc = life.data;

  return (
    <>
      <PageHead
        crumb="Service · warranty & RMA lifecycle (M8 / doc 08)"
        title="Warranty & RMA"
        sub="The unit replacement lifecycle from the real HubSpot RMA pipeline — faulty unit → replacement, sharing the original's warranty window. V2→V3 is a legacy unit replaced by the current product under warranty."
      />

      {forbidden && <LayerNote>hidden — requires view:pipeline_coverage</LayerNote>}

      {!forbidden && (
        <>
          {/* ---- RMA stats ---- */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(150px,1fr))', gap: 12, marginBottom: 16 }}>
            {stats.isLoading && <Card style={{ padding: 16 }}><SkeletonRow cols={1} /></Card>}
            {s && <>
              <Stat label="RMA tickets" value={num(s.total_rma_tickets)} sub={`${num(s.matched_faulty_units)} faulty matched`} />
              <Stat label="V2 → V3" value={num(s.v2_to_v3_replacements)} sub="legacy replaced by current" />
              <Stat label="V3 → V3" value={num(s.v3_to_v3_replacements)} />
              <Stat label="V2 → V2" value={num(s.v2_to_v2_replacements)} />
              <Stat label="Faulty by gen" value={`${num(s.faulty_v2)} / ${num(s.faulty_v3)}`} sub="V2 / V3" />
            </>}
          </div>

          {/* ---- RMA / unit-replacement browser (the hero) ---- */}
          <Card title={`Unit replacements${rmaTotal ? ` (${num(rmaTotal)})` : ''}`} icon={I.shield}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>every RMA: faulty unit → replacement · click to trace the family</span>}
            className="tablewrap" style={{ padding: 0, marginBottom: 16 }}>
            <div className="loadbar" style={{ padding: '12px 15px 0', marginBottom: 0 }}>
              <input className="cellinput" style={{ width: 300, textAlign: 'left' }} value={rmaQ} data-testid="rma-search"
                onChange={(e) => { setRmaQ(e.target.value); setRmaPage(0); }} placeholder="Search ticket, serial or customer…" />
            </div>
            <table className="tbl">
              <thead><tr><th>Ticket</th><th>Opened</th><th>Faulty unit</th><th>→ Replacement</th><th>Product</th><th>Customer</th><th>Reason</th></tr></thead>
              <tbody>
                {rmas.isLoading && <SkeletonRow cols={7} />}
                {!rmas.isLoading && rmaRows.length === 0 && <EmptyRow cols={7}>No RMAs match.</EmptyRow>}
                {rmaRows.map((r) => (
                  <tr key={r.ticket_ref} data-testid="rma-row" style={{ cursor: r.replacement_serial ? 'pointer' : 'default' }}
                    onClick={() => openSerial(r.replacement_serial || r.original_serial)}>
                    <td className="mono" style={{ fontSize: 11.5 }}>{r.ticket_ref}</td>
                    <td className="dim">{r.opened_at ? r.opened_at.slice(0, 10) : '—'}</td>
                    <td className="mono" style={{ fontSize: 11 }}>{r.original_serial || <span className="dim">—</span>}</td>
                    <td className="mono" style={{ fontSize: 11 }}>{r.replacement_serial ? <b>{r.replacement_serial} ↗</b> : <span className="dim">—</span>}</td>
                    <td className="dim" style={{ fontSize: 11.5 }}>{r.replacement_product || r.original_product || '—'}</td>
                    <td>{r.owner_id ? <span style={{ cursor: 'pointer', color: 'var(--accent-bright)' }} onClick={(e) => { e.stopPropagation(); navigate('/crm/account/' + r.owner_id); }}>{r.owner} ↗</span> : (r.owner || '—')}</td>
                    <td className="dim" style={{ fontSize: 11.5, maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.reason || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rmaTotal > RMA_PAGE && (
              <div className="row between" style={{ padding: '10px 15px' }}>
                <button className="btn ghost sm" disabled={rmaPage === 0} onClick={() => setRmaPage((p) => Math.max(0, p - 1))}>← Prev</button>
                <span className="dim" style={{ fontSize: 12 }}>{rmaPage * RMA_PAGE + 1}–{Math.min((rmaPage + 1) * RMA_PAGE, rmaTotal)} of {num(rmaTotal)}</span>
                <button className="btn ghost sm" disabled={(rmaPage + 1) * RMA_PAGE >= rmaTotal} onClick={() => setRmaPage((p) => p + 1)}>Next →</button>
              </div>
            )}
          </Card>

          {/* ---- free-shipment mix ---- */}
          <Card title="Free-shipment mix" icon={I.layers} aux={<span className="dim" style={{ fontSize: 12 }}>COGS-without-revenue, classified</span>}>
            <table className="tbl">
              <thead><tr><th>Category</th><th style={{ textAlign: 'right' }}>Shipments</th><th style={{ textAlign: 'right' }}>COGS absorbed</th><th style={{ textAlign: 'right' }}>Avg</th></tr></thead>
              <tbody>
                {free.isLoading && <SkeletonRow cols={4} />}
                {!free.isLoading && cats.length === 0 && <EmptyRow cols={4}>No free shipments classified.</EmptyRow>}
                {cats.map((c) => (
                  <tr key={c.category}>
                    <td><Chip s={c.category.startsWith('warranty') ? 'accent' : c.category.startsWith('sample') ? 'plum' : 'neutral'}>{c.category}</Chip></td>
                    <td style={{ textAlign: 'right' }}>{num(c.shipments)}</td>
                    <td style={{ textAlign: 'right' }}>£{num(c.cogs_absorbed)}</td>
                    <td style={{ textAlign: 'right' }} className="dim">£{num(c.avg_cogs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          {/* ---- monthly trend ---- */}
          {(() => {
            const rows = Array.isArray(trend.data) ? trend.data : [];
            const byMonth = new Map<string, number>();
            rows.forEach((r) => byMonth.set(r.month, (byMonth.get(r.month) ?? 0) + r.shipments));
            const months = Array.from(byMonth.entries()).sort((a, b) => a[0].localeCompare(b[0]));
            const max = Math.max(1, ...months.map((m) => m[1]));
            return (
              <Card title="Free shipments — monthly trend" icon={I.trend} aux={<span className="dim" style={{ fontSize: 12 }}>units shipped free, by month</span>}>
                {trend.isLoading && <SkeletonRow cols={1} />}
                {!trend.isLoading && months.length === 0 && <EmptyRow cols={1}>No trend data.</EmptyRow>}
                <div style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 120, marginTop: 8 }}>
                  {months.map(([m, n]) => (
                    <div key={m} title={`${m}: ${n}`} style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', alignItems: 'center', gap: 4, height: '100%' }}>
                      <div style={{ width: '100%', height: `${Math.max(2, Math.round((n / max) * 96))}px`, background: 'var(--accent)', borderRadius: '3px 3px 0 0' }} />
                      <span className="dim" style={{ fontSize: 9, transform: 'rotate(-60deg)', whiteSpace: 'nowrap', transformOrigin: 'center' }}>{m.slice(2)}</span>
                    </div>
                  ))}
                </div>
              </Card>
            );
          })()}

          {/* ---- serial lifecycle lookup ---- */}
          <Card title="Unit lifecycle" icon={I.clock} aux={<span className="dim" style={{ fontSize: 12 }}>family timeline · shared warranty · RMA tickets</span>}>
            <form
              onSubmit={(e) => { e.preventDefault(); setSerial(input.trim()); }}
              style={{ display: 'flex', gap: 8, marginBottom: 12 }}
            >
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="serial number (e.g. 030100…) or V2 MAC"
                data-testid="warranty-serial"
                style={{ flex: 1, padding: '9px 12px', borderRadius: 8, border: '1px solid var(--line)', background: 'var(--panel-2)', color: 'inherit', fontFamily: 'var(--font-mono)', fontSize: 13 }}
              />
              <button type="submit" className="btn" disabled={!input.trim()}>Look up</button>
            </form>

            {serial && life.isLoading && <SkeletonRow cols={1} />}
            {serial && !life.isLoading && (!lc || (lc as any).error) && <EmptyRow cols={1}>No unit found for “{serial}”.</EmptyRow>}
            {lc && lc.timeline && (
              <>
                <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 12 }}>
                  <div><span className="dim" style={{ fontSize: 12 }}>Family </span><b>{lc.family_size}</b> unit{lc.family_size === 1 ? '' : 's'}</div>
                  <div><span className="dim" style={{ fontSize: 12 }}>Warranty ends </span><b>{day(lc.warranty_end)}</b> <Chip s={inWarranty(lc.warranty_end) ? 'ok' : 'neutral'}>{lc.warranty_end ? (inWarranty(lc.warranty_end) ? 'in warranty' : 'expired') : 'unknown'}</Chip></div>
                </div>
                <table className="tbl">
                  <thead><tr><th>Serial</th><th>Role</th><th>Status</th><th>Activated</th><th>Warranty end</th></tr></thead>
                  <tbody>
                    {lc.timeline.map((u) => (
                      <tr key={u.serial}>
                        <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{u.serial}</td>
                        <td><Chip s={u.is_replacement ? 'plum' : 'accent'}>{u.is_replacement ? 'replacement' : 'original'}</Chip></td>
                        <td className="dim">{u.status}</td>
                        <td>{day(u.activated_at)}</td>
                        <td>{day(u.warranty_end)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {lc.rma_tickets.length > 0 && (
                  <table className="tbl" style={{ marginTop: 12 }}>
                    <thead><tr><th>RMA ticket</th><th>Reason</th><th>Opened</th><th>Stage</th></tr></thead>
                    <tbody>
                      {lc.rma_tickets.map((t) => (
                        <tr key={t.ticket_ref}>
                          <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{t.ticket_ref}</td>
                          <td>{t.reason || '—'}</td>
                          <td>{day(t.opened_at)}</td>
                          <td><Chip s="neutral">{t.status || '—'}</Chip></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </>
            )}
          </Card>
        </>
      )}
    </>
  );
}
