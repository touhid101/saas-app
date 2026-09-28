/**
 * HrExpenses.jsx — conveyance and expense claims (MOD37).
 *
 * <b>A reimbursement is not pay.</b> Money somebody spent on the pharmacy's
 * behalf is being returned, not earned, so an approved claim is paid on its own
 * and never appears on a payslip. Putting it in the gross would put it inside
 * the taxable base and inside the provident fund base, and the pharmacy would
 * deduct PF from a rickshaw fare.
 *
 * A claim is a header and its lines because a week of deliveries is six
 * journeys on six dates, not six claims. The totals shown are always the sum of
 * the lines — nothing on this screen lets somebody type a total, because a total
 * that disagrees with its own detail is worse than no total.
 *
 * Approval is per line: an approver who allows the fares and trims the dinner
 * has made two decisions, and both are entered where they belong.
 *
 * Three sections, one on screen at a time: the queue of claims waiting on a
 * decision (searched and paged here — it is what an approver opens the page
 * for), every claim at the branch (paged on the server, narrowed by person and
 * status), and the kinds of expense.
 */
import React, { useState, useEffect, useCallback } from "react";
import { Card, Modal, Field } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import {
  HrPage, Kpis, Kpi, HrTable, Status, HrTabs, HrTabBar, Filter, HrFilterBar, HrSearch,
  Facts, Fact, PolicyNote, HrButton, HrDrawer, HrPagination, HrEmployeePicker, HrLines,
  HrFormSection, HrPageSkeleton, HrErrorState, Ic, usePagedList, useLocalPage, humanError, P, HR_CRUMB,
} from "../components/HrKit.jsx";
import {
  getExpenseTypes, createExpenseType, updateExpenseType, retireExpenseType,
  searchExpenseClaims, getPendingExpenses, getExpenseClaim,
  fileExpenseClaim, submitExpenseClaim, approveExpenseClaim, rejectExpenseClaim,
  payExpenseClaim, cancelExpenseClaim, attachReceipt, removeReceipt,
  getSummary, searchEmployees, money, isoToday,
} from "../services/hrService.js";
import { ReceiptCell, ReceiptGallery, ReceiptPick, ReceiptViewer, receiptItems, receiptProblem } from "../components/ReceiptKit.jsx";

const searchPeople = async (q) => (await searchEmployees(q)).items;
const fmt = (n) => Number(n || 0).toLocaleString("en-US");
const emptyLine = (typeId) => ({
  expenseTypeId: typeId || "", expenseDate: isoToday(),
  fromPlace: "", toPlace: "", description: "", claimedAmount: "",
  receiptFile: null,   // chosen now, uploaded once the line exists
});
const covering = (r) => (r.periodFrom === r.periodTo ? r.periodFrom : `${r.periodFrom} – ${r.periodTo}`);

