/**
 * AccInvestors — investors, their capital and profit, and the ledger behind both.
 * Balances come from the ledger by party; every movement is a transaction
 * that posts, never a typed figure.
 */
import React, { useEffect, useMemo, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { Modal } from "../../components/UI.jsx";
import { HrPage, HrTable, HrButton, HrDrawer, HrTabs, Status, Kpis, Kpi, Facts, Fact, HrPageSkeleton, HrErrorState, humanError, P, HrFilterBar, Filter, HrSearch } from "../../components/HrKit.jsx";
import { getInvestors, saveInvestor, getInvestor, createInvestorTxn, approveInvestorTxn, cancelInvestorTxn, INVESTOR_TXN_TYPES, PAYMENT_MODES } from "../../services/accountsService.js";
import { amount, compact, drcr, fmtDate, fmtDateTime, isoToday, pct } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, ReasonPrompt, useAsync, Source } from "./AccKit.jsx";

export default function AccInvestors({ onNavigate }) {
  const perm = usePermission("accinvestors");
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(getInvestors, []);
  const [editing, setEditing] = useState(null);
  const [open, setOpen] = useState(null);
  const [txnFor, setTxnFor] = useState(null);
  const [q, setQ] = useState("");
  const [statusF, setStatusF] = useState("");
  const all = data || [];
  const rows = all.filter((r) => (!statusF || r.investor.investorStatus === statusF) && (!q || `${r.investor.investorCode} ${r.investor.investorName} ${r.investor.contactPhone || ""}`.toLowerCase().includes(q.toLowerCase())));
  const sum = (k) => rows.reduce((t, r) => t + Number(r[k] || 0), 0);
  if (busy && !data) return <HrPageSkeleton label="Reading the investor ledger…" kpis={5} rows={5} />;
  if (err && !data) return <HrErrorState hint={err} onRetry={reload} />;
  const save = async (f) => {
    try { await saveInvestor(f.pidInvestor, f); addToast(f.pidInvestor ? "Investor updated." : "Investor added."); setEditing(null); reload(); }
    catch (e) { addToast(humanError(e), "error"); }
  };
  const cols = [
    { key: "code", label: "Code", lead: true, sortable: true, sortValue: (r) => r.investor.investorCode, render: (r) => <b>{r.investor.investorCode}</b> },
    { key: "name", label: "Investor", sortable: true, sortValue: (r) => r.investor.investorName, render: (r) => <>{r.investor.investorName}<div className="acc-muted">{r.investor.investmentType} {r.investor.contactPhone ? `· ${r.investor.contactPhone}` : ""}</div></> },
    { key: "capital", label: "Capital", num: true, sortable: true, sortValue: (r) => Number(r.capital), render: (r) => <b>{amount(r.capital)}</b> },
    { key: "sharePct", label: "Share %", num: true, sortable: true, sortValue: (r) => Number(r.investor.sharePct), render: (r) => (r.investor.sharePct == null ? "—" : pct(r.investor.sharePct)) },
    { key: "profitAllocated", label: "Allocated", num: true, render: (r) => amount(r.profitAllocated) },
    { key: "profitPaid", label: "Paid", num: true, render: (r) => amount(r.profitPaid) },
    { key: "profitOutstanding", label: "Outstanding", num: true, sortable: true, sortValue: (r) => Number(r.profitOutstanding), render: (r) => <span className={Number(r.profitOutstanding) > 0 ? "acc-aged" : ""}>{amount(r.profitOutstanding)}</span> },
    { key: "status", label: "Status", render: (r) => <Status code={r.investor.investorStatus} /> },
    { key: "actions", label: "", actions: true, render: (r) => perm.canCreate && r.investor.investorStatus === "ACTIVE" && <HrButton size="sm" onClick={(e) => { e.stopPropagation(); setTxnFor(r.investor); }}>Record…</HrButton> },
  ];
  return (
    <HrPage className="acc-page" icon={AI.investors} crumbs={[ACC_CRUMB]} title="Investors" subtitle="Who has put money in, what the business holds for them, and what profit they are owed — all read from the ledger."
            actions={perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setEditing({})}>New investor</HrButton>}>
      <Kpis>
        <Kpi icon={AI.coins} label="Total capital" value={compact(sum("capital"))} tone="teal" />
        <Kpi icon={AI.investors} label="Active investors" value={rows.filter((r) => r.investor.investorStatus === "ACTIVE").length} />
        <Kpi icon={AI.profit} label="Profit allocated" value={compact(sum("profitAllocated"))} />
        <Kpi icon={AI.check} label="Profit paid" value={compact(sum("profitPaid"))} tone="sage" />
        <Kpi icon={AI.alert} label="Profit payable" value={compact(sum("profitOutstanding"))} warn={sum("profitOutstanding") > 0} note={sum("profitOutstanding") > 0 ? "owed to investors" : undefined} />
      </Kpis>
      <HrFilterBar active={(q ? 1 : 0) + (statusF ? 1 : 0)} onReset={() => { setQ(""); setStatusF(""); }}>
        <Filter label="Search" grow><HrSearch value={q} onChange={setQ} delay={0} placeholder="Code, name or phone…" /></Filter>
        <Filter label="Status"><select value={statusF} onChange={(e) => setStatusF(e.target.value)}><option value="">Any</option><option>ACTIVE</option><option>INACTIVE</option><option>EXITED</option></select></Filter>
      </HrFilterBar>
      <HrTable cols={cols} rows={rows} rowKey={(r) => r.investor.pidInvestor} onRowClick={(r) => setOpen(r.investor)} caption={`${rows.length} investor(s)`}
               empty={{ icon: AI.investors, title: q || statusF ? "No investor matches" : "No investor yet", hint: q || statusF ? "Clear the filters." : "Add an investor, then record their first investment — capital and profit share are read from the ledger from then on.", action: perm.canCreate && !q ? <HrButton size="sm" icon={P.plus} onClick={() => setEditing({})}>New investor</HrButton> : null }} />
      {editing && <InvestorForm initial={editing} onClose={() => setEditing(null)} onSave={save} />}
      {open && <InvestorDrawer investor={open} perm={perm} onClose={() => setOpen(null)} onEdit={() => { setEditing(open); setOpen(null); }} onRecord={() => setTxnFor(open)} onChanged={reload} />}
      {txnFor && <TxnForm investor={txnFor} onClose={() => setTxnFor(null)} onSaved={(t) => { setTxnFor(null); addToast(t.txnStatus === "POSTED" ? `${t.txnNo} posted.` : `${t.txnNo} saved — awaiting approval.`); reload(); }} />}
    </HrPage>
  );
}

