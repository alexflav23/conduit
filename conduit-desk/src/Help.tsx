import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PageHead, Card, Chip } from './kit/kit';
import { I } from './kit/icons';
import { CHAPTERS, SECTION_ORDER, LEARNING_PATHS, TOURS, ManualChapter, ManualStatus, LearningPath } from './help/content';

// The interactive user manual (spec 38): a searchable, section-grouped helpbook (one chapter per screen), the
// role-based training curriculum (learning paths + completion + PDF export, M-Help.4), and the guided-tour
// launcher (M-Help.3). Reachable at /help and /help/<chapter>; the per-screen "?" deep-links here (M-Help.2).

const STATUS_TONE: Record<ManualStatus, 'ok' | 'accent' | 'warn' | 'neutral'> = {
  live: 'ok', shadow: 'accent', partial: 'warn', planned: 'neutral',
};
const STATUS_LABEL: Record<ManualStatus, string> = {
  live: 'live', shadow: 'shadow-only', partial: 'in progress', planned: 'planned',
};

const DONE_KEY = 'conduit.help.done';
function loadDone(): Set<string> {
  try { return new Set(JSON.parse(localStorage.getItem(DONE_KEY) || '[]')); } catch { return new Set(); }
}
function saveDone(s: Set<string>): void {
  try { localStorage.setItem(DONE_KEY, JSON.stringify([...s])); } catch { /* storage unavailable */ }
}

// Assemble a clean, dependency-free printable document for a chapter set → the browser's "Save as PDF" exports it.
function printChapters(title: string, chapters: ManualChapter[]): void {
  const w = window.open('', '_blank');
  if (!w) return;
  const esc = (s: string) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] as string));
  const body = chapters.map((ch) =>
    `<section><h2>${esc(ch.title)} <small>(${ch.status})</small></h2><p>${esc(ch.summary)}</p>` +
    (ch.concepts?.length ? `<h4>Key concepts</h4><ul>${ch.concepts.map((c) => `<li><b>${esc(c.term)}</b> — ${esc(c.def)}</li>`).join('')}</ul>` : '') +
    (ch.tasks?.length ? ch.tasks.map((t) => `<h4>${esc(t.title)}</h4><ol>${t.steps.map((s) => `<li>${esc(s)}</li>`).join('')}</ol>`).join('') : '') +
    '</section>').join('');
  w.document.write(
    `<html><head><title>${esc(title)}</title><style>body{font-family:system-ui,-apple-system,sans-serif;max-width:760px;margin:40px auto;color:#111;line-height:1.5;padding:0 16px}h1{color:#962DFF}section{break-inside:avoid;margin-bottom:26px;border-bottom:1px solid #eee;padding-bottom:16px}small{color:#888;font-weight:400}h4{margin:12px 0 4px;font-size:13px}ul,ol{margin:4px 0}</style></head><body><h1>Conduit — ${esc(title)}</h1>${body}<script>window.onload=()=>window.print()</script></body></html>`,
  );
  w.document.close();
}

function startTour(tourId: string): void {
  window.dispatchEvent(new CustomEvent('conduit:tour', { detail: tourId }));
}

