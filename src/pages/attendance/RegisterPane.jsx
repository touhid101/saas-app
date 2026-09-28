/**
 * RegisterPane — one day, for a branch of any size.
 *
 * <b>The register lists people, not rows.</b> The ones with no row yet are the
 * point: at nine in the morning the question is "who have we not marked", and a
 * list of what exists answers the wrong one.
 *
 * <b>Built for 2,500.</b> The page is a page — fifty people, searched and
 * filtered on the server, with the day's counts computed over the whole branch
 * rather than over the fifty on screen. Marks made on page three survive a
 * trip to page nine: they are held by employee id, and the save bar counts all
 * of them. And because a register that size is filled by somebody who never
 * lifts their hands from the keys, the keys do it: ↑↓ walk the rows, P A L H
 * mark the one under the cursor and move on.
 *
 * <b>Finishing a day is one request.</b> "Everyone else was present" is done
 * on the server, over the people the current filter shows who have no row yet.
 * A browser downloading 2,500 people to post 2,400 of them back is not a
 * feature.
 *
 * <b>What cannot be marked here says so.</b> A day written by approved leave is
 * locked — marking it present would leave the leave still counted and the day
 * paid twice. A device punch is evidence, and is changed through a correction
 * rather than overwritten.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Card, Modal, Field } from "../../components/UI.jsx";
import EmployeeAvatar from "../../components/EmployeeAvatar.jsx";
import {
  Kpis, Kpi, HrTable, Status, HrButton, HrSearch, HrPagination, HrProgress,
  PolicyNote, Ic, P, humanError,
} from "../../components/HrKit.jsx";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import {
  getAttendanceDay, recordAttendanceDay, fillAttendanceDay, requestCorrection,
  hoursOf, isoToday,
} from "../../services/hrService.js";

/* The four a register is actually filled with, and the key that makes each. */
const QUICK = [
  { code: "PRESENT",  label: "P", key: "p", title: "Present" },
  { code: "ABSENT",   label: "A", key: "a", title: "Absent" },
  { code: "LATE",     label: "L", key: "l", title: "Late" },
  { code: "HALF_DAY", label: "½", key: "h", title: "Half day" },
];
const KEY_TO_CODE = Object.fromEntries(QUICK.map((s) => [s.key, s.code]));

const FILTERS = [
  { id: "",             label: "Everyone" },
  { id: "NOT_RECORDED", label: "Not recorded" },
  { id: "PRESENT",      label: "Present" },
  { id: "LATE",         label: "Late" },
  { id: "ABSENT",       label: "Absent" },
  { id: "LEAVE",        label: "On leave" },
];

const fmt = (n) => Number(n || 0).toLocaleString("en-US");
const shiftDate = (iso, days) => {
  const d = new Date(iso + "T00:00:00");
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};
const weekday = (iso) => new Date(iso + "T00:00:00").toLocaleDateString(undefined, { weekday: "long" });

/** A row that must not be marked from the register, and why. */
const lockOf = (r) =>
  r.leaveRequestId ? "leave" : r.editable === false ? "device" : null;

