/**
 * AccProfit — profit-share policies and the distribution workflow:
 *   period → scope → calculate → review → approve → post → pay.
 * Never one button: each step is a decision somebody signs.
 */
import React, { useEffect, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { Modal } from "../../components/UI.jsx";
import { HrPage, HrTable, HrButton, HrTabBar, HrStepper, HrDrawer, Status, Kpis, Kpi, Facts, Fact, HrPageSkeleton, HrErrorState, humanError, P } from "../../components/HrKit.jsx";
import { getPolicies, savePolicy, getDistributions, getDistribution, calculateProfit, distributionStep, getInvestors, SHARE_METHODS } from "../../services/accountsService.js";
import { amount, compact, fmtDate, fmtDateTime, isoToday, isoMonthStart, pct } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, BranchPicker, DateRange, Figure, ReasonPrompt, useAsync, takeIntent } from "./AccKit.jsx";

const STEPS = [{ id: "period", label: "Period" }, { id: "scope", label: "Scope" }, { id: "calc", label: "Calculate" }, { id: "distributable", label: "Distributable" },
  { id: "alloc", label: "Allocation" }, { id: "approve", label: "Approval" }, { id: "post", label: "Post" }, { id: "pay", label: "Payment" }];
const STATUS_STEP = { DRAFT: 2, CALCULATED: 5, REVIEWED: 5, APPROVED: 6, POSTED: 7, PAID: 8, CANCELLED: 0 };

export default function AccProfit({ onNavigate }) {
  const perm = usePermission("accprofit");
  const { addToast } = useApp();
  const [intent] = useState(() => takeIntent("accprofit"));
  const [tab, setTab] = useState(intent?.tab || "distributions");
  const dists = useAsync(getDistributions, []);
  const [open, setOpen] = useState(null);
  const [starting, setStarting] = useState(false);
  const rows = dists.data || [];
  const sum = (k, f = () => true) => rows.filter((d) => d.distStatus !== "CANCELLED" && f(d)).reduce((t, d) => t + Number(d[k] || 0), 0);
  return (
    <HrPage className="acc-page" icon={AI.profit} crumbs={[ACC_CRUMB]} title="Profit Distribution" subtitle="Accounting profit for a period, less what is retained, allocated to investors by the policy in force — then approved, posted as a liability and paid."
            actions={perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setStarting(true)}>New distribution</HrButton>}>
      <Kpis>
        <Kpi icon={AI.profit} label="Allocated (all periods)" value={compact(sum("allocatedTotal", (d) => ["POSTED", "PAID"].includes(d.distStatus)))} tone="teal" />
        <Kpi icon={AI.check} label="Paid" value={compact(sum("paidTotal"))} tone="sage" />
        <Kpi icon={AI.coins} label="Outstanding" value={compact(sum("allocatedTotal", (d) => ["POSTED", "PAID"].includes(d.distStatus)) - sum("paidTotal"))} />
        <Kpi icon={AI.clock} label="Awaiting a decision" value={rows.filter((d) => ["CALCULATED", "REVIEWED", "APPROVED"].includes(d.distStatus)).length} warn />
      </Kpis>
      <AccTabs tabs={[{ id: "distributions", label: "Distributions", icon: AI.profit }, { id: "policies", label: "Policies", icon: AI.settings }]} active={tab} onChange={setTab} />
      {tab === "distributions" ? (
        <HrTable rows={rows} rowKey={(d) => d.pidDistribution} loading={dists.busy} error={dists.err} onRetry={dists.reload} onRowClick={(d) => setOpen(d.pidDistribution)} empty="No distribution yet. Start one for a closed period."
          cols={[{ key: "distributionNo", label: "No", lead: true, render: (d) => <b>{d.distributionNo}</b> }, { key: "period", label: "Period", render: (d) => `${fmtDate(d.periodFrom)} – ${fmtDate(d.periodTo)}` },
                 { key: "shareMethod", label: "Method", render: (d) => SHARE_METHODS.find((m) => m.id === d.shareMethod)?.label.split(" — ")[0] || d.shareMethod },
                 { key: "accountingProfit", label: "Profit", num: true, render: (d) => amount(d.accountingProfit) }, { key: "distributableProfit", label: "Distributable", num: true, render: (d) => amount(d.distributableProfit) },
                 { key: "allocatedTotal", label: "Allocated", num: true, render: (d) => amount(d.allocatedTotal) }, { key: "paidTotal", label: "Paid", num: true, render: (d) => amount(d.paidTotal) },
                 { key: "distStatus", label: "Status", render: (d) => <Status code={d.distStatus} /> }]} />
      ) : <Policies perm={perm} />}
      {starting && <StartForm onClose={() => setStarting(false)} onDone={(d) => { setStarting(false); dists.reload(); setOpen(d.distribution.pidDistribution); addToast(`${d.distribution.distributionNo} calculated.`); }} />}
      {open && <DistributionDrawer id={open} perm={perm} onClose={() => setOpen(null)} onChanged={dists.reload} onNavigate={onNavigate} />}
    </HrPage>
  );
}

