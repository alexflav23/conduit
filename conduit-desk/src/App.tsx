import React, { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { LOCALES, LOCALE_LABEL, setLocale } from './i18n';
import { I } from './kit/icons';
import { OrderDesk } from './OrderDesk';
import { DealDesk } from './DealDesk';
import { H6Q } from './H6Q';
import { Flow } from './Flow';
import { SupplyWindow } from './SupplyWindow';
import { Shelf } from './Shelf';
import { Finance } from './Finance';
import { Documents } from './Documents';
import { Lifecycle } from './Lifecycle';
import { Auditability } from './Auditability';
import { Tax } from './Tax';
import { Forecasting } from './Forecasting';
import { Proof } from './Proof';
import { Period } from './Period';
import { Sync } from './Sync';
import { SignIn, sessionEmail, signOutGoogle } from './SignIn';

// The Conduit Desk shell — ported from the Claude Design bundle (desk-shell.jsx + desk.css): a grouped rail,
// the working-context bar, a ⌘K command palette, theme toggle, notifications, and the role/session menu. The
// real, API-wired feature pages render inside `.work` (the design's mock pages are NOT used — functionality
// stays). The real SignIn (Google + dev token) gates entry. All e2e testids are preserved.

type TabId =
  | 'order' | 'dealdesk' | 'h6q' | 'flow' | 'supply' | 'shelf' | 'finance' | 'docs'
  | 'lifecycle' | 'audit' | 'period' | 'sync' | 'tax' | 'engine' | 'proof';

const GROUPS: { sec: string; items: { id: TabId; key: string; icon: keyof typeof I }[] }[] = [
  { sec: 'Sell', items: [
    { id: 'order', key: 'nav.order', icon: 'charger' },
    { id: 'dealdesk', key: 'nav.dealdesk', icon: 'flag' },
  ]},
  { sec: 'Plan (H6Q)', items: [
    { id: 'h6q', key: 'nav.h6q', icon: 'layers' },
    { id: 'flow', key: 'nav.flow', icon: 'trend' },
    { id: 'supply', key: 'nav.supply', icon: 'cpu' },
    { id: 'shelf', key: 'nav.shelf', icon: 'battery' },
    { id: 'engine', key: 'nav.engine', icon: 'pulse' },
  ]},
  { sec: 'Finance & control', items: [
    { id: 'finance', key: 'nav.finance', icon: 'sessions' },
    { id: 'docs', key: 'nav.docs', icon: 'list' },
    { id: 'lifecycle', key: 'nav.lifecycle', icon: 'clock' },
    { id: 'audit', key: 'nav.audit', icon: 'shield' },
    { id: 'period', key: 'nav.period', icon: 'clock' },
    { id: 'tax', key: 'nav.tax', icon: 'globe' },
  ]},
  { sec: 'Govern', items: [
    { id: 'sync', key: 'nav.sync', icon: 'sync' },
    { id: 'proof', key: 'nav.proof', icon: 'scale' },
  ]},
];
const ALL = GROUPS.flatMap((g) => g.items);

const PAGES: Record<TabId, React.ComponentType<{ token: string }>> = {
  order: OrderDesk, dealdesk: DealDesk, h6q: H6Q, flow: Flow, supply: SupplyWindow, shelf: Shelf,
  finance: Finance, docs: Documents, lifecycle: Lifecycle, audit: Auditability, period: Period,
  sync: Sync, tax: Tax, engine: Forecasting, proof: Proof,
};

export function App() {
  const { t } = useTranslation();
  const [token, setTokenState] = useState(() => sessionStorage.getItem('conduit_token') ?? '');
  const [route, setRoute] = useState<TabId>(() => (localStorage.getItem('conduit.route') as TabId) || 'order');
  const [theme, setTheme] = useState(() => localStorage.getItem('conduit.theme') || 'dark');
  const [menu, setMenu] = useState<string | null>(null);
  const [palOpen, setPalOpen] = useState(false);

  const setToken = (tk: string) => {
    if (tk) sessionStorage.setItem('conduit_token', tk);
    else sessionStorage.removeItem('conduit_token');
    setTokenState(tk);
  };
  const signOut = () => { signOutGoogle(); setToken(''); };

  useEffect(() => { document.documentElement.setAttribute('data-theme', theme); localStorage.setItem('conduit.theme', theme); }, [theme]);
  useEffect(() => { localStorage.setItem('conduit.route', route); }, [route]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalOpen((o) => !o); }
      if (e.key === 'Escape') { setMenu(null); setPalOpen(false); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (!token) return <SignIn onToken={setToken} />;

  const View = PAGES[route];
  const Bell = I.bell, Search = I.search, ChevR = I.chevR;

  return (
    <div className="app">
      <aside className="rail">
        <div className="brand">
          <span className="bolt">{I.bolt({ size: 20 })}</span>
          <span className="wm"><b>CONDUIT</b><span>Desk</span></span>
        </div>
        {GROUPS.map((g) => (
          <div key={g.sec}>
            <div className="nav-sec">{g.sec}</div>
            {g.items.map((it) => {
              const Icon = I[it.icon];
              return (
                <div key={it.id} className={'nav-it' + (route === it.id ? ' on' : '')} data-testid={'tab-' + it.id} onClick={() => setRoute(it.id)}>
                  {Icon({ size: 17 })}<span>{t(it.key)}</span>
                </div>
              );
            })}
          </div>
        ))}
        <div className="rail-foot">
          <div className="vchip" data-testid="session-chip"><div className="r1"><span className="dot" />{sessionEmail(token)}<ChevR /></div></div>
        </div>
      </aside>

      <div className="main">
        <header className="ctx">
          <div className="ctx-seg"><span className="k">Entity</span>Hypervolt Ltd (UK)</div>
          <div className="ctx-seg"><span className="k">Market</span>UK</div>
          <div className="ctx-seg"><span className="k">Period</span>2026-09<span className="chip ok" style={{ padding: '1px 7px', fontSize: 9.5 }}>open</span></div>
          <div className="ctx-right">
            <div className="kbtn" onClick={() => setPalOpen(true)}><Search size={13} />Jump to<kbd>⌘K</kbd></div>
            <div className="ibtn" title="Theme" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? '☾' : '☀'}</div>
            <div className="ibtn" title="Notifications"><Bell /></div>
            <select data-testid="locale-select" aria-label={t('common.language')} className="ctx-seg" defaultValue=""
              onChange={(e) => setLocale(e.target.value)}>
              <option value="" disabled>{t('common.language')}</option>
              {LOCALES.map((l) => (<option key={l} value={l}>{LOCALE_LABEL[l]}</option>))}
            </select>
            <button className="btn" data-testid="signout" onClick={signOut}>{t('app.signOut')}</button>
          </div>
        </header>
        <div className="work">{View ? <View token={token} /> : null}</div>
      </div>

      <Palette open={palOpen} onClose={() => setPalOpen(false)} go={(r) => setRoute(r)} t={t} />
    </div>
  );
}

function Palette({ open, onClose, go, t }: { open: boolean; onClose: () => void; go: (r: TabId) => void; t: (k: string) => string }) {
  const [q, setQ] = useState('');
  const [hot, setHot] = useState(0);
  const ref = useRef<HTMLInputElement>(null);
  const cmds = ALL.map((it) => ({ id: it.id, label: t(it.key) }));
  const hits = cmds.filter((c) => c.label.toLowerCase().includes(q.toLowerCase()));
  useEffect(() => { if (open) { setQ(''); setHot(0); setTimeout(() => ref.current?.focus(), 30); } }, [open]);
  if (!open) return null;
  const key = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setHot((h) => Math.min(hits.length - 1, h + 1)); }
    if (e.key === 'ArrowUp') { e.preventDefault(); setHot((h) => Math.max(0, h - 1)); }
    if (e.key === 'Enter' && hits[hot]) { go(hits[hot].id); onClose(); }
    if (e.key === 'Escape') onClose();
  };
  return (
    <div className="pal-scrim" onClick={onClose}>
      <div className="pal" onClick={(e) => e.stopPropagation()}>
        <input ref={ref} placeholder="Jump to a desk…" value={q} onChange={(e) => { setQ(e.target.value); setHot(0); }} onKeyDown={key} />
        <div className="list">
          {hits.map((c, i) => (
            <div key={c.id} className={'it' + (i === hot ? ' hot' : '')} onMouseEnter={() => setHot(i)} onClick={() => { go(c.id); onClose(); }}>
              <span>{c.label}</span><span className="sect">screen</span>
            </div>
          ))}
          {hits.length === 0 && <div style={{ padding: 16, color: 'var(--faint)', fontSize: 13 }}>No matches.</div>}
        </div>
        <div className="foot"><span><kbd>↑↓</kbd> navigate</span><span><kbd>↵</kbd> open</span><span><kbd>esc</kbd> close</span></div>
      </div>
    </div>
  );
}
