import React, { useState, useEffect, useCallback } from 'react';
import { I } from './icons';

// Conduit Desk shared UI kit — Hypervolt dark-first. Money/chips/cards/drawer/coverage/zone/audit ride on the
// desk.css structural classes so views compose className + kit only (no hand-rolled colours/fonts).
//
// DATA-LAYER WALL (doc 05): a withheld layer is ABSENT from the payload, so the desk COLLAPSES rather than zeros.
// <Money> is layer-aware: pass `layer` (the data layer this figure belongs to) + `role` (the viewer, carrying the
// layers they hold). If the viewer lacks the layer, Money renders NOTHING (null) — never £0.00, never a placeholder.
// When no layer/role is supplied the figure is unconditional (the server already projected it in).

export type DataLayer = 'volume' | 'commercial' | 'profitability' | 'commission' | 'inter_entity' | 'pii';
export interface ViewerRole {
  layers: DataLayer[] | string[];
}

export function gbp(v: number | string | null | undefined, ccy?: string): string {
  if (v == null || v === '') return '—';
  const n = typeof v === 'string' ? parseFloat(v) : v;
  const sym = ccy === 'USD' ? '$' : ccy === 'EUR' ? '€' : '£';
  return (n < 0 ? '−' : '') + sym + Math.abs(n).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
export function num(v: number | string | null | undefined): string {
  return v == null ? '—' : Number(v).toLocaleString('en-GB');
}

export function Money({ value, ccy, layer, role }: {
  value: number | string | null | undefined;
  ccy?: string;
  layer?: DataLayer | string;
  role?: ViewerRole;
}) {
  if (layer && role && (role.layers as string[]).indexOf(layer) < 0) return null;
  return <span className="num">{gbp(value, ccy)}</span>;
}

const CHIP: Record<string, string> = {
  active: 'ok', matched: 'ok', approved: 'ok', pass: 'ok', placed: 'ok', paid: 'ok', sent: 'ok', standard: 'ok', balanced: 'ok', ok: 'ok',
  locked: 'accent', closed: 'accent', accent: 'accent', structural: 'accent',
  pending_ceo: 'warn', draft: 'warn', proposed: 'warn', outstanding: 'warn', monitoring: 'warn', warn: 'warn',
  open: 'neutral', neutral: 'neutral',
  rejected: 'danger', fail: 'danger', void: 'danger', block: 'danger', exception: 'danger', error: 'danger', danger: 'danger',
  champion: 'plum', plum: 'plum',
};
const CHIP_TXT: Record<string, string> = { pending_ceo: 'Pending CEO', proposed: 'Proposed', monitoring: 'Monitoring' };
export function Chip({ s, children }: { s: string; children?: React.ReactNode }) {
  return <span className={'chip ' + (CHIP[s] || 'neutral')}><span className="d" />{children || CHIP_TXT[s] || s}</span>;
}

export function Card({ title, icon, aux, children, style, className }: {
  title?: React.ReactNode; icon?: (p?: any) => React.ReactElement; aux?: React.ReactNode;
  children?: React.ReactNode; style?: React.CSSProperties; className?: string;
}) {
  return (
    <div className={'card ' + (className || '')} style={style}>
      {title && (
        <div className="ct">
          <div className="t">{icon && icon()}{title}</div>
          {aux && <div className="aux">{aux}</div>}
        </div>
      )}
      {children}
    </div>
  );
}

export function LoadBar({ children }: { children: React.ReactNode }) {
  return <div className="loadbar">{children}</div>;
}

export function PageHead({ crumb, title, sub, right }: { crumb?: React.ReactNode; title: React.ReactNode; sub?: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div className="phead">
      <div>
        {crumb && <div className="crumb">{crumb}</div>}
        <h1>{title}</h1>
        {sub && <div className="sub">{sub}</div>}
      </div>
      {right}
    </div>
  );
}

export function EmptyRow({ cols, children }: { cols: number; children: React.ReactNode }) {
  return <tr><td className="dim" colSpan={cols} style={{ padding: '18px 12px', textAlign: 'center' }}>{children}</td></tr>;
}

export function LayerNote({ children }: { children: React.ReactNode }) {
  return <div className="layer-note">{I.shield()}{children}</div>;
}

export function AuditRef({ id }: { id: React.ReactNode }) {
  return <span className="aref">{I.check()}{id}</span>;
}

const ZONE: Record<string, string> = {
  firm: 'zfrozen', frozen: 'zfrozen',
  flex: 'zflex',
  indicative: 'zfree', free: 'zfree',
};
export function ZoneTag({ zone }: { zone: 'firm' | 'flex' | 'indicative' | string }) {
  return <span className={'zone ' + (ZONE[zone] || 'zfree')}>{zone}</span>;
}

export function Coverage({ pct }: { pct: number }) {
  const p = Math.min(100, pct);
  const col = pct >= 85 ? 'var(--ok)' : pct >= 65 ? 'var(--warn)' : 'var(--danger)';
  return (
    <div className="covbar">
      <div className="track"><i style={{ width: p + '%', background: col, opacity: 0.9 }} /></div>
      <span className="pct" style={{ color: col }}>{pct.toFixed(0)}%</span>
    </div>
  );
}

export function Drawer({ open, onClose, title, sub, chip, children, footer, width }: {
  open: boolean; onClose: () => void; title?: React.ReactNode; sub?: React.ReactNode; chip?: React.ReactNode;
  children?: React.ReactNode; footer?: React.ReactNode; width?: number | string;
}) {
  return (
    <>
      <div className={'scrim' + (open ? ' open' : '')} onClick={onClose} />
      <div className={'drawer' + (open ? ' open' : '')} style={width ? { width } : undefined}>
        {open && (
          <>
            <div className="dh">
              <div style={{ flex: 1, minWidth: 0 }}>
                {chip}
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 19, fontWeight: 600, marginTop: chip ? 7 : 0 }}>{title}</div>
                {sub && <div className="dim" style={{ fontSize: 12.5, marginTop: 3 }}>{sub}</div>}
              </div>
              <div className="ibtn" onClick={onClose}>{I.x()}</div>
            </div>
            <div className="db">{children}</div>
            {footer && <div className="df">{footer}</div>}
          </>
        )}
      </div>
    </>
  );
}

