import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from './api';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, Skeleton } from './kit/kit';
import { tableState } from './state';
import { I } from './kit/icons';

// 26 — Access / permission builder (`access`). spec/ui/26-access.md.
// The HubSpot-style governance control room (doc 05): compose a role visually — permissions per CRUD
// action × object type, scoped to market ∧ channel ∧ sector, plus the data layers it sees (the
// collapse-not-zero wall), plus the users it's assigned to. The edit ⊆ view rule is visible and enforced
// (a role can't get an edit it can't view — the toggle is blocked). Changes take effect on the next
// request (revocation is immediate). A live "view-as" preview answers "what will this role actually see?".
//
// Admin-only (view/create/edit:role) — this screen DEFINES the wall. Auto-load on mount + when ctx changes;
// no Load/Refresh buttons. Four states everywhere: loading / empty / forbidden(403) / error. Maker-checker:
// the edit⊆view guard is the structural invariant surfaced here.

type Res = { status: number; json: any } | null;

const DATA_LAYERS = ['volume', 'commercial', 'profitability', 'commission', 'inter_entity', 'pii'] as const;
const MONEY_LAYERS = ['commercial', 'profitability', 'commission'];

interface RoleDef {
  key: string;
  title: string;
  users?: { id: string; name: string }[];
  grants: Record<string, string[]>; // object -> actions
  scope: { market?: string[]; channel?: string[]; sector?: string[] };
  layers: string[];
}

interface AccessData {
  objects: string[];
  actions: string[];
  layers: string[];
  scopeOptions?: { market?: string[]; channel?: string[]; sector?: string[] };
  roles: RoleDef[];
}

const SCOPE_AXES = ['market', 'channel', 'sector'] as const;

