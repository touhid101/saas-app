/**
 * HrAdvances.jsx — advances and loans (MOD35).
 *
 * Money lent to staff, and the plan by which payroll takes it back. Two lists,
 * one section at a time, both paged on the server and narrowed by person and
 * status. The tiles above them are the branch's figures from the HR summary —
 * not the page's — so what is outstanding is what is outstanding, whichever
 * fifty rows happen to be on screen.
 *
 * <b>Nothing here posts to a ledger.</b> Paying out and disbursing record the
 * operational fact; Accounts owns the journal and does not exist in this build.
 */
import React, { useState, useEffect, useCallback } from "react";
import { Card, Modal, Field } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import {
  HrPage, Kpis, Kpi, HrTable, Status, HrTabs, HrTabBar, Filter, HrFilterBar,
  HrButton, HrRowActions, HrPagination, HrEmployeePicker, HrPageSkeleton, HrErrorState,
  PolicyNote, usePagedList, humanError, P, HR_CRUMB,
} from "../components/HrKit.jsx";
import {
  searchAdvances, createAdvance, approveAdvance, rejectAdvance, payAdvance, cancelAdvance,
  searchLoans, getLoan, createLoan, approveLoan, rejectLoan, disburseLoan, cancelLoan,
  getVocabulary, getSummary, searchEmployees, money, isoToday,
} from "../services/hrService.js";

const searchPeople = async (q) => (await searchEmployees(q)).items;
const fmt = (n) => Number(n || 0).toLocaleString("en-US");

