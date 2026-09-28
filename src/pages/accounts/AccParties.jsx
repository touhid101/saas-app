/**
 * AccParties — receivables and payables from the ledger: balances, ageing
 * buckets, and a statement per customer or supplier.
 */
import React, { useEffect, useMemo, useState } from "react";
import { usePermission } from "../../lib/usePermission.js";
import { HrPage, HrTable, HrFilterBar, Filter, HrSearch, HrTabBar, HrDrawer, Kpis, Kpi, humanError, HrErrorState, Facts, Fact } from "../../components/HrKit.jsx";
import { getParties, getStatement } from "../../services/accountsService.js";
import { amount, drcr, compact, fmtDate, isoToday } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, BranchPicker, DateRange, Source, takeIntent } from "./AccKit.jsx";

export default function AccParties() {
  const perm = usePermission("accparties");
  const [intent] = useState(() => takeIntent("accparties"));
  const [tab, setTab] = useState(intent?.tab || "CUSTOMER");
  const [asOf, setAsOf] = useState(isoToday());
  const [branchId, setBranchId] = useState(null);
  const [q, setQ] = useState("");
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(true);
  const [err, setErr] = useState(null);
  const [open, setOpen] = useState(null);
  useEffect(() => {
    let alive = true; setBusy(true); setErr(null);
    getParties(tab, { asOf, branchId, ageing: true }).then((d) => { if (!alive) return; setRows(d); if (intent?.partyId && intent.tab === tab) { const hit = d.find((r) => String(r.partyId) === String(intent.partyId)); if (hit) setOpen(hit); } }).catch((e) => alive && setErr(humanError(e))).finally(() => alive && setBusy(false));
    return () => { alive = false; };
  }, [tab, asOf, branchId]);
  const shown = useMemo(() => rows.filter((r) => !q || (r.partyName || "").toLowerCase().includes(q.toLowerCase())), [rows, q]);
  const total = shown.reduce((t, r) => t + Number(r.outstanding || 0), 0);
  const b = (k) => shown.reduce((t, r) => t + Number(r[k] || 0), 0);
  const who = tab === "CUSTOMER" ? "customer" : tab === "SUPPLIER" ? "supplier" : tab.toLowerCase();
  const cols = [
    { key: "partyName", label: tab === "CUSTOMER" ? "Customer" : tab === "SUPPLIER" ? "Supplier" : tab === "EMPLOYEE" ? "Employee" : "Tenant", lead: true, sortable: true, render: (r) => <b>{r.partyName || `#${r.partyId}`}</b> },
    { key: "outstanding", label: "Outstanding", num: true, sortable: true, sortValue: (r) => Number(r.outstanding), render: (r) => <b>{amount(r.outstanding)}</b> },
    { key: "d0_30", label: "0–30 days", num: true, sortable: true, sortValue: (r) => Number(r.d0_30), render: (r) => drcr(r.d0_30) },
    { key: "d31_60", label: "31–60", num: true, sortable: true, sortValue: (r) => Number(r.d31_60), render: (r) => drcr(r.d31_60) },
    { key: "d61_90", label: "61–90", num: true, sortable: true, sortValue: (r) => Number(r.d61_90), render: (r) => <span className={Number(r.d61_90) > 0 ? "acc-aged" : ""}>{drcr(r.d61_90)}</span> },
    { key: "d90plus", label: "Over 90", num: true, sortable: true, sortValue: (r) => Number(r.d90plus), render: (r) => <span className={Number(r.d90plus) > 0 ? "acc-aged is-bad" : ""}>{drcr(r.d90plus)}</span> },
    { key: "lastActivity", label: "Last activity", sortable: true, render: (r) => fmtDate(r.lastActivity) },
    { key: "actions", label: "", actions: true, render: () => <span className="acc-row-hint">Statement ›</span> },
  ];
  return (
    <HrPage className="acc-page" icon={AI.parties} crumbs={[ACC_CRUMB]} title="Receivables & Payables" subtitle="What customers owe and what suppliers are owed, read from the ledger by party and aged against their most recent invoices.">
      <AccTabs tabs={[{ id: "CUSTOMER", label: "Receivables", icon: AI.parties }, { id: "SUPPLIER", label: "Payables", icon: AI.truck }, { id: "EMPLOYEE", label: "Staff", icon: AI.investors }, { id: "TENANT", label: "Tenants", icon: AI.bank }]} active={tab} onChange={(t) => { setTab(t); setQ(""); }} />
      <Kpis>
        <Kpi icon={AI.scale} label={`Total ${tab === "CUSTOMER" ? "receivable" : tab === "SUPPLIER" ? "payable" : "balance"}`} value={compact(total)} tone="teal" />
        <Kpi icon={AI.clock} label="0–30 days" value={compact(b("d0_30"))} note="current" />
        <Kpi icon={AI.clock} label="31–60 days" value={compact(b("d31_60"))} />
        <Kpi icon={AI.alert} label="61–90 days" value={compact(b("d61_90"))} warn={b("d61_90") > 0} />
        <Kpi icon={AI.alert} label="Over 90 days" value={compact(b("d90plus"))} warn={b("d90plus") > 0} tone="red" note={b("d90plus") > 0 ? "needs chasing" : undefined} />
      </Kpis>
      <HrFilterBar>
        <Filter label="Search" grow><HrSearch value={q} onChange={setQ} delay={0} placeholder={`Find a ${who}…`} /></Filter>
        <Filter label="As at"><input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></Filter>
        <BranchPicker value={branchId} onChange={setBranchId} allowAll />
      </HrFilterBar>
      {err ? <HrErrorState hint={err} /> : <HrTable cols={cols} rows={shown} rowKey={(r) => r.partyId} loading={busy} onRowClick={(r) => setOpen(r)} empty={`No open ${who} balance.`} caption={`${shown.length} ${who}(s) with a balance`} />}
      {open && <StatementDrawer partyType={tab} party={open} branchId={branchId} onClose={() => setOpen(null)} />}
      {!perm.canView && null}
    </HrPage>
  );
}