function StartForm({ onClose, onDone }) {
  const { addToast } = useApp();
  const [range, setRange] = useState({ from: isoMonthStart(), to: isoToday() });
  const [branchId, setBranchId] = useState(null);
  const [policyId, setPolicyId] = useState("");
  const [adj, setAdj] = useState({ amount: "", reason: "" });
  const [notes, setNotes] = useState("");
  const [policies, setPolicies] = useState([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => { getPolicies().then((p) => setPolicies(p.map((x) => x.policy))).catch(() => {}); }, []);
  const submit = async () => {
    setBusy(true);
    try { onDone(await calculateProfit({ from: range.from, to: range.to, branchId, policyId: policyId || null, adjustmentAmount: adj.amount || null, adjustmentReason: adj.reason, notes })); }
    catch (e) { addToast(humanError(e, "Could not calculate."), "error"); }
    finally { setBusy(false); }
  };
  return (
    <Modal title="New profit distribution" subtitle="Steps 1–3: the period, the scope and the calculation. Profit is the ledger's revenue less expenses for the span; nothing is typed." onClose={onClose} width={620}
      footer={<><HrButton onClick={onClose} disabled={busy}>Cancel</HrButton><HrButton variant="primary" busy={busy} onClick={submit} disabled={!range.from || !range.to || (adj.amount && !adj.reason.trim())}>Calculate</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Period from</label><input type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} /></div>
        <div className="field"><label>Period to</label><input type="date" value={range.to} max={isoToday()} onChange={(e) => setRange({ ...range, to: e.target.value })} /></div>
        <div className="field acc-span2"><label>Policy</label><select value={policyId} onChange={(e) => setPolicyId(e.target.value)}><option value="">The policy in force for the period end</option>{policies.map((p) => <option key={p.pidProfitPolicy} value={p.pidProfitPolicy}>{p.policyName} · {p.shareMethod} · retained {p.retainedPct}%</option>)}</select></div>
        <div className="field acc-span2"><BranchPicker value={branchId} onChange={setBranchId} allowAll /></div>
        <div className="field"><label>Approved adjustment (±)</label><input type="number" step="0.01" value={adj.amount} onChange={(e) => setAdj({ ...adj, amount: e.target.value })} className="hr-num" placeholder="0.00" /></div>
        <div className="field"><label>Adjustment reason</label><input value={adj.reason} onChange={(e) => setAdj({ ...adj, reason: e.target.value })} placeholder="required with an adjustment" /></div>
        <div className="field acc-span2"><label>Notes</label><input value={notes} onChange={(e) => setNotes(e.target.value)} /></div>
      </div>
    </Modal>
  );
}

function DistributionDrawer({ id, perm, onClose, onChanged, onNavigate }) {
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(() => getDistribution(id), [id]);
  const [cancelling, setCancelling] = useState(false);
  const [acting, setActing] = useState(false);
  const d = data?.distribution;
  const step = async (name, body) => { setActing(true); try { await distributionStep(id, name, body); addToast(`Distribution ${name === "post" ? "posted" : name + "d"}.`); reload(); onChanged(); } catch (e) { addToast(humanError(e), "error"); } finally { setActing(false); } };
  const next = d && ({ CALCULATED: ["review", "Mark reviewed", perm.canEdit], REVIEWED: ["approve", "Approve", perm.canApprove], APPROVED: ["post", "Post to the ledger", perm.canApprove] }[d.distStatus]);
  return (
    <HrDrawer title={d ? d.distributionNo : "Distribution"} subtitle={d ? `${fmtDate(d.periodFrom)} – ${fmtDate(d.periodTo)} · ${SHARE_METHODS.find((m) => m.id === d.shareMethod)?.label || d.shareMethod}` : ""} onClose={onClose} width={820}
      footer={d && <>
        {["CALCULATED", "REVIEWED", "APPROVED", "POSTED"].includes(d.distStatus) && perm.canApprove && <HrButton onClick={() => setCancelling(true)}>Cancel…</HrButton>}
        {d.distStatus === "POSTED" && <HrButton onClick={() => onNavigate?.("accinvestors")}>Pay investors</HrButton>}
        {next && next[2] && <HrButton variant="primary" busy={acting} onClick={() => step(next[0])}>{next[1]}</HrButton>}
      </>}>
      {err && <HrErrorState hint={err} onRetry={reload} />}
      {busy && !d && <p className="acc-muted">Loading…</p>}
      {d && (
        <>
          <div className="acc-drawer-status"><Status code={d.distStatus} /></div>
          <HrStepper steps={STEPS} at={STATUS_STEP[d.distStatus] ?? 0} />
          <div className="acc-figures">
            <Figure label="Revenue" value={amount(d.revenue)} /><Figure label="Cost of sales" value={amount(d.costOfSales)} />
            <Figure label="Operating expenses" value={amount(d.operatingExpenses)} /><Figure label="Other income − expense" value={amount(Number(d.otherIncome) - Number(d.otherExpense))} />
            <Figure label="Accounting profit" value={amount(d.accountingProfit)} big tone={Number(d.accountingProfit) < 0 ? "red" : "sage"} />
            <Figure label={`Retained ${pct(d.retainedPct)}`} value={amount(d.retainedAmount)} />
            <Figure label="Adjustment" value={amount(d.adjustmentAmount)} hint={d.adjustmentReason} />
            <Figure label="Distributable profit" value={amount(d.distributableProfit)} big tone="teal" hint={`investor pool ${pct(d.investorPoolPct)}`} />
          </div>
          <h4 className="acc-h4">Investor allocation</h4>
          <HrTable rows={data.allocations || []} rowKey={(a) => a.allocation.pidAllocation} empty="No investor eligible."
            cols={[{ key: "investor", label: "Investor", lead: true, render: (a) => <b>{a.investor?.investorName}</b> },
                   { key: "opening", label: "Opening capital", num: true, render: (a) => amount(a.allocation.openingCapital) },
                   { key: "add", label: "+ Additions", num: true, render: (a) => amount(a.allocation.additions) }, { key: "wd", label: "− Withdrawals", num: true, render: (a) => amount(a.allocation.withdrawals) },
                   { key: "eligible", label: "Eligible", num: true, render: (a) => amount(a.allocation.eligibleCapital) }, { key: "pct", label: "Share", num: true, render: (a) => pct(a.allocation.sharePct) },
                   { key: "alloc", label: "Allocated", num: true, render: (a) => amount(a.allocation.allocatedAmount) }, { key: "paid", label: "Paid", num: true, render: (a) => amount(a.allocation.paidAmount) },
                   { key: "st", label: "Status", render: (a) => <Status code={a.allocation.allocStatus} /> }]} />
          <Facts>
            <Fact label="Calculated" value={d.calculatedAt ? `${fmtDateTime(d.calculatedAt)} · ${d.calculatedBy}` : "—"} />
            <Fact label="Reviewed" value={d.reviewedAt ? `${fmtDateTime(d.reviewedAt)} · ${d.reviewedBy}` : "—"} />
            <Fact label="Approved" value={d.approvedAt ? `${fmtDateTime(d.approvedAt)} · ${d.approvedBy}` : "—"} />
            <Fact label="Posted" value={d.postedAt ? `${fmtDateTime(d.postedAt)} · ${d.postedBy}` : "—"} />
            {d.cancelReason && <Fact label="Cancelled" value={`${fmtDateTime(d.cancelledAt)} · ${d.cancelledBy} — ${d.cancelReason}`} />}
          </Facts>
          <details className="acc-details"><summary>Profit lines the calculation read</summary>
            <table className="hr-table acc-lines-table"><thead><tr><th>Section</th><th>Account</th><th className="hr-num">Amount</th></tr></thead>
              <tbody>{(data.profitLines || []).map((l) => <tr key={l.accountId}><td>{l.group}</td><td>{l.code} {l.name}</td><td className="hr-num">{amount(l.amount)}</td></tr>)}</tbody></table>
          </details>
        </>
      )}
      {cancelling && <ReasonPrompt title={`Cancel ${d.distributionNo}`} hint={d.distStatus === "POSTED" ? "The posted allocation is reversed in the ledger. Payments already made must be cancelled first." : ""} confirmLabel="Cancel distribution" onConfirm={(why) => { setCancelling(false); step("cancel", { reason: why }); }} onClose={() => setCancelling(false)} />}
    </HrDrawer>
  );
}

function Policies({ perm }) {
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(getPolicies, []);
  const [editing, setEditing] = useState(null);
  const rows = data || [];
  return (
    <>
      <div className="acc-actions">{perm.canEdit && <HrButton variant="primary" icon={P.plus} onClick={() => setEditing({ policy: {}, lines: [] })}>New policy</HrButton>}</div>
      <HrTable rows={rows} rowKey={(r) => r.policy.pidProfitPolicy} loading={busy} error={err} onRetry={reload} onRowClick={perm.canEdit ? setEditing : undefined} empty="No policy yet. A distribution needs one in force for its period."
        cols={[{ key: "name", label: "Policy", lead: true, render: (r) => <b>{r.policy.policyName}</b> }, { key: "method", label: "Method", render: (r) => SHARE_METHODS.find((m) => m.id === r.policy.shareMethod)?.label || r.policy.shareMethod },
               { key: "retained", label: "Retained", num: true, render: (r) => pct(r.policy.retainedPct) }, { key: "pool", label: "Investor pool", num: true, render: (r) => pct(r.policy.investorPoolPct) },
               { key: "from", label: "Effective", render: (r) => `${fmtDate(r.policy.effectiveFrom)}${r.policy.effectiveTo ? " – " + fmtDate(r.policy.effectiveTo) : ""}` },
               { key: "lines", label: "Investor %", render: (r) => (r.lines || []).map((l) => `${l.pidInvestor}: ${l.sharePct}%`).join(", ") },
               { key: "active", label: "Status", render: (r) => <Status code={r.policy.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> }]} />
      {editing && <PolicyForm initial={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); addToast("Policy saved."); reload(); }} />}
    </>
  );
}

