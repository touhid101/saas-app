/**
 * HrisForms.jsx — the five things an employee files for themselves.
 *
 * Each form is a dialog on the portal, not a page: the person is filing one
 * thing and going back to their overview. Every form has the same spine —
 * sections, inline validation, a footer that says what is about to be sent,
 * a Submit that says "Submitting…" and cannot be pressed twice, and a guard
 * that asks before a half-filled form is thrown away.
 *
 * Nothing here decides anything. The server counts leave days, checks the
 * balance and the overlap, numbers the request and starts the workflow; the
 * figures shown while typing are marked as estimates.
 */
import React, { useEffect, useMemo, useState } from "react";
import { Modal, Field } from "../../components/UI.jsx";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import {
  HrButton, HrFormSection, HrLines, PolicyNote, Status, Ic, humanError, P,
} from "../../components/HrKit.jsx";
import { money, days as dayNum, isoToday } from "../../services/hrService.js";

/** "3 days", "0.5 day", "—". */
const fmtDays = (n) => (n == null || n === "" ? "—" : `${dayNum(n)} ${Number(n) === 1 ? "day" : "days"}`);
import * as hris from "../../services/hrisService.js";
import { ReceiptGallery, ReceiptPick, ReceiptViewer, receiptItems, receiptProblem } from "../../components/ReceiptKit.jsx";

/* ── Shared ─────────────────────────────────────────────────────────────── */

/** A close that asks first once something has been typed. */
function useGuardedClose(dirty, onClose) {
  const confirm = useConfirm();
  return async () => {
    if (!dirty) return onClose();
    const ok = await confirm({
      title: "Leave without submitting?",
      body: "You have unsaved changes. If you leave now they are lost.",
      confirmLabel: "Leave", cancelLabel: "Stay", tone: "danger",
    });
    if (ok) onClose();
  };
}

const Problem = ({ children }) => (
  <div className="hr-lines-problem" role="status"><Ic path={P.alert} size={14} />{children}</div>
);

const Note = ({ children }) => (
  <div className="hr-form-note"><Ic path={P.info} size={14} /><span>{children}</span></div>
);

const daysBetween = (a, b) => {
  if (!a || !b) return 0;
  const d = (new Date(b + "T00:00:00") - new Date(a + "T00:00:00")) / 86400000;
  return d < 0 ? 0 : d + 1;
};

/* ═══════════════════════════════════════════════════════════════════════════
   LEAVE
   ═══════════════════════════════════════════════════════════════════════════ */

