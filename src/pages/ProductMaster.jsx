/**
 * ProductMaster.jsx — Master Data › Product
 *
 * Follows the house layout used by Sales Activity and the other operational
 * pages: `.page` wrapper → `.page-head` with an <h1> and `.page-sub` → KPI strip
 * → filter chips → filter bar → `.data-table` → pagination.
 *
 * Every colour comes from a CSS custom property defined in styles.css, with no
 * hardcoded fallbacks, so the page follows all four themes (light, dark, ocean,
 * forest). Typography follows the system: Fraunces for headings via <h1>/.card-title,
 * IBM Plex Mono via .mono for codes and figures, Inter for everything else.
 *
 * Views: list | form | detail, switched in place.
 */
import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { Card, Pill, Field, Modal } from "../components/UI.jsx";
import SearchSelect from "../components/SearchSelect.jsx";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import { useApp, CAP_BRANCH_CATALOGUE } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import {
  getProducts, getProduct, createProduct, updateProduct, deleteProduct,
  setProductStatus, uploadProductImage, deleteProductImage,
  getFormLookups, calculatePrices, toFormValues, validateForm, resolveImageUrl,
  checkProductName,
  bulkImportProducts,
} from "../services/productMasterService.js";
import {
  downloadTemplate, parseWorkbook, downloadImportResult, downloadProductsExcel,
} from "../services/productExcel.js";

/* ── Icon helper — matches the `Ic` convention used across the app ── */
const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

const P = {
  search:   ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  plus:     "M12 5v14M5 12h14",
  download: ["M12 3v13", "M6 11l6 6 6-6", "M4 20h16"],
  upload:   ["M12 20V7", "M6 12l6-6 6 6", "M4 20h16"],
  sheet:    ["M4 3h16v18H4z", "M4 9h16M4 15h16M10 3v18M16 3v18"],
  edit:     ["M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"],
  eye:      ["M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"],
  trash:    ["M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"],
  power:    ["M18.36 6.64a9 9 0 1 1-12.73 0", "M12 2v10"],
  image:    ["M3 3h18v18H3z", "M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M21 15l-5-5L5 21"],
  back:     ["M19 12H5", "M12 19l-7-7 7-7"],
  lock:     ["M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4"],
  calc:     ["M4 2h16v20H4z", "M8 6h8M8 10h2M12 10h2M16 10h.01M8 14h2M12 14h2M16 14h.01M8 18h8"],
  box:      ["M21 8 12 3 3 8l9 5 9-5z", "M3 8v9l9 5 9-5V8", "M12 13v9"],
  alert:    ["M12 9v4", "M12 17h.01", "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"],
  check:    "M20 6 9 17l-5-5",
  x:        "M18 6L6 18M6 6l12 12",
  chevL:    "M15 18l-6-6 6-6",
  chevR:    "M9 18l6-6-6-6",
  pill:     ["M10.5 20.5a7.78 7.78 0 0 1-11-11l9-9a7.78 7.78 0 0 1 11 11z", "M8.5 8.5l7 7"],
  rx:       ["M7 21V8h4a3 3 0 0 1 0 6H7", "M11 14l6 7", "M14 16l4-4"],
  layers:   ["M12 2 2 7l10 5 10-5-10-5Z", "M2 12l10 5 10-5", "M2 17l10 5 10-5"],
  warn:     ["M12 8v5", "M12 16h.01", "M12 3 2 20h20L12 3z"],
};

const PAGE_SIZES = [10, 25, 50, 100];
const money = (v) => v == null || v === "" ? "—" : `৳${Number(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const stockTone = (s) => (s === "OUT" ? "clay" : s === "LOW" ? "amber" : "sage");

const ALL_COLUMNS = [
  { key: "image",        label: "",          always: true },
  { key: "productCode",  label: "Code",      sort: "productCode", always: true },
  { key: "productName",  label: "Product",   sort: "productName", always: true },
  { key: "productType",  label: "Type" },
  { key: "genericName",  label: "Generic" },
  { key: "companyName",  label: "Company" },
  { key: "categoryName", label: "Category" },
  { key: "sku",          label: "SKU",       sort: "sku" },
  { key: "barcode",      label: "Barcode" },
  { key: "unitName",     label: "Unit" },
  { key: "mrp",          label: "MRP",       sort: "mrpPrice", num: true },
  { key: "tpPrice",      label: "TP Price",  sort: "tpPrice",  num: true },
  { key: "buyPrice",     label: "Buy Price", sort: "buyPrice", num: true },
  { key: "stockStatus",  label: "Stock" },
  { key: "flags",        label: "Tracking" },
  { key: "status",       label: "Status",    sort: "status" },
  { key: "actions",      label: "",          always: true },
];
const DEFAULT_COLS = new Set([
  "image", "productCode", "productName", "productType", "genericName", "companyName",
  "sku", "unitName", "mrp", "tpPrice", "buyPrice", "stockStatus", "status", "actions",
]);

/* ═══════════════════════════════════════════════════════════════════════════
   SHARED BITS
   ═══════════════════════════════════════════════════════════════════════════ */

const Spinner = ({ label = "Loading…" }) => (
  <div className="empty-state">
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" style={{ animation: "spin 1s linear infinite" }}>
      <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
    </svg>
    <div style={{ marginTop: 8, fontSize: 13 }}>{label}</div>
  </div>
);

/** Product thumbnail with a coloured-initials fallback. */
const Thumb = ({ product, size = 34, radius = 8 }) => {
  const url = resolveImageUrl(product?.productImage);
  return (
    <div style={{
      width: size, height: size, borderRadius: radius, overflow: "hidden", flexShrink: 0,
      background: product?.imgColor || "var(--teal)",
      display: "flex", alignItems: "center", justifyContent: "center",
    }}>
      {url
        ? <img src={url} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
        : <span className="mono" style={{ color: "#fff", fontWeight: 700, fontSize: size * 0.33 }}>
            {(product?.productName || "?").slice(0, 2).toUpperCase()}
          </span>}
    </div>
  );
};

/** Read-only display for a value the server owns. Not an input — nothing submits. */
const Derived = ({ label, value, hint, locked }) => (
  <div className="field">
    <label style={{ display: "flex", alignItems: "center", gap: 5 }}>
      {label}
      <span title={locked ? "Generated by the server" : "Calculated by the server"}
        style={{ color: "var(--ink-soft)", display: "inline-flex" }}>
        <Ic path={locked ? P.lock : P.calc} size={11} />
      </span>
    </label>
    <div className="mono" style={{
      padding: "9px 12px", borderRadius: 9, fontSize: 13.5, fontWeight: 700,
      background: locked ? "var(--paper-deep)" : "var(--teal-soft)",
      border: "1px dashed var(--line)", color: locked ? "var(--ink-soft)" : "var(--teal)",
      minHeight: 38, display: "flex", alignItems: "center",
    }}>{value ?? "—"}</div>
    {hint && <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>{hint}</span>}
  </div>
);

/** Section divider inside the form — mirrors `.card-eyebrow` styling. */
const Section = ({ n, title, sub, icon }) => (
  <div style={{
    display: "flex", alignItems: "center", gap: 11,
    margin: "26px 0 14px", paddingBottom: 10, borderBottom: "1px solid var(--line)",
  }}>
    <span style={{
      width: 28, height: 28, borderRadius: 8, flexShrink: 0,
      background: "var(--amber-soft)", color: "var(--amber-deep)",
      display: "inline-flex", alignItems: "center", justifyContent: "center",
    }}><Ic path={icon} size={14} /></span>
    <div>
      <div className="card-eyebrow" style={{ marginBottom: 2 }}>Step {n}</div>
      <div style={{ fontSize: 14, fontWeight: 700, lineHeight: 1.2 }}>{title}</div>
    </div>
    {sub && <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginLeft: "auto", textAlign: "right", maxWidth: 380 }}>{sub}</div>}
  </div>
);

const Req = () => <span style={{ color: "var(--clay)", marginLeft: 2 }} aria-hidden="true">*</span>;

const Grid = ({ min = 200, children }) => (
  <div style={{ display: "grid", gap: "0 16px", gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))` }}>
    {children}
  </div>
);

const Toggle = ({ label, hint, checked, onChange }) => (
  <label style={{
    display: "flex", gap: 11, alignItems: "flex-start", padding: "12px 14px",
    border: `1.5px solid ${checked ? "var(--teal)" : "var(--line)"}`, borderRadius: 10,
    cursor: "pointer", background: checked ? "var(--teal-soft)" : "var(--paper)",
    transition: "all .12s", marginBottom: 14,
  }}>
    <input type="checkbox" checked={!!checked} onChange={(e) => onChange(e.target.checked)}
      style={{ accentColor: "var(--teal)", width: 16, height: 16, marginTop: 1 }} />
    <div>
      <div style={{ fontSize: 13, fontWeight: 700 }}>{label}</div>
      {hint && <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 2, lineHeight: 1.45 }}>{hint}</div>}
    </div>
  </label>
);

