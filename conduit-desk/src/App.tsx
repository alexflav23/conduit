import React, { useState, useEffect, useRef } from 'react';
import { Routes, Route, Navigate, useNavigate, useLocation, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LOCALES, LOCALE_LABEL, setLocale } from './i18n';
import { I } from './kit/icons';
import { useToast, type DataLayer } from './kit/kit';
import { OrderDesk } from './OrderDesk';
import { DealDesk } from './DealDesk';
import { Returns } from './Returns';
import { CRM } from './CRM';
import { Reseller } from './Reseller';
import { H6Q } from './H6Q';
import { Flow } from './Flow';
import { Forecasting } from './Forecasting';
import { ForecastRuns } from './ForecastRuns';
import { SupplyWindow } from './SupplyWindow';
import { Shelf } from './Shelf';
import { Inventory } from './Inventory';
import { Purchasing } from './Purchasing';
import { BatchGenealogy } from './BatchGenealogy';
import { Activation } from './Activation';
import { Finance } from './Finance';
import { Commission } from './Commission';
import { Documents } from './Documents';
import { Lifecycle } from './Lifecycle';
import { Tax } from './Tax';
import { Intercompany } from './Intercompany';
import { Procurement } from './Procurement';
import { Auditability } from './Auditability';
import { Period } from './Period';
import { Sync } from './Sync';
import { Proof } from './Proof';
import { Access } from './Access';
import { Notifications } from './Notifications';
import { AccountPage } from './AccountPage';
import { useAuth } from 'react-oidc-context';
import { SignIn, sessionEmail } from './SignIn';
import { setOidcToken, devToken, setDevToken } from './lib/auth';

// The Conduit Desk shell (spec/ui/README.md "Shell affordances"; structure mirrors .design-ref/desk-shell.jsx,
// Hypervolt dark-first). A grouped rail, the working-context bar (entity/market/period/scenario with the
// period open/closed/locked badge), the role / view-as switcher that surfaces the viewer's data layers, the
// notifications bell, the ⌘K command palette, the dark/light toggle and the session chip. The real, API-wired
// feature pages render inside `.work`; each receives { token, role, ctx, toast } and auto-loads its own data
// on mount + on ctx/route change — there is no global load button. The real SignIn gates entry. All e2e
// testids (tab-*, session-chip, signout, locale-select, token via SignIn) are preserved.

type TabId =
  | 'order' | 'dealdesk' | 'returns' | 'crm' | 'reseller'
  | 'h6q' | 'flow' | 'supply' | 'shelf' | 'engine' | 'runs'
  | 'inventory' | 'purchasing' | 'batch' | 'activation'
  | 'finance' | 'commission' | 'docs' | 'lifecycle' | 'tax'
  | 'intercompany' | 'procurement'
  | 'audit' | 'period' | 'sync' | 'proof' | 'access' | 'notifications';

interface NavItem { id: TabId; label: string; icon: keyof typeof I }

