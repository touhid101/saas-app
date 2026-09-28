/**
 * HistoryPane — one person's days over a range.
 *
 * The person is found by typing, not chosen from a list of everyone: at 2,500
 * a `<select>` is a wall. The range has the three answers people actually
 * want as buttons, with the two dates underneath for anything else. The chips
 * above the table say what the month adds up to, because "how many days was
 * she late" is the question and counting rows is not an answer.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card } from "../../components/UI.jsx";
import {
  HrTable, Status, HrEmployeePicker, HrEmptyState, Filters, Filter, P, humanError,
} from "../../components/HrKit.jsx";
import { getAttendanceFor, searchEmployees, hoursOf, isoToday, monthStart } from "../../services/hrService.js";

const fmt = (n) => Number(n || 0).toLocaleString("en-US");

/** The first and last day of the month `offset` months from today. */
const monthRange = (offset) => {
  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth() + offset, 1);
  const last = new Date(now.getFullYear(), now.getMonth() + offset + 1, 0);
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  return [iso(first), iso(last)];
};

const PRESETS = [
  { id: "this",  label: "This month", range: () => [monthStart(isoToday()), isoToday()] },
  { id: "last",  label: "Last month", range: () => monthRange(-1) },
  { id: "30",    label: "Last 30 days", range: () => {
      const d = new Date(); d.setDate(d.getDate() - 29);
      return [`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`, isoToday()];
    } },
];

export default function HistoryPane({ addToast, who, setWho }) {
  const [from, setFrom] = useState(monthStart(isoToday()));
  const [to, setTo]     = useState(isoToday());
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr]   = useState(null);

  const search = useCallback(async (q) => (await searchEmployees(q)).items, []);

  const load = useCallback(async () => {
    if (!who) { setRows([]); return; }
    setBusy(true);
    try {
      setRows(await getAttendanceFor(who, from, to));
      setErr(null);
    } catch (e) {
      setErr(humanError(e, "That history could not be read."));
    } finally {
      setBusy(false);
    }
  }, [who, from, to]);

  useEffect(() => { load(); }, [load]);

  const sum = useMemo(() => {
    const s = { present: 0, late: 0, absent: 0, leave: 0, half: 0, ot: 0, worked: 0 };
    rows.forEach((r) => {
      if (r.attendanceStatus === "PRESENT" || r.attendanceStatus === "EARLY_LEAVE") s.present++;
      else if (r.attendanceStatus === "LATE") { s.present++; s.late++; }
      else if (r.attendanceStatus === "ABSENT") s.absent++;
      else if (r.attendanceStatus === "LEAVE") s.leave++;
      else if (r.attendanceStatus === "HALF_DAY") s.half++;
      s.ot += r.overtimeMinutes || 0;
      s.worked += r.workingMinutes || 0;
    });
    return s;
  }, [rows]);

  const preset = PRESETS.find((p) => { const [f, t] = p.range(); return f === from && t === to; })?.id;

  return (
    <Card title="One person's history">
      <Filters>
        <Filter label="Person" grow>
          <HrEmployeePicker value={who} onChange={setWho} search={search}
                            placeholder="Type a name or code…" />
        </Filter>
        <Filter label="Range">
          <div className="hr-mark">
            {PRESETS.map((p) => (
              <button key={p.id} type="button" className={preset === p.id ? "is-on" : ""}
                      onClick={() => { const [f, t] = p.range(); setFrom(f); setTo(t); }}>
                {p.label}
              </button>
            ))}
          </div>
        </Filter>
        <Filter label="From">
          <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        </Filter>
        <Filter label="To">
          <input type="date" value={to} min={from} max={isoToday()} onChange={(e) => setTo(e.target.value)} />
        </Filter>
      </Filters>

      {!who ? (
        <HrEmptyState icon={P.user} title="Choose somebody"
                      hint="Start typing a name or a code above. The history is one person's days over the range." />
      ) : (
        <>
          {rows.length > 0 && (
            <div className="hr-sumchips" aria-label="Totals for the range">
              <span className="hr-sumchip"><b>{fmt(rows.length)}</b> days recorded</span>
              <span className="hr-sumchip"><b>{fmt(sum.present)}</b> present</span>
              {sum.late > 0 && <span className="hr-sumchip"><b>{fmt(sum.late)}</b> of them late</span>}
              <span className="hr-sumchip"><b>{fmt(sum.absent)}</b> absent</span>
              {sum.leave > 0 && <span className="hr-sumchip"><b>{fmt(sum.leave)}</b> on leave</span>}
              {sum.half > 0 && <span className="hr-sumchip"><b>{fmt(sum.half)}</b> half days</span>}
              <span className="hr-sumchip"><b>{hoursOf(sum.worked)}</b> worked</span>
              {sum.ot > 0 && <span className="hr-sumchip"><b>{hoursOf(sum.ot)}</b> overtime</span>}
            </div>
          )}
          <HrTable
            rowKey={(r) => r.id}
            rows={rows}
            loading={busy && !rows.length}
            error={err}
            onRetry={load}
            caption="One person's attendance history"
            empty={{ icon: P.calendar, title: "Nothing recorded in that range",
                     hint: "No day between those two dates carries a record for this person." }}
            cols={[
              { key: "attendanceDate", label: "Date", lead: true, sortable: true,
                render: (r) => (
                  <span>{r.attendanceDate}
                    <span className="hr-reg-who-meta" style={{ marginLeft: 6 }}>
                      {new Date(r.attendanceDate + "T00:00:00").toLocaleDateString(undefined, { weekday: "short" })}
                    </span>
                  </span>
                ) },
              { key: "attendanceStatus", label: "Status", sortable: true,
                render: (r) => <Status code={r.attendanceStatus} label={r.attendanceStatusLabel} /> },
              { key: "checkIn", label: "In", advisory: true,
                render: (r) => r.checkIn ? String(r.checkIn).slice(11, 16) : <span className="hr-dash">—</span> },
              { key: "checkOut", label: "Out", advisory: true,
                render: (r) => r.checkOut ? String(r.checkOut).slice(11, 16) : <span className="hr-dash">—</span> },
              { key: "workingMinutes", label: "Worked", num: true, sortable: true,
                render: (r) => hoursOf(r.workingMinutes) },
              { key: "overtimeMinutes", label: "Overtime", num: true, sortable: true,
                render: (r) => (r.overtimeMinutes ? hoursOf(r.overtimeMinutes) : <span className="hr-dash">—</span>) },
              { key: "source", label: "Source", advisory: true },
              { key: "remarks", label: "Note", advisory: true },
            ]}
          />
        </>
      )}
    </Card>
  );
}