/* ═══════════════════════════════════════════════════════════════════════════
   PAGE
   ═══════════════════════════════════════════════════════════════════════════ */

export default function ProductMaster() {
  const { addToast } = useApp();
  const perm = usePermission("productmaster");

  const [view, setView] = useState("list");
  const [editingId, setEditingId] = useState(null);
  const [detailId, setDetailId] = useState(null);
  const [lookups, setLookups] = useState(null);
  const [lookupError, setLookupError] = useState(null);

  useEffect(() => {
    let dead = false;
    getFormLookups()
      .then((d) => { if (!dead) setLookups(d); })
      .catch((e) => { if (!dead) setLookupError(e.message || "Could not load dropdown data."); });
    return () => { dead = true; };
  }, []);

  if (!perm.canView) {
    return (
      <div className="page">
        <div className="page-head"><div><h1>Product Master</h1></div></div>
        <Card><div className="empty-state">You do not have permission to view Product Master.</div></Card>
      </div>
    );
  }

  const back = () => { setView("list"); setEditingId(null); setDetailId(null); };

  return (
    <div className="page">
      {lookupError && (
        <div style={{
          padding: "12px 16px", background: "var(--clay-soft)", border: "1px solid var(--clay)",
          borderRadius: "var(--radius-sm)", fontSize: 13, marginBottom: 16, color: "var(--clay)",
          display: "flex", alignItems: "center", gap: 9,
        }}>
          <Ic path={P.alert} size={16} />
          <span><b>Dropdown data unavailable:</b> {lookupError}</span>
        </div>
      )}

      {view === "list" && (
        <ProductList
          perm={perm} lookups={lookups} addToast={addToast}
          onCreate={() => { setEditingId(null); setView("form"); }}
          onEdit={(id) => { setEditingId(id); setView("form"); }}
          onView={(id) => { setDetailId(id); setView("detail"); }}
        />
      )}

      {view === "form" && (
        <ProductForm
          productId={editingId} lookups={lookups} perm={perm} addToast={addToast}
          onDone={back} onCancel={back}
        />
      )}

      {view === "detail" && (
        <ProductDetail
          productId={detailId} perm={perm}
          onEdit={() => { setEditingId(detailId); setView("form"); }}
          onBack={back}
        />
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   LIST
   ═══════════════════════════════════════════════════════════════════════════ */

const EMPTY_FILTERS = {
  search: "", sku: "", barcode: "",
  genericId: "", companyId: "", categoryId: "", unitId: "",
  status: "ALL", stockStatus: "ALL",
};

const STOCK_CHIPS = [
  { key: "ALL", label: "All products", tone: "var(--teal)" },
  { key: "OK",  label: "In stock",     tone: "var(--sage)" },
  { key: "LOW", label: "Low stock",    tone: "var(--amber)" },
  { key: "OUT", label: "Out of stock", tone: "var(--clay)" },
];

function ProductList({ perm, lookups, addToast, onCreate, onEdit, onView }) {
  const { can } = useApp();
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [searchInput, setSearchInput] = useState("");
  const [showFilters, setShowFilters] = useState(false);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sortBy, setSortBy] = useState("productName");
  const [sortDir, setSortDir] = useState("asc");

  const [cols, setCols] = useState(DEFAULT_COLS);
  const [showCols, setShowCols] = useState(false);
  const [confirm, setConfirm] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const [importOpen, setImportOpen] = useState(false);

  /* Debounce free-text so typing does not fire a request per keystroke. */
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
      const res = await getProducts({ ...filters, page, size, sortBy, sortDir });
      setRows(res.items); setTotal(res.total); setTotalPages(res.totalPages);
    } catch (e) {
      setError(e.message || "Could not load products."); setRows([]); setTotal(0);
    } finally { setLoading(false); }
  }, [filters, page, size, sortBy, sortDir]);

  useEffect(() => { load(); }, [load]);

  const setFilter = (k, v) => { setFilters((f) => ({ ...f, [k]: v })); setPage(0); };
  const clearFilters = () => { setFilters(EMPTY_FILTERS); setSearchInput(""); setPage(0); };

  const toggleSort = (field) => {
    if (!field) return;
    if (sortBy === field) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else { setSortBy(field); setSortDir("asc"); }
    setPage(0);
  };

  const activeFilters = useMemo(() => {
    const f = filters;
    return [f.sku, f.barcode, f.genericId, f.companyId, f.categoryId, f.unitId]
      .filter(Boolean).length + (f.status !== "ALL" ? 1 : 0);
  }, [filters]);

  const visible = ALL_COLUMNS.filter((c) => c.always || cols.has(c.key));

  /* ── row actions ── */
  const doDelete = async (p) => {
    setBusyId(p.id);
    try {
      await deleteProduct(p.id);
      addToast("Product deleted successfully.");
      setConfirm(null);
      if (rows.length === 1 && page > 0) setPage((x) => x - 1); else load();
    } catch (e) {
      if (e.errorCode === "PRODUCT_HAS_TRANSACTIONS") {
        setConfirm({ kind: "blocked", product: p, reason: e.message });
      } else { addToast(e.message || "Could not delete the product.", "error"); setConfirm(null); }
    } finally { setBusyId(null); }
  };

  const doStatus = async (p, next) => {
    setBusyId(p.id);
    try {
      await setProductStatus(p.id, next);
      addToast(next === "INACTIVE" ? "Product deactivated successfully." : "Product activated successfully.");
      setConfirm(null); load();
    } catch (e) { addToast(e.message || "Could not change the product status.", "error"); }
    finally { setBusyId(null); }
  };

  const exportExcel = async () => {
    try {
      // Export what the filters currently select, not just the visible page.
      const all = await getProducts({ ...filters, page: 0, size: 200, sortBy, sortDir });
      await downloadProductsExcel(all.items);
      addToast(`Exported ${all.items.length} product${all.items.length === 1 ? "" : "s"} to Excel.`);
    } catch (e) { addToast(e.message || "Export failed.", "error"); }
  };

  /**
   * The Excel helpers load SheetJS on demand, so they are async and can reject
   * (a slow network, a blocked chunk). Without this the failure would surface
   * only as an unhandled rejection in the console.
   */
  const getTemplate = async () => {
    try { await downloadTemplate(lookups); addToast("Template downloaded."); }
    catch (e) { addToast(e.message || "Could not build the template.", "error"); }
  };

  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, total);

  /* ── KPI strip ──
     Counts cover the whole catalogue, not just the page on screen. Each is a
     size=1 request read for its totalElements, which is far cheaper than
     pulling every row client-side just to count it. They deliberately ignore
     the stock-status chip so the numbers stay stable while you filter by it. */
  const [counts, setCounts] = useState({ low: null, out: null, rx: null });

  useEffect(() => {
    let dead = false;
    const base = { ...filters, stockStatus: "ALL", page: 0, size: 1 };
    Promise.all([
      getProducts({ ...base, stockStatus: "LOW" }),
      getProducts({ ...base, stockStatus: "OUT" }),
      getProducts({ ...base, prescriptionRequired: true }),
    ])
      .then(([low, out, rx]) => {
        if (!dead) setCounts({ low: low.total, out: out.total, rx: rx.total });
      })
      .catch(() => { if (!dead) setCounts({ low: null, out: null, rx: null }); });
    return () => { dead = true; };
  }, [filters]);

  const kpis = useMemo(() => [
    { label: "Products", value: total, tone: "teal", icon: P.layers },
    { label: "Low stock", value: counts.low, tone: "amber", icon: P.warn },
    { label: "Out of stock", value: counts.out, tone: "clay", icon: P.alert },
    { label: "Prescription-only", value: counts.rx, tone: "sage", icon: P.rx },
  ], [total, counts]);

  return (
    <>
      {/* ── Header ── */}
      <div className="page-head">
        <div>
          <h1>Product Master</h1>
          <div className="page-sub">
            The pharmaceutical catalogue — pricing, stock levels, batch and expiry
            tracking. Trade price, IGTA and buy price are calculated by the server.
          </div>
        </div>
        <div className="flex-gap" style={{ flexWrap: "wrap" }}>
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" onClick={exportExcel}>
              <Ic path={P.download} size={13} /> Export Excel
            </button>
          )}
          {perm.canCreate && (
            <button className="btn btn-ghost btn-sm" onClick={getTemplate} disabled={!lookups}>
              <Ic path={P.sheet} size={13} /> Template
            </button>
          )}
          {perm.canCreate && (
            <button className="btn btn-teal btn-sm" onClick={() => setImportOpen(true)}>
              <Ic path={P.upload} size={13} /> Import Excel
            </button>
          )}
          {/* The catalogue screen is deliberately off the menu, so this is how
              it is reached. Shown only to someone who holds it and may create
              products — importing does both, and a button that leads to a
              refusal is worse than no button. */}
          {perm.canCreate && can(CAP_BRANCH_CATALOGUE) && (
            <button className="btn btn-teal btn-sm"
                    onClick={() => {
                      /* pushState alone does not tell the router anything — popstate only
                         fires for the back button. The event says "the URL moved". */
                      window.history.pushState(null, "", "/branchcatalogue");
                      window.dispatchEvent(new PopStateEvent("popstate"));
                    }}
                    title="Copy medicines from the shared catalogue into your product list">
              <Ic path={P.sheet} size={13} /> From catalogue
            </button>
          )}
          {perm.canCreate && (
            <button className="btn btn-primary" onClick={onCreate}>
              <Ic path={P.plus} size={14} /> New Product
            </button>
          )}
        </div>
      </div>

      {/* ── KPI strip ── */}
      <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(168px, 1fr))" }}>
        {kpis.map((k) => (
          <div className="kpi-card" key={k.label} style={{ padding: "14px 16px" }}>
            <div className="kpi-top" style={{ marginBottom: 8 }}>
              <div className="kpi-icon" style={{ width: 30, height: 30, background: `var(--${k.tone}-soft)`, color: `var(--${k.tone})` }}>
                <Ic path={k.icon} size={15} />
              </div>
            </div>
            <div className="kpi-value" style={{ fontSize: 21 }}>
              {k.value == null ? <span style={{ color: "var(--ink-soft)" }}>—</span> : k.value}
            </div>
            <div className="kpi-label" style={{ marginTop: 4 }}>{k.label}</div>
          </div>
        ))}
      </div>

      {/* ── Stock chips ── */}
      <div style={{ display: "flex", gap: 7, flexWrap: "wrap", marginBottom: 16 }}>
        {STOCK_CHIPS.map((c) => {
          const active = filters.stockStatus === c.key;
          return (
            <button key={c.key} onClick={() => setFilter("stockStatus", c.key)}
              style={{
                padding: "6px 14px", borderRadius: 999, fontSize: 12.5, fontWeight: 600,
                cursor: "pointer", border: active ? "none" : "1.5px solid var(--line)",
                background: active ? c.tone : "var(--card)", color: active ? "#fff" : "var(--ink)",
                transition: "all .12s",
              }}>
              {c.label}
            </button>
          );
        })}
        <div style={{ marginLeft: "auto", display: "flex", gap: 7 }}>
          <button className="btn btn-ghost btn-sm" onClick={() => setShowCols(true)}>Columns</button>
          <button className={`btn btn-sm ${activeFilters ? "btn-primary" : "btn-ghost"}`}
            onClick={() => setShowFilters((s) => !s)}>
            Filters{activeFilters ? ` (${activeFilters})` : ""}
          </button>
        </div>
      </div>

      {/* ── Filter bar ── */}
      <div style={{
        display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end", marginBottom: 16,
        padding: "12px 16px", background: "var(--card)", borderRadius: "var(--radius)",
        border: "1px solid var(--line)",
      }}>
        <div className="search-box" style={{ flex: 2, minWidth: 240, maxWidth: 380 }}>
          <Ic path={P.search} size={14} />
          <input placeholder="Search name, code, SKU or barcode…" value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)} aria-label="Search products"
            style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1, color: "var(--ink)" }} />
          {searchInput && (
            <button onClick={() => setSearchInput("")} aria-label="Clear search"
              style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>
          )}
        </div>

        <FilterSelect label="Status" value={filters.status} onChange={(v) => setFilter("status", v)}
          options={[["ALL", "All"], ["ACTIVE", "Active"], ["INACTIVE", "Inactive"]]} />

        {showFilters && (
          <>
            <FilterSelect label="Generic" value={filters.genericId} onChange={(v) => setFilter("genericId", v)}
              options={[["", "All generics"], ...(lookups?.generics ?? []).map((g) => [g.id, g.name])]} />
            <FilterSelect label="Company" value={filters.companyId} onChange={(v) => setFilter("companyId", v)}
              options={[["", "All companies"], ...(lookups?.companies ?? []).map((c) => [c.id, c.name])]} />
            <FilterSelect label="Category" value={filters.categoryId} onChange={(v) => setFilter("categoryId", v)}
              options={[["", "All categories"], ...(lookups?.categories ?? []).map((c) => [c.id, c.name])]} />
            <FilterSelect label="Unit" value={filters.unitId} onChange={(v) => setFilter("unitId", v)}
              options={[["", "All units"], ...(lookups?.units ?? []).map((u) => [u.id, u.name])]} />
            <div>
              <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>SKU</div>
              <input value={filters.sku} onChange={(e) => setFilter("sku", e.target.value)} placeholder="Exact SKU"
                style={inputStyle} />
            </div>
            <div>
              <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>Barcode</div>
              <input value={filters.barcode} onChange={(e) => setFilter("barcode", e.target.value)} placeholder="Scan or type"
                style={inputStyle} />
            </div>
          </>
        )}

        {(activeFilters > 0 || filters.search) && (
          <button className="btn btn-ghost btn-sm" onClick={clearFilters} style={{ marginLeft: "auto" }}>
            Clear all
          </button>
        )}
      </div>

      {/* ── Grid ── */}
      <Card pad={false}>
        <div style={{ padding: "14px 18px 0" }}>
          <div className="card-head">
            <div>
              <div className="card-eyebrow">Master Data › Product</div>
              <div className="card-title">Catalogue</div>
            </div>
            <Pill tone="teal">{total} product{total === 1 ? "" : "s"}</Pill>
          </div>
        </div>

        {loading ? <Spinner label="Loading products…" />
          : error ? (
            <div className="empty-state">
              <div style={{ color: "var(--clay)", marginBottom: 10 }}>{error}</div>
              <button className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
            </div>
          ) : rows.length === 0 ? (
            <div className="empty-state">
              {filters.search || activeFilters ? (
                <>
                  <div>No products match your search.</div>
                  <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={clearFilters}>Clear filters</button>
                </>
              ) : (
                <>
                  <div style={{ marginBottom: 4 }}>No products yet.</div>
                  <div style={{ fontSize: 12.5, marginBottom: 12 }}>
                    Add one at a time, or import a whole catalogue from Excel.
                  </div>
                  {perm.canCreate && (
                    <div className="flex-gap" style={{ justifyContent: "center" }}>
                      <button className="btn btn-primary btn-sm" onClick={onCreate}>
                        <Ic path={P.plus} size={13} /> Add product
                      </button>
                      <button className="btn btn-ghost btn-sm" onClick={getTemplate} disabled={!lookups}>
                        <Ic path={P.sheet} size={13} /> Download template
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          ) : (
            <div className="table-wrap" style={{ padding: "0 18px" }}>
              <table className="data-table">
                <thead>
                  <tr>
                    {visible.map((c) => (
                      <th key={c.key} onClick={() => toggleSort(c.sort)}
                        style={{ cursor: c.sort ? "pointer" : "default", textAlign: c.num ? "right" : "left" }}
                        aria-sort={c.sort && sortBy === c.sort ? (sortDir === "asc" ? "ascending" : "descending") : undefined}>
                        {c.label}
                        {c.sort && sortBy === c.sort && (
                          <span style={{ marginLeft: 4, opacity: .75 }}>{sortDir === "asc" ? "▲" : "▼"}</span>
                        )}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((p) => (
                    <tr key={p.id} style={{ opacity: p.status === "INACTIVE" ? 0.55 : 1 }}>
                      {visible.map((c) => (
                        <td key={c.key} style={{ textAlign: c.num ? "right" : "left" }}>
                          <Cell col={c.key} p={p} perm={perm} busyId={busyId}
                            onView={onView} onEdit={onEdit} setConfirm={setConfirm} />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

        {/* ── Pagination ── */}
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

      {/* ── Modals ── */}
      {showCols && (
        <Modal title="Visible columns" onClose={() => setShowCols(false)} width={420}
          footer={<button className="btn btn-primary" onClick={() => setShowCols(false)}>Done</button>}>
          <div style={{ display: "grid", gap: 9 }}>
            {ALL_COLUMNS.filter((c) => c.label).map((c) => (
              <label key={c.key} style={{
                display: "flex", gap: 9, alignItems: "center", fontSize: 13,
                opacity: c.always ? .5 : 1, cursor: c.always ? "not-allowed" : "pointer",
              }}>
                <input type="checkbox" checked={c.always || cols.has(c.key)} disabled={c.always}
                  style={{ accentColor: "var(--amber)" }}
                  onChange={() => setCols((prev) => {
                    const n = new Set(prev);
                    n.has(c.key) ? n.delete(c.key) : n.add(c.key);
                    return n;
                  })} />
                {c.label}
                {c.always && <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>always shown</span>}
              </label>
            ))}
          </div>
        </Modal>
      )}

      {importOpen && (
        <ImportModal lookups={lookups} addToast={addToast}
          onClose={() => setImportOpen(false)}
          onDone={() => { setImportOpen(false); load(); }} />
      )}

      {confirm?.kind === "delete" && (
        <Modal title="Delete product?" onClose={() => setConfirm(null)} width={470}
          footer={<>
            <button className="btn btn-ghost" onClick={() => setConfirm(null)}>Cancel</button>
            <button className="btn btn-danger" disabled={busyId === confirm.product.id}
              onClick={() => doDelete(confirm.product)}>
              {busyId === confirm.product.id ? "Deleting…" : "Delete permanently"}
            </button>
          </>}>
          <p style={{ fontSize: 13.5, lineHeight: 1.6 }}>
            <b>{confirm.product.productName}</b> (<span className="mono">{confirm.product.productCode}</span>)
            will be permanently removed, along with its image. This cannot be undone.
          </p>
        </Modal>
      )}

      {confirm?.kind === "blocked" && (
        <Modal title="This product cannot be deleted" onClose={() => setConfirm(null)} width={470}
          footer={<>
            <button className="btn btn-ghost" onClick={() => setConfirm(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busyId === confirm.product.id}
              onClick={() => doStatus(confirm.product, "INACTIVE")}>
              {busyId === confirm.product.id ? "Deactivating…" : "Deactivate instead"}
            </button>
          </>}>
          <p style={{ fontSize: 13.5, lineHeight: 1.6 }}>{confirm.reason}</p>
          <p style={{ fontSize: 12.5, color: "var(--ink-soft)", marginTop: 10 }}>
            Deactivating hides the product from sales and purchase screens while keeping its history intact.
          </p>
        </Modal>
      )}

      {confirm?.kind === "status" && (
        <Modal title={confirm.next === "INACTIVE" ? "Deactivate product?" : "Activate product?"}
          onClose={() => setConfirm(null)} width={440}
          footer={<>
            <button className="btn btn-ghost" onClick={() => setConfirm(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busyId === confirm.product.id}
              onClick={() => doStatus(confirm.product, confirm.next)}>
              {busyId === confirm.product.id ? "Saving…" : "Confirm"}
            </button>
          </>}>
          <p style={{ fontSize: 13.5, lineHeight: 1.6 }}>
            {confirm.next === "INACTIVE"
              ? <><b>{confirm.product.productName}</b> will stop appearing in sales and purchase screens. Its history is kept.</>
              : <><b>{confirm.product.productName}</b> will become available for sales and purchases again.</>}
          </p>
        </Modal>
      )}
    </>
  );
}

const inputStyle = {
  padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)",
  fontSize: 13, background: "var(--paper)", color: "var(--ink)",
};

const FilterSelect = ({ label, value, onChange, options }) => (
  <div>
    <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>{label}</div>
    <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
      {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
    </select>
  </div>
);

/** One grid cell. */
function Cell({ col, p, perm, busyId, onView, onEdit, setConfirm }) {
  switch (col) {
    case "image": return <Thumb product={p} />;
    case "productCode": return <span className="mono" style={{ fontSize: 11.5 }}>{p.productCode || "—"}</span>;
    case "productName": return (
      <div>
        <div style={{ fontWeight: 700 }}>{p.productName}</div>
        <div style={{ fontSize: 11, color: "var(--ink-soft)", display: "flex", alignItems: "center", gap: 5 }}>
          {p.genericName || "—"}
          {p.prescriptionRequired && (
            <span title="Prescription required" style={{ color: "var(--clay)", display: "inline-flex" }}>
              <Ic path={P.rx} size={11} />
            </span>
          )}
        </div>
      </div>
    );
    case "mrp":      return <span className="mono">{money(p.mrp)}</span>;
    case "tpPrice":  return <span className="mono">{money(p.tpPrice)}</span>;
    case "buyPrice": return <span className="mono" style={{ fontWeight: 700 }}>{money(p.buyPrice)}</span>;
    case "productType": {
      const general = p.productType === "GENERAL";
      return (
        <span className={`type-pill ${general ? "type-pill-general" : "type-pill-pharma"}`}>
          {general ? "General" : "Pharma"}
        </span>
      );
    }
    case "sku":      return <span className="mono" style={{ fontSize: 12 }}>{p.sku || "—"}</span>;
    case "barcode":  return <span className="mono" style={{ fontSize: 11.5 }}>{p.barcode || "—"}</span>;
    case "stockStatus": return (
      <div style={{ display: "flex", flexDirection: "column", gap: 3, alignItems: "flex-start" }}>
        <Pill tone={stockTone(p.stockStatus)}>{p.stockStatus}</Pill>
        <span className="mono" style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
          {Number(p.currentStock ?? 0)} on hand
        </span>
      </div>
    );
    case "flags": {
      const f = [];
      if (p.batchTracking) f.push(<Pill key="b" tone="teal">Batch</Pill>);
      if (p.expiryTracking) f.push(<Pill key="e" tone="amber">Expiry</Pill>);
      if (p.prescriptionRequired) f.push(<Pill key="r" tone="clay">Rx</Pill>);
      return f.length ? <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>{f}</div> : "—";
    }
    case "status": return <Pill tone={p.status === "ACTIVE" ? "sage" : "clay"}>{p.status}</Pill>;
    case "actions": return (
      <div style={{ display: "flex", gap: 4, justifyContent: "flex-end" }}>
        <button className="icon-btn" title="View" onClick={() => onView(p.id)}><Ic path={P.eye} size={14} /></button>
        {perm.canEdit && <button className="icon-btn" title="Edit" onClick={() => onEdit(p.id)}><Ic path={P.edit} size={14} /></button>}
        {perm.canEdit && (
          <button className="icon-btn" title={p.status === "ACTIVE" ? "Deactivate" : "Activate"} disabled={busyId === p.id}
            onClick={() => setConfirm({ kind: "status", product: p, next: p.status === "ACTIVE" ? "INACTIVE" : "ACTIVE" })}>
            <Ic path={P.power} size={14} />
          </button>
        )}
        {/* Delete appears only when the server says the row has no history. */}
        {perm.canDelete && p.deletable && (
          <button className="icon-btn" title="Delete" disabled={busyId === p.id}
            onClick={() => setConfirm({ kind: "delete", product: p })}><Ic path={P.trash} size={14} /></button>
        )}
      </div>
    );
    default: return p[col] ?? "—";
  }
}

/* ═══════════════════════════════════════════════════════════════════════════
   EXCEL IMPORT
   ═══════════════════════════════════════════════════════════════════════════ */

function ImportModal({ lookups, addToast, onClose, onDone }) {
  const [step, setStep] = useState("pick");      // pick | preview | running | result
  const [file, setFile] = useState(null);
  const [parsed, setParsed] = useState(null);
  const [parseError, setParseError] = useState(null);
  const [mode, setMode] = useState("CREATE");
  const [result, setResult] = useState(null);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef(null);

  /* Local wrappers — the Excel helpers lazy-load SheetJS and can reject. */
  const getTemplate = async () => {
    try { await downloadTemplate(lookups); addToast("Template downloaded."); }
    catch (e) { addToast(e.message || "Could not build the template.", "error"); }
  };
  const saveResult = async () => {
    try { await downloadImportResult(result); }
    catch (e) { addToast(e.message || "Could not build the result file.", "error"); }
  };

  const handleFile = async (f) => {
    if (!f) return;
    setParseError(null); setFile(f);
    try {
      const data = await parseWorkbook(f);
      setParsed(data);
      setStep("preview");
    } catch (e) {
      setParseError(e.message || "Could not read that file.");
      setParsed(null);
      setStep("pick");
    }
  };

  const run = async () => {
    setStep("running");
    try {
      const res = await bulkImportProducts(parsed.rows, mode);
      setResult(res);
      setStep("result");
      if (res.failed === 0) addToast(`Imported ${res.created + res.updated} product${res.created + res.updated === 1 ? "" : "s"}.`);
      else addToast(`${res.failed} row${res.failed === 1 ? "" : "s"} could not be imported.`, "warning");
    } catch (e) {
      setParseError(e.message || "The import failed.");
      setStep("preview");
    }
  };

  const close = () => {
    // Anything imported is already saved, so refresh the list on the way out.
    if (result && (result.created || result.updated)) onDone(); else onClose();
  };

  const footer = {
    pick: <button className="btn btn-ghost" onClick={onClose}>Cancel</button>,
    preview: (
      <>
        <button className="btn btn-ghost" onClick={() => { setStep("pick"); setParsed(null); setFile(null); }}>
          Choose another file
        </button>
        <button className="btn btn-primary" onClick={run} disabled={!parsed?.rows?.length}>
          <Ic path={P.upload} size={14} /> Import {parsed?.rows?.length ?? 0} row{parsed?.rows?.length === 1 ? "" : "s"}
        </button>
      </>
    ),
    running: <button className="btn btn-ghost" disabled>Importing…</button>,
    result: (
      <>
        <button className="btn btn-ghost" onClick={saveResult}>
          <Ic path={P.download} size={14} /> Download result
        </button>
        <button className="btn btn-primary" onClick={close}>Done</button>
      </>
    ),
  }[step];

  return (
    <Modal title="Import products from Excel" onClose={close} width={780} footer={footer}>
      {/* ── Step: pick ── */}
      {step === "pick" && (
        <>
          <div style={{
            background: "var(--paper)", border: "1px solid var(--line)", borderRadius: "var(--radius-sm)",
            padding: "13px 15px", marginBottom: 16, fontSize: 12.5, lineHeight: 1.6, color: "var(--ink-soft)",
          }}>
            <div style={{ fontWeight: 700, color: "var(--ink)", marginBottom: 4 }}>Before you start</div>
            Download the template — it lists your organisation's exact Generic, Company,
            Category, Unit and Box names on a Reference sheet, so the values you type will match.
            Trade price and product code are <b>not</b> in the template; the server calculates them.
            IGTA and Buy Price are optional — leave them blank to have them calculated, or type
            your own and they are stored as typed.
            <div style={{ marginTop: 10 }}>
              <button className="btn btn-ghost btn-sm" onClick={getTemplate} disabled={!lookups}>
                <Ic path={P.sheet} size={13} /> Download template (.xlsx)
              </button>
            </div>
          </div>

          <div
            onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => { e.preventDefault(); setDragging(false); handleFile(e.dataTransfer.files?.[0]); }}
            onClick={() => inputRef.current?.click()}
            style={{
              border: `2px dashed ${dragging ? "var(--amber)" : "var(--line)"}`,
              background: dragging ? "var(--amber-soft)" : "var(--paper)",
              borderRadius: "var(--radius)", padding: "34px 20px", textAlign: "center",
              cursor: "pointer", transition: "all .12s",
            }}>
            <div style={{ color: "var(--ink-soft)", marginBottom: 10 }}><Ic path={P.upload} size={26} /></div>
            <div style={{ fontSize: 14, fontWeight: 700, marginBottom: 4 }}>
              Drop your .xlsx file here, or click to browse
            </div>
            <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>
              .xlsx, .xls or .csv · up to 2000 rows
            </div>
            <input ref={inputRef} type="file" accept=".xlsx,.xls,.csv" style={{ display: "none" }}
              onChange={(e) => handleFile(e.target.files?.[0])} />
          </div>

          {parseError && (
            <div style={{
              marginTop: 14, padding: "11px 14px", background: "var(--clay-soft)",
              border: "1px solid var(--clay)", borderRadius: "var(--radius-sm)",
              fontSize: 12.5, color: "var(--clay)", display: "flex", gap: 9,
            }}>
              <Ic path={P.alert} size={15} /><span>{parseError}</span>
            </div>
          )}
        </>
      )}

      {/* ── Step: preview ── */}
      {step === "preview" && parsed && (
        <>
          <div className="flex-between" style={{ marginBottom: 14, flexWrap: "wrap", gap: 10 }}>
            <div>
              <div style={{ fontSize: 13.5, fontWeight: 700 }}>{file?.name}</div>
              <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>
                Sheet “{parsed.sheetName}” · <span className="mono">{parsed.rows.length}</span> data row{parsed.rows.length === 1 ? "" : "s"}
              </div>
            </div>
            <div>
              <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>If the SKU already exists</div>
              <select value={mode} onChange={(e) => setMode(e.target.value)} style={inputStyle}>
                <option value="CREATE">Report it as an error</option>
                <option value="UPSERT">Update the existing product</option>
              </select>
            </div>
          </div>

          {parsed.warnings?.length > 0 && (
            <div style={{
              padding: "10px 13px", background: "var(--amber-soft)", border: "1px solid var(--amber)",
              borderRadius: "var(--radius-sm)", fontSize: 12, color: "var(--amber-deep)", marginBottom: 14,
            }}>
              {parsed.warnings.map((w, i) => <div key={i} style={{ marginBottom: i < parsed.warnings.length - 1 ? 4 : 0 }}>{w}</div>)}
            </div>
          )}

          <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginBottom: 6 }}>
            Preview of the first {Math.min(8, parsed.rows.length)} row{parsed.rows.length === 1 ? "" : "s"} —
            every row is validated by the server on import.
          </div>
          <div className="table-wrap" style={{ maxHeight: 250, overflowY: "auto" }}>
            <table className="data-table" style={{ fontSize: 12 }}>
              <thead>
                <tr><th>Row</th><th>Product</th><th>SKU</th><th>Generic</th><th>Company</th><th style={{ textAlign: "right" }}>MRP</th></tr>
              </thead>
              <tbody>
                {parsed.rows.slice(0, 8).map((r) => (
                  <tr key={r.rowNumber}>
                    <td className="mono">{r.rowNumber}</td>
                    <td style={{ fontWeight: 600 }}>{r.productName || <span style={{ color: "var(--clay)" }}>missing</span>}</td>
                    <td className="mono">{r.sku || <span style={{ color: "var(--clay)" }}>missing</span>}</td>
                    <td>{r.genericName || "—"}</td>
                    <td>{r.companyName || "—"}</td>
                    <td className="mono" style={{ textAlign: "right" }}>{r.mrp ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {parseError && (
            <div style={{
              marginTop: 14, padding: "11px 14px", background: "var(--clay-soft)",
              border: "1px solid var(--clay)", borderRadius: "var(--radius-sm)",
              fontSize: 12.5, color: "var(--clay)",
            }}>{parseError}</div>
          )}
        </>
      )}

      {/* ── Step: running ── */}
      {step === "running" && <Spinner label={`Importing ${parsed?.rows?.length ?? 0} rows…`} />}

      {/* ── Step: result ── */}
      {step === "result" && result && (
        <>
          <div className="kpi-grid" style={{ marginBottom: 16, gridTemplateColumns: "repeat(4, 1fr)" }}>
            {[
              { label: "Rows", value: result.totalRows, tone: "teal" },
              { label: "Created", value: result.created, tone: "sage" },
              { label: "Updated", value: result.updated, tone: "amber" },
              { label: "Failed", value: result.failed, tone: "clay" },
            ].map((k) => (
              <div className="kpi-card" key={k.label} style={{ padding: "12px 14px" }}>
                <div className="kpi-value" style={{ fontSize: 20, color: `var(--${k.tone})` }}>{k.value}</div>
                <div className="kpi-label">{k.label}</div>
              </div>
            ))}
          </div>

          {result.failed > 0 && (
            <>
              <div style={{ fontSize: 12.5, fontWeight: 700, marginBottom: 6 }}>
                Rows that could not be imported
              </div>
              <div className="table-wrap" style={{ maxHeight: 260, overflowY: "auto" }}>
                <table className="data-table" style={{ fontSize: 12 }}>
                  <thead><tr><th>Row</th><th>SKU</th><th>Reason</th></tr></thead>
                  <tbody>
                    {result.results.filter((r) => r.status === "FAILED").map((r, i) => (
                      <tr key={i}>
                        <td className="mono">{r.rowNumber}</td>
                        <td className="mono">{r.sku || "—"}</td>
                        <td style={{ color: "var(--clay)" }}>{r.error}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 8 }}>
                The successful rows are already saved. Fix these in your file and upload again —
                re-importing the whole file is safe, since existing SKUs are detected.
              </div>
            </>
          )}

          {result.failed === 0 && (
            <div style={{
              padding: "14px 16px", background: "var(--sage-soft)", border: "1px solid var(--sage)",
              borderRadius: "var(--radius-sm)", fontSize: 13, color: "var(--sage)",
              display: "flex", alignItems: "center", gap: 10,
            }}>
              <Ic path={P.check} size={17} />
              <span>Every row imported successfully.</span>
            </div>
          )}
        </>
      )}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   FORM
   ═══════════════════════════════════════════════════════════════════════════ */

function ProductForm({ productId, lookups, perm, addToast, onDone, onCancel }) {
  const confirm = useConfirm();
  const isEdit = productId != null;

  const [values, setValues] = useState(() => toFormValues(null, lookups?.defaults));
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [nameChecking, setNameChecking] = useState(false);
  /* The last name we asked about, so leaving the field without having touched
     it does not fire the same question again on every tab through the form. */
  const nameAsked = useRef(null);
  const [dirty, setDirty] = useState(false);
  const [loadError, setLoadError] = useState(null);

  const [productCode, setProductCode] = useState(null);
  const [existingImage, setExistingImage] = useState(null);
  const [imageFile, setImageFile] = useState(null);
  const [imagePreview, setImagePreview] = useState(null);
  const [removeImage, setRemoveImage] = useState(false);
  const fileRef = useRef(null);

  useEffect(() => {
    if (!isEdit && lookups?.defaults && !dirty) setValues(toFormValues(null, lookups.defaults));
  }, [lookups, isEdit]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!isEdit) return;
    let dead = false;
    setLoading(true);
    getProduct(productId)
      .then((p) => {
        if (dead) return;
        setValues(toFormValues(p, lookups?.defaults));
        setProductCode(p.productCode);
        setExistingImage(p.productImage);
        setLoading(false);
      })
      .catch((e) => { if (!dead) { setLoadError(e.message || "Could not load the product."); setLoading(false); } });
    return () => { dead = true; };
  }, [productId, isEdit]); // eslint-disable-line react-hooks/exhaustive-deps

  /* Warn before a browser navigation discards unsaved edits. */
  useEffect(() => {
    if (!dirty) return undefined;
    const h = (e) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, [dirty]);

  useEffect(() => () => { if (imagePreview) URL.revokeObjectURL(imagePreview); }, [imagePreview]);

  const set = (k, v) => {
    setValues((s) => ({ ...s, [k]: v }));
    setDirty(true);
    setErrors((e) => (e[k] ? { ...e, [k]: undefined } : e));
    // Editing the name makes any previous verdict about it stale, so the next
    // blur asks again rather than trusting what it was told about older text.
    if (k === "productName") nameAsked.current = null;
  };

  /**
   * Checks the product name as the cursor leaves the field.
   *
   * <p>Trims first, so the value the server is asked about is the value that
   * will be saved. Asks once per distinct name: tabbing back and forth through a
   * finished form should not put a request on the wire each time.
   *
   * <p>The message is shown on the field and said out loud once, because the
   * field can be off screen by the time the answer lands — the cursor has moved
   * on and the form scrolls.
   */
  const checkNameOnBlur = async (raw) => {
    const name = (raw ?? "").trim();
    if (name !== raw) set("productName", name);

    if (!name || nameAsked.current === name.toLowerCase()) return;
    nameAsked.current = name.toLowerCase();

    setNameChecking(true);
    try {
      // On an edit the product must not collide with itself.
      const { available, message } = await checkProductName(name, isEdit ? productId : null);
      if (!available) {
        setErrors((e) => ({ ...e, productName: message || "That name is already taken." }));
        addToast(`${message} Give this one a name that tells the two apart.`, "warning");
      }
    } finally {
      setNameChecking(false);
    }
  };

  const prices = useMemo(
    () => calculatePrices({ mrp: values.mrp, conversionRate: values.conversionRate, vatPct: values.vatPct }),
    [values.mrp, values.conversionRate, values.vatPct]
  );

  const selectedBox = useMemo(
    () => (lookups?.boxes ?? []).find((b) => String(b.id) === String(values.boxId)) || null,
    [lookups, values.boxId]
  );

  const subCategoryOptions = useMemo(() => {
    if (!values.categoryId) return [];
    return (lookups?.subCategories ?? []).filter((s) => String(s.categoryId) === String(values.categoryId));
  }, [lookups, values.categoryId]);

  useEffect(() => {
    if (!values.subCategoryId) return;
    if (!subCategoryOptions.some((s) => String(s.id) === String(values.subCategoryId))) {
      setValues((v) => ({ ...v, subCategoryId: "" }));
    }
  }, [subCategoryOptions]); // eslint-disable-line react-hooks/exhaustive-deps

  const isGeneral = values.productType === "GENERAL";

  /* Switching to General clears any error already showing against the two fields
     it hides. Without this, a form that failed validation as Pharma keeps
     "SKU is required" on screen with no SKU box to fix it in — an error the user
     cannot act on and cannot dismiss. */
  useEffect(() => {
    if (!isGeneral) return;
    setErrors((prev) => {
      if (!prev.sku && !prev.genericId) return prev;
      const { sku, genericId, ...rest } = prev;
      return rest;
    });
  }, [isGeneral]);

  const pickImage = (e) => {
    const f = e.target.files?.[0];
    if (!f) return;
    if (!/^image\/(jpeg|png|webp|gif)$/.test(f.type)) {
      addToast("Invalid image format. Allowed: JPG, PNG, WEBP, GIF.", "error");
      e.target.value = ""; return;
    }
    if (f.size > 2 * 1024 * 1024) {
      addToast("Image size exceeds the allowed limit of 2 MB.", "error");
      e.target.value = ""; return;
    }
    if (imagePreview) URL.revokeObjectURL(imagePreview);
    setImageFile(f); setImagePreview(URL.createObjectURL(f)); setRemoveImage(false); setDirty(true);
  };

  const clearImage = () => {
    if (imagePreview) URL.revokeObjectURL(imagePreview);
    setImageFile(null); setImagePreview(null);
    if (existingImage) setRemoveImage(true);
    if (fileRef.current) fileRef.current.value = "";
    setDirty(true);
  };

  const submit = async (e) => {
    e.preventDefault();
    if (saving) return;

    const found = validateForm(values);
    const bad = Object.fromEntries(Object.entries(found).filter(([, v]) => v));
    if (Object.keys(bad).length) {
      setErrors(bad);
      addToast("Please correct the highlighted fields.", "error");
      const first = document.querySelector(`[data-field="${Object.keys(bad)[0]}"]`);
      first?.scrollIntoView({ behavior: "smooth", block: "center" });
      first?.querySelector("input,select")?.focus();
      return;
    }

    setSaving(true);
    try {
      const saved = isEdit ? await updateProduct(productId, values) : await createProduct(values);

      if (imageFile) {
        try { await uploadProductImage(saved.id, imageFile); }
        catch (imgErr) {
          addToast(`Product saved, but the image failed: ${imgErr.message}`, "warning");
          setDirty(false); onDone(); return;
        }
      } else if (removeImage && isEdit) {
        try { await deleteProductImage(saved.id); } catch { /* non-fatal */ }
      }

      /* An edit does not overwrite the product — it writes a new version and
         retires the one that was open. Said plainly, because the alternative is
         a user who finds two rows where they expected one and assumes they
         double-saved. */
      addToast(
        isEdit
          ? `Saved as version ${saved.versionNo ?? 2}. The previous version was retired and keeps `
            + "the sales, purchases and stock movements raised against it."
          : "Product created successfully.");
      setDirty(false); onDone();
    } catch (err) {
      const c = err.errorCode || "";
      if (c === "PRODUCT_NAME_EXISTS") setErrors({ productName: err.message });
      else if (c === "PRODUCT_SKU_EXISTS") setErrors({ sku: err.message });
      else if (c === "PRODUCT_BARCODE_EXISTS") setErrors({ barcode: err.message });
      else if (c === "PRODUCT_DUPLICATE") setErrors({ productName: err.message });
      else if (c === "VALIDATION_FAILED" && err.data) setErrors(err.data);
      /* Somebody else edited this product while the form was open, so what is on
         screen is a version that no longer exists to be edited. Nothing here can
         merge the two — the honest move is to say so and send them back for the
         current one, rather than offering a Save that will fail again. */
      else if (c === "PRODUCT_VERSION_SUPERSEDED") {
        addToast(err.message, "warning");
        setDirty(false); onCancel(); return;
      }
      addToast(err.message || "Could not save the product.", "error");
    } finally { setSaving(false); }
  };

  const cancel = async () => {
    if (dirty && !(await confirm({
      title: "Discard your changes?",
      body: "This product has edits that have not been saved.",
      detail: "Leaving now loses them.",
      confirmLabel: "Discard changes", tone: "danger",
    }))) return;
    onCancel();
  };

  if (loading) return <Card><Spinner label="Loading product…" /></Card>;
  if (loadError) return (
    <Card>
      <div className="empty-state">
        <div style={{ color: "var(--clay)" }}>{loadError}</div>
        <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={onCancel}>Back to list</button>
      </div>
    </Card>
  );

  const canSave = isEdit ? perm.canEdit : perm.canCreate;
  const shownImage = imagePreview || (removeImage ? null : resolveImageUrl(existingImage));

  return (
    <form onSubmit={submit} noValidate>
      <div className="page-head">
        <div>
          <h1>{isEdit ? "Edit Product" : "New Product"}</h1>
          <div className="page-sub">
            {isEdit
              ? <>Editing <span className="mono">{productCode}</span>. The product code never changes.</>
              : "The product code is generated when you save."}
          </div>
        </div>
        <div className="flex-gap">
          <button type="button" className="btn btn-ghost btn-sm" onClick={cancel} disabled={saving}>
            <Ic path={P.back} size={13} /> Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving || !canSave}>
            {saving ? "Saving…" : isEdit ? "Save changes" : "Create product"}
          </button>
        </div>
      </div>

      <Card>
        {!canSave && (
          <div style={{
            padding: "11px 14px", background: "var(--amber-soft)", border: "1px solid var(--amber)",
            borderRadius: "var(--radius-sm)", fontSize: 13, marginBottom: 8, color: "var(--amber-deep)",
          }}>You have read-only access to Product Master.</div>
        )}

        {/* 1 — Basic */}
        <Section n={1} icon={P.pill} title="Basic Information" sub="Identity and classification" />
        <Grid min={210}>
          <Derived label="Product Code" locked value={productCode || "Generated on save"} hint="System generated, permanent" />

          {/* The name is checked as the cursor leaves it, not only at Save.
              Finding out a name is taken after filling in eleven more fields is
              the same information delivered at the worst possible moment. */}
          <div data-field="productName">
            <Field label={<>Product Name<Req /></>} error={errors.productName}
                   hint={nameChecking ? "Checking this name…" : undefined}>
              <input value={values.productName} maxLength={150} required aria-invalid={!!errors.productName}
                className={errors.productName ? "field-error" : ""}
                placeholder="e.g. Napa 500mg Tablet"
                onChange={(e) => set("productName", e.target.value)}
                onBlur={(e) => checkNameOnBlur(e.target.value)} />
            </Field>
          </div>

          <div data-field="productType">
            <Field label={<>Product Type<Req /></>}
                   hint={isGeneral ? "No SKU or generic needed" : "SKU and generic are required"}>
              <select value={values.productType}
                onChange={(e) => set("productType", e.target.value)}>
                <option value="PHARMA">Pharma — medicine</option>
                <option value="GENERAL">General — everything else</option>
              </select>
            </Field>
          </div>

          {/* SKU and Generic identify a medicine. A general product has neither,
              and the fields are removed rather than disabled: a greyed-out box
              still says "you have not filled this in", which is exactly the wrong
              thing to tell someone entering a bar of soap. */}
          {!isGeneral && (
            <div data-field="sku">
              {/* Optional. Most medicines carry no SKU of their own — 75 of the
                  119 products here have none — and the identifier that matters
                  is the product code, which the server always generates. */}
              <Field label="SKU" error={errors.sku} hint="Optional">
                <input value={values.sku} maxLength={50} aria-invalid={!!errors.sku}
                  className={`mono ${errors.sku ? "field-error" : ""}`} placeholder="NAPA500"
                  onChange={(e) => set("sku", e.target.value)}
                  onBlur={(e) => set("sku", e.target.value.trim())} />
              </Field>
            </div>
          )}

          {!isGeneral && (
            <div data-field="genericId">
              <Field label={<>Generic<Req /></>} error={errors.genericId}>
                <SearchSelect value={values.genericId} invalid={!!errors.genericId}
                  options={lookups?.generics ?? []} placeholder="Select generic…"
                  ariaLabel="Generic" onChange={(v) => set("genericId", v)} />
              </Field>
            </div>
          )}

          <div data-field="companyId">
            <Field label={<>Company<Req /></>} error={errors.companyId}>
              <SearchSelect value={values.companyId} invalid={!!errors.companyId}
                options={lookups?.companies ?? []} placeholder="Select company…"
                ariaLabel="Company" onChange={(v) => set("companyId", v)} />
            </Field>
          </div>

          <div data-field="categoryId">
            <Field label={<>Category<Req /></>} error={errors.categoryId}>
              <SearchSelect value={values.categoryId} invalid={!!errors.categoryId}
                options={lookups?.categories ?? []} placeholder="Select category…"
                ariaLabel="Category" onChange={(v) => set("categoryId", v)} />
            </Field>
          </div>

          <Field label="Sub-Category">
            <SearchSelect value={values.subCategoryId} disabled={!values.categoryId}
              options={subCategoryOptions} ariaLabel="Sub-category"
              placeholder={values.categoryId ? "None" : "Select a category first"}
              onChange={(v) => set("subCategoryId", v)} />
          </Field>

          <div data-field="unitId">
            <Field label={<>Unit<Req /></>} error={errors.unitId}>
              <SearchSelect value={values.unitId} invalid={!!errors.unitId}
                options={lookups?.units ?? []} placeholder="Select unit…"
                ariaLabel="Unit" onChange={(v) => set("unitId", v)} />
            </Field>
          </div>

          <Field label="Box">
            <SearchSelect value={values.boxId} options={lookups?.boxes ?? []}
              placeholder="None" ariaLabel="Box" onChange={(v) => set("boxId", v)} />
          </Field>

          <Derived label="No Of Pcs" value={selectedBox?.noOfPcs ?? (values.boxId ? "Not configured" : "—")}
            hint="From the Box — not stored on the product" />

          <div data-field="barcode">
            <Field label="Barcode" error={errors.barcode}>
              <input value={values.barcode} maxLength={30} autoComplete="off" aria-invalid={!!errors.barcode}
                className={`mono ${errors.barcode ? "field-error" : ""}`} placeholder="Scan or type"
                onChange={(e) => set("barcode", e.target.value)}
                onBlur={(e) => set("barcode", e.target.value.trim())} />
            </Field>
          </div>
        </Grid>

        {/* 2 — Pricing */}
        <Section n={2} icon={P.calc} title="Pricing"
          sub="TP Price, IGTA and Buy Price update as you type — the server recalculates them on save" />
        <Grid min={200}>
          <div data-field="mrp">
            <Field label={<>MRP (৳)<Req /></>} error={errors.mrp}>
              <input type="number" step="0.0001" min="0" inputMode="decimal" required
                value={values.mrp} aria-invalid={!!errors.mrp} placeholder="0.00"
                className={`mono ${errors.mrp ? "field-error" : ""}`}
                onChange={(e) => set("mrp", e.target.value)} />
            </Field>
          </div>

          <div data-field="conversionRate">
            <Field label={<>Conversion Rate (%)<Req /></>} error={errors.conversionRate}>
              <input type="number" step="0.001" min="0" max="99.999" inputMode="decimal"
                value={values.conversionRate} aria-invalid={!!errors.conversionRate}
                className={`mono ${errors.conversionRate ? "field-error" : ""}`}
                onChange={(e) => set("conversionRate", e.target.value)} />
            </Field>
          </div>

          <Derived label="TP Price (৳)" value={money(prices.tpPrice)} hint="MRP − (MRP × rate ÷ 100)" />

          <div data-field="vatPct">
            <Field label={<>VAT (%)<Req /></>} error={errors.vatPct}>
              <input type="number" step="0.01" min="0" max="100" inputMode="decimal"
                value={values.vatPct} aria-invalid={!!errors.vatPct}
                className={`mono ${errors.vatPct ? "field-error" : ""}`}
                onChange={(e) => set("vatPct", e.target.value)} />
            </Field>
          </div>

          <Derived label="IGTA (৳)" value={money(prices.igta)} hint="TP Price × VAT ÷ 100" />
          <Derived label="Buy Price (৳)" value={money(prices.buyPrice)} hint="TP Price + IGTA" />

          <div data-field="discountPct">
            <Field label="Discount (%)" error={errors.discountPct}>
              <input type="number" step="0.01" min="0" max="100" inputMode="decimal" className="mono"
                value={values.discountPct} placeholder="0"
                onChange={(e) => set("discountPct", e.target.value)} />
            </Field>
          </div>
        </Grid>

        {/* 3 — Inventory */}
        <Section n={3} icon={P.box} title="Inventory Control"
          sub="Minimum ≤ Safety ≤ Reorder ≤ Maximum · leave Maximum at 0 for no ceiling" />
        <Grid min={170}>
          {[
            ["minimumStock", "Minimum Stock"], ["safetyStock", "Safety Stock"],
            ["reorderLevel", "Reorder Level"], ["maximumStock", "Maximum Stock"],
          ].map(([key, label]) => (
            <div data-field={key} key={key}>
              <Field label={label} error={errors[key]}>
                <input type="number" step="0.01" min="0" inputMode="decimal" placeholder="0"
                  value={values[key]} aria-invalid={!!errors[key]}
                  className={`mono ${errors[key] ? "field-error" : ""}`}
                  onChange={(e) => set(key, e.target.value)} />
              </Field>
            </div>
          ))}
        </Grid>

        {/* 4 — Tracking */}
        <Section n={4} icon={P.rx} title="Tracking & Compliance"
          sub="Applied by the purchase, inventory and sales workflows" />
        <Grid min={260}>
          <Toggle label="Batch Tracking" checked={values.batchTracking}
            onChange={(v) => set("batchTracking", v)}
            hint="Batch number becomes mandatory in purchase and inventory transactions" />
          <Toggle label="Expiry Tracking" checked={values.expiryTracking}
            onChange={(v) => set("expiryTracking", v)}
            hint="Expiry date becomes mandatory; expired stock becomes detectable" />
          <Toggle label="Prescription Required" checked={values.prescriptionRequired}
            onChange={(v) => set("prescriptionRequired", v)}
            hint="Dispensing rules apply in sales workflows" />
        </Grid>

        {/* 5 — Image */}
        <Section n={5} icon={P.image} title="Product Image" sub="JPG, PNG, WEBP or GIF up to 2 MB" />
        <div style={{ display: "flex", gap: 20, alignItems: "flex-start", flexWrap: "wrap" }}>
          <div style={{
            width: 132, height: 132, borderRadius: "var(--radius)", overflow: "hidden", flexShrink: 0,
            border: "1px dashed var(--line)", background: values.imgColor || "var(--paper)",
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            {shownImage
              ? <img src={shownImage} alt="Product preview" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              : <div style={{ color: "var(--ink-soft)", textAlign: "center", fontSize: 11.5 }}>
                  <Ic path={P.image} size={22} />
                  <div style={{ marginTop: 6 }}>No image</div>
                </div>}
          </div>
          <div style={{ flex: "1 1 250px" }}>
            <input ref={fileRef} type="file" id="product-image-input" style={{ display: "none" }}
              accept="image/jpeg,image/png,image/webp,image/gif" onChange={pickImage} />
            <div className="flex-gap" style={{ flexWrap: "wrap" }}>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => fileRef.current?.click()}>
                <Ic path={P.image} size={13} /> {shownImage ? "Replace image" : "Choose image"}
              </button>
              {shownImage && (
                <button type="button" className="btn btn-ghost btn-sm" onClick={clearImage}>
                  <Ic path={P.x} size={13} /> Remove
                </button>
              )}
            </div>
            <p style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 10, lineHeight: 1.6 }}>
              {isEdit
                ? "The image uploads after the product is saved, replacing the previous one."
                : "The image uploads once the product has been created."}
            </p>
            <div style={{ maxWidth: 200, marginTop: 6 }}>
              <Field label="Fallback colour">
                <input type="color" value={values.imgColor || "#2E6A62"} style={{ height: 38, padding: 3 }}
                  onChange={(e) => set("imgColor", e.target.value)} />
              </Field>
            </div>
          </div>
        </div>

        {/* 6 — Status */}
        <Section n={6} icon={P.power} title="Status" />
        <Grid min={200}>
          <Field label={<>Status<Req /></>}>
            <select value={values.status} onChange={(e) => set("status", e.target.value)}>
              <option value="ACTIVE">Active</option>
              <option value="INACTIVE">Inactive</option>
            </select>
          </Field>
        </Grid>

        <div style={{
          display: "flex", justifyContent: "flex-end", gap: 10,
          marginTop: 24, paddingTop: 18, borderTop: "1px solid var(--line)",
        }}>
          <button type="button" className="btn btn-ghost" onClick={cancel} disabled={saving}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={saving || !canSave}>
            {saving ? "Saving…" : isEdit ? "Save changes" : "Create product"}
          </button>
        </div>
      </Card>
    </form>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   DETAIL
   ═══════════════════════════════════════════════════════════════════════════ */

function ProductDetail({ productId, perm, onEdit, onBack }) {
  const [product, setProduct] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let dead = false;
    setLoading(true);
    getProduct(productId)
      .then((p) => { if (!dead) { setProduct(p); setLoading(false); } })
      .catch((e) => { if (!dead) { setError(e.message || "Could not load the product."); setLoading(false); } });
    return () => { dead = true; };
  }, [productId]);

  if (loading) return <Card><Spinner label="Loading product…" /></Card>;
  if (error || !product) return (
    <Card>
      <div className="empty-state">
        <div style={{ color: "var(--clay)" }}>{error || "Product not found."}</div>
        <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={onBack}>Back to list</button>
      </div>
    </Card>
  );

  const Row = ({ label, children, mono }) => (
    <div style={{ display: "flex", gap: 12, padding: "8px 0", borderBottom: "1px solid var(--line)" }}>
      <div style={{ width: 138, flexShrink: 0, fontSize: 12, color: "var(--ink-soft)" }}>{label}</div>
      <div className={mono ? "mono" : ""} style={{ fontSize: 13.5, fontWeight: 600 }}>{children ?? "—"}</div>
    </div>
  );

  const imageUrl = resolveImageUrl(product.productImage);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>{product.productName}</h1>
          <div className="page-sub">
            <span className="mono">{product.productCode}</span> · {product.generic?.name} · {product.company?.name}
          </div>
        </div>
        <div className="flex-gap">
          <button className="btn btn-ghost btn-sm" onClick={onBack}><Ic path={P.back} size={13} /> Back to list</button>
          {perm.canEdit && <button className="btn btn-primary" onClick={onEdit}><Ic path={P.edit} size={14} /> Edit</button>}
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "minmax(200px, 240px) 1fr", gap: 18, alignItems: "start" }}
        className="detail-split">
        {/* Left rail */}
        <Card>
          <div style={{
            width: "100%", aspectRatio: "1", borderRadius: "var(--radius)", overflow: "hidden",
            background: product.imgColor || "var(--teal)",
            display: "flex", alignItems: "center", justifyContent: "center", marginBottom: 12,
          }}>
            {imageUrl
              ? <img src={imageUrl} alt={product.productName} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              : <span className="mono" style={{ color: "#fff", fontWeight: 800, fontSize: 40 }}>
                  {(product.productName || "?").slice(0, 2).toUpperCase()}
                </span>}
          </div>
          <div style={{ display: "flex", gap: 5, flexWrap: "wrap", marginBottom: 12 }}>
            <Pill tone={product.status === "ACTIVE" ? "sage" : "clay"}>{product.status}</Pill>
            <Pill tone={stockTone(product.stockStatus)}>{product.stockStatus}</Pill>
            {product.batchTracking && <Pill tone="teal">Batch</Pill>}
            {product.expiryTracking && <Pill tone="amber">Expiry</Pill>}
            {product.prescriptionRequired && <Pill tone="clay">Rx</Pill>}
          </div>
          <div style={{ borderTop: "1px solid var(--line)", paddingTop: 10 }}>
            <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>Buy Price</div>
            <div className="kpi-value" style={{ fontSize: 22, color: "var(--teal)" }}>{money(product.buyPrice)}</div>
          </div>
          {!product.deletable && (
            <div style={{
              marginTop: 12, padding: "9px 11px", background: "var(--amber-soft)",
              borderRadius: "var(--radius-sm)", fontSize: 11, color: "var(--amber-deep)", lineHeight: 1.5,
            }}>
              Has transaction history ({product.deleteBlockedReason}). Can be deactivated but not deleted.
            </div>
          )}
        </Card>

        {/* Right detail */}
        <div style={{ display: "grid", gap: 16, gridTemplateColumns: "repeat(auto-fit, minmax(290px, 1fr))" }}>
          <Card title="Basic Information" eyebrow="Section 1">
            <Row label="Product Code" mono>{product.productCode}</Row>
            <Row label="Product Name">{product.productName}</Row>
            <Row label="Generic">{product.generic?.name}</Row>
            <Row label="Company">{product.company?.name}</Row>
            <Row label="Category">{product.category?.name}</Row>
            <Row label="Sub-Category">{product.subCategory?.name}</Row>
            <Row label="SKU" mono>{product.sku}</Row>
            <Row label="Unit">{product.unit?.name}</Row>
            <Row label="Box">{product.box?.name}</Row>
            <Row label="No Of Pcs" mono>{product.box?.noOfPcs}</Row>
            <Row label="Barcode" mono>{product.barcode}</Row>
          </Card>

          <Card title="Pricing" eyebrow="Section 2">
            <Row label="MRP" mono>{money(product.mrp)}</Row>
            <Row label="Conversion Rate" mono>{product.conversionRate}%</Row>
            <Row label="TP Price" mono>{money(product.tpPrice)}</Row>
            <Row label="VAT" mono>{product.vatPct}%</Row>
            <Row label="IGTA" mono>{money(product.igta)}</Row>
            <Row label="Buy Price" mono>{money(product.buyPrice)}</Row>
            <Row label="Discount" mono>{product.discountPct ? `${product.discountPct}%` : "—"}</Row>
          </Card>

          <Card title="Inventory Control" eyebrow="Section 3">
            <Row label="Current Stock" mono>{Number(product.currentStock ?? 0)}</Row>
            <Row label="Minimum Stock" mono>{Number(product.minimumStock ?? 0)}</Row>
            <Row label="Safety Stock" mono>{Number(product.safetyStock ?? 0)}</Row>
            <Row label="Reorder Level" mono>{Number(product.reorderLevel ?? 0)}</Row>
            <Row label="Maximum Stock" mono>
              {Number(product.maximumStock ?? 0) === 0 ? "No ceiling" : Number(product.maximumStock)}
            </Row>
          </Card>

          <Card title="Tracking & Audit" eyebrow="Section 4">
            <Row label="Batch Tracking">{product.batchTracking ? "Enabled" : "Disabled"}</Row>
            <Row label="Expiry Tracking">{product.expiryTracking ? "Enabled" : "Disabled"}</Row>
            <Row label="Prescription">{product.prescriptionRequired ? "Required" : "Not required"}</Row>
            <Row label="Created By">{product.createdBy}</Row>
            <Row label="Created" mono>{product.createdDate ? new Date(product.createdDate).toLocaleString() : "—"}</Row>
            <Row label="Updated By">{product.updatedBy}</Row>
            <Row label="Updated" mono>{product.updatedDate ? new Date(product.updatedDate).toLocaleString() : "—"}</Row>
          </Card>
        </div>
      </div>
    </>
  );
}