// Skeleton — shimmer placeholder for the loading state. Renders `lines` shimmer bars, or a single bar of `w`/`h`.
export function Skeleton({ lines, w, h, style }: { lines?: number; w?: number | string; h?: number | string; style?: React.CSSProperties }) {
  if (lines && lines > 0) {
    return (
      <>
        {Array.from({ length: lines }).map((_, i) => (
          <div key={i} className="skel skel-line" style={{ width: i === lines - 1 ? '60%' : '100%' }} />
        ))}
      </>
    );
  }
  return <div className="skel" style={{ width: w ?? '100%', height: h ?? 12, ...style }} />;
}

// SkeletonRow — a shimmer row inside a <tbody> while a table loads.
export function SkeletonRow({ cols }: { cols: number }) {
  return (
    <tr className="skel-row">
      {Array.from({ length: cols }).map((_, i) => (
        <td key={i}><div className="skel skel-line" /></td>
      ))}
    </tr>
  );
}

type ToastKind = 'ok' | 'warn' | 'err';
// useToast — the desk's mutation-confirmation surface. Returns [node, fire]; render `node` once at the page root.
export function useToast(): [React.ReactNode, (text: string, kind?: ToastKind) => void] {
  const [msg, setMsg] = useState<{ text: string; kind: ToastKind; id: number } | null>(null);
  const fire = useCallback((text: string, kind: ToastKind = 'ok') => setMsg({ text, kind, id: Math.random() }), []);
  useEffect(() => {
    if (!msg) return;
    const t = setTimeout(() => setMsg(null), 3200);
    return () => clearTimeout(t);
  }, [msg]);
  const node = msg ? (
    <div className={'toast ' + msg.kind} key={msg.id}>
      {msg.kind === 'err' ? I.alert({ size: 15 }) : msg.kind === 'warn' ? I.flag({ size: 15 }) : I.check({ size: 15 })}
      <span>{msg.text}</span>
    </div>
  ) : null;
  return [node, fire];
}
