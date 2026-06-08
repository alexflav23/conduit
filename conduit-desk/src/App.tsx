import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { OrderDesk } from './OrderDesk';
import { DealDesk } from './DealDesk';
import { H6Q } from './H6Q';
import { Flow } from './Flow';
import { SupplyWindow } from './SupplyWindow';
import { Shelf } from './Shelf';
import { Finance } from './Finance';
import { Documents } from './Documents';
import { Auditability } from './Auditability';

const styles = stylex.create({
  page: { minHeight: '100vh', backgroundColor: colors.bg, color: colors.text, fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif', padding: '2rem' },
  title: { fontSize: '1.5rem', fontWeight: 700, marginBottom: '1rem' },
  accent: { color: colors.accent },
  nav: { display: 'flex', gap: '0.5rem', marginBottom: '1.25rem' },
  tab: { backgroundColor: 'transparent', color: colors.muted, border: `1px solid ${colors.border}`, borderRadius: '999px', padding: '0.4rem 1rem', fontWeight: 600, cursor: 'pointer' },
  tabActive: { backgroundColor: colors.accent, color: '#fff', border: `1px solid ${colors.accent}` },
  tokenRow: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '1.25rem', maxWidth: '560px' },
  label: { color: colors.muted, fontSize: '0.8rem', width: '110px' },
  input: { backgroundColor: colors.surface, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.5rem 0.7rem', fontSize: '0.95rem', flexGrow: 1 },
});

export function App() {
  const [token, setToken] = useState('dev:agent-e2e');
  const [view, setView] = useState<'order' | 'dealdesk' | 'h6q' | 'flow' | 'supply' | 'shelf' | 'finance' | 'docs' | 'audit'>('order');

  return (
    <div {...stylex.props(styles.page)}>
      <div {...stylex.props(styles.title)}><span {...stylex.props(styles.accent)}>Conduit</span> — Desk</div>
      <div {...stylex.props(styles.nav)}>
        <button {...stylex.props(styles.tab, view === 'order' && styles.tabActive)} data-testid="tab-order" onClick={() => setView('order')}>Order Desk</button>
        <button {...stylex.props(styles.tab, view === 'dealdesk' && styles.tabActive)} data-testid="tab-dealdesk" onClick={() => setView('dealdesk')}>Deal Desk</button>
        <button {...stylex.props(styles.tab, view === 'h6q' && styles.tabActive)} data-testid="tab-h6q" onClick={() => setView('h6q')}>H6Q</button>
        <button {...stylex.props(styles.tab, view === 'flow' && styles.tabActive)} data-testid="tab-flow" onClick={() => setView('flow')}>Flow</button>
        <button {...stylex.props(styles.tab, view === 'supply' && styles.tabActive)} data-testid="tab-supply" onClick={() => setView('supply')}>Supply</button>
        <button {...stylex.props(styles.tab, view === 'shelf' && styles.tabActive)} data-testid="tab-shelf" onClick={() => setView('shelf')}>Shelf</button>
        <button {...stylex.props(styles.tab, view === 'finance' && styles.tabActive)} data-testid="tab-finance" onClick={() => setView('finance')}>Finance</button>
        <button {...stylex.props(styles.tab, view === 'docs' && styles.tabActive)} data-testid="tab-docs" onClick={() => setView('docs')}>Documents</button>
        <button {...stylex.props(styles.tab, view === 'audit' && styles.tabActive)} data-testid="tab-audit" onClick={() => setView('audit')}>Audit</button>
      </div>
      <div {...stylex.props(styles.tokenRow)}>
        <span {...stylex.props(styles.label)}>Auth token</span>
        <input {...stylex.props(styles.input)} data-testid="token" value={token} onChange={(e) => setToken(e.target.value)} />
      </div>
      {view === 'order' ? <OrderDesk token={token} />
        : view === 'dealdesk' ? <DealDesk token={token} />
        : view === 'h6q' ? <H6Q token={token} />
        : view === 'flow' ? <Flow token={token} />
        : view === 'supply' ? <SupplyWindow token={token} />
        : view === 'shelf' ? <Shelf token={token} />
        : view === 'finance' ? <Finance token={token} />
        : view === 'docs' ? <Documents token={token} />
        : <Auditability token={token} />}
    </div>
  );
}