const GROUPS: { sec: string; items: NavItem[] }[] = [
  { sec: 'Sell', items: [
    { id: 'order', label: 'Order Desk', icon: 'charger' },
    { id: 'dealdesk', label: 'Deal Desk', icon: 'flag' },
    { id: 'returns', label: 'Returns', icon: 'arrowBack' },
    { id: 'crm', label: 'CRM', icon: 'user' },
    { id: 'reseller', label: 'Reseller portal', icon: 'globe' },
  ]},
  { sec: 'Plan', items: [
    { id: 'h6q', label: 'Demand (H6Q)', icon: 'layers' },
    { id: 'flow', label: 'Flow', icon: 'trend' },
    { id: 'supply', label: 'Supply window', icon: 'cpu' },
    { id: 'shelf', label: 'Shelf', icon: 'battery' },
    { id: 'engine', label: 'Forecast Engine', icon: 'pulse' },
    { id: 'runs', label: 'Forecast Runs', icon: 'clock' },
  ]},
  { sec: 'Supply', items: [
    { id: 'inventory', label: 'Inventory', icon: 'grid' },
    { id: 'purchasing', label: 'Purchasing', icon: 'download' },
    { id: 'batch', label: 'Batch & Genealogy', icon: 'map' },
    { id: 'activation', label: 'Activation', icon: 'wifi' },
  ]},
  { sec: 'Finance', items: [
    { id: 'finance', label: 'Finance', icon: 'sessions' },
    { id: 'commission', label: 'Commission', icon: 'up' },
    { id: 'docs', label: 'Documents', icon: 'list' },
    { id: 'lifecycle', label: 'Lifecycle', icon: 'clock' },
    { id: 'tax', label: 'Tax', icon: 'globe' },
  ]},
  { sec: 'Treasury', items: [
    { id: 'intercompany', label: 'Intercompany', icon: 'grid' },
    { id: 'procurement', label: 'Procurement', icon: 'layers' },
  ]},
  { sec: 'Govern', items: [
    { id: 'audit', label: 'Auditability', icon: 'shield' },
    { id: 'period', label: 'Period', icon: 'clock' },
    { id: 'sync', label: 'Sync', icon: 'sync' },
    { id: 'proof', label: 'Proof Center', icon: 'scale' },
    { id: 'access', label: 'Access', icon: 'settings' },
    { id: 'notifications', label: 'Notifications', icon: 'bell' },
  ]},
];
const ALL: NavItem[] = GROUPS.flatMap((g) => g.items);

type ViewProps = { token: string; role: Role; ctx: Ctx; toast: (text: string, kind?: 'ok' | 'warn' | 'err') => void };

const PAGES: Record<TabId, React.ComponentType<any>> = {
  order: OrderDesk, dealdesk: DealDesk, returns: Returns, crm: CRM, reseller: Reseller,
  h6q: H6Q, flow: Flow, supply: SupplyWindow, shelf: Shelf, engine: Forecasting, runs: ForecastRuns,
  inventory: Inventory, purchasing: Purchasing, batch: BatchGenealogy, activation: Activation,
  finance: Finance, commission: Commission, docs: Documents, lifecycle: Lifecycle, tax: Tax,
  intercompany: Intercompany, procurement: Procurement,
  audit: Auditability, period: Period, sync: Sync, proof: Proof, access: Access, notifications: Notifications,
};

// Renders the feature page for the /:tab route; an unknown tab falls back to the Order Desk.
function TabView(props: ViewProps) {
  const { tab } = useParams();
  const Page = tab ? PAGES[tab as TabId] : undefined;
  if (!Page) return <Navigate to="/order" replace />;
  return <Page key={tab + '|' + props.token} {...props} />;
}

// The viewer's identity + data-layer grant. The bearer (set by SignIn) is the server-side projection key; the
// layer set here mirrors what the server would have projected in so the shell can SHOW the viewer's layers in
// the role chip / view-as switcher (the wall itself is enforced server-side — withheld layers never arrive).
export interface Role { token: string; name: string; title: string; layers: DataLayer[] }

const ALL_LAYERS: DataLayer[] = ['volume', 'commercial', 'profitability', 'commission', 'inter_entity', 'pii'];

const ROLE_TABLE: Record<string, { name: string; title: string; layers: DataLayer[] }> = {
  'dev:ceo-e2e': { name: 'Alex Rivera', title: 'CEO', layers: ALL_LAYERS },
  'dev:finance-e2e': { name: 'Sam Okafor', title: 'Finance', layers: ['volume', 'commercial', 'profitability', 'inter_entity'] },
  'dev:agent-e2e': { name: 'Jordan Lee', title: 'Sales agent', layers: ['volume', 'commercial', 'commission'] },
  'dev:tax-e2e': { name: 'Priya Shah', title: 'Tax', layers: ['volume', 'commercial', 'inter_entity'] },
  'dev:admin-e2e': { name: 'Robin Cole', title: 'Admin', layers: ALL_LAYERS },
};
const VIEW_AS_TOKENS = Object.keys(ROLE_TABLE);

