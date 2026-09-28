/**
 * Inventory — branch-wise batch stock reports.
 *
 * Every figure here comes from POS_STOCK_BATCH and POS_STOCK_MOVEMENT through
 * /api/inventory. The version this replaces ran entirely on mockData.js: it
 * looked like a working report and showed fabricated numbers.
 *
 * Two conventions worth keeping:
 *
 *   Quantities are pieces. The `qtyDisplay` / `closingDisplay` strings ("8 box
 *   2 strip 3 pcs") are computed server-side from the packing frozen on each
 *   batch, so they describe the stock actually held even after someone edits a
 *   product's pack size. Do not recompute them here.
 *
 *   Branch is never sent. The server resolves it from the JWT; only an
 *   administrator may look at another branch.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill, Tabs, Table } from "../components/UI.jsx";
import { usePermission } from "../lib/usePermission.js";
import inventoryService, {
  PRICE_SLOTS, MOVEMENT_LABELS, EXPIRY_TONES, STOCK_TONES, DIRECTION_TONES,
} from "../services/inventoryService.js";

/* ── Formatting ───────────────────────────────────────────────────────────
   Local rather than imported from lib/calc.js, which pulls in mockData.js as a
   side effect of its own imports. This page must not depend on that. */

const money = (n) =>
  "৳ " + Number(n || 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const money0 = (n) =>
  "৳ " + Number(n || 0).toLocaleString("en-BD", { maximumFractionDigits: 0 });

const qty = (n) =>
  Number(n || 0).toLocaleString("en-BD", { maximumFractionDigits: 2 });

/** An unpriced unit is not zero — it renders as a dash, never as ৳ 0.00. */
const priceOrDash = (v) => (v === null || v === undefined ? "—" : money(v));

const TABS = [
  "Dashboard", "Stock Ledger", "Batch Stock", "Stock Movement",
  "Movement Summary", "Profit", "Expiry", "Alerts",
];

/**
 * Box / strip / pcs in one cell.
 *
 * Every stock figure is stored in pieces; boxes and strips are derived
 * server-side from the packing frozen on each lot. Showing all three lets the
 * person counting shelves and the person reading the valuation use the same
 * row. A dash means the product has no packing for that unit — not zero.
 */
function UnitCell({ pcs, box, strip, display }) {
  return (
    <div className="unit-cell">
      <div className="unit-cell-main mono">{qty(pcs)} <span className="unit-cell-tag">pcs</span></div>
      <div className="unit-cell-sub mono">
        {box == null ? "—" : `${qty(box)} box`} · {strip == null ? "—" : `${qty(strip)} strip`}
      </div>
      {display && <div className="unit-cell-shelf">{display}</div>}
    </div>
  );
}

/**
 * Actual buy price per box, strip and piece.
 *
 * The landed cost of the batch — trade price, IGTA and line discount included —
 * not the product's current list cost, which says nothing about what any
 * particular delivery was billed at. A dash means that unit has no packing
 * configured; "avg" marks a figure spanning several lots, which is a different
 * number from any single lot's cost.
 */
function CostCell({ cost }) {
  if (!cost || cost.costPerPcs == null) return <span className="muted">—</span>;

  return (
    <div className="cost-cell" title={
      cost.averaged
        ? "Weighted average across the lots on hand"
        : "Landed cost of this batch"
    }>
      <div className="cost-cell-row">
        <span className="cost-cell-label">Box</span>
        <span className="mono">{cost.costPerBox == null ? "—" : money(cost.costPerBox)}</span>
      </div>
      <div className="cost-cell-row">
        <span className="cost-cell-label">Strip</span>
        <span className="mono">{cost.costPerStrip == null ? "—" : money(cost.costPerStrip)}</span>
      </div>
      <div className="cost-cell-row">
        <span className="cost-cell-label">Pcs</span>
        <span className="mono">{money(cost.costPerPcs)}</span>
      </div>
      {cost.averaged && <div className="cost-cell-avg">avg</div>}
    </div>
  );
}

/**
 * Pieces with the box (and optionally strip) equivalent beneath, for the
 * narrower columns of the movement summary where a full {@link UnitCell} would
 * make every row three lines tall.
 */
function Dual({ pcs, box, strip }) {
  return (
    <div className="dual-cell">
      <div className="mono">{qty(pcs)}</div>
      <div className="dual-cell-sub mono">
        {box == null ? "—" : `${qty(box)} bx`}
        {strip != null && ` · ${qty(strip)} st`}
      </div>
    </div>
  );
}

export default function Inventory({ onNavigate }) {
  const [tab, setTab] = useState("Dashboard");
  const perm = usePermission("inventory");

  // Filters live above the tabs and survive switching between them: having
  // narrowed to one manufacturer on Batch Stock, you almost always want the
  // same narrowing when you jump to Movement or Profit to see why.
  const [filter, setFilter] = useState(inventoryService.emptyFilter());
  const [lookups, setLookups] = useState({ companies: [], batches: [] });

  useEffect(() => {
    let alive = true;
    inventoryService.getLookups()
      .then((l) => alive && setLookups(l))
      .catch(() => {}); // a missing filter bar must not break the reports
    return () => { alive = false; };
  }, []);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Inventory</h1>
          <div className="page-sub">
            Branch-wise batch stock — levels, movements, expiry and alerts.
          </div>
        </div>
      </div>

      <Tabs tabs={TABS} active={tab} onChange={setTab} />

      {tab !== "Dashboard" && (
        <FilterBar filter={filter} onChange={setFilter} lookups={lookups} />
      )}

      {tab === "Dashboard"        && <InvDashboard onJump={setTab} />}
      {tab === "Stock Ledger"     && <StockLedger filter={filter} />}
      {tab === "Batch Stock"      && <BatchStock filter={filter} />}
      {tab === "Stock Movement"   && <StockMovement filter={filter} />}
      {tab === "Movement Summary" && <MovementSummary filter={filter} />}
      {tab === "Profit"           && <ProfitReport filter={filter} />}
      {tab === "Expiry"           && <ExpiryReport filter={filter} />}
      {tab === "Alerts"           && <StockAlerts filter={filter} perm={perm} />}
    </div>
  );
}

