/**
 * AccCash — cash, bank and petty cash: positions, vouchers (receipt, payment,
 * transfer, opening balance, approved adjustments) and bank accounts.
 */
import React, { useEffect, useMemo, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { Modal } from "../../components/UI.jsx";
import { HrPage, HrTable, HrFilterBar, Filter, HrButton, HrTabBar, HrPagination, Status, Kpis, Kpi, usePagedList, humanError, Facts, Fact, P, Ic } from "../../components/HrKit.jsx";
import { getCashPositions, getCashAccounts, getChart, searchVouchers, createVoucher, approveVoucher, cancelVoucher, getSettings, getCostCenters, createBank, updateBank, VOUCHER_KINDS } from "../../services/accountsService.js";
import { amount, compact, fmtDate, isoToday, isoMonthStart } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, DateRange, BranchPicker, ReasonPrompt, useAsync, takeIntent } from "./AccKit.jsx";

const KIND_ICON = { RECEIPT: AI.trendUp, PAYMENT: AI.trendDown, TRANSFER: AI.scale, OPENING: AI.play, ADJUSTMENT_IN: AI.plus, ADJUSTMENT_OUT: AI.undo };

export default function AccCash({ onNavigate }) {
  const perm = usePermission("acccash");
  const { addToast } = useApp();
  const [intent] = useState(() => takeIntent("acccash"));
  const [tab, setTab] = useState(intent?.tab || "vouchers");
  const [branchId, setBranchId] = useState(null);
  const positions = useAsync(() => getCashPositions({ branchId }), [branchId]);
  const [creating, setCreating] = useState(null);
  const [cancelling, setCancelling] = useState(null);
  const [range, setRange] = useState({ from: isoMonthStart(), to: isoToday() });
  const [kind, setKind] = useState("");
  const [status, setStatus] = useState("");
  const list = usePagedList(({ page, size }) => searchVouchers({ branchId, kind, status, from: range.from, to: range.to, page, size }), [branchId, kind, status, range.from, range.to]);
  const cancel = async (reason) => {
    try { await cancelVoucher(cancelling.pidCashVoucher, reason); addToast(`${cancelling.voucherNo} cancelled.`); setCancelling(null); list.reload(); positions.reload(); }
    catch (e) { addToast(humanError(e), "error"); }
  };
  const approve = async (v) => { try { await approveVoucher(v.pidCashVoucher); addToast(`${v.voucherNo} approved and posted.`); list.reload(); positions.reload(); } catch (e) { addToast(humanError(e), "error"); } };
  const cols = useMemo(() => [
    { key: "voucherDate", label: "Date", render: (v) => fmtDate(v.voucherDate) },
    { key: "voucherNo", label: "Voucher", lead: true, render: (v) => <><b>{v.voucherNo}</b><div className="acc-muted">{v.description}</div></> },
    { key: "voucherKind", label: "Kind", render: (v) => VOUCHER_KINDS.find((k) => k.id === v.voucherKind)?.label.split(" — ")[0] || v.voucherKind },
    { key: "amount", label: "Amount", num: true, render: (v) => amount(v.amount) },
    { key: "partyName", label: "Party", render: (v) => v.partyName || "" },
    { key: "reference", label: "Reference" },
    { key: "voucherStatus", label: "Status", render: (v) => <Status code={v.voucherStatus} /> },
    { key: "actions", label: "", actions: true, render: (v) => (
      <span className="acc-row-actions">
        {v.voucherStatus === "DRAFT" && perm.canApprove && <HrButton size="sm" variant="primary" onClick={(e) => { e.stopPropagation(); approve(v); }}>Approve</HrButton>}
        {v.voucherStatus !== "CANCELLED" && perm.canApprove && <HrButton size="sm" onClick={(e) => { e.stopPropagation(); setCancelling(v); }}>Cancel…</HrButton>}
      </span>) },
  ], [perm.canApprove]); // eslint-disable-line react-hooks/exhaustive-deps
  const pos = positions.data || [];
  const byRole = (r) => pos.filter((p) => p.role === r).reduce((t, p) => t + Number(p.balance || 0), 0);
  const bank = pos.filter((p) => p.code?.startsWith("113")).reduce((t, p) => t + Number(p.balance || 0), 0);
  return (
    <HrPage className="acc-page" icon={AI.bank} crumbs={[ACC_CRUMB]} title="Cash & Bank" subtitle="The till, the petty cash box, the wallets and every bank account — balances from the ledger, and the vouchers that move money no other screen records."
            actions={perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setCreating("RECEIPT")}>New voucher</HrButton>}>
      <Kpis>
        <Kpi icon={AI.till} label="Cash in till" value={compact(byRole("CASH_REGISTER"))} tone="teal" onClick={() => onNavigate?.("accdailycash")} note="see Daily Cash" />
        <Kpi icon={AI.coins} label="Petty cash" value={compact(byRole("PETTY_CASH"))} />
        <Kpi icon={AI.bank} label="Bank" value={compact(bank)} />
        <Kpi icon={AI.phone} label="Mobile wallets" value={compact(byRole("MOBILE_WALLET"))} />
        <Kpi icon={AI.truck} label="Cash in transit" value={compact(byRole("CASH_IN_TRANSIT"))} note="with delivery men" />
      </Kpis>
      <AccTabs tabs={[{ id: "vouchers", label: "Vouchers", icon: AI.receipt }, { id: "accounts", label: "Cash accounts", icon: AI.coins }, { id: "banks", label: "Bank accounts", icon: AI.bank }]} active={tab} onChange={setTab} />
      {tab === "vouchers" && (
        <>
          <HrFilterBar>
            <DateRange from={range.from} to={range.to} onChange={setRange} />
            <Filter label="Kind"><select value={kind} onChange={(e) => setKind(e.target.value)}><option value="">Any</option>{VOUCHER_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}</select></Filter>
            <Filter label="Status"><select value={status} onChange={(e) => setStatus(e.target.value)}><option value="">Any</option>{["DRAFT", "APPROVED", "POSTED", "CANCELLED"].map((s) => <option key={s}>{s}</option>)}</select></Filter>
            <BranchPicker value={branchId} onChange={setBranchId} allowAll />
          </HrFilterBar>
          {perm.canCreate && (
            <div className="acc-quick-strip" role="group" aria-label="New voucher">
              <span className="acc-quick-label">New</span>
              {VOUCHER_KINDS.map((k) => <button key={k.id} type="button" className="acc-quick-btn" onClick={() => setCreating(k.id)}><Ic path={KIND_ICON[k.id]} size={14} /><span>{k.label.split(" — ")[0].replace(" (approved)", "").replace(" (first day)", "")}</span><small>{(k.label.split(" — ")[1] || (k.id.startsWith("ADJUSTMENT") ? "approved" : k.id === "OPENING" ? "first day" : "")).trim()}</small></button>)}
            </div>
          )}
          <HrTable cols={cols} rows={list.rows} rowKey={(v) => v.pidCashVoucher} loading={list.busy} error={list.err} onRetry={list.reload} empty="No voucher in this window." caption={`${list.total} voucher(s)`} />
          <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages} onPage={list.setPage} onSize={list.setSize} />
        </>
      )}
      {tab === "accounts" && (
        <HrTable rows={pos} rowKey={(p) => p.accountId} loading={positions.busy} error={positions.err} onRetry={positions.reload} empty="No cash account."
          cols={[{ key: "code", label: "Code", lead: true, render: (p) => <b>{p.code}</b> }, { key: "name", label: "Account" }, { key: "role", label: "Role", render: (p) => p.role ? <code className="acc-role">{p.role}</code> : "" }, { key: "balance", label: "Balance", num: true, render: (p) => amount(p.balance) }]} />
      )}
      {tab === "banks" && <BankAccounts perm={perm} onChanged={positions.reload} />}
      {creating && <VoucherForm kind={creating} onClose={() => setCreating(null)} onSaved={(v) => { setCreating(null); addToast(v.voucherStatus === "POSTED" ? `${v.voucherNo} posted.` : `${v.voucherNo} saved — awaiting approval.`); list.reload(); positions.reload(); }} />}
      {cancelling && <ReasonPrompt title={`Cancel ${cancelling.voucherNo}`} hint="A posted voucher is reversed in the ledger; the voucher itself stays on record as cancelled." confirmLabel="Cancel voucher" onConfirm={cancel} onClose={() => setCancelling(null)} />}
    </HrPage>
  );
}