function roleOf(token: string): Role {
  const r = ROLE_TABLE[token];
  if (r) return { token, ...r };
  // A Google JWT (or any non-dev session): name from the email claim, full layers (server still walls).
  return { token, name: sessionEmail(token), title: 'Signed in', layers: ALL_LAYERS };
}

const initials = (s: string) => s.split(/[ @.]/).filter(Boolean).map((w) => w[0]).join('').slice(0, 2).toUpperCase();

export interface Ctx { entity: string; market: string; period: string; scenario: string }
const CTX_OPTS = {
  entity: ['Hypervolt Ltd (UK)', 'Hypervolt Manufacturing (UK)', 'Hypervolt IE'],
  // Only markets that actually exist in the data (year-1 is UK-only, doc 08); offering IE 400'd every scoped call.
  market: ['UK'],
  scenario: ['P20', 'P50', 'P80'],
};
const PERIODS: { key: string; status: 'open' | 'closed' | 'locked' }[] = [
  { key: '2026-09', status: 'open' },
  { key: '2026-08', status: 'locked' },
  { key: '2026-07', status: 'locked' },
];
const periodStatus = (key: string) => PERIODS.find((p) => p.key === key)?.status ?? 'open';
const STATUS_CHIP: Record<string, string> = { open: 'ok', closed: 'warn', locked: 'accent' };

function Menu({ children, onClose, style }: { children: React.ReactNode; onClose: () => void; style?: React.CSSProperties }) {
  return (
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 55 }} onClick={onClose} />
      <div className="menu" style={style} onClick={(e) => e.stopPropagation()}>{children}</div>
    </>
  );
}

