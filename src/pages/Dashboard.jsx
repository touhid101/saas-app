/**
 * The dashboard.
 *
 * <b>Two dashboards, one page.</b> Everyone gets the operational board — what is
 * short, what is dated, what is waiting to go out. The financial board is drawn
 * only when the server sends the figures, and for anyone not entitled to them it
 * sends nothing: `finance` is absent from the payload, not blanked here. The
 * screen reads `scope.canSeeFinancials` rather than the user's role, so exactly
 * one place knows the rule and it is the server.
 *
 * <b>Why it is in three tabs.</b> Laid out as one page it ran to three and a
 * half screens of sixteen equally weighted cards, with stock split across four
 * of them and money across seven, interleaved. The best single line on it —
 * that two products carry eighty per cent of the revenue — sat three screens
 * down, which is the same as not being there.
 *
 * Each tab is now about one screen and about one subject, and they are ordered
 * the way the questions are asked: what needs doing now, what is on the shelf,
 * how the business is doing. The third does not exist for somebody who may not
 * see money, which is a cleaner statement of the rule than a paragraph
 * apologising for an empty space.
 *
 * <b>Nothing here is estimated.</b> The screen this replaced multiplied revenue
 * by 0.18 and called it gross profit.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill, Table } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import dashboardService from "../services/dashboardService.js";
import { usePermission } from "../lib/usePermission.js";
import { useApp } from "../lib/AppContext.jsx";
import { HBars, GroupedBars, DonutChart, CalendarHeat, Pareto } from "../components/DashCharts.jsx";

const money = (n) =>
  "৳ " + Number(n ?? 0).toLocaleString("en-US", { maximumFractionDigits: 0 });
const moneyExact = (n) =>
  "৳ " + Number(n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const count = (n) => Number(n ?? 0).toLocaleString("en-US", { maximumFractionDigits: 0 });

const SEVERITY_TONE = { CRITICAL: "clay", WARNING: "amber", INFO: "teal" };

/**
 * Ordered the way the questions are asked: what needs doing now, what is on the
 * shelf, how the business is doing. "Money" is filtered out entirely when the
 * server did not send the figures.
 */
const TABS = [
  { key: "today", label: "Today" },
  { key: "stock", label: "Stock" },
  { key: "people", label: "People" },
  { key: "money", label: "Money" },
];

/** Where each alert is answered. */
const ACTION_LABEL = {
  ai: "Open the Copilot",
  inventory: "Open Inventory",
  delivery: "Open Delivery",
  sales: "Open Sales",
};