function PolicyForm({ initial, onClose, onSaved }) {
  const { addToast } = useApp();
  const p = initial.policy || {};
  const [f, setF] = useState({ policyName: p.policyName || "", shareMethod: p.shareMethod || "CAPITAL_RATIO", retainedPct: p.retainedPct ?? 0, investorPoolPct: p.investorPoolPct ?? 100, effectiveFrom: p.effectiveFrom || isoToday().slice(0, 4) + "-01-01", effectiveTo: p.effectiveTo || "", isActive: p.isActive ?? 1, notes: p.notes || "" });
  const [lines, setLines] = useState((initial.lines || []).map((l) => ({ pidInvestor: l.pidInvestor, sharePct: l.sharePct })));
  const [investors, setInvestors] = useState([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => { getInvestors().then((r) => setInvestors(r.map((x) => x.investor))).catch(() => {}); }, []);
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const total = lines.reduce((t, l) => t + Number(l.sharePct || 0), 0);
  const submit = async () => {
    setBusy(true);
    try { await savePolicy({ id: p.pidProfitPolicy, policy: { ...f, effectiveTo: f.effectiveTo || null }, lines: f.shareMethod === "INVESTOR_SPECIFIC_PERCENTAGE" ? lines.map((l) => ({ pidInvestor: Number(l.pidInvestor), sharePct: Number(l.sharePct) })) : [] }); onSaved(); }
    catch (e) { addToast(humanError(e), "error"); }
    finally { setBusy(false); }
  };
  return (
    <Modal title={p.pidProfitPolicy ? p.policyName : "New profit-share policy"} subtitle="The method is stored with every distribution it produces, so a later change never rewrites an earlier calculation." onClose={onClose} width={640}
      footer={<><HrButton onClick={onClose} disabled={busy}>Cancel</HrButton><HrButton variant="primary" busy={busy} onClick={submit} disabled={!f.policyName || (f.shareMethod === "INVESTOR_SPECIFIC_PERCENTAGE" && (total > 100 || !lines.length))}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field acc-span2"><label>Name</label><input value={f.policyName} onChange={(e) => set("policyName", e.target.value)} /></div>
        <div className="field acc-span2"><label>Method</label><select value={f.shareMethod} onChange={(e) => set("shareMethod", e.target.value)}>{SHARE_METHODS.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}</select></div>
        <div className="field"><label>Retained before distribution %</label><input type="number" min="0" max="100" step="0.01" value={f.retainedPct} onChange={(e) => set("retainedPct", e.target.value)} className="hr-num" /></div>
        <div className="field"><label>Investor pool % of distributable</label><input type="number" min="0" max="100" step="0.01" value={f.investorPoolPct} onChange={(e) => set("investorPoolPct", e.target.value)} className="hr-num" /></div>
        <div className="field"><label>Effective from</label><input type="date" value={f.effectiveFrom} onChange={(e) => set("effectiveFrom", e.target.value)} /></div>
        <div className="field"><label>Effective to</label><input type="date" value={f.effectiveTo} onChange={(e) => set("effectiveTo", e.target.value)} /></div>
        <div className="field"><label>Status</label><select value={f.isActive} onChange={(e) => set("isActive", Number(e.target.value))}><option value={1}>Active</option><option value={0}>Retired</option></select></div>
        <div className="field acc-span2"><label>Notes</label><input value={f.notes} onChange={(e) => set("notes", e.target.value)} /></div>
        {f.shareMethod === "INVESTOR_SPECIFIC_PERCENTAGE" && (
          <div className="acc-span2">
            <h4 className="acc-h4">Percentage per investor <span className={`acc-muted${total > 100 ? " is-bad" : ""}`}>— total {total.toFixed(2)}%</span></h4>
            {lines.map((l, i) => (
              <div key={i} className="acc-inline">
                <select value={l.pidInvestor} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, pidInvestor: e.target.value } : x)))}><option value="">Investor…</option>{investors.map((inv) => <option key={inv.pidInvestor} value={inv.pidInvestor}>{inv.investorCode} {inv.investorName}</option>)}</select>
                <input type="number" min="0" max="100" step="0.01" value={l.sharePct} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, sharePct: e.target.value } : x)))} className="hr-num" />
                <HrButton size="sm" onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>Remove</HrButton>
              </div>))}
            <HrButton size="sm" icon={P.plus} onClick={() => setLines((ls) => [...ls, { pidInvestor: "", sharePct: "" }])}>Add investor</HrButton>
          </div>
        )}
      </div>
    </Modal>
  );
}