export function App() {
  const { t } = useTranslation();
  const auth = useAuth();
  // The bearer is a dev-door override if set, else the live OIDC access token. The OIDC token lifecycle (PKCE,
  // storage, silent renew off the refresh token) is owned by react-oidc-context — no hand-rolled timers here.
  const [dev, setDev] = useState(devToken());
  useEffect(() => { setOidcToken(auth.user?.access_token); }, [auth.user]);
  const token = dev || auth.user?.access_token || '';
  const navigate = useNavigate();
  const location = useLocation();
  // The URL is the source of truth for the active view (react-router). The account page lives at /account/:id;
  // for nav highlighting it keeps the Shelf tab lit since that's where accounts are reached from.
  const seg = location.pathname.split('/')[1] || 'order';
  const route = (seg === 'account' ? 'shelf' : seg) as TabId;
  const setRoute = (r: TabId) => navigate('/' + r);
  const [theme, setTheme] = useState(() => localStorage.getItem('conduit.theme') || 'dark');
  const [ctx, setCtx] = useState<Ctx>(() => {
    const base = { entity: 'Hypervolt Ltd (UK)', market: 'UK', period: '2026-09', scenario: 'P50' };
    try {
      const merged = { ...base, ...JSON.parse(localStorage.getItem('conduit.ctx') || '{}') };
      // Coerce a stale/invalid market or scenario (e.g. a previously-selected "IE") back to a real option.
      if (!CTX_OPTS.market.includes(merged.market)) merged.market = 'UK';
      if (!CTX_OPTS.scenario.includes(merged.scenario)) merged.scenario = 'P50';
      return merged;
    } catch {
      return base;
    }
  });
  const [menu, setMenu] = useState<string | null>(null);
  const [palOpen, setPalOpen] = useState(false);
  const [toastNode, toast] = useToast();

  // The non-prod quick-doors set a dev:<id> override; real users go through Keycloak.
  const enterDev = (tk: string) => { setDevToken(tk); setDev(tk); };
  const signOut = () => {
    if (dev) { setDevToken(''); setDev(''); return; }
    setOidcToken(undefined);
    auth.signoutRedirect().catch(() => auth.removeUser());
  };

  useEffect(() => { document.documentElement.setAttribute('data-theme', theme); localStorage.setItem('conduit.theme', theme); }, [theme]);
  useEffect(() => { localStorage.setItem('conduit.ctx', JSON.stringify(ctx)); }, [ctx]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalOpen((o) => !o); return; }
      if (e.key === 'Escape') { setMenu(null); setPalOpen(false); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (!token) {
    if (auth.isLoading || auth.activeNavigator) {
      return <div className="signin" data-testid="signin-loading"><div className="panel"><div className="sub">Signing in…</div></div></div>;
    }
    // kc_idp_hint sends the user straight to Google rather than Keycloak's own login form.
    return <SignIn signIn={() => auth.signinRedirect({ extraQueryParams: { kc_idp_hint: 'google' } })} error={auth.error?.message} />;
  }

  const role = roleOf(token);
  const Bell = I.bell, Search = I.search, ChevR = I.chevR;
  const pStatus = periodStatus(ctx.period);

  const pickCtx = (k: keyof Ctx, v: string) => { setCtx({ ...ctx, [k]: v }); setMenu(null); toast(`${k[0].toUpperCase()}${k.slice(1)} → ${v}`); };
  const viewAs = (tk: string) => { enterDev(tk); setMenu(null); toast(`Now viewing as ${roleOf(tk).title}`); };
  const go = (r: TabId) => { setRoute(r); setMenu(null); };

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
                  {Icon({ size: 17 })}<span>{it.label}</span>
                </div>
              );
            })}
          </div>
        ))}
        <div className="rail-foot">
          <div style={{ position: 'relative' }}>
            <div className="vchip" data-testid="session-chip" onClick={() => setMenu(menu === 'railuser' ? null : 'railuser')}>
              <div className="r1"><span className="dot" />{sessionEmail(token)}<ChevR /></div>
              <div className="layers">{ALL_LAYERS.map((l) => <span key={l} className={'lyr' + (role.layers.includes(l) ? ' has' : '')}>{l}</span>)}</div>
            </div>
            {menu === 'railuser' && (
              <Menu onClose={() => setMenu(null)} style={{ bottom: '108%', left: 0, right: 0 }}>
                <div className="mh">Signed in as</div>
                <div className="mi" style={{ cursor: 'default' }}><span className="av">{initials(role.name)}</span><div><div style={{ fontWeight: 600 }}>{role.name}</div><div className="dim" style={{ fontSize: 11 }}>{role.title} · {role.layers.length} layers</div></div></div>
                <div className="sep" />
                <div className="mh">View as</div>
                {VIEW_AS_TOKENS.map((tk) => {
                  const u = ROLE_TABLE[tk];
                  return (
                    <div key={tk} className={'mi' + (token === tk ? ' on' : '')} onClick={() => viewAs(tk)}>
                      <span className="av" style={{ width: 22, height: 22, fontSize: 9 }}>{initials(u.name)}</span>{u.title}
                      {token === tk && <span className="ck">{I.check({ size: 14 })}</span>}
                    </div>
                  );
                })}
                <div className="sep" />
                <div className="mi" onClick={signOut}>{I.arrowR({})}{t('app.signOut')}</div>
                <div className="note">The bearer token sets the server-side projection. Hidden layers are absent from the payload — money columns and whole tabs change.</div>
              </Menu>
            )}
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="ctx">
          <div style={{ position: 'relative' }}>
            <div className="ctx-seg" onClick={() => setMenu(menu === 'entity' ? null : 'entity')}><span className="k">Entity</span>{ctx.entity}<ChevR /></div>
            {menu === 'entity' && <Menu onClose={() => setMenu(null)} style={{ top: '110%', left: 0 }}>{CTX_OPTS.entity.map((o) => <div key={o} className={'mi' + (ctx.entity === o ? ' on' : '')} onClick={() => pickCtx('entity', o)}>{o}{ctx.entity === o && <span className="ck">{I.check({ size: 14 })}</span>}</div>)}</Menu>}
          </div>
          <div style={{ position: 'relative' }}>
            <div className="ctx-seg" onClick={() => setMenu(menu === 'market' ? null : 'market')}><span className="k">Market</span>{ctx.market}<ChevR /></div>
            {menu === 'market' && <Menu onClose={() => setMenu(null)} style={{ top: '110%', left: 0 }}>{CTX_OPTS.market.map((o) => <div key={o} className={'mi' + (ctx.market === o ? ' on' : '')} onClick={() => pickCtx('market', o)}>{o}{ctx.market === o && <span className="ck">{I.check({ size: 14 })}</span>}</div>)}</Menu>}
          </div>
          <div style={{ position: 'relative' }}>
            <div className="ctx-seg" onClick={() => setMenu(menu === 'period' ? null : 'period')}><span className="k">Period</span>{ctx.period}<span className={'chip ' + (STATUS_CHIP[pStatus] || 'neutral')} style={{ padding: '1px 7px', fontSize: 9.5 }}>{pStatus}</span><ChevR /></div>
            {menu === 'period' && <Menu onClose={() => setMenu(null)} style={{ top: '110%', left: 0, minWidth: 220 }}>
              {PERIODS.map((p) => (
                <div key={p.key} className={'mi' + (ctx.period === p.key ? ' on' : '')} onClick={() => { if (p.status === 'open') pickCtx('period', p.key); else { setCtx({ ...ctx, period: p.key }); setMenu(null); toast(`${p.key} is ${p.status} — read-only`, 'warn'); } }}>
                  {p.key}<span className={'chip ' + (STATUS_CHIP[p.status] || 'neutral')} style={{ marginLeft: 6, padding: '1px 6px' }}>{p.status}</span>
                  {ctx.period === p.key && <span className="ck">{I.check({ size: 14 })}</span>}
                </div>
              ))}
            </Menu>}
          </div>
          <div style={{ position: 'relative' }}>
            <div className="ctx-seg" onClick={() => setMenu(menu === 'scenario' ? null : 'scenario')}><span className="k">Scenario</span>{ctx.scenario}<ChevR /></div>
            {menu === 'scenario' && <Menu onClose={() => setMenu(null)} style={{ top: '110%', left: 0, minWidth: 220 }}>
              <div className="mh">Demand band</div>
              {[['P20', 'blue-sky upside'], ['P50', 'mid case'], ['P80', 'conservative']].map((o) => <div key={o[0]} className={'mi' + (ctx.scenario === o[0] ? ' on' : '')} onClick={() => pickCtx('scenario', o[0])}>{o[0]}<span className="dim" style={{ fontSize: 11, marginLeft: 6 }}>{o[1]}</span>{ctx.scenario === o[0] && <span className="ck">{I.check({ size: 14 })}</span>}</div>)}
            </Menu>}
          </div>

          <div className="ctx-right">
            <div className="kbtn" onClick={() => setPalOpen(true)}><Search size={13} />Jump to<kbd>⌘K</kbd></div>
            <div className="ibtn" title="Theme" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? '☾' : '☀'}</div>
            <div style={{ position: 'relative' }}>
              <div className="ibtn" title="Notifications" onClick={() => setMenu(menu === 'notif' ? null : 'notif')}><Bell /></div>
              {menu === 'notif' && (
                <Menu onClose={() => setMenu(null)} style={{ top: '110%', right: 0, minWidth: 320, padding: 7 }}>
                  <div className="mh">Needs attention</div>
                  <div className="mi" onClick={() => go('dealdesk')}>ADLP exceptions awaiting decision</div>
                  <div className="mi" onClick={() => go('supply')}>Supply divergence warnings</div>
                  <div className="mi" onClick={() => go('notifications')}>Open all notifications</div>
                </Menu>
              )}
            </div>
            <select data-testid="locale-select" aria-label={t('common.language')} className="ctx-seg" defaultValue=""
              onChange={(e) => setLocale(e.target.value)}>
              <option value="" disabled>{t('common.language')}</option>
              {LOCALES.map((l) => (<option key={l} value={l}>{LOCALE_LABEL[l]}</option>))}
            </select>
            <button className="avatar" title={role.name} onClick={() => setMenu(menu === 'user' ? null : 'user')}>{initials(role.name)}</button>
            <button className="btn sm" data-testid="signout" title={t('app.signOut')} onClick={signOut}>{t('app.signOut')}</button>
            {menu === 'user' && (
              <div style={{ position: 'absolute', top: '100%', right: 0, marginTop: 8 }}>
                <Menu onClose={() => setMenu(null)} style={{ position: 'static', minWidth: 240 }}>
                  <div className="mh">Signed in as</div>
                  <div className="mi" style={{ cursor: 'default' }}><span className="av">{initials(role.name)}</span><div><div style={{ fontWeight: 600 }}>{role.name}</div><div className="dim" style={{ fontSize: 11 }}>{role.title} · {role.layers.length} layers</div></div></div>
                  <div className="sep" />
                  <div className="mh">View as</div>
                  {VIEW_AS_TOKENS.map((tk) => {
                    const u = ROLE_TABLE[tk];
                    return (
                      <div key={tk} className={'mi' + (token === tk ? ' on' : '')} onClick={() => viewAs(tk)}>
                        <span className="av" style={{ width: 22, height: 22, fontSize: 9 }}>{initials(u.name)}</span>{u.title}
                        {token === tk && <span className="ck">{I.check({ size: 14 })}</span>}
                      </div>
                    );
                  })}
                  <div className="sep" />
                  <div className="mi" onClick={signOut}>{I.arrowR({})}{t('app.signOut')}</div>
                </Menu>
              </div>
            )}
          </div>
        </header>
        <div className="work">
          <Routes>
            <Route path="/" element={<Navigate to="/order" replace />} />
            <Route path="/account/:id" element={<AccountPage key={'account|' + token} token={token} role={role} ctx={ctx} toast={toast} />} />
            <Route path="/:tab" element={<TabView token={token} role={role} ctx={ctx} toast={toast} />} />
          </Routes>
        </div>
      </div>

      <Palette open={palOpen} onClose={() => setPalOpen(false)} go={(r) => setRoute(r)} />
      {toastNode}
    </div>
  );
}