function InvestorForm({ initial, onClose, onSave }) {
  const [f, setF] = useState({ pidInvestor: initial.pidInvestor, investorCode: initial.investorCode || "", investorName: initial.investorName || "", contactPhone: initial.contactPhone || "", contactEmail: initial.contactEmail || "",
    address: initial.address || "", idReference: initial.idReference || "", investmentType: initial.investmentType || "EQUITY", startDate: initial.startDate || isoToday(), sharePct: initial.sharePct ?? "",
    bankName: initial.bankName || "", bankAccountNo: initial.bankAccountNo || "", mobileWallet: initial.mobileWallet || "", remarks: initial.remarks || "", investorStatus: initial.investorStatus || "ACTIVE" });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <Modal title={initial.pidInvestor ? initial.investorName : "New investor"} subtitle="A reference to identification, never a copy of it. Bank details are optional and used only for paying profit." onClose={onClose} width={640}
      footer={<><HrButton onClick={onClose}>Cancel</HrButton><HrButton variant="primary" onClick={() => onSave({ ...f, sharePct: f.sharePct === "" ? null : Number(f.sharePct) })} disabled={!f.investorName}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Code</label><input value={f.investorCode} onChange={(e) => set("investorCode", e.target.value)} placeholder="auto if blank" disabled={!!initial.pidInvestor} /></div>
        <div className="field"><label>Name</label><input value={f.investorName} onChange={(e) => set("investorName", e.target.value)} /></div>
        <div className="field"><label>Type</label><select value={f.investmentType} onChange={(e) => set("investmentType", e.target.value)}>{["EQUITY", "PARTNER", "SILENT", "OTHER"].map((t) => <option key={t}>{t}</option>)}</select></div>
        <div className="field"><label>Started</label><input type="date" value={f.startDate || ""} onChange={(e) => set("startDate", e.target.value)} /></div>
        <div className="field"><label>Share % (for percentage policies)</label><input type="number" min="0" max="100" step="0.01" value={f.sharePct} onChange={(e) => set("sharePct", e.target.value)} /></div>
        <div className="field"><label>Status</label><select value={f.investorStatus} onChange={(e) => set("investorStatus", e.target.value)}>{["ACTIVE", "INACTIVE", "EXITED"].map((t) => <option key={t}>{t}</option>)}</select></div>
        <div className="field"><label>Phone</label><input value={f.contactPhone} onChange={(e) => set("contactPhone", e.target.value)} /></div>
        <div className="field"><label>Email</label><input value={f.contactEmail} onChange={(e) => set("contactEmail", e.target.value)} /></div>
        <div className="field acc-span2"><label>Address</label><input value={f.address} onChange={(e) => set("address", e.target.value)} /></div>
        <div className="field"><label>ID reference</label><input value={f.idReference} onChange={(e) => set("idReference", e.target.value)} placeholder="NID / passport no" /></div>
        <div className="field"><label>Mobile wallet</label><input value={f.mobileWallet} onChange={(e) => set("mobileWallet", e.target.value)} /></div>
        <div className="field"><label>Bank</label><input value={f.bankName} onChange={(e) => set("bankName", e.target.value)} /></div>
        <div className="field"><label>Bank account no</label><input value={f.bankAccountNo} onChange={(e) => set("bankAccountNo", e.target.value)} /></div>
        <div className="field acc-span2"><label>Remarks</label><input value={f.remarks} onChange={(e) => set("remarks", e.target.value)} /></div>
      </div>
    </Modal>
  );
}

