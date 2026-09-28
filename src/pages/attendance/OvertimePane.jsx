/**
 * OvertimePane — claims, and the queue of them waiting.
 *
 * On the attendance page rather than a page of its own because overtime is a
 * fact about a day in the register, and approving a claim writes onto that
 * day. A separate page would put the claim somewhere other than the thing it
 * changes.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Card, Modal, Field } from "../../components/UI.jsx";
import {
  HrTable, Status, HrButton, HrRowActions, HrFilterBar, Filter, HrEmployeePicker,
  PolicyNote, P, humanError,
} from "../../components/HrKit.jsx";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import {
  searchOvertime, getPendingOvertime, claimOvertime, submitOvertime,
  approveOvertime, rejectOvertime, cancelOvertime, searchEmployees, hoursOf, isoToday,
} from "../../services/hrService.js";

export default function OvertimePane({ perm, addToast, policy, date, onChanged, reloadToken }) {
  const confirm = useConfirm();
  const [rows, setRows]   = useState([]);
  const [queue, setQueue] = useState([]);
  const [busy, setBusy]   = useState(true);
  const [err, setErr]     = useState(null);
  const [filter, setFilter] = useState("");
  const [form, setForm]   = useState(null);

  const search = useCallback(async (q) => (await searchEmployees(q)).items, []);

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const [page, q] = await Promise.all([
        searchOvertime({ size: 100, status: filter || undefined }),
        getPendingOvertime().catch(() => []),
      ]);
      setRows(page?.items || []);
      setQueue(q || []);
      setErr(null);
    } catch (e) {
      setErr(humanError(e, "The claims could not be read."));
    } finally {
      setBusy(false);
    }
  }, [filter]);

  useEffect(() => { load(); }, [load, reloadToken]);

  const file = async () => {
    try {
      await claimOvertime({
        employeeId: Number(form.employeeId), otDate: form.otDate,
        claimedMinutes: Number(form.claimedMinutes), reason: form.reason || null, submit: true,
      });
      setForm(null);
      addToast("Overtime claimed.");
      load();
    } catch (e) {
      addToast(humanError(e, "Could not file that claim."), "error");
    }
  };

  const decide = async (row, approve) => {
    const ok = await confirm({
      title: approve ? "Approve this overtime?" : "Reject this claim?",
      body: approve
        ? `${hoursOf(row.claimedMinutes)} is added to ${row.empName}'s attendance for ${row.otDate}. Payroll values overtime from the register, so this is what makes the hours payable.`
        : "Nothing is written to the register. The claim stays on the record as refused.",
      confirmLabel: approve ? "Approve" : "Reject",
      tone: approve ? "primary" : "danger",
    });
    if (!ok) return;
    try {
      if (approve) await approveOvertime(row.id, {});
      else await rejectOvertime(row.id, "Not accepted");
      addToast(approve ? "Overtime approved." : "Claim rejected.");
      load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "That did not go through."), "error");
    }
  };

  const drop = async (row) => {
    const ok = await confirm({
      title: "Cancel this claim?",
      body: row.requestStatus === "APPROVED"
        ? `The ${hoursOf(row.approvedMinutes)} this claim added comes back off ${row.otDate} — exactly that, not the whole day.`
        : "The claim is withdrawn.",
      confirmLabel: "Cancel claim", tone: "danger",
    });
    if (!ok) return;
    try {
      await cancelOvertime(row.id, "Withdrawn");
      addToast("Claim cancelled.");
      load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "Could not cancel that claim."), "error");
    }
  };

  return (
    <>
      <Card title="Overtime claims"
            right={perm.canCreate && (
              <HrButton size="sm" variant="primary" icon={P.plus}
                        onClick={() => setForm({ employeeId: "", otDate: date, claimedMinutes: 60, reason: "" })}>
                Claim overtime
              </HrButton>
            )}>

        {queue.length > 0 && (
          <HrTable
            rowKey={(r) => r.id}
            rows={queue}
            empty=""
            caption="Overtime claims waiting for a decision"
            cols={[
              { key: "empName", label: "Waiting on a decision", lead: true },
              { key: "otDate", label: "Date" },
              { key: "claimed", label: "Claimed", num: true, render: (r) => hoursOf(r.claimedMinutes) },
              { key: "reason", label: "Reason", advisory: true },
              { key: "act", label: "", actions: true,
                render: (r) => perm.canApprove && (
                  <HrRowActions items={[{ label: "Reject this claim", icon: P.x, danger: true, onClick: () => decide(r, false) }]}>
                    <HrButton size="sm" variant="primary" icon={P.check} onClick={() => decide(r, true)}>Approve</HrButton>
                  </HrRowActions>
                ) },
            ]}
          />
        )}

        <HrFilterBar active={filter ? 1 : 0} onReset={() => setFilter("")}>
          <Filter label="Status" grow>
            <select value={filter} onChange={(e) => setFilter(e.target.value)}>
              <option value="">All</option>
              {["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"]
                .map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
            </select>
          </Filter>
        </HrFilterBar>

        <HrTable
          rowKey={(r) => r.id}
          rows={rows}
          loading={busy && !rows.length}
          error={err}
          onRetry={load}
          caption="Overtime claims"
          empty={filter
            ? { icon: P.filter, title: "No claims with that status",
                action: <HrButton icon={P.reset} onClick={() => setFilter("")}>Show all</HrButton> }
            : { icon: P.timer, title: "No overtime has been claimed",
                hint: "A claim is the paper trail behind extra hours. Overtime can also be entered straight onto a day in the register." }}
          cols={[
            { key: "empName", label: "Employee", lead: true, sortable: true },
            { key: "otDate", label: "Date", sortable: true },
            { key: "claimed", label: "Claimed", num: true, render: (r) => hoursOf(r.claimedMinutes) },
            { key: "approved", label: "Approved", num: true,
              render: (r) => (r.approvedMinutes == null ? <span className="hr-dash">—</span> : hoursOf(r.approvedMinutes)) },
            { key: "requestStatus", label: "Status",
              render: (r) => <Status code={r.requestStatus} label={r.requestStatusLabel} /> },
            { key: "reason", label: "Reason", advisory: true },
            { key: "act", label: "", actions: true,
              render: (r) => (
                <HrRowActions
                  items={["DRAFT", "SUBMITTED", "APPROVED"].includes(r.requestStatus) && perm.canDelete
                    ? [{ label: "Cancel this claim", icon: P.ban, danger: true, onClick: () => drop(r) }] : []}>
                  {r.editable && perm.canCreate && (
                    <HrButton size="sm" icon={P.check} onClick={async () => {
                      try { await submitOvertime(r.id); addToast("Claim submitted."); load(); }
                      catch (e) { addToast(humanError(e, "Could not submit."), "error"); }
                    }}>Submit</HrButton>
                  )}
                </HrRowActions>
              ) },
          ]}
        />

        <PolicyNote>
          An approved claim is <strong>written onto the attendance row for that day</strong>, and
          payroll reads the register exactly as it always has — so no hour is paid twice.
        </PolicyNote>
        {policy && !policy.overtimeAllowed && (
          <PolicyNote>
            Overtime is currently <strong>not claimable</strong> at this branch. Turn it on under
            Policy. What an hour is worth is set on the salary structure.
          </PolicyNote>
        )}
      </Card>

      {form && (
        <Modal title="Claim overtime" width={520} onClose={() => setForm(null)}
               footer={
                 <>
                   <HrButton onClick={() => setForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check}
                             disabled={!form.employeeId || !form.otDate || !form.claimedMinutes}
                             onClick={file}>Claim</HrButton>
                 </>
               }>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={form.employeeId}
                                  onChange={(id) => setForm({ ...form, employeeId: id })}
                                  search={search} />
              </Field>
            </div>
            <Field label="Date">
              <input type="date" max={isoToday()} value={form.otDate}
                     onChange={(e) => setForm({ ...form, otDate: e.target.value })} />
            </Field>
            <Field label="Minutes" hint={hoursOf(form.claimedMinutes)}>
              <input type="number" min="1" max="1440" value={form.claimedMinutes}
                     onChange={(e) => setForm({ ...form, claimedMinutes: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <Field label="Reason">
                <textarea rows={2} value={form.reason}
                          onChange={(e) => setForm({ ...form, reason: e.target.value })} />
              </Field>
            </div>
          </div>
          <PolicyNote>
            The day must already be on the register — approval adds these minutes to it.
          </PolicyNote>
        </Modal>
      )}
    </>
  );
}