function ChapterBody({ ch, onOpen }: { ch: ManualChapter; onOpen: (id: string) => void }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card title={ch.title} icon={I.list} aux={<Chip s={STATUS_TONE[ch.status]}>{STATUS_LABEL[ch.status]}</Chip>}>
        <p style={{ margin: 0, lineHeight: 1.55, fontSize: 13.5 }}>{ch.summary}</p>
        {ch.screenshot && (
          <img
            src={`/help-shots/${ch.screenshot}.png`} alt={`${ch.title} screenshot`} loading="lazy"
            style={{ width: '100%', marginTop: 14, borderRadius: 10, border: '1px solid var(--line)', display: 'block' }}
          />
        )}
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
                const target = CHAPTERS.find((c) => c.route === r) || CHAPTERS.find((c) => c.id === r);
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

function PathView({ path, done, toggle, onOpen }: {
  path: LearningPath; done: Set<string>; toggle: (id: string) => void; onOpen: (id: string) => void;
}) {
  const chapters = path.chapters.map((id) => CHAPTERS.find((c) => c.id === id)).filter(Boolean) as ManualChapter[];
  const completed = chapters.filter((c) => done.has(c.id)).length;
  const tour = TOURS.find((t) => t.id === path.id);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card title={`${path.role} — learning path`} icon={I.layers}
        aux={<Chip s={completed === chapters.length ? 'ok' : 'accent'}>{completed}/{chapters.length} done</Chip>}>
        <p style={{ margin: '0 0 12px', fontSize: 13.5 }}>{path.blurb}</p>
        <div className="row g8" style={{ flexWrap: 'wrap' }}>
          <button className="btn sm" onClick={() => printChapters(`${path.role} learning path`, chapters)}>Print / Save as PDF</button>
          {tour && <button className="btn ghost sm" onClick={() => startTour(tour.id)}>▶ Start the guided tour</button>}
        </div>
      </Card>
      <Card title="Chapters in order" icon={I.list} className="tablewrap" style={{ padding: 0 }}>
        <table className="tbl">
          <thead><tr><th style={{ width: 40 }} /><th style={{ width: 30 }}>#</th><th>Chapter</th><th>Section</th><th /></tr></thead>
          <tbody>
            {chapters.map((c, i) => (
              <tr key={c.id} style={{ opacity: done.has(c.id) ? 0.6 : 1 }}>
                <td style={{ textAlign: 'center' }}>
                  <input type="checkbox" checked={done.has(c.id)} onChange={() => toggle(c.id)} title="mark complete" />
                </td>
                <td className="dim">{i + 1}</td>
                <td style={{ cursor: 'pointer' }} onClick={() => onOpen(c.id)}><b>{c.title}</b> <span className="dim">↗</span></td>
                <td className="dim" style={{ fontSize: 11.5 }}>{c.section}</td>
                <td><Chip s={STATUS_TONE[c.status]}>{STATUS_LABEL[c.status]}</Chip></td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

export function Help(props: { token: string; role: any; ctx: any; toast: (m: string, k?: 'ok' | 'warn' | 'err') => void; sub?: string }) {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [path, setPath] = useState<LearningPath | null>(null);
  const [done, setDone] = useState<Set<string>>(loadDone);
  const open = (id: string) => { setPath(null); navigate('/help/' + id); };
  const toggle = (id: string) => setDone((prev) => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); saveDone(n); return n; });

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
        right={
          <div className="row g6">
            <a className="btn ghost sm" href="/api/v1/docs" target="_blank" rel="noreferrer" title="OpenAPI reference (Scalar) — generated from the live Tapir endpoints">API reference ↗</a>
            <button className="btn ghost sm" onClick={() => printChapters('Complete manual', CHAPTERS)}>Print whole book</button>
            <Chip s="accent">{CHAPTERS.length} chapters</Chip>
          </div>
        }
      />
      <div className="grid" style={{ gridTemplateColumns: '300px 1fr', gap: 16, alignItems: 'start' }}>
        <Card title="Contents" icon={I.list} style={{ position: 'sticky', top: 12 }}>
          <input
            className="cellinput" placeholder="Search the manual…" value={q} onChange={(e) => setQ(e.target.value)}
            style={{ width: '100%', marginBottom: 10, textAlign: 'left' }}
          />
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: '70vh', overflowY: 'auto' }}>
            <div>
              <div className="fldlabel" style={{ marginBottom: 4 }}>Training paths</div>
              {LEARNING_PATHS.map((p) => (
                <div key={p.id} onClick={() => { setPath(p); navigate('/help'); }}
                  style={{ cursor: 'pointer', fontSize: 12.5, padding: '4px 6px', borderRadius: 6, color: path?.id === p.id ? 'var(--accent-bright)' : undefined, background: path?.id === p.id ? 'var(--bg-2)' : undefined }}>
                  {p.role}
                </div>
              ))}
            </div>
            {bySection.map((g) => (
              <div key={g.sec}>
                <div className="fldlabel" style={{ marginBottom: 4 }}>{g.sec}</div>
                {g.items.map((c) => (
                  <div
                    key={c.id}
                    onClick={() => open(c.id)}
                    style={{
                      cursor: 'pointer', fontSize: 12.5, padding: '4px 6px', borderRadius: 6,
                      background: !path && current?.id === c.id ? 'var(--bg-2)' : undefined,
                      color: !path && current?.id === c.id ? 'var(--accent-bright)' : undefined,
                    }}
                  >{c.title}</div>
                ))}
              </div>
            ))}
            {bySection.length === 0 && <div className="dim" style={{ fontSize: 12 }}>No chapter matches “{q}”.</div>}
          </div>
        </Card>

        <div>
          {path
            ? <PathView path={path} done={done} toggle={toggle} onOpen={open} />
            : current
              ? <ChapterBody ch={current} onOpen={open} />
              : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                  <Card title="Welcome to the Conduit manual" icon={I.list}>
                    <p style={{ marginTop: 0, lineHeight: 1.55, fontSize: 13.5 }}>
                      Pick a chapter on the left, or search. Start with the <b>Primer</b> — it explains how Conduit
                      thinks (data layers, the event ledger, the golden record, shadow mode) before the screen-by-screen guides.
                      A <b>?</b> on any screen jumps you straight to that screen’s chapter.
                    </p>
                    <div className="row g6" style={{ flexWrap: 'wrap', marginTop: 8 }}>
                      {CHAPTERS.filter((c) => c.section === 'Primer').map((c) => (
                        <span key={c.id} style={{ cursor: 'pointer' }} onClick={() => open(c.id)}><Chip s="accent">{c.title} ↗</Chip></span>
                      ))}
                    </div>
                  </Card>
                  <Card title="Guided tours" icon={I.charger} aux={<span className="dim" style={{ fontSize: 11.5 }}>walk a real flow on the live screens</span>}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {TOURS.map((t) => (
                        <div key={t.id} className="row between" style={{ fontSize: 13 }}>
                          <span><b>{t.title}</b> <span className="dim">· {t.steps.length} steps</span></span>
                          <button className="btn ghost sm" onClick={() => startTour(t.id)}>▶ Start</button>
                        </div>
                      ))}
                    </div>
                  </Card>
                </div>
              )}
        </div>
      </div>
    </div>
  );
}
