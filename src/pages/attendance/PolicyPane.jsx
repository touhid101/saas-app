/**
 * PolicyPane — the rules the register is judged by.
 *
 * The weekly off, the shifts, the holidays. Every one is something this
 * pharmacy decides; none is invented here. A policy that names no weekly off
 * means every day is worked, and the screen says so rather than guessing.
 */
import React, { useState } from "react";
import { Card, Modal, Field } from "../../components/UI.jsx";
import {
  HrTable, Status, HrButton, HrRowActions, Facts, Fact, Blocked, PolicyNote, P, humanError,
} from "../../components/HrKit.jsx";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import {
  setPolicy, createShift, updateShift, retireShift, createHoliday, removeHoliday,
  WEEK_DAYS, HOLIDAY_TYPES, clock, hoursOf, isoToday,
} from "../../services/hrService.js";

export default function PolicyPane({ perm, addToast, policy, shifts, holidays, calendar,
                                     holYear, setHolYear, reload, onChanged }) {
  const confirm = useConfirm();
  const [polForm, setPolForm]     = useState(null);
  const [shiftForm, setShiftForm] = useState(null);
  const [holForm, setHolForm]     = useState(null);

  const savePolicy = async () => {
    try {
      /* Built field by field. polForm carries `weekend`, an array the toggle
         buttons work on, which the request does not have. */
      await setPolicy({
        policyName: polForm.policyName,
        weekendDays: (polForm.weekend || []).join(","),
        defaultShiftId: polForm.defaultShiftId || null,
        lateThresholdMinutes: polForm.lateThresholdMinutes,
        earlyLeaveThresholdMinutes: polForm.earlyLeaveThresholdMinutes,
        overtimeAllowed: polForm.overtimeAllowed,
        effectiveFrom: polForm.effectiveFrom,
      });
      setPolForm(null);
      addToast("Working policy set.");
      reload(); onChanged?.();
    } catch (e) { addToast(humanError(e, "Could not set the policy."), "error"); }
  };

  const saveShift = async () => {
    try {
      const body = {
        shiftCode: shiftForm.shiftCode, shiftName: shiftForm.shiftName,
        startTime: shiftForm.startTime, endTime: shiftForm.endTime,
        breakMinutes: Number(shiftForm.breakMinutes) || 0,
        graceMinutes: Number(shiftForm.graceMinutes) || 0,
      };
      if (shiftForm.id) await updateShift(shiftForm.id, body); else await createShift(body);
      setShiftForm(null);
      addToast("Shift saved.");
      reload();
    } catch (e) { addToast(humanError(e, "Could not save that shift."), "error"); }
  };

  const dropShift = async (r) => {
    const ok = await confirm({ title: `Retire ${r.shiftName}?`,
      body: "People on it keep their history. New days are judged by the branch default.",
      confirmLabel: "Retire", tone: "danger" });
    if (!ok) return;
    try { await retireShift(r.id); addToast("Shift retired."); reload(); }
    catch (e) { addToast(humanError(e, "Could not retire that shift."), "error"); }
  };

  const saveHoliday = async () => {
    try {
      await createHoliday({
        holidayDate: holForm.holidayDate, holidayName: holForm.holidayName,
        holidayType: holForm.holidayType, isPaid: holForm.isPaid, branchOnly: holForm.branchOnly,
      });
      setHolForm(null);
      addToast("Holiday added.");
      reload(); onChanged?.();
    } catch (e) { addToast(humanError(e, "Could not add that holiday."), "error"); }
  };

  const dropHoliday = async (h) => {
    const ok = await confirm({ title: `Remove ${h.holidayName}?`,
      body: "Leave over that date will start counting it as a working day again.",
      confirmLabel: "Remove", tone: "danger" });
    if (!ok) return;
    try { await removeHoliday(h.id); addToast("Holiday removed."); reload(); onChanged?.(); }
    catch (e) { addToast(humanError(e, "Could not remove it."), "error"); }
  };

  return (
    <>
      <Card title="Working policy"
            right={perm.canEdit && (
              <HrButton variant="primary" icon={P.edit} onClick={() => setPolForm({
                policyName: policy?.policyName || "Working policy",
                weekend: policy?.weekendDays ? policy.weekendDays.split(",").map(Number) : [],
                defaultShiftId: policy?.defaultShiftId || "",
                lateThresholdMinutes: policy?.lateThresholdMinutes ?? 0,
                earlyLeaveThresholdMinutes: policy?.earlyLeaveThresholdMinutes ?? 0,
                overtimeAllowed: policy?.overtimeAllowed ? 1 : 0,
                effectiveFrom: isoToday(),
              })}>{policy ? "Change policy" : "Set the policy"}</HrButton>
            )}>
        {!policy ? (
          <PolicyNote>
            No working policy has been set, so <strong>every day is a working day</strong>. Leave
            counts every calendar day in a range until a weekly off is decided — a decision the
            application deliberately does not make for you.
          </PolicyNote>
        ) : (
          <>
            <Facts>
              <Fact label="Policy" value={policy.policyName} />
              <Fact label="Weekly off" value={policy.weekendSet ? (policy.weekendDayNames || []).join(", ") : "not set — every day is worked"} />
              <Fact label="Default shift" value={policy.defaultShiftName} />
              <Fact label="Late after" value={`${policy.lateThresholdMinutes ?? 0} min`} />
              <Fact label="Overtime" value={policy.overtimeAllowed ? "May be claimed" : "Not claimed"} />
              <Fact label="In force since" value={policy.effectiveFrom} />
            </Facts>
            {!policy.weekendSet && <Blocked>This policy names no weekly off, so leave still counts every calendar day.</Blocked>}
          </>
        )}
      </Card>

      {calendar && (
        <Card title="This month" eyebrow={`${calendar.from} to ${calendar.to}`}>
          <Facts>
            <Fact label="Calendar days" value={calendar.calendarDays} />
            <Fact label="Working days" value={calendar.workingDays} />
            <Fact label="Not worked" value={calendar.calendarDays - calendar.workingDays} />
          </Facts>
          {(calendar.days || []).some((d) => !d.workingDay) && (
            <HrTable rowKey={(r) => r.date} rows={(calendar.days || []).filter((d) => !d.workingDay)}
                     caption="Days not worked this month"
                     cols={[
                       { key: "date", label: "Date", lead: true },
                       { key: "why", label: "Why not", render: (r) => (r.holiday ? r.holidayName : "Weekly off") },
                       { key: "kind", label: "Kind",
                         render: (r) => <Status code={r.holiday ? "APPROVED" : "SUBMITTED"} label={r.holiday ? "Holiday" : "Weekend"} /> },
                     ]} />
          )}
        </Card>
      )}

      <Card title="Shifts"
            right={perm.canCreate && (
              <HrButton size="sm" icon={P.plus} onClick={() => setShiftForm({
                shiftCode: "", shiftName: "", startTime: "09:00", endTime: "18:00", breakMinutes: 60, graceMinutes: 10,
              })}>Add a shift</HrButton>
            )}>
        <HrTable rowKey={(r) => r.id} rows={shifts} caption="Shifts"
                 empty={{ icon: P.clock, title: "No shifts", hint: "Attendance is then judged by what was entered." }}
                 cols={[
                   { key: "shiftName", label: "Shift", lead: true, sortable: true },
                   { key: "shiftCode", label: "Code", advisory: true },
                   { key: "times", label: "Hours",
                     render: (r) => `${clock(r.startTime)} – ${clock(r.endTime)}` + (r.crossesMidnight ? " (next day)" : "") },
                   { key: "breakMinutes", label: "Break", num: true, render: (r) => `${r.breakMinutes || 0}m` },
                   { key: "graceMinutes", label: "Grace", num: true, advisory: true, render: (r) => `${r.graceMinutes || 0}m` },
                   { key: "workingMinutes", label: "Worked", num: true, render: (r) => hoursOf(r.workingMinutes) },
                   { key: "employeesOn", label: "Staff", num: true, sortable: true },
                   { key: "act", label: "", actions: true,
                     render: (r) => (
                       <HrRowActions items={perm.canDelete && r.status === "Y"
                         ? [{ label: "Retire this shift", icon: P.ban, danger: true, onClick: () => dropShift(r) }] : []}>
                         {perm.canEdit && (
                           <HrButton size="sm" icon={P.edit} onClick={() => setShiftForm({
                             id: r.id, shiftCode: r.shiftCode, shiftName: r.shiftName,
                             startTime: clock(r.startTime), endTime: clock(r.endTime),
                             breakMinutes: r.breakMinutes ?? 0, graceMinutes: r.graceMinutes ?? 0,
                           })}>Edit</HrButton>
                         )}
                       </HrRowActions>
                     ) },
                 ]} />
        <PolicyNote>
          An end time earlier than the start is an overnight shift, not a mistake — 22:00 to 06:00
          finishes the next morning, and the worked total is calculated accordingly.
        </PolicyNote>
      </Card>

      <Card title="Holidays"
            right={
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <select value={holYear} onChange={(e) => setHolYear(Number(e.target.value))} aria-label="Year"
                        style={{ padding: "6px 10px", borderRadius: 8, border: "1px solid var(--line)",
                                 background: "var(--paper)", color: "var(--ink)", fontSize: 13, minHeight: 32 }}>
                  {[holYear - 1, holYear, holYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
                </select>
                {perm.canCreate && (
                  <HrButton size="sm" icon={P.plus} onClick={() => setHolForm({
                    holidayDate: "", holidayName: "", holidayType: "COMPANY", isPaid: 1, branchOnly: false,
                  })}>Add</HrButton>
                )}
              </div>
            }>
        <HrTable rowKey={(r) => r.id} rows={holidays} caption={`Holidays in ${holYear}`}
                 empty={{ icon: P.sun, title: `No holidays recorded for ${holYear}`,
                          hint: "Government holidays are declared by gazette each year and are not seeded — enter the year's list here." }}
                 cols={[
                   { key: "holidayDate", label: "Date", lead: true, sortable: true },
                   { key: "holidayName", label: "Holiday" },
                   { key: "holidayType", label: "Type", render: (r) => r.holidayTypeLabel || r.holidayType },
                   { key: "orgWide", label: "Applies to", render: (r) => (r.orgWide ? "All branches" : "This branch") },
                   { key: "isPaid", label: "Paid", advisory: true, render: (r) => (r.isPaid === 1 ? "Paid" : "Unpaid") },
                   { key: "act", label: "", actions: true,
                     render: (r) => perm.canDelete && (
                       <HrButton size="sm" icon={P.trash} onClick={() => dropHoliday(r)}>Remove</HrButton>
                     ) },
                 ]} />
      </Card>

      {polForm && (
        <Modal title="Working policy" width={620} onClose={() => setPolForm(null)}
               footer={<><HrButton onClick={() => setPolForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={savePolicy}>Save</HrButton></>}>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Weekly off" hint="Leave every day unticked if this pharmacy has not decided one.">
                <div className="hr-mark">
                  {WEEK_DAYS.map((d) => (
                    <button key={d.value} type="button"
                            className={(polForm.weekend || []).includes(d.value) ? "is-on" : ""}
                            onClick={() => setPolForm({ ...polForm,
                              weekend: (polForm.weekend || []).includes(d.value)
                                ? polForm.weekend.filter((x) => x !== d.value)
                                : [...(polForm.weekend || []), d.value] })}>
                      {d.label.slice(0, 3)}
                    </button>
                  ))}
                </div>
              </Field>
            </div>
            <Field label="Default shift">
              <select value={polForm.defaultShiftId} onChange={(e) => setPolForm({ ...polForm, defaultShiftId: e.target.value })}>
                <option value="">— none —</option>
                {shifts.filter((s) => s.status === "Y").map((s) => <option key={s.id} value={s.id}>{s.shiftName}</option>)}
              </select>
            </Field>
            <Field label="In force from">
              <input type="date" value={polForm.effectiveFrom} onChange={(e) => setPolForm({ ...polForm, effectiveFrom: e.target.value })} />
            </Field>
            <Field label="Late after (minutes)">
              <input type="number" min="0" value={polForm.lateThresholdMinutes}
                     onChange={(e) => setPolForm({ ...polForm, lateThresholdMinutes: Number(e.target.value) })} />
            </Field>
            <Field label="Overtime">
              <select value={polForm.overtimeAllowed} onChange={(e) => setPolForm({ ...polForm, overtimeAllowed: Number(e.target.value) })}>
                <option value={0}>Not claimed here</option>
                <option value={1}>May be claimed</option>
              </select>
            </Field>
            <div className="hr-form-full">
              <PolicyNote>
                Saving this <strong>supersedes</strong> the current policy from the date above rather
                than editing it — last month's attendance was judged under the old rules and keeps them.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {shiftForm && (
        <Modal title={shiftForm.id ? "Edit shift" : "Add a shift"} width={560} onClose={() => setShiftForm(null)}
               footer={<><HrButton onClick={() => setShiftForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={saveShift}
                                   disabled={!shiftForm.shiftCode || !shiftForm.shiftName}>Save</HrButton></>}>
          <div className="hr-form">
            <Field label="Code"><input value={shiftForm.shiftCode} disabled={!!shiftForm.id}
                     onChange={(e) => setShiftForm({ ...shiftForm, shiftCode: e.target.value })} /></Field>
            <Field label="Name"><input value={shiftForm.shiftName}
                     onChange={(e) => setShiftForm({ ...shiftForm, shiftName: e.target.value })} /></Field>
            <Field label="Starts"><input type="time" value={shiftForm.startTime}
                     onChange={(e) => setShiftForm({ ...shiftForm, startTime: e.target.value })} /></Field>
            <Field label="Ends" hint="Earlier than the start means an overnight shift.">
              <input type="time" value={shiftForm.endTime} onChange={(e) => setShiftForm({ ...shiftForm, endTime: e.target.value })} /></Field>
            <Field label="Break (minutes)"><input type="number" min="0" value={shiftForm.breakMinutes}
                     onChange={(e) => setShiftForm({ ...shiftForm, breakMinutes: e.target.value })} /></Field>
            <Field label="Grace (minutes)" hint="Arriving within this is not late.">
              <input type="number" min="0" value={shiftForm.graceMinutes}
                     onChange={(e) => setShiftForm({ ...shiftForm, graceMinutes: e.target.value })} /></Field>
          </div>
        </Modal>
      )}

      {holForm && (
        <Modal title="Add a holiday" width={560} onClose={() => setHolForm(null)}
               footer={<><HrButton onClick={() => setHolForm(null)}>Cancel</HrButton>
                         <HrButton variant="primary" icon={P.check} onClick={saveHoliday}
                                   disabled={!holForm.holidayDate || !holForm.holidayName}>Add</HrButton></>}>
          <div className="hr-form">
            <Field label="Date"><input type="date" value={holForm.holidayDate}
                     onChange={(e) => setHolForm({ ...holForm, holidayDate: e.target.value })} /></Field>
            <Field label="Name"><input value={holForm.holidayName}
                     onChange={(e) => setHolForm({ ...holForm, holidayName: e.target.value })} /></Field>
            <Field label="Type">
              <select value={holForm.holidayType} onChange={(e) => setHolForm({ ...holForm, holidayType: e.target.value })}>
                {HOLIDAY_TYPES.map((t) => <option key={t} value={t}>{t.charAt(0) + t.slice(1).toLowerCase()}</option>)}
              </select>
            </Field>
            <Field label="Applies to">
              <select value={holForm.branchOnly ? "branch" : "org"}
                      onChange={(e) => setHolForm({ ...holForm, branchOnly: e.target.value === "branch" })}>
                <option value="org">All branches</option>
                <option value="branch">This branch only</option>
              </select>
            </Field>
            <Field label="Paid">
              <select value={holForm.isPaid} onChange={(e) => setHolForm({ ...holForm, isPaid: Number(e.target.value) })}>
                <option value={1}>Paid</option>
                <option value={0}>Unpaid</option>
              </select>
            </Field>
          </div>
        </Modal>
      )}
    </>
  );
}
