/**
 * HrLeave.jsx — requests, approvals, balances and types (MOD34).
 *
 * The screen opens on the approval queue, because that is what somebody comes
 * here to clear. One section at a time — a tab strip on a desktop, the bottom
 * bar on a phone — and every list that can grow with the branch is paged on
 * the server. Nobody is chosen from a list of everybody: the picker types
 * against the server and shows the twenty that match.
 *
 * <b>A leave type with no quota is shown as unfinished, not as broken.</b> V73
 * seeds the four kinds of leave every pharmacy has and deliberately leaves the
 * entitlement blank — how many days each is worth is policy, and in places
 * statute. The screen says so, rather than showing an empty cell that reads as
 * a bug.
 */
import React, { useState, useEffect, useCallback } from "react";
import { Card, Modal, Field } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import {
  HrPage, Kpis, Kpi, HrTable, Status, HrTabs, HrTabBar, Filter, HrFilterBar, HrSearch,
  HrButton, HrRowActions, HrPagination, HrEmployeePicker, HrPageSkeleton, HrErrorState,
  HrEmptyState, PolicyNote, Blocked, usePagedList, useLocalPage, humanError, P, HR_CRUMB,
} from "../components/HrKit.jsx";
import {
  getLeaveTypes, updateLeaveType, createLeaveType, getPendingLeave, searchLeave,
  fileLeave, approveLeave, rejectLeave, cancelLeave, getLeaveBalances,
  openLeaveBalance, searchEmployees, days, isoToday,
} from "../services/hrService.js";

const searchPeople = async (q) => (await searchEmployees(q)).items;

