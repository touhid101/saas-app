/**
 * SalesDiscount.jsx — Sales% (per-unit sale pricing)
 *
 * Sets the discount off MRP that produces each unit's sale price, per the SRS:
 *
 *     Unit Sales Price = MRP − (MRP × Sales Percentage / 100)
 *
 * Six slots per product — BOX / STRIP / PCS for a regular (wholesale/retail)
 * customer, and the same three again for a special customer — mapping to
 * SALES_PER, SALES_PER_LEAF, SALES_PER_PCS, SALES_PER_SPC, SALES_PER_SPC_LEAF
 * and SALES_PER_SPC_RET.
 *
 * The grid edits in place: percentages are typed straight into the row and the
 * resulting price appears under each one, because the number the user is
 * actually deciding is the price, not the percentage. A row saves on its own
 * so a long catalogue never depends on one big submit.
 *
 * Replaces a version that kept four tiers in AppContext and never reached the
 * database — the "Special" tier was a single value rather than three, so
 * special-customer strip and pcs prices could not be expressed at all.
 */
import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { Card, Pill, Field, Modal } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import {
  SLOTS, REGULAR_SLOTS, SPECIAL_SLOTS,
  getSalesPercentages, saveSalesPercent, getFilterLookups,
  applyBulk, applyBulkByScope, previewScope,
  salePrice, toFormValues, validate,
} from "../services/salesPercentService.js";
import { downloadCSV } from "../utils/csv.js";

/* ── Icons ── */
const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

const P = {
  search: ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  save:   ["M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z", "M17 21v-8H7v8", "M7 3v5h8"],
  tag:    ["M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z", "M7 7h.01"],
  layers: ["M12 2 2 7l10 5 10-5-10-5Z", "M2 12l10 5 10-5", "M2 17l10 5 10-5"],
  star:   "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z",
  alert:  ["M12 9v4", "M12 17h.01", "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"],
  check:  "M20 6 9 17l-5-5",
  wand:   ["M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8L19 13M15 9h0M17.8 6.2L19 5M3 21l9-9M12.2 6.2L11 5"],
  down:   ["M12 3v13", "M6 11l6 6 6-6", "M4 20h16"],
  chevL:  "M15 18l-6-6 6-6",
  chevR:  "M9 18l6-6-6-6",
  x:      "M18 6L6 18M6 6l12 12",
};

const PAGE_SIZES = [10, 25, 50, 100];

