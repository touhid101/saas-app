/**
 * Charts for the dashboard.
 *
 * Plain SVG on purpose — the whole set is a few hundred lines and adds nothing
 * to the bundle, where a charting library would add several hundred kilobytes
 * to a page whose job is to load fast.
 *
 * Each shape answers a different question, and that is the reason there is more
 * than one. Bars compare, donuts compose, a heatmap shows rhythm, a Pareto
 * shows concentration. Drawing all four as a line would hide three of them.
 */
import React from "react";

const TONE = {
  CRITICAL: "var(--clay)",
  WARNING: "var(--amber)",
  OK: "var(--teal)",
  GOOD: "var(--sage)",
};
const PALETTE = ["#2E6A62", "#B8752B", "#4C7A5E", "#A8452E", "#8F5A1E", "#6B5EA8", "#9FB0A5", "#C2A25B"];

/* ══════════════════════════════════════════════════════════════════════════
   Horizontal bars — comparison across a handful of named things
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * Ranked or bucketed comparison.
 *
 * Horizontal rather than vertical because the labels are words: "1 to 3 months"
 * set sideways under a vertical bar is unreadable, and rotating it is worse.
 */
export function HBars({ rows, format = (v) => v, empty = "Nothing to show." }) {
  if (!rows?.length) return <div className="empty-state">{empty}</div>;
  const max = Math.max(1, ...rows.map((r) => Math.abs(Number(r.value) || 0)));
  return (
    <div className="hbars">
      {rows.map((r, i) => {
        const v = Number(r.value) || 0;
        const pct = Math.max(v === 0 ? 0 : 1.5, (Math.abs(v) / max) * 100);
        return (
          <div key={r.label + i} className="hbar-row">
            <div className="hbar-label" title={r.label}>{r.label}</div>
            <div className="hbar-track">
              <div className="hbar-fill"
                style={{ width: `${pct}%`, background: TONE[r.tone] || PALETTE[i % PALETTE.length] }} />
            </div>
            <div className="hbar-value mono">{format(v, r)}</div>
          </div>
        );
      })}
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Grouped bars — two series that belong side by side
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * Revenue against what it cost, month by month.
 *
 * Two bars per month rather than a stacked one: stacking would make the total
 * the obvious reading, and the total is not the point — the gap is.
 */
export function GroupedBars({ rows, format = (v) => v, height = 170 }) {
  if (!rows?.length) return <div className="empty-state">Not enough history yet.</div>;
  const max = Math.max(1, ...rows.flatMap((r) => [Number(r.revenue) || 0, Number(r.cost) || 0]));
  return (
    <>
      <div className="gbars" style={{ height }}>
        {rows.map((r) => {
          const rev = Number(r.revenue) || 0;
          const cost = Number(r.cost) || 0;
          const margin = rev - cost;
          return (
            <div key={r.month} className="gbar-col"
              title={`${r.month}\nrevenue ${format(rev)}\ncost ${format(cost)}\nmargin ${format(margin)}`}>
              <div className="gbar-pair">
                <div className="gbar" style={{ height: `${(rev / max) * 100}%`, background: "var(--teal)" }} />
                <div className="gbar" style={{ height: `${(cost / max) * 100}%`, background: "var(--amber)" }} />
              </div>
              <div className="gbar-x">{String(r.month).slice(2)}</div>
            </div>
          );
        })}
      </div>
      <Legend items={[["Revenue", "var(--teal)"], ["Cost of goods", "var(--amber)"]]} />
    </>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Donut — composition
   ══════════════════════════════════════════════════════════════════════════ */

export function DonutChart({ rows, format = (v) => v, size = 148, thickness = 24 }) {
  const total = (rows ?? []).reduce((s, r) => s + (Number(r.value) || 0), 0);
  if (!rows?.length || total <= 0) return <div className="empty-state">Nothing recorded yet.</div>;

  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  let offset = 0;

  return (
    <div className="donut-wrap">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label="Composition">
        <g transform={`rotate(-90 ${size / 2} ${size / 2})`}>
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--line)" strokeWidth={thickness} />
          {rows.map((row, i) => {
            const frac = (Number(row.value) || 0) / total;
            const dash = frac * c;
            const el = (
              <circle key={row.label} cx={size / 2} cy={size / 2} r={r} fill="none"
                stroke={PALETTE[i % PALETTE.length]} strokeWidth={thickness}
                strokeDasharray={`${dash} ${c - dash}`} strokeDashoffset={-offset}>
                <title>{`${row.label}: ${format(row.value)}`}</title>
              </circle>
            );
            offset += dash;
            return el;
          })}
        </g>
      </svg>
      <div className="donut-key">
        {rows.map((row, i) => (
          <div key={row.label} className="donut-key-row">
            <span className="donut-dot" style={{ background: PALETTE[i % PALETTE.length] }} />
            <span className="donut-key-label">{row.label}</span>
            <span className="mono donut-key-val">{format(row.value)}</span>
            <span className="donut-key-pct">
              {Math.round(((Number(row.value) || 0) / total) * 100)}%
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Calendar heatmap — rhythm
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * A day per square, weeks as columns.
 *
 * A line shows the level; this shows the pattern — which weekday is quiet, where
 * the gaps are, whether last week looked like the one before. On a pharmacy that
 * is the difference between staffing Friday and staffing Monday.
 */
export function CalendarHeat({ points, format = (v) => v }) {
  if (!points?.length) return <div className="empty-state">No activity recorded.</div>;

  const max = Math.max(1, ...points.map((p) => Number(p.value) || 0));
  // Pad the front so the first column starts on the right weekday.
  const first = new Date(points[0].day + "T00:00:00");
  const pad = first.getDay();
  const cells = [...Array(pad).fill(null), ...points];
  const weeks = [];
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7));

  const shade = (v) => {
    if (!v) return "var(--paper)";
    const t = Math.min(1, 0.18 + (v / max) * 0.82);
    return `color-mix(in srgb, var(--teal) ${Math.round(t * 100)}%, var(--paper))`;
  };

  return (
    <div className="heat">
      <div className="heat-days">
        {["S", "M", "T", "W", "T", "F", "S"].map((d, i) => (
          <div key={i} className="heat-daylabel">{i % 2 ? d : ""}</div>
        ))}
      </div>
      <div className="heat-grid">
        {weeks.map((w, wi) => (
          <div key={wi} className="heat-week">
            {Array.from({ length: 7 }, (_, di) => {
              const cell = w[di];
              if (!cell) return <div key={di} className="heat-cell heat-empty" />;
              const v = Number(cell.value) || 0;
              return (
                <div key={di} className="heat-cell" style={{ background: shade(v) }}
                  title={`${cell.day}: ${format(v)}`} />
              );
            })}
          </div>
        ))}
      </div>
      <div className="heat-legend">
        <span>quiet</span>
        {[0, 0.25, 0.5, 0.75, 1].map((t) => (
          <span key={t} className="heat-cell heat-key" style={{ background: shade(t * max) }} />
        ))}
        <span>busy</span>
      </div>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Pareto — concentration
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * Bars for each product, a line for the running total.
 *
 * The question this answers is not "which sells most" — the table above already
 * says that — but "how few products is this business standing on". Where the
 * line crosses 80% is the number of lines that must not run out.
 */
export function Pareto({ rows, format = (v) => v, height = 170 }) {
  if (!rows?.length) return <div className="empty-state">Not enough sales to rank.</div>;

  const total = rows.reduce((s, r) => s + (Number(r.value) || 0), 0);
  if (total <= 0) return <div className="empty-state">Not enough sales to rank.</div>;

  const w = 100 / rows.length;
  let running = 0;
  const cum = rows.map((r) => {
    running += (Number(r.value) || 0) / total;
    return running;
  });
  const max = Math.max(...rows.map((r) => Number(r.value) || 0));
  const eighty = cum.findIndex((c) => c >= 0.8) + 1;

  const path = cum
    .map((c, i) => `${i === 0 ? "M" : "L"} ${(w * (i + 0.5)).toFixed(2)} ${((1 - c) * 100).toFixed(2)}`)
    .join(" ");

  return (
    <>
      <div className="pareto" style={{ height }}>
        <div className="pareto-bars">
          {rows.map((r, i) => (
            <div key={r.label + i} className="pareto-col"
              title={`${r.label}: ${format(r.value)} · ${Math.round(cum[i] * 100)}% cumulative`}>
              <div className="pareto-bar"
                style={{ height: `${((Number(r.value) || 0) / max) * 100}%` }} />
            </div>
          ))}
        </div>
        <svg className="pareto-line" viewBox="0 0 100 100" preserveAspectRatio="none">
          <line x1="0" y1="20" x2="100" y2="20" stroke="var(--clay)" strokeWidth="0.5"
            strokeDasharray="2 2" vectorEffect="non-scaling-stroke" />
          <path d={path} fill="none" stroke="var(--clay)" strokeWidth="2"
            vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
        </svg>
      </div>
      <div className="pareto-note">
        {eighty > 0
          ? <>The top <b>{eighty}</b> of {rows.length} products carry 80% of the revenue.
              Those are the lines that must not run out.</>
          : <>Revenue is spread evenly across these {rows.length} products.</>}
      </div>
    </>
  );
}

/* ══════════════════════════════════════════════════════════════════════════ */

function Legend({ items }) {
  return (
    <div className="chart-legend">
      {items.map(([label, color]) => (
        <span key={label} className="chart-legend-item">
          <span className="donut-dot" style={{ background: color }} />{label}
        </span>
      ))}
    </div>
  );
}
