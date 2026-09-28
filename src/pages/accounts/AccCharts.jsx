/**
 * AccCharts — the chart forms the Accounts screens need, drawn by hand in SVG
 * so they wear the theme tokens and need no library.
 *
 *   TrendChart   change over time: up to four series as 2px lines with a faint
 *                area wash; a crosshair that snaps to the nearest day and one
 *                tooltip listing every series at that day; legend items that
 *                toggle a series; Daily / Cumulative; an optional "previous
 *                window" overlay drawn dashed and muted; a table view.
 *   Sparkline    a 2px line in a tile, current period only, no axes.
 *   BarList      magnitude by category: horizontal bars in a single hue, value
 *                at the tip, largest first, each optionally a link.
 *   ShareBar     a 100 % bar split into named parts (where each ৳100 went),
 *                categorical colours, direct labels where they fit, legend.
 *   AgeingBar    a 100 % bar of ageing buckets in one hue light→dark
 *                (magnitude by age is sequential, not categorical).
 *
 * Series colours are the validated chart tokens (--acc-chart-1..4, see
 * accounts.css); text always wears the text tokens, never the series colour.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";
import { amount, compact, fmtDate } from "../../utils/financial.js";

const W = 720, H = 220, PAD = { t: 14, r: 16, b: 28, l: 52 };

function niceTicks(max, n = 4) {
  if (!(max > 0)) return [0];
  const raw = max / n, mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) || mag * 10;
  const ticks = []; for (let v = 0; v <= max + step * 0.001; v += step) ticks.push(Math.round(v * 100) / 100);
  return ticks;
}

/** Every day of the window, zero where the ledger had nothing — a quiet week must look quiet, not vanish. */
export function fillDays(rows, from, to) {
  if (!from || !to) return rows;
  const byDate = new Map(rows.map((r) => [String(r.date).slice(0, 10), r]));
  const out = [];
  for (let d = new Date(from + "T00:00:00Z"); d <= new Date(to + "T00:00:00Z") && out.length < 1000; d.setUTCDate(d.getUTCDate() + 1)) {
    const key = d.toISOString().slice(0, 10);
    out.push(byDate.get(key) || { date: key });
  }
  return out;
}

const reduced = () => typeof window !== "undefined" && window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

