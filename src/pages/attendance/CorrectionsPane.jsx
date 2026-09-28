/**
 * CorrectionsPane — days somebody is disputing.
 *
 * A disputed day still says what it said. The proposal sits beside it and the
 * register is unchanged until somebody with Approve decides — which is what
 * makes this a correction rather than an edit, and the only route by which a
 * device punch can be changed at all.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Card } from "../../components/UI.jsx";
import {
  HrTable, Status, HrButton, HrRowActions, HrSearch, PolicyNote, P, humanError,
} from "../../components/HrKit.jsx";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import { getCorrections, approveCorrection, rejectCorrection, hoursOf } from "../../services/hrService.js";

export default function CorrectionsPane({ perm, addToast, onChanged, reloadToken }) {
  const confirm = useConfirm();
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(true);
  const [err, setErr]   = useState(null);
  const [q, setQ]       = useState("");

  const load = useCallback(async () => {
    setBusy(true);
    try { setRows(await getCorrections()); setErr(null); }
    catch (e) { setErr(humanError(e, "The corrections could not be read.")); }
    finally { setBusy(false); }
  }, []);

  useEffect(() => { load(); }, [load, reloadToken]);

  const decide = async (row, approve) => {
    const ok = await confirm({
      title: approve ? "Approve this correction?" : "Reject this correction?",
      body: approve
        ? `${row.empName}'s ${row.attendanceDate} becomes ${row.proposedStatus || row.attendanceStatus}, and the worked total is recalculated from the times.`
        : "The day is left exactly as it is. The request stays on the record.",
      confirmLabel: approve ? "Approve" : "Reject",
      tone: approve ? "primary" : "danger",
    });
    if (!ok) return;
    try {
      if (approve) await approveCorrection(row.id);
      else await rejectCorrection(row.id, "Not accepted");
      addToast(approve ? "Correction applied." : "Correction rejected.");
      load();
      onChanged?.();
    } catch (e) {
      addToast(humanError(e, "That did not go through."), "error");
    }
  };

  const t = q.trim().toLowerCase();
  const shown = t
    ? rows.filter((r) => (r.empName || "").toLowerCase().includes(t) || (r.empId || "").toLowerCase().includes(t))
    : rows;

  return (
    <Card title={`Corrections waiting for a decision · ${rows.length}`}
          right={rows.length > 8 && (
            <div style={{ minWidth: 220 }}>
              <HrSearch value={q} onChange={setQ} delay={0} placeholder="Name or code…" label="Search corrections" />
            </div>
          )}>
      <HrTable
        rowKey={(r) => r.id}
        rows={shown}
        loading={busy && !rows.length}
        error={err}
        onRetry={load}
        caption="Attendance corrections waiting for a decision"
        empty={q
          ? { icon: P.search, title: "Nobody matches that", hint: "The queue has nobody by that name or code." }
          : { icon: P.check, title: "Nothing disputed",
              hint: "No day at this branch is being argued with. A correction appears here the moment somebody asks for one." }}
        cols={[
          { key: "empName", label: "Employee", lead: true, sortable: true },
          { key: "attendanceDate", label: "Date", sortable: true },
          { key: "now", label: "Currently",
            render: (r) => <Status code={r.attendanceStatus} label={r.attendanceStatusLabel} /> },
          { key: "wanted", label: "Asked for",
            render: (r) => (
              <span>
                {r.proposedStatus
                  ? <Status code={r.proposedStatus} label={r.proposedStatus} />
                  : <span className="hr-dash">times only</span>}
                {r.proposedOvertimeMinutes != null && (
                  <span style={{ marginLeft: 6 }}>· OT {hoursOf(r.proposedOvertimeMinutes)}</span>
                )}
              </span>
            ) },
          { key: "correctionReason", label: "Reason", advisory: true },
          { key: "correctionRequestedBy", label: "Asked by", advisory: true },
          { key: "act", label: "", actions: true,
            render: (r) => perm.canApprove && (
              <HrRowActions
                items={[{ label: "Reject this correction", icon: P.x, danger: true,
                          onClick: () => decide(r, false) }]}>
                <HrButton size="sm" variant="primary" icon={P.check}
                          onClick={() => decide(r, true)}>Approve</HrButton>
              </HrRowActions>
            ) },
        ]}
      />
      <PolicyNote>
        A disputed day <strong>still says what it said</strong> — the proposal sits beside it and
        the register is unchanged until somebody approves. That is what makes this a correction
        rather than an edit, and it is the only route by which a device punch can be changed at all.
      </PolicyNote>
    </Card>
  );
}