export default function Dashboard({ onNavigate }) {
  const addToast = useToast();

  /* The Copilot is offered only to somebody who may act on what it says.
     Read from the matrix rather than named here, so who that is changes on the
     RBAC screen and not in this file. The Money tab needs no gate of its own:
     the server sends no finance block without the same right, so the tab is
     already absent for anyone who should not have it. */
  const aiPerm = usePermission("ai");

  /* The People tab is drawn only for somebody who has an HR page to open. The
     counts come from the context, which fetched them once for the sidebar
     badges — so the tab costs no request of its own, and a tile and a badge
     can never disagree about how many things are waiting. */
  const { hrCounts, hasHrPage, refreshHrCounts } = useApp();

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [days, setDays] = useState(30);
  const [tab, setTab] = useState("today");

  // No branch to choose. The server reads it off the session, so the board is
  // the pharmacy this user works in and cannot be pointed at another one.
  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    return dashboardService
      .getDashboard({ days })
      .then(setData)
      .catch((e) => setError(e?.message || "Could not load the dashboard."))
      .finally(() => setLoading(false));
  }, [days]);

  useEffect(() => { load(); }, [load]);

  /**
   * Whether this alert's answer is one this user may open.
   *
   * <p>Only the Copilot is gated, and on Approve: it recommends what to buy, and
   * an alert offering to open it for somebody who cannot act on the
   * recommendation is a dead end dressed as an action.
   */
  const canGo = (action) => action !== "ai" || aiPerm.canApprove;

  /* The server writes "The Copilot can work out what to order" into the alert,
     which is a promise to somebody who has no Copilot — this pharmacy may not
     have bought the module, or this role may not act on it. Rather than let the
     text advertise a screen that is not there, the sentence naming it is
     dropped and the rest of the alert stands on its own. */
  const detailFor = (a) =>
    a.action === "ai" && !canGo("ai")
      ? String(a.detail || "").replace(/\s*The Copilot[^.]*\.\s*$/, "")
      : a.detail;

  const jump = (action) => {
    if (!action || !onNavigate || !canGo(action)) return;
    onNavigate(action === "inventory" ? "inventory"
      : action === "delivery" ? "delivery"
      : action === "sales" ? "sales" : "ai");
  };

  const ops = data?.operations;
  const fin = data?.finance;

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Dashboard</h1>
          <div className="page-sub">
            {data?.scope?.branchName ?? "…"}
            {data && ` · ${data.scope.days} days · as at ${data.scope.asOf}`}
          </div>
        </div>
        <div className="flex-gap">
          <select className="req-branch" value={days} disabled={loading}
                  onChange={(e) => setDays(Number(e.target.value))} aria-label="Window">
            <option value={7}>7 days</option>
            <option value={30}>30 days</option>
            <option value={90}>90 days</option>
          </select>
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            {loading ? "Loading…" : "Refresh"}
          </button>
        </div>
      </div>

      {error && (
        <Card>
          <div className="empty-state" style={{ color: "var(--clay)" }}>
            {error}
            <div style={{ marginTop: 10 }}>
              <button className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
            </div>
          </div>
        </Card>
      )}

      {!error && !data && loading && (
        <Card><div className="empty-state">Reading the ledger…</div></Card>
      )}

      {data && (
        <>
          {/* Alerts sit above the tabs: they are the reason to open the page at
              all, and burying one behind a tab is how it gets missed. */}
          {ops.alerts.length > 0 ? (
            <div className="dash-alerts">
              {ops.alerts.map((a) => (
                <button key={a.key} className={`dash-alert dash-alert-${a.severity.toLowerCase()}`}
                  onClick={() => jump(a.action)}
                  /* Still shown, still readable: the alert is the point, and
                     hiding it would keep somebody from knowing about a problem
                     merely because they cannot be the one to fix it. */
                  disabled={!!a.action && !canGo(a.action)}>
                  <div className="dash-alert-top">
                    <Pill tone={SEVERITY_TONE[a.severity] || "teal"}>{a.severity}</Pill>
                    {a.count != null && <span className="dash-alert-count mono">{a.count}</span>}
                  </div>
                  <div className="dash-alert-title">{a.title}</div>
                  <div className="dash-alert-detail">{detailFor(a)}</div>
                  {a.action && canGo(a.action) &&
                    <div className="dash-alert-go">{ACTION_LABEL[a.action] ?? "Open"} →</div>}
                </button>
              ))}
            </div>
          ) : (
            <div className="dash-allclear">
              Nothing needs attention. No product is out or below its reorder level, nothing is
              short-dated, and every confirmed order is on somebody's round.
            </div>
          )}

          <div className="tabs">
            {TABS.filter((t) => (t.key !== "money" || fin)
                             && (t.key !== "people" || hasHrPage)).map((t) => (
              <button key={t.key} className={`tab-btn ${tab === t.key ? "active" : ""}`}
                onClick={() => setTab(t.key)}>
                {t.label}
              </button>
            ))}
          </div>

          {tab === "today" && (
            <>
              <div className="dash-strip">
                <Stat label="Orders raised today" value={count(ops.today.ordersRaised)} />
                <Stat label="Delivered today" value={count(ops.today.ordersDelivered)} />
                <Stat label="Awaiting delivery" value={count(ops.today.ordersPending)}
                      tone={ops.today.ordersPending > 0 ? "amber" : undefined} />
                <Stat label="Pieces sold today" value={count(ops.today.piecesSold)} />
                <Stat label="Products needing attention"
                      value={count(ops.stock.outOfStock + ops.stock.lowStock)}
                      sub={`of ${count(ops.stock.productsTracked)} tracked`}
                      tone={ops.stock.outOfStock > 0 ? "clay" : undefined} />
              </div>

              <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
                <Card eyebrow="Every square is a day" title="Trading rhythm"
                  right={<span className="ai-stat-sub">pieces sold</span>}>
                  <CalendarHeat points={ops.trend.map((t) => ({ day: t.day, value: t.pcs }))}
                    format={(v) => `${count(v)} pcs`} />
                </Card>

                <Card eyebrow={`Last ${data.scope.days} days`} title="Order pipeline"
                  right={onNavigate && (
                    <button className="btn btn-ghost btn-sm"
                      onClick={() => onNavigate("delivery")}>Delivery →</button>
                  )}>
                  <HBars rows={ops.pipeline} format={(v) => count(v)}
                    empty="No orders in this window." />
                </Card>
              </div>
            </>
          )}

          {tab === "stock" && (
            <>
              <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
                <Card eyebrow="How long the catalogue will last" title="Stock cover"
                  right={<span className="ai-stat-sub">products</span>}>
                  <HBars rows={ops.coverBuckets} format={(v) => count(v)} />
                </Card>

                <Card eyebrow="Sell the top bars first" title="Shelf life remaining"
                  right={<span className="ai-stat-sub">pieces</span>}>
                  <HBars rows={ops.expiryRunway}
                    format={(v, r) => (fin && r.amount != null && r.amount > 0)
                      ? `${count(v)} · ${money(r.amount)}` : count(v)} />
                </Card>
              </div>

              <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
                <StockCard stock={ops.stock} onNavigate={onNavigate} />
                <ExpiringCard rows={ops.expiring} onNavigate={onNavigate} />
              </div>

              <MoversCard movers={ops.movers} days={data.scope.days} />
            </>
          )}

          {tab === "people" && hasHrPage && (
            <PeopleBoard hr={hrCounts} onNavigate={onNavigate} onRefresh={refreshHrCounts} />
          )}

          {tab === "money" && fin && (
            <>
              <FinanceBoard fin={fin} days={data.scope.days} />

              <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
                {/* Concentration first: it is the most useful single sentence on
                    the page, and it used to be the last thing on it. */}
                <Card eyebrow="How few lines carry the shop" title="Revenue concentration">
                  <Pareto rows={fin.topProducts.map((p) => ({ label: p.name, value: p.revenue }))}
                    format={money} />
                </Card>

                <Card eyebrow="Month by month" title="Revenue against cost"
                  right={<span className="ai-stat-sub">the gap is the margin</span>}>
                  <GroupedBars rows={fin.monthly} format={money} />
                </Card>
              </div>

              <div className="dash-charts">
                <Card eyebrow="A debt only matters by its age" title="Owed to us, by age">
                  <HBars rows={fin.receivableAgeing} format={money} empty="Nothing outstanding." />
                </Card>

                <Card eyebrow={`Last ${data.scope.days} days`} title="How customers pay">
                  <DonutChart rows={fin.paymentMix} format={money} />
                </Card>

                <Card eyebrow="Commercial exposure" title="Revenue by manufacturer">
                  <HBars rows={fin.revenueByMaker} format={money} empty="No sales in this window." />
                </Card>
              </div>

              <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
                <TopCustomers rows={fin.topCustomers} days={data.scope.days} />
                <TopProducts rows={fin.topProducts.slice(0, 6)} days={data.scope.days} />
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   People
   ══════════════════════════════════════════════════════════════════════════ */

/**
 * The workforce, on the board that everything else is on.
 *
 * <p>Three rows, in the order the questions are asked: <b>is anybody
 * waiting on me</b>, <b>who is in today</b>, and <b>where is this month's
 * payroll</b>. Everything is a real count from the HR summary — nothing here is
 * derived, estimated or filled in with a plausible number.
 *
 * <p>The tiles that count work are links. A figure telling you eleven things
 * need a decision, with no way to reach them, is a complaint rather than a
 * dashboard.
 */
function PeopleBoard({ hr, onNavigate, onRefresh }) {
  if (!hr) {
    return (
      <Card>
        <div className="empty-state">
          The HR figures could not be read.
          <div style={{ marginTop: 10 }}>
            <button className="btn btn-ghost btn-sm" onClick={onRefresh}>Try again</button>
          </div>
        </div>
      </Card>
    );
  }

  const go = (page) => () => onNavigate?.(page);

  const decisions = [
    { label: "Leave requests",   n: hr.pendingLeaveRequests, page: "leave" },
    { label: "Expense claims",   n: hr.pendingExpenseClaims, page: "expenses" },
    { label: "Advances & loans", n: (hr.pendingAdvances || 0) + (hr.pendingLoans || 0),
      page: "advances" },
    { label: "Attendance fixes", n: hr.pendingAttendanceCorrections, page: "attendance" },
    { label: "Separations",      n: hr.pendingSeparations, page: "hr" },
  ];
  const waiting = decisions.reduce((s, d) => s + (d.n || 0), 0);

  const pay = hr.latestPayroll;
  const marked = (hr.presentToday || 0) + (hr.absentToday || 0) + (hr.onLeaveToday || 0);

  return (
    <>
      <div className="dash-strip dash-people">
        <Stat label="Active staff" value={count(hr.activeEmployees)}
              sub={`${count(hr.payrollReady)} ready for payroll`} />
        <Stat label="In today" value={count(hr.presentToday)}
              sub={`${count(hr.absentToday)} absent · ${count(hr.onLeaveToday)} on leave`} />
        <Stat label="Register not marked" value={count(hr.attendanceNotRecordedToday)}
              sub={marked > 0 ? `${count(marked)} recorded so far` : "nobody marked yet"}
              tone={hr.attendanceNotRecordedToday > 0 ? "amber" : undefined} />
        <Stat label="Waiting on a decision" value={count(waiting)}
              sub={waiting > 0 ? "across five queues" : "every queue is clear"}
              tone={waiting > 0 ? "clay" : undefined} />
        <Stat label="Owed by staff"
              value={money(Number(hr.advanceOutstanding || 0) + Number(hr.loanOutstanding || 0))}
              sub={`${money(hr.advanceOutstanding)} advance · ${money(hr.loanOutstanding)} loan`} />
      </div>

      {/* Somebody payroll will skip is the one thing on this tab that is wrong
         rather than merely pending, so it is said in words and not as a tile. */}
      {hr.notPayrollReady > 0 && (
        <div className="dash-warn">
          <b>{count(hr.notPayrollReady)}</b>{" "}
          {hr.notPayrollReady === 1 ? "person is" : "people are"} not ready for payroll and will be
          skipped by every run — usually a missing basic salary or salary structure.{" "}
          <button className="dash-inline-link" onClick={go("hr")}>Open the staff list →</button>
        </div>
      )}

      <div className="grid-2" style={{ alignItems: "stretch", gap: 16 }}>
        <Card eyebrow="Nothing here is a total — each is a queue" title="Waiting for a decision"
              right={<span className="ai-stat-sub">{count(waiting)} in all</span>}>
          {waiting === 0 ? (
            <div className="empty-state">
              Nothing is waiting on anybody. Every request filed at this branch has been decided.
            </div>
          ) : (
            <div className="dash-queues">
              {decisions.filter((d) => d.n > 0).map((d) => (
                <button key={d.label} className="dash-queue" onClick={go(d.page)}>
                  <span className="dash-queue-n mono">{count(d.n)}</span>
                  <span className="dash-queue-label">{d.label}</span>
                  <span className="dash-queue-go">Open →</span>
                </button>
              ))}
            </div>
          )}
        </Card>

        <Card eyebrow={pay ? pay.periodLabel : "No run yet"} title="This month's payroll"
              right={onNavigate && (
                <button className="btn btn-ghost btn-sm" onClick={go("payroll")}>Payroll →</button>
              )}>
          {!pay ? (
            <div className="empty-state">
              No payroll has been calculated. A run needs an open pay period, and the period
              carries the working-day count every absence is valued against.
            </div>
          ) : (
            <>
              <div className="dash-fin">
                <Stat label="Payslips" value={count(pay.employeeCount)} />
                <Stat label="Gross" value={money(pay.totalGross)} />
                <Stat label="Net" value={money(pay.totalNet)} />
                <Stat label="Still owing" value={money(pay.unpaid)}
                      tone={Number(pay.unpaid || 0) > 0 ? "amber" : undefined} />
              </div>
              <div className="dash-payroll-state">
                <Pill tone={PAYROLL_TONE[pay.payrollStatus] || "grey"}>
                  {pay.payrollStatusLabel || pay.payrollStatus}
                </Pill>
                <span className="ai-stat-sub">{PAYROLL_NEXT[pay.payrollStatus] || ""}</span>
              </div>
            </>
          )}
        </Card>
      </div>
    </>
  );
}

const PAYROLL_TONE = {
  DRAFT: "grey", CALCULATED: "teal", REVIEWED: "teal",
  APPROVED: "sage", POSTED: "sage", PAID: "sage", CANCELLED: "clay",
};

/** What the run is waiting for. A status word says where it is, not what next. */
const PAYROLL_NEXT = {
  DRAFT:      "Not calculated yet.",
  CALCULATED: "Check the exceptions, then mark it reviewed.",
  REVIEWED:   "Waiting for approval.",
  APPROVED:   "Waiting to be posted. Posting freezes it.",
  POSTED:     "Frozen. Record the payments.",
  PAID:       "Done.",
  CANCELLED:  "Cancelled — start the month again.",
};

/* ══════════════════════════════════════════════════════════════════════════
   Shared pieces
   ══════════════════════════════════════════════════════════════════════════ */

function Stat({ label, value, sub, tone }) {
  return (
    <div className="ai-stat">
      <div className="ai-stat-label">{label}</div>
      <div className={`ai-stat-value${tone ? ` ai-stat-${tone}` : ""}`}>{value}</div>
      {sub && <div className="ai-stat-sub">{sub}</div>}
    </div>
  );
}

/** Shape, not readings — there are axes on the reports for that. */
function Sparkline({ points, tone = "var(--teal)" }) {
  if (!points?.length) return <div className="ai-stat-sub">No activity recorded.</div>;
  const w = 240, h = 40, pad = 2;
  const max = Math.max(...points.map((p) => p.v), 1);
  const step = points.length > 1 ? (w - pad * 2) / (points.length - 1) : 0;
  const d = points.map((p, i) =>
    `${i === 0 ? "M" : "L"} ${(pad + i * step).toFixed(1)} ${(h - pad - (p.v / max) * (h - pad * 2)).toFixed(1)}`
  ).join(" ");
  return (
    <svg className="ai-spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" role="img"
         aria-label={`${points.length} day trend`}>
      <path d={d} fill="none" stroke={tone} strokeWidth="1.6" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Finance — drawn only when the server sent the figures
   ══════════════════════════════════════════════════════════════════════════ */

function FinanceBoard({ fin, days }) {
  const trend = fin.revenueTrendPct;
  return (
    <Card eyebrow="This month" title="Trading"
      right={<span className="ai-stat-sub">
        Margin is revenue less the cost of the batches that actually went out
      </span>}>
      <div className="dash-fin">
        <Stat label="Revenue today" value={money(fin.today.revenue)}
              sub={`${fin.today.invoices} invoice${fin.today.invoices === 1 ? "" : "s"}`} />
        <Stat label="Revenue this month" value={money(fin.month.revenue)}
              sub={trend == null ? "no prior month to compare"
                   : `${trend > 0 ? "+" : ""}${trend}% on last month`}
              tone={trend == null ? undefined : trend < 0 ? "clay" : "sage"} />
        <Stat label="Margin this month" value={money(fin.month.margin)}
              sub={fin.month.marginPct == null ? "—" : `${fin.month.marginPct}% of revenue`}
              tone={fin.month.marginPct != null && fin.month.marginPct < 10 ? "amber" : "sage"} />
        <Stat label="Purchases this month" value={money(fin.purchasesMonth)}
              sub={`${money(fin.purchasesToday)} today`} />
        {/* Cashback and gift value only. The bonus pieces are named in the
            sub-line but stay out of the headline figure: a bonus is already
            counted as a lower cost per piece, and adding an MRP for it here
            would show the business a benefit it received once as though twice. */}
        {fin.supplierBenefit && (
          <Stat label="Supplier benefit this month"
                value={money(fin.supplierBenefit.total)}
                sub={[
                  fin.supplierBenefit.pctOfPurchases != null
                    ? `${fin.supplierBenefit.pctOfPurchases}% of purchases` : null,
                  fin.supplierBenefit.bonusPcs > 0
                    ? `${fin.supplierBenefit.bonusPcs} bonus pcs` : null,
                ].filter(Boolean).join(" · ") || "nothing received yet"}
                tone={fin.supplierBenefit.total > 0 ? "sage" : undefined} />
        )}
        <Stat label="Owed to us" value={money(fin.receivable.total)}
              sub={`${fin.receivable.count} unpaid · ${money(fin.receivable.overdue)} over 30 days`}
              tone={fin.receivable.overdue > 0 ? "amber" : undefined} />
        <Stat label="We owe" value={money(fin.payable.total)}
              sub={`${fin.payable.count} supplier${fin.payable.count === 1 ? "" : "s"}`} />
        <Stat label="Stock at cost" value={money(fin.stockValue)}
              sub={`${money(fin.expiringValue)} dated within 90 days`}
              tone={fin.expiredValue > 0 ? "clay" : undefined} />
        <div className="dash-spark-wrap">
          <div className="ai-stat-label">Margin · {days} days</div>
          <Sparkline points={fin.trend.map((t) => ({ v: Math.max(t.margin, 0) }))} tone="var(--sage)" />
        </div>
      </div>
      {fin.expiredValue > 0 && (
        <div className="dash-warn">
          {moneyExact(fin.expiredValue)} of stock is already past its expiry date and still counted
          on the shelf. It cannot be sold — write it off so the stock figure means something.
        </div>
      )}
    </Card>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Operational cards
   ══════════════════════════════════════════════════════════════════════════ */

function StockCard({ stock, onNavigate }) {
  const rows = [
    ["Out of stock", stock.outOfStock, "clay"],
    ["At or below reorder level", stock.lowStock, "amber"],
    ["Dated within 30 days", stock.expiring30, "amber"],
    ["Dated within 90 days", stock.expiring90, "teal"],
    ["Already expired", stock.alreadyExpired, "clay"],
    ["Not sold in 60 days", stock.deadStock, "grey"],
  ];
  return (
    <Card eyebrow={`${count(stock.productsTracked)} products · ${count(stock.piecesOnHand)} pcs on hand`}
      title="Stock health"
      right={onNavigate && (
        <button className="btn btn-ghost btn-sm" onClick={() => onNavigate("inventory")}>Inventory →</button>
      )}>
      <div className="dash-rows">
        {rows.map(([label, n, tone]) => (
          <div key={label} className="dash-row">
            <span>{label}</span>
            <span className={`mono dash-row-n${n > 0 ? ` dash-${tone}` : ""}`}>{count(n)}</span>
          </div>
        ))}
      </div>
    </Card>
  );
}

function MoversCard({ movers, days }) {
  if (!movers.length) {
    return <Card title="What's moving"><div className="empty-state">Nothing has sold in this window.</div></Card>;
  }
  return (
    <Card eyebrow={`Last ${days} days, by quantity`} title="What's moving">
      <Table head={["Product", "Sold", "On hand", "Cover", "Trend"]}>
        {movers.map((m) => (
          <tr key={m.productName}>
            <td>
              <div style={{ fontWeight: 600, fontSize: 12.5 }}>{m.productName}</div>
              <div className="ai-prod-sub">{m.companyName || "—"}</div>
            </td>
            <td className="mono">{count(m.pcs)}</td>
            <td className="mono">{count(m.onHand)}</td>
            <td className="mono">
              {m.daysOfCover == null ? <span className="muted">—</span>
                : <span className={m.daysOfCover <= 7 ? "ai-danger" : ""}>{m.daysOfCover}d</span>}
            </td>
            <td className="mono">
              {m.trendPct == null ? <span className="muted">new</span>
                : <span className={m.trendPct < 0 ? "ai-danger" : "ai-good"}>
                    {m.trendPct > 0 ? "+" : ""}{m.trendPct}%
                  </span>}
            </td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}

function ExpiringCard({ rows, onNavigate }) {
  if (!rows.length) {
    return (
      <Card title="Expiring soonest">
        <div className="empty-state">Nothing on the shelf is dated within 90 days.</div>
      </Card>
    );
  }
  return (
    <Card eyebrow="Sell these first" title="Expiring soonest"
      right={onNavigate && (
        <button className="btn btn-ghost btn-sm" onClick={() => onNavigate("inventory")}>Batches →</button>
      )}>
      <Table head={["Product", "Batch", "Pieces", "Expires", "Left"]}>
        {rows.map((e, i) => (
          <tr key={`${e.batchNo}-${i}`}>
            <td style={{ fontWeight: 600, fontSize: 12.5 }}>{e.productName}</td>
            <td className="mono">{e.batchNo}</td>
            <td className="mono">{count(e.pcs)}</td>
            <td className="mono">{e.expiryDate}</td>
            <td className="mono">
              <span className={e.daysLeft < 0 ? "ai-danger" : e.daysLeft < 30 ? "ai-danger" : ""}>
                {e.daysLeft < 0 ? "expired" : `${e.daysLeft}d`}
              </span>
            </td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}

function TopCustomers({ rows, days }) {
  if (!rows.length) return null;
  return (
    <Card eyebrow={`Last ${days} days`} title="Best customers">
      <Table head={["Customer", "Type", "Revenue", "Owing", "Invoices"]}>
        {rows.map((c) => (
          <tr key={c.name}>
            <td style={{ fontWeight: 600, fontSize: 12.5 }}>{c.name}</td>
            <td className="ai-prod-sub">{c.type}</td>
            <td className="mono" style={{ fontWeight: 700 }}>{money(c.revenue)}</td>
            <td className="mono">
              {c.due > 0 ? <span className="ai-danger">{money(c.due)}</span> : <span className="muted">—</span>}
            </td>
            <td className="mono">{c.invoices}</td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}

function TopProducts({ rows, days }) {
  if (!rows.length) return null;
  return (
    <Card eyebrow={`Last ${days} days`} title="Best earners">
      <Table head={["Product", "Revenue", "Margin", "%", "Pieces"]}>
        {rows.map((p) => (
          <tr key={p.name}>
            <td style={{ fontWeight: 600, fontSize: 12.5 }}>{p.name}</td>
            <td className="mono" style={{ fontWeight: 700 }}>{money(p.revenue)}</td>
            <td className="mono">{money(p.margin)}</td>
            <td className="mono">
              {p.marginPct == null ? <span className="muted">—</span>
                : <span className={p.marginPct < 0 ? "ai-danger" : ""}>{p.marginPct}%</span>}
            </td>
            <td className="mono">{count(p.pcs)}</td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}
