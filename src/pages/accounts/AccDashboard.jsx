/**
 * AccDashboard — the Accounts home: the books at a glance, and a way into
 * every one of them.
 *
 * Every figure is a ledger balance or a ledger movement (never a sum of
 * business tables). The hero says what the period made and how that compares
 * with the window just before; the charts are the same ledger by day, with
 * the previous window available as an overlay; every tile, bar, segment and
 * control-centre row opens the screen that explains it, already filtered.
 * Sections collapse and remember it per browser; the page can refresh itself.
 */
import React, { useEffect, useMemo, useState } from "react";
import { usePermission } from "../../lib/usePermission.js";
import { HrPage, Kpis, Kpi, HrTable, HrPageSkeleton, HrErrorState, HrButton, HrFilterBar, Status, Ic, P } from "../../components/HrKit.jsx";
import { getDashboard } from "../../services/accountsService.js";
import { amount, compact, variance, fmtDate, fmtDateTime, isoMonthStart, isoToday, pct } from "../../utils/financial.js";
import { ACC_CRUMB, AI, useAsync, DateRange, BranchPicker, Figure, Source, Delta, sendIntent } from "./AccKit.jsx";
import { TrendChart, BarList, Sparkline, ShareBar, AgeingBar, useCountUp } from "./AccCharts.jsx";

const iso = (d) => d.toISOString().slice(0, 10);
const PRESETS = () => {
  const t = new Date(), today = isoToday();
  const d7 = new Date(t); d7.setDate(t.getDate() - 6);
  const lm1 = new Date(t.getFullYear(), t.getMonth() - 1, 1), lm2 = new Date(t.getFullYear(), t.getMonth(), 0);
  return [
    ["Today", today, today], ["7 days", iso(d7), today], ["This month", isoMonthStart(), today],
    ["Last month", iso(new Date(lm1.getTime() - lm1.getTimezoneOffset() * 60000)), iso(new Date(lm2.getTime() - lm2.getTimezoneOffset() * 60000))],
    ["This year", today.slice(0, 4) + "-01-01", today],
  ];
};

/* Which sections the viewer folded, remembered in this browser only. */
const FOLD_KEY = "acc.dash.folded";
const readFolds = () => { try { return new Set(JSON.parse(localStorage.getItem(FOLD_KEY) || "[]")); } catch { return new Set(); } };

function Section({ id, icon, title, hint, action, folds, onFold, children, className = "" }) {
  const folded = folds.has(id);
  return (
    <section className={`hr-card acc-card acc-section${folded ? " is-folded" : ""} ${className}`} aria-label={title}>
      <header className="acc-card-head">
        <div className="acc-card-title">
          <button type="button" className="acc-fold" aria-expanded={!folded} onClick={() => onFold(id)} title={folded ? "Show" : "Hide"}>
            <Ic path={P.chevronDown} size={14} className="acc-fold-caret" />
            <h3><Ic path={icon} size={15} /> {title}</h3>
          </button>
          {!folded && hint && <p className="acc-muted">{hint}</p>}
        </div>
        {action && <div className="acc-card-side">{action}</div>}
      </header>
      {!folded && children}
    </section>
  );
}

function HeroValue({ value, tone }) {
  const shown = useCountUp(value);
  return <span className={`acc-hero-value is-${tone}`}>{amount(shown)}</span>;
}

