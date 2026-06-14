import React, { useMemo, useState } from 'react';
import { useApi } from './lib/query';
import { PageHead, Card, LayerNote, EmptyRow, SkeletonRow, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// 26 — Access / permission builder (`access`). spec/ui/26-access.md.
// The HubSpot-style governance control room (doc 05): the preset roles that compose the wall, and a live
// read of what the *signed-in* principal can actually do — permissions per CRUD action × object type, plus
// the data layers the role sees (the collapse-not-zero wall). The matrix is the policy decision surfaced:
// edit ⊆ view is the structural invariant (an edit/approve/delete grant always sits behind a view grant).
//
// Backed by the real policy service: GET /api/v1/admin/roles (the role catalogue, admin-only) and
// GET /api/v1/access/me (the viewer's effective grants — list of "action:object" strings). Auto-loads via
// React Query; no Load/Refresh buttons. Four states everywhere: loading / empty / forbidden(403) / error.
// The grant/layer/scope authoring mutations the prototype guessed have no backend route in this environment —
// the matrix renders the effective policy read-only rather than faking writes.

// The CRUD columns the matrix renders, in policy order. The wall is "view first": every other action is
// blocked unless view is held for the same object (edit ⊆ view).
const ACTIONS = ['view', 'create', 'edit', 'approve', 'delete', 'export'] as const;

// The role's data layers come from the session (App resolves them per role); money widgets collapse for a
// withheld layer rather than rendering £0. Surfaced here so the control room states what the role can see.
const MONEY_LAYERS = ['commercial', 'profitability', 'commission'];

interface RoleDto {
  id: string;
  name: string;
  isPreset: boolean;
}

interface WhoAmI {
  userId: string;
  permissions: string[]; // "action:objectType"
}

// Split "action:objectType" into the object → set-of-actions map the matrix renders.
function grantsOf(permissions: string[]): { objects: string[]; map: Record<string, Set<string>> } {
  const map: Record<string, Set<string>> = {};
  for (const p of permissions) {
    const idx = p.indexOf(':');
    if (idx < 0) continue;
    const action = p.slice(0, idx);
    const object = p.slice(idx + 1);
    if (!object) continue;
    (map[object] ||= new Set<string>()).add(action);
  }
  return { objects: Object.keys(map).sort(), map };
}

export function Access({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const roles = useApi<RoleDto[]>(['access-roles', ctx?.entity, ctx?.market], '/api/v1/admin/roles');
  const me = useApi<WhoAmI>(['access-me'], '/api/v1/access/me');

  const [selected, setSelected] = useState<string | null>(null);

  const grants = useMemo(() => grantsOf(me.data?.permissions ?? []), [me.data]);
  const layers: string[] = Array.isArray(role?.layers) ? role.layers : [];

  // The roles list drives the left rail; the matrix on the right reads the signed-in principal's effective
  // grants (the only per-subject policy the API exposes). Selecting a role is navigational context for the
  // control room — the matrix always shows "what *you* can do" since that's what /access/me returns.
  const rolesErr = roles.error;
  const rolesForbidden = rolesErr?.forbidden ?? false;
  const rolesNotImpl = rolesErr?.notImplemented ?? false;
  const rolesOther = !!rolesErr && !rolesForbidden && !rolesNotImpl;
  const rolesData = roles.data ?? [];
  const rolesReady = !roles.isLoading && !rolesErr;

  const meErr = me.error;
  const meForbidden = meErr?.forbidden ?? false;
  const meNotImpl = meErr?.notImplemented ?? false;
  const meOther = !!meErr && !meForbidden && !meNotImpl;
  const meReady = !me.isLoading && !meErr;

  const selectedRole = rolesData.find((r) => r.id === selected) ?? rolesData[0] ?? null;
  const effSelected = selectedRole?.id ?? null;

  const has = (obj: string, action: string): boolean => grants.map[obj]?.has(action) ?? false;

  return (
    <div className="page" style={{ maxWidth: 1340 }}>
      <PageHead
        crumb="Permission builder (doc 05) · the governance control room"
        title="Access"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            The preset roles that compose the wall, and a live read of the signed-in principal's effective
            grants — permissions per CRUD action × object, plus the data layers the role sees. The{' '}
            <b>edit ⊆ view</b> rule is structural; revocation takes effect on the next request.
          </span>
        }
      />

      {rolesNotImpl && meNotImpl ? (
        <NotAvailable />
      ) : (
        <div className="grid" style={{ gridTemplateColumns: '240px 1fr', alignItems: 'start', gap: 16 }}>
          {/* ---- role catalogue ---- */}
          <Card style={{ padding: 0 }} className="tablewrap">
            <div className="mini" style={{ padding: '12px 14px 6px' }}>
              Roles{rolesReady ? ` · ${rolesData.length}` : ''}
            </div>
            <table className="tbl sel" data-testid="access-roles">
              <tbody>
                {roles.isLoading && Array.from({ length: 5 }).map((_, i) => <SkeletonRow key={i} cols={1} />)}
                {rolesForbidden && (
                  <tr><td style={{ padding: 14 }}><LayerNote>hidden — requires <b>view:role</b>. The role catalogue is admin-only.</LayerNote></td></tr>
                )}
                {rolesNotImpl && (
                  <tr><td className="dim" style={{ padding: 14, fontSize: 11.5 }}>Role catalogue not available in this environment.</td></tr>
                )}
                {rolesOther && (
                  <EmptyRow cols={1}>Couldn't load roles — {rolesErr?.message || 'the access service may be unavailable.'}</EmptyRow>
                )}
                {rolesReady && rolesData.length === 0 && (
                  <EmptyRow cols={1}>No roles defined yet — create the first role to compose the wall.</EmptyRow>
                )}
                {rolesReady && rolesData.map((r) => (
                  <tr
                    key={r.id}
                    className={effSelected === r.id ? 'sel' : ''}
                    style={{ cursor: 'pointer' }}
                    onClick={() => setSelected(r.id)}
                    data-testid="access-role-row"
                  >
                    <td>
                      <b style={{ fontSize: 12.5 }}>{r.name}</b>
                      <div className="dim" style={{ fontSize: 10.5 }}>{r.isPreset ? 'preset' : 'custom'} · {r.id.slice(0, 8)}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          {/* ---- effective-policy matrix (read from /access/me) ---- */}
          <div>
            <Card
              title="Effective permissions · this session"
              icon={I.shield}
              aux={meReady ? `${me.data?.userId?.slice(0, 8) ?? ''} · ${grants.objects.length} objects` : 'edit ⊆ view enforced'}
              style={{ padding: 0, marginBottom: 14 }}
              className="tablewrap"
            >
              <table className="tbl" data-testid="access-matrix">
                <thead>
                  <tr>
                    <th>Object</th>
                    {ACTIONS.map((a) => (
                      <th key={a} style={{ textAlign: 'center', textTransform: 'capitalize' }}>{a}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {me.isLoading && Array.from({ length: 5 }).map((_, i) => <SkeletonRow key={i} cols={ACTIONS.length + 1} />)}
                  {meForbidden && (
                    <tr><td colSpan={ACTIONS.length + 1} style={{ padding: 14 }}><LayerNote>hidden — requires <b>view:role</b>. The access matrix is admin-only.</LayerNote></td></tr>
                  )}
                  {meNotImpl && (
                    <tr><td colSpan={ACTIONS.length + 1} style={{ padding: 0 }}><NotAvailable embedded /></td></tr>
                  )}
                  {meOther && (
                    <EmptyRow cols={ACTIONS.length + 1}>Couldn't load effective permissions — {meErr?.message || 'the access service may be unavailable.'}</EmptyRow>
                  )}
                  {meReady && grants.objects.length === 0 && (
                    <EmptyRow cols={ACTIONS.length + 1}>No object grants on this session — deny by default.</EmptyRow>
                  )}
                  {meReady && grants.objects.map((obj) => (
                    <tr key={obj} style={{ cursor: 'default' }} data-testid="access-matrix-row">
                      <td className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{obj}</td>
                      {ACTIONS.map((a) => {
                        const on = has(obj, a);
                        const blocked = a !== 'view' && !has(obj, 'view');
                        return (
                          <td key={a} style={{ textAlign: 'center' }}>
                            <span
                              title={blocked ? 'requires view (edit ⊆ view)' : on ? 'granted' : 'not granted'}
                              style={{
                                width: 26, height: 26, borderRadius: 7,
                                border: '1px solid ' + (on ? 'transparent' : 'var(--border)'),
                                background: on ? 'var(--accent)' : blocked ? 'var(--bg-2)' : 'transparent',
                                color: on ? 'var(--on-accent)' : 'var(--faint)',
                                display: 'inline-grid', placeItems: 'center',
                                opacity: blocked ? 0.4 : 1,
                              }}
                            >
                              {on ? I.check({ size: 14 }) : blocked ? '·' : ''}
                            </span>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>

            {/* data layers this role sees (from the session — collapse-not-zero) */}
            <Card title="Data layers" icon={I.layers} aux="which money layers this role sees (collapse-not-zero)" style={{ marginBottom: 14 }}>
              <div className="row g8 wrap">
                {layers.length === 0
                  ? <span className="dim" style={{ fontSize: 12 }}>volume only — all money collapses</span>
                  : layers.map((l) => (
                      <span key={l} className={'chip ' + (l === 'pii' ? 'danger' : MONEY_LAYERS.indexOf(l) >= 0 ? 'ok' : 'neutral')} style={{ padding: '5px 11px' }}>
                        {I.check({ size: 12 })}{l}
                      </span>
                    ))}
              </div>
              <LayerNote>A withheld layer is <b>absent</b> from the role's payload — money widgets collapse, never show £0.00.</LayerNote>
            </Card>

            {/* view-as preview — what will this role actually see? */}
            {meReady && (
              <ViewAsPreview userId={me.data?.userId} objects={grants.objects} has={has} layers={layers} />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function ViewAsPreview({ userId, objects, has, layers }: { userId?: string; objects: string[]; has: (o: string, a: string) => boolean; layers: string[] }) {
  const visibleObjects = objects.filter((o) => has(o, 'view'));
  const moneyLayers = MONEY_LAYERS.filter((l) => layers.indexOf(l) >= 0);
  const seesPii = layers.indexOf('pii') >= 0;

  return (
    <Card title="View-as preview" icon={I.user} aux="what does this session actually see?" style={{ background: 'var(--bg-2)' }}>
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
          <div className="fldlabel" style={{ marginBottom: 6 }}>Identity</div>
          <div className="row g6 wrap">
            <span className="chip accent mono" style={{ fontSize: 10.5 }}>{userId ? userId.slice(0, 8) : 'unknown'}</span>
          </div>
        </div>
      </div>
      <LayerNote>To see the desk as another role, use the user menu (top-right) → switch user. Hidden layers and tabs change live; the wall is enforced server-side.</LayerNote>
    </Card>
  );
}

// An honest "endpoint not built" panel (404) — distinct from a stuck skeleton or a £0.
function NotAvailable({ embedded }: { embedded?: boolean }) {
  return (
    <div
      data-testid="access-not-available"
      style={{
        padding: embedded ? '24px 18px' : '28px 18px',
        textAlign: 'center',
        color: 'var(--muted)',
        border: embedded ? 'none' : '1px dashed var(--border)',
        borderRadius: 10,
        background: 'var(--bg-2)',
      }}
    >
      <div style={{ marginBottom: 6, color: 'var(--faint)' }}>{I.shield({ size: 22 })}</div>
      <div style={{ fontWeight: 600, fontSize: 13.5, color: 'var(--text)' }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>The access policy endpoints aren't built in this deployment.</div>
    </div>
  );
}
