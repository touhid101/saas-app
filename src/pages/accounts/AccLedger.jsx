/**
 * AccLedger — one account's ledger with a running balance, and the trial balance.
 */
import React, { useEffect, useMemo, useState } from "react";
import { usePermission } from "../../lib/usePermission.js";
import { HrPage, HrTable, HrFilterBar, Filter, HrSearch, HrButton, HrTabBar, HrPagination, Status, humanError, HrErrorState, P } from "../../components/HrKit.jsx";
import { getChart, getLedger, getTrialBalance } from "../../services/accountsService.js";
import { amount, drcr, fmtDate, isoToday, isoMonthStart } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, DateRange, BranchPicker, Source, Figure, takeIntent, sendIntent } from "./AccKit.jsx";
import { downloadCSV } from "../../utils/csv.js";
const exportCsv = (name, rows) => downloadCSV(name, rows, rows.length ? Object.keys(rows[0]) : []);

export default function AccLedger({ onNavigate }) {
  const perm = usePermission("accledger");
  // A tile on the dashboard may have sent us here with an account, a range and a branch.
  const [intent, setIntent] = useState(() => takeIntent("accledger"));
  const openAccount = (accountId) => { setIntent({ accountId, at: Date.now() }); setTab("ledger"); };
  const [tab, setTab] = useState(intent?.tab || "ledger");
  const [chart, setChart] = useState([]);
  const [range, setRange] = useState({ from: intent?.from || isoMonthStart(), to: intent?.to || isoToday() });
  const [branchId, setBranchId] = useState(intent?.branchId ?? null);
  useEffect(() => { getChart().then((c) => setChart(c.filter((a) => a.isGroup === 0))).catch(() => {}); }, []);
  return (
    <HrPage className="acc-page" icon={AI.ledger} crumbs={[ACC_CRUMB]} title="General Ledger" subtitle="Every posted line, account by account, with a running balance. The trial balance proves the books: closing debits equal closing credits.">
      <AccTabs tabs={[{ id: "ledger", label: "Ledger", icon: AI.ledger }, { id: "tb", label: "Trial balance", icon: AI.scale }]} active={tab} onChange={setTab} />
      {tab === "ledger" ? <LedgerTab intent={intent} chart={chart} range={range} setRange={setRange} branchId={branchId} setBranchId={setBranchId} perm={perm} onNavigate={onNavigate} />
                        : <TrialBalanceTab range={range} setRange={setRange} branchId={branchId} setBranchId={setBranchId} perm={perm} onOpenAccount={openAccount} />}
    </HrPage>
  );
}

function LedgerTab({ intent, chart, range, setRange, branchId, setBranchId, perm, onNavigate }) {
  const [accountId, setAccountId] = useState(intent?.accountId ? String(intent.accountId) : "");
  const [q, setQ] = useState("");
  const [source, setSource] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  useEffect(() => { if (intent?.accountId) { setAccountId(String(intent.accountId)); setPage(0); } }, [intent]);
  useEffect(() => {
    if (accountId || !chart.length) return;
    const wanted = chart.find((a) => a.roleKey === (intent?.role || "CASH_REGISTER")) || chart[0];
    setAccountId(String(wanted.pidAccount));
  }, [chart, accountId, intent]);
  useEffect(() => {
    if (!accountId) return;
    let alive = true; setBusy(true); setErr(null);
    getLedger({ accountId, from: range.from, to: range.to, branchId, q, source, page, size })
      .then((d) => alive && setData(d)).catch((e) => alive && setErr(humanError(e))).finally(() => alive && setBusy(false));
    return () => { alive = false; };
  }, [accountId, range.from, range.to, branchId, q, source, page, size]);
  const account = chart.find((a) => String(a.pidAccount) === String(accountId));
  const cols = useMemo(() => [
    { key: "date", label: "Date", sortable: true, render: (r) => fmtDate(r.date) },
    { key: "journalNo", label: "Journal", lead: true, sortable: true, render: (r) => <><b>{r.journalNo}</b>{r.journalStatus === "REVERSED" && <> <Status code="REVERSED" /></>}</> },
    { key: "reference", label: "Reference" },
    { key: "description", label: "Description", render: (r) => <>{r.description}{r.partyName && <div className="acc-muted">{r.partyName}</div>}</> },
    { key: "source", label: "Source", render: (r) => <Source module={r.sourceModule} type={r.sourceType} /> },
    { key: "debit", label: "Debit", num: true, sortable: true, sortValue: (r) => Number(r.debit), render: (r) => drcr(r.debit) },
    { key: "credit", label: "Credit", num: true, sortable: true, sortValue: (r) => Number(r.credit), render: (r) => drcr(r.credit) },
    { key: "balance", label: "Balance", num: true, render: (r) => amount(r.balance) },
  ], []);
  const rows = data?.rows || [];
  const doExport = () => exportCsv(`ledger-${account?.accountCode || accountId}-${range.from}-${range.to}.csv`, rows.map((r) => ({
    Date: r.date, Journal: r.journalNo, Reference: r.reference, Description: r.description, Source: r.sourceModule, Debit: r.debit, Credit: r.credit, Balance: r.balance })));
  return (
    <>
      <HrFilterBar>
        <Filter label="Account" grow>
          <select value={accountId} onChange={(e) => { setAccountId(e.target.value); setPage(0); }}>
            {chart.map((a) => <option key={a.pidAccount} value={a.pidAccount}>{a.accountCode} {a.accountName}</option>)}
          </select>
        </Filter>
        <DateRange from={range.from} to={range.to} onChange={(r) => { setRange(r); setPage(0); }} />
        <BranchPicker value={branchId} onChange={(b) => { setBranchId(b); setPage(0); }} allowAll />
        <Filter label="Source"><select value={source} onChange={(e) => { setSource(e.target.value); setPage(0); }}>
          <option value="">Any</option>{["SALES", "PURCHASE", "SUPPLIER_PAYMENT", "COLLECTION", "HR", "CASH", "INVESTOR", "MANUAL", "DAILY_CASH", "SUBSCRIPTION"].map((s) => <option key={s}>{s}</option>)}</select></Filter>
        <Filter label="Search" grow><HrSearch value={q} onChange={(v) => { setQ(v); setPage(0); }} placeholder="Journal, reference, party…" /></Filter>
      </HrFilterBar>
      {data && (
        <div className="acc-figures acc-figures-row">
          <Figure label={`Opening ${fmtDate(range.from)}`} value={amount(data.openingBalance)} />
          <Figure label="Debits" value={amount(data.totalDebit)} tone="sage" />
          <Figure label="Credits" value={amount(data.totalCredit)} tone="red" />
          <Figure label={`Closing ${fmtDate(range.to)}`} value={amount(data.closingBalance)} big hint={account ? `${account.normalSide === "D" ? "debit" : "credit"}-normal account` : ""} />
          <div className="acc-figure-actions">{perm.canExport && <HrButton icon={P.download} onClick={doExport} disabled={!rows.length}>Export CSV</HrButton>}</div>
        </div>
      )}
      {err ? <HrErrorState hint={err} /> : (
        <>
          <HrTable cols={cols} rows={rows} rowKey={(r, i) => `${r.journalId}-${i}`} loading={busy && !data} empty="No posting on this account in the window." onRowClick={(r) => { sendIntent("accjournals", { journalId: r.journalId }); onNavigate?.("accjournals"); }} />
          <HrPagination page={page} size={size} total={data?.rowCount || 0} onPage={setPage} onSize={setSize} sizes={[50, 100, 250]} />
        </>
      )}
    </>
  );
}

