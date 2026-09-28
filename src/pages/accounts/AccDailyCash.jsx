/**
 * AccDailyCash — the branch business day.
 *
 *   previous closing → today's opening → cash in / out (ledger) → expected
 *   → physical count (by denomination) → difference → close → next opening
 *
 * The figures on this page are the ledger's; the page never keeps a balance
 * of its own. Actions appear only when the server says they are allowed.
 */
import React, { useEffect, useMemo, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { HrPage, HrTable, HrFilterBar, Filter, HrButton, HrTabBar, HrStepper, HrTimeline, Status, Kpis, Kpi, HrPageSkeleton, HrErrorState, humanError, P } from "../../components/HrKit.jsx";
import { getDaySheet, getCashBook, openDay, countDay, closeDay, reopenDay } from "../../services/accountsService.js";
import { amount, compact, variance, fmtDate, fmtDateTime, isoToday } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, BranchPicker, DateRange, Figure, ReasonPrompt, useAsync, takeIntent } from "./AccKit.jsx";

const STEPS = [
  { id: "review", label: "Review transactions" }, { id: "expected", label: "Expected cash" }, { id: "count", label: "Count physical cash" },
  { id: "reconcile", label: "Reconcile difference" }, { id: "submit", label: "Submit closing" }, { id: "review2", label: "Approve / review" }, { id: "closed", label: "Day closed" },
];

