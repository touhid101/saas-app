/**
 * Reports.
 *
 * ── What this replaces ────────────────────────────────────────────────────
 *
 * The previous version made no API calls: thirty reports were computed in the
 * browser from mockData.js by a single `buildReport(id)` switch. Its only filter
 * was a text box that searched the rows already built — no date range, no
 * branch, no company, on a screen whose first report is "Daily Profit & Loss".
 *
 * It also reported a Net Profit of gross × 0.65 — a flat 35% deduction for
 * operating expenses nobody had entered. That figure is not carried over; the
 * data cannot support it.
 *
 * ── How it works now ──────────────────────────────────────────────────────
 *
 * Every report is a grid that describes its own columns, so this file renders
 * all of them without knowing what any one contains, and adding a report needs
 * no change here. Each also declares which filters it honours, so the bar shows
 * only controls that do something.
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Search, X, ArrowLeft, Printer, Download, RotateCcw, ChevronRight,
  ChevronLeft, FileX2, TriangleAlert, Loader2, SlidersHorizontal,
  Wallet, ShoppingCart, Truck, Boxes, Bike, Users, CalendarCheck, Banknote, PiggyBank,
  Scale, BookOpen, ArrowLeftRight, Landmark, TrendingUp,
} from "lucide-react";
import { Card, Pill } from "../components/UI.jsx";
import SearchSelect from "../components/SearchSelect.jsx";
import { moneyFull as money } from "../utils/format.js";
import { usePermission } from "../lib/usePermission.js";
import { useApp } from "../lib/AppContext.jsx";
import { useToast } from "../hooks/useToast.js";
import { downloadCSV } from "../utils/csv.js";
import svc, { REPORT_GROUPS, findReport } from "../services/reportService.js";
import { getBranches, getCategories, getCustomers, getSuppliers } from "../services/masterDataService.js";
import inventoryService from "../services/inventoryService.js";
import { getCostCenters } from "../services/accountsService.js";

/* Eight groups, five pill tones. The three HR groups share one deliberately —
   they are one family, and the badge carries the group's name beside the colour,
   so the colour is a grouping cue rather than the identifier. */
const GROUP_TONE = {
  Finance: "amber", Sales: "teal", Purchase: "sage",
  Inventory: "clay", "Delivery & Collection": "grey",
  People: "teal", "Attendance & Leave": "amber", Payroll: "sage", "Money & Benefits": "clay",
  "Financial Statements": "amber", "Ledgers & Journals": "grey", "Receivables & Payables": "teal",
  "Cash & Bank": "sage", "Investors & Payroll": "clay",
};

/* One icon per group, drawn on every card of the group so a card says what
   family it belongs to at a glance — the pill carries the name, the icon the
   shape. */
const GROUP_ICON = {
  Finance: Wallet, Sales: ShoppingCart, Purchase: Truck, Inventory: Boxes, "Delivery & Collection": Bike,
  People: Users, "Attendance & Leave": CalendarCheck, Payroll: Banknote, "Money & Benefits": PiggyBank,
  "Financial Statements": Scale, "Ledgers & Journals": BookOpen, "Receivables & Payables": ArrowLeftRight,
  "Cash & Bank": Landmark, "Investors & Payroll": TrendingUp,
};