/**
 * Company, batch and free-text search, shared by every report.
 *
 * The search box matches product name, SKU *and* batch reference, so someone
 * who has a number in front of them can type it without first working out
 * which field it belongs to. The batch dropdown is for browsing what is
 * actually on the shelf.
 */
function FilterBar({ filter, onChange, lookups }) {
  const [text, setText] = useState(filter.search || "");

  // Debounced so typing does not fire a request per keystroke.
  //
  // The update is functional, not `{...filter, search: text}`. Spreading the
  // `filter` captured when the timer was set reverts anything else changed
  // during those 300ms — pick a company right after typing and the company
  // silently un-picks itself when the timer fires.
  useEffect(() => {
    const t = setTimeout(() => {
      onChange((prev) => (prev.search === text ? prev : { ...prev, search: text }));
    }, 300);
    return () => clearTimeout(t);
  }, [text, onChange]);

  const set = (k, v) => onChange((prev) => ({ ...prev, [k]: v }));
  const active = inventoryService.hasActiveFilter(filter);

  const clear = () => {
    setText("");
    onChange(inventoryService.emptyFilter());
  };

  return (
    <div className="inv-filters">
      <input
        className="input inv-filter-search"
        type="search"
        placeholder="Search product, SKU or batch…"
        value={text}
        onChange={(e) => setText(e.target.value)}
        aria-label="Search product, SKU or batch"
      />

      <select className="input" value={filter.companyId || ""}
              onChange={(e) => set("companyId", e.target.value)}
              aria-label="Filter by company">
        <option value="">All companies</option>
        {lookups.companies.map((c) => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>

      <select className="input" value={filter.batchNo || ""}
              onChange={(e) => set("batchNo", e.target.value)}
              aria-label="Filter by batch">
        <option value="">All batches</option>
        {lookups.batches.map((b) => (
          <option key={b} value={b}>{b}</option>
        ))}
      </select>

      {active && (
        <button className="btn btn-ghost btn-sm" onClick={clear}>
          Clear filters
        </button>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   SHARED
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Loading, error and empty are three different states and each needs its own
 * message. Collapsing them into one spinner is how "no data" and "the request
 * failed" become indistinguishable.
 */
function useAsync(fn, deps) {
  const [state, setState] = useState({ loading: true, error: null, data: null });
  const run = useCallback(fn, deps); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    run()
      .then((data) => alive && setState({ loading: false, error: null, data }))
      .catch((e) => alive && setState({ loading: false, error: e?.message || "Request failed.", data: null }));
    return () => { alive = false; };
  }, [run]);

  return state;
}

function Status({ loading, error, empty, children }) {
  if (loading) return <div className="empty-state" role="status" aria-live="polite">Loading…</div>;
  if (error) return <div className="empty-state" role="alert">{error}</div>;
  if (empty) return <div className="empty-state">{empty}</div>;
  return children;
}

function Pager({ page, totalPages, total, onPage }) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <div className="pager">
      <button className="btn btn-ghost" disabled={page <= 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <span className="pager-info">
        Page {page + 1} of {totalPages} · {total} row{total === 1 ? "" : "s"}
      </span>
      <button className="btn btn-ghost" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </div>
  );
}

/** All six sale prices, as a compact grid. */
function PriceGrid({ prices }) {
  if (!prices) return <span className="muted">—</span>;
  if (!prices.priced) return <span className="muted">Not priced</span>;

  return (
    <div className="price-grid">
      {PRICE_SLOTS.map((slot) => {
        const value = prices[slot.key];
        const pct = prices[slot.pct];
        return (
          <div key={slot.key} className="price-cell" title={
            value === null || value === undefined
              ? `${slot.tier} ${slot.unit}: not priced`
              : `${slot.tier} ${slot.unit}: ${money(prices[slot.mrp])} MRP less ${pct}%`
          }>
            <div className="price-cell-label">{slot.tier === "Special" ? "SPC " : ""}{slot.unit}</div>
            <div className="price-cell-value mono">{priceOrDash(value)}</div>
          </div>
        );
      })}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   DASHBOARD
   ═══════════════════════════════════════════════════════════════════════════ */

function InvDashboard({ onJump }) {
  const { loading, error, data } = useAsync(() => inventoryService.getDashboard(), []);

  const kpis = useMemo(() => {
    if (!data) return [];
    return [
      { label: "Stock Value",        value: money0(data.totalStockValue), tone: "teal" },
      { label: "Total Pieces",       value: qty(data.totalPcs),           tone: "teal" },
      { label: "Products in Stock",  value: qty(data.productsInStock),    tone: "sage" },
      { label: "Low Stock",          value: qty(data.lowStockCount),      tone: "amber", jump: "Alerts" },
      { label: "Out of Stock",       value: qty(data.outOfStockCount),    tone: "clay",  jump: "Alerts" },
      { label: "Near Expiry (30d)",  value: qty(data.nearExpiryCount),    tone: "amber", jump: "Expiry" },
      { label: "Expired Batches",    value: qty(data.expiredCount),       tone: "clay",  jump: "Expiry" },
    ];
  }, [data]);

  return (
    <Status loading={loading} error={error}>
      <div className="inv-kpi-grid">
        {kpis.map((k) => {
          const clickable = Boolean(k.jump);
          return (
            <button
              key={k.label}
              type="button"
              className={`kpi-card kpi-${k.tone}${clickable ? " kpi-clickable" : ""}`}
              onClick={() => clickable && onJump(k.jump)}
              disabled={!clickable}
              aria-label={clickable ? `${k.label}: ${k.value}. Open ${k.jump}.` : `${k.label}: ${k.value}`}
            >
              <div className="kpi-value">{k.value}</div>
              <div className="kpi-label">{k.label}</div>
            </button>
          );
        })}
      </div>

      <Card
        title="Where these numbers come from"
        eyebrow="Branch-wise batch stock"
      >
        <p className="muted" style={{ margin: 0, lineHeight: 1.6 }}>
          Stock is held in pieces, per branch, per batch. Purchases add to a lot;
          sales consume lots earliest-expiry-first; returns go back to the lot
          they came from. Every movement is logged, and the balances above are
          reconciled against that log.
        </p>
      </Card>
    </Status>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   STOCK LEDGER — per product
   ═══════════════════════════════════════════════════════════════════════════ */

function StockLedger({ filter }) {
  const [page, setPage] = useState(0);

  // Any filter change goes back to page one — page 3 of the previous result set
  // is meaningless against a different one, and lands the user on "no rows".
  useEffect(() => { setPage(0); }, [filter]);

  const { loading, error, data } = useAsync(
    () => inventoryService.getStockLedger({ ...filter, page, size: 25 }),
    [filter, page]
  );

  return (
    <Card title="Stock Ledger" eyebrow="Per product, this branch">
      <Status
        loading={loading}
        error={error}
        empty={data && data.items.length === 0 ? "No products match." : null}
      >
        {data && (
          <>
            <Table head={["Product", "SKU", "Stock", "Batches", "Buy Price", "Value", "Alert", "Status", "Sale Prices"]}>
              {data.items.map((r) => (
                <tr key={r.productId}>
                  <td>{r.productName}</td>
                  <td className="mono">{r.sku || "—"}</td>
                  <td><UnitCell pcs={r.stockPcs} box={r.stockBox} strip={r.stockStrip} display={r.stockDisplay} /></td>
                  <td className="mono">{r.batchCount}</td>
                  <td><CostCell cost={r.cost} /></td>
                  <td className="mono">{money(r.stockValue)}</td>
                  <td className="mono">{r.alertQty ?? "—"}</td>
                  <td><Pill tone={STOCK_TONES[r.stockStatus] || "grey"}>{r.stockStatus}</Pill></td>
                  <td><PriceGrid prices={r.prices} /></td>
                </tr>
              ))}
            </Table>
            <Pager page={data.page} totalPages={data.totalPages} total={data.total} onPage={setPage} />
          </>
        )}
      </Status>
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   BATCH STOCK
   ═══════════════════════════════════════════════════════════════════════════ */

function BatchStock({ filter }) {
  const [includeEmpty, setIncludeEmpty] = useState(false);
  const [page, setPage] = useState(0);

  useEffect(() => { setPage(0); }, [filter]);

  const { loading, error, data } = useAsync(
    () => inventoryService.getBatchStock({ ...filter, includeEmpty, page, size: 50 }),
    [filter, includeEmpty, page]
  );

  return (
    <Card
      title="Batch Stock"
      eyebrow="One row per lot"
      right={
        <label className="checkline">
          <input
            type="checkbox"
            checked={includeEmpty}
            onChange={(e) => { setIncludeEmpty(e.target.checked); setPage(0); }}
          />
          Show exhausted lots
        </label>
      }
    >
      <Status
        loading={loading}
        error={error}
        empty={data && data.items.length === 0 ? "No batches hold stock in this branch." : null}
      >
        {data && (
          <>
            <Table head={["Product", "Batch", "Expiry", "Days", "Stock", "Buy Price", "Value", "Received", "Issued", "Sale Prices"]}>
              {data.items.map((b) => (
                <tr key={b.stockId}>
                  <td>{b.productName}</td>
                  <td className="mono">{b.batchNo}</td>
                  <td className="mono">{b.expiryDate || "—"}</td>
                  <td>
                    <Pill tone={EXPIRY_TONES[b.expiryStatus] || "grey"}>
                      {b.daysToExpiry === null || b.daysToExpiry === undefined
                        ? "—"
                        : b.daysToExpiry < 0 ? `${Math.abs(b.daysToExpiry)}d ago` : `${b.daysToExpiry}d`}
                    </Pill>
                  </td>
                  <td><UnitCell pcs={b.qtyPcs} box={b.qtyBox} strip={b.qtyStrip} display={b.qtyDisplay} /></td>
                  <td><CostCell cost={b.cost} /></td>
                  <td className="mono">{money(b.stockValue)}</td>
                  <td className="mono">{qty(b.receivedPcs)}</td>
                  <td className="mono">{qty(b.issuedPcs)}</td>
                  <td><PriceGrid prices={b.prices} /></td>
                </tr>
              ))}
            </Table>
            <Pager page={data.page} totalPages={data.totalPages} total={data.total} onPage={setPage} />
          </>
        )}
      </Status>
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   STOCK MOVEMENT — the ledger
   ═══════════════════════════════════════════════════════════════════════════ */

function StockMovement({ filter }) {
  const period = useMemo(() => inventoryService.defaultPeriod(), []);
  const [fromDate, setFromDate] = useState(period.fromDate);
  const [toDate, setToDate] = useState(period.toDate);
  const [page, setPage] = useState(0);

  useEffect(() => { setPage(0); }, [filter]);

  const { loading, error, data } = useAsync(
    () => inventoryService.getMovements({ ...filter, fromDate, toDate, page, size: 50 }),
    [filter, fromDate, toDate, page]
  );

  return (
    <Card
      title="Stock Movement"
      eyebrow="Every piece in and out, and the document behind it"
      right={
        <div className="filter-row">
          <input className="input" type="date" value={fromDate}
                 onChange={(e) => { setFromDate(e.target.value); setPage(0); }} aria-label="From date" />
          <span className="muted">to</span>
          <input className="input" type="date" value={toDate}
                 onChange={(e) => { setToDate(e.target.value); setPage(0); }} aria-label="To date" />
        </div>
      }
    >
      <Status
        loading={loading}
        error={error}
        empty={data && data.items.length === 0 ? "No stock moved in this period." : null}
      >
        {data && (
          <>
            <Table head={["Date", "Product", "Batch", "Type", "In/Out", "Quantity", "Balance", "Buy Price", "Reference"]}>
              {data.items.map((m) => (
                <tr key={m.movementId}>
                  <td className="mono">{m.movementDate}</td>
                  <td>{m.productName}</td>
                  <td className="mono">{m.batchNo}</td>
                  <td>{MOVEMENT_LABELS[m.movementType] || m.movementType}</td>
                  <td>
                    <Pill tone={DIRECTION_TONES[m.direction] || "grey"}>
                      {m.direction === "I" ? "IN" : "OUT"}
                    </Pill>
                  </td>
                  <td><UnitCell pcs={m.qtyPcs} box={m.qtyBox} strip={m.qtyStrip} display={m.qtyDisplay} /></td>
                  <td className="mono">{qty(m.balanceAfter)}</td>
                  <td><CostCell cost={m.cost} /></td>
                  <td className="mono">{m.remarks || (m.refMst ? `#${m.refMst}` : "—")}</td>
                </tr>
              ))}
            </Table>
            <Pager page={data.page} totalPages={data.totalPages} total={data.total} onPage={setPage} />
          </>
        )}
      </Status>
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   MOVEMENT SUMMARY — all four document types
   ═══════════════════════════════════════════════════════════════════════════ */

function MovementSummary({ filter }) {
  const period = useMemo(() => inventoryService.defaultPeriod(), []);
  const [fromDate, setFromDate] = useState(period.fromDate);
  const [toDate, setToDate] = useState(period.toDate);

  const { loading, error, data } = useAsync(
    () => inventoryService.getMovementSummary({ ...filter, fromDate, toDate }),
    [filter, fromDate, toDate]
  );

  return (
    <>
      <Card
        title="Movement Summary"
        eyebrow="Opening → purchases, returns, sales → closing"
        right={
          <div className="filter-row">
            <input className="input" type="date" value={fromDate}
                   onChange={(e) => setFromDate(e.target.value)} aria-label="From date" />
            <span className="muted">to</span>
            <input className="input" type="date" value={toDate}
                   onChange={(e) => setToDate(e.target.value)} aria-label="To date" />
          </div>
        }
      >
        <Status
          loading={loading}
          error={error}
          empty={data && data.rows.length === 0 ? "Nothing moved in this period." : null}
        >
          {data && (
            <>
              {data.unbalancedCount > 0 && (
                <div className="banner banner-clay" role="alert">
                  {data.unbalancedCount} product{data.unbalancedCount === 1 ? "" : "s"} closed at a
                  figure that does not match their own movements. Investigate before relying on
                  these totals.
                </div>
              )}

              <div className="summary-strip">
                <SummaryStat label="Purchased"       value={qty(data.totalPurchasedPcs)} sub="pcs" />
                <SummaryStat label="Purchase Return" value={qty(data.totalPurchaseReturnedPcs)} sub="pcs" />
                <SummaryStat label="Sold"            value={qty(data.totalSoldPcs)} sub="pcs" />
                <SummaryStat label="Sales Return"    value={qty(data.totalSalesReturnedPcs)} sub="pcs" />
                <SummaryStat label="Purchase Value"  value={money0(data.totalPurchaseValue)} />
                <SummaryStat label="Cost of Sales"   value={money0(data.totalSalesCostValue)} />
              </div>

              <Table head={[
                "Product", "Opening", "Purchased", "Pur. Return", "Sold", "Sales Return",
                "Adjusted", "Closing", "Buy Price", "Purchase Value", "Cost of Sales", "Sale Prices",
              ]}>
                {data.rows.map((r) => (
                  <tr key={r.productId} className={r.balanced ? undefined : "row-warn"}>
                    <td>
                      {r.productName}
                      {!r.balanced && <> <Pill tone="clay">unbalanced</Pill></>}
                    </td>
                    <td><Dual pcs={r.openingPcs} box={r.openingBox} /></td>
                    <td><Dual pcs={r.purchasedPcs} box={r.purchasedBox} /></td>
                    <td className="mono">{qty(r.purchaseReturnedPcs)}</td>
                    <td><Dual pcs={r.soldPcs} box={r.soldBox} /></td>
                    <td className="mono">{qty(r.salesReturnedPcs)}</td>
                    <td className="mono">{qty(r.adjustedPcs)}</td>
                    <td title={r.closingDisplay}>
                      <Dual pcs={r.closingPcs} box={r.closingBox} strip={r.closingStrip} />
                    </td>
                    <td><CostCell cost={r.cost} /></td>
                    <td className="mono">{money(r.purchaseValue)}</td>
                    <td className="mono">{money(r.salesCostValue)}</td>
                    <td><PriceGrid prices={r.prices} /></td>
                  </tr>
                ))}
              </Table>

              <p className="muted" style={{ marginTop: 12, fontSize: 13 }}>
                Quantities in pieces. Cost of Sales is the cost of the batches actually consumed,
                not an average — it is the COGS half of actual profit.
              </p>
            </>
          )}
        </Status>
      </Card>
    </>
  );
}

function SummaryStat({ label, value, sub }) {
  return (
    <div className="summary-stat">
      <div className="summary-stat-label">{label}</div>
      <div className="summary-stat-value mono">
        {value}{sub && <span className="summary-stat-sub"> {sub}</span>}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PROFIT — revenue against the cost of the batches actually consumed
   ═══════════════════════════════════════════════════════════════════════════ */

function ProfitReport({ filter }) {
  const period = useMemo(() => inventoryService.defaultPeriod(), []);
  const [fromDate, setFromDate] = useState(period.fromDate);
  const [toDate, setToDate] = useState(period.toDate);

  const { loading, error, data } = useAsync(
    () => inventoryService.getProfit({ ...filter, fromDate, toDate }),
    [filter, fromDate, toDate]
  );

  return (
    <Card
      title="Actual Profit"
      eyebrow="Invoiced revenue less the cost of the batches consumed"
      right={
        <div className="filter-row">
          <input className="input" type="date" value={fromDate}
                 onChange={(e) => setFromDate(e.target.value)} aria-label="From date" />
          <span className="muted">to</span>
          <input className="input" type="date" value={toDate}
                 onChange={(e) => setToDate(e.target.value)} aria-label="To date" />
        </div>
      }
    >
      <Status
        loading={loading}
        error={error}
        empty={data && data.rows.length === 0 ? "Nothing was sold in this period." : null}
      >
        {data && (
          <>
            {data.belowCostCount > 0 && (
              <div className="banner banner-clay" role="alert">
                {data.belowCostCount} product{data.belowCostCount === 1 ? " was" : "s were"} sold
                below cost. The total below is net of those losses — check the flagged rows before
                relying on it.
              </div>
            )}

            <div className="summary-strip">
              <SummaryStat label="Revenue"      value={money0(data.totalRevenue)} />
              <SummaryStat label="Cost of Sales" value={money0(data.totalCost)} />
              <SummaryStat label="Gross Profit" value={money0(data.totalGrossProfit)} />
              <SummaryStat label="Margin"
                           value={data.totalMarginPct == null ? "—" : `${data.totalMarginPct}%`} />
              <SummaryStat label="Sold"         value={qty(data.totalNetPcs)} sub="pcs" />
              <SummaryStat label="Products"     value={qty(data.productCount)} />
            </div>

            <Table head={[
              "Product", "Sold", "Returned", "Net Sold", "Buy Price", "Revenue",
              "Cost", "Gross Profit", "Margin", "Avg Sale/pc",
            ]}>
              {data.rows.map((r) => (
                <tr key={r.productId} className={r.soldBelowCost ? "row-warn" : undefined}>
                  <td>
                    {r.productName}
                    {r.soldBelowCost && <> <Pill tone="clay">below cost</Pill></>}
                  </td>
                  <td className="mono">{qty(r.soldPcs)}</td>
                  <td className="mono">{qty(r.returnedPcs)}</td>
                  <td>
                    <UnitCell pcs={r.netPcs} box={r.netBox} strip={r.netStrip} display={r.netDisplay} />
                  </td>
                  {/* The buy price the margin was actually earned against. */}
                  <td><CostCell cost={r.costPrice} /></td>
                  <td className="mono">{money(r.revenue)}</td>
                  <td className="mono">{money(r.cost)}</td>
                  <td className={`mono ${r.grossProfit < 0 ? "tone-clay" : "tone-sage"}`}>
                    {money(r.grossProfit)}
                  </td>
                  <td className={`mono ${r.marginPct < 0 ? "tone-clay" : ""}`}>
                    {r.marginPct == null ? "—" : `${r.marginPct}%`}
                  </td>
                  <td className="mono">{priceOrDash(r.avgSalePricePerPcs)}</td>
                </tr>
              ))}
            </Table>

            <p className="muted" style={{ marginTop: 12, fontSize: 13 }}>
              Cost is what the specific batches consumed actually cost, paired line by line — not an
              average product cost. Margin is stated against revenue, not cost. Sales returns are
              netted off both sides.
            </p>
          </>
        )}
      </Status>
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   EXPIRY
   ═══════════════════════════════════════════════════════════════════════════ */

function ExpiryReport({ filter }) {
  const [days, setDays] = useState(30);
  const [page, setPage] = useState(0);

  const { loading, error, data } = useAsync(
    () => inventoryService.getExpiringBatches({ ...filter, days, page, size: 50 }),
    [filter, days, page]
  );

  return (
    <Card
      title="Expiry"
      eyebrow="Lots at or past expiry — already-expired first"
      right={
        <select className="input" value={days}
                onChange={(e) => { setDays(Number(e.target.value)); setPage(0); }}
                aria-label="Expiry window">
          <option value={0}>Already expired</option>
          <option value={30}>Within 30 days</option>
          <option value={60}>Within 60 days</option>
          <option value={90}>Within 90 days</option>
          <option value={180}>Within 180 days</option>
        </select>
      }
    >
      <Status
        loading={loading}
        error={error}
        empty={data && data.items.length === 0 ? "No lots expire in this window." : null}
      >
        {data && (
          <>
            <Table head={["Product", "Batch", "Expiry", "Days", "Status", "Stock", "Buy Price", "Value at Cost"]}>
              {data.items.map((b) => (
                <tr key={b.stockId}>
                  <td>{b.productName}</td>
                  <td className="mono">{b.batchNo}</td>
                  <td className="mono">{b.expiryDate}</td>
                  <td className="mono">
                    {b.daysToExpiry < 0 ? `${Math.abs(b.daysToExpiry)} ago` : b.daysToExpiry}
                  </td>
                  <td><Pill tone={EXPIRY_TONES[b.expiryStatus] || "grey"}>{b.expiryStatus.replace("_", " ")}</Pill></td>
                  <td><UnitCell pcs={b.qtyPcs} box={b.qtyBox} strip={b.qtyStrip} display={b.qtyDisplay} /></td>
                  {/* What is about to be written off, per unit. */}
                  <td><CostCell cost={b.cost} /></td>
                  <td className="mono">{money(b.stockValue)}</td>
                </tr>
              ))}
            </Table>
            <Pager page={data.page} totalPages={data.totalPages} total={data.total} onPage={setPage} />
          </>
        )}
      </Status>
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ALERTS
   ═══════════════════════════════════════════════════════════════════════════ */

function StockAlerts({ filter }) {
  const low = useAsync(() => inventoryService.getLowStock(filter), [filter]);
  const out = useAsync(() => inventoryService.getOutOfStock(filter), [filter]);

  return (
    <div className="alert-split">
      <Card title="Low Stock" eyebrow="At or below the alert level">
        <Status
          loading={low.loading}
          error={low.error}
          empty={low.data && low.data.length === 0 ? "Nothing is running low." : null}
        >
          {low.data && (
            <Table head={["Product", "Stock", "Alert At", "Buy Price", "Value"]}>
              {low.data.map((p) => (
                <tr key={p.productId}>
                  <td>{p.productName}</td>
                  <td><UnitCell pcs={p.stockPcs} box={p.stockBox} strip={p.stockStrip} display={p.stockDisplay} /></td>
                  <td className="mono">{p.alertQty ?? "—"}</td>
                  {/* What a replacement order will cost, per unit. */}
                  <td><CostCell cost={p.cost} /></td>
                  <td className="mono">{money(p.stockValue)}</td>
                </tr>
              ))}
            </Table>
          )}
        </Status>
      </Card>

      <Card title="Out of Stock" eyebrow="No sellable stock — expired-only counts as none">
        <Status
          loading={out.loading}
          error={out.error}
          empty={out.data && out.data.length === 0 ? "Everything is in stock." : null}
        >
          {out.data && (
            <Table head={["Product", "Alert At"]}>
              {out.data.map((p) => (
                <tr key={p.productId}>
                  <td>{p.productName}</td>
                  <td className="mono">{p.alertQty ?? "—"}</td>
                </tr>
              ))}
            </Table>
          )}
        </Status>
      </Card>
    </div>
  );
}