function InvestorDrawer({ investor, perm, onClose, onEdit, onRecord, onChanged }) {
  const { addToast } = useApp();
  const [tab, setTab] = useState("ledger");
  const [cancelling, setCancelling] = useState(null);
  const { data, busy, err, reload } = useAsync(() => getInvestor(investor.pidInvestor), [investor.pidInvestor]);
  const act = async (fn, msg) => { try { await fn(); addToast(msg); reload(); onChanged(); } catch (e) { addToast(humanError(e), "error"); } };
  const d = data;
  return (
    <HrDrawer title={investor.investorName} subtitle={`${investor.investorCode} · ${investor.investmentType}`} onClose={onClose} width={760}
      footer={<>{perm.canEdit && <HrButton onClick={onEdit}>Edit profile</HrButton>}{perm.canCreate && investor.investorStatus === "ACTIVE" && <HrButton variant="primary" onClick={onRecord}>Record transaction</HrButton>}</>}>
      {err && <HrErrorState hint={err} onRetry={reload} />}
      {d && (
        <>
          <Facts><Fact label="Capital" value={amount(d.capital)} /><Fact label="Profit payable" value={amount(d.profitOutstanding)} /><Fact label="Status" value={<Status code={investor.investorStatus} />} /><Fact label="Share %" value={investor.sharePct == null ? "—" : pct(investor.sharePct)} /></Facts>
          <AccTabs inline tabs={[{ id: "ledger", label: "Ledger", icon: AI.ledger }, { id: "txns", label: "Transactions", icon: AI.receipt }, { id: "profit", label: "Profit share", icon: AI.profit }]} active={tab} onChange={setTab} />
          {tab === "ledger" && (
            <table className="hr-table acc-lines-table">
              <thead><tr><th>Date</th><th>Journal</th><th>Account</th><th>Description</th><th className="hr-num">Debit</th><th className="hr-num">Credit</th><th className="hr-num">Balance</th></tr></thead>
              <tbody><tr><td colSpan={6}>Opening balance</td><td className="hr-num">{amount(d.ledger?.openingBalance)}</td></tr>
                {(d.ledger?.rows || []).map((r, i) => <tr key={i}><td>{fmtDate(r.date)}</td><td><b>{r.journalNo}</b></td><td>{r.account}</td><td>{r.description}</td><td className="hr-num">{drcr(r.debit)}</td><td className="hr-num">{drcr(r.credit)}</td><td className="hr-num">{amount(r.balance)}</td></tr>)}
              </tbody>
            </table>
          )}
          {tab === "txns" && (
            <HrTable rows={d.transactions || []} rowKey={(t) => t.pidInvestorTxn} empty="No transaction yet."
              cols={[{ key: "txnDate", label: "Date", render: (t) => fmtDate(t.txnDate) }, { key: "txnNo", label: "No", lead: true, render: (t) => <b>{t.txnNo}</b> },
                     { key: "txnType", label: "Type", render: (t) => INVESTOR_TXN_TYPES.find((x) => x.id === t.txnType)?.label || t.txnType }, { key: "amount", label: "Amount", num: true, render: (t) => amount(t.amount) },
                     { key: "paymentMode", label: "Mode" }, { key: "txnStatus", label: "Status", render: (t) => <Status code={t.txnStatus} /> },
                     { key: "actions", label: "", actions: true, render: (t) => <span className="acc-row-actions">
                        {t.txnStatus === "DRAFT" && perm.canApprove && <HrButton size="sm" variant="primary" onClick={() => act(() => approveInvestorTxn(t.pidInvestorTxn), `${t.txnNo} approved and posted.`)}>Approve</HrButton>}
                        {["DRAFT", "POSTED"].includes(t.txnStatus) && t.txnType !== "PROFIT_ALLOCATION" && perm.canApprove && <HrButton size="sm" onClick={() => setCancelling(t)}>Cancel…</HrButton>}</span> }]} />
          )}
          {tab === "profit" && (
            <HrTable rows={d.allocations || []} rowKey={(a) => a.pidAllocation} empty="No profit allocation yet."
              cols={[{ key: "eligibleCapital", label: "Eligible capital", num: true, render: (a) => amount(a.eligibleCapital) }, { key: "sharePct", label: "Share %", num: true, render: (a) => pct(a.sharePct) },
                     { key: "allocatedAmount", label: "Allocated", num: true, render: (a) => amount(a.allocatedAmount) }, { key: "paidAmount", label: "Paid", num: true, render: (a) => amount(a.paidAmount) },
                     { key: "left", label: "Outstanding", num: true, render: (a) => amount(Number(a.allocatedAmount) - Number(a.paidAmount)) }, { key: "allocStatus", label: "Status", render: (a) => <Status code={a.allocStatus} /> }]} />
          )}
        </>
      )}
      {cancelling && <ReasonPrompt title={`Cancel ${cancelling.txnNo}`} confirmLabel="Cancel transaction" onConfirm={(why) => { setCancelling(null); act(() => cancelInvestorTxn(cancelling.pidInvestorTxn, why), `${cancelling.txnNo} cancelled.`); }} onClose={() => setCancelling(null)} />}
    </HrDrawer>
  );
}