export default function RegisterPane({ perm, addToast, departments, shifts, date, setDate, onChanged }) {
  const confirm = useConfirm();

  /* ── What is being looked at ── */
  const [page, setPage]   = useState(0);
  const [size, setSize]   = useState(50);
  const [q, setQ]         = useState("");
  const [deptId, setDept] = useState("");
  const [shiftId, setShift] = useState("");
  const [filter, setFilter] = useState("");

  const [day, setDay]   = useState(null);
  const [busy, setBusy] = useState(true);
  const [err, setErr]   = useState(null);

  /* ── What has been decided but not yet saved ──
     `marks` is by employee id and survives paging, filtering and date-free
     reloads. `known` remembers every row this session has seen, so a mark on
     a page that is no longer on screen can still be compared with what the
     server holds and counted in the save bar. Both reset when the date does:
     a mark is a fact about one day. */
  const [marks, setMarks] = useState({});
  const [known, setKnown] = useState({});
  const [saving, setSaving] = useState(false);
  const [filling, setFilling] = useState(false);

  /* The row the keyboard is on. */
  const [cursor, setCursor] = useState(null);
  const tableRef = useRef(null);

  const [detail, setDetail]     = useState(null);
  const [corrForm, setCorrForm] = useState(null);

  const isFuture = date > isoToday();
  const canMark = perm.canCreate && !isFuture;

  /* ── Loading ── */
  const load = useCallback(async () => {
    setBusy(true);
    try {
      const data = await getAttendanceDay(date, {
        page, size, q, departmentId: deptId, shiftId, filter,
      });
      setDay(data);
      setKnown((k) => {
        const next = { ...k };
        (data.rows || []).forEach((r) => {
          next[r.employeeId] = {
            id: r.id, attendanceStatus: r.attendanceStatus, empName: r.empName,
            lock: lockOf(r),
          };
        });
        return next;
      });
      setErr(null);
      // A page that came back empty because the filter moved past it goes to
      // the last page that exists, rather than showing "nothing" for a filter
      // that matches hundreds.
      if (data.rows?.length === 0 && data.page > 0 && data.totalPages > 0) {
        setPage(Math.max(0, data.totalPages - 1));
      }
    } catch (e) {
      setErr(humanError(e, "The register could not be read."));
    } finally {
      setBusy(false);
    }
  }, [date, page, size, q, deptId, shiftId, filter]);

  useEffect(() => { load(); }, [load]);

  /* A new day is a new register. Nothing decided about Tuesday applies to
     Wednesday, and carrying it over would be the worst kind of wrong. */
  useEffect(() => { setMarks({}); setKnown({}); setCursor(null); setPage(0); }, [date]);

  /* Any narrowing goes back to page one — page 40 of a filter with three pages
     is nowhere. */
  const narrow = (fn) => { fn(); setPage(0); };

  /* ── Marks ── */
  const mark = useCallback((employeeId, code) => {
    setMarks((m) => {
      const cur = m[employeeId];
      const next = { ...m };
      if (cur === code) delete next[employeeId]; else next[employeeId] = code;
      return next;
    });
  }, []);

  /** Rows on this page that may be marked from here. */
  const markable = useMemo(
    () => (day?.rows || []).filter((r) => !lockOf(r)),
    [day]);

  const markPage = (code) => {
    if (!canMark) return;
    setMarks((m) => {
      const next = { ...m };
      markable.forEach((r) => { next[r.employeeId] = code; });
      return next;
    });
  };

  /** Every mark that would change something the server holds, across every page seen. */
  const changed = useMemo(() => {
    const rows = [];
    for (const [id, wanted] of Object.entries(marks)) {
      const k = known[id];
      if (!k || k.lock) continue;
      if (k.id && k.attendanceStatus === wanted) continue;   // nothing to say
      rows.push({ employeeId: Number(id), attendanceDate: date, attendanceStatus: wanted });
    }
    return rows;
  }, [marks, known, date]);

  const onThisPage = useMemo(() => {
    const here = new Set((day?.rows || []).map((r) => r.employeeId));
    return changed.filter((c) => here.has(c.employeeId)).length;
  }, [changed, day]);

  const save = async () => {
    if (!changed.length) return;
    setSaving(true);
    try {
      await recordAttendanceDay(date, changed);
      addToast(`${fmt(changed.length)} ${changed.length === 1 ? "row" : "rows"} recorded.`);
      setMarks({});
      await load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "Nothing was saved."), "error");
    } finally {
      setSaving(false);
    }
  };

  const discard = async () => {
    if (changed.length > 3) {
      const ok = await confirm({
        title: `Discard ${fmt(changed.length)} unsaved marks?`,
        body: "They have not reached the server. The register goes back to what it says now.",
        confirmLabel: "Discard", tone: "danger",
      });
      if (!ok) return;
    }
    setMarks({});
  };

  /* ── Fill the rest, on the server ── */
  const fillRest = async (code) => {
    const scoped = !!(q || deptId || shiftId);
    const n = scoped ? null : day?.notRecorded;
    const ok = await confirm({
      title: `Mark everyone else ${QUICK.find((s) => s.code === code)?.title.toLowerCase()}?`,
      body: scoped
        ? "Everyone the current search and filters match who has no row for this day is marked. "
          + "Clear the filters first to do the whole branch."
        : `${fmt(n)} ${n === 1 ? "person has" : "people have"} no row for ${date}. Each is marked `
          + `${code.toLowerCase().replace("_", " ")}.`,
      detail: "Only people with no row are touched. A mark already made, a device punch, or a day "
            + "written by approved leave is left exactly as it is. This is one request and it is "
            + "all or nothing.",
      confirmLabel: "Mark them", tone: "primary",
    });
    if (!ok) return;
    setFilling(true);
    try {
      const out = await fillAttendanceDay({
        attendanceDate: date, attendanceStatus: code, q, departmentId: deptId, shiftId,
      });
      addToast(`${fmt(out.recorded)} marked ${code.toLowerCase().replace("_", " ")}. `
             + (out.stillUnrecorded > 0 ? `${fmt(out.stillUnrecorded)} still unrecorded.` : "The day is complete."));
      await load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "Nothing was filled in."), "error");
    } finally {
      setFilling(false);
    }
  };

  /* ── The keyboard ── */
  const rows = day?.rows || [];
  const cursorIdx = rows.findIndex((r) => r.employeeId === cursor);

  const onKey = (e) => {
    // Typing in a field is typing, not marking.
    if (/^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName)) return;
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    if (!rows.length) return;

    const key = e.key.toLowerCase();
    if (key === "arrowdown" || key === "j") {
      e.preventDefault();
      setCursor(rows[Math.min(rows.length - 1, cursorIdx + 1)].employeeId);
    } else if (key === "arrowup" || key === "k") {
      e.preventDefault();
      setCursor(rows[Math.max(0, cursorIdx - 1)].employeeId);
    } else if (KEY_TO_CODE[key] && canMark && cursorIdx >= 0) {
      e.preventDefault();
      const r = rows[cursorIdx];
      if (!lockOf(r)) mark(r.employeeId, KEY_TO_CODE[key]);
      // And on to the next, which is what filling a register is.
      if (cursorIdx < rows.length - 1) setCursor(rows[cursorIdx + 1].employeeId);
    } else if (key === "enter" && cursorIdx >= 0 && canMark) {
      const r = rows[cursorIdx];
      if (!lockOf(r)) { e.preventDefault(); openTimes(r); }
    } else if (key === "escape") {
      setCursor(null);
    } else if ((key === "s") && e.shiftKey === false && changed.length) {
      // Plain S saves, the same as the button — never Ctrl+S, which is the browser's.
      e.preventDefault(); save();
    }
  };

  /* Keep the cursor row on screen as the keys walk it. */
  useEffect(() => {
    if (cursor == null || !tableRef.current) return;
    const el = tableRef.current.querySelector(`tr[data-emp="${cursor}"]`);
    el?.scrollIntoView({ block: "nearest" });
  }, [cursor]);

  const openTimes = (r) => setDetail({
    employeeId: r.employeeId, empName: r.empName,
    attendanceStatus: marks[r.employeeId] || r.attendanceStatus || "PRESENT",
    checkIn: "", checkOut: "", overtimeMinutes: r.overtimeMinutes || 0,
    lateMinutes: r.lateMinutes || 0, remarks: r.remarks || "",
  });

  /** Record one person's times. Built field by field — see the note in the old page. */
  const saveDetail = async () => {
    const minutes = (v) => (v === "" || v === null || v === undefined ? null : Number(v));
    try {
      await recordAttendanceDay(date, [{
        employeeId: detail.employeeId,
        attendanceDate: date,
        attendanceStatus: detail.attendanceStatus,
        checkIn: detail.checkIn || null,
        checkOut: detail.checkOut || null,
        overtimeMinutes: minutes(detail.overtimeMinutes),
        lateMinutes: minutes(detail.lateMinutes),
        remarks: detail.remarks || null,
      }]);
      setDetail(null);
      setMarks((m) => { const n = { ...m }; delete n[detail.employeeId]; return n; });
      addToast("Attendance recorded.");
      load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "That did not save."), "error");
    }
  };

  const askCorrection = async () => {
    try {
      await requestCorrection(corrForm.id, {
        attendanceStatus: corrForm.attendanceStatus || null,
        checkIn: corrForm.checkIn || null,
        checkOut: corrForm.checkOut || null,
        overtimeMinutes: corrForm.overtimeMinutes === "" ? null : Number(corrForm.overtimeMinutes),
        reason: corrForm.reason,
      });
      setCorrForm(null);
      addToast("Correction requested.");
      load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "Could not request that correction."), "error");
    }
  };

  /* ── Derived ── */
  const byStatus = day?.byStatus || {};
  const total = day?.total ?? 0;
  const recorded = day?.recorded ?? 0;
  const notRecorded = day?.notRecorded ?? 0;
  const activeFilters = (q ? 1 : 0) + (deptId ? 1 : 0) + (shiftId ? 1 : 0) + (filter ? 1 : 0);
  const complete = total > 0 && notRecorded === 0;

  const kpiTile = (id, label, value, extra = {}) => (
    <Kpi label={label} value={fmt(value)} {...extra}
         onClick={() => narrow(() => setFilter(filter === id ? "" : id))}
         note={filter === id ? "Showing these — click to clear" : extra.note} />
  );

  return (
    <div className="hr-reg" onKeyDown={onKey}>
      {/* ── Which day, and how it is going ── */}
      <div className="hr-reg-head">
        <div className="hr-datenav">
          <HrButton iconOnly icon={P.back} title="The day before" size="sm"
                    onClick={() => setDate(shiftDate(date, -1))} />
          <input type="date" value={date} max={isoToday()} aria-label="Register date"
                 onChange={(e) => e.target.value && setDate(e.target.value)} />
          <HrButton iconOnly icon={P.chevron} title="The day after" size="sm"
                    disabled={date >= isoToday()}
                    onClick={() => setDate(shiftDate(date, 1))} />
          {date !== isoToday() && (
            <HrButton size="sm" onClick={() => setDate(isoToday())}>Today</HrButton>
          )}
          <span className="hr-datenav-day">{weekday(date)}</span>
        </div>

        <div className="hr-reg-progress">
          <HrProgress value={recorded} max={total}
                      label={complete ? "Register complete" : "Recorded so far"}
                      tone={complete ? "sage" : notRecorded > total / 2 ? "amber" : "teal"} />
        </div>
      </div>

      {/* ── The day, in tiles that are also filters ── */}
      <Kpis>
        {kpiTile("NOT_RECORDED", "Not recorded", notRecorded,
                 { icon: P.alert, warn: notRecorded > 0,
                   note: notRecorded > 0 ? "Payroll counts these as nothing" : "Every person has a row" })}
        {kpiTile("PRESENT", "Present", byStatus.PRESENT || 0, { icon: P.check })}
        {kpiTile("LATE", "Late", byStatus.LATE || 0, { icon: P.clock })}
        {kpiTile("ABSENT", "Absent", byStatus.ABSENT || 0, { icon: P.x })}
        {kpiTile("LEAVE", "On leave", byStatus.LEAVE || 0, { icon: P.away })}
      </Kpis>

      {isFuture && (
        <PolicyNote>
          <strong>{date} has not happened yet.</strong> The register can be read for any day but only
          marked up to today.
        </PolicyNote>
      )}

      <Card
        title={`Register · ${fmt(day?.matching ?? 0)} ${activeFilters ? "matching" : "people"}`}
        right={canMark && (
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <HrButton size="sm" icon={P.check} disabled={!markable.length}
                      title="Mark every person on this page present (unsaved until you save)"
                      onClick={() => markPage("PRESENT")}>
              Mark page present
            </HrButton>
            {notRecorded > 0 && (
              <HrButton size="sm" variant="teal" icon={P.wand}
                        busy={filling} busyLabel="Marking…"
                        title="Everyone with no row yet is marked present, on the server, in one request"
                        onClick={() => fillRest("PRESENT")}>
                Everyone else present
              </HrButton>
            )}
          </div>
        )}>

        {/* ── Search and filters ── */}
        <div className="hr-reg-tools">
          <HrSearch value={q} onChange={(v) => narrow(() => setQ(v))}
                    placeholder="Name or code…" label="Search the register" />
          <select value={deptId} aria-label="Department"
                  onChange={(e) => narrow(() => setDept(e.target.value))}>
            <option value="">All departments</option>
            {(departments || []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
          <select value={shiftId} aria-label="Shift"
                  onChange={(e) => narrow(() => setShift(e.target.value))}>
            <option value="">All shifts</option>
            {(shifts || []).filter((s) => s.status === "Y")
              .map((s) => <option key={s.id} value={s.id}>{s.shiftName}</option>)}
          </select>
          <select value={filter} aria-label="Show"
                  onChange={(e) => narrow(() => setFilter(e.target.value))}>
            {FILTERS.map((f) => <option key={f.id} value={f.id}>{f.label}</option>)}
          </select>
          {activeFilters > 0 && (
            <HrButton size="sm" icon={P.reset}
                      onClick={() => narrow(() => { setQ(""); setDept(""); setShift(""); setFilter(""); })}>
              Clear
            </HrButton>
          )}
          <span className="hr-spacer" />
          {canMark && (
            <span className="hr-keys" aria-label="Keyboard">
              <span><kbd>↑</kbd><kbd>↓</kbd> move</span>
              <span><kbd>P</kbd><kbd>A</kbd><kbd>L</kbd><kbd>H</kbd> mark</span>
              <span><kbd>↵</kbd> times</span>
              <span><kbd>S</kbd> save</span>
            </span>
          )}
        </div>

        {/* ── The page ── */}
        <div ref={tableRef} tabIndex={0} style={{ outline: "none" }}
             aria-label="Register rows — use the arrow keys and P, A, L or H">
          <HrTable
            rowKey={(r) => r.employeeId}
            rows={rows}
            loading={busy && !day}
            error={err}
            onRetry={load}
            caption={`Attendance register for ${date}`}
            onRowClick={(r) => setCursor(r.employeeId)}
            rowClass={(r) => [
              r.employeeId === cursor ? "is-cursor" : "",
              marks[r.employeeId] && changed.some((c) => c.employeeId === r.employeeId) ? "is-dirty" : "",
            ].filter(Boolean).join(" ")}
            rowAttrs={(r) => ({ "data-emp": r.employeeId })}
            empty={activeFilters
              ? { icon: P.filter, title: "Nobody matches that",
                  hint: filter === "NOT_RECORDED" && !q && !deptId && !shiftId
                    ? "Everyone has a row for this day. The register is complete."
                    : "Widen the search, or clear the filters.",
                  action: <HrButton icon={P.reset}
                                    onClick={() => narrow(() => { setQ(""); setDept(""); setShift(""); setFilter(""); })}>
                            Clear the filters
                          </HrButton> }
              : { icon: P.people, title: "Nobody is employed at this branch yet",
                  hint: "The register lists every active person. Add staff before marking a day." }}
            cols={[
              { key: "empName", label: "Person", lead: true,
                render: (r) => (
                  <span className="hr-reg-who">
                    <EmployeeAvatar src={r.imagePath} initials={r.initials} name={r.empName} size={30} />
                    <span className="hr-reg-who-text">
                      <span className="hr-reg-who-name">{r.empName}</span>
                      <span className="hr-reg-who-meta">
                        {r.empId}{r.departmentName ? ` · ${r.departmentName}` : ""}
                        {r.shiftName ? ` · ${r.shiftName}` : ""}
                      </span>
                    </span>
                  </span>
                ) },
              { key: "mark", label: "Mark",
                render: (r) => {
                  const lock = lockOf(r);
                  if (lock === "leave") {
                    return <span className="hr-reg-locked"><Ic path={P.away} /> Approved leave</span>;
                  }
                  if (lock === "device") {
                    return (
                      <span className="hr-reg-locked" title="Recorded by a device — change it through a correction">
                        <Ic path={P.lock} /> {r.attendanceStatusLabel}
                      </span>
                    );
                  }
                  const on = marks[r.employeeId] ?? r.attendanceStatus;
                  return (
                    <div className="hr-mark" role="group" aria-label={`Mark ${r.empName}`}>
                      {QUICK.map((s) => (
                        <button key={s.code} type="button" title={`${s.title} (${s.key.toUpperCase()})`}
                                disabled={!canMark}
                                className={`mark-${s.code}${on === s.code ? " is-on" : ""}`}
                                onClick={() => { setCursor(r.employeeId); mark(r.employeeId, s.code); }}>
                          {s.label}
                        </button>
                      ))}
                    </div>
                  );
                } },
              { key: "overtimeMinutes", label: "Overtime", num: true, advisory: true,
                render: (r) => (r.overtimeMinutes ? hoursOf(r.overtimeMinutes) : <span className="hr-dash">—</span>) },
              { key: "attendanceStatus", label: "Recorded",
                render: (r) => r.id
                  ? <Status code={r.attendanceStatus} label={r.attendanceStatusLabel} />
                  : <span className="hr-dash">—</span> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <>
                    {r.correctionStatus === "REQUESTED" && (
                      <span title={r.correctionReason}><Status code="SUBMITTED" label="Fix pending" /></span>
                    )}
                    {r.id && perm.canCreate && lockOf(r) !== "leave" && r.correctionStatus !== "REQUESTED" && (
                      <HrButton size="sm" icon={P.edit} title="Ask for this day to be corrected"
                                onClick={() => setCorrForm({
                                  id: r.id, empName: r.empName, date: r.attendanceDate,
                                  current: r.attendanceStatusLabel,
                                  attendanceStatus: "", checkIn: "", checkOut: "",
                                  overtimeMinutes: "", reason: "",
                                })}>
                        Fix
                      </HrButton>
                    )}
                    {canMark && !lockOf(r) && (
                      <HrButton size="sm" icon={P.clock} title="Record check-in, check-out and overtime"
                                onClick={() => openTimes(r)}>
                        Times
                      </HrButton>
                    )}
                  </>
                ) },
            ]}
          />
        </div>

        <HrPagination page={day?.page ?? page} size={size}
                      total={day?.matching ?? 0} totalPages={day?.totalPages ?? 1}
                      onPage={setPage}
                      onSize={(s) => { setSize(s); setPage(0); }} />
      </Card>

      {/* ── The save bar, which follows the page ── */}
      {changed.length > 0 && (
        <div className="hr-savebar" role="region" aria-label="Unsaved marks">
          <div className="hr-savebar-text">
            <b>{fmt(changed.length)}</b> unsaved {changed.length === 1 ? "mark" : "marks"}
            {changed.length !== onThisPage && (
              <> · <b>{fmt(onThisPage)}</b> on this page</>
            )}
            <small>Nothing reaches the server until you save. Saving is all or nothing.</small>
          </div>
          <div className="hr-savebar-actions">
            <HrButton icon={P.undo} onClick={discard} disabled={saving}>Discard</HrButton>
            <HrButton variant="primary" icon={P.check} onClick={save}
                      busy={saving} busyLabel="Saving…">
              Save {fmt(changed.length)}
            </HrButton>
          </div>
        </div>
      )}

      {/* ── Times ── */}
      {detail && (
        <Modal title={`Times — ${detail.empName}`} width={520} onClose={() => setDetail(null)}
               footer={
                 <>
                   <HrButton onClick={() => setDetail(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check} onClick={saveDetail}>Save</HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Status">
              <select value={detail.attendanceStatus}
                      onChange={(e) => setDetail({ ...detail, attendanceStatus: e.target.value })}>
                {["PRESENT", "ABSENT", "LATE", "EARLY_LEAVE", "HALF_DAY", "HOLIDAY", "WEEKEND"]
                  .map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
              </select>
            </Field>
            <Field label="Overtime (minutes)">
              <input type="number" min="0" value={detail.overtimeMinutes}
                     onChange={(e) => setDetail({ ...detail, overtimeMinutes: e.target.value })} />
            </Field>
            <Field label="Checked in">
              <input type="datetime-local" value={detail.checkIn}
                     onChange={(e) => setDetail({ ...detail, checkIn: e.target.value })} />
            </Field>
            <Field label="Checked out">
              <input type="datetime-local" value={detail.checkOut}
                     onChange={(e) => setDetail({ ...detail, checkOut: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <Field label="Note">
                <input value={detail.remarks}
                       onChange={(e) => setDetail({ ...detail, remarks: e.target.value })} />
              </Field>
            </div>
            <div className="hr-form-full">
              <PolicyNote>
                Giving both times makes the worked total come from them rather than from anything
                typed. Overtime is recorded whether or not it is paid; what an hour is worth is set
                on the salary structure.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Ask for a correction ── */}
      {corrForm && (
        <Modal title={`Correct ${corrForm.date} — ${corrForm.empName}`} width={560}
               onClose={() => setCorrForm(null)}
               footer={
                 <>
                   <HrButton onClick={() => setCorrForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check} onClick={askCorrection}
                             disabled={!corrForm.reason
                               || (!corrForm.attendanceStatus && !corrForm.checkIn
                                   && !corrForm.checkOut && corrForm.overtimeMinutes === "")}>
                     Request
                   </HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Currently"><input value={corrForm.current || "—"} readOnly /></Field>
            <Field label="Should be" hint="Leave blank to change only the times.">
              <select value={corrForm.attendanceStatus}
                      onChange={(e) => setCorrForm({ ...corrForm, attendanceStatus: e.target.value })}>
                <option value="">— unchanged —</option>
                {["PRESENT", "ABSENT", "LATE", "EARLY_LEAVE", "HALF_DAY", "HOLIDAY", "WEEKEND"]
                  .map((s) => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
              </select>
            </Field>
            <Field label="Checked in">
              <input type="datetime-local" value={corrForm.checkIn}
                     onChange={(e) => setCorrForm({ ...corrForm, checkIn: e.target.value })} />
            </Field>
            <Field label="Checked out">
              <input type="datetime-local" value={corrForm.checkOut}
                     onChange={(e) => setCorrForm({ ...corrForm, checkOut: e.target.value })} />
            </Field>
            <Field label="Overtime (minutes)">
              <input type="number" min="0" value={corrForm.overtimeMinutes}
                     onChange={(e) => setCorrForm({ ...corrForm, overtimeMinutes: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <Field label="Why">
                <textarea value={corrForm.reason} placeholder="What actually happened on this day?"
                          onChange={(e) => setCorrForm({ ...corrForm, reason: e.target.value })} />
              </Field>
            </div>
            <div className="hr-form-full">
              <PolicyNote>
                <strong>The day does not change yet.</strong> The register keeps saying what it says
                and this proposal sits beside it until somebody with the Approve right decides. A
                reason is required because the approver is being asked to agree to something.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
