/**
 * CalendarPane — the month, with everything hanging off it.
 *
 * The same working calendar the register is judged by. Nothing here is
 * stored twice: every entry is read from the row it already lives in — a
 * holiday, an approved leave request, a date on somebody's record — so
 * cancelling leave takes it off this calendar with no second copy to keep.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Card } from "../../components/UI.jsx";
import {
  HrButton, HrFilterBar, Filter, HrEmployeePicker, Facts, Fact, HrSkeleton, PolicyNote, P,
} from "../../components/HrKit.jsx";
import { getHrCalendar, searchEmployees, CALENDAR_EVENT_LABEL, isoToday } from "../../services/hrService.js";

const shiftMonth = (ym, n) => {
  const [y, m] = ym.split("-").map(Number);
  const d = new Date(y, m - 1 + n, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
};

export default function CalendarPane({ reloadToken }) {
  const [month, setMonth] = useState(() => isoToday().slice(0, 7));
  const [who, setWho]     = useState("");
  const [cal, setCal]     = useState(null);

  const search = useCallback(async (q) => (await searchEmployees(q)).items, []);

  const load = useCallback(async () => {
    setCal(null);
    try {
      /* The month's last day from its own parts, never through toISOString():
         a local midnight east of Greenwich converts back a day and the month
         quietly lost its last date. */
      const [y, m] = month.split("-").map(Number);
      const lastDay = new Date(y, m, 0).getDate();
      setCal(await getHrCalendar(`${month}-01`, `${month}-${String(lastDay).padStart(2, "0")}`, who || undefined));
    } catch {
      setCal({ days: [], failed: true });
    }
  }, [month, who]);

  useEffect(() => { load(); }, [load, reloadToken]);

  const label = new Date(month + "-01T00:00:00").toLocaleDateString(undefined, { month: "long", year: "numeric" });

  return (
    <Card title="What is happening this month">
      <HrFilterBar active={who ? 1 : 0} onReset={who ? () => setWho("") : undefined}>
        <Filter label="Month">
          <div className="hr-datenav">
            <HrButton iconOnly icon={P.back} title="Previous month" size="sm" onClick={() => setMonth(shiftMonth(month, -1))} />
            <input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} aria-label="Month" />
            <HrButton iconOnly icon={P.chevron} title="Next month" size="sm" onClick={() => setMonth(shiftMonth(month, 1))} />
          </div>
        </Filter>
        <Filter label="Person" grow>
          <HrEmployeePicker value={who} onChange={setWho} search={search} allowEmpty emptyLabel="Everybody" />
        </Filter>
      </HrFilterBar>

      {!cal ? (
        <div className="hr-calendar-grid" aria-busy="true">
          <span className="sr-only" role="status">Loading the month…</span>
          {Array.from({ length: 35 }).map((_, i) => (
            <div className="hr-cal-day" key={i} aria-hidden="true">
              <HrSkeleton w="38%" h={13} />
              <HrSkeleton w="80%" h={9} style={{ marginTop: 6 }} />
            </div>
          ))}
        </div>
      ) : (
        <>
          <Facts>
            <Fact label="Month" value={label} />
            <Fact label="Working days" value={cal.workingDays} />
            <Fact label="Calendar days" value={cal.calendarDays} />
            <Fact label="Days with something on" value={cal.daysWithEvents} />
            <Fact label="Events" value={cal.eventCount} />
          </Facts>

          <div className="hr-calendar-grid">
            {(cal.days || []).map((d) => (
              <div key={d.date}
                   className={"hr-cal-day" + (d.workingDay ? "" : " is-off") + (d.holiday ? " is-holiday" : "")
                              + (d.date === isoToday() ? " is-today" : "")}>
                <div className="hr-cal-date">
                  <strong>{Number(d.date.slice(8))}</strong>
                  <span>{new Date(d.date + "T00:00:00").toLocaleDateString(undefined, { weekday: "short" })}</span>
                </div>
                {!d.workingDay && <div className="hr-cal-off">{d.holiday ? d.holidayName : "Weekend"}</div>}
                {(d.events || []).filter((e) => e.eventType !== "HOLIDAY").map((e, i) => (
                  <div key={i} className={"hr-cal-event ev-" + e.eventType.toLowerCase()} title={e.detail || e.title}>
                    <span className="hr-cal-kind">{CALENDAR_EVENT_LABEL[e.eventType] || e.eventType}</span>
                    <span className="hr-cal-what">{e.empName || e.title}</span>
                  </div>
                ))}
              </div>
            ))}
          </div>

          {cal.configured === false && (
            <PolicyNote>
              No working policy is set, so <strong>every day counts as a working day</strong>. Leave
              and birthdays are shown either way.
            </PolicyNote>
          )}
          <PolicyNote>
            Nothing here is stored twice. Every entry is read from the row it already lives in, so
            cancelling leave takes it off this calendar with no second copy to keep in step.
          </PolicyNote>
        </>
      )}
    </Card>
  );
}
