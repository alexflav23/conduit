import React, { useState } from 'react';
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

export function Warranty(_props: any) {
  const stats = useApi<RmaStats>(['rma-stats'], '/api/v1/warranty/rma-stats');
  const free = useApi<FreeCat[]>(['free-ship-summary'], '/api/v1/free-shipments/summary');
  const [input, setInput] = useState('');
  const [serial, setSerial] = useState('');
  const life = useApi<Lifecycle>(['serial-life', serial], `/api/v1/serials/${encodeURIComponent(serial)}/lifecycle`, { enabled: !!serial });

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