export function LeaveForm({ onClose, onDone, addToast }) {
  const [types, setTypes] = useState(null);
  const [balances, setBalances] = useState([]);
  const [f, setF] = useState({ leaveTypeId: "", fromDate: isoToday(), toDate: isoToday(), halfDay: false, reason: "" });
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const close = useGuardedClose(dirty, onClose);
  const set = (patch) => { setDirty(true); setF((x) => ({ ...x, ...patch })); };

  useEffect(() => {
    Promise.all([hris.getLeaveTypes().catch(() => []), hris.getLeaveBalances().catch(() => [])])
      .then(([t, b]) => {
        setTypes(t || []); setBalances(b || []);
        if (!f.leaveTypeId && t?.length) setF((x) => ({ ...x, leaveTypeId: String(t[0].id) }));
      });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const balance = balances.find((b) => String(b.leaveTypeId) === String(f.leaveTypeId));
  const requested = f.halfDay ? 0.5 : daysBetween(f.fromDate, f.toDate);
  const available = balance ? Number(balance.available || 0) : null;
  const remaining = available == null ? null : available - requested;

  const problems = [];
  if (!f.leaveTypeId) problems.push("Choose the kind of leave.");
  if (!f.fromDate || !f.toDate) problems.push("Both dates are needed.");
  else if (f.toDate < f.fromDate) problems.push("The last day is before the first.");
  if (f.halfDay && f.fromDate !== f.toDate) problems.push("A half day is one day — set the same date for both.");
  if (!f.reason.trim()) problems.push("Say briefly why.");

  const submit = async () => {
    if (busy || problems.length) return;
    setBusy(true);
    try {
      const saved = await hris.applyLeave({
        leaveTypeId: Number(f.leaveTypeId), fromDate: f.fromDate, toDate: f.toDate,
        isHalfDay: f.halfDay ? 1 : 0, reason: f.reason.trim(), submit: true,
      });
      addToast(`Leave request submitted — ${saved?.requestNo || ""} · ${saved?.requestStatusLabel || "Pending"}`);
      setDirty(false);
      onDone?.({ type: "LEAVE", id: saved?.id, requestNo: saved?.requestNo });
    } catch (err) {
      addToast(humanError(err, "The leave request was not accepted."), "error");
    } finally { setBusy(false); }
  };

  return (
    <Modal title="Apply for leave" subtitle="Time off, counted on the working calendar and taken from your balance" width={640} onClose={close}
           footer={<>
             <span className="hr-foot-status">
               {requested ? <><b>{fmtDays(requested)}</b> requested</> : "Choose the dates"}
               {available != null && requested > 0 && <> · <b className={remaining < 0 ? "is-over" : ""}>{fmtDays(Math.max(0, remaining))}</b> would remain</>}
             </span>
             <HrButton variant="ghost" onClick={close} disabled={busy}>Cancel</HrButton>
             <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Submitting…"
                       disabled={problems.length > 0 || types === null} title={problems[0]} onClick={submit}>
               Submit request
             </HrButton>
           </>}>
      <HrFormSection icon={P.calendar} title="The leave" hint="What kind, and which days.">
        <Field label="Kind of leave">
          <select value={f.leaveTypeId} onChange={(e) => set({ leaveTypeId: e.target.value })} disabled={types === null}>
            {types === null && <option value="">Loading…</option>}
            {types?.length === 0 && <option value="">No leave types are set up</option>}
            {(types || []).map((t) => <option key={t.id} value={t.id}>{t.typeName}{t.isPaid === 0 ? " (unpaid)" : ""}</option>)}
          </select>
        </Field>
        <Field label="Half day">
          <label className="hr-lines-check" style={{ minHeight: 38 }}>
            <input type="checkbox" checked={f.halfDay}
                   onChange={(e) => set({ halfDay: e.target.checked, toDate: e.target.checked ? f.fromDate : f.toDate })} />
            Just half of one day
          </label>
        </Field>
        <Field label="First day">
          <input type="date" value={f.fromDate} onChange={(e) => set({ fromDate: e.target.value, toDate: f.halfDay || f.toDate < e.target.value ? e.target.value : f.toDate })} />
        </Field>
        <Field label="Last day">
          <input type="date" value={f.toDate} min={f.fromDate} disabled={f.halfDay} onChange={(e) => set({ toDate: e.target.value })} />
        </Field>
        <div className="hr-form-full">
          <Field label="Reason">
            <textarea rows={2} value={f.reason} maxLength={500} placeholder="A sentence is enough — the approver reads it"
                      onChange={(e) => set({ reason: e.target.value })} />
          </Field>
        </div>
      </HrFormSection>

      {balance ? (
        <HrFormSection icon={P.wallet} title="Your balance" hint={`${balance.leaveTypeName} · ${balance.leaveYear}`}>
          <div className="hr-form-full">
            <div className="hris-balance-box">
              <div className="hris-stat"><span>Available</span><b>{fmtDays(available)}</b></div>
              <div className="hris-stat"><span>Requested</span><b>{fmtDays(requested)}</b><small>calendar days — the server counts working days</small></div>
              <div className="hris-stat"><span>Remaining</span><b className={remaining < 0 ? "is-over" : ""}>{fmtDays(remaining)}</b></div>
            </div>
            {remaining < 0 && <Problem>That is more than the balance holds. The request can still be filed; the approver will see it.</Problem>}
          </div>
        </HrFormSection>
      ) : f.leaveTypeId && types?.length > 0 ? (
        <Note>No balance has been opened for this kind of leave this year. The request is filed anyway and HR sees that.</Note>
      ) : null}
      {problems.length > 0 && dirty && <Problem>{problems[0]}</Problem>}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   EXPENSE CLAIM
   ═══════════════════════════════════════════════════════════════════════════ */

const emptyLine = (typeId) => ({ expenseTypeId: typeId || "", expenseDate: isoToday(), fromPlace: "", toPlace: "", description: "", claimedAmount: "", receiptFile: null });

export function ExpenseForm({ onClose, onDone, addToast }) {
  const [types, setTypes] = useState(null);
  const [f, setF] = useState({ claimDate: isoToday(), purpose: "", lines: [emptyLine("")] });
  /* Once saved as a draft the claim has lines with ids, and receipts can go on
     them. `draft` holds that saved claim; the form then shows step two. */
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [stage, setStage] = useState(null);       // what a save is doing right now
  const [dirty, setDirty] = useState(false);
  const [receiptBusy, setReceiptBusy] = useState(null);
  const [viewer, setViewer] = useState(null);      // { items, index }
  const confirm = useConfirm();
  const close = useGuardedClose(dirty && !draft, onClose);
  const set = (patch) => { setDirty(true); setF((x) => ({ ...x, ...patch })); };

  useEffect(() => {
    hris.getExpenseTypes().catch(() => []).then((t) => {
      setTypes(t || []);
      if (t?.length) setF((x) => ({ ...x, lines: x.lines.map((l) => ({ ...l, expenseTypeId: l.expenseTypeId || String(t[0].id) })) }));
    });
  }, []);

  const total = f.lines.reduce((s, l) => s + (Number(l.claimedAmount) || 0), 0);
  const badAmount = (l) => l.claimedAmount !== "" && !(Number(l.claimedAmount) > 0);
  const badDate = (l) => !l.expenseDate || l.expenseDate > isoToday();
  const problems = [];
  if (!f.lines.length) problems.push("Add at least one expense.");
  if (f.lines.some((l) => !l.expenseTypeId)) problems.push("Every expense needs a kind.");
  if (f.lines.some(badDate)) problems.push("Every expense needs a date that is not in the future.");
  if (f.lines.some((l) => !(Number(l.claimedAmount) > 0))) problems.push("Every expense needs an amount above nought.");

  const body = (submit) => ({
    claimDate: f.claimDate, purpose: f.purpose.trim() || null, submit,
    lines: f.lines.map((l) => ({
      expenseTypeId: Number(l.expenseTypeId), expenseDate: l.expenseDate,
      fromPlace: l.fromPlace || null, toPlace: l.toPlace || null, description: l.description || null,
      claimedAmount: Number(l.claimedAmount), receiptPath: null,
    })),
  });

  /* Receipts picked on the lines have nowhere to go until the claim exists,
     and the server takes them only while it is a draft. So a submit with
     receipts is: save as a draft, put the receipts up, then submit — one tap
     for the person. A receipt that is refused leaves the claim as a draft on
     the receipts step, so it can be tried again; nothing is submitted
     without the evidence that was chosen. */
  const save = async (submit) => {
    if (busy || problems.length) return;
    setBusy(true);
    try {
      const picked = f.lines.map((l) => l.receiptFile);
      const anyReceipt = picked.some(Boolean);
      let saved = await hris.fileExpense(body(submit && !anyReceipt));
      let failed = 0;
      if (anyReceipt && saved?.lines) {
        setStage("Attaching receipts…");
        for (let i = 0; i < picked.length; i++) {
          const file = picked[i], line = saved.lines[i];
          if (!file || !line?.id) continue;
          try {
            const up = await hris.attachReceipt(line.id, file);
            saved = { ...saved, lines: saved.lines.map((l) => (l.id === line.id ? { ...l, receiptPath: up?.receiptPath || l.receiptPath } : l)) };
          } catch (err) { failed += 1; addToast(humanError(err, `The receipt for line ${i + 1} was not accepted.`), "error"); }
        }
        if (submit && !failed) { setStage("Submitting…"); saved = (await hris.submitExpense(saved.id)) || saved; }
      }
      setDirty(false);
      if (submit && !failed) {
        addToast(`Expense claim submitted — ${saved?.claimNo || ""} · ${money(saved?.claimedAmount)}`);
        onDone?.({ type: "EXPENSE", id: saved?.id, requestNo: saved?.claimNo });
      } else {
        setDraft(saved);
        addToast(failed ? "Saved as a draft — attach the missing receipt, then submit." : `Saved as a draft — ${saved?.claimNo || ""}. Attach receipts, then submit.`, failed ? "warning" : undefined);
      }
    } catch (err) {
      addToast(humanError(err, "The claim was not accepted."), "error");
    } finally { setBusy(false); setStage(null); }
  };

  const submitDraft = async () => {
    if (busy || !draft) return;
    setBusy(true);
    try {
      const saved = await hris.submitExpense(draft.id);
      addToast(`Expense claim submitted — ${saved?.claimNo || ""}`);
      onDone?.({ type: "EXPENSE", id: saved?.id, requestNo: saved?.claimNo });
    } catch (err) {
      addToast(humanError(err, "The claim could not be submitted."), "error");
    } finally { setBusy(false); }
  };

  const onReceipt = async (lineId, file) => {
    if (!file) return;
    const problem = receiptProblem(file);
    if (problem) { addToast(problem, "error"); return; }
    setReceiptBusy(lineId);
    try {
      const line = await hris.attachReceipt(lineId, file);
      setDraft((d) => ({ ...d, lines: d.lines.map((l) => (l.id === lineId ? { ...l, receiptPath: line?.receiptPath || l.receiptPath } : l)) }));
      addToast("Receipt attached.");
    } catch (err) { addToast(humanError(err, "That file was not accepted."), "error"); }
    finally { setReceiptBusy(null); }
  };
  const dropReceipt = async (lineId) => {
    const ok = await confirm({ title: "Remove this receipt?", body: "The line stays; only the picture goes. Another can be attached while the claim is a draft.", confirmLabel: "Remove", tone: "danger" });
    if (!ok) return;
    setReceiptBusy(lineId);
    try {
      await hris.removeReceipt(lineId);
      setDraft((d) => ({ ...d, lines: d.lines.map((l) => (l.id === lineId ? { ...l, receiptPath: null } : l)) }));
    } catch (err) { addToast(humanError(err, "Could not remove it."), "error"); }
    finally { setReceiptBusy(null); }
  };
  const lookAt = (line) => {
    const items = receiptItems(draft?.lines || [], draft || {});
    const index = Math.max(0, items.findIndex((it) => it.lineId === line.id));
    if (items.length) setViewer({ items, index });
  };

  if (draft) {
    const needing = (draft.lines || []).filter((l) => !l.receiptPath).length;
    return (
      <Modal title={`Receipts — ${draft.claimNo || "draft"}`} subtitle="Attach a receipt to each line that has one, then submit the claim" width={720}
             onClose={() => { onDone?.({ type: "EXPENSE", id: draft.id, requestNo: draft.claimNo, quiet: true }); }}
             footer={<>
               <span className="hr-foot-status"><b>{money(draft.claimedAmount)}</b> · {(draft.lines || []).length} {(draft.lines || []).length === 1 ? "line" : "lines"}{needing ? ` · ${needing} without a receipt` : ""}</span>
               <HrButton variant="ghost" onClick={() => onDone?.({ type: "EXPENSE", id: draft.id, requestNo: draft.claimNo, quiet: true })} disabled={busy}>Keep as draft</HrButton>
               <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Submitting…" onClick={submitDraft}>Submit claim</HrButton>
             </>}>
        <ReceiptGallery lines={draft.lines || []} editable busyLine={receiptBusy}
                        onOpen={lookAt} onPick={onReceipt} onRemove={dropReceipt} />
        <Note>Whether a receipt is required depends on the kind of expense; a claim can be submitted without, and the approver decides.</Note>
        {viewer && (
          <ReceiptViewer items={viewer.items} index={viewer.index}
                         onIndex={(index) => setViewer({ ...viewer, index })} onClose={() => setViewer(null)} />
        )}
      </Modal>
    );
  }

  return (
    <Modal title="Expense claim" subtitle="Money you spent on the pharmacy's behalf, to be paid back — never through a payslip" width={860} onClose={close}
           footer={<>
             <span className="hr-foot-status">
               <b>{f.lines.length}</b> {f.lines.length === 1 ? "expense" : "expenses"} · <b>{money(total)}</b>
               {f.lines.some((l) => l.receiptFile) && <> · <b>{f.lines.filter((l) => l.receiptFile).length}</b> {f.lines.filter((l) => l.receiptFile).length === 1 ? "receipt" : "receipts"}</>}
             </span>
             <HrButton variant="ghost" onClick={close} disabled={busy}>Cancel</HrButton>
             <HrButton disabled={problems.length > 0 || busy} title={problems[0] || "Keep it as a draft — receipts can still be attached"} onClick={() => save(false)}>
               Save as a draft
             </HrButton>
             <HrButton variant="primary" icon={P.check} busy={busy} busyLabel={stage || "Submitting…"} disabled={problems.length > 0} title={problems[0]} onClick={() => save(true)}>
               Submit claim
             </HrButton>
           </>}>
      <HrFormSection icon={P.receipt} title="The claim" hint="When it is filed, and what the spending was for.">
        <Field label="Filed on"><input type="date" max={isoToday()} value={f.claimDate} onChange={(e) => set({ claimDate: e.target.value })} /></Field>
        <Field label="Purpose"><input value={f.purpose} placeholder="Optional — a delivery round, a market visit" onChange={(e) => set({ purpose: e.target.value })} /></Field>
      </HrFormSection>
      <HrFormSection icon={P.doc} title={`Expenses · ${f.lines.length}`} hint="One line per journey or receipt. The total is worked out from the lines.">
        <div className="hr-form-full">
          <HrLines
            cols={[
              { key: "kind", label: "Kind", width: "22%" }, { key: "date", label: "Date", width: "18%" },
              { key: "from", label: "From" }, { key: "to", label: "To" }, { key: "amount", label: "Amount", num: true, width: "14%" },
              { key: "receipt", label: "Receipt", width: "15%" },
            ]}
            rows={f.lines}
            onRemove={(i) => set({ lines: f.lines.filter((_, j) => j !== i) })}
            onAdd={() => set({ lines: [...f.lines, emptyLine(types?.[0]?.id ? String(types[0].id) : "")] })}
            addLabel="Add an expense"
            empty="No expenses yet — add the first one."
            cell={(l, i, key) => {
              const patch = (k, v) => set({ lines: f.lines.map((x, j) => (j === i ? { ...x, [k]: v } : x)) });
              switch (key) {
                case "kind": return (
                  <select value={l.expenseTypeId} aria-label="Kind of expense" onChange={(e) => patch("expenseTypeId", e.target.value)}>
                    {(types || []).map((t) => <option key={t.id} value={t.id}>{t.typeName}</option>)}
                  </select>);
                case "date": return <input type="date" max={isoToday()} aria-label="Date" value={l.expenseDate} className={badDate(l) && dirty ? "is-invalid" : ""} onChange={(e) => patch("expenseDate", e.target.value)} />;
                case "from": return <input placeholder="From" aria-label="From" value={l.fromPlace} onChange={(e) => patch("fromPlace", e.target.value)} />;
                case "to": return <input placeholder="To" aria-label="To" value={l.toPlace} onChange={(e) => patch("toPlace", e.target.value)} />;
                case "amount": return <input type="number" min="0" step="0.01" placeholder="0.00" aria-label="Amount" value={l.claimedAmount} className={badAmount(l) ? "is-invalid" : ""} onChange={(e) => patch("claimedAmount", e.target.value)} />;
                case "receipt": return (
                  <ReceiptPick file={l.receiptFile}
                               onPick={(file) => { if (!file) return; const p = receiptProblem(file); if (p) { addToast(p, "error"); return; } patch("receiptFile", file); }}
                               onClear={() => patch("receiptFile", null)} />);
                default: return null;
              }
            }}
          />
          {f.lines.some((l) => badAmount(l) || (l.expenseDate && l.expenseDate > isoToday())) && (
            <Problem>{f.lines.some(badAmount) ? "An amount has to be above nought." : "An expense cannot be dated in the future."}</Problem>
          )}
          <div className="hr-summary">
            <div className="hr-summary-box">
              <div className="hr-summary-row"><span>Expenses</span><b>{f.lines.length}</b></div>
              <div className="hr-summary-row is-grand"><span>Claimed</span><b>{money(total)}</b></div>
            </div>
          </div>
          <Note>Attach the receipt on each line, or save as a draft and add them after. A receipt can be changed only while the claim is a draft.</Note>
        </div>
      </HrFormSection>
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ADVANCE
   ═══════════════════════════════════════════════════════════════════════════ */

export function AdvanceForm({ onClose, onDone, addToast, outstanding = 0 }) {
  const [f, setF] = useState({ amount: "", installments: "1", recoverFrom: "", reason: "" });
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const close = useGuardedClose(dirty, onClose);
  const set = (patch) => { setDirty(true); setF((x) => ({ ...x, ...patch })); };

  const amount = Number(f.amount) || 0;
  const n = Math.max(1, Number(f.installments) || 1);
  const problems = [];
  if (!(amount > 0)) problems.push("Enter the amount you are asking for.");
  if (!(Number(f.installments) >= 1)) problems.push("Recovery is over at least one payslip.");
  if (!f.reason.trim()) problems.push("Say briefly what it is for.");

  const submit = async () => {
    if (busy || problems.length) return;
    setBusy(true);
    try {
      const saved = await hris.requestAdvance({
        amount, installments: n, recoverFrom: f.recoverFrom || null, reason: f.reason.trim(), submit: true,
      });
      addToast(`Advance requested — ${saved?.advanceNo || ""} · ${money(saved?.amount)}`);
      setDirty(false);
      onDone?.({ type: "ADVANCE", id: saved?.id, requestNo: saved?.advanceNo });
    } catch (err) {
      addToast(humanError(err, "The advance request was not accepted."), "error");
    } finally { setBusy(false); }
  };

  return (
    <Modal title="Request an advance" subtitle="Salary paid ahead of the payslip, and taken back from the next ones" width={600} onClose={close}
           footer={<>
             <span className="hr-foot-status">{amount > 0 ? <><b>{money(amount)}</b> · {n} × {money(amount / n)}</> : "Enter an amount"}</span>
             <HrButton variant="ghost" onClick={close} disabled={busy}>Cancel</HrButton>
             <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Submitting…" disabled={problems.length > 0} title={problems[0]} onClick={submit}>Submit request</HrButton>
           </>}>
      <HrFormSection icon={P.wallet} title="The advance" hint="How much, and how it is recovered.">
        <Field label="Amount"><input type="number" min="0" step="0.01" value={f.amount} placeholder="0.00" onChange={(e) => set({ amount: e.target.value })} /></Field>
        <Field label="Recovered over (payslips)"><input type="number" min="1" step="1" value={f.installments} onChange={(e) => set({ installments: e.target.value })} /></Field>
        <Field label="Start recovering from" hint="Blank means the next payroll."><input type="date" value={f.recoverFrom} onChange={(e) => set({ recoverFrom: e.target.value })} /></Field>
        <div className="hr-form-full">
          <Field label="Reason"><textarea rows={2} maxLength={500} value={f.reason} placeholder="A sentence is enough" onChange={(e) => set({ reason: e.target.value })} /></Field>
        </div>
        <div className="hr-form-full">
          <div className="hris-balance-box">
            <div className="hris-stat"><span>Outstanding now</span><b>{money(outstanding)}</b><small>advances still being recovered</small></div>
            <div className="hris-stat"><span>This request</span><b>{money(amount)}</b></div>
            <div className="hris-stat"><span>Per payslip</span><b>{money(amount > 0 ? amount / n : 0)}</b><small>the approver may change the amount</small></div>
          </div>
        </div>
      </HrFormSection>
      {problems.length > 0 && dirty && <Problem>{problems[0]}</Problem>}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   LOAN
   ═══════════════════════════════════════════════════════════════════════════ */

export function LoanForm({ onClose, onDone, addToast, outstanding = 0 }) {
  const [f, setF] = useState({ principal: "", installments: "6", startDate: "", purpose: "" });
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const close = useGuardedClose(dirty, onClose);
  const set = (patch) => { setDirty(true); setF((x) => ({ ...x, ...patch })); };

  const amount = Number(f.principal) || 0;
  const n = Math.max(1, Number(f.installments) || 1);
  const problems = [];
  if (!(amount > 0)) problems.push("Enter the amount you are asking for.");
  if (!(Number(f.installments) >= 1)) problems.push("Repayment is over at least one instalment.");
  if (!f.purpose.trim()) problems.push("Say briefly what it is for.");

  const submit = async () => {
    if (busy || problems.length) return;
    setBusy(true);
    try {
      const saved = await hris.requestLoan({
        principal: amount, installments: n, startDate: f.startDate || null, interestRate: null, purpose: f.purpose.trim(), submit: true,
      });
      addToast(`Loan requested — ${saved?.loanNo || ""} · ${money(saved?.principal)}`);
      setDirty(false);
      onDone?.({ type: "LOAN", id: saved?.id, requestNo: saved?.loanNo });
    } catch (err) {
      addToast(humanError(err, "The loan request was not accepted."), "error");
    } finally { setBusy(false); }
  };

  return (
    <Modal title="Request a loan" subtitle="Repaid by instalments that payroll deducts each month" width={600} onClose={close}
           footer={<>
             <span className="hr-foot-status">{amount > 0 ? <><b>{money(amount)}</b> · {n} × {money(amount / n)}</> : "Enter an amount"}</span>
             <HrButton variant="ghost" onClick={close} disabled={busy}>Cancel</HrButton>
             <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Submitting…" disabled={problems.length > 0} title={problems[0]} onClick={submit}>Submit request</HrButton>
           </>}>
      <HrFormSection icon={P.money} title="The loan" hint="How much, over how long.">
        <Field label="Amount"><input type="number" min="0" step="0.01" value={f.principal} placeholder="0.00" onChange={(e) => set({ principal: e.target.value })} /></Field>
        <Field label="Instalments"><input type="number" min="1" step="1" value={f.installments} onChange={(e) => set({ installments: e.target.value })} /></Field>
        <Field label="First instalment" hint="Blank means the next payroll."><input type="date" value={f.startDate} onChange={(e) => set({ startDate: e.target.value })} /></Field>
        <div className="hr-form-full">
          <Field label="Purpose"><textarea rows={2} maxLength={500} value={f.purpose} placeholder="A sentence is enough" onChange={(e) => set({ purpose: e.target.value })} /></Field>
        </div>
        <div className="hr-form-full">
          <div className="hris-balance-box">
            <div className="hris-stat"><span>Outstanding now</span><b>{money(outstanding)}</b><small>loans still being repaid</small></div>
            <div className="hris-stat"><span>This request</span><b>{money(amount)}</b></div>
            <div className="hris-stat"><span>Per instalment</span><b>{money(amount > 0 ? amount / n : 0)}</b><small>the schedule is written on approval</small></div>
          </div>
        </div>
      </HrFormSection>
      {problems.length > 0 && dirty && <Problem>{problems[0]}</Problem>}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ATTENDANCE CORRECTION
   ═══════════════════════════════════════════════════════════════════════════ */

const STATUSES = [
  ["PRESENT", "Present"], ["LATE", "Late"], ["HALF_DAY", "Half day"], ["EARLY_LEAVE", "Left early"], ["ABSENT", "Absent"],
];
const timeOf = (iso) => (iso ? String(iso).slice(11, 16) : "");

/**
 * Asks for one of my recorded days to be corrected.
 *
 * <p>Only a recorded day can be corrected — a proposal needs a row to sit
 * beside. An unrecorded day is said so, plainly, rather than a row invented.
 */
export function CorrectionForm({ onClose, onDone, addToast, rows = [], initial = null }) {
  const recorded = useMemo(() => rows.filter((r) => r.id && !["HOLIDAY", "WEEKEND"].includes(r.attendanceStatus)).slice().reverse(), [rows]);
  const [pick, setPick] = useState(initial?.id ? String(initial.id) : (recorded[0]?.id ? String(recorded[0].id) : ""));
  const day = recorded.find((r) => String(r.id) === pick) || initial;
  const [f, setF] = useState({ status: "", checkIn: "", checkOut: "", reason: "" });
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const close = useGuardedClose(dirty, onClose);
  const set = (patch) => { setDirty(true); setF((x) => ({ ...x, ...patch })); };

  useEffect(() => {
    if (day) setF((x) => ({ ...x, status: day.attendanceStatus || "PRESENT", checkIn: timeOf(day.checkIn), checkOut: timeOf(day.checkOut) }));
  }, [day?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const problems = [];
  if (!day) problems.push("Choose a recorded day.");
  else if (day.correctionStatus === "REQUESTED") problems.push("A correction for this day is already waiting.");
  else if (day.leaveRequestId) problems.push("This day came from approved leave — cancel the leave instead.");
  if (f.checkIn && f.checkOut && f.checkOut <= f.checkIn) problems.push("Check-out has to be after check-in.");
  if (!f.reason.trim()) problems.push("Say what was wrong.");
  const unchanged = day && f.status === (day.attendanceStatus || "") && f.checkIn === timeOf(day.checkIn) && f.checkOut === timeOf(day.checkOut);
  if (unchanged) problems.push("Nothing has been changed yet.");

  const submit = async () => {
    if (busy || problems.length) return;
    setBusy(true);
    try {
      const d = day.attendanceDate;
      const saved = await hris.requestCorrection(day.id, {
        attendanceStatus: f.status || null,
        checkIn: f.checkIn ? `${d}T${f.checkIn}:00` : null,
        checkOut: f.checkOut ? `${d}T${f.checkOut}:00` : null,
        overtimeMinutes: null,
        reason: f.reason.trim(),
      });
      addToast(`Correction requested for ${saved?.attendanceDate || d}`);
      setDirty(false);
      onDone?.({ type: "ATTENDANCE", id: saved?.id, requestNo: saved?.attendanceDate });
    } catch (err) {
      addToast(humanError(err, "The correction was not accepted."), "error");
    } finally { setBusy(false); }
  };

  return (
    <Modal title="Attendance correction" subtitle="Ask for a recorded day to be put right — the day stays as it is until somebody decides" width={640} onClose={close}
           footer={<>
             <span className="hr-foot-status">{day ? <>Correcting <b>{day.attendanceDate}</b></> : "Choose a day"}</span>
             <HrButton variant="ghost" onClick={close} disabled={busy}>Cancel</HrButton>
             <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Submitting…" disabled={problems.length > 0} title={problems[0]} onClick={submit}>Submit request</HrButton>
           </>}>
      <HrFormSection icon={P.calendar} title="The day" hint="Only a recorded day can be corrected. A day nobody has recorded yet has nothing to correct — ask your manager to record it first.">
        <div className="hr-form-full">
          <Field label="Recorded day">
            <select value={pick} onChange={(e) => { setPick(e.target.value); setDirty(true); }}>
              {recorded.length === 0 && <option value="">No recorded days in the last two months</option>}
              {recorded.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.attendanceDate} · {r.attendanceStatusLabel || r.attendanceStatus}{r.correctionStatus === "REQUESTED" ? " · correction pending" : ""}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {day && (
          <div className="hr-form-full">
            <div className="hris-readonly">
              <div className="hris-stat"><span>Recorded as</span><b><Status code={day.attendanceStatus} label={day.attendanceStatusLabel} /></b></div>
              <div className="hris-stat"><span>Check-in</span><b>{timeOf(day.checkIn) || "—"}</b>{day.source && <small>{day.source.toLowerCase()} record</small>}</div>
              <div className="hris-stat"><span>Check-out</span><b>{timeOf(day.checkOut) || "—"}</b></div>
            </div>
          </div>
        )}
      </HrFormSection>
      <HrFormSection icon={P.edit} title="What it should say" hint="Leave a field as it is to keep what was recorded.">
        <Field label="Status">
          <select value={f.status} onChange={(e) => set({ status: e.target.value })} disabled={!day}>
            {STATUSES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </Field>
        <div />
        <Field label="Check-in"><input type="time" value={f.checkIn} disabled={!day} onChange={(e) => set({ checkIn: e.target.value })} /></Field>
        <Field label="Check-out"><input type="time" value={f.checkOut} disabled={!day} onChange={(e) => set({ checkOut: e.target.value })} /></Field>
        <div className="hr-form-full">
          <Field label="What was wrong"><textarea rows={2} maxLength={500} value={f.reason} disabled={!day} placeholder="e.g. The device did not read my card; I was in by 9:05" onChange={(e) => set({ reason: e.target.value })} /></Field>
        </div>
      </HrFormSection>
      {problems.length > 0 && dirty && <Problem>{problems[0]}</Problem>}
      <PolicyNote>
        The record keeps saying what it says while the request waits. If it is approved the day is rewritten
        and the worked hours recomputed; if it is refused, nothing moves and the reason is shown to you.
      </PolicyNote>
    </Modal>
  );
}