export default function AccDailyCash({ onNavigate }) {
  const perm = usePermission("accdailycash");
  const { addToast } = useApp();
  const [intent] = useState(() => takeIntent("accdailycash"));
  const [tab, setTab] = useState(intent?.tab || "today");
  const [date, setDate] = useState(isoToday());
  const [branchId, setBranchId] = useState(null);
  const sheet = useAsync(() => getDaySheet({ date, branchId }), [date, branchId]);
  const [counts, setCounts] = useState({});
  const [reason, setReason] = useState("");
  const [notes, setNotes] = useState("");
  const [reopening, setReopening] = useState(false);
  const [busy, setBusy] = useState(false);
  const s = sheet.data;
  useEffect(() => {
    if (!s) return;
    const m = {}; (s.counts || []).forEach((c) => { m[Number(c.denomValue)] = c.quantity; });
    setCounts(m); setReason(s.day?.differenceReason || ""); setNotes(s.day?.notes || "");
  }, [s?.day?.pidBusinessDay, s?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  const counted = useMemo(() => (s?.denominations || []).reduce((t, d) => t + Number(d.denomValue) * (Number(counts[Number(d.denomValue)]) || 0), 0), [counts, s]);
  const diffLive = s ? counted - Number(s.expectedClosing || 0) : 0;
  const stepAt = !s ? 0 : s.status === "CLOSED" || s.status === "LOCKED" ? 7 : s.status === "SUBMITTED" ? 5 : s.physicalCash != null ? 3 : s.status === "NOT_STARTED" ? 0 : 1;

  const run = async (fn, okMsg) => { setBusy(true); try { const d = await fn(); sheet.setData(d); if (okMsg) addToast(okMsg); } catch (e) { addToast(humanError(e, "The action was refused."), "error"); } finally { setBusy(false); } };
  const doOpen = () => run(() => openDay({ branchId, date }), "Business day opened.");
  const doCount = () => run(() => countDay(s.day.pidBusinessDay, { counts: (s.denominations || []).map((d) => ({ denomValue: d.denomValue, quantity: Number(counts[Number(d.denomValue)]) || 0 })) }), "Cash count saved.");
  const doClose = () => run(() => closeDay(s.day.pidBusinessDay, { differenceReason: reason, notes }), s.requiresReview && !perm.canApprove ? "Closing submitted for review." : "Business day closed.");
  const doReopen = (why) => { setReopening(false); run(() => reopenDay(s.day.pidBusinessDay, why), "Day reopened."); };

  if (sheet.busy && !s) return <HrPageSkeleton label="Reading the day sheet…" kpis={6} rows={4} />;
  if (sheet.err && !s) return <HrErrorState hint={sheet.err} onRetry={sheet.reload} />;
  const frozen = ["SUBMITTED", "CLOSED", "LOCKED"].includes(s.status);
  const tol = Number(s.tolerance || 0);
  const needsReason = s.physicalCash != null && Math.abs(Number(s.difference || 0)) > tol;

  return (
    <HrPage className="acc-page acc-dc" icon={AI.till} crumbs={[ACC_CRUMB]} title="Daily Cash" subtitle={`${s.branchName || "Branch"} · business date ${fmtDate(s.businessDate)} · previous closing ${s.previousClosing == null ? "none" : amount(s.previousClosing)}${s.previousDate ? ` (${fmtDate(s.previousDate)})` : ""}`}
            actions={<div className="acc-actions">
              <Status code={s.status} />
              {s.canOpen && <HrButton variant="primary" busy={busy} onClick={doOpen}>Open day</HrButton>}
              {s.canReopen && <HrButton icon={P.undo} onClick={() => setReopening(true)}>Reopen…</HrButton>}
              <HrButton icon={P.reset} onClick={sheet.reload} busy={sheet.busy}>Refresh</HrButton>
            </div>}>
      <HrFilterBar>
        <Filter label="Business date"><input type="date" value={date} max={isoToday()} onChange={(e) => setDate(e.target.value)} /></Filter>
        <BranchPicker value={branchId} onChange={setBranchId} />
      </HrFilterBar>
      {s.unclosedEarlier?.length > 0 && <p className="acc-alert">Earlier days are still open: {s.unclosedEarlier.map(fmtDate).join(", ")}. Close them first — each opening is the previous closing.</p>}
      <AccTabs tabs={[{ id: "today", label: "Day sheet", icon: AI.till }, { id: "book", label: "Cash book", icon: AI.history }]} active={tab} onChange={setTab} />
      {tab === "book" ? <CashBook branchId={branchId} onPick={(d) => { setDate(d); setTab("today"); }} /> : (
        <>
          <Kpis>
            <Kpi icon={AI.play} label="Opening cash" value={compact(s.openingCash)} note={s.openingSource === "PREVIOUS_CLOSING" ? "from the previous closing" : s.openingSource === "DAY" ? "fixed when the day opened" : "ledger balance"} />
            <Kpi icon={AI.trendUp} label="Cash in" value={compact(s.cashIn)} tone="sage" />
            <Kpi icon={AI.trendDown} label="Cash out" value={compact(s.cashOut)} tone="red" />
            <Kpi icon={AI.scale} label="Expected cash" value={compact(s.expectedClosing)} tone="teal" />
            <Kpi icon={AI.coins} label="Physical cash" value={s.physicalCash == null ? "—" : compact(s.physicalCash)} />
            <Kpi icon={AI.alert} label="Difference" value={s.difference == null ? "—" : variance(s.difference)} warn={s.differenceKind === "SHORT"} tone={s.differenceKind === "SHORT" ? "red" : s.differenceKind === "OVER" ? "amber" : "sage"} />
          </Kpis>
          <HrStepper steps={STEPS} at={stepAt} />
          <div className="acc-two">
            <section className="hr-card acc-card">
              <header className="acc-card-head"><div><h3>1 · Cash movement</h3><p className="acc-muted">Every posted line on the till dated {fmtDate(s.businessDate)}. Click a row for the journals.</p></div></header>
              <HrTable rows={s.movements || []} rowKey={(m, i) => i} empty="No cash movement on this date yet." onRowClick={() => onNavigate?.("accledger")}
                cols={[{ key: "sourceModule", label: "Source", lead: true, render: (m) => <><b>{m.sourceModule}</b> <span className="acc-muted">{m.sourceType}</span></> },
                       { key: "count", label: "Lines", num: true }, { key: "cashIn", label: "Cash in", num: true, render: (m) => amount(m.cashIn) }, { key: "cashOut", label: "Cash out", num: true, render: (m) => amount(m.cashOut) }]} />
              <div className="acc-figures acc-figures-row">
                <Figure label="Opening" value={amount(s.openingCash)} />
                <Figure label="+ In" value={amount(s.cashIn)} tone="sage" />
                <Figure label="− Out" value={amount(s.cashOut)} tone="red" />
                <Figure label="2 · Expected closing" value={amount(s.expectedClosing)} big />
              </div>
            </section>
            <section className="hr-card acc-card">
              <header className="acc-card-head"><div><h3>3 · Count physical cash</h3><p className="acc-muted">By denomination. The total is the drawer as it is, not as it should be.</p></div></header>
              {s.status === "NOT_STARTED" ? <p className="acc-muted">Open the day to count.</p> : (
                <>
                  <table className="hr-table acc-count">
                    <thead><tr><th>Denomination</th><th className="hr-num">Count</th><th className="hr-num">Amount</th></tr></thead>
                    <tbody>{(s.denominations || []).map((d) => {
                      const v = Number(d.denomValue), q = Number(counts[v]) || 0;
                      return (<tr key={v}><td>{amount(v)} <span className="acc-muted">{d.denomKind?.toLowerCase()}</span></td>
                        <td className="hr-num"><input type="number" min="0" inputMode="numeric" value={counts[v] ?? ""} disabled={!s.canCount} onChange={(e) => setCounts((c) => ({ ...c, [v]: e.target.value }))} aria-label={`Count of ${v}`} /></td>
                        <td className="hr-num">{amount(v * q)}</td></tr>);
                    })}</tbody>
                    <tfoot><tr><td>Physical cash</td><td /><td className="hr-num"><b>{amount(counted)}</b></td></tr></tfoot>
                  </table>
                  <div className="acc-figures acc-figures-row">
                    <Figure label="Expected" value={amount(s.expectedClosing)} />
                    <Figure label="Counted" value={amount(counted)} />
                    <Figure label="4 · Difference" value={variance(diffLive)} tone={diffLive < 0 ? "red" : diffLive > 0 ? "amber" : "sage"} big />
                  </div>
                  {s.canCount && <div className="acc-actions"><HrButton variant="primary" busy={busy} onClick={doCount}>Save count</HrButton></div>}
                </>
              )}
            </section>
          </div>
          {s.status !== "NOT_STARTED" && (
            <section className="hr-card acc-card">
              <header className="acc-card-head"><div><h3>5 · Close the day</h3><p className="acc-muted">{s.requiresReview ? "Closing is submitted for review, then approved." : "Closing is final for the date; a cash posting into it is refused afterwards."} Tolerance {amount(tol)}. {s.postsVariance ? "A difference is posted to Cash Short/Over so the ledger equals the drawer." : "A difference is recorded on the day sheet; the ledger keeps the expected figure."}</p></div></header>
              {frozen ? (
                <div className="acc-figures acc-figures-row">
                  <Figure label="Result" value={s.differenceKind ? variance(s.difference) : "—"} tone={s.differenceKind === "SHORT" ? "red" : s.differenceKind === "OVER" ? "amber" : "sage"} big />
                  <Figure label="Reason" value={s.day?.differenceReason || "—"} />
                  <Figure label="Closed" value={s.day?.closedAt ? `${fmtDateTime(s.day.closedAt)} by ${s.day.closedBy}` : s.day?.submittedAt ? `submitted ${fmtDateTime(s.day.submittedAt)} by ${s.day.submittedBy}` : "—"} />
                  <Figure label="Carried forward" value={amount(s.physicalCash)} hint="next day's opening" />
                  {s.canClose && s.status === "SUBMITTED" && <div className="acc-figure-actions"><HrButton variant="primary" busy={busy} onClick={doClose}>Approve closing</HrButton></div>}
                </div>
              ) : (
                <div className="acc-form-grid">
                  <div className="field acc-span2"><label>Reason for the difference {needsReason ? "(required)" : "(optional)"}</label>
                    <textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} disabled={!s.canClose} placeholder="e.g. change given from the drawer, note torn…" /></div>
                  <div className="field acc-span2"><label>Notes</label><input value={notes} onChange={(e) => setNotes(e.target.value)} disabled={!s.canClose} /></div>
                  <div className="acc-actions acc-span2">
                    {s.canClose && <HrButton variant="primary" busy={busy} disabled={s.physicalCash == null || (needsReason && !reason.trim())} onClick={doClose}>{s.requiresReview && !perm.canApprove ? "Submit closing" : "Close day"}</HrButton>}
                    {s.physicalCash == null && <span className="acc-muted">Save a count first.</span>}
                  </div>
                </div>
              )}
            </section>
          )}
          {(s.audit || []).length > 0 && (
            <section className="hr-card acc-card"><header className="acc-card-head"><div><h3>Audit timeline</h3></div></header>
              <HrTimeline items={(s.audit || []).map((a) => ({ id: a.pidDayAudit, title: a.action, note: a.detail, when: fmtDateTime(a.actedAt), who: a.actedBy, tone: a.action === "CLOSED" ? "sage" : a.action === "REOPENED" ? "amber" : "teal" }))} />
            </section>
          )}
        </>
      )}
      {reopening && <ReasonPrompt title={`Reopen ${fmtDate(s.businessDate)}`} hint="Reopening a closed day is recorded. A posted variance is reversed and the count can change." confirmLabel="Reopen" onConfirm={doReopen} onClose={() => setReopening(false)} />}
    </HrPage>
  );
}

function CashBook({ branchId, onPick }) {
  const [range, setRange] = useState({ from: isoToday().slice(0, 8) + "01", to: isoToday() });
  const { data, busy, err, reload } = useAsync(() => getCashBook({ branchId, from: range.from, to: range.to }), [branchId, range.from, range.to]);
  return (
    <>
      <HrFilterBar><DateRange from={range.from} to={range.to} onChange={setRange} /></HrFilterBar>
      <HrTable rows={data || []} rowKey={(r) => r.date} loading={busy} error={err} onRetry={reload} onRowClick={(r) => onPick(String(r.date).slice(0, 10))} empty="No cash movement in this window."
        cols={[{ key: "date", label: "Date", lead: true, render: (r) => <b>{fmtDate(r.date)}</b> }, { key: "openingCash", label: "Opening", num: true, render: (r) => amount(r.openingCash) },
               { key: "cashIn", label: "Cash in", num: true, render: (r) => amount(r.cashIn) }, { key: "cashOut", label: "Cash out", num: true, render: (r) => amount(r.cashOut) },
               { key: "expectedClosing", label: "Expected", num: true, render: (r) => amount(r.expectedClosing) }, { key: "physicalCash", label: "Counted", num: true, render: (r) => amount(r.physicalCash) },
               { key: "difference", label: "Difference", num: true, render: (r) => (r.difference == null ? "—" : variance(r.difference)) }, { key: "status", label: "Status", render: (r) => <Status code={r.status} /> },
               { key: "closedBy", label: "Closed by", render: (r) => (r.closedBy ? `${r.closedBy} · ${fmtDateTime(r.closedAt)}` : "") }]} />
    </>
  );
}