export default function AccDashboard({ onNavigate }) {
  const perm = usePermission("accounts");
  const [range, setRange] = useState({ from: isoMonthStart(), to: isoToday() });
  const [branchId, setBranchId] = useState(null);
  const [auto, setAuto] = useState(false);
  const [folds, setFolds] = useState(readFolds);
  const { data, busy, err, reload } = useAsync(() => getDashboard({ from: range.from, to: range.to, branchId }), [range.from, range.to, branchId]);
  const [stamp, setStamp] = useState(null);
  useEffect(() => { if (data) setStamp(new Date()); }, [data]);
  useEffect(() => { if (!auto) return; const id = setInterval(reload, 60000); return () => clearInterval(id); }, [auto, reload]);
  const toggleFold = (id) => setFolds((f) => { const next = new Set(f); if (next.has(id)) next.delete(id); else next.add(id); try { localStorage.setItem(FOLD_KEY, JSON.stringify([...next])); } catch { /* per-viewer convenience only */ } return next; });

  const k = data?.kpi || {}, pv = data?.previous || {}, c = data?.control || {}, cash = data?.todayCash, ag = data?.ageing || {};
  const go = (page, intent) => { if (intent) sendIntent(page, intent); onNavigate?.(page); };
  const ledgerByRole = (role) => go("accledger", { role, from: range.from, to: range.to, branchId });
  const report = (id) => go("accreports", { report: id, from: range.from, to: range.to, branchId });

  const attention = useMemo(() => [
    [c.failedEvents, "failed event", "bad", () => go("accreconcile", { tab: "events", status: "FAILED" })],
    [c.unclosedDays, "unclosed day", "bad", () => go("accdailycash")],
    [c.draftJournals, "draft journal", "warn", () => go("accjournals", { status: "DRAFT" })],
    [c.pendingDistributions, "distribution pending", "warn", () => go("accprofit")],
    [c.openPeriods, "past period open", "warn", () => go("accsettings", { tab: "periods" })],
    [c.skippedEvents, "skipped event", "warn", () => go("accreconcile", { tab: "events", status: "SKIPPED" })],
  ].filter(([n]) => Number(n) > 0), [c]); // eslint-disable-line react-hooks/exhaustive-deps

  if (busy && !data) return <HrPageSkeleton label="Reading the ledger…" kpis={6} rows={4} />;
  if (err && !data) return <HrErrorState message={err} onRetry={reload} />;

  const netTone = Number(k.netProfit) < 0 ? "red" : "sage";
  const revenue = Number(k.revenue) || 0, cogs = Number(k.costOfSales) || 0, expenses = Number(k.expenses) || 0, net = Number(k.netProfit) || 0;
  const margin = revenue ? (net / revenue) * 100 : null;
  const cashRows = [
    { id: "CASH_REGISTER", name: "Cash in till", amount: k.cash }, { id: "BANK_DEFAULT", name: "Bank", amount: k.bank },
    { id: "MOBILE_WALLET", name: "Mobile wallets", amount: k.mobileWallet }, { id: "PETTY_CASH", name: "Petty cash", amount: k.pettyCash },
    { id: "CASH_IN_TRANSIT", name: "Cash in transit", amount: k.cashInTransit },
  ].filter((r) => Number(r.amount) !== 0);
  const liquid = ["cash", "bank", "mobileWallet", "pettyCash"].reduce((t, key) => t + (Number(k[key]) || 0), 0);
  const trend = data.trend || [], prevTrend = data.previousTrend || [];
  const netTrend = trend.map((r) => ({ date: r.date, net: (Number(r.revenue) || 0) - (Number(r.expense) || 0) }));
  const todayInRange = range.from <= isoToday() && range.to >= isoToday();
  const todayRow = todayInRange ? (trend.find((r) => String(r.date).slice(0, 10) === isoToday()) || { revenue: 0, cashIn: 0, cashOut: 0 }) : null;
  const dim = busy ? " is-refreshing" : "";

  return (
    <HrPage className="acc-page acc-dash" icon={AI.dashboard} crumbs={[ACC_CRUMB]} title="Accounts Dashboard"
            subtitle={<>Ledger figures for {fmtDate(range.from)} – {fmtDate(range.to)}; balances as at {fmtDate(range.to)}.{stamp && <span className="acc-stamp"> Updated {fmtDateTime(stamp)}</span>}</>}
            actions={<div className="acc-actions">
              {perm.canCreate && <HrButton icon={P.plus} onClick={() => go("accjournals", { action: "new" })}>Manual journal</HrButton>}
              <HrButton icon={AI.reports} onClick={() => go("accreports")}>Reports</HrButton>
              <button type="button" className={`btn btn-sm acc-auto${auto ? " is-on" : ""}`} aria-pressed={auto} onClick={() => setAuto((a) => !a)} title="Refresh every minute"><span className="acc-auto-dot" />{auto ? "Live" : "Auto-refresh"}</button>
              <HrButton icon={P.reset} onClick={reload} busy={busy} aria-label="Refresh">Refresh</HrButton>
            </div>}>
      <HrFilterBar>
        <div className="hr-filter"><label>Range</label>
          <div className="acc-quick" role="group" aria-label="Quick ranges">
            {PRESETS().map(([l, f, t]) => <button key={l} type="button" className={`btn btn-sm ${range.from === f && range.to === t ? "btn-primary" : "btn-ghost"}`} aria-pressed={range.from === f && range.to === t} onClick={() => setRange({ from: f, to: t })}>{l}</button>)}
          </div>
        </div>
        <DateRange from={range.from} to={range.to} onChange={setRange} />
        <BranchPicker value={branchId} onChange={setBranchId} allowAll />
      </HrFilterBar>

      {/* ── Today strip: what has happened so far today, if today is in the window ── */}
      {todayRow && (
        <div className={`acc-today${dim}`} role="group" aria-label="Today so far">
          <span className="acc-today-label"><Ic path={AI.clock} size={13} /> Today so far</span>
          <button type="button" onClick={() => ledgerByRole("SALES_REVENUE")}><span>Revenue</span><b>{amount(todayRow.revenue)}</b></button>
          <button type="button" onClick={() => ledgerByRole("CASH_REGISTER")}><span>Cash in</span><b>{amount(todayRow.cashIn)}</b></button>
          <button type="button" onClick={() => ledgerByRole("CASH_REGISTER")}><span>Cash out</span><b>{amount(todayRow.cashOut)}</b></button>
          {cash && <button type="button" onClick={() => go("accdailycash")}><span>Till</span><b><Status code={cash.status} /></b></button>}
        </div>
      )}

      {/* ── Hero ── */}
      <section className={`acc-hero${dim}`} aria-label="Result for the period">
        <div className="acc-hero-main">
          <span className="acc-hero-label"><Ic path={net < 0 ? AI.trendDown : AI.trendUp} size={14} /> Net {net < 0 ? "loss" : "profit"} · {fmtDate(range.from)} – {fmtDate(range.to)}</span>
          <HeroValue value={net} tone={netTone} />
          <div className="acc-hero-sub">
            <Delta now={k.netProfit} before={pv.netProfit} label={`${fmtDate(pv.from)} – ${fmtDate(pv.to)}`} />
            {margin != null && <span className="acc-hero-margin" title="Net profit ÷ revenue">{pct(margin, 1)} margin</span>}
          </div>
          <div className="acc-hero-spark"><Sparkline rows={netTrend} from={range.from} to={range.to} seriesKey="net" points={31} tone={net < 0 ? 4 : 1} /><span className="acc-muted">net by day</span></div>
          <div className="acc-hero-share">
            <span className="acc-hero-share-label">Where each ৳100 of revenue went</span>
            <ShareBar ariaLabel="Revenue split" total={revenue > 0 ? revenue : undefined}
              parts={[{ key: "cogs", label: "Cost of sales", value: cogs, tone: 2 }, { key: "opex", label: "Other expenses", value: expenses - cogs, tone: 4 }, { key: "net", label: "Net profit", value: net, tone: 1 }]}
              onPick={(p) => (p.key === "cogs" ? ledgerByRole("COGS") : p.key === "opex" ? report("acc-expense-analysis") : report("acc-profit-loss"))} />
          </div>
        </div>
        <Kpis>
          <Kpi icon={AI.receipt} label="Revenue" value={compact(k.revenue)} tone="teal" note={<><Delta now={k.revenue} before={pv.revenue} /><Sparkline rows={trend} from={range.from} to={range.to} seriesKey="revenue" /></>} onClick={() => ledgerByRole("SALES_REVENUE")} />
          <Kpi icon={AI.box} label="Cost of sales" value={compact(k.costOfSales)} note={<Delta now={k.costOfSales} before={pv.costOfSales} upIsGood={false} />} onClick={() => ledgerByRole("COGS")} />
          <Kpi icon={AI.percent} label="Gross profit" value={compact(k.grossProfit)} tone={Number(k.grossProfit) < 0 ? "red" : "sage"} note={<><Delta now={k.grossProfit} before={pv.grossProfit} />{revenue ? <span className="acc-muted">{pct(((Number(k.grossProfit) || 0) / revenue) * 100, 1)} of revenue</span> : null}</>} onClick={() => report("acc-profit-loss")} />
          <Kpi icon={AI.tag} label="Expenses" value={compact(k.expenses)} note={<><Delta now={k.expenses} before={pv.expenses} upIsGood={false} /><Sparkline rows={trend} from={range.from} to={range.to} seriesKey="expense" tone={2} /></>} onClick={() => report("acc-expense-analysis")} />
        </Kpis>
      </section>

      {/* ── Trend ── */}
      <div className={`acc-two${dim}`}>
        <Section id="rev" icon={AI.trendUp} title="Revenue and expenses" hint="By day. Click a legend item to hide it; Compare overlays the previous window." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => report("acc-daily-summary")}>Daily summary</HrButton>}>
          <TrendChart rows={trend} previous={prevTrend} from={range.from} to={range.to} prevFrom={pv.from} prevTo={pv.to} title="Revenue and expenses by day" series={[{ key: "revenue", label: "Revenue" }, { key: "expense", label: "Expenses (incl. cost of sales)" }]} />
        </Section>
        <Section id="cash" icon={AI.wallet} title="Cash movement" hint="Into and out of till, bank, wallets and petty cash." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => report("acc-cash-flow")}>Cash flow</HrButton>}>
          <TrendChart rows={trend} previous={prevTrend} from={range.from} to={range.to} prevFrom={pv.from} prevTo={pv.to} title="Cash in and out by day" series={[{ key: "cashIn", label: "Cash in" }, { key: "cashOut", label: "Cash out" }]} />
        </Section>
      </div>

      {/* ── Position ── */}
      <section aria-label="Position" className={dim}>
        <h3 className="acc-h3"><Ic path={AI.scale} size={15} /> Position as at {fmtDate(range.to)} <span className="acc-muted">— click a tile to open its ledger</span></h3>
        <Kpis>
          <Kpi icon={AI.parties} label="Receivable" value={compact(k.receivable)} note={ag.customers ? `${Number(ag.customers.parties) || 0} customers owe` : "customers owe"} onClick={() => go("accparties", { tab: "CUSTOMER" })} />
          <Kpi icon={AI.truck} label="Payable" value={compact(k.payable)} note={ag.suppliers ? `we owe ${Number(ag.suppliers.parties) || 0} suppliers` : "we owe suppliers"} onClick={() => go("accparties", { tab: "SUPPLIER" })} />
          <Kpi icon={AI.coins} label="Liquid cash" value={compact(liquid)} tone="teal" note="till + bank + wallets + petty" onClick={() => go("acccash")} />
          <Kpi icon={AI.till} label="Cash in till" value={compact(k.cash)} onClick={() => ledgerByRole("CASH_REGISTER")} />
          <Kpi icon={AI.bank} label="Bank" value={compact(k.bank)} onClick={() => ledgerByRole("BANK_DEFAULT")} />
          <Kpi icon={AI.phone} label="Mobile wallets" value={compact(k.mobileWallet)} onClick={() => ledgerByRole("MOBILE_WALLET")} />
          <Kpi icon={AI.box} label="Inventory" value={compact(k.inventory)} warn={Number(k.inventory) < 0} note={Number(k.inventory) < 0 ? "negative — opening stock not yet booked" : undefined} onClick={() => ledgerByRole("INVENTORY")} />
          <Kpi icon={AI.receipt} label="Payroll payable" value={compact(k.payrollPayable)} onClick={() => ledgerByRole("SALARY_PAYABLE")} />
          <Kpi icon={AI.investors} label="Investor capital" value={compact(k.investorCapital)} onClick={() => go("accinvestors")} />
          <Kpi icon={AI.profit} label="Investor profit payable" value={compact(k.investorProfitPayable)} onClick={() => go("accprofit")} />
          <Kpi icon={AI.percent} label="Tax payable" value={compact(k.taxPayable)} onClick={() => ledgerByRole("TAX_PAYABLE")} />
          <Kpi icon={AI.truck} label="Cash in transit" value={compact(k.cashInTransit)} note="with delivery men" onClick={() => ledgerByRole("CASH_IN_TRANSIT")} />
        </Kpis>
      </section>

      {/* ── Ageing + branches ── */}
      <div className={`acc-two${dim}`}>
        <Section id="ageing" icon={AI.clock} title="How old the balances are" hint="Light is current, dark is overdue. Click a segment to open that list." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => report("acc-ar-ageing")}>Ageing report</HrButton>}>
          <div className="acc-ageing-grid">
            <AgeingBar label="Customers owe" buckets={ag.customers} onPick={() => go("accparties", { tab: "CUSTOMER" })} />
            <AgeingBar label="We owe suppliers" buckets={ag.suppliers} onPick={() => go("accparties", { tab: "SUPPLIER" })} />
          </div>
        </Section>
        {data.branches ? (
          <Section id="branches" icon={AI.layers} title="Branch by branch" hint="Net profit for the window per branch. Click a branch to focus the whole dashboard on it." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => report("acc-branch-pnl")}>Branch P&amp;L</HrButton>}>
            <BarList rows={data.branches.map((b) => ({ id: b.branchId, name: b.branchName, amount: b.netProfit }))} onPick={(r) => setBranchId(r.id)} selectedId={branchId} empty="No branch has movement in this window." />
            {data.branches.length > 0 && (
              <div className="hr-table-wrap"><table className="hr-table acc-mini">
                <thead><tr><th scope="col">Branch</th><th scope="col" className="hr-num">Revenue</th><th scope="col" className="hr-num">Expenses</th><th scope="col" className="hr-num">Net</th><th scope="col" className="hr-num">Till</th></tr></thead>
                <tbody>{data.branches.map((b) => <tr key={b.branchId}><td>{b.branchName}</td><td className="hr-num">{amount(b.revenue)}</td><td className="hr-num">{amount(b.expenses)}</td><td className={`hr-num${Number(b.netProfit) < 0 ? " acc-neg" : ""}`}>{amount(b.netProfit)}</td><td className="hr-num">{amount(b.cash)}</td></tr>)}</tbody>
              </table></div>
            )}
          </Section>
        ) : (
          <Section id="expenses" icon={AI.tag} title="Where the money went" hint="Largest expense accounts in the period. Click to open the ledger." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => report("acc-expense-analysis")}>Expense analysis</HrButton>}>
            <BarList rows={(data.expenseTop || []).map((r) => ({ id: r.accountId, name: `${r.code} ${r.name}`, amount: r.amount }))} onPick={(r) => go("accledger", { accountId: r.id, from: range.from, to: range.to, branchId })} empty="No expense posted in this range." />
          </Section>
        )}
      </div>

      {/* ── Cash by account + expenses (when branches took the slot) ── */}
      <div className={`acc-two${dim}`}>
        <Section id="cashwhere" icon={AI.coins} title="Where the cash is" hint={`Balance per cash account as at ${fmtDate(range.to)}. Click to open the ledger.`} folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => go("acccash")}>Cash &amp; Bank</HrButton>}>
          <BarList rows={cashRows} onPick={(r) => ledgerByRole(r.id)} empty="No cash account has a balance yet." />
        </Section>
        {data.branches ? (
          <Section id="expenses" icon={AI.tag} title="Where the money went" hint="Largest expense accounts in the period. Click to open the ledger." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => report("acc-expense-analysis")}>Expense analysis</HrButton>}>
            <BarList rows={(data.expenseTop || []).map((r) => ({ id: r.accountId, name: `${r.code} ${r.name}`, amount: r.amount }))} onPick={(r) => go("accledger", { accountId: r.id, from: range.from, to: range.to, branchId })} empty="No expense posted in this range." />
          </Section>
        ) : (
          cash && <DailyCashCard cash={cash} go={go} folds={folds} onFold={toggleFold} />
        )}
      </div>

      {/* ── Daily cash (all-branches layout) + control centre ── */}
      <div className="acc-two">
        {data.branches && cash ? <DailyCashCard cash={cash} go={go} folds={folds} onFold={toggleFold} /> : null}
        <Section id="control" icon={AI.alert} title="Control centre" hint="What needs a person. Click a row to go there." folds={folds} onFold={toggleFold}
                 action={<span className={`acc-health${attention.length ? (attention.some(([, , tone]) => tone === "bad") ? " is-bad" : " is-warn") : " is-ok"}`} title={attention.length ? attention.map(([n, l]) => `${n} ${l}${Number(n) === 1 ? "" : "s"}`).join(" · ") : "Nothing needs attention"}>
                   <Ic path={attention.length ? AI.alert : AI.check} size={13} /> {attention.length ? `${attention.length} need attention` : "All clear"}</span>}>
          <ul className="acc-control">
            {[
              [c.failedEvents, "Failed accounting events", "bad", () => go("accreconcile", { tab: "events", status: "FAILED" }), "Open"],
              [c.skippedEvents, "Skipped events", "warn", () => go("accreconcile", { tab: "events", status: "SKIPPED" }), "Open"],
              [c.draftJournals, "Draft journals", "warn", () => go("accjournals", { status: "DRAFT" }), "Open"],
              [c.unclosedDays, "Unclosed branch days", "bad", () => go("accdailycash"), "Open"],
              [c.pendingDistributions, "Profit distributions pending", "warn", () => go("accprofit"), "Open"],
              [c.openPeriods, "Past periods still open", "warn", () => go("accsettings", { tab: "periods" }), "Close"],
            ].map(([n, label, tone, act, verb]) => (
              <li key={label} className={Number(n) ? `is-${tone}` : ""}><button type="button" className="acc-control-row" onClick={act}><span>{label}</span><b>{n ?? 0}</b><em>{verb}</em></button></li>
            ))}
            <li><button type="button" className="acc-control-row" onClick={() => report("acc-cash-difference")}><span>Cash short / over (period)</span><b>{amount(c.cashShortages)} / {amount(c.cashOverages)}</b><em>Report</em></button></li>
            <li><button type="button" className="acc-control-row" onClick={() => go("accsettings", { tab: "periods" })}><span>Current period</span><b>{c.currentPeriod ? <>{c.currentPeriod.name} <Status code={c.currentPeriod.status} /></> : "—"}</b><em>Periods</em></button></li>
          </ul>
        </Section>
      </div>

      <div className="acc-two">
        <Section id="journals" icon={AI.journal} title="Recent journals" hint="Last seven days. Click a row to open it." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => go("accjournals")}>All journals</HrButton>}>
          <HrTable rowKey={(j) => j.pidJournal} rows={data.recentJournals || []} empty="No journal in the last seven days." onRowClick={(j) => go("accjournals", { journalId: j.pidJournal })}
            cols={[
              { key: "journalNo", label: "Journal", lead: true, render: (j) => <><b>{j.journalNo}</b> <span className="acc-muted">{fmtDate(j.journalDate)}</span><div className="acc-muted acc-ellipsis" title={j.description}>{j.description}</div><Source module={j.sourceModule} type={j.sourceType} /></> },
              { key: "totalDebit", label: "Amount", num: true, render: (j) => amount(j.totalDebit) },
              { key: "journalStatus", label: "Status", render: (j) => <Status code={j.journalStatus} /> },
            ]} />
        </Section>
        <Section id="balances" icon={AI.parties} title="Largest balances" hint="Receivables and payables, from the ledger. Click a name for the statement." folds={folds} onFold={toggleFold} action={<HrButton size="sm" onClick={() => go("accparties")}>Receivables &amp; Payables</HrButton>}>
          <div className="acc-two-lists">
            <div><h4>Customers owe</h4><BarList rows={(data.receivablesTop || []).map((r) => ({ id: r.partyId, name: r.partyName, amount: r.balance }))} onPick={(r) => go("accparties", { tab: "CUSTOMER", partyId: r.id })} empty="Nothing outstanding." /></div>
            <div><h4>We owe suppliers</h4><BarList rows={(data.payablesTop || []).map((r) => ({ id: r.partyId, name: r.partyName, amount: r.balance }))} onPick={(r) => go("accparties", { tab: "SUPPLIER", partyId: r.id })} empty="Nothing outstanding." /></div>
          </div>
        </Section>
      </div>
    </HrPage>
  );
}