function TxnForm({ investor, onClose, onSaved }) {
  const { addToast } = useApp();
  const [busy, setBusy] = useState(false);
  const [allocs, setAllocs] = useState([]);
  const [f, setF] = useState({ pidInvestor: investor.pidInvestor, txnType: "INITIAL_INVESTMENT", txnDate: isoToday(), amount: "", direction: 1, paymentMode: "BANK", reference: "", description: "", reason: "", pidAllocation: "" });
  useEffect(() => { getInvestor(investor.pidInvestor).then((d) => setAllocs((d.allocations || []).filter((a) => ["POSTED", "PARTIAL"].includes(a.allocStatus)))); }, [investor.pidInvestor]);
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const adj = ["CAPITAL_ADJUSTMENT", "OTHER_APPROVED_ADJUSTMENT"].includes(f.txnType), pay = f.txnType === "PROFIT_DISTRIBUTION";
  const submit = async () => {
    setBusy(true);
    try { onSaved(await createInvestorTxn({ ...f, amount: Number(f.amount), pidAllocation: f.pidAllocation ? Number(f.pidAllocation) : null })); }
    catch (e) { addToast(humanError(e, "Could not record the transaction."), "error"); }
    finally { setBusy(false); }
  };
  return (
    <Modal title={`Record — ${investor.investorName}`} subtitle="Investments and withdrawals post at once. Adjustments wait for Approve and need a reason. A profit payment settles a posted allocation." onClose={onClose} width={560}
      footer={<><HrButton onClick={onClose} disabled={busy}>Cancel</HrButton><HrButton variant="primary" busy={busy} onClick={submit} disabled={!f.amount || (adj && !f.reason.trim()) || (pay && !f.pidAllocation)}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field acc-span2"><label>Transaction</label><select value={f.txnType} onChange={(e) => set("txnType", e.target.value)}>{INVESTOR_TXN_TYPES.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</select></div>
        <div className="field"><label>Date</label><input type="date" value={f.txnDate} onChange={(e) => set("txnDate", e.target.value)} /></div>
        <div className="field"><label>Amount</label><input type="number" min="0.01" step="0.01" value={f.amount} onChange={(e) => set("amount", e.target.value)} className="hr-num" /></div>
        {!adj && <div className="field"><label>Paid by</label><select value={f.paymentMode} onChange={(e) => set("paymentMode", e.target.value)}>{PAYMENT_MODES.map((m) => <option key={m}>{m}</option>)}</select></div>}
        {adj && <div className="field"><label>Direction</label><select value={f.direction} onChange={(e) => set("direction", Number(e.target.value))}><option value={1}>Raise capital</option><option value={-1}>Lower capital</option></select></div>}
        {pay && <div className="field acc-span2"><label>Allocation being paid</label><select value={f.pidAllocation} onChange={(e) => set("pidAllocation", e.target.value)}><option value="">Choose…</option>{allocs.map((a) => <option key={a.pidAllocation} value={a.pidAllocation}>#{a.pidAllocation} · allocated {amount(a.allocatedAmount)} · unpaid {amount(Number(a.allocatedAmount) - Number(a.paidAmount))}</option>)}</select></div>}
        <div className="field"><label>Reference</label><input value={f.reference} onChange={(e) => set("reference", e.target.value)} /></div>
        <div className="field"><label>Description</label><input value={f.description} onChange={(e) => set("description", e.target.value)} /></div>
        {adj && <div className="field acc-span2"><label>Reason</label><textarea rows={2} value={f.reason} onChange={(e) => set("reason", e.target.value)} /></div>}
      </div>
    </Modal>
  );
}
