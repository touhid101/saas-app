import React from "react";

const PALETTE = ["#B8752B", "#2E6A62", "#4C7A5E", "#A8452E", "#8F5A1E", "#9FB0A5"];

export function BarChart({ data, height = 180, valueFmt = (v) => v }) {
  const max = Math.max(1, ...data.map(d => d.amount));
  const w = 100 / data.length;
  return (
    <div style={{ display: "flex", alignItems: "flex-end", height, gap: 4 }}>
      {data.map((d, i) => (
        <div key={i} title={`${d.date}: ${valueFmt(d.amount)}`} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "flex-end", height: "100%" }}>
          <div style={{
            width: "70%", height: `${Math.max(3, (d.amount / max) * 100)}%`,
            background: "linear-gradient(180deg, #B8752B, #8F5A1E)", borderRadius: "5px 5px 2px 2px",
          }} />
          <div style={{ fontSize: 9.5, color: "var(--ink-soft)", marginTop: 6, fontFamily: "'IBM Plex Mono', monospace" }}>{d.date}</div>
        </div>
      ))}
    </div>
  );
}

export function Donut({ data, size = 150, thickness = 22 }) {
  const total = data.reduce((s, d) => s + d.value, 0) || 1;
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  let offset = 0;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <g transform={`rotate(-90 ${size / 2} ${size / 2})`}>
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#ECEADC" strokeWidth={thickness} />
          {data.map((d, i) => {
            const frac = d.value / total;
            const dash = frac * c;
            const el = (
              <circle key={i} cx={size / 2} cy={size / 2} r={r} fill="none" stroke={PALETTE[i % PALETTE.length]}
                strokeWidth={thickness} strokeDasharray={`${dash} ${c - dash}`} strokeDashoffset={-offset} strokeLinecap="butt" />
            );
            offset += dash;
            return el;
          })}
        </g>
      </svg>
      <div className="stack" style={{ gap: 8 }}>
        {data.map((d, i) => (
          <div key={i} className="flex-gap" style={{ fontSize: 12.5 }}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: PALETTE[i % PALETTE.length], display: "inline-block" }} />
            <span style={{ fontWeight: 600 }}>{d.label}</span>
            <span className="text-soft mono">{d.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function RegionBars({ data, max, colorFor }) {
  const m = max || Math.max(1, ...data.map(d => d.amount));
  return (
    <div>
      {data.map((d, i) => (
        <div className="bar-row" key={i}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>{d.region}</span>
          <div className="bar-track">
            <div className="bar-fill" style={{ width: `${(d.amount / m) * 100}%`, background: colorFor ? colorFor(i) : PALETTE[i % PALETTE.length] }} />
          </div>
          <span className="mono" style={{ fontSize: 12, textAlign: "right" }}>{Math.round(d.amount).toLocaleString()}</span>
        </div>
      ))}
    </div>
  );
}

export function Sparkline({ points, width = 120, height = 34, color = "#4C7A5E" }) {
  const max = Math.max(1, ...points);
  const min = Math.min(...points);
  const range = max - min || 1;
  const step = width / (points.length - 1 || 1);
  const d = points.map((p, i) => `${i === 0 ? "M" : "L"} ${i * step} ${height - ((p - min) / range) * height}`).join(" ");
  return (
    <svg width={width} height={height}>
      <path d={d} fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
