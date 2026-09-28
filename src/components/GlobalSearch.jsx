/**
 * The search palette.
 *
 * What this replaces: the same six mockData arrays the app ships as seed data,
 * filtered in the browser. It never called the server, so typing a real
 * customer's name found nothing and typing a seed name found a record that does
 * not exist in this database.
 *
 * Results now come from /api/v1/search, scoped to the signed-in user's branch
 * and to the modules they may already view — searching reaches nothing they
 * could not already open.
 */
import React, { useState, useEffect, useRef, useCallback } from "react";
import { searchAll } from "../services/searchService.js";

const Ic = ({ d, size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    <path d={d} />
  </svg>
);

/** Icon and order per kind. The server names the kind; this only draws it. */
const TYPE_META = {
  Product:     { icon: "M21 8 12 3 3 8l9 5 9-5z M3 8v9l9 5 9-5V8 M12 13v9" },
  Customer:    { icon: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z" },
  Supplier:    { icon: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z" },
  Sale:        { icon: "M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z M9 7h6 M9 11h6" },
  Purchase:    { icon: "M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z M9 7h6 M9 11h6" },
  Requisition: { icon: "M9 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9z M13 2v7h7 M9 12h6" },
};
const TYPE_ORDER = ["Product", "Customer", "Supplier", "Sale", "Purchase", "Requisition"];

/** Matches the server's own minimum: below this every query matches everything. */
const MIN_CHARS = 2;

export default function GlobalSearch({ onClose, onNavigate }) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState([]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const inputRef = useRef(null);

  useEffect(() => { inputRef.current?.focus(); }, []);

  /* Debounced, and every response checked against the query that is current by
     the time it lands. Without that a slow answer for "na" can overwrite a fast
     one for "napa" and show results for something the reader has stopped
     typing. */
  useEffect(() => {
    const term = q.trim();
    if (term.length < MIN_CHARS) { setResults([]); setBusy(false); setFailed(false); return; }

    let live = true;
    setBusy(true);
    const timer = setTimeout(async () => {
      const { items, failed: err } = await searchAll(term);
      if (!live) return;
      setResults(items);
      setFailed(err);
      setBusy(false);
    }, 220);

    return () => { live = false; clearTimeout(timer); };
  }, [q]);

  const handleSelect = useCallback((item) => {
    if (item.link && onNavigate) onNavigate(item.link);
    onClose();
  }, [onNavigate, onClose]);

  const tooShort = q.trim().length > 0 && q.trim().length < MIN_CHARS;

  return (
    <div style={{
      position: "fixed", inset: 0, background: "rgba(24,36,32,0.55)", zIndex: 500,
      display: "flex", alignItems: "flex-start", justifyContent: "center", paddingTop: 80,
    }} onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: "var(--card)", borderRadius: "var(--radius)", width: "100%", maxWidth: 600, boxShadow: "var(--shadow-lg)", overflow: "hidden" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", borderBottom: "1px solid var(--line)" }}>
          <Ic d="M21 21l-4.3-4.3M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z" size={18} />
          <input
            ref={inputRef}
            value={q}
            onChange={e => setQ(e.target.value)}
            placeholder="Search products, customers, invoices, suppliers…"
            style={{ flex: 1, border: "none", outline: "none", fontSize: 15.5, background: "transparent", color: "var(--ink)" }}
          />
          {busy && <span style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>searching…</span>}
          <kbd style={{ fontSize: 11, background: "var(--paper)", border: "1px solid var(--line)", borderRadius: 5, padding: "2px 7px", color: "var(--ink-soft)" }}>ESC</kbd>
        </div>

        <div style={{ maxHeight: 440, overflowY: "auto" }}>
          {!q && (
            <div style={{ padding: "32px 20px", textAlign: "center", color: "var(--ink-soft)", fontSize: 13.5 }}>
              Search this branch — products, customers, suppliers, invoices, requisitions
            </div>
          )}

          {tooShort && (
            <div style={{ padding: "32px 20px", textAlign: "center", color: "var(--ink-soft)", fontSize: 13.5 }}>
              Keep typing — at least {MIN_CHARS} characters.
            </div>
          )}

          {/* A search that could not run must not read as a search that found
              nothing. The old one reported "no results" for both, for ever. */}
          {failed && !busy && (
            <div style={{ padding: "32px 20px", textAlign: "center", color: "var(--clay)", fontSize: 13.5 }} role="alert">
              Search is unavailable right now. Nothing is wrong with your records.
            </div>
          )}

          {!failed && !busy && !tooShort && q.trim().length >= MIN_CHARS && results.length === 0 && (
            <div style={{ padding: "32px 20px", textAlign: "center", color: "var(--ink-soft)", fontSize: 13.5 }}>
              Nothing in this branch matches "{q.trim()}".
            </div>
          )}

          {results.length > 0 && TYPE_ORDER.map(type => {
            const group = results.filter(r => r.type === type);
            if (!group.length) return null;
            const meta = TYPE_META[type] || TYPE_META.Product;
            return (
              <div key={type}>
                <div style={{ padding: "8px 18px 4px", fontSize: 10.5, fontWeight: 700, textTransform: "uppercase", letterSpacing: ".1em", color: "var(--amber-deep)", background: "var(--paper-deep)" }}>
                  {type}s
                </div>
                {group.map(item => (
                  <button
                    key={`${item.type}-${item.id}`}
                    onClick={() => handleSelect(item)}
                    style={{ width: "100%", padding: "11px 18px", border: "none", background: "none", textAlign: "left", cursor: "pointer", display: "flex", alignItems: "center", gap: 12, borderBottom: "1px solid var(--line)" }}
                    onMouseEnter={e => e.currentTarget.style.background = "var(--paper)"}
                    onMouseLeave={e => e.currentTarget.style.background = "none"}
                  >
                    <div style={{ width: 32, height: 32, borderRadius: 8, background: "var(--amber-soft)", color: "var(--amber-deep)", display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
                      <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                        {meta.icon.split(" M").map((seg, i) => <path key={i} d={(i === 0 ? "" : "M") + seg} />)}
                      </svg>
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: 600, fontSize: 13.5, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {item.label}
                      </div>
                      {item.sub && (
                        <div style={{ fontSize: 11.5, color: "var(--ink-soft)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                          {item.sub}
                        </div>
                      )}
                    </div>
                    <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>↵</span>
                  </button>
                ))}
              </div>
            );
          })}
        </div>

        <div style={{ padding: "8px 18px", borderTop: "1px solid var(--line)", fontSize: 11.5, color: "var(--ink-soft)", display: "flex", gap: 16 }}>
          <span>↵ Select</span><span>ESC Close</span>
          <span style={{ marginLeft: "auto" }}>This branch only</span>
        </div>
      </div>
    </div>
  );
}
