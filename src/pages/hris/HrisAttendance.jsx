/**
 * HrisAttendance.jsx — my attendance: today, the month, the calendar.
 *
 * The same month grid the HR calendar uses (hr-cal-*), with my day drawn on
 * each square: recorded status, in and out, worked hours, a dashed outline
 * while a correction waits. Tapping a recorded day opens the correction form
 * on that day. Nothing here edits attendance — it asks.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Card } from "../../components/UI.jsx";
import { HrButton, HrSkeleton, HrErrorState, Status, Ic, humanError, P } from "../../components/HrKit.jsx";
import { hoursOf, isoToday } from "../../services/hrService.js";
import * as hris from "../../services/hrisService.js";

const shiftMonth = (ym, n) => {
  const [y, m] = ym.split("-").map(Number);
  const d = new Date(y, m - 1 + n, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
};
const t = (iso) => (iso ? String(iso).slice(11, 16) : "—");
const tagOf = (s) => (["PRESENT", "EARLY_LEAVE"].includes(s) ? "present" : s === "LATE" || s === "HALF_DAY" ? "late" : s === "ABSENT" ? "absent" : s === "LEAVE" ? "leave" : "");

export function TodayCard({ today, month, onCorrect }) {
  return (
    <Card title="Today" eyebrow={new Date().toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}
          right={today?.id && !today.leaveRequestId && today.correctionStatus !== "REQUESTED" && (
            <HrButton size="sm" variant="ghost" icon={P.edit} onClick={() => onCorrect(today)}>Ask for a correction</HrButton>
          )}>
      <div className="hris-today">
        <div className="hris-stat"><span>Status</span><b>{today ? <Status code={today.attendanceStatus} label={today.attendanceStatusLabel} /> : <span className="hr-dash">Not recorded yet</span>}</b>
          {today?.correctionStatus === "REQUESTED" && <small>correction pending</small>}</div>
        <div className="hris-stat"><span>Check-in</span><b>{t(today?.checkIn)}</b>{today?.lateMinutes > 0 && <small className="is-warn">{today.lateMinutes} min late</small>}</div>
        <div className="hris-stat"><span>Check-out</span><b>{t(today?.checkOut)}</b>{today?.earlyLeaveMinutes > 0 && <small>{today.earlyLeaveMinutes} min early</small>}</div>
        <div className="hris-stat"><span>Worked</span><b>{hoursOf(today?.workingMinutes)}</b>{today?.overtimeMinutes > 0 && <small>{hoursOf(today.overtimeMinutes)} overtime</small>}</div>
      </div>
      {month && (
        <>
          <h4 style={{ margin: "14px 0 8px", fontSize: 12, letterSpacing: ".06em", textTransform: "uppercase", color: "var(--ink-soft)" }}>This month</h4>
          <div className="hris-today">
            <div className="hris-stat"><span>Present</span><b>{month.present}</b><small>of {month.workingDays} working days</small></div>
            <div className="hris-stat"><span>Late</span><b className={month.late > 0 ? "is-warn" : ""}>{month.late}</b></div>
            <div className="hris-stat"><span>Absent</span><b className={month.absent > 0 ? "is-warn" : ""}>{month.absent}</b>{month.unrecorded > 0 && <small>{month.unrecorded} not recorded</small>}</div>
            <div className="hris-stat"><span>Leave</span><b>{month.leave}</b><small>{month.holiday} holiday{month.holiday === 1 ? "" : "s"} · {month.weekend} weekend day{month.weekend === 1 ? "" : "s"}</small></div>
          </div>
        </>
      )}
    </Card>
  );
}

export default function HrisAttendance({ onCorrect, onOpen, reloadToken }) {
  const [month, setMonth] = useState(() => isoToday().slice(0, 7));
  const [data, setData] = useState(null);
  const [cal, setCal] = useState(null);
  const [err, setErr] = useState(null);

  const load = useCallback(async () => {
    setData(null); setErr(null);
    try {
      const [a, c] = await Promise.all([hris.getAttendance(month), hris.getCalendar(month).catch(() => null)]);
      setData(a); setCal(c);
    } catch (e) { setErr(humanError(e, "Your attendance could not be read.")); }
  }, [month]);
  useEffect(() => { load(); }, [load, reloadToken]);

  const label = new Date(month + "-01T00:00:00").toLocaleDateString(undefined, { month: "long", year: "numeric" });
  const byDate = new Map((data?.rows || []).map((r) => [r.attendanceDate, r]));
  const holidays = new Map((data?.holidays || []).map((h) => [h.date, h]));
  const weekends = new Set(data?.weekends || []);
  const events = new Map((cal?.days || []).map((d) => [d.date, (d.events || []).filter((e) => e.eventType !== "HOLIDAY")]));

  const daysOfMonth = [];
  if (data) {
    const [y, m] = month.split("-").map(Number);
    const n = new Date(y, m, 0).getDate();
    for (let i = 1; i <= n; i++) daysOfMonth.push(`${month}-${String(i).padStart(2, "0")}`);
  }

  return (
    <Card title="My calendar" eyebrow={label}
          right={<div className="hr-datenav">
            <HrButton iconOnly icon={P.back} title="Previous month" size="sm" onClick={() => setMonth(shiftMonth(month, -1))} />
            <input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} aria-label="Month" />
            <HrButton iconOnly icon={P.chevron} title="Next month" size="sm" onClick={() => setMonth(shiftMonth(month, 1))} />
          </div>}>
      {err ? <HrErrorState title="Your attendance could not be read" hint={err} onRetry={load} /> : !data ? (
        <div className="hr-calendar-grid" aria-busy="true">
          <span className="sr-only" role="status">Loading the month…</span>
          {Array.from({ length: 35 }).map((_, i) => (
            <div className="hr-cal-day" key={i} aria-hidden="true"><HrSkeleton w="38%" h={13} /><HrSkeleton w="80%" h={9} style={{ marginTop: 6 }} /></div>
          ))}
        </div>
      ) : (
        <>
          <div className="hris-cal-legend" aria-label="Legend">
            <span><i style={{ background: "var(--sage)" }} />Present</span>
            <span><i style={{ background: "var(--amber)" }} />Late / half day</span>
            <span><i style={{ background: "var(--clay)" }} />Absent</span>
            <span><i style={{ background: "var(--teal)" }} />Leave</span>
            <span><i style={{ border: "2px dashed var(--amber)", background: "transparent" }} />Correction pending</span>
            <span><i style={{ background: "var(--paper-deep)" }} />Holiday / weekend</span>
          </div>
          {/* Weeks start on Saturday, the first working day here; the 1st sits
              under its own weekday so a column reads as one day of the week. */}
          <div className="hr-calendar-grid hris-cal-grid">
            {["Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri"].map((w) => <div key={w} className="hris-cal-wd" aria-hidden="true">{w}</div>)}
            {Array.from({ length: daysOfMonth.length ? (new Date(daysOfMonth[0] + "T00:00:00").getDay() + 1) % 7 : 0 }).map((_, i) => (
              <div key={"pad" + i} className="hr-cal-day is-pad" aria-hidden="true" />
            ))}
            {daysOfMonth.map((d) => {
              const r = byDate.get(d);
              const h = holidays.get(d);
              const off = h || weekends.has(d);
              const tag = r ? tagOf(r.attendanceStatus) : "";
              const clickable = !!(r && r.id);
              return (
                <div key={d}
                     className={"hr-cal-day" + (off ? " is-off" : "") + (h ? " is-holiday" : "") + (d === isoToday() ? " is-today" : "")
                                + (tag ? ` is-${tag}` : "") + (r?.correctionStatus === "REQUESTED" ? " is-pending" : "") + (clickable ? " is-clickable" : "")}
                     role={clickable ? "button" : undefined} tabIndex={clickable ? 0 : undefined}
                     aria-label={clickable ? `${d}: ${r.attendanceStatusLabel || r.attendanceStatus}. Open` : undefined}
                     onClick={clickable ? () => onOpen(r) : undefined}
                     onKeyDown={clickable ? (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onOpen(r); } } : undefined}>
                  <div className="hr-cal-date">
                    <strong>{Number(d.slice(8))}</strong>
                    <span>{new Date(d + "T00:00:00").toLocaleDateString(undefined, { weekday: "short" })}</span>
                  </div>
                  {h && <div className="hr-cal-off">{h.name}</div>}
                  {!h && weekends.has(d) && !r && <div className="hr-cal-off">Weekend</div>}
                  {r && (
                    <>
                      <span className={`hris-cal-tag t-${tag || "none"}`}>{r.attendanceStatusLabel || r.attendanceStatus}</span>
                      {(r.checkIn || r.checkOut) && <span className="hris-cal-time">{t(r.checkIn)} – {t(r.checkOut)}</span>}
                      {r.workingMinutes > 0 && <span className="hris-cal-time hr-dash">{hoursOf(r.workingMinutes)}{r.overtimeMinutes > 0 ? ` +${hoursOf(r.overtimeMinutes)}` : ""}</span>}
                      {r.correctionStatus === "REQUESTED" && <span className="hris-cal-tag t-late">Correction pending</span>}
                    </>
                  )}
                  {(events.get(d) || []).map((e, i) => (
                    <div key={i} className={"hr-cal-event ev-" + String(e.eventType).toLowerCase()} title={e.detail || e.title}>
                      <span className="hr-cal-kind">{String(e.eventType).toLowerCase()}</span>
                      <span className="hr-cal-what">{e.title}</span>
                    </div>
                  ))}
                  {!r && !off && d <= isoToday() && <span className="hr-cal-off">Not recorded</span>}
                </div>
              );
            })}
          </div>
          <div className="hr-form-note">
            <Ic path={P.info} size={14} />
            <span>Tap a recorded day to see it in full and ask for a correction. A day nobody has recorded yet has nothing to correct.</span>
          </div>
        </>
      )}
    </Card>
  );
}