function Palette({ open, onClose, go }: { open: boolean; onClose: () => void; go: (r: TabId) => void }) {
  const [q, setQ] = useState('');
  const [hot, setHot] = useState(0);
  const ref = useRef<HTMLInputElement>(null);
  const cmds: { id: TabId; label: string; sect: string }[] = ALL.map((it) => ({ id: it.id, label: it.label, sect: 'screen' })).concat([
    { id: 'dealdesk', label: 'ORD-DEALDESK — ADLP exception', sect: 'record' },
    { id: 'docs', label: 'INV-FLOW — recognised invoice', sect: 'record' },
    { id: 'supply', label: 'Volex — supply commitments', sect: 'record' },
  ]);
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
        <input ref={ref} placeholder="Jump to a desk or record…" value={q} onChange={(e) => { setQ(e.target.value); setHot(0); }} onKeyDown={key} />
        <div className="list">
          {hits.map((c, i) => (
            <div key={c.label} className={'it' + (i === hot ? ' hot' : '')} onMouseEnter={() => setHot(i)} onClick={() => { go(c.id); onClose(); }}>
              <span>{c.label}</span><span className="sect">{c.sect}</span>
            </div>
          ))}
          {hits.length === 0 && <div style={{ padding: 16, color: 'var(--faint)', fontSize: 13 }}>No matches.</div>}
        </div>
        <div className="foot"><span><kbd>↑↓</kbd> navigate</span><span><kbd>↵</kbd> open</span><span><kbd>esc</kbd> close</span></div>
      </div>
    </div>
  );
}

export type { ViewProps };