export default function HrLeave() {
  const { addToast } = useApp();
  const confirm = useConfirm();
  const perm = usePermission("leave");

  const [pane, setPane]       = useState("queue");
  const [types, setTypes]     = useState([]);
  const [pending, setPending] = useState([]);
  const [busy, setBusy]       = useState(true);
  const [loadErr, setLoadErr] = useState(null);
  const [reloadToken, setReloadToken] = useState(0);
  const bump = () => setReloadToken((n) => n + 1);

  /* All-requests filters — sent to the server. */
  const [status, setStatus] = useState("");
  const [who, setWho]       = useState("");

  /* The queue is loaded whole (it is the work) and searched here. */
  const [queueQ, setQueueQ] = useState("");

  const [form, setForm]         = useState(null);
  const [typeForm, setTypeForm] = useState(null);
  const [balFor, setBalFor]     = useState("");
  const [balances, setBalances] = useState([]);
  const [balForm, setBalForm]   = useState(null);

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const [t, p] = await Promise.all([getLeaveTypes(false), getPendingLeave().catch(() => [])]);
      setTypes(t || []);
      setPending(p || []);
      setLoadErr(null);
    } catch (err) {
      setLoadErr(humanError(err, "Leave could not be loaded."));
      addToast(humanError(err, "Could not load leave."), "error");
    } finally {
      setBusy(false);
    }
  }, [addToast]);

  useEffect(() => { load(); }, [load, reloadToken]);

  /* Every request, paged on the server. */
  const all = usePagedList(
    ({ page, size }) => searchLeave({ page, size, status: status || undefined, employeeId: who || undefined }),
    [status, who, reloadToken]);

  const queue = useLocalPage(pending, {
    q: queueQ,
    match: (r, t) => (r.empName || "").toLowerCase().includes(t)
                  || (r.leaveTypeName || "").toLowerCase().includes(t),
  });

  const openBalances = async (employeeId) => {
    setBalFor(employeeId);
    setBalances([]);
    if (!employeeId) return;
    try { setBalances(await getLeaveBalances(employeeId)); }
    catch (err) { addToast(humanError(err, "Could not load balances."), "error"); }
  };

  const decide = async (fn, id, verb) => {
    const ok = await confirm({
      title: `${verb} this request?`,
      body: verb === "Approve"
        ? "The reserved days become taken, and the days it covers are written into attendance."
        : "The reserved days go back to the balance.",
      confirmLabel: verb, tone: verb === "Approve" ? "primary" : "danger",
    });
    if (!ok) return;
    try {
      await fn(id);
      addToast(`Leave ${verb.toLowerCase()}d.`);
      bump();
    } catch (err) { addToast(humanError(err, "That did not go through."), "error"); }
  };

  const file = async () => {
    try {
      await fileLeave({
        employeeId: Number(form.employeeId), leaveTypeId: Number(form.leaveTypeId),
        fromDate: form.fromDate, toDate: form.toDate, isHalfDay: form.isHalfDay,
        reason: form.reason || null, submit: true,
      });
      setForm(null);
      addToast("Request submitted.");
      bump();
    } catch (err) { addToast(humanError(err, "Could not file that request."), "error"); }
  };

  /** Built field by field — `id` is for the URL and the DTO has no such field. */
  const saveType = async () => {
    try {
      const num = (v) => (v === "" || v === null || v === undefined ? null : Number(v));
      const body = {
        typeCode: typeForm.typeCode, typeName: typeForm.typeName,
        description: typeForm.description || null,
        annualQuota: num(typeForm.annualQuota), isPaid: typeForm.isPaid,
        carryForward: typeForm.carryForward, maxCarryForward: num(typeForm.maxCarryForward),
        requiresApproval: typeForm.requiresApproval,
        ...(typeForm.status ? { status: typeForm.status } : {}),
      };
      if (typeForm.id) await updateLeaveType(typeForm.id, body); else await createLeaveType(body);
      setTypeForm(null);
      addToast("Leave type saved.");
      bump();
    } catch (err) { addToast(humanError(err, "Could not save that type."), "error"); }
  };

  const saveBalance = async () => {
    try {
      await openLeaveBalance({
        employeeId: Number(balForm.employeeId), leaveTypeId: Number(balForm.leaveTypeId),
        leaveYear: Number(balForm.leaveYear),
        entitled: balForm.entitled === "" ? null : Number(balForm.entitled),
        carriedForward: balForm.carriedForward === "" ? 0 : Number(balForm.carriedForward),
      });
      setBalForm(null);
      addToast("Balance saved.");
      openBalances(balFor);
    } catch (err) { addToast(humanError(err, "Could not save that balance."), "error"); }
  };

  if (busy && !types.length) return <HrPageSkeleton label="Loading leave…" kpis={3} />;

  if (loadErr && !types.length) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Leave" }]} title="Leave">
        <HrErrorState title="Leave could not be loaded" hint={loadErr} onRetry={load} retrying={busy} />
      </HrPage>
    );
  }

  const unquoted = types.filter((t) => t.status === "Y" && !t.quotaSet);
  const tabs = [
    { id: "queue",    label: "Queue",    icon: P.inbox,    badge: pending.length },
    { id: "all",      label: "All requests", icon: P.doc },
    { id: "balances", label: "Balances", icon: P.away },
    { id: "types",    label: "Types",    icon: P.settings, badge: unquoted.length },
  ];

  const requestCols = [
    { key: "empName", label: "Employee", lead: true, sortable: true },
    { key: "leaveTypeName", label: "Type" },
    { key: "fromDate", label: "From", sortable: true },
    { key: "toDate", label: "To", advisory: true },
    { key: "days", label: "Days", num: true, render: (r) => days(r.days) },
    { key: "requestStatus", label: "Status",
      render: (r) => <Status code={r.requestStatus} label={r.requestStatusLabel} /> },
  ];

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Leave" }]}
      title="Leave"
      subtitle="Requests, approvals and the balance each person has left"
      actions={
        <>
          <HrButton iconOnly icon={P.reset} title="Refresh" onClick={bump} busy={busy} />
          {perm.canCreate && (
            <HrButton variant="primary" icon={P.plus}
                      onClick={() => setForm({ employeeId: "", leaveTypeId: "",
                                               fromDate: isoToday(), toDate: isoToday(), isHalfDay: 0, reason: "" })}>
              File a request
            </HrButton>
          )}
        </>
      }
    >
      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "queue" && (
        <>
          <Kpis>
            <Kpi label="Waiting" value={pending.length} warn={pending.length > 0} icon={P.clock}
                 note={pending.length ? "Needs a decision" : "Queue is clear"} />
            <Kpi label="Leave types" value={types.filter((t) => t.status === "Y").length}
                 icon={P.away} onClick={() => setPane("types")} note="Open the list" />
            <Kpi label="Types with no quota" value={unquoted.length} warn={unquoted.length > 0}
                 icon={P.alert} onClick={unquoted.length ? () => setPane("types") : undefined}
                 note="Balances cannot be opened for these" />
          </Kpis>

          {unquoted.length > 0 && (
            <Blocked>
              {unquoted.length} leave {unquoted.length === 1 ? "type has" : "types have"} no annual
              quota, so no balance can be opened against {unquoted.length === 1 ? "it" : "them"}:{" "}
              {unquoted.map((t) => t.typeName).join(", ")}. How many days each is worth is this
              pharmacy&rsquo;s policy — the application seeds the kinds of leave and deliberately
              does not invent the number.
            </Blocked>
          )}

          <Card title={`Waiting for a decision · ${pending.length}`}
                right={pending.length > 8 && (
                  <div style={{ minWidth: 240 }}>
                    <HrSearch value={queueQ} onChange={setQueueQ} delay={0}
                              placeholder="Name or leave type…" label="Search the queue" />
                  </div>
                )}>
            <HrTable
              rowKey={(r) => r.id}
              rows={queue.rows}
              loading={busy && !pending.length}
              caption="Leave requests waiting for a decision"
              empty={queueQ
                ? { icon: P.search, title: "Nobody matches that", hint: "The queue has nobody by that name or type." }
                : { icon: P.check, title: "Nothing waiting", hint: "Every leave request filed at this branch has been decided." }}
              cols={[
                ...requestCols,
                { key: "reason", label: "Reason", advisory: true },
                { key: "act", label: "", actions: true,
                  render: (r) => perm.canApprove && (
                    <HrRowActions items={[{ label: "Reject this request", icon: P.x, danger: true,
                                            onClick: () => decide(rejectLeave, r.id, "Reject") }]}>
                      <HrButton variant="primary" size="sm" icon={P.check}
                                onClick={() => decide(approveLeave, r.id, "Approve")}>Approve</HrButton>
                    </HrRowActions>
                  ) },
              ]}
            />
            {queue.total > queue.size && (
              <HrPagination page={queue.page} size={queue.size} total={queue.total}
                            totalPages={queue.totalPages} onPage={queue.setPage} onSize={queue.setSize} />
            )}
          </Card>
        </>
      )}

      {pane === "all" && (
        <Card title={`All requests · ${Number(all.total).toLocaleString("en-US")}`}>
          <HrFilterBar active={(who ? 1 : 0) + (status ? 1 : 0)}
                       onReset={() => { setWho(""); setStatus(""); }}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={who} onChange={setWho} search={searchPeople}
                                allowEmpty emptyLabel="Everyone" />
            </Filter>
            <Filter label="Status">
              <select value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="">Any</option>
                {["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"]
                  .map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
              </select>
            </Filter>
          </HrFilterBar>

          <HrTable
            rowKey={(r) => r.id}
            rows={all.rows}
            loading={all.busy && !all.rows.length}
            error={all.err}
            onRetry={all.reload}
            caption="Every leave request at this branch"
            empty={(who || status)
              ? { icon: P.filter, title: "No requests match that",
                  hint: "Widen the filter, or reset it to see everything.",
                  action: <HrButton icon={P.reset} onClick={() => { setWho(""); setStatus(""); }}>Reset the filter</HrButton> }
              : { icon: P.away, title: "No leave has been filed", hint: "Nobody at this branch has asked for leave yet." }}
            cols={[
              ...requestCols,
              { key: "act", label: "", actions: true,
                render: (r) => perm.canDelete && ["DRAFT", "SUBMITTED", "APPROVED"].includes(r.requestStatus) && (
                  <HrButton size="sm" icon={P.ban} onClick={() => decide(cancelLeave, r.id, "Cancel")}>Cancel</HrButton>
                ) },
            ]}
          />
          <HrPagination page={all.page} size={all.size} total={all.total} totalPages={all.totalPages}
                        onPage={all.setPage} onSize={all.setSize} />
        </Card>
      )}

      {pane === "balances" && (
        <Card title="Balances">
          <HrFilterBar active={balFor ? 1 : 0} onReset={balFor ? () => openBalances("") : undefined}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={balFor} onChange={openBalances} search={searchPeople} />
            </Filter>
            {balFor && perm.canEdit && (
              <Filter label="&nbsp;">
                <HrButton icon={P.plus}
                          onClick={() => setBalForm({ employeeId: balFor, leaveTypeId: "",
                                                      leaveYear: new Date().getFullYear(), entitled: "", carriedForward: "" })}>
                  Open a balance
                </HrButton>
              </Filter>
            )}
          </HrFilterBar>

          {!balFor ? (
            <HrEmptyState icon={P.user} title="Choose somebody"
                          hint="A balance is per person and per year. Start typing a name or a code." />
          ) : (
            <HrTable
              rowKey={(r) => r.id}
              rows={balances}
              caption="Leave balances for the chosen employee"
              empty={{ icon: P.away, title: "No balances opened for this year",
                       hint: "A balance has to be opened before leave can be taken against it — and a leave type with no annual quota cannot have one." }}
              cols={[
                { key: "leaveTypeName", label: "Type", lead: true },
                { key: "entitled", label: "Entitled", num: true, render: (r) => days(r.entitled) },
                { key: "carriedForward", label: "Carried", num: true, advisory: true, render: (r) => days(r.carriedForward) },
                { key: "taken", label: "Taken", num: true, render: (r) => days(r.taken) },
                { key: "pending", label: "Pending", num: true, render: (r) => days(r.pending) },
                { key: "available", label: "Left", num: true, render: (r) => <strong>{days(r.available)}</strong> },
              ]}
            />
          )}
        </Card>
      )}

      {pane === "types" && (
        <Card title="Leave types"
              right={perm.canCreate && (
                <HrButton size="sm" icon={P.plus}
                          onClick={() => setTypeForm({ typeCode: "", typeName: "", description: "",
                                                       annualQuota: "", isPaid: 1, carryForward: 0,
                                                       maxCarryForward: "", requiresApproval: 1 })}>
                  Add a type
                </HrButton>
              )}>
          <HrTable
            rowKey={(r) => r.id}
            rows={types}
            loading={busy}
            caption="The kinds of leave this branch recognises"
            empty={{ icon: P.away, title: "No leave types", hint: "Nothing can be filed until at least one kind of leave exists." }}
            cols={[
              { key: "typeName", label: "Type", lead: true, sortable: true },
              { key: "typeCode", label: "Code", advisory: true },
              { key: "annualQuota", label: "Annual quota", num: true,
                render: (r) => r.quotaSet ? days(r.annualQuota) : <span style={{ color: "var(--amber-deep)" }}>not set</span> },
              { key: "isPaid", label: "Paid",
                render: (r) => <Status code={r.isPaid === 1 ? "APPROVED" : "CANCELLED"} label={r.isPaid === 1 ? "Paid" : "Unpaid"} /> },
              { key: "status", label: "Active", advisory: true, render: (r) => (r.status === "Y" ? "Yes" : "No") },
              { key: "act", label: "", actions: true,
                render: (r) => perm.canEdit && (
                  <HrButton size="sm" icon={P.edit}
                            onClick={() => setTypeForm({ id: r.id, typeCode: r.typeCode, typeName: r.typeName,
                                                         description: r.description || "", annualQuota: r.annualQuota ?? "",
                                                         isPaid: r.isPaid, carryForward: r.carryForward,
                                                         maxCarryForward: r.maxCarryForward ?? "",
                                                         requiresApproval: r.requiresApproval, status: r.status })}>
                    Edit
                  </HrButton>
                ) },
            ]}
          />
        </Card>
      )}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />

      {form && (
        <Modal title="File a leave request" width={560} onClose={() => setForm(null)}
               footer={<><HrButton onClick={() => setForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={file}
                                   disabled={!form.employeeId || !form.leaveTypeId}>Submit</HrButton></>}>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={form.employeeId} onChange={(id) => setForm({ ...form, employeeId: id })}
                                  search={searchPeople} />
              </Field>
            </div>
            <Field label="Type">
              <select value={form.leaveTypeId} onChange={(e) => setForm({ ...form, leaveTypeId: e.target.value })}>
                <option value="">— choose —</option>
                {types.filter((t) => t.status === "Y").map((t) => <option key={t.id} value={t.id}>{t.typeName}</option>)}
              </select>
            </Field>
            <Field label="Half day">
              <select value={form.isHalfDay}
                      onChange={(e) => { const half = Number(e.target.value);
                        setForm({ ...form, isHalfDay: half, toDate: half ? form.fromDate : form.toDate }); }}>
                <option value={0}>No</option>
                <option value={1}>Yes</option>
              </select>
            </Field>
            <Field label="From">
              <input type="date" value={form.fromDate}
                     onChange={(e) => setForm({ ...form, fromDate: e.target.value, toDate: form.isHalfDay ? e.target.value : form.toDate })} />
            </Field>
            <Field label="To">
              <input type="date" value={form.toDate} disabled={form.isHalfDay === 1}
                     onChange={(e) => setForm({ ...form, toDate: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <Field label="Reason">
                <textarea value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />
              </Field>
            </div>
            <div className="hr-form-full">
              <PolicyNote>
                Submitting reserves the days against the balance straight away, so a request that
                could not be honoured is refused now rather than a week later.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {typeForm && (
        <Modal title={typeForm.id ? "Edit leave type" : "Add a leave type"} width={560} onClose={() => setTypeForm(null)}
               footer={<><HrButton onClick={() => setTypeForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={saveType}
                                   disabled={!typeForm.typeCode || !typeForm.typeName}>Save</HrButton></>}>
          <div className="hr-form">
            <Field label="Code"><input value={typeForm.typeCode} disabled={!!typeForm.id}
                     onChange={(e) => setTypeForm({ ...typeForm, typeCode: e.target.value })} /></Field>
            <Field label="Name"><input value={typeForm.typeName}
                     onChange={(e) => setTypeForm({ ...typeForm, typeName: e.target.value })} /></Field>
            <Field label="Annual quota (days)">
              <input type="number" min="0" step="0.5" value={typeForm.annualQuota} placeholder="not set"
                     onChange={(e) => setTypeForm({ ...typeForm, annualQuota: e.target.value })} /></Field>
            <Field label="Paid">
              <select value={typeForm.isPaid} onChange={(e) => setTypeForm({ ...typeForm, isPaid: Number(e.target.value) })}>
                <option value={1}>Paid</option>
                <option value={0}>Unpaid — deducted in payroll</option>
              </select>
            </Field>
            <Field label="Carry forward">
              <select value={typeForm.carryForward} onChange={(e) => setTypeForm({ ...typeForm, carryForward: Number(e.target.value) })}>
                <option value={0}>No</option>
                <option value={1}>Yes</option>
              </select>
            </Field>
            {typeForm.carryForward === 1 && (
              <Field label="Most days carried">
                <input type="number" min="0" step="0.5" value={typeForm.maxCarryForward}
                       onChange={(e) => setTypeForm({ ...typeForm, maxCarryForward: e.target.value })} /></Field>
            )}
            <div className="hr-form-full">
              <PolicyNote>
                Leaving the quota blank is a valid state and the one this application ships with:
                the entitlement is policy, and in places statute.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {balForm && (
        <Modal title="Open a leave balance" width={520} onClose={() => setBalForm(null)}
               footer={<><HrButton onClick={() => setBalForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={saveBalance}
                                   disabled={!balForm.leaveTypeId}>Save</HrButton></>}>
          <div className="hr-form">
            <Field label="Type">
              <select value={balForm.leaveTypeId} onChange={(e) => setBalForm({ ...balForm, leaveTypeId: e.target.value })}>
                <option value="">— choose —</option>
                {types.filter((t) => t.status === "Y").map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.typeName}{t.quotaSet ? ` (${days(t.annualQuota)} days)` : " — no quota set"}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Year">
              <input type="number" value={balForm.leaveYear}
                     onChange={(e) => setBalForm({ ...balForm, leaveYear: Number(e.target.value) })} /></Field>
            <Field label="Entitled (days)">
              <input type="number" min="0" step="0.5" value={balForm.entitled} placeholder="from the type's quota"
                     onChange={(e) => setBalForm({ ...balForm, entitled: e.target.value })} /></Field>
            <Field label="Carried forward">
              <input type="number" min="0" step="0.5" value={balForm.carriedForward}
                     onChange={(e) => setBalForm({ ...balForm, carriedForward: e.target.value })} /></Field>
          </div>
        </Modal>
      )}
    </HrPage>
  );
}