export default function HrExpenses() {
  const { addToast } = useApp();
  const confirm = useConfirm();
  const perm = usePermission("expenses");

  const [pane, setPane]       = useState("queue");
  const [ready, setReady]     = useState(false);
  const [loadErr, setLoadErr] = useState(null);
  const [types, setTypes]     = useState([]);
  const [queue, setQueue]     = useState([]);
  const [summary, setSummary] = useState(null);
  const [unpaid, setUnpaid]   = useState(0);
  const [reloadToken, setReloadToken] = useState(0);
  const bump = () => setReloadToken((n) => n + 1);

  const [queueQ, setQueueQ] = useState("");
  const [who, setWho]       = useState("");
  const [status, setStatus] = useState("");

  const [form, setForm]         = useState(null);   // a claim being written
  const [saving, setSaving]     = useState(null);   // what the form is doing right now, while it is
  const [open, setOpen]         = useState(null);   // a claim being read
  const [typeForm, setTypeForm] = useState(null);
  const [payForm, setPayForm]   = useState(null);
  const [decision, setDecision] = useState(null);   // a claim being decided
  const [receiptBusy, setReceiptBusy] = useState(null);   // the line a receipt is going up for
  const [viewer, setViewer] = useState(null);             // { items, index } — the receipt being looked at

  const loadShared = useCallback(async () => {
    try {
      const [t, q, s, u] = await Promise.all([
        getExpenseTypes(false).catch(() => []),
        getPendingExpenses().catch(() => []),
        getSummary().catch(() => null),
        searchExpenseClaims({ status: "APPROVED", page: 0, size: 1 }).catch(() => null),
      ]);
      setTypes(t || []);
      setQueue(q || []);
      setSummary(s);
      setUnpaid(u?.total || 0);
      setLoadErr(null);
    } catch (err) {
      setLoadErr(humanError(err, "Expense claims could not be loaded."));
    } finally {
      setReady(true);
    }
  }, []);
  useEffect(() => { loadShared(); }, [loadShared, reloadToken]);

  const all = usePagedList(
    ({ page, size }) => searchExpenseClaims({ page, size, status: status || undefined, employeeId: who || undefined }),
    [status, who, reloadToken]);

  const queued = useLocalPage(queue, {
    q: queueQ,
    match: (r, q) => [r.empName, r.empId, r.purpose].some((v) => String(v || "").toLowerCase().includes(q)),
  });

  const activeTypes = types.filter((t) => t.status === "Y");

  /* ── Writing a claim ─────────────────────────────────────────────────── */

  const lineTotal = (f) => (f?.lines || []).reduce((s, l) => s + (Number(l.claimedAmount) || 0), 0);

  /* Why the claim cannot be submitted yet, in the order the reader fixes it.
     Every rule here is one the server enforces; saying it first spares a
     round trip that ends in a red toast. */
  const claimProblems = (f) => {
    const out = [];
    if (!f?.employeeId) out.push("Choose who is claiming.");
    const lines = f?.lines || [];
    if (!lines.length) out.push("Add at least one expense.");
    if (lines.some((l) => !l.expenseTypeId)) out.push("Every expense needs a kind.");
    if (lines.some((l) => !l.expenseDate)) out.push("Every expense needs a date.");
    if (lines.some((l) => l.expenseDate && l.expenseDate > isoToday())) out.push("An expense cannot be dated in the future.");
    if (lines.some((l) => !(Number(l.claimedAmount) > 0))) out.push("Every expense needs an amount above nought.");
    return out;
  };
  const badAmount = (l) => l.claimedAmount !== "" && !(Number(l.claimedAmount) > 0);
  const badDate = (l) => !l.expenseDate || l.expenseDate > isoToday();

  const saveClaim = async (submit) => {
    try {
      const body = {
        employeeId: Number(form.employeeId),
        claimDate: form.claimDate,
        purpose: form.purpose || null,
        lines: (form.lines || []).map((l) => ({
          expenseTypeId: Number(l.expenseTypeId),
          expenseDate: l.expenseDate,
          fromPlace: l.fromPlace || null,
          toPlace: l.toPlace || null,
          description: l.description || null,
          claimedAmount: Number(l.claimedAmount),
          receiptPath: l.receiptPath || null,
        })),
        submit,
      };
      /* Receipts picked in the form have no line to belong to until the claim
         is saved, and the server takes them only while the claim is a draft.
         So: save as a draft, put the receipts up, then submit if that was
         asked — one click for the person, three calls underneath. A receipt
         that fails leaves the claim as a draft, opened, with the card to try
         again; nothing is submitted without the evidence that was chosen. */
      const picked = (form.lines || []).map((l) => l.receiptFile);
      const anyReceipt = picked.some(Boolean);
      const saved = await fileExpenseClaim({ ...body, submit: submit && !anyReceipt });
      let failed = 0;
      if (anyReceipt && saved?.lines) {
        setSaving("Attaching receipts…");
        for (let i = 0; i < picked.length; i++) {
          const file = picked[i], line = saved.lines[i];
          if (!file || !line?.id) continue;
          try { await attachReceipt(line.id, file); }
          catch (err) { failed += 1; addToast(humanError(err, `The receipt for line ${i + 1} was not accepted.`), "error"); }
        }
        if (submit && !failed) {
          setSaving("Submitting…");
          await submitExpenseClaim(saved.id);
        }
      }
      setForm(null);
      bump();
      if (submit && !failed) addToast("Claim submitted.");
      else if (submit) addToast("Saved as a draft — attach the missing receipt, then submit.", "warning");
      else addToast("Claim saved as a draft.");
      /* A draft is opened straight away: the cards are where receipts go. */
      if ((!submit || failed) && saved?.id) openClaim(saved.id);
    } catch (err) {
      addToast(humanError(err, "Could not save that claim."), "error");
    } finally {
      setSaving(null);
    }
  };

  const openClaim = async (id) => {
    try { setOpen(await getExpenseClaim(id)); }
    catch (err) { addToast(humanError(err, "Could not open that claim."), "error"); }
  };

  /* ── Receipts ────────────────────────────────────────────────────────── */

  /* The same checks the server makes, said before the round trip. The claim
     is re-read afterwards so the thumbnail, the gallery and the count all
     come from the one record. */
  const pickReceipt = async (lineId, file) => {
    if (!file || !lineId) return;
    const problem = receiptProblem(file);
    if (problem) { addToast(problem, "error"); return; }
    setReceiptBusy(lineId);
    try {
      await attachReceipt(lineId, file);
      addToast("Receipt attached.");
      if (open) await openClaim(open.id);
    } catch (err) {
      addToast(humanError(err, "That image was not accepted."), "error");
    } finally {
      setReceiptBusy(null);
    }
  };

  const dropReceipt = async (lineId) => {
    const ok = await confirm({
      title: "Remove this receipt?",
      body: "The line stays; only the picture goes. Another can be attached while the claim is a draft.",
      confirmLabel: "Remove", tone: "danger",
    });
    if (!ok) return;
    setReceiptBusy(lineId);
    try {
      await removeReceipt(lineId);
      addToast("Receipt removed.");
      if (open) await openClaim(open.id);
    } catch (err) {
      addToast(humanError(err, "Could not remove it."), "error");
    } finally {
      setReceiptBusy(null);
    }
  };

  /* Look at a receipt: every receipt on the claim is in the viewer, opened on
     the one that was clicked, so the approver can page through them. */
  const lookAt = (lines, claim, line) => {
    const items = receiptItems(lines, claim);
    const index = Math.max(0, items.findIndex((it) => it.lineId === line.id));
    if (items.length) setViewer({ items, index });
  };

  /* ── Deciding ────────────────────────────────────────────────────────── */

  const decide = async () => {
    try {
      await approveExpenseClaim(decision.id, {
        lines: (decision.lines || []).map((l) => ({
          lineId: l.id,
          approvedAmount: l.approvedAmount === "" ? null : Number(l.approvedAmount),
        })),
        remarks: decision.remarks || null,
      });
      setDecision(null);
      setOpen(null);
      addToast("Claim approved.");
      bump();
    } catch (err) {
      addToast(humanError(err, "That did not go through."), "error");
    }
  };

  const act = async (fn, label) => {
    try { await fn(); addToast(label); setOpen(null); bump(); }
    catch (err) { addToast(humanError(err, "That did not go through."), "error"); }
  };

  const reject = async (row) => {
    const ok = await confirm({
      title: "Reject this claim?",
      body: "Nothing is reimbursed. The claim stays on the record as refused.",
      confirmLabel: "Reject", tone: "danger",
    });
    if (ok) act(() => rejectExpenseClaim(row.id, "Not accepted"), "Claim rejected.");
  };

  const cancel = async (row) => {
    const ok = await confirm({
      title: "Cancel this claim?", body: "The claim is withdrawn. Nothing is reimbursed.",
      confirmLabel: "Cancel claim", tone: "danger",
    });
    if (ok) act(() => cancelExpenseClaim(row.id, "Withdrawn"), "Claim cancelled.");
  };

  /* ── Types ───────────────────────────────────────────────────────────── */

  const saveType = async () => {
    try {
      const body = {
        typeCode: typeForm.typeCode,
        typeName: typeForm.typeName,
        description: typeForm.description || null,
        /* "" is sent as null, because clearing a cap is a decision to stop
           capping and a blank is not a limit of nought. */
        maxPerClaim: typeForm.maxPerClaim === "" ? null : Number(typeForm.maxPerClaim),
        maxPerDay: typeForm.maxPerDay === "" ? null : Number(typeForm.maxPerDay),
        requiresReceipt: typeForm.requiresReceipt ? 1 : 0,
        sortOrder: typeForm.sortOrder ?? 100,
      };
      if (typeForm.id) await updateExpenseType(typeForm.id, body);
      else await createExpenseType(body);
      setTypeForm(null);
      addToast("Expense type saved.");
      bump();
    } catch (err) {
      addToast(humanError(err, "Could not save that type."), "error");
    }
  };

  const retire = async (t) => {
    const ok = await confirm({
      title: `Retire ${t.typeName}?`,
      body: "It stops being offered on new claims. Claims already filed keep pointing at it, so "
          + "last year's spending still says what it was on.",
      confirmLabel: "Retire", tone: "danger",
    });
    if (ok) act(() => retireExpenseType(t.id), "Expense type retired.");
  };

  if (!ready) return <HrPageSkeleton label="Loading expense claims…" kpis={3} />;
  if (loadErr && !types.length && !summary) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Expense Claims" }]} title="Expense Claims">
        <HrErrorState title="Expense claims could not be loaded" hint={loadErr} onRetry={loadShared} />
      </HrPage>
    );
  }

  const pending = summary?.pendingExpenseClaims ?? queue.length;
  const awaiting = queue.reduce((s, c) => s + Number(c.claimedAmount || 0), 0);
  const tabs = [
    { id: "queue", label: "Waiting", icon: P.inbox, badge: pending },
    { id: "all",   label: "All claims", icon: P.doc },
    { id: "types", label: "Kinds", icon: P.receipt },
  ];

  const showOnly = (s) => { setStatus(s); setWho(""); setPane("all"); };
  const reimburse = (r) => setPayForm({
    id: r.id, empName: r.empName, amount: r.approvedAmount,
    paymentMode: "CASH", paidDate: isoToday(), paymentReference: "",
  });

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Expense Claims" }]}
      title="Expense Claims"
      subtitle="What staff spent on the pharmacy's behalf, and getting it back"
      actions={
        <>
          <HrButton iconOnly icon={P.reset} title="Refresh" onClick={bump} />
          {perm.canCreate && (
            <HrButton variant="primary" icon={P.plus} onClick={() => setForm({
              employeeId: "", claimDate: isoToday(), purpose: "",
              lines: [emptyLine(activeTypes[0]?.id)],
            })}>
              File a claim
            </HrButton>
          )}
        </>
      }>

      <Kpis>
        <Kpi label="Awaiting a decision" value={fmt(pending)} note={pending ? money(awaiting) : "Nothing waiting"}
             icon={P.clock} warn={pending > 0} onClick={() => setPane("queue")} />
        <Kpi label="Approved, not yet paid" value={fmt(unpaid)} warn={unpaid > 0} icon={P.wallet}
             onClick={() => showOnly("APPROVED")} note={unpaid ? "Click to show only these" : "Everything approved is paid"} />
        <Kpi label="Kinds of expense" value={fmt(activeTypes.length)} icon={P.receipt}
             onClick={() => setPane("types")} note="Open the list" />
      </Kpis>

      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "queue" && (
        <Card title={`Waiting on a decision · ${fmt(queued.total)}`}
              right={<HrSearch value={queueQ} onChange={setQueueQ} placeholder="Name, code or purpose" />}>
          <HrTable
            rowKey={(r) => r.id}
            rows={queued.rows}
            onRowClick={(r) => openClaim(r.id)}
            caption="Expense claims waiting for a decision"
            empty={queueQ
              ? { icon: P.filter, title: "Nobody waiting matches that", hint: "Try another spelling, or clear the search.",
                  action: <HrButton icon={P.reset} onClick={() => setQueueQ("")}>Clear</HrButton> }
              : { icon: P.check, title: "Nothing is waiting", hint: "Every submitted claim has been decided." }}
            cols={[
              { key: "empName", label: "Employee", lead: true, sortable: true },
              { key: "period", label: "Covering", render: covering },
              { key: "lineCount", label: "Items", num: true },
              { key: "claimedAmount", label: "Claimed", num: true, render: (r) => money(r.claimedAmount) },
              { key: "purpose", label: "Purpose", advisory: true },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrButton size="sm" variant="primary" icon={P.eye} onClick={() => openClaim(r.id)}>Review</HrButton>
                ) },
            ]}
          />
          <HrPagination page={queued.page} size={queued.size} total={queued.total} totalPages={queued.totalPages}
                        onPage={queued.setPage} onSize={queued.setSize} />
        </Card>
      )}

      {pane === "all" && (
        <Card title={`Claims · ${fmt(all.total)}`}>
          <HrFilterBar active={(status ? 1 : 0) + (who ? 1 : 0)} onReset={() => { setStatus(""); setWho(""); }}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={who} onChange={setWho} search={searchPeople} allowEmpty emptyLabel="Everyone" />
            </Filter>
            <Filter label="Status">
              <select value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">Any</option>
                {["DRAFT", "SUBMITTED", "APPROVED", "PAID", "REJECTED", "CANCELLED"].map((s) => (
                  <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>
                ))}
              </select>
            </Filter>
          </HrFilterBar>

          <HrTable
            rowKey={(r) => r.id}
            rows={all.rows}
            loading={all.busy && !all.rows.length}
            error={all.err}
            onRetry={all.reload}
            onRowClick={(r) => openClaim(r.id)}
            caption="Expense claims at this branch"
            empty={(status || who)
              ? { icon: P.filter, title: "No claims match that", hint: "Widen the filter, or reset it to see everything.",
                  action: <HrButton icon={P.reset} onClick={() => { setStatus(""); setWho(""); }}>Reset</HrButton> }
              : { icon: P.receipt, title: "Nothing has been claimed",
                  hint: "A claim is what somebody spent on the pharmacy's behalf and wants back." }}
            cols={[
              { key: "empName", label: "Employee", lead: true, sortable: true },
              { key: "claimDate", label: "Filed", sortable: true },
              { key: "lineCount", label: "Items", num: true },
              { key: "claimedAmount", label: "Claimed", num: true, render: (r) => money(r.claimedAmount) },
              { key: "approvedAmount", label: "Allowed", num: true,
                render: (r) => (r.approvedAmount == null ? <span className="hr-dash">—</span> : money(r.approvedAmount)) },
              { key: "claimStatus", label: "Status", render: (r) => <Status code={r.claimStatus} label={r.claimStatusLabel} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <>
                    <HrButton size="sm" icon={P.eye} onClick={() => openClaim(r.id)}>Open</HrButton>
                    {r.claimStatus === "APPROVED" && perm.canApprove && (
                      <HrButton size="sm" variant="primary" icon={P.money} onClick={() => reimburse(r)}>Reimburse</HrButton>
                    )}
                  </>
                ) },
            ]}
          />
          <HrPagination page={all.page} size={all.size} total={all.total} totalPages={all.totalPages}
                        onPage={all.setPage} onSize={all.setSize} />
          <PolicyNote>
            A reimbursement is <strong>paid on its own and never through a payslip</strong>. Money
            spent on the pharmacy's behalf is being returned, not earned — putting it in the gross
            would take income tax and provident fund out of a rickshaw fare.
          </PolicyNote>
        </Card>
      )}

      {pane === "types" && (
        <Card title="Kinds of expense"
              right={perm.canCreate && (
                <HrButton size="sm" variant="primary" icon={P.plus}
                          onClick={() => setTypeForm({
                            typeCode: "", typeName: "", description: "",
                            maxPerClaim: "", maxPerDay: "", requiresReceipt: false, sortOrder: 100,
                          })}>
                  Add a kind
                </HrButton>
              )}>
          <HrTable
            rowKey={(r) => r.id}
            rows={types}
            caption="The kinds of expense this branch recognises"
            empty={{ icon: P.receipt, title: "Nothing to claim for",
                     hint: "Add a kind of expense — a fare, a meal, stationery — before anybody can file a claim." }}
            cols={[
              { key: "typeName", label: "Name", lead: true, sortable: true },
              { key: "typeCode", label: "Code", advisory: true },
              { key: "maxPerClaim", label: "Per expense", num: true,
                render: (r) => (r.maxPerClaim == null ? <span className="hr-dash">no limit set</span> : money(r.maxPerClaim)) },
              { key: "maxPerDay", label: "Per day", num: true,
                render: (r) => (r.maxPerDay == null ? <span className="hr-dash">no limit set</span> : money(r.maxPerDay)) },
              { key: "requiresReceipt", label: "Receipt", advisory: true,
                render: (r) => (r.requiresReceipt === 1 ? "Expected" : "Optional") },
              { key: "status", label: "Status",
                render: (r) => <Status code={r.status === "Y" ? "APPROVED" : "CLOSED"} label={r.status === "Y" ? "In use" : "Retired"} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <>
                    {perm.canEdit && (
                      <HrButton variant="ghost" size="sm" icon={P.edit} onClick={() => setTypeForm({
                        id: r.id, typeCode: r.typeCode, typeName: r.typeName,
                        description: r.description || "",
                        maxPerClaim: r.maxPerClaim ?? "",
                        maxPerDay: r.maxPerDay ?? "",
                        requiresReceipt: r.requiresReceipt === 1,
                        sortOrder: r.sortOrder,
                      })}>Edit</HrButton>
                    )}
                    {perm.canDelete && r.status === "Y" && (
                      <HrButton variant="ghost" size="sm" icon={P.x} onClick={() => retire(r)}>Retire</HrButton>
                    )}
                  </>
                ) },
            ]}
          />
          <PolicyNote>
            <strong>No limit is set on anything.</strong> What a day's conveyance is worth at this
            pharmacy is a business rule, and a figure shipped in the box would be a number nobody
            chose being enforced against people's own money. Blank means undecided, not zero, and
            nothing is refused for exceeding a limit that was never set.
          </PolicyNote>
        </Card>
      )}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />

      {/* ── Writing a claim ── */}
      {form && (
        <Modal title="File an expense claim" width={860} onClose={() => setForm(null)}
               subtitle="Money somebody spent on the pharmacy's behalf, to be paid back — never through a payslip"
               footer={
                 <>
                   <span className="hr-foot-status">
                     <b>{(form.lines || []).length}</b> {(form.lines || []).length === 1 ? "expense" : "expenses"} · <b>{money(lineTotal(form))}</b>
                     {(form.lines || []).some((l) => l.receiptFile) && (
                       <> · <b>{(form.lines || []).filter((l) => l.receiptFile).length}</b> {(form.lines || []).filter((l) => l.receiptFile).length === 1 ? "receipt" : "receipts"}</>
                     )}
                   </span>
                   <HrButton variant="ghost" onClick={() => setForm(null)} disabled={!!saving}>Cancel</HrButton>
                   <HrButton disabled={!form.employeeId || !!saving} title="Keep it without submitting — receipts can still be attached to a draft"
                             onClick={() => saveClaim(false)}>Save as a draft</HrButton>
                   <HrButton variant="primary" icon={P.check} disabled={claimProblems(form).length > 0 || !!saving}
                             title={claimProblems(form)[0]} onClick={() => saveClaim(true)}>{saving || "Submit for approval"}</HrButton>
                 </>
               }>
          <HrFormSection icon={P.user} title="Who is claiming" hint="The person who paid, and what the spending was for.">
            <Field label="Employee">
              <HrEmployeePicker value={form.employeeId} onChange={(id) => setForm({ ...form, employeeId: id })} search={searchPeople} />
            </Field>
            <Field label="Filed on">
              <input type="date" max={isoToday()} value={form.claimDate} onChange={(e) => setForm({ ...form, claimDate: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <Field label="Purpose">
                <input value={form.purpose} placeholder="Optional — a delivery round, a market visit, a training day"
                       onChange={(e) => setForm({ ...form, purpose: e.target.value })} />
              </Field>
            </div>
          </HrFormSection>

          <HrFormSection icon={P.receipt} title={`Expenses · ${(form.lines || []).length}`}
                         hint="One line per journey or receipt. The total is worked out from the lines and cannot be typed.">
          <div className="hr-form-full">
          <HrLines
            cols={[
              { key: "kind", label: "Kind", width: "22%" },
              { key: "date", label: "Date", width: "18%" },
              { key: "from", label: "From" },
              { key: "to", label: "To" },
              { key: "amount", label: "Amount", num: true, width: "14%" },
              { key: "receipt", label: "Receipt", width: "15%" },
            ]}
            rows={form.lines || []}
            onRemove={(i) => setForm({ ...form, lines: form.lines.filter((_, j) => j !== i) })}
            onAdd={() => setForm({ ...form, lines: [...(form.lines || []), emptyLine(activeTypes[0]?.id)] })}
            addLabel="Add an expense"
            empty="No expenses yet — add the first one."
            cell={(l, i, key) => {
              const patch = (k, v) => { const next = [...form.lines]; next[i] = { ...l, [k]: v }; setForm({ ...form, lines: next }); };
              switch (key) {
                case "kind": return (
                  <select value={l.expenseTypeId} aria-label="Kind of expense" onChange={(e) => patch("expenseTypeId", e.target.value)}>
                    {activeTypes.map((t) => <option key={t.id} value={t.id}>{t.typeName}</option>)}
                  </select>);
                case "date": return <input type="date" max={isoToday()} aria-label="Date" value={l.expenseDate}
                                           className={badDate(l) ? "is-invalid" : ""} aria-invalid={badDate(l) || undefined}
                                           onChange={(e) => patch("expenseDate", e.target.value)} />;
                case "from": return <input placeholder="From" aria-label="From" value={l.fromPlace} onChange={(e) => patch("fromPlace", e.target.value)} />;
                case "to": return <input placeholder="To" aria-label="To" value={l.toPlace} onChange={(e) => patch("toPlace", e.target.value)} />;
                case "amount": return <input type="number" min="0" step="0.01" placeholder="0.00" aria-label="Amount" value={l.claimedAmount}
                                             className={badAmount(l) ? "is-invalid" : ""} aria-invalid={badAmount(l) || undefined}
                                             onChange={(e) => patch("claimedAmount", e.target.value)} />;
                case "receipt": return (
                  <ReceiptPick file={l.receiptFile}
                               onPick={(file) => { if (!file) return; const p = receiptProblem(file); if (p) { addToast(p, "error"); return; } patch("receiptFile", file); }}
                               onClear={() => patch("receiptFile", null)} />);
                default: return null;
              }
            }}
          />
          {/* Said only once something typed is wrong — a fresh line is not a
              mistake yet. Until then the disabled Submit carries the reason. */}
          {(form.lines || []).some((l) => badAmount(l) || (l.expenseDate && l.expenseDate > isoToday())) && (
            <div className="hr-lines-problem" role="status">
              <Ic path={P.alert} size={14} />
              {(form.lines || []).some(badAmount) ? "An amount has to be above nought." : "An expense cannot be dated in the future."}
            </div>
          )}

          <div className="hr-summary">
            <div className="hr-summary-box">
              <div className="hr-summary-row"><span>Expenses</span><b>{(form.lines || []).length}</b></div>
              <div className="hr-summary-row"><span>Days covered</span><b>{new Set((form.lines || []).map((l) => l.expenseDate).filter(Boolean)).size}</b></div>
              <div className="hr-summary-row is-grand"><span>Claimed</span><b>{money(lineTotal(form))}</b></div>
            </div>
          </div>
          <div className="hr-form-note">
            <Ic path={P.info} size={14} />
            <span>Attach the receipt on each line here, or save as a draft and add them later — a receipt can be
              changed only while the claim is a draft. Reimbursement is paid on its own; nothing here reaches a payslip.</span>
          </div>
          </div>
          </HrFormSection>
        </Modal>
      )}

      {/* ── One claim ──
          A drawer, not a modal. This is a reading view: the reader is checking a
          claim against the list they came from, and a modal that blacks out the
          list to say "answer me" is the wrong shape for that. The decision
          itself still opens a modal, because that one really is a question. */}
      {open && (
        <HrDrawer
          title={`Claim — ${open.empName}`}
          subtitle={covering(open)}
          width={640}
          onClose={() => setOpen(null)}
          footer={
            <>
              {["DRAFT", "SUBMITTED", "APPROVED"].includes(open.claimStatus) && perm.canDelete && (
                <HrButton icon={P.ban} onClick={() => cancel(open)}>Cancel claim</HrButton>
              )}
              {open.claimStatus === "DRAFT" && perm.canCreate && (
                <HrButton variant="primary" icon={P.check} onClick={() => act(() => submitExpenseClaim(open.id), "Claim submitted.")}>
                  Submit
                </HrButton>
              )}
              {open.claimStatus === "APPROVED" && perm.canApprove && (
                <HrButton variant="primary" icon={P.money} onClick={() => { reimburse(open); }}>Reimburse</HrButton>
              )}
              {open.claimStatus === "SUBMITTED" && perm.canApprove && (
                <>
                  <HrButton variant="danger" icon={P.x} onClick={() => reject(open)}>Reject</HrButton>
                  <HrButton variant="primary" icon={P.check} onClick={() => setDecision({
                    id: open.id, remarks: "",
                    lines: (open.lines || []).map((l) => ({ ...l, approvedAmount: l.claimedAmount })),
                  })}>
                    Approve
                  </HrButton>
                </>
              )}
            </>
          }>
          <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <Facts>
              <Fact label="Status" value={<Status code={open.claimStatus} label={open.claimStatusLabel} />} />
              <Fact label="Covering" value={covering(open)} />
              <Fact label="Claimed" value={money(open.claimedAmount)} num />
              <Fact label="Allowed" num value={open.approvedAmount == null ? "—" : money(open.approvedAmount)} />
              {open.paidDate && <Fact label="Reimbursed" value={open.paidDate} />}
              {open.paymentReference && <Fact label="Reference" value={open.paymentReference} />}
            </Facts>

            {open.purpose && <PolicyNote>{open.purpose}</PolicyNote>}

            <HrTable
              rowKey={(r) => r.id}
              rows={open.lines || []}
              caption="Expenses on this claim"
              empty={{ icon: P.receipt, title: "Nothing on this claim", hint: "A claim with no expenses on it cannot be submitted." }}
              cols={[
                { key: "typeName", label: "Kind", lead: true },
                { key: "expenseDate", label: "Date" },
                { key: "detail", label: "Detail", advisory: true,
                  render: (r) => [r.fromPlace, r.toPlace].filter(Boolean).join(" to ") || r.description || "—" },
                { key: "claimedAmount", label: "Claimed", num: true, render: (r) => money(r.claimedAmount) },
                { key: "approvedAmount", label: "Allowed", num: true,
                  render: (r) => (r.approvedAmount == null ? "—" : money(r.approvedAmount)) },
                { key: "receipt", label: "Receipt",
                  render: (r) => (
                    <ReceiptCell line={r} editable={open.editable && perm.canEdit} busy={receiptBusy === r.id}
                                 onOpen={() => lookAt(open.lines, open, r)}
                                 onPick={(file) => pickReceipt(r.id, file)}
                                 onRemove={() => dropReceipt(r.id)} />
                  ) },
              ]}
            />

            {/* The receipts as pictures, one card a line. While the claim is a
                draft the cards are where a photograph is dropped; afterwards
                they are what the approver looks through. */}
            <ReceiptGallery lines={open.lines || []} editable={open.editable && perm.canEdit} busyLine={receiptBusy}
                            onOpen={(l) => lookAt(open.lines, open, l)}
                            onPick={pickReceipt} onRemove={dropReceipt} />

            {open.decisionRemarks && (
              <PolicyNote><strong>{open.decidedBy}:</strong> {open.decisionRemarks}</PolicyNote>
            )}
            {open.claimStatus === "PAID" && (
              <PolicyNote>
                Reimbursed by {open.paymentMode?.toLowerCase()} on {open.paidDate}.
                <strong> Not through payroll</strong> — no payslip changed.
              </PolicyNote>
            )}
          </div>
        </HrDrawer>
      )}

      {/* The receipt being looked at, over whatever opened it. */}
      {viewer && (
        <ReceiptViewer items={viewer.items} index={viewer.index}
                       onIndex={(index) => setViewer({ ...viewer, index })}
                       onClose={() => setViewer(null)} />
      )}

      {/* ── Deciding ── */}
      {decision && (
        <Modal title="Approve this claim" width={720} onClose={() => setDecision(null)}
               subtitle="Allow each line what it deserves — the claim is approved for the sum of what is allowed"
               footer={
                 <>
                   <span className="hr-foot-status">
                     Allowing <b>{money((decision.lines || []).reduce((s, l) => s + Number(l.approvedAmount || 0), 0))}</b>
                     {" "}of {money((decision.lines || []).reduce((s, l) => s + Number(l.claimedAmount || 0), 0))}
                   </span>
                   <HrButton variant="ghost" onClick={() => setDecision(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check}
                             disabled={(decision.lines || []).some((l) => Number(l.approvedAmount || 0) > Number(l.claimedAmount || 0))
                                       || !(decision.lines || []).some((l) => Number(l.approvedAmount || 0) > 0)}
                             onClick={decide}>Approve</HrButton>
                 </>
               }>
          <HrLines
            cols={[
              { key: "kind", label: "Kind" },
              { key: "date", label: "Date", width: "18%" },
              { key: "detail", label: "Detail" },
              { key: "receipt", label: "Receipt", width: "9%" },
              { key: "claimed", label: "Claimed", num: true, width: "16%" },
              { key: "allow", label: "Allow", num: true, width: "18%" },
            ]}
            rows={decision.lines || []}
            foot={[
              { label: "Claimed", value: money((decision.lines || []).reduce((s, l) => s + Number(l.claimedAmount || 0), 0)) },
              { label: "Allowing", value: money((decision.lines || []).reduce((s, l) => s + Number(l.approvedAmount || 0), 0)) },
            ]}
            cell={(l, i, key) => {
              switch (key) {
                case "kind": return <span className="hr-lines-static">{l.typeName}</span>;
                case "date": return <span className="hr-lines-static">{l.expenseDate}</span>;
                case "detail": return <span className="hr-lines-static hr-dash">{[l.fromPlace, l.toPlace].filter(Boolean).join(" to ") || l.description || "—"}</span>;
                case "receipt": return <ReceiptCell line={l} onOpen={() => lookAt(decision.lines, open, l)} />;
                case "claimed": return <span className="hr-lines-static" style={{ textAlign: "right", fontVariantNumeric: "tabular-nums" }}>{money(l.claimedAmount)}</span>;
                case "allow": return (
                  <input type="number" min="0" max={l.claimedAmount} step="0.01" aria-label="Amount allowed" value={l.approvedAmount}
                         onChange={(e) => {
                           const next = [...decision.lines];
                           next[i] = { ...l, approvedAmount: e.target.value };
                           setDecision({ ...decision, lines: next });
                         }} />);
                default: return null;
              }
            }}
          />
          {(decision.lines || []).some((l) => Number(l.approvedAmount || 0) > Number(l.claimedAmount || 0)) && (
            <div className="hr-lines-problem" role="status"><Ic path={P.alert} size={14} />A line cannot be allowed more than it claimed.</div>
          )}
          {!(decision.lines || []).some((l) => Number(l.approvedAmount || 0) > 0) && (
            <div className="hr-lines-problem" role="status"><Ic path={P.alert} size={14} />Allowing nothing at all is a rejection — use Reject instead.</div>
          )}
          <div className="hr-form" style={{ marginTop: 14 }}>
            <div className="hr-form-full">
              <Field label="Remarks">
                <input value={decision.remarks} placeholder="Optional — seen by the claimant"
                       onChange={(e) => setDecision({ ...decision, remarks: e.target.value })} />
              </Field>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Reimbursing ── */}
      {payForm && (
        <Modal title={`Reimburse ${payForm.empName}`} width={520} onClose={() => setPayForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setPayForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.money} onClick={() => act(() => payExpenseClaim(payForm.id, {
                     paidDate: payForm.paidDate,
                     paymentMode: payForm.paymentMode,
                     paymentReference: payForm.paymentReference || null,
                   }), "Claim reimbursed.").then(() => setPayForm(null))}>
                     Record the payment
                   </HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Amount"><input value={money(payForm.amount)} readOnly /></Field>
            <Field label="Paid on">
              <input type="date" value={payForm.paidDate} onChange={(e) => setPayForm({ ...payForm, paidDate: e.target.value })} />
            </Field>
            <Field label="How">
              <select value={payForm.paymentMode} onChange={(e) => setPayForm({ ...payForm, paymentMode: e.target.value })}>
                {["CASH", "BANK", "CHEQUE", "MOBILE"].map((m) => (
                  <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>
                ))}
              </select>
            </Field>
            <Field label="Reference">
              <input value={payForm.paymentReference} onChange={(e) => setPayForm({ ...payForm, paymentReference: e.target.value })} />
            </Field>
          </div>
          <PolicyNote>
            Its own payment, with its own reference. <strong>Nothing here touches payroll</strong>
            {" "}— no payslip changes, and no tax or provident fund comes off money being handed back.
          </PolicyNote>
        </Modal>
      )}

      {/* ── A kind of expense ── */}
      {typeForm && (
        <Modal title={typeForm.id ? "Change a kind of expense" : "Add a kind of expense"}
               width={520} onClose={() => setTypeForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setTypeForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" disabled={!typeForm.typeCode || !typeForm.typeName} onClick={saveType}>Save</HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Code">
              <input value={typeForm.typeCode} onChange={(e) => setTypeForm({ ...typeForm, typeCode: e.target.value })} />
            </Field>
            <Field label="Name">
              <input value={typeForm.typeName} onChange={(e) => setTypeForm({ ...typeForm, typeName: e.target.value })} />
            </Field>
            <Field label="Description">
              <input value={typeForm.description} onChange={(e) => setTypeForm({ ...typeForm, description: e.target.value })} />
            </Field>
            <Field label="Most per expense" hint="Blank means no limit has been decided.">
              <input type="number" min="0" step="0.01" value={typeForm.maxPerClaim}
                     onChange={(e) => setTypeForm({ ...typeForm, maxPerClaim: e.target.value })} />
            </Field>
            <Field label="Most per day" hint="Counted across every live claim, not just this one.">
              <input type="number" min="0" step="0.01" value={typeForm.maxPerDay}
                     onChange={(e) => setTypeForm({ ...typeForm, maxPerDay: e.target.value })} />
            </Field>
            <Field label="Receipt">
              <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}>
                <input type="checkbox" checked={typeForm.requiresReceipt}
                       onChange={(e) => setTypeForm({ ...typeForm, requiresReceipt: e.target.checked })} />
                Expected before a claim can be submitted
              </label>
            </Field>
          </div>
          <PolicyNote>
            Leaving a limit blank is a real answer and the one this application ships with: no cap
            has been decided, and nothing is refused against it. Clearing a limit that was set
            removes it.
          </PolicyNote>
        </Modal>
      )}
    </HrPage>
  );
}