function VoucherForm({ kind: initialKind, onClose, onSaved }) {
  const { addToast } = useApp();
  const [cashAccounts, setCashAccounts] = useState([]);
  const [chart, setChart] = useState([]);
  const [costCentres, setCostCentres] = useState([]);
  const [busy, setBusy] = useState(false);
  const [f, setF] = useState({ voucherKind: initialKind, voucherDate: isoToday(), pidCashAccount: "", pidCounterAccount: "", amount: "", partyName: "", reference: "", description: "", reason: "", pidCostCenter: "" });
  useEffect(() => { getCashAccounts().then(setCashAccounts); getChart().then((c) => setChart(c.filter((a) => a.isGroup === 0 && a.isActive === 1))); }, []);
  /* The dimension is optional and the list is often empty, so a failure here
     leaves the picker out rather than stopping somebody writing a voucher. */
  useEffect(() => { getCostCenters().then(setCostCentres).catch(() => setCostCentres([])); }, []);
  useEffect(() => { if (!f.pidCashAccount && cashAccounts.length) setF((x) => ({ ...x, pidCashAccount: String((cashAccounts.find((a) => a.roleKey === "CASH_REGISTER") || cashAccounts[0]).pidAccount) })); }, [cashAccounts]); // eslint-disable-line react-hooks/exhaustive-deps
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const kind = VOUCHER_KINDS.find((k) => k.id === f.voucherKind);
  const isTransfer = f.voucherKind === "TRANSFER", needsCounter = ["RECEIPT", "PAYMENT", "TRANSFER"].includes(f.voucherKind), needsReason = ["OPENING", "ADJUSTMENT_IN", "ADJUSTMENT_OUT"].includes(f.voucherKind);
  const counterList = isTransfer ? cashAccounts : chart.filter((a) => (f.voucherKind === "RECEIPT" ? ["REVENUE", "LIABILITY", "EQUITY", "ASSET"] : ["EXPENSE", "ASSET", "LIABILITY"]).includes(a.accountType) && a.allowManual === 1 && !cashAccounts.some((c) => c.pidAccount === a.pidAccount));
  const submit = async () => {
    setBusy(true);
    try { onSaved(await createVoucher({ ...f, pidCashAccount: Number(f.pidCashAccount), pidCounterAccount: f.pidCounterAccount ? Number(f.pidCounterAccount) : null, amount: Number(f.amount), pidCostCenter: f.pidCostCenter ? Number(f.pidCostCenter) : null })); }
    catch (e) { addToast(humanError(e, "Could not save the voucher."), "error"); }
    finally { setBusy(false); }
  };
  return (
    <Modal title={kind?.label || "Voucher"} subtitle={needsReason ? "Creates cash the trading did not — needs a reason and Approve." : isTransfer ? "Moves money between two cash accounts; nothing enters or leaves the business." : ""} onClose={onClose} width={620}
      footer={<><HrButton onClick={onClose} disabled={busy}>Cancel</HrButton><HrButton variant="primary" busy={busy} onClick={submit} disabled={!f.amount || !f.pidCashAccount || (needsCounter && !f.pidCounterAccount) || (needsReason && !f.reason.trim())}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Kind</label><select value={f.voucherKind} onChange={(e) => { set("voucherKind", e.target.value); set("pidCounterAccount", ""); }}>{VOUCHER_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}</select></div>
        <div className="field"><label>Date</label><input type="date" value={f.voucherDate} onChange={(e) => set("voucherDate", e.target.value)} /></div>
        <div className="field"><label>{isTransfer ? "From" : "Cash / bank account"}</label><select value={f.pidCashAccount} onChange={(e) => set("pidCashAccount", e.target.value)}>{cashAccounts.map((a) => <option key={a.pidAccount} value={a.pidAccount}>{a.accountCode} {a.accountName}</option>)}</select></div>
        {needsCounter && <div className="field"><label>{isTransfer ? "To" : f.voucherKind === "RECEIPT" ? "Received for (account)" : "Paid for (account)"}</label>
          <select value={f.pidCounterAccount} onChange={(e) => set("pidCounterAccount", e.target.value)}><option value="">Choose…</option>{counterList.map((a) => <option key={a.pidAccount} value={a.pidAccount}>{a.accountCode} {a.accountName}</option>)}</select></div>}
        <div className="field"><label>Amount</label><input type="number" min="0.01" step="0.01" value={f.amount} onChange={(e) => set("amount", e.target.value)} className="hr-num" /></div>
        <div className="field"><label>Reference</label><input value={f.reference} onChange={(e) => set("reference", e.target.value)} placeholder="Slip, txn id…" /></div>
        {!isTransfer && !needsReason && <div className="field"><label>Party (optional)</label><input value={f.partyName} onChange={(e) => set("partyName", e.target.value)} placeholder="Who paid / was paid" /></div>}
        {/* Who the money was for, as against which branch it left — offered only
            once somebody has defined the centres, because an empty picker is a
            control that does nothing. */}
        {costCentres.length > 0 && !isTransfer && (
          <div className="field"><label>Cost centre (optional)</label>
            <select value={f.pidCostCenter} onChange={(e) => set("pidCostCenter", e.target.value)}>
              <option value="">— not set —</option>
              {costCentres.map((c) => <option key={c.pidCostCenter} value={c.pidCostCenter}>{c.ccCode} {c.ccName}</option>)}
            </select></div>
        )}
        <div className="field acc-span2"><label>Description</label><input value={f.description} onChange={(e) => set("description", e.target.value)} /></div>
        {needsReason && <div className="field acc-span2"><label>Reason</label><textarea rows={2} value={f.reason} onChange={(e) => set("reason", e.target.value)} placeholder="Why this cash exists — recorded with the voucher" /></div>}
      </div>
    </Modal>
  );
}

function BankAccounts({ perm, onChanged }) {
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(getSettings, []);
  const [editing, setEditing] = useState(null);
  const banks = data?.bankAccounts || [];
  const save = async (f) => {
    try { if (f.pidBankAccount) await updateBank(f.pidBankAccount, f); else await createBank(f); addToast("Bank account saved."); setEditing(null); reload(); onChanged(); }
    catch (e) { addToast(humanError(e), "error"); }
  };
  return (
    <>
      <div className="acc-actions">{perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setEditing({})}>Add bank account</HrButton>}</div>
      <HrTable rows={banks} rowKey={(b) => b.pidBankAccount} loading={busy} error={err} onRetry={reload} onRowClick={perm.canEdit ? setEditing : undefined} empty="No bank account yet. Add one and it gets its own ledger account under Bank."
        cols={[{ key: "bankName", label: "Bank", lead: true, render: (b) => <b>{b.bankName}</b> }, { key: "accountTitle", label: "Title" }, { key: "accountNo", label: "Account no" }, { key: "branchName", label: "Bank branch" },
          { key: "isDefault", label: "Default", render: (b) => (b.isDefault === 1 ? "Yes" : "") }, { key: "isActive", label: "Status", render: (b) => <Status code={b.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> }]} />
      {editing && <BankForm initial={editing} onClose={() => setEditing(null)} onSave={save} />}
    </>
  );
}

function BankForm({ initial, onClose, onSave }) {
  const [f, setF] = useState({ pidBankAccount: initial.pidBankAccount, bankName: initial.bankName || "", accountTitle: initial.accountTitle || "", accountNo: initial.accountNo || "", branchName: initial.branchName || "", isDefault: initial.isDefault ?? 0, isActive: initial.isActive ?? 1 });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <Modal title={initial.pidBankAccount ? initial.bankName : "New bank account"} onClose={onClose} width={520}
      footer={<><HrButton onClick={onClose}>Cancel</HrButton><HrButton variant="primary" onClick={() => onSave(f)} disabled={!f.bankName}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Bank</label><input value={f.bankName} onChange={(e) => set("bankName", e.target.value)} /></div>
        <div className="field"><label>Account title</label><input value={f.accountTitle} onChange={(e) => set("accountTitle", e.target.value)} /></div>
        <div className="field"><label>Account no</label><input value={f.accountNo} onChange={(e) => set("accountNo", e.target.value)} /></div>
        <div className="field"><label>Bank branch</label><input value={f.branchName} onChange={(e) => set("branchName", e.target.value)} /></div>
        <div className="field"><label>Default for bank payments</label><select value={f.isDefault} onChange={(e) => set("isDefault", Number(e.target.value))}><option value={0}>No</option><option value={1}>Yes</option></select></div>
        <div className="field"><label>Status</label><select value={f.isActive} onChange={(e) => set("isActive", Number(e.target.value))}><option value={1}>Active</option><option value={0}>Closed</option></select></div>
      </div>
    </Modal>
  );
}