/** 2026-05-28 → 28 May 2026. Long enough to be unambiguous, short enough to read. */
function fmtDay(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

/**
 * The branches this user may actually run a report against.
 *
 * The branch list is organisation-wide, but the server resolves any branch but
 * your own to a 404 unless you are ROLE01. Offering the others made the picker
 * a list of ways to break the report, so it is narrowed to what will work.
 */
function selectableBranches(all, user) {
  if (user?.roleCode === "ROLE01") return all;
  const mine = user?.branchId ?? user?.pidBranch ?? null;
  if (mine == null) return [];
  return all.filter(b => (b.id ?? b.pidBranch) === mine);
}

/**
 * The general Reports screen. The same index and viewer serve HR Reports
 * (`HrReports.jsx`) with a different registry, right and heading — one
 * grid, one filter bar, one CSV export, two doors.
 */
export default function Reports({
  groups = REPORT_GROUPS, permission = "reports", title = "Reports",
  subtitle = "Every figure here is read from the live ledger — nothing is estimated.",
  initialReportId = null, initialFilters = null,
}) {
  const [openId, setOpenId] = useState(initialReportId);
  const perm = usePermission(permission);

  return openId
    ? <ReportViewer reportId={openId} onBack={() => setOpenId(null)} perm={perm} initialFilters={openId === initialReportId ? initialFilters : null} />
    : <ReportIndex onOpen={setOpenId} groups={groups} title={title} subtitle={subtitle} />;
}

/* ═══════════════════════════════════════════════════════════════════════════
   INDEX
   ═══════════════════════════════════════════════════════════════════════════ */

function ReportIndex({ onOpen, groups: registry, title, subtitle }) {
  const [q, setQ] = useState("");
  const term = q.trim().toLowerCase();

  const groups = registry
    .map(g => ({ ...g, reports: g.reports.filter(r => !term || r.label.toLowerCase().includes(term)) }))
    .filter(g => g.reports.length);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>{title}</h1>
          <div className="page-sub">{subtitle}</div>
        </div>
        <div className="search-field" style={{ width: 260 }}>
          <Search size={15} />
          <input placeholder="Find a report…" value={q}
                 onChange={e => setQ(e.target.value)} aria-label="Find a report" />
          {q && (
            <button className="pwd-clear" onClick={() => setQ("")} aria-label="Clear search"
                    title="Clear the search">
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      {groups.length === 0 && (
        <div className="rpt-state">
          <FileX2 size={32} />
          <b>No report matches</b>
          <span>Nothing is called &ldquo;{q}&rdquo;. Try a shorter word — the search matches
                anywhere in a report&rsquo;s name.</span>
          <button className="btn btn-ghost btn-sm" onClick={() => setQ("")}
                  title="Show every report again">
            <SlidersHorizontal size={13} /> Clear the search
          </button>
        </div>
      )}

      <div className="rpt-groups">
        {groups.map(g => (
          <div key={g.group}>
            <div className="rpt-group-head">
              <span className="rpt-group-name">{g.group}</span>
              <Pill tone={GROUP_TONE[g.group] || "grey"}>{g.reports.length}</Pill>
            </div>
            <div className="rpt-grid">
              {g.reports.map(r => {
                const GroupIcon = GROUP_ICON[g.group];
                return (
                <button key={r.id} className="rpt-card" onClick={() => onOpen(r.id)}
                        title={`Open ${r.label}`}>
                  <div className="rpt-card-top">
                    {GroupIcon && <span className={`rpt-card-icon is-${GROUP_TONE[g.group] || "grey"}`}><GroupIcon size={16} /></span>}
                    <span className="rpt-card-name">{r.label}{r.desc && <span className="rpt-card-desc">{r.desc}</span>}</span>
                    <ChevronRight size={15} className="rpt-card-go" />
                  </div>
                  {/* The group, as a badge rather than the grey caption it was.
                      It earns its place when the search is on: results span
                      groups, and this is what says which one a card came from
                      without reading back up to a heading that is no longer
                      above it. */}
                  <Pill tone={GROUP_TONE[g.group] || "grey"}>{g.group}</Pill>
                </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   VIEWER
   ═══════════════════════════════════════════════════════════════════════════ */

function ReportViewer({ reportId, onBack, perm, initialFilters }) {
  const addToast = useToast();
  const meta = findReport(reportId);
  const { currentUser } = useApp();

  const [filters, setFilters] = useState(() => ({ ...svc.emptyFilters(), ...(initialFilters || {}) }));
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Lookups for the filter bar. Loaded once; a failure leaves the relevant
  // picker empty rather than blocking the report itself.
  const [lookups, setLookups] = useState({ branches: [], companies: [], categories: [], customers: [], suppliers: [], costCenters: [] });

  useEffect(() => {
    // allSettled, not all: one dead lookup should leave its own picker empty,
    // not stop the other four — and certainly not the report.
    Promise.allSettled([
      getBranches(),
      // The manufacturer whose products were sold, from POS_COMPANY_INFO — not
      // getCompanies(), which lists the organisation's own business entities.
      // The report filters on the product's company, so they are different lists.
      inventoryService.getLookups(),
      getCategories(),
      getCustomers({ size: 200 }),
      getSuppliers({ size: 200 }),
      // Accounting only, and usually empty: an organisation that keeps no cost
      // centres simply never sees the picker.
      getCostCenters(),
    ]).then(([b, lk, cat, cu, su, cc]) => setLookups({
      branches:   b.status === "fulfilled" ? selectableBranches(b.value ?? [], currentUser) : [],
      companies:  lk.status === "fulfilled" ? (lk.value?.companies ?? []) : [],
      categories: cat.status === "fulfilled" ? (cat.value ?? []) : [],
      customers:  cu.status === "fulfilled" ? (cu.value?.items ?? []) : [],
      suppliers:  su.status === "fulfilled" ? (su.value?.items ?? []) : [],
      costCenters: cc.status === "fulfilled" ? (cc.value ?? []) : [],
    }));
  }, [currentUser]);

  const run = useCallback(() => {
    setLoading(true);
    return svc.runReport(reportId, filters)
      .then(r => { setReport(r); setError(null); })
      .catch(e => setError(e?.message || "Could not run this report."))
      .finally(() => setLoading(false));
  }, [reportId, filters]);

  // Search is debounced; every other filter applies at once. Typing must not
  // fire a query per keystroke, but choosing a branch should feel immediate.
  const debounce = useRef(null);
  useEffect(() => {
    clearTimeout(debounce.current);
    debounce.current = setTimeout(run, filters.q ? 350 : 0);
    return () => clearTimeout(debounce.current);
  }, [run, filters.q]);

  const set = (patch) => setFilters(f => ({ ...f, ...patch }));
  const supports = (name) => report?.supportedFilters?.includes(name);

  const exportCSV = () => {
    if (!report) return;
    const headers = report.columns.map(c => c.label);
    const rows = report.rows.map(r =>
      Object.fromEntries(report.columns.map(c => [c.label, r[c.key] ?? ""])));
    downloadCSV(`${reportId}.csv`, rows, headers);
  };

  return (
    <div className="page report-shell">
      <div className="page-head">
        <div>
          <button className="btn btn-ghost btn-sm" style={{ marginBottom: 10 }} onClick={onBack}
                  title="Back to every report">
            <ArrowLeft size={14} /> All Reports
          </button>
          <h1>{report?.title || meta?.label}</h1>
          {report?.note && (
            <div className="page-sub" style={{ maxWidth: 720 }}>{report.note}</div>
          )}
        </div>
        <div className="flex-gap">
          {perm.canExport && (
            <>
              <button className="btn btn-ghost btn-sm" onClick={() => window.print()}
                      disabled={!report?.rows?.length}
                      title={report?.rows?.length ? "Print this report" : "Nothing to print yet"}>
                <Printer size={14} /> Print
              </button>
              <button className="btn btn-primary btn-sm" onClick={exportCSV}
                      disabled={!report?.rows?.length}
                      title={report?.rows?.length
                        ? `Download all ${(report.rowCount ?? report.rows.length).toLocaleString()} rows as CSV`
                        : "Nothing to export yet"}>
                <Download size={14} /> Export CSV
              </button>
            </>
          )}
        </div>
      </div>

      {/* Only the filters this report honours. A control that changes nothing
          is worse than one that is absent — someone will set it and trust it. */}
      <div className="filter-bar">
        {supports("dateRange") && (
          <>
            <label className="rpt-field">
              <span className="rpt-label">From</span>
              <input type="date" value={filters.from} max={filters.to || undefined}
                     onChange={e => set({ from: e.target.value })}
                     title="Earliest date to include" />
            </label>
            <label className="rpt-field">
              <span className="rpt-label">To</span>
              <input type="date" value={filters.to} min={filters.from || undefined}
                     onChange={e => set({ to: e.target.value })}
                     title="Latest date to include" />
            </label>
          </>
        )}

        {supports("branch") && lookups.branches.length > 1 && (
          <Picker label="Branch" value={filters.branchId} onChange={v => set({ branchId: v })}
            options={lookups.branches.map(b => ({ v: b.id ?? b.pidBranch, t: b.name ?? b.branchName }))} />
        )}
        {supports("company") && (
          <Picker label="Company" value={filters.companyId} onChange={v => set({ companyId: v })}
            options={lookups.companies.map(c => ({ v: c.id ?? c.pidCompany, t: c.name ?? c.companyName }))} />
        )}
        {supports("category") && (
          <Picker label="Category" value={filters.categoryId} onChange={v => set({ categoryId: v })}
            options={lookups.categories.map(c => ({ v: c.id ?? c.pidCategory, t: c.name ?? c.categoryName }))} />
        )}
        {supports("customer") && (
          <Picker label="Customer" value={filters.customerId} onChange={v => set({ customerId: v })}
            options={lookups.customers.map(c => ({ v: c.id ?? c.pidCustomer, t: c.name ?? c.customerName }))} />
        )}
        {supports("supplier") && (
          <Picker label="Supplier" value={filters.supplierId} onChange={v => set({ supplierId: v })}
            options={lookups.suppliers.map(s => ({ v: s.id ?? s.pidSupplier, t: s.name ?? s.supplierName }))} />
        )}
        {supports("costCenter") && lookups.costCenters.length > 0 && (
          <Picker label="Cost centre" value={filters.costCenterId} onChange={v => set({ costCenterId: v })}
            options={lookups.costCenters.map(c => ({ v: c.pidCostCenter, t: c.ccCode + " " + c.ccName }))} />
        )}
        {supports("paymentStatus") && (
          <Picker label="Payment" value={filters.paymentStatus} onChange={v => set({ paymentStatus: v || "All" })}
            options={["Paid", "Partial", "Unpaid"].map(s => ({ v: s, t: s }))} allLabel="All" />
        )}
        {supports("search") && (
          <label className="rpt-field rpt-search">
            <span className="rpt-label">Search</span>
            <span className="search-field">
              <Search size={14} />
              <input placeholder="Name, code or invoice…" value={filters.q}
                     onChange={e => set({ q: e.target.value })} />
              {filters.q && (
                <button type="button" className="pwd-clear" onClick={() => set({ q: "" })}
                        aria-label="Clear search" title="Clear the search">
                  <X size={13} />
                </button>
              )}
            </span>
          </label>
        )}

        <div className="rpt-bar-end">
          {svc.hasActiveFilter(filters) && (
            <button className="btn btn-ghost btn-sm" onClick={() => setFilters(svc.emptyFilters())}
                    title="Clear every filter and run the report again">
              <SlidersHorizontal size={13} /> Clear
            </button>
          )}
          <span className="rpt-count">
            {loading
              ? <><Loader2 size={12} className="rpt-spin" style={{ verticalAlign: "-2px" }} /> running…</>
              : report?.truncated
                ? `${(report.rows?.length ?? 0).toLocaleString()} of ${(report.rowCount ?? 0).toLocaleString()} rows`
                : `${(report?.rowCount ?? 0).toLocaleString()} rows`}
          </span>
        </div>
      </div>

      {/* A default is not a secret. Where the report picked the window rather
          than the reader, it says so and offers the way out of it. */}
      {!loading && report?.appliedFrom && (report.windowDefaulted || report.truncated) && (
        <div className="report-window-note">
          {report.windowDefaulted && (
            <span>
              Showing <strong>{fmtDay(report.appliedFrom)} – {fmtDay(report.appliedTo)}</strong>,
              the last 90 days. Set a date range above for any other period.
            </span>
          )}
          {report.truncated && (
            <span>
              {report.windowDefaulted ? " " : ""}
              This is the first {(report.rows?.length ?? 0).toLocaleString()} of{" "}
              <strong>{(report.rowCount ?? 0).toLocaleString()}</strong> matching rows — narrow the
              dates or filters to see the rest. Totals above cover all {(report.rowCount ?? 0).toLocaleString()}.
            </span>
          )}
        </div>
      )}

      <Card>
        {error ? (
          <div className="rpt-state rpt-state-error" role="alert">
            <TriangleAlert size={30} />
            <b>This report could not run</b>
            <span>{error}</span>
            <button className="btn btn-ghost btn-sm" onClick={run} title="Run the report again">
              <RotateCcw size={13} /> Try again
            </button>
          </div>
        ) : loading && !report ? (
          <div className="rpt-state">
            <Loader2 size={28} className="rpt-spin" />
            <b>Running…</b>
            <span>Reading the ledger for this period.</span>
          </div>
        ) : !report?.rows?.length ? (
          <div className="rpt-state">
            <FileX2 size={30} />
            <b>{svc.hasActiveFilter(filters) ? "Nothing matches those filters" : "Nothing to show yet"}</b>
            <span>
              {svc.hasActiveFilter(filters)
                ? "No record in this period fits the filters above."
                : "This report has no data for the period it covers."}
            </span>
            {svc.hasActiveFilter(filters) && (
              <button className="btn btn-ghost btn-sm"
                      onClick={() => setFilters(svc.emptyFilters())}
                      title="Clear every filter and run the report again">
                <SlidersHorizontal size={13} /> Clear filters
              </button>
            )}
          </div>
        ) : (
          <ReportTable report={report} />
        )}
      </Card>
    </div>
  );
}

/**
 * A filter picker.
 *
 * <b>Searchable, because of what these lists actually hold.</b> Customer and
 * supplier load two hundred rows each; a native select of two hundred is a
 * scroll, and its type-ahead only matches from the first letter, so finding
 * "Metro Medical Supply" means either scrolling or knowing it begins with M.
 * SearchSelect matches anywhere and moves on the arrow keys — the same control
 * the Purchase screen and Product Master already use, so there is one of these
 * to learn rather than two.
 *
 * <b>"All" is an option, not a separate button.</b> Clearing one filter is then
 * the same gesture as setting it, and the bar keeps its shape either way.
 */
function Picker({ label, value, onChange, options, allLabel = "All" }) {
  const list = [
    { id: "", name: allLabel },
    ...options.filter(o => o.v != null && o.t).map(o => ({ id: String(o.v), name: o.t })),
  ];
  return (
    <label className="rpt-field">
      <span className="rpt-label">{label}</span>
      <div className={`rpt-pick ${value ? "on" : ""}`}>
        <SearchSelect
          value={value == null ? "" : String(value)}
          onChange={onChange}
          options={list}
          ariaLabel={label}
          placeholder={allLabel}
          emptyLabel={`No ${label.toLowerCase()} matches that.`}
        />
      </div>
    </label>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   TABLE
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Renders any report from its own column definitions.
 *
 * The alternative — a bespoke table per report — is thirty places for a header
 * to drift out of step with the column beneath it, which is exactly what a
 * report must never do.
 */
function ReportTable({ report }) {
  const [page, setPage] = useState(0);
  const SIZE = 100;

  useEffect(() => { setPage(0); }, [report]);

  const { columns, rows, totals } = report;
  const pageRows = rows.slice(page * SIZE, (page + 1) * SIZE);
  const totalPages = Math.ceil(rows.length / SIZE);
  const hasTotals = columns.some(c => c.total);

  const fmt = (value, type) => {
    if (value === null || value === undefined || value === "") return "—";
    switch (type) {
      case "money":   return money(value);
      case "percent": return `${Number(value).toFixed(1)}%`;
      case "number":  return Number(value).toLocaleString("en-BD");
      default:        return String(value);
    }
  };
  const numeric = (t) => t === "money" || t === "number" || t === "percent";

  return (
    <>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              {columns.map(c => (
                <th key={c.key} style={{ textAlign: numeric(c.type) ? "right" : "left" }}>{c.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pageRows.map((r, i) => (
              <tr key={i}>
                {columns.map(c => {
                  const v = r[c.key];
                  // A negative money figure is a loss or a credit; it should not
                  // read the same as a positive one at a glance.
                  const negative = c.type === "money" && Number(v) < 0;
                  return (
                    <td key={c.key}
                      className={numeric(c.type) ? "mono" : ""}
                      style={{
                        textAlign: numeric(c.type) ? "right" : "left",
                        color: negative ? "var(--clay)" : undefined,
                        fontWeight: negative ? 700 : undefined,
                      }}>
                      {v === "BELOW COST"
                        ? <span className="rpt-flag">BELOW COST</span>
                        : fmt(v, c.type)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
          {hasTotals && (
            <tfoot>
              {/* Totals span the whole report, not the page on screen. A footer
                  summing only the visible rows looks like an answer and is not. */}
              <tr className="rpt-total-row">
                {columns.map((c, i) => (
                  <td key={c.key} className={numeric(c.type) ? "mono" : ""}
                    style={{ textAlign: numeric(c.type) ? "right" : "left", padding: "10px 8px" }}>
                    {i === 0 ? "Total" : c.total ? fmt(totals?.[c.key], c.type) : ""}
                  </td>
                ))}
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      {totalPages > 1 && (
        <div className="rpt-pager">
          <span className="rpt-count">
            Showing {pageRows.length.toLocaleString()} of {rows.length.toLocaleString()}
          </span>
          <div className="rpt-pager-nav">
            <button className="btn btn-ghost btn-sm" disabled={page === 0}
                    onClick={() => setPage(p => p - 1)}
                    title={page === 0 ? "This is the first page" : "Previous page"}>
              <ChevronLeft size={14} /> Prev
            </button>
            <span className="rpt-page-of">Page {page + 1} / {totalPages}</span>
            <button className="btn btn-ghost btn-sm" disabled={page + 1 >= totalPages}
                    onClick={() => setPage(p => p + 1)}
                    title={page + 1 >= totalPages ? "This is the last page" : "Next page"}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </>
  );
}