const money = (v) => v == null || v === ""
  ? "—"
  : `৳${Number(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const inputStyle = {
  padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)",
  fontSize: 13, background: "var(--paper)", color: "var(--ink)",
};

const Spinner = ({ label = "Loading…" }) => (
  <div className="empty-state">
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" style={{ animation: "spin 1s linear infinite" }}>
      <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
    </svg>
    <div style={{ marginTop: 8, fontSize: 13 }}>{label}</div>
  </div>
);

/**
 * One percentage cell: the input, and the price it produces underneath.
 * Showing the price inline is the point of the screen — a percentage on its own
 * does not tell anyone what the customer pays.
 */
/**
 * Landed cost per box, strip and piece.
 *
 * Blank when the product holds no stock — there is no cost to state, and a zero
 * would read as "free" rather than "nothing bought yet".
 */
function BuyPriceCell({ cost }) {
  if (!cost || cost.costPerPcs == null) {
    return <span style={{ color: "var(--ink-soft)", fontSize: 11 }}>—</span>;
  }
  const line = (label, v) => (
    <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
      <span style={{ fontSize: 9, textTransform: "uppercase", letterSpacing: ".04em",
                     color: "var(--ink-soft)" }}>{label}</span>
      <span className="mono">{v == null ? "—" : money(v)}</span>
    </div>
  );
  return (
    <div style={{ fontSize: 10.5, lineHeight: 1.35, minWidth: 88 }}
         title="Weighted average landed cost of the stock on hand">
      {line("Box", cost.costPerBox)}
      {line("Strip", cost.costPerStrip)}
      {line("Pcs", cost.costPerPcs)}
    </div>
  );
}

function PctCell({ value, mrp, cost, belowCost, tone, error, disabled, onChange, ariaLabel, rowUnpriced }) {
  const filled = value !== "" && value !== null && value !== undefined && Number(value) !== 0;
  // A row of zeros has decided nothing — every unit would sell at MRP, which is
  // where an untouched row already sat. Showing six MRP figures would read as a
  // priced row, so it says so instead.
  const price = rowUnpriced ? null : salePrice(mrp, value);

  // Recomputed locally while typing, so the warning tracks the number being
  // entered rather than the last one the server saw.
  const under = price !== null && cost != null
    ? Number(price) <= Number(cost)
    : Boolean(belowCost);

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 2 }}>
      <div style={{ position: "relative", display: "inline-flex", alignItems: "center" }}>
        <input
          type="number" min="0" max="100" step="0.25" inputMode="decimal"
          value={value ?? ""} disabled={disabled} aria-label={ariaLabel}
          onChange={(e) => onChange(e.target.value)}
          placeholder="—"
          className="mono"
          style={{
            width: 72, padding: "5px 18px 5px 7px", textAlign: "right",
            border: `1.5px solid ${error ? "var(--clay)" : filled ? `var(--${tone})` : "var(--line)"}`,
            borderRadius: 7, fontSize: 12.5, fontWeight: 700,
            background: filled ? `var(--${tone}-soft)` : "var(--paper)",
            color: filled ? `var(--${tone})` : "var(--ink-soft)",
            outline: "none",
          }} />
        <span style={{
          position: "absolute", right: 6, fontSize: 10,
          color: filled ? `var(--${tone})` : "var(--ink-soft)", pointerEvents: "none",
        }}>%</span>
      </div>
      <span className="mono" style={{
        fontSize: 10.5, whiteSpace: "nowrap", fontWeight: under && !error ? 700 : 400,
        color: error ? "var(--clay)" : under ? "var(--clay)" : "var(--ink-soft)",
      }} title={under && !error && cost != null
        ? `Below the ${money(cost)} this unit cost`
        : undefined}>
        {error ? error : price === null ? "not priced" : money(price)}
        {under && !error && " ▼"}
      </span>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PAGE
   ═══════════════════════════════════════════════════════════════════════════ */

const EMPTY_FILTERS = {
  search: "", companyId: "", categoryId: "", genericId: "",
  status: "ACTIVE", configured: "ALL", belowCost: false,
};

const CONFIG_CHIPS = [
  { key: "ALL",   label: "All products",  tone: "var(--teal)" },
  { key: "true",  label: "Priced",        tone: "var(--sage)" },
  { key: "false", label: "Needs pricing", tone: "var(--amber)" },
];

export default function SalesDiscount() {
  const { addToast } = useApp();
  const perm = usePermission("salesdiscount");

  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [lookups, setLookups] = useState(null);
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [searchInput, setSearchInput] = useState("");
  const [showFilters, setShowFilters] = useState(false);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);

  /* Per-row edit buffer: only rows the user touched appear here. */
  const [edits, setEdits] = useState({});
  const [errors, setErrors] = useState({});
  const [savingId, setSavingId] = useState(null);
  const [selected, setSelected] = useState(new Set());
  const [bulkOpen, setBulkOpen] = useState(false);

  useEffect(() => {
    let dead = false;
    getFilterLookups()
      .then((d) => { if (!dead) setLookups(d); })
      .catch(() => { /* filters degrade to text search */ });
    return () => { dead = true; };
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      setFilters((f) => (f.search === searchInput ? f : { ...f, search: searchInput }));
      setPage(0);
    }, 350);
    return () => clearTimeout(t);
  }, [searchInput]);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const res = await getSalesPercentages({ ...filters, page, size });
      setRows(res.items); setTotal(res.total); setTotalPages(res.totalPages);
      setEdits({}); setErrors({});
    } catch (e) {
      setError(e.message || "Could not load products."); setRows([]); setTotal(0);
    } finally { setLoading(false); }
  }, [filters, page, size]);

  useEffect(() => { load(); }, [load]);

  const setFilter = (k, v) => { setFilters((f) => ({ ...f, [k]: v })); setPage(0); };
  const clearFilters = () => { setFilters(EMPTY_FILTERS); setSearchInput(""); setPage(0); };

  const activeFilters = useMemo(
    () => [filters.companyId, filters.categoryId, filters.genericId].filter(Boolean).length
        + (filters.status !== "ACTIVE" ? 1 : 0)
        + (filters.belowCost ? 1 : 0),
    [filters]
  );

  /**
   * Current value for a slot: the edit buffer if touched, else the stored one.
   *
   * <p>An unset slot reads as 0 rather than blank. Zero is what an unpriced unit
   * effectively sells at — full MRP — so showing it states the position the row
   * is actually in, and gives somebody a number to edit rather than an empty box
   * to work out the meaning of. Typing over it works exactly as before.
   */
  const valueOf = (row, key) => {
    const buf = edits[row.productId];
    if (buf && buf[key] !== undefined) return buf[key];
    const pct = row[key]?.pct;
    return pct === null || pct === undefined ? 0 : pct;
  };

  /**
   * Nothing has been decided for this row: every slot is zero or unset.
   *
   * <p>Read from the values on screen rather than the row's stored flag, so the
   * label follows what is being typed instead of what was last saved.
   */
  const rowUnpriced = (row) =>
    SLOTS.every(({ key }) => {
      const v = valueOf(row, key);
      return v === "" || v === null || Number(v) === 0;
    });

  const isDirty = (row) => {
    const buf = edits[row.productId];
    if (!buf) return false;
    return SLOTS.some(({ key }) => {
      const stored = row[key]?.pct;
      const storedStr = stored === null || stored === undefined ? "" : String(stored);
      return buf[key] !== undefined && String(buf[key]) !== storedStr;
    });
  };

  const editRow = (row, key, value) => {
    setEdits((e) => ({ ...e, [row.productId]: { ...(e[row.productId] ?? {}), [key]: value } }));
    setErrors((e) => {
      if (!e[row.productId]?.[key]) return e;
      const next = { ...e, [row.productId]: { ...e[row.productId] } };
      delete next[row.productId][key];
      return next;
    });
  };

  const saveRow = async (row) => {
    const current = {};
    SLOTS.forEach(({ key }) => { current[key] = valueOf(row, key); });

    const found = validate(current);
    if (Object.keys(found).length) {
      setErrors((e) => ({ ...e, [row.productId]: found }));
      addToast("Please correct the highlighted percentages.", "error");
      return;
    }

    setSavingId(row.productId);
    try {
      const saved = await saveSalesPercent(row.productId, current);
      setRows((list) => list.map((r) => (r.productId === saved.productId ? saved : r)));
      setEdits((e) => { const n = { ...e }; delete n[row.productId]; return n; });
      addToast(`Sales% saved for ${saved.productName}.`);
    } catch (e) {
      addToast(e.message || "Could not save.", "error");
    } finally { setSavingId(null); }
  };

  const saveAllDirty = async () => {
    const dirty = rows.filter(isDirty);
    if (!dirty.length) { addToast("Nothing to save.", "warning"); return; }
    for (const row of dirty) await saveRow(row);
  };

  const toggleSelect = (id) => setSelected((s) => {
    const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n;
  });

  const exportCsv = () => {
    const data = rows.map((r) => ({
      Code: r.productCode ?? "", Product: r.productName ?? "",
      Company: r.companyName ?? "", MRP: r.mrp ?? "",
      "Box %": r.box?.pct ?? "", "Box Price": r.box?.price ?? "",
      "Strip %": r.strip?.pct ?? "", "Strip Price": r.strip?.price ?? "",
      "PCS %": r.pcs?.pct ?? "", "PCS Price": r.pcs?.price ?? "",
      "Spc Box %": r.specialBox?.pct ?? "", "Spc Box Price": r.specialBox?.price ?? "",
      "Spc Strip %": r.specialStrip?.pct ?? "", "Spc Strip Price": r.specialStrip?.price ?? "",
      "Spc PCS %": r.specialPcs?.pct ?? "", "Spc PCS Price": r.specialPcs?.price ?? "",
    }));
    downloadCSV("sales-percentages.csv", data, Object.keys(data[0] ?? { Code: "" }));
    addToast(`Exported ${data.length} row${data.length === 1 ? "" : "s"}.`);
  };

  const dirtyCount = rows.filter(isDirty).length;
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, total);
  const canEdit = perm.canEdit || perm.canCreate;

  const kpis = useMemo(() => {
    const priced = rows.filter((r) => !r.unconfigured).length;
    const complete = rows.filter((r) => r.regularComplete).length;
    return [
      { label: "Products", value: total, tone: "teal", icon: P.layers },
      { label: "Priced (page)", value: priced, tone: "sage", icon: P.check },
      { label: "All 3 units (page)", value: complete, tone: "teal", icon: P.tag },
      { label: "Needs pricing (page)", value: rows.length - priced, tone: "amber", icon: P.alert },
    ];
  }, [rows, total]);

  if (!perm.canView) {
    return (
      <div className="page">
        <div className="page-head"><div><h1>Sales Percentage</h1></div></div>
        <Card><div className="empty-state">You do not have permission to view Sales%.</div></Card>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Sales Percentage</h1>
          <div className="page-sub">
            Discount off MRP per selling unit. Sale price = MRP − (MRP × Sales% ÷ 100),
            set separately for Box, Strip and PCS, and again for special customers.
          </div>
        </div>
        <div className="flex-gap" style={{ flexWrap: "wrap" }}>
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" onClick={exportCsv} disabled={!rows.length}>
              <Ic path={P.down} size={13} /> Export CSV
            </button>
          )}
          {canEdit && (
            <button className="btn btn-teal btn-sm" onClick={() => setBulkOpen(true)}
              title="Apply one set of percentages to a whole company, category or the ticked rows">
              <Ic path={P.wand} size={13} />
              {selected.size ? `Bulk apply (${selected.size} selected)` : "Bulk apply by company"}
            </button>
          )}
          {canEdit && (
            <button className="btn btn-primary" onClick={saveAllDirty} disabled={!dirtyCount || savingId != null}>
              <Ic path={P.save} size={14} /> Save {dirtyCount || ""} change{dirtyCount === 1 ? "" : "s"}
            </button>
          )}
        </div>
      </div>

      <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(168px, 1fr))" }}>
        {kpis.map((k) => (
          <div className="kpi-card" key={k.label} style={{ padding: "14px 16px" }}>
            <div className="kpi-top" style={{ marginBottom: 8 }}>
              <div className="kpi-icon" style={{ width: 30, height: 30, background: `var(--${k.tone}-soft)`, color: `var(--${k.tone})` }}>
                <Ic path={k.icon} size={15} />
              </div>
            </div>
            <div className="kpi-value" style={{ fontSize: 21 }}>{k.value}</div>
            <div className="kpi-label" style={{ marginTop: 4 }}>{k.label}</div>
          </div>
        ))}
      </div>

      {/* Chips */}
      <div style={{ display: "flex", gap: 7, flexWrap: "wrap", marginBottom: 16, alignItems: "center" }}>
        {CONFIG_CHIPS.map((c) => {
          const active = String(filters.configured) === c.key;
          return (
            <button key={c.key} onClick={() => setFilter("configured", c.key)}
              style={{
                padding: "6px 14px", borderRadius: 999, fontSize: 12.5, fontWeight: 600,
                cursor: "pointer", border: active ? "none" : "1.5px solid var(--line)",
                background: active ? c.tone : "var(--card)", color: active ? "#fff" : "var(--ink)",
                transition: "all .12s",
              }}>{c.label}</button>
          );
        })}

        {/* Separate from the three above because it answers a different question:
            those are about whether a price has been set, this is about whether
            the price set is losing money. Both can be on at once. */}
        <button
          onClick={() => setFilter("belowCost", !filters.belowCost)}
          title="Only products where a discounted price has fallen to or below what the stock cost"
          style={{
            padding: "6px 14px", borderRadius: 999, fontSize: 12.5, fontWeight: 600,
            cursor: "pointer", border: filters.belowCost ? "none" : "1.5px solid var(--line)",
            background: filters.belowCost ? "var(--clay)" : "var(--card)",
            color: filters.belowCost ? "#fff" : "var(--ink)",
            transition: "all .12s", display: "inline-flex", alignItems: "center", gap: 6,
          }}>
          <span aria-hidden="true">▼</span> At or below cost
        </button>
        <button className={`btn btn-sm ${activeFilters ? "btn-primary" : "btn-ghost"}`}
          style={{ marginLeft: "auto" }} onClick={() => setShowFilters((s) => !s)}>
          Filters{activeFilters ? ` (${activeFilters})` : ""}
        </button>
      </div>

      {/* Filter bar */}
      <div style={{
        display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end", marginBottom: 16,
        padding: "12px 16px", background: "var(--card)", borderRadius: "var(--radius)",
        border: "1px solid var(--line)",
      }}>
        <div className="search-box" style={{ flex: 2, minWidth: 230, maxWidth: 360 }}>
          <Ic path={P.search} size={14} />
          <input placeholder="Search product, code, SKU or barcode…" value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)} aria-label="Search products"
            style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1, color: "var(--ink)" }} />
          {searchInput && (
            <button onClick={() => setSearchInput("")} aria-label="Clear search"
              style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>
          )}
        </div>

        {showFilters && (
          <>
            <FilterField label="Company">
              <select value={filters.companyId} onChange={(e) => setFilter("companyId", e.target.value)} style={inputStyle}>
                <option value="">All companies</option>
                {(lookups?.companies ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </FilterField>
            <FilterField label="Category">
              <select value={filters.categoryId} onChange={(e) => setFilter("categoryId", e.target.value)} style={inputStyle}>
                <option value="">All categories</option>
                {(lookups?.categories ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </FilterField>
            <FilterField label="Generic">
              <select value={filters.genericId} onChange={(e) => setFilter("genericId", e.target.value)} style={inputStyle}>
                <option value="">All generics</option>
                {(lookups?.generics ?? []).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select>
            </FilterField>
            <FilterField label="Status">
              <select value={filters.status} onChange={(e) => setFilter("status", e.target.value)} style={inputStyle}>
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
                <option value="ALL">All</option>
              </select>
            </FilterField>
          </>
        )}

        {(activeFilters > 0 || filters.search) && (
          <button className="btn btn-ghost btn-sm" onClick={clearFilters} style={{ marginLeft: "auto" }}>
            Clear all
          </button>
        )}
      </div>

      {/* Grid */}
      <Card pad={false}>
        <div style={{ padding: "14px 18px 0" }}>
          <div className="card-head">
            <div>
              <div className="card-eyebrow">Sales%</div>
              <div className="card-title">Unit Pricing</div>
            </div>
            <div className="flex-gap">
              {dirtyCount > 0 && <Pill tone="amber">{dirtyCount} unsaved</Pill>}
              <Pill tone="teal">{total} product{total === 1 ? "" : "s"}</Pill>
            </div>
          </div>
        </div>

        {loading ? <Spinner label="Loading products…" />
          : error ? (
            <div className="empty-state">
              <div style={{ color: "var(--clay)", marginBottom: 10 }}>{error}</div>
              <button className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
            </div>
          ) : !rows.length ? (
            <div className="empty-state">
              <div>No products match your filters.</div>
              <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={clearFilters}>Clear filters</button>
            </div>
          ) : (
            <div className="table-wrap" style={{ padding: "0 18px" }}>
              <table className="data-table">
                <thead>
                  {/* Two header rows: the tier grouping, then the units under it.
                      Six flat columns would not show which three belong to whom. */}
                  <tr>
                    <th rowSpan={2} style={{ width: 34 }} />
                    <th rowSpan={2}>Product</th>
                    <th rowSpan={2} style={{ textAlign: "right" }}>MRP</th>
                    <th rowSpan={2} style={{ textAlign: "right" }}>Buy Price</th>
                    <th colSpan={3} style={{ textAlign: "center", color: "var(--teal)" }}>
                      Wholesale / Retail
                    </th>
                    <th colSpan={3} style={{ textAlign: "center", color: "var(--amber-deep)" }}>
                      Special Customer
                    </th>
                    <th rowSpan={2} />
                  </tr>
                  <tr>
                    {REGULAR_SLOTS.map((s) => (
                      <th key={s.key} style={{ textAlign: "right" }}>{s.label}</th>
                    ))}
                    {SPECIAL_SLOTS.map((s) => (
                      <th key={s.key} style={{ textAlign: "right" }}>{s.label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => {
                    const dirty = isDirty(row);
                    const rowErrors = errors[row.productId] ?? {};
                    return (
                      <tr key={row.productId}
                        // Unsaved edits win the background — that state is
                        // about to change and is the more urgent thing to see.
                        // Below-cost is a standing condition underneath it.
                        className={row.belowCost && !dirty ? "row-below-cost" : undefined}
                        title={row.belowCost
                          ? "At least one price is at or below what this stock cost"
                          : undefined}
                        style={{
                          background: dirty ? "var(--amber-soft)" : undefined,
                          opacity: row.status === "INACTIVE" ? 0.6 : 1,
                        }}>
                        <td>
                          <input type="checkbox" checked={selected.has(row.productId)}
                            onChange={() => toggleSelect(row.productId)}
                            aria-label={`Select ${row.productName}`}
                            style={{ accentColor: "var(--amber)" }} />
                        </td>

                        <td style={{ minWidth: 200 }}>
                          <div style={{ fontWeight: 700, display: "flex", gap: 6, alignItems: "center" }}>
                            {row.productName}
                            {row.unconfigured && <Pill tone="amber">unpriced</Pill>}
                          </div>
                          <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
                            <span className="mono">{row.productCode}</span>
                            {row.companyName ? ` · ${row.companyName}` : ""}
                            {row.pcsPerBox ? ` · box of ${row.pcsPerBox}` : ""}
                          </div>
                        </td>

                        <td className="mono" style={{ textAlign: "right", fontWeight: 700 }}>
                          {money(row.mrp)}
                          <div style={{ fontSize: 9.5, color: "var(--ink-soft)", fontWeight: 400 }}>
                            per box
                          </div>
                        </td>

                        {/* Landed cost per unit, so a percentage can be judged
                            against what the stock cost and not only against
                            list price. Blank when the product holds none. */}
                        <td style={{ textAlign: "right" }}>
                          <BuyPriceCell cost={row.cost} />
                        </td>

                        {SLOTS.map((s) => (
                          <td key={s.key} style={{ textAlign: "right" }}>
                            <PctCell
                              value={valueOf(row, s.key)}
                              mrp={row[s.key]?.mrp ?? row.mrp}
                              cost={row[s.key]?.cost}
                              belowCost={row[s.key]?.belowCost}
                              tone={s.tier === "SPECIAL" ? "amber" : "teal"}
                              error={rowErrors[s.key]}
                              disabled={!canEdit}
                              ariaLabel={`${s.tier === "SPECIAL" ? "Special " : ""}${s.label} percent for ${row.productName}`}
                              rowUnpriced={rowUnpriced(row)}
                              onChange={(v) => editRow(row, s.key, v)} />
                          </td>
                        ))}

                        <td>
                          {canEdit && (
                            <button className="btn btn-sm btn-primary" disabled={!dirty || savingId === row.productId}
                              onClick={() => saveRow(row)}
                              style={{ opacity: dirty ? 1 : 0.35 }}>
                              {savingId === row.productId ? "…" : <Ic path={P.save} size={12} />}
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

        {!loading && !error && total > 0 && (
          <div style={{
            display: "flex", justifyContent: "space-between", alignItems: "center",
            gap: 12, flexWrap: "wrap", padding: "14px 18px", borderTop: "1px solid var(--line)",
          }}>
            <div style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
              Showing <b className="mono">{from}–{to}</b> of <b className="mono">{total}</b>
            </div>
            <div className="flex-gap">
              <select value={size} onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                aria-label="Rows per page" style={{ ...inputStyle, width: "auto" }}>
                {PAGE_SIZES.map((s) => <option key={s} value={s}>{s} / page</option>)}
              </select>
              <button className="btn btn-ghost btn-sm" disabled={page === 0} aria-label="Previous page"
                onClick={() => setPage((p) => Math.max(0, p - 1))}><Ic path={P.chevL} size={13} /></button>
              <span className="mono" style={{ fontSize: 12, minWidth: 92, textAlign: "center" }}>
                Page {page + 1} of {Math.max(totalPages, 1)}
              </span>
              <button className="btn btn-ghost btn-sm" disabled={page + 1 >= totalPages} aria-label="Next page"
                onClick={() => setPage((p) => p + 1)}><Ic path={P.chevR} size={13} /></button>
            </div>
          </div>
        )}
      </Card>

      {bulkOpen && (
        <BulkModal productIds={[...selected]} lookups={lookups} addToast={addToast}
          initialScope={{ companyId: filters.companyId, categoryId: filters.categoryId, genericId: filters.genericId }}
          onClose={() => setBulkOpen(false)}
          onDone={() => { setBulkOpen(false); setSelected(new Set()); load(); }} />
      )}
    </div>
  );
}

const FilterField = ({ label, children }) => (
  <div>
    <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>{label}</div>
    {children}
  </div>
);

/* ═══════════════════════════════════════════════════════════════════════════
   BULK APPLY
   ═══════════════════════════════════════════════════════════════════════════ */

function BulkModal({ productIds, lookups, initialScope, addToast, onClose, onDone }) {
  /* Company-wise is the default target: a manufacturer quotes one trade rate
     for its whole range, which is how pricing is actually negotiated. Ticking
     rows is the exception, so it is only offered when rows are ticked. */
  const [mode, setMode] = useState(productIds.length ? "selected" : "scope");
  const [scope, setScope] = useState(() => ({
    companyId: initialScope?.companyId ?? "",
    categoryId: initialScope?.categoryId ?? "",
    genericId: initialScope?.genericId ?? "",
    status: "ACTIVE",
    configured: "ALL",
  }));

  const [values, setValues] = useState(() => toFormValues(null));
  const [overwrite, setOverwrite] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  const [matchCount, setMatchCount] = useState(null);
  const [counting, setCounting] = useState(false);

  const set = (k, v) => setValues((s) => ({ ...s, [k]: v }));
  const setScopeField = (k, v) => setScope((s) => ({ ...s, [k]: v }));

  const scopeEmpty = !scope.companyId && !scope.categoryId && !scope.genericId;

  /* Count the matches as the scope changes, so the confirmation can state the
     blast radius before anything is written. */
  useEffect(() => {
    if (mode !== "scope" || scopeEmpty) { setMatchCount(null); return undefined; }
    let dead = false;
    setCounting(true);
    const t = setTimeout(() => {
      previewScope(scope)
        .then((n) => { if (!dead) setMatchCount(n); })
        .catch(() => { if (!dead) setMatchCount(null); })
        .finally(() => { if (!dead) setCounting(false); });
    }, 250);
    return () => { dead = true; clearTimeout(t); };
  }, [mode, scope.companyId, scope.categoryId, scope.genericId, scope.status, scope.configured]); // eslint-disable-line react-hooks/exhaustive-deps

  const targetCount = mode === "selected" ? productIds.length : matchCount;

  const run = async () => {
    const found = validate(values);
    if (Object.keys(found).length) { addToast("Correct the highlighted percentages.", "error"); return; }
    const anySet = SLOTS.some(({ key }) => values[key] !== "" && values[key] != null);
    if (!anySet) { addToast("Enter at least one percentage to apply.", "warning"); return; }
    if (mode === "scope" && scopeEmpty) {
      addToast("Choose a company, category or generic to apply to.", "warning"); return;
    }

    setBusy(true);
    try {
      const res = mode === "scope"
        ? await applyBulkByScope(scope, values, overwrite)
        : await applyBulk(productIds, values, overwrite);
      setResult(res);
      addToast(`Applied to ${res.updated} product${res.updated === 1 ? "" : "s"}.`);
    } catch (e) {
      addToast(e.message || "Bulk apply failed.", "error");
    } finally { setBusy(false); }
  };

  const companyName = (lookups?.companies ?? []).find((c) => String(c.id) === String(scope.companyId))?.name;

  return (
    <Modal title="Apply Sales% in bulk"
      onClose={result ? onDone : onClose} width={660}
      footer={result ? (
        <button className="btn btn-primary" onClick={onDone}>Done</button>
      ) : (
        <>
          <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={run}
            disabled={busy || (mode === "scope" && (scopeEmpty || !matchCount))}>
            {busy ? "Applying…"
              : targetCount ? `Apply to ${targetCount} product${targetCount === 1 ? "" : "s"}`
              : "Apply"}
          </button>
        </>
      )}>
      {result ? (
        <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(3, 1fr)" }}>
          {[
            { label: "Updated", value: result.updated, tone: "sage" },
            { label: "Skipped", value: result.skipped, tone: "amber" },
            { label: "Failed", value: result.failed, tone: "clay" },
          ].map((k) => (
            <div className="kpi-card" key={k.label} style={{ padding: "12px 14px" }}>
              <div className="kpi-value" style={{ fontSize: 20, color: `var(--${k.tone})` }}>{k.value}</div>
              <div className="kpi-label">{k.label}</div>
            </div>
          ))}
        </div>
      ) : (
        <>
          {/* ── Target ── */}
          <div className="card-eyebrow" style={{ marginBottom: 8 }}>Apply to</div>
          <div style={{ display: "flex", gap: 8, marginBottom: 12, flexWrap: "wrap" }}>
            <ModeTab active={mode === "scope"} onClick={() => setMode("scope")}
              label="A whole company / category" />
            <ModeTab active={mode === "selected"} onClick={() => setMode("selected")}
              disabled={!productIds.length}
              label={productIds.length ? `${productIds.length} selected row${productIds.length === 1 ? "" : "s"}` : "Selected rows (none ticked)"} />
          </div>

          {mode === "scope" ? (
            <>
              <div style={{ display: "grid", gap: "0 12px", gridTemplateColumns: "repeat(auto-fit, minmax(170px, 1fr))" }}>
                <Field label="Company">
                  <select value={scope.companyId} onChange={(e) => setScopeField("companyId", e.target.value)}>
                    <option value="">All companies</option>
                    {(lookups?.companies ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </Field>
                <Field label="Category">
                  <select value={scope.categoryId} onChange={(e) => setScopeField("categoryId", e.target.value)}>
                    <option value="">All categories</option>
                    {(lookups?.categories ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </Field>
                <Field label="Generic">
                  <select value={scope.genericId} onChange={(e) => setScopeField("genericId", e.target.value)}>
                    <option value="">All generics</option>
                    {(lookups?.generics ?? []).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
                  </select>
                </Field>
                <Field label="Only">
                  <select value={scope.configured} onChange={(e) => setScopeField("configured", e.target.value)}>
                    <option value="ALL">All matching products</option>
                    <option value="false">Unpriced products only</option>
                    <option value="true">Already-priced products only</option>
                  </select>
                </Field>
              </div>

              <div style={{
                padding: "10px 13px", borderRadius: "var(--radius-sm)", marginBottom: 14,
                background: scopeEmpty ? "var(--amber-soft)" : "var(--teal-soft)",
                border: `1px solid ${scopeEmpty ? "var(--amber)" : "var(--teal)"}`,
                fontSize: 12.5, color: scopeEmpty ? "var(--amber-deep)" : "var(--teal)",
                display: "flex", alignItems: "center", gap: 8,
              }}>
                <Ic path={scopeEmpty ? P.alert : P.check} size={15} />
                <span>
                  {scopeEmpty
                    ? "Choose at least a company, category or generic — an empty scope would reprice the whole catalogue."
                    : counting ? "Counting matching products…"
                    : matchCount === 0 ? "No products match this selection."
                    : <>This will update <b className="mono">{matchCount}</b> product{matchCount === 1 ? "" : "s"}
                        {companyName ? <> in <b>{companyName}</b></> : null}.</>}
                </span>
              </div>
            </>
          ) : (
            <div style={{
              padding: "10px 13px", borderRadius: "var(--radius-sm)", marginBottom: 14,
              background: "var(--teal-soft)", border: "1px solid var(--teal)",
              fontSize: 12.5, color: "var(--teal)",
            }}>
              Applies to the <b className="mono">{productIds.length}</b> row
              {productIds.length === 1 ? "" : "s"} you ticked.
            </div>
          )}

          {/* ── Percentages ── */}
          <div className="card-eyebrow" style={{ margin: "4px 0 8px" }}>Percentages</div>
          <div style={{ fontSize: 12.5, color: "var(--ink-soft)", marginBottom: 12, lineHeight: 1.6 }}>
            Leave a box empty to leave that unit alone.
          </div>

          <div style={{ display: "grid", gap: 16, gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))" }}>
            {[["Wholesale / Retail", REGULAR_SLOTS, "teal"], ["Special Customer", SPECIAL_SLOTS, "amber"]]
              .map(([title, slots, tone]) => (
                <div key={title}>
                  <div className="card-eyebrow" style={{ marginBottom: 8, color: `var(--${tone})` }}>{title}</div>
                  {slots.map((s) => (
                    <Field key={s.key} label={`${s.label} %`}>
                      <input type="number" min="0" max="100" step="0.25" className="mono"
                        value={values[s.key] ?? ""} placeholder="—"
                        onChange={(e) => set(s.key, e.target.value)} />
                    </Field>
                  ))}
                </div>
              ))}
          </div>

          <label style={{
            display: "flex", gap: 10, alignItems: "flex-start", marginTop: 8, padding: "11px 13px",
            border: "1px solid var(--line)", borderRadius: 10, cursor: "pointer",
            background: overwrite ? "var(--clay-soft)" : "var(--paper)",
          }}>
            <input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)}
              style={{ accentColor: "var(--clay)", marginTop: 2 }} />
            <div>
              <div style={{ fontSize: 13, fontWeight: 700 }}>Overwrite existing percentages</div>
              <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 2, lineHeight: 1.5 }}>
                Off by default: only unpriced units are filled in, so a bulk apply
                cannot wipe prices someone tuned by hand.
              </div>
            </div>
          </label>
        </>
      )}
    </Modal>
  );
}

const ModeTab = ({ active, disabled, label, onClick }) => (
  <button type="button" onClick={onClick} disabled={disabled}
    aria-pressed={active}
    style={{
      padding: "9px 14px", borderRadius: 10, fontSize: 12.5,
      fontWeight: active ? 700 : 600,
      border: `1.5px solid ${active ? "var(--amber)" : "var(--line)"}`,
      background: active ? "var(--amber-soft)" : "var(--paper)",
      color: active ? "var(--amber-deep)" : "var(--ink)",
      cursor: disabled ? "not-allowed" : "pointer", opacity: disabled ? 0.5 : 1,
      transition: "all .12s",
    }}>{label}</button>
);
