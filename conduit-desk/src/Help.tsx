import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHead, Card, Chip } from './kit/kit';
import { I } from './kit/icons';
import { CHAPTERS, SECTION_ORDER, ManualChapter, ManualStatus } from './help/content';

// The interactive user manual (spec 38, M-Help.1): a searchable, section-grouped helpbook, one chapter per
// feature, rendered in-app. Reachable at /help and /help/<chapter>; the per-screen "?" deep-links here (M-Help.2).

const STATUS_TONE: Record<ManualStatus, 'ok' | 'accent' | 'warn' | 'neutral'> = {
  live: 'ok', shadow: 'accent', partial: 'warn', planned: 'neutral',
};
const STATUS_LABEL: Record<ManualStatus, string> = {
  live: 'live', shadow: 'shadow-only', partial: 'in progress', planned: 'planned',
};

function ChapterBody({ ch, onOpen }: { ch: ManualChapter; onOpen: (id: string) => void }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card title={ch.title} icon={I.list} aux={<Chip s={STATUS_TONE[ch.status]}>{STATUS_LABEL[ch.status]}</Chip>}>
        <p style={{ margin: 0, lineHeight: 1.55, fontSize: 13.5 }}>{ch.summary}</p>
      </Card>

      {ch.concepts && ch.concepts.length > 0 && (
        <Card title="Key concepts" icon={I.layers}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {ch.concepts.map((c) => (
              <div key={c.term}>
                <div style={{ fontFamily: 'var(--font-disp)', fontWeight: 600, fontSize: 13 }}>{c.term}</div>
                <div className="dim" style={{ fontSize: 12.5, lineHeight: 1.5 }}>{c.def}</div>
              </div>
            ))}
          </div>
        </Card>
      )}

      {ch.tasks && ch.tasks.length > 0 && (
        <Card title="How to" icon={I.charger}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {ch.tasks.map((t) => (
              <div key={t.title}>
                <div style={{ fontFamily: 'var(--font-disp)', fontWeight: 600, fontSize: 13, marginBottom: 6 }}>{t.title}</div>
                <ol style={{ margin: 0, paddingLeft: 18, fontSize: 12.5, lineHeight: 1.7 }}>
                  {t.steps.map((s, i) => <li key={i}>{s}</li>)}
                </ol>
                {t.note && <div className="dim" style={{ fontSize: 11.5, marginTop: 5, fontStyle: 'italic' }}>{t.note}</div>}
              </div>
            ))}
          </div>
        </Card>
      )}

      {((ch.related && ch.related.length > 0) || (ch.seeAlso && ch.seeAlso.length > 0)) && (
        <Card title="Related" icon={I.map}>
          {ch.related && ch.related.length > 0 && (
            <div className="row g6" style={{ flexWrap: 'wrap', marginBottom: ch.seeAlso?.length ? 8 : 0 }}>
              {ch.related.map((r) => {
                const target = CHAPTERS.find((c) => c.route === r);
                return target
                  ? <span key={r} style={{ cursor: 'pointer' }} onClick={() => onOpen(target.id)}><Chip s="neutral">{target.title} ↗</Chip></span>
                  : <Chip key={r} s="neutral">{r}</Chip>;
              })}
            </div>
          )}
          {ch.seeAlso && ch.seeAlso.length > 0 && (
            <div className="dim" style={{ fontSize: 11.5 }}>See also: {ch.seeAlso.join(' · ')}</div>
          )}
        </Card>
      )}
    </div>
  );
}

export function Help(props: { token: string; role: any; ctx: any; toast: (m: string, k?: 'ok' | 'warn' | 'err') => void; sub?: string }) {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const open = (id: string) => navigate('/help/' + id);

  const filtered = useMemo(() => {
    const term = q.trim().toLowerCase();
    if (!term) return CHAPTERS;
    const hit = (ch: ManualChapter) =>
      (ch.title + ' ' + ch.summary + ' ' + (ch.concepts ?? []).map((c) => c.term + ' ' + c.def).join(' ') +
        ' ' + (ch.tasks ?? []).map((t) => t.title).join(' ')).toLowerCase().includes(term);
    return CHAPTERS.filter(hit);
  }, [q]);

  const current = props.sub ? CHAPTERS.find((c) => c.id === props.sub) : undefined;
  const bySection = SECTION_ORDER
    .map((sec) => ({ sec, items: filtered.filter((c) => c.section === sec) }))
    .filter((g) => g.items.length > 0);

  return (
    <div className="page">
      <PageHead
        title="User manual"
        sub="The interactive helpbook — one chapter per screen, searchable. The training curriculum for the whole desk."
        right={<Chip s="accent">{CHAPTERS.length} chapters</Chip>}
      />
      <div className="grid" style={{ gridTemplateColumns: '300px 1fr', gap: 16, alignItems: 'start' }}>
        <Card title="Contents" icon={I.list} style={{ position: 'sticky', top: 12 }}>
          <input
            className="cellinput" placeholder="Search the manual…" value={q} onChange={(e) => setQ(e.target.value)}
            style={{ width: '100%', marginBottom: 10, textAlign: 'left' }}
          />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: '70vh', overflowY: 'auto' }}>
            {bySection.map((g) => (
              <div key={g.sec}>
                <div className="fldlabel" style={{ marginBottom: 4 }}>{g.sec}</div>
                {g.items.map((c) => (
                  <div
                    key={c.id}
                    onClick={() => open(c.id)}
                    style={{
                      cursor: 'pointer', fontSize: 12.5, padding: '4px 6px', borderRadius: 6,
                      background: current?.id === c.id ? 'var(--bg-2)' : undefined,
                      color: current?.id === c.id ? 'var(--accent-bright)' : undefined,
                    }}
                  >{c.title}</div>
                ))}
              </div>
            ))}
            {bySection.length === 0 && <div className="dim" style={{ fontSize: 12 }}>No chapter matches “{q}”.</div>}
          </div>
        </Card>

        <div>
          {current
            ? <ChapterBody ch={current} onOpen={open} />
            : (
              <Card title="Welcome to the Conduit manual" icon={I.list}>
                <p style={{ marginTop: 0, lineHeight: 1.55, fontSize: 13.5 }}>
                  Pick a chapter on the left, or search. Start with the <b>Primer</b> — it explains how Conduit
                  thinks (data layers, the event ledger, the golden record, shadow mode) before the screen-by-screen guides.
                </p>
                <div className="row g6" style={{ flexWrap: 'wrap', marginTop: 8 }}>
                  {CHAPTERS.filter((c) => c.section === 'Primer').map((c) => (
                    <span key={c.id} style={{ cursor: 'pointer' }} onClick={() => open(c.id)}><Chip s="accent">{c.title} ↗</Chip></span>
                  ))}
                </div>
              </Card>
            )}
        </div>
      </div>
    </div>
  );
}