export default function HrAdvances() {
  const { addToast } = useApp();
  const confirm = useConfirm();
  const perm = usePermission("advance");

  const [pane, setPane]     = useState("advances");
  const [vocab, setVocab]   = useState({});
  const [summary, setSummary] = useState(null);
  const [ready, setReady]   = useState(false);
  const [loadErr, setLoadErr] = useState(null);
  const [reloadToken, setReloadToken] = useState(0);
  const bump = () => setReloadToken((n) => n + 1);

  const [who, setWho]       = useState("");
  const [status, setStatus] = useState("");

  const [advForm, setAdvForm]   = useState(null);
  const [loanForm, setLoanForm] = useState(null);
  const [payForm, setPayForm]   = useState(null);
  const [approveForm, setApproveForm] = useState(null);
  const [openLoan, setOpenLoan] = useState(null);

  const loadShared = useCallback(async () => {
    try {
      const [v, s] = await Promise.all([getVocabulary().catch(() => ({})), getSummary().catch(() => null)]);
      setVocab(v || {});
      setSummary(s);
      setLoadErr(null);
    } catch (err) {
      setLoadErr(humanError(err, "Advances and loans could not be loaded."));
    } finally {
      setReady(true);
    }
  }, []);
  useEffect(() => { loadShared(); }, [loadShared, reloadToken]);

  const advances = usePagedList(
    ({ page, size }) => searchAdvances({ page, size, employeeId: who || undefined, status: status || undefined }),
    [who, status, reloadToken]);
  const loans = usePagedList(
    ({ page, size }) => searchLoans({ page, size, employeeId: who || undefined, status: status || undefined }),
    [who, status, reloadToken]);

  const run = async (fn, msg) => {
    try { await fn(); addToast(msg); bump(); }
    catch (err) { addToast(humanError(err, "That did not go through."), "error"); }
  };
  const ask = (title, body, confirmLabel, tone = "primary") => confirm({ title, body, confirmLabel, tone });

  if (!ready) return <HrPageSkeleton label="Loading advances and loans…" kpis={3} />;
  if (loadErr && !summary) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Advance & Loan" }]} title="Advance & Loan">
        <HrErrorState title="Advances and loans could not be loaded" hint={loadErr} onRetry={loadShared} />
      </HrPage>
    );
  }

  const waiting = (summary?.pendingAdvances || 0) + (summary?.pendingLoans || 0);
  const tabs = [
    { id: "advances", label: "Advances", icon: P.wallet, badge: summary?.pendingAdvances || 0 },
    { id: "loans",    label: "Loans",    icon: P.money,  badge: summary?.pendingLoans || 0 },
  ];

  const filters = (
    <HrFilterBar active={(who ? 1 : 0) + (status ? 1 : 0)} onReset={() => { setWho(""); setStatus(""); }}>
      <Filter label="Employee" grow>
        <HrEmployeePicker value={who} onChange={setWho} search={searchPeople} allowEmpty emptyLabel="Everyone" />
      </Filter>
      <Filter label="Status">
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Any</option>
          {["SUBMITTED", "APPROVED", "PAID", "DISBURSED", "RECOVERING", "CLOSED", "REJECTED", "CANCELLED"]
            .map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
        </select>
      </Filter>
    </HrFilterBar>
  );

  const emptyFor = (what, icon, hint) => ((who || status)
    ? { icon: P.filter, title: `No ${what} match that`, hint: "Widen the filter, or reset it to see everything.",
        action: <HrButton icon={P.reset} onClick={() => { setWho(""); setStatus(""); }}>Reset</HrButton> }
    : { icon, title: `No ${what}`, hint });

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Advance & Loan" }]}
      title="Advance & Loan"
      subtitle="Money lent to staff, and the plan by which payroll takes it back"
      actions={
        <>
          <HrButton iconOnly icon={P.reset} title="Refresh" onClick={bump} />
          {perm.canCreate && (
            <>
              <HrButton icon={P.plus}
                        onClick={() => setAdvForm({ employeeId: "", amount: "", installments: 1, recoverFrom: "", reason: "" })}>
                Advance
              </HrButton>
              <HrButton variant="primary" icon={P.plus}
                        onClick={() => setLoanForm({ employeeId: "", principal: "", installments: 6, startDate: "", interestRate: "", purpose: "" })}>
                Loan
              </HrButton>
            </>
          )}
        </>
      }
    >
      <Kpis>
        <Kpi label="Advances outstanding" value={money(summary?.advanceOutstanding)} icon={P.wallet}
             onClick={() => setPane("advances")} note="Across the whole branch" />
        <Kpi label="Loans outstanding" value={money(summary?.loanOutstanding)} icon={P.money}
             onClick={() => setPane("loans")} note="Across the whole branch" />
        <Kpi label="Waiting for a decision" value={fmt(waiting)} warn={waiting > 0} icon={P.clock}
             onClick={waiting ? () => setStatus("SUBMITTED") : undefined}
             note={waiting ? "Click to show only these" : "Nothing outstanding"} />
      </Kpis>

      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "advances" && (
        <Card title={`Advances · ${fmt(advances.total)}`}>
          {filters}
          <HrTable
            rowKey={(r) => r.id}
            rows={advances.rows}
            loading={advances.busy && !advances.rows.length}
            error={advances.err}
            onRetry={advances.reload}
            caption="Salary advances"
            empty={emptyFor("advances", P.wallet, "An advance is money paid ahead of a payslip and taken back by payroll.")}
            cols={[
              { key: "empName", label: "Employee", lead: true, sortable: true },
              { key: "requestDate", label: "Requested", advisory: true, sortable: true },
              { key: "amount", label: "Asked", num: true, advisory: true, render: (r) => money(r.amount) },
              { key: "approvedAmount", label: "Approved", num: true, render: (r) => money(r.approvedAmount) },
              { key: "outstanding", label: "Outstanding", num: true, render: (r) => <strong>{money(r.outstanding)}</strong> },
              { key: "advanceStatus", label: "Status", render: (r) => <Status code={r.advanceStatus} label={r.advanceStatusLabel} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions items={[
                    r.advanceStatus === "SUBMITTED" && perm.canApprove
                      && { label: "Reject this advance", icon: P.x, danger: true,
                           onClick: async () => { if (await ask("Reject this advance?", "", "Reject", "danger")) run(() => rejectAdvance(r.id), "Advance rejected."); } },
                    ["DRAFT", "SUBMITTED", "APPROVED", "PAID"].includes(r.advanceStatus) && perm.canDelete && Number(r.recoveredAmount || 0) === 0
                      && { label: "Cancel this advance", icon: P.ban, danger: true,
                           onClick: async () => { if (await ask("Cancel this advance?", "", "Cancel it", "danger")) run(() => cancelAdvance(r.id), "Advance cancelled."); } },
                  ].filter(Boolean)}>
                    {r.advanceStatus === "SUBMITTED" && perm.canApprove && (
                      <HrButton size="sm" variant="primary" icon={P.check}
                                onClick={() => setApproveForm({ kind: "advance", id: r.id, empName: r.empName, asked: r.amount, approvedAmount: r.amount, remarks: "" })}>
                        Approve
                      </HrButton>
                    )}
                    {r.advanceStatus === "APPROVED" && perm.canEdit && (
                      <HrButton size="sm" variant="primary" icon={P.money}
                                onClick={() => setPayForm({ kind: "advance", id: r.id, empName: r.empName, amount: r.approvedAmount, paidDate: isoToday(), paymentMode: "CASH", reference: "" })}>
                        Pay out
                      </HrButton>
                    )}
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={advances.page} size={advances.size} total={advances.total} totalPages={advances.totalPages}
                        onPage={advances.setPage} onSize={advances.setSize} />
        </Card>
      )}

      {pane === "loans" && (
        <Card title={`Loans · ${fmt(loans.total)}`}>
          {filters}
          <HrTable
            rowKey={(r) => r.id}
            rows={loans.rows}
            loading={loans.busy && !loans.rows.length}
            error={loans.err}
            onRetry={loans.reload}
            caption="Staff loans"
            onRowClick={async (r) => { try { setOpenLoan(await getLoan(r.id)); } catch (e2) { addToast(humanError(e2), "error"); } }}
            empty={emptyFor("loans", P.money, "A loan is repaid by instalments that payroll deducts each month.")}
            cols={[
              { key: "empName", label: "Employee", lead: true, sortable: true },
              { key: "requestDate", label: "Requested", advisory: true, sortable: true },
              { key: "approvedPrincipal", label: "Principal", num: true, render: (r) => money(r.approvedPrincipal ?? r.principal) },
              { key: "installments", label: "Over", num: true, render: (r) => `${r.installments} × ${money(r.installmentAmount)}` },
              { key: "outstanding", label: "Outstanding", num: true, render: (r) => <strong>{money(r.outstanding)}</strong> },
              { key: "loanStatus", label: "Status", render: (r) => <Status code={r.loanStatus} label={r.loanStatusLabel} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions items={[
                    { label: "Repayment schedule", icon: P.doc,
                      onClick: async () => { try { setOpenLoan(await getLoan(r.id)); } catch (e2) { addToast(humanError(e2), "error"); } } },
                    r.loanStatus === "SUBMITTED" && perm.canApprove
                      && { label: "Reject this loan", icon: P.x, danger: true,
                           onClick: async () => { if (await ask("Reject this loan?", "", "Reject", "danger")) run(() => rejectLoan(r.id), "Loan rejected."); } },
                    ["DRAFT", "SUBMITTED", "APPROVED", "DISBURSED"].includes(r.loanStatus) && perm.canDelete && Number(r.recoveredAmount || 0) === 0
                      && { label: "Cancel this loan", icon: P.ban, danger: true,
                           onClick: async () => { if (await ask("Cancel this loan?", "", "Cancel it", "danger")) run(() => cancelLoan(r.id), "Loan cancelled."); } },
                  ].filter(Boolean)}>
                    {r.loanStatus === "SUBMITTED" && perm.canApprove ? (
                      <HrButton size="sm" variant="primary" icon={P.check}
                                onClick={() => setApproveForm({ kind: "loan", id: r.id, empName: r.empName, asked: r.principal, approvedPrincipal: r.principal, installments: r.installments, startDate: r.startDate || "", remarks: "" })}>
                        Approve
                      </HrButton>
                    ) : r.loanStatus === "APPROVED" && perm.canEdit ? (
                      <HrButton size="sm" variant="primary" icon={P.money}
                                onClick={() => setPayForm({ kind: "loan", id: r.id, empName: r.empName, amount: r.approvedPrincipal, paidDate: isoToday(), paymentMode: "CASH", reference: "" })}>
                        Disburse
                      </HrButton>
                    ) : (
                      <HrButton size="sm" icon={P.doc}
                                onClick={async () => { try { setOpenLoan(await getLoan(r.id)); } catch (e2) { addToast(humanError(e2), "error"); } }}>
                        Schedule
                      </HrButton>
                    )}
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={loans.page} size={loans.size} total={loans.total} totalPages={loans.totalPages}
                        onPage={loans.setPage} onSize={loans.setSize} />
        </Card>
      )}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />

      {advForm && (
        <Modal title="Request an advance" width={540} onClose={() => setAdvForm(null)}
               footer={<><HrButton onClick={() => setAdvForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} disabled={!advForm.employeeId || !advForm.amount}
                                   onClick={() => run(async () => {
                                     await createAdvance({ employeeId: Number(advForm.employeeId), amount: Number(advForm.amount),
                                                           installments: Number(advForm.installments) || 1,
                                                           recoverFrom: advForm.recoverFrom || null, reason: advForm.reason || null, submit: true });
                                     setAdvForm(null);
                                   }, "Advance requested.")}>Submit</HrButton></>}>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={advForm.employeeId} onChange={(id) => setAdvForm({ ...advForm, employeeId: id })} search={searchPeople} />
              </Field>
            </div>
            <Field label="Amount"><input type="number" min="0" step="0.01" value={advForm.amount}
                     onChange={(e) => setAdvForm({ ...advForm, amount: e.target.value })} /></Field>
            <Field label="Recovered over (payslips)"><input type="number" min="1" value={advForm.installments}
                     onChange={(e) => setAdvForm({ ...advForm, installments: e.target.value })} /></Field>
            <Field label="Start recovering from"><input type="date" value={advForm.recoverFrom}
                     onChange={(e) => setAdvForm({ ...advForm, recoverFrom: e.target.value })} /></Field>
            <div className="hr-form-full">
              <Field label="Reason"><textarea value={advForm.reason} onChange={(e) => setAdvForm({ ...advForm, reason: e.target.value })} /></Field>
            </div>
          </div>
        </Modal>
      )}

      {loanForm && (
        <Modal title="Request a loan" width={540} onClose={() => setLoanForm(null)}
               footer={<><HrButton onClick={() => setLoanForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} disabled={!loanForm.employeeId || !loanForm.principal}
                                   onClick={() => run(async () => {
                                     await createLoan({ employeeId: Number(loanForm.employeeId), principal: Number(loanForm.principal),
                                                        installments: Number(loanForm.installments) || 1,
                                                        interestRate: loanForm.interestRate === "" ? null : Number(loanForm.interestRate),
                                                        startDate: loanForm.startDate || null, purpose: loanForm.purpose || null, submit: true });
                                     setLoanForm(null);
                                   }, "Loan requested.")}>Submit</HrButton></>}>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={loanForm.employeeId} onChange={(id) => setLoanForm({ ...loanForm, employeeId: id })} search={searchPeople} />
              </Field>
            </div>
            <Field label="Principal"><input type="number" min="0" step="0.01" value={loanForm.principal}
                     onChange={(e) => setLoanForm({ ...loanForm, principal: e.target.value })} /></Field>
            <Field label="Installments"><input type="number" min="1" value={loanForm.installments}
                     onChange={(e) => setLoanForm({ ...loanForm, installments: e.target.value })} /></Field>
            <Field label="First due"><input type="date" value={loanForm.startDate}
                     onChange={(e) => setLoanForm({ ...loanForm, startDate: e.target.value })} /></Field>
            <Field label="Interest rate (%)"><input type="number" min="0" step="0.01" value={loanForm.interestRate} placeholder="recorded only"
                     onChange={(e) => setLoanForm({ ...loanForm, interestRate: e.target.value })} /></Field>
            <div className="hr-form-full">
              <Field label="Purpose"><textarea value={loanForm.purpose} onChange={(e) => setLoanForm({ ...loanForm, purpose: e.target.value })} /></Field>
            </div>
            <div className="hr-form-full">
              <PolicyNote>
                An interest rate entered here is recorded and never applied — nothing in this application
                computes interest on a staff loan. Whether interest is charged is a decision this pharmacy
                has to make before the field means anything.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {approveForm && (
        <Modal title={`Approve — ${approveForm.empName}`} width={520} onClose={() => setApproveForm(null)}
               footer={<><HrButton onClick={() => setApproveForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={() => run(async () => {
                           if (approveForm.kind === "advance") {
                             await approveAdvance(approveForm.id, { approvedAmount: Number(approveForm.approvedAmount), remarks: approveForm.remarks });
                           } else {
                             await approveLoan(approveForm.id, { approvedPrincipal: Number(approveForm.approvedPrincipal),
                                                                 installments: Number(approveForm.installments),
                                                                 startDate: approveForm.startDate || null, remarks: approveForm.remarks });
                           }
                           setApproveForm(null);
                         }, "Approved.")}>Approve</HrButton></>}>
          <div className="hr-form">
            <Field label="Asked for"><input value={money(approveForm.asked)} readOnly /></Field>
            {approveForm.kind === "advance" ? (
              <Field label="Approve"><input type="number" min="0" step="0.01" value={approveForm.approvedAmount}
                       onChange={(e) => setApproveForm({ ...approveForm, approvedAmount: e.target.value })} /></Field>
            ) : (
              <>
                <Field label="Approve"><input type="number" min="0" step="0.01" value={approveForm.approvedPrincipal}
                         onChange={(e) => setApproveForm({ ...approveForm, approvedPrincipal: e.target.value })} /></Field>
                <Field label="Installments"><input type="number" min="1" value={approveForm.installments}
                         onChange={(e) => setApproveForm({ ...approveForm, installments: e.target.value })} /></Field>
                <Field label="First due"><input type="date" value={approveForm.startDate}
                         onChange={(e) => setApproveForm({ ...approveForm, startDate: e.target.value })} /></Field>
              </>
            )}
            <div className="hr-form-full">
              <Field label="Note"><input value={approveForm.remarks} onChange={(e) => setApproveForm({ ...approveForm, remarks: e.target.value })} /></Field>
            </div>
            <div className="hr-form-full">
              <PolicyNote>
                Approving for less than was asked is kept as a separate figure, so the decision stays
                visible afterwards. Every recovery is computed from the approved amount.
                {approveForm.kind === "loan" && " Approving writes the whole repayment schedule now."}
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {payForm && (
        <Modal title={`${payForm.kind === "advance" ? "Pay out" : "Disburse"} — ${payForm.empName}`} width={500} onClose={() => setPayForm(null)}
               footer={<><HrButton onClick={() => setPayForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.money} onClick={() => run(async () => {
                           const body = { paidDate: payForm.paidDate, paymentMode: payForm.paymentMode, reference: payForm.reference || null };
                           if (payForm.kind === "advance") await payAdvance(payForm.id, body); else await disburseLoan(payForm.id, body);
                           setPayForm(null);
                         }, "Recorded.")}>Record</HrButton></>}>
          <div className="hr-form">
            <Field label="Amount"><input value={money(payForm.amount)} readOnly /></Field>
            <Field label="Date"><input type="date" value={payForm.paidDate} onChange={(e) => setPayForm({ ...payForm, paidDate: e.target.value })} /></Field>
            <Field label="Method">
              <select value={payForm.paymentMode} onChange={(e) => setPayForm({ ...payForm, paymentMode: e.target.value })}>
                {(vocab.paymentMode || []).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </Field>
            <Field label="Reference"><input value={payForm.reference} onChange={(e) => setPayForm({ ...payForm, reference: e.target.value })} /></Field>
            <div className="hr-form-full">
              <PolicyNote>
                This records the operational fact only — no accounting entry is written, because Accounts owns
                the general ledger and does not exist in this build yet.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {openLoan && (
        <Modal title={`Repayment schedule — ${openLoan.empName}`} width={640} onClose={() => setOpenLoan(null)}>
          <HrTable
            rowKey={(r) => r.id}
            rows={openLoan.schedule || []}
            caption="Repayment schedule"
            empty={{ icon: P.calendar, title: "No schedule yet", hint: "Instalments are worked out when the loan is approved, not when it is asked for." }}
            cols={[
              { key: "installmentNo", label: "#", lead: true, render: (r) => `Installment ${r.installmentNo}` },
              { key: "dueDate", label: "Due" },
              { key: "dueAmount", label: "Amount", num: true, render: (r) => money(r.dueAmount) },
              { key: "recoveredAmount", label: "Recovered", num: true, render: (r) => money(r.recoveredAmount) },
              { key: "scheduleStatus", label: "Status", render: (r) => <Status code={r.scheduleStatus} label={r.scheduleStatusLabel} /> },
              { key: "payrollId", label: "Taken by", advisory: true, render: (r) => (r.payrollId ? `Run #${r.payrollId}` : "—") },
            ]}
          />
          <PolicyNote>
            The run that took each installment is recorded — the answer to &ldquo;why is my salary short this
            month&rdquo;, and what makes recalculating a payroll safe: an installment already marked against a run
            is released before that run computes again, so it is never taken twice.
          </PolicyNote>
        </Modal>
      )}
    </HrPage>
  );
}
