/**
 * HrAttendance.jsx — the daily register (MOD33), rebuilt for a branch of 2,500.
 *
 * <b>One section at a time.</b> Register, history, corrections, overtime, the
 * calendar and the policy used to be six panes stacked down one page on any
 * screen wider than a phone — at 2,500 people that is a page nobody could
 * scroll to the end of. They are tabs now: a strip along the top on a desktop,
 * the same set as the bottom bar on a phone, both bound to one state.
 *
 * <b>The register is the reason the page exists</b> and it is built for
 * volume: paged and filtered on the server, marks that survive paging, a save
 * bar that follows the page, "everyone else was present" done in one request
 * on the server, and a keyboard — because a register that size is filled by
 * somebody who never lifts their hands from the keys. See RegisterPane.
 *
 * <b>Nobody is chosen from a list of everybody.</b> Every place a person is
 * picked — history, a claim, the calendar — types against the server and gets
 * the twenty that match. A `<select>` of 2,500 names is not a control.
 *
 * What each pane needs is loaded once here and handed down; what only one pane
 * needs, that pane loads itself. A change in one pane that another must see —
 * a correction approved, a holiday added — bumps `reloadToken`, and the panes
 * that read the day reload.
 */
import React, { useCallback, useEffect, useState } from "react";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import {
  HrPage, HrTabs, HrTabBar, HrButton, HrPageSkeleton, HrErrorState, P, HR_CRUMB, humanError,
} from "../components/HrKit.jsx";
import {
  getPolicy, getShifts, getHolidays, getWorkingCalendar, getDepartments,
  getCorrections, getPendingOvertime, getAttendanceDay,
  isoToday, monthStart,
} from "../services/hrService.js";
import RegisterPane from "./attendance/RegisterPane.jsx";
import HistoryPane from "./attendance/HistoryPane.jsx";
import CorrectionsPane from "./attendance/CorrectionsPane.jsx";
import OvertimePane from "./attendance/OvertimePane.jsx";
import CalendarPane from "./attendance/CalendarPane.jsx";
import PolicyPane from "./attendance/PolicyPane.jsx";

export default function HrAttendance() {
  const { addToast } = useApp();
  const perm = usePermission("attendance");

  const [pane, setPane] = useState("register");
  const [date, setDate] = useState(isoToday());
  const [who, setWho]   = useState("");          // the person the history pane is on

  /* ── What every pane shares ── */
  const [policy, setPolicyState] = useState(null);
  const [shifts, setShifts]       = useState([]);
  const [holidays, setHolidays]   = useState([]);
  const [calendar, setCalendar]   = useState(null);
  const [departments, setDepartments] = useState([]);
  const [holYear, setHolYear]     = useState(new Date().getFullYear());
  const [ready, setReady]         = useState(false);
  const [loadErr, setLoadErr]     = useState(null);

  /* ── The tab badges: how much is waiting where ── */
  const [counts, setCounts] = useState({ notRecorded: 0, corrections: 0, overtime: 0 });

  /* Bumped whenever one pane changes something another shows. */
  const [reloadToken, setReloadToken] = useState(0);
  const bump = useCallback(() => setReloadToken((n) => n + 1), []);

  const loadShared = useCallback(async () => {
    try {
      const [p, s, h, c, d] = await Promise.all([
        getPolicy().catch(() => null),
        getShifts(false).catch(() => []),
        getHolidays(holYear).catch(() => []),
        getWorkingCalendar(monthStart(date), date).catch(() => null),
        getDepartments().catch(() => []),
      ]);
      setPolicyState(p);
      setShifts(s || []);
      setHolidays(h || []);
      setCalendar(c);
      setDepartments(d || []);
      setLoadErr(null);
    } catch (e) {
      setLoadErr(humanError(e, "The attendance settings could not be read."));
    } finally {
      setReady(true);
    }
  }, [holYear, date]);

  useEffect(() => { loadShared(); }, [loadShared]);

  /* The badges are counts over the whole branch, asked for cheaply — one
     page of one row is enough to get the day's totals. */
  const loadCounts = useCallback(async () => {
    try {
      const [day, corr, ot] = await Promise.all([
        getAttendanceDay(date, { page: 0, size: 1 }).catch(() => null),
        getCorrections().catch(() => []),
        getPendingOvertime().catch(() => []),
      ]);
      setCounts({
        notRecorded: day?.notRecorded || 0,
        corrections: (corr || []).length,
        overtime: (ot || []).length,
      });
    } catch { /* badges are a courtesy */ }
  }, [date]);

  useEffect(() => { loadCounts(); }, [loadCounts, reloadToken]);

  if (!ready) return <HrPageSkeleton label="Loading attendance…" kpis={5} />;

  if (loadErr && !policy && !shifts.length) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Attendance" }]} title="Attendance">
        <HrErrorState title="Attendance could not be loaded" hint={loadErr} onRetry={loadShared} />
      </HrPage>
    );
  }

  const tabs = [
    { id: "register",    label: "Register",  icon: P.calendar, badge: counts.notRecorded },
    { id: "history",     label: "History",   icon: P.clock },
    { id: "corrections", label: "Fixes",     icon: P.doc,      badge: counts.corrections },
    { id: "overtime",    label: "Overtime",  icon: P.timer,    badge: counts.overtime },
    { id: "calendar",    label: "Calendar",  icon: P.sun },
    { id: "policy",      label: "Policy",    icon: P.settings, badge: policy && policy.weekendSet ? 0 : 1 },
  ];

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Attendance" }]}
      title="Attendance"
      subtitle="One row per person per day — what payroll counts a working month from"
      actions={
        <HrButton iconOnly icon={P.reset} title="Refresh everything"
                  onClick={() => { loadShared(); bump(); }} />
      }
    >
      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "register" && (
        <RegisterPane perm={perm} addToast={addToast}
                      departments={departments} shifts={shifts}
                      date={date} setDate={setDate}
                      onChanged={bump} />
      )}
      {pane === "history" && (
        <HistoryPane addToast={addToast} who={who} setWho={setWho} />
      )}
      {pane === "corrections" && (
        <CorrectionsPane perm={perm} addToast={addToast} onChanged={bump} reloadToken={reloadToken} />
      )}
      {pane === "overtime" && (
        <OvertimePane perm={perm} addToast={addToast} policy={policy} date={date}
                      onChanged={bump} reloadToken={reloadToken} />
      )}
      {pane === "calendar" && (
        <CalendarPane reloadToken={reloadToken} />
      )}
      {pane === "policy" && (
        <PolicyPane perm={perm} addToast={addToast}
                    policy={policy} shifts={shifts} holidays={holidays} calendar={calendar}
                    holYear={holYear} setHolYear={setHolYear}
                    reload={loadShared} onChanged={bump} />
      )}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />
    </HrPage>
  );
}