export function TrendChart({ rows = [], previous = null, series, title, ariaLabel, from, to, prevFrom, prevTo }) {
  const [hover, setHover] = useState(null);
  const [table, setTable] = useState(false);
  const [hidden, setHidden] = useState(() => new Set());
  const [mode, setMode] = useState("daily");          // daily | cumulative
  const [compare, setCompare] = useState(false);
  const svgRef = useRef(null);

  const build = (src, f, t) => {
    const filled = fillDays(src || [], f, t).map((r) => ({ date: r.date, values: series.map((s) => Number(r[s.key]) || 0) }));
    if (mode === "cumulative") { const run = series.map(() => 0); for (const d of filled) d.values = d.values.map((v, k) => (run[k] += v)); }
    return filled;
  };
  const data = useMemo(() => build(rows, from, to), [rows, series, from, to, mode]); // eslint-disable-line react-hooks/exhaustive-deps
  const prev = useMemo(() => (compare && previous ? build(previous, prevFrom, prevTo) : null), [previous, prevFrom, prevTo, compare, mode, series]); // eslint-disable-line react-hooks/exhaustive-deps
  const visible = (k) => !hidden.has(k);
  const max = useMemo(() => Math.max(0, ...data.flatMap((d) => d.values.filter((_, k) => visible(k))), ...(prev ? prev.flatMap((d) => d.values.filter((_, k) => visible(k))) : [])), [data, prev, hidden]); // eslint-disable-line react-hooks/exhaustive-deps
  const ticks = niceTicks(max);
  const top = ticks[ticks.length - 1] || 1;
  const n = data.length;
  const x = (i, len = n) => PAD.l + (len <= 1 ? (W - PAD.l - PAD.r) / 2 : (i * (W - PAD.l - PAD.r)) / (len - 1));
  const y = (v) => PAD.t + (H - PAD.t - PAD.b) * (1 - v / top);
  const path = (rowsArr, k) => rowsArr.map((d, i) => `${i ? "L" : "M"}${x(i, rowsArr.length).toFixed(1)},${y(d.values[k]).toFixed(1)}`).join(" ");
  const area = (k) => (n ? `${path(data, k)} L${x(n - 1).toFixed(1)},${y(0)} L${x(0).toFixed(1)},${y(0)} Z` : "");
  const onMove = (e) => {
    const svg = svgRef.current; if (!svg || !n) return;
    const box = svg.getBoundingClientRect();
    const px = ((e.clientX - box.left) / box.width) * W;
    let best = 0, dist = Infinity;
    for (let i = 0; i < n; i++) { const d = Math.abs(x(i) - px); if (d < dist) { dist = d; best = i; } }
    setHover(best);
  };
  const xLabels = useMemo(() => {
    if (!n) return [];
    const every = Math.max(1, Math.ceil(n / 6));
    const picked = data.map((d, i) => (i % every === 0 ? i : null)).filter((i) => i !== null);
    if (picked[picked.length - 1] !== n - 1) { if (n - 1 - picked[picked.length - 1] < every / 2) picked.pop(); picked.push(n - 1); }
    return picked;
  }, [data, n]);
  // Draw-in on first paint (skipped under reduced motion).
  const [drawn, setDrawn] = useState(reduced());
  useEffect(() => { if (!drawn) { const id = requestAnimationFrame(() => setDrawn(true)); return () => cancelAnimationFrame(id); } }, [drawn]);
  useEffect(() => { setDrawn(reduced()); }, [rows, mode]);

  if (!n || max === 0) return <div className="acc-chart-empty">No ledger movement in this range.</div>;
  const h = hover != null ? data[hover] : null;
  const hp = hover != null && prev ? prev[Math.min(hover, prev.length - 1)] : null;
  return (
    <div className="acc-chart">
      <div className="acc-chart-head">
        <ul className="acc-legend" aria-label="Series — click to show or hide">
          {series.map((s, k) => (
            <li key={s.key}><button type="button" className={`acc-legend-btn${visible(k) ? "" : " is-off"}`} aria-pressed={visible(k)} onClick={() => setHidden((set) => { const next = new Set(set); if (next.has(k)) next.delete(k); else if (next.size < series.length - 1) next.add(k); return next; })}><i className={`acc-key is-${k + 1}`} />{s.label}</button></li>
          ))}
          {prev && <li><span className="acc-legend-btn"><i className="acc-key is-prev" />previous window</span></li>}
        </ul>
        <div className="acc-chart-tools" role="group" aria-label="Chart options">
          <button type="button" className={`acc-seg${mode === "daily" ? " is-on" : ""}`} aria-pressed={mode === "daily"} onClick={() => setMode("daily")}>Daily</button>
          <button type="button" className={`acc-seg${mode === "cumulative" ? " is-on" : ""}`} aria-pressed={mode === "cumulative"} onClick={() => setMode("cumulative")}>Cumulative</button>
          {previous && previous.length > 0 && <button type="button" className={`acc-seg${compare ? " is-on" : ""}`} aria-pressed={compare} onClick={() => setCompare((c) => !c)} title="Overlay the window immediately before, day for day">Compare</button>}
          <button type="button" className={`acc-seg${table ? " is-on" : ""}`} aria-pressed={table} onClick={() => setTable((t) => !t)}>{table ? "Chart" : "Table"}</button>
        </div>
      </div>
      {table ? (
        <div className="hr-table-wrap"><table className="hr-table acc-chart-table">
          <thead><tr><th scope="col">Date</th>{series.map((s) => <th key={s.key} scope="col" className="hr-num">{s.label}{mode === "cumulative" ? " (to date)" : ""}</th>)}</tr></thead>
          <tbody>{data.map((d) => <tr key={String(d.date)}><td>{fmtDate(d.date)}</td>{d.values.map((v, k) => <td key={k} className="hr-num">{amount(v)}</td>)}</tr>)}</tbody>
        </table></div>
      ) : (
        <div className="acc-chart-plot">
          <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} role="img" aria-label={ariaLabel || title} onMouseMove={onMove} onMouseLeave={() => setHover(null)}
               onFocus={() => setHover(n - 1)} onBlur={() => setHover(null)} tabIndex={0}
               onKeyDown={(e) => { if (e.key === "ArrowLeft") setHover((i) => Math.max(0, (i ?? n) - 1)); if (e.key === "ArrowRight") setHover((i) => Math.min(n - 1, (i ?? -1) + 1)); }}>
            {ticks.map((t) => (
              <g key={t}>
                <line className="acc-grid" x1={PAD.l} x2={W - PAD.r} y1={y(t)} y2={y(t)} />
                <text className="acc-axis" x={PAD.l - 8} y={y(t) + 4} textAnchor="end">{compact(t).replace(/^৳\s?/, "")}</text>
              </g>
            ))}
            {xLabels.map((i) => <text key={i} className="acc-axis" x={x(i)} y={H - 8} textAnchor={i === 0 ? "start" : i === n - 1 ? "end" : "middle"}>{fmtDate(data[i].date).slice(0, 5)}</text>)}
            {prev && series.map((s, k) => visible(k) && <path key={"p" + s.key} className={`acc-line is-${k + 1} is-prev`} d={path(prev, k)} />)}
            {series.map((s, k) => visible(k) && <path key={"a" + s.key} className={`acc-area is-${k + 1}${drawn ? " is-drawn" : ""}`} d={area(k)} />)}
            {series.map((s, k) => visible(k) && <path key={"l" + s.key} className={`acc-line is-${k + 1}${drawn ? " is-drawn" : ""}`} d={path(data, k)} pathLength="1" />)}
            {series.map((s, k) => visible(k) && <circle key={"e" + s.key} className={`acc-dot is-${k + 1}`} cx={x(n - 1)} cy={y(data[n - 1].values[k])} r={4} />)}
            {h && (
              <g>
                <line className="acc-crosshair" x1={x(hover)} x2={x(hover)} y1={PAD.t} y2={H - PAD.b} />
                {series.map((s, k) => visible(k) && <circle key={"h" + s.key} className={`acc-dot is-${k + 1}`} cx={x(hover)} cy={y(h.values[k])} r={5} />)}
              </g>
            )}
          </svg>
          {h && (
            <div className={`acc-tip${x(hover) > W * 0.7 ? " is-left" : x(hover) < W * 0.3 ? " is-right" : ""}`} style={{ left: `${(x(hover) / W) * 100}%` }} role="status">
              <div className="acc-tip-date">{fmtDate(h.date)}{mode === "cumulative" ? " · to date" : ""}</div>
              {series.map((s, k) => visible(k) && (
                <div key={s.key} className="acc-tip-row"><i className={`acc-key is-${k + 1}`} /><b>{amount(h.values[k])}</b><span>{s.label}{hp ? <em className="acc-tip-prev"> · prev {amount(hp.values[k])}</em> : null}</span></div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** A 2px line in a tile: the last `points` days of one series, no axes, end dot. */
export function Sparkline({ rows = [], from, to, seriesKey, points = 14, tone = 1 }) {
  const data = useMemo(() => fillDays(rows, from, to).map((r) => Number(r[seriesKey]) || 0).slice(-points), [rows, from, to, seriesKey, points]);
  const w = 96, hgt = 26, n = data.length;
  if (n < 2) return null;
  const max = Math.max(...data), min = Math.min(0, ...data), span = max - min || 1;
  const xx = (i) => (i * (w - 4)) / (n - 1) + 2, yy = (v) => hgt - 3 - ((v - min) / span) * (hgt - 6);
  const d = data.map((v, i) => `${i ? "L" : "M"}${xx(i).toFixed(1)},${yy(v).toFixed(1)}`).join(" ");
  return (
    <svg className="acc-spark" viewBox={`0 0 ${w} ${hgt}`} width={w} height={hgt} aria-hidden="true">
      <path className={`acc-spark-area is-${tone}`} d={`${d} L${xx(n - 1).toFixed(1)},${hgt - 3} L2,${hgt - 3} Z`} />
      <path className={`acc-spark-line is-${tone}`} d={d} />
      <circle className={`acc-dot is-${tone}`} cx={xx(n - 1)} cy={yy(data[n - 1])} r={2.5} />
    </svg>
  );
}

/** Horizontal bars, one hue, largest first, value at the tip. `onPick(row)` makes a bar a link. */
export function BarList({ rows = [], labelKey = "name", valueKey = "amount", onPick, empty = "Nothing in this range.", selectedId = null }) {
  const max = Math.max(0, ...rows.map((r) => Math.abs(Number(r[valueKey]) || 0)));
  if (!rows.length) return <div className="acc-chart-empty">{empty}</div>;
  return (
    <ul className="acc-bars" aria-label="Breakdown">
      {rows.map((r, i) => {
        const v = Number(r[valueKey]) || 0, w = max ? (Math.abs(v) / max) * 100 : 0;
        const inner = (
          <>
            <span className="acc-bar-label">{r[labelKey]}</span>
            <span className="acc-bar-track"><span className={`acc-bar-fill${v < 0 ? " is-neg" : ""}`} style={{ width: `${w}%` }} /></span>
            <span className="acc-bar-value">{amount(v)}</span>
          </>
        );
        const sel = selectedId != null && String(selectedId) === String(r.id);
        return <li key={r.id ?? r[labelKey] ?? i} className={sel ? "is-selected" : ""}>{onPick ? <button type="button" className="acc-bar-btn" aria-pressed={sel} onClick={() => onPick(r)} title={`Open ${r[labelKey]}`}>{inner}</button> : inner}</li>;
      })}
    </ul>
  );
}

/**
 * A 100 % bar split into named parts. `parts` = [{ key, label, value, tone }];
 * negative or zero parts are left out of the bar but kept in the legend.
 */
export function ShareBar({ parts = [], total, onPick, ariaLabel = "Share" }) {
  const [hover, setHover] = useState(null);
  const sum = total ?? parts.reduce((t, p) => t + Math.max(0, Number(p.value) || 0), 0);
  if (!(sum > 0)) return <div className="acc-chart-empty">Nothing to share out yet.</div>;
  return (
    <div className="acc-share">
      <div className="acc-share-bar" role="img" aria-label={ariaLabel}>
        {parts.map((p) => {
          const v = Math.max(0, Number(p.value) || 0), pct = (v / sum) * 100;
          if (pct <= 0) return null;
          const Tag = onPick ? "button" : "span";
          return (
            <Tag key={p.key} type={onPick ? "button" : undefined} className={`acc-share-seg is-${p.tone || 1}${hover === p.key ? " is-hover" : ""}`} style={{ width: `${pct}%` }}
                 onMouseEnter={() => setHover(p.key)} onMouseLeave={() => setHover(null)} onFocus={() => setHover(p.key)} onBlur={() => setHover(null)}
                 onClick={onPick ? () => onPick(p) : undefined} title={`${p.label}: ${amount(v)} (${pct.toFixed(1)}%)`} aria-label={`${p.label} ${pct.toFixed(1)} percent`}>
              {pct >= 12 && <span className="acc-share-label">{pct.toFixed(0)}%</span>}
            </Tag>
          );
        })}
      </div>
      <ul className="acc-legend acc-share-legend">
        {parts.map((p) => { const v = Number(p.value) || 0, pct = sum ? (Math.max(0, v) / sum) * 100 : 0; return (
          <li key={p.key} className={hover === p.key ? "is-hover" : ""}><i className={`acc-key is-block is-${p.tone || 1}`} /><span>{p.label}</span><b>{amount(v)}</b><em>{pct.toFixed(1)}%</em></li>); })}
      </ul>
    </div>
  );
}

/** Ageing buckets as one bar in a single hue, light (current) to dark (oldest). */
export function AgeingBar({ buckets, onPick, label }) {
  const keys = [["d0_30", "0–30 days"], ["d31_60", "31–60"], ["d61_90", "61–90"], ["d90plus", "over 90"]];
  const total = keys.reduce((t, [k]) => t + (Number(buckets?.[k]) || 0), 0);
  const [hover, setHover] = useState(null);
  return (
    <div className="acc-ageing">
      <div className="acc-ageing-head"><b>{label}</b><span className="acc-muted">{amount(buckets?.outstanding)} outstanding · {Number(buckets?.parties) || 0} {Number(buckets?.parties) === 1 ? "party" : "parties"}</span></div>
      {total > 0 ? (
        <div className="acc-share-bar is-ageing" role="img" aria-label={`${label} by age`}>
          {keys.map(([k, lab], i) => { const v = Number(buckets[k]) || 0, pct = (v / total) * 100; if (pct <= 0) return null; return (
            <button key={k} type="button" className={`acc-share-seg is-age-${i + 1}${hover === k ? " is-hover" : ""}`} style={{ width: `${pct}%` }} onMouseEnter={() => setHover(k)} onMouseLeave={() => setHover(null)} onClick={() => onPick?.(k)} title={`${lab}: ${amount(v)} (${pct.toFixed(1)}%)`} aria-label={`${lab} ${amount(v)}`}>
              {pct >= 14 && <span className="acc-share-label">{lab}</span>}
            </button>); })}
        </div>
      ) : <div className="acc-chart-empty">Nothing outstanding.</div>}
      <ul className="acc-legend acc-share-legend">
        {keys.map(([k, lab], i) => <li key={k} className={hover === k ? "is-hover" : ""}><i className={`acc-key is-block is-age-${i + 1}`} /><span>{lab}</span><b>{amount(buckets?.[k])}</b></li>)}
      </ul>
    </div>
  );
}

/** Counts a number up on first paint (skipped under reduced motion). Returns the value to render. */
export function useCountUp(value, ms = 650) {
  const target = Number(value) || 0;
  const [shown, setShown] = useState(reduced() ? target : 0);
  const fromRef = useRef(0);
  useEffect(() => {
    if (reduced()) { setShown(target); return; }
    const start = performance.now(), from = fromRef.current;
    let raf;
    const tick = (now) => { const p = Math.min(1, (now - start) / ms), e = 1 - Math.pow(1 - p, 3); setShown(from + (target - from) * e); if (p < 1) raf = requestAnimationFrame(tick); else fromRef.current = target; };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, ms]);
  return shown;
}