export function Access({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [res, setRes] = useState<Res>(null);
  const [data, setData] = useState<AccessData | null>(null);
  const [roleKey, setRoleKey] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setRes(null);
    const r = await apiFetch('/api/v1/access/roles');
    setRes(r);
    const d: AccessData | null = r.status === 200 && r.json ? normalise(r.json) : null;
    setData(d);
    setRoleKey((prev) => (d && d.roles.length ? (prev && d.roles.some((x) => x.key === prev) ? prev : d.roles[0].key) : null));
  }, []);

  // Auto-load on mount + when the entity/market context shifts (the access wall is entity-scoped).
  useEffect(() => {
    void load();
  }, [load, ctx?.entity, ctx?.market]);

  const state = tableState(res, data?.roles);
  const rd = data?.roles.find((r) => r.key === roleKey) ?? null;

  const has = (obj: string, action: string): boolean => (rd?.grants[obj] || []).indexOf(action) >= 0;

  const toggleGrant = async (obj: string, action: string) => {
    if (!rd || busy) return;
    if (action !== 'view' && !has(obj, 'view')) {
      toast('edit ⊆ view — grant view first', 'warn');
      return;
    }
    setBusy(true);
    const next = has(obj, action);
    const r = await apiFetch(`/api/v1/access/roles/${rd.key}/grants`, {
      method: 'POST',
      body: JSON.stringify({ object: obj, action, enabled: !next }),
    });
    setBusy(false);
    if (r.status === 422) { toast(r.json?.message || 'edit ⊆ view violated', 'warn'); return; }
    if (r.status >= 400) { toast('Could not update grant', 'err'); return; }
    toast(`${!next ? 'Granted' : 'Revoked'} ${action}:${obj} — effective next request`, 'ok');
    void load();
  };

  const toggleLayer = async (layer: string) => {
    if (!rd || busy) return;
    setBusy(true);
    const next = rd.layers.indexOf(layer) >= 0;
    const r = await apiFetch(`/api/v1/access/roles/${rd.key}/layers`, {
      method: 'POST',
      body: JSON.stringify({ layer, enabled: !next }),
    });
    setBusy(false);
    if (r.status >= 400) { toast('Could not update layer', 'err'); return; }
    toast(`${!next ? 'Granted' : 'Withheld'} ${layer} layer`, 'ok');
    void load();
  };

  const toggleScope = async (axis: string, value: string) => {
    if (!rd || busy) return;
    setBusy(true);
    const cur = (rd.scope as any)[axis] || [];
    const next = cur.indexOf(value) >= 0;
    const r = await apiFetch(`/api/v1/access/roles/${rd.key}/scope`, {
      method: 'POST',
      body: JSON.stringify({ axis, value, enabled: !next }),
    });
    setBusy(false);
    if (r.status >= 400) { toast('Could not update scope', 'err'); return; }
    void load();
  };

  return (
    <div className="page" style={{ maxWidth: 1340 }}>
      <PageHead
        crumb="Permission builder (doc 05) · the governance control room"
        title="Access"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            Compose a role: permissions per CRUD action × object, scoped to market ∧ channel ∧ sector, plus the data
            layers it sees. The <b>edit ⊆ view</b> rule is enforced; revocation takes effect on the next request.
          </span>
        }
      />

      {state === 'forbidden' && (
        <Card title="Access builder" icon={I.shield}>
          <LayerNote>hidden — requires <b>view:role</b>. The access builder is admin-only.</LayerNote>
        </Card>
      )}

      {state === 'error' && (
        <Card title="Access builder" icon={I.shield}>
          <div className="banner danger"><span>{I.alert()}</span><span>Could not load roles. The access service may be unavailable.</span></div>
        </Card>
      )}

      {state === 'loading' && (
        <div className="grid" style={{ gridTemplateColumns: '220px 1fr', alignItems: 'start', gap: 16 }}>
          <Card style={{ padding: 12 }}><Skeleton lines={5} /></Card>
          <Card title="Permission matrix" icon={I.shield}><Skeleton lines={6} /></Card>
        </div>
      )}

      {state === 'empty' && (
        <Card title="Roles" icon={I.shield}>
          <table className="tbl"><tbody><EmptyRow cols={1}>No roles defined yet — create the first role to compose the wall.</EmptyRow></tbody></table>
        </Card>
      )}

      {state === 'ready' && data && (
        <div className="grid" style={{ gridTemplateColumns: '220px 1fr', alignItems: 'start', gap: 16 }}>
          {/* ---- role list ---- */}
          <Card style={{ padding: 0 }} className="tablewrap">
            <div className="mini" style={{ padding: '12px 14px 6px' }}>Roles · {data.roles.length}</div>
            <table className="tbl sel">
              <tbody>
                {data.roles.map((r) => {
                  const n = r.users?.length ?? 0;
                  return (
                    <tr key={r.key} className={roleKey === r.key ? 'sel' : ''} style={{ cursor: 'pointer' }} onClick={() => setRoleKey(r.key)}>
                      <td>
                        <b style={{ fontSize: 12.5 }}>{r.title}</b>
                        <div className="dim" style={{ fontSize: 10.5 }}>{n} user{n === 1 ? '' : 's'} · {(r.grants && Object.keys(r.grants).filter((o) => (r.grants[o] || []).length).length) || 0} objects</div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>

          {/* ---- editor ---- */}
          {rd && (
            <div>
              {/* permission matrix */}
              <Card
                title={rd.title + ' · permission matrix'}
                icon={I.shield}
                aux="cells toggle the grant · edit ⊆ view enforced"
                style={{ padding: 0, marginBottom: 14 }}
                className="tablewrap"
              >
                <table className="tbl">
                  <thead>
                    <tr>
                      <th>Object</th>
                      {data.actions.map((a) => (
                        <th key={a} style={{ textAlign: 'center', textTransform: 'capitalize' }}>{a}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.objects.length === 0 && <EmptyRow cols={data.actions.length + 1}>No object types configured.</EmptyRow>}
                    {data.objects.map((obj) => (
                      <tr key={obj} style={{ cursor: 'default' }}>
                        <td className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{obj}</td>
                        {data.actions.map((a) => {
                          const on = has(obj, a);
                          const blocked = a !== 'view' && !has(obj, 'view');
                          return (
                            <td key={a} style={{ textAlign: 'center' }}>
                              <button
                                onClick={() => toggleGrant(obj, a)}
                                disabled={busy}
                                title={blocked ? 'requires view first (edit ⊆ view)' : on ? 'revoke (effective next request)' : 'grant'}
                                style={{
                                  width: 26, height: 26, borderRadius: 7,
                                  border: '1px solid ' + (on ? 'transparent' : 'var(--border)'),
                                  background: on ? 'var(--accent)' : blocked ? 'var(--bg-2)' : 'transparent',
                                  color: on ? 'var(--on-accent)' : 'var(--faint)',
                                  cursor: busy ? 'default' : 'pointer',
                                  display: 'inline-grid', placeItems: 'center',
                                  opacity: blocked ? 0.4 : 1,
                                }}
                              >
                                {on ? I.check({ size: 14 }) : blocked ? '·' : ''}
                              </button>
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>

              <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start', gap: 14, marginBottom: 14 }}>
                {/* scope axes */}
                <Card title="Scope axes" icon={I.globe} aux="market ∧ channel ∧ sector (empty = unconstrained)">
                  {SCOPE_AXES.map((ax) => {
                    const sel: string[] = (rd.scope as any)[ax] || [];
                    const opts: string[] = (data.scopeOptions as any)?.[ax] || sel;
                    return (
                      <div key={ax} style={{ marginBottom: 12 }}>
                        <div className="fldlabel" style={{ marginBottom: 6, textTransform: 'capitalize' }}>{ax}</div>
                        <div className="row g6 wrap">
                          {opts.length === 0 && <span className="dim" style={{ fontSize: 11.5 }}>no options</span>}
                          {opts.map((v) => {
                            const on = sel.indexOf(v) >= 0;
                            return (
                              <button
                                key={v}
                                onClick={() => toggleScope(ax, v)}
                                disabled={busy}
                                className={'chip ' + (on ? 'accent' : 'neutral')}
                                style={{ cursor: busy ? 'default' : 'pointer', padding: '3px 10px', opacity: on ? 1 : 0.55 }}
                              >
                                {on ? I.check({ size: 11 }) : <span className="d" />}{v}
                              </button>
                            );
                          })}
                          {sel.length === 0 && <span className="dim" style={{ fontSize: 11.5 }}>unconstrained (all {ax}s)</span>}
                        </div>
                      </div>
                    );
                  })}
                  <LayerNote>“UK-Wholesale-Energy view-only” = market <b>UK</b> ∧ channel <b>wholesale</b> ∧ sector <b>Energy</b>, with only the <b>view</b> column ticked.</LayerNote>
                </Card>

                {/* data layers */}
                <Card title="Data layers" icon={I.layers} aux="which money layers this role sees (collapse-not-zero)">
                  <div className="row g8 wrap">
                    {(data.layers && data.layers.length ? data.layers : (DATA_LAYERS as readonly string[])).map((l) => {
                      const on = rd.layers.indexOf(l) >= 0;
                      return (
                        <button
                          key={l}
                          onClick={() => toggleLayer(l)}
                          disabled={busy}
                          className={'chip ' + (on ? (l === 'pii' ? 'danger' : 'ok') : 'neutral')}
                          style={{ cursor: busy ? 'default' : 'pointer', padding: '5px 11px', opacity: on ? 1 : 0.55 }}
                        >
                          {on ? I.check({ size: 12 }) : <span className="d" />}{l}
                        </button>
                      );
                    })}
                  </div>
                  <LayerNote>A withheld layer is <b>absent</b> from this role’s payload — money widgets collapse, never show £0.00.</LayerNote>
                </Card>
              </div>

              {/* user assignment */}
              <Card title="Assigned users" icon={I.user} aux="who holds this role (per-user scope can narrow further)" style={{ marginBottom: 14, padding: 0 }} className="tablewrap">
                <table className="tbl">
                  <thead><tr><th>User</th><th style={{ textAlign: 'right' }}>Effective scope</th></tr></thead>
                  <tbody>
                    {(!rd.users || rd.users.length === 0) && <EmptyRow cols={2}>No users hold this role yet.</EmptyRow>}
                    {(rd.users || []).map((u) => (
                      <tr key={u.id}>
                        <td><b style={{ fontSize: 12.5 }}>{u.name}</b><div className="dim mono" style={{ fontSize: 10.5 }}>{u.id}</div></td>
                        <td style={{ textAlign: 'right' }}><span className="dim" style={{ fontSize: 11.5 }}>inherits role scope</span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>

              {/* view-as preview — the killer affordance */}
              <ViewAsPreview rd={rd} objects={data.objects} has={has} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function ViewAsPreview({ rd, objects, has }: { rd: RoleDef; objects: string[]; has: (o: string, a: string) => boolean }) {
  const visibleObjects = objects.filter((o) => has(o, 'view'));
  const moneyLayers = MONEY_LAYERS.filter((l) => rd.layers.indexOf(l) >= 0);
  const scopeStr = SCOPE_AXES
    .map((ax) => { const s: string[] = (rd.scope as any)[ax] || []; return s.length ? `${ax}: ${s.join('/')}` : null; })
    .filter(Boolean) as string[];
  const seesPii = rd.layers.indexOf('pii') >= 0;

  return (
    <Card title="View-as preview" icon={I.user} aux="what will this role actually see?" style={{ background: 'var(--bg-2)' }}>
      <div className="grid" style={{ gridTemplateColumns: '1fr 1fr 1fr', gap: 14 }}>
        <div>
          <div className="fldlabel" style={{ marginBottom: 6 }}>Can open ({visibleObjects.length})</div>
          <div className="row g6 wrap">
            {visibleObjects.length === 0
              ? <span className="dim" style={{ fontSize: 12 }}>sees nothing — deny by default</span>
              : visibleObjects.map((o) => <span key={o} className="chip neutral" style={{ fontSize: 10.5 }}>{o}</span>)}
          </div>
        </div>
        <div>
          <div className="fldlabel" style={{ marginBottom: 6 }}>Money it sees</div>
          <div className="row g6 wrap">
            {moneyLayers.length
              ? moneyLayers.map((l) => <span key={l} className="chip ok" style={{ fontSize: 10.5 }}>{l}</span>)
              : <span className="dim" style={{ fontSize: 12 }}>volume only — all money collapses</span>}
          </div>
          {seesPii && <div style={{ marginTop: 6 }}><span className="chip danger" style={{ fontSize: 10.5 }}>pii (contacts visible)</span></div>}
        </div>
        <div>
          <div className="fldlabel" style={{ marginBottom: 6 }}>Restricted to</div>
          <div className="row g6 wrap">
            {scopeStr.length
              ? scopeStr.map((s) => <span key={s} className="chip accent" style={{ fontSize: 10.5 }}>{s}</span>)
              : <span className="dim" style={{ fontSize: 12 }}>all markets / channels / sectors</span>}
          </div>
        </div>
      </div>
      <LayerNote>To see the real desk as this role, use the user menu (top-right) → switch user. Hidden layers and tabs change live; the wall is enforced server-side.</LayerNote>
    </Card>
  );
}

// Normalise either an array-of-roles shape or a keyed-object shape into the editor model. Tolerant of the
// dev backend returning roles as a map (legacy prototype) or as an array (REST list).
function normalise(json: any): AccessData {
  const objects: string[] = Array.isArray(json.objects) ? json.objects : [];
  const actions: string[] = Array.isArray(json.actions) ? json.actions : ['view', 'create', 'edit', 'approve', 'delete', 'export'];
  const layers: string[] = Array.isArray(json.layers) ? json.layers : (DATA_LAYERS as readonly string[]).slice();
  const scopeOptions = json.scopeOptions || json.scope_options || undefined;

  let rolesRaw = json.roles;
  let roles: RoleDef[];
  if (Array.isArray(rolesRaw)) {
    roles = rolesRaw.map((r: any) => toRole(r.key ?? r.id ?? r.title, r));
  } else if (rolesRaw && typeof rolesRaw === 'object') {
    roles = Object.keys(rolesRaw).map((k) => toRole(k, rolesRaw[k]));
  } else {
    roles = [];
  }
  return { objects, actions, layers, scopeOptions, roles };
}

function toRole(key: string, r: any): RoleDef {
  return {
    key,
    title: r.title || r.name || key,
    users: Array.isArray(r.users)
      ? r.users.map((u: any) => (typeof u === 'string' ? { id: u, name: u } : { id: u.id ?? u.keycloakId ?? '', name: u.name ?? u.id ?? '' }))
      : [],
    grants: r.grants && typeof r.grants === 'object' ? r.grants : {},
    scope: r.scope && typeof r.scope === 'object' ? r.scope : {},
    layers: Array.isArray(r.layers) ? r.layers : [],
  };
}