function StatementDrawer({ partyType, party, branchId, onClose }) {
  const [range, setRange] = useState({ from: isoToday().slice(0, 4) + "-01-01", to: isoToday() });
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  useEffect(() => { setD(null); getStatement(partyType, party.partyId, { from: range.from, to: range.to, branchId }).then(setD).catch((e) => setErr(humanError(e))); }, [partyType, party.partyId, range.from, range.to, branchId]);
  return (
    <HrDrawer title={party.partyName || `#${party.partyId}`} subtitle={`${partyType.toLowerCase()} statement`} onClose={onClose} width={720}>
      <HrFilterBar><DateRange from={range.from} to={range.to} onChange={setRange} /></HrFilterBar>
      {err && <HrErrorState hint={err} />}
      {d && (
        <>
          <Facts><Fact label="Opening balance" value={amount(d.openingBalance)} /><Fact label={`Closing balance · ${balanceNote(partyType, d.closingBalance)}`} value={amount(d.closingBalance)} /></Facts>
          {partyType === "SUPPLIER" && <p className="acc-muted" style={{ margin: "4px 0 10px" }}>Payable and supplier advances together: a positive balance is owed to the supplier, a negative one is an advance the supplier holds for you. Each line names the account it was posted to.</p>}
          <table className="hr-table acc-lines-table">
            <thead><tr><th>Date</th><th>Journal</th><th>Description</th><th className="hr-num">Debit</th><th className="hr-num">Credit</th><th className="hr-num">Balance</th></tr></thead>
            <tbody>{(d.rows || []).map((r, i) => (
              <tr key={i}><td>{fmtDate(r.date)}</td><td><b>{r.journalNo}</b><div className="acc-muted"><Source module={r.sourceModule} type={r.sourceType} /></div></td>
                <td>{r.reference ? <b>{r.reference} </b> : null}{r.description}{r.account && <div className="acc-muted">{r.account}</div>}</td><td className="hr-num">{drcr(r.debit)}</td><td className="hr-num">{drcr(r.credit)}</td><td className="hr-num">{amount(r.balance)}</td></tr>))}
              {!(d.rows || []).length && <tr><td colSpan={6} className="acc-muted">No movement in this window.</td></tr>}
            </tbody>
          </table>
        </>
      )}
    </HrDrawer>
  );
}

/** What the sign of a party balance means, in words: for a supplier a negative balance is an advance held, for a customer a credit. */
function balanceNote(partyType, balance) {
  const v = Number(balance || 0);
  if (!v) return "settled";
  if (partyType === "SUPPLIER") return v < 0 ? "advance held by the supplier" : "owed to the supplier";
  if (partyType === "CUSTOMER" || partyType === "TENANT") return v < 0 ? "credit owed to the customer" : "owed by the customer";
  return "";
}