function TrialBalanceTab({ range, setRange, branchId, setBranchId, perm, onOpenAccount }) {
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(true);
  const [err, setErr] = useState(null);
  const load = () => { setBusy(true); setErr(null); getTrialBalance({ from: range.from, to: range.to, branchId }).then(setRows).catch((e) => setErr(humanError(e))).finally(() => setBusy(false)); };
  useEffect(load, [range.from, range.to, branchId]); // eslint-disable-line react-hooks/exhaustive-deps
  const totals = rows.reduce((t, r) => ({ d: t.d + Number(r.debit), c: t.c + Number(r.credit), cd: t.cd + Number(r.closingDebit), cc: t.cc + Number(r.closingCredit) }), { d: 0, c: 0, cd: 0, cc: 0 });
  const balanced = Math.abs(totals.cd - totals.cc) < 0.005;
  const cols = [
    { key: "code", label: "Code", lead: true, sortable: true, render: (r) => <b>{r.code}</b> },
    { key: "name", label: "Account", sortable: true }, { key: "type", label: "Type", sortable: true, render: (r) => <span className="acc-type">{r.type}</span> },
    { key: "opening", label: "Opening", num: true, sortable: true, sortValue: (r) => Number(r.opening), render: (r) => amount(r.opening) },
    { key: "debit", label: "Debit", num: true, sortable: true, sortValue: (r) => Number(r.debit), render: (r) => drcr(r.debit) },
    { key: "credit", label: "Credit", num: true, sortable: true, sortValue: (r) => Number(r.credit), render: (r) => drcr(r.credit) },
    { key: "closingDebit", label: "Closing Dr", num: true, sortable: true, sortValue: (r) => Number(r.closingDebit), render: (r) => drcr(r.closingDebit) },
    { key: "closingCredit", label: "Closing Cr", num: true, sortable: true, sortValue: (r) => Number(r.closingCredit), render: (r) => drcr(r.closingCredit) },
  ];
  return (
    <>
      <HrFilterBar>
        <DateRange from={range.from} to={range.to} onChange={setRange} />
        <BranchPicker value={branchId} onChange={setBranchId} allowAll />
        {perm.canExport && <div className="hr-filter"><label>&nbsp;</label><HrButton icon={P.download} onClick={() => exportCsv(`trial-balance-${range.to}.csv`, rows)} disabled={!rows.length}>Export CSV</HrButton></div>}
      </HrFilterBar>
      <div className="acc-figures acc-figures-row">
        <Figure label="Movement debits" value={amount(totals.d)} />
        <Figure label="Movement credits" value={amount(totals.c)} />
        <Figure label="Closing debits" value={amount(totals.cd)} big />
        <Figure label="Closing credits" value={amount(totals.cc)} big />
        <Figure label="Books" value={balanced ? "BALANCED" : `OUT BY ${amount(Math.abs(totals.cd - totals.cc))}`} tone={balanced ? "sage" : "red"} />
      </div>
      {err ? <HrErrorState hint={err} onRetry={load} /> : <HrTable cols={cols} rows={rows} rowKey={(r) => r.accountId} loading={busy} empty="Nothing has been posted in this window." onRowClick={(r) => onOpenAccount(r.accountId)} />}
    </>
  );
}