function DailyCashCard({ cash, go, folds, onFold }) {
  return (
    <Section id="daily" icon={AI.till} title="Branch daily cash" hint={`${cash.branchName} · ${fmtDate(cash.businessDate)}`} folds={folds} onFold={onFold} action={<Status code={cash.status} />}>
      <div className="acc-figures">
        <Figure label="Previous closing" value={amount(cash.previousClosing)} hint={cash.previousDate ? fmtDate(cash.previousDate) : "none yet"} />
        <Figure label="Today's opening" value={amount(cash.openingCash)} />
        <Figure label="Cash in" value={amount(cash.cashIn)} tone="sage" />
        <Figure label="Cash out" value={amount(cash.cashOut)} tone="red" />
        <Figure label="Expected cash" value={amount(cash.expectedClosing)} big />
        <Figure label="Physical cash" value={amount(cash.physicalCash)} big />
        <Figure label="Difference" value={cash.difference == null ? "—" : variance(cash.difference)} tone={cash.differenceKind === "SHORT" ? "red" : cash.differenceKind === "OVER" ? "amber" : "sage"} />
      </div>
      <div className="acc-actions">
        {cash.canOpen && <HrButton variant="primary" icon={AI.play} onClick={() => go("accdailycash")}>Open day</HrButton>}
        {cash.canClose && <HrButton variant="primary" icon={AI.check} onClick={() => go("accdailycash")}>Close day</HrButton>}
        <HrButton icon={AI.history} onClick={() => go("accdailycash", { tab: "book" })}>Cash book</HrButton>
      </div>
    </Section>
  );
}
