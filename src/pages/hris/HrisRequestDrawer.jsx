/**
 * HrisRequestDrawer.jsx — one of my requests, read in full.
 *
 * A drawer, because the person is checking a request against the list they
 * came from. Six things, in the order somebody asks about them: where it is
 * (the rail), what it is (the business data), what was attached, what was
 * said, who signed, and everything that happened (the audit line).
 *
 * The only action here is withdraw — a decision is somebody else's.
 */
import React, { useEffect, useState } from "react";
import { useConfirm } from "../../lib/ConfirmProvider.jsx";
import {
  HrDrawer, HrRequestTimeline, HrTimeline, HrButton, HrErrorState, HrSkeleton, Status, Facts, Fact,
  HrTable, PolicyNote, Ic, humanError, fmtWhen, P,
} from "../../components/HrKit.jsx";
import { money, days, hoursOf } from "../../services/hrService.js";
import * as hris from "../../services/hrisService.js";
import { ReceiptGallery, ReceiptViewer, receiptItems, receiptProblem } from "../../components/ReceiptKit.jsx";

export const TYPE_ICON = { LEAVE: P.calendar, EXPENSE: P.receipt, ADVANCE: P.wallet, LOAN: P.money, ATTENDANCE: P.clock };
const t = (iso) => (iso ? String(iso).slice(11, 16) : "—");

export default function HrisRequestDrawer({ type, id, onClose, onChanged, addToast }) {
  const confirm = useConfirm();
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const [receiptBusy, setReceiptBusy] = useState(null);
  const [viewer, setViewer] = useState(null);   // { items, index }

  const load = async () => {
    setErr(null);
    try { setD(await hris.getRequest(type, id)); }
    catch (e) { setErr(humanError(e, "This request could not be read.")); }
  };
  useEffect(() => { setD(null); load(); }, [type, id]); // eslint-disable-line react-hooks/exhaustive-deps

  const item = d?.item;
  const data = d?.data || {};

  const withdraw = async () => {
    const ok = await confirm({
      title: "Withdraw this request?",
      body: "It is taken back and nobody decides it. It stays on your record as withdrawn.",
      confirmLabel: "Withdraw", tone: "danger",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await hris.cancelRequest(type, id, "Withdrawn by the employee");
      addToast("Request withdrawn.");
      onChanged?.();
      load();
    } catch (e) { addToast(humanError(e, "It could not be withdrawn."), "error"); }
    finally { setBusy(false); }
  };

  /* A draft claim is finished here: receipts on its lines, then Submit. */
  const submitClaim = async () => {
    setBusy(true);
    try {
      await hris.submitExpense(id);
      addToast("Expense claim submitted.");
      onChanged?.();
      load();
    } catch (e) { addToast(humanError(e, "The claim could not be submitted."), "error"); }
    finally { setBusy(false); }
  };
  const onReceipt = async (lineId, file) => {
    if (!file) return;
    const problem = receiptProblem(file);
    if (problem) { addToast(problem, "error"); return; }
    setReceiptBusy(lineId);
    try { await hris.attachReceipt(lineId, file); addToast("Receipt attached."); await load(); }
    catch (e) { addToast(humanError(e, "That file was not accepted."), "error"); }
    finally { setReceiptBusy(null); }
  };
  const dropReceipt = async (lineId) => {
    const ok = await confirm({ title: "Remove this receipt?", body: "The line stays; only the picture goes. Another can be attached while the claim is a draft.", confirmLabel: "Remove", tone: "danger" });
    if (!ok) return;
    setReceiptBusy(lineId);
    try { await hris.removeReceipt(lineId); await load(); }
    catch (e) { addToast(humanError(e, "Could not remove it."), "error"); }
    finally { setReceiptBusy(null); }
  };
  const lookAt = (line) => {
    const items = receiptItems(data.lines || [], { empName: item?.empName });
    const index = Math.max(0, items.findIndex((it) => it.lineId === line.id));
    if (items.length) setViewer({ items, index });
  };

  const title = item ? `${item.typeLabel}${item.requestNo ? ` · ${item.requestNo}` : ""}` : "Request";
  const viewerNode = viewer && (
    <ReceiptViewer items={viewer.items} index={viewer.index}
                   onIndex={(index) => setViewer({ ...viewer, index })} onClose={() => setViewer(null)} />
  );

  return (
    <HrDrawer title={title} subtitle={item?.title} width={560} onClose={onClose}
              footer={<>
                <HrButton onClick={onClose}>Close</HrButton>
                {item?.cancellable && (
                  <HrButton variant="danger" icon={P.ban} busy={busy} busyLabel="Withdrawing…" onClick={withdraw}>Withdraw</HrButton>
                )}
                {type === "EXPENSE" && item?.submittable && (
                  <HrButton variant="primary" icon={P.check} busy={busy} busyLabel="Submitting…" onClick={submitClaim}>Submit claim</HrButton>
                )}
              </>}>
      {err ? (
        <HrErrorState title="This request could not be read" hint={err} onRetry={load} />
      ) : !d ? (
        <div className="hris-detail" aria-busy="true">
          <HrSkeleton w="60%" h={18} /><HrSkeleton w="100%" h={80} /><HrSkeleton w="100%" h={120} />
        </div>
      ) : (
        <div className="hris-detail">
          <div className="hris-detail-head">
            <div>
              <b>{item.title}</b>
              <p>{item.subtitle}</p>
            </div>
            <Status code={item.status} label={item.statusLabel} />
          </div>

          {item.actionRequired && (
            <div className="hris-attn" role="status">
              <Ic path={P.alert} size={16} />
              <div className="hris-attn-body"><b>Something is waiting on you</b>{item.actionText}</div>
            </div>
          )}

          <section>
            <h4>Where it is</h4>
            <div className="hris-stat" style={{ marginBottom: 10 }}>
              <span>Current stage</span><b style={{ fontSize: 14 }}>{item.stage}</b>
              {item.stageNo != null && <small>Step {item.stageNo} of {item.stageTotal}</small>}
            </div>
            <HrRequestTimeline stages={d.stages} />
          </section>

          {(item.status === "REJECTED" || item.status === "CANCELLED") && (
            <PolicyNote>
              <strong>{item.statusLabel}</strong>{item.decidedBy ? ` by ${item.decidedBy}` : ""}{item.decidedAt ? ` on ${fmtWhen(item.decidedAt)}` : ""}.
              {item.decisionRemarks ? ` Reason: “${item.decisionRemarks}”` : " No reason was recorded."}
            </PolicyNote>
          )}

          <section>
            <h4>The request</h4>
            <Facts>
              <Fact label="Number" value={item.requestNo || "—"} />
              <Fact label="Kind" value={item.typeLabel} />
              {item.requestDate && <Fact label="Filed" value={item.requestDate} />}
              {item.submittedAt && <Fact label="Submitted" value={fmtWhen(item.submittedAt)} />}
              {item.amount != null && <Fact label="Asked" value={money(item.amount)} num />}
              {item.approvedAmount != null && <Fact label="Approved" value={money(item.approvedAmount)} num />}
              {item.days != null && <Fact label="Days" value={days(item.days)} />}
              {item.updatedAt && <Fact label="Last updated" value={fmtWhen(item.updatedAt)} />}
            </Facts>
            <Business type={type} data={data} />
          </section>

          {/* Receipts as pictures. Replace and Remove exist only while the claim
              is a draft (submittable); once it is submitted, approved, rejected
              or withdrawn the evidence stays as it was sent. */}
          {type === "EXPENSE" && (data.lines || []).length > 0 && (
            <section>
              <ReceiptGallery lines={data.lines || []} editable={Boolean(item.submittable)} busyLine={receiptBusy}
                              onOpen={lookAt} onPick={onReceipt} onRemove={dropReceipt} />
            </section>
          )}

          {(item.decisionRemarks || (d.chain?.actions || []).some((a) => a.remarks)) && (
            <section>
              <h4>Comments</h4>
              <HrTimeline items={[
                ...(d.chain?.actions || []).filter((a) => a.remarks).map((a) => ({
                  title: a.levelLabel || `Level ${a.levelNo}`, who: a.decidedBy, when: fmtWhen(a.decidedAt), note: a.remarks,
                  tone: a.decision === "REJECTED" ? "clay" : "sage", icon: a.decision === "REJECTED" ? P.x : P.check,
                })),
                ...(item.decisionRemarks && !(d.chain?.actions || []).some((a) => a.remarks === item.decisionRemarks)
                  ? [{ title: item.statusLabel, who: item.decidedBy, when: fmtWhen(item.decidedAt), note: item.decisionRemarks,
                       tone: item.status === "REJECTED" ? "clay" : "sage", icon: item.status === "REJECTED" ? P.x : P.check }] : []),
              ]} />
            </section>
          )}

          <section>
            <h4>Approval history</h4>
            <HrTimeline
              empty={item.status === "DRAFT" ? "Not submitted yet." : "No signature has been given yet."}
              items={(d.history || []).map((h) => ({
                title: h.label, who: h.by, when: h.at ? fmtWhen(h.at) : null, note: h.remarks,
                tone: h.state === "REJECTED" ? "clay" : h.state === "CANCELLED" ? "grey" : "sage",
                icon: h.state === "REJECTED" ? P.x : h.state === "CANCELLED" ? P.ban : P.check,
              }))} />
            {d.chain && (
              <p className="hr-dash" style={{ margin: "8px 0 0", fontSize: 12 }}>
                Chain: {d.chain.flowName || "one signature"} · {d.chain.progress}
              </p>
            )}
          </section>
        </div>
      )}
      {viewerNode}
    </HrDrawer>
  );
}

/** The module's own record, laid out for the person it is about. */
function Business({ type, data }) {
  switch (type) {
    case "LEAVE":
      return (
        <Facts>
          <Fact label="Kind" value={data.leaveTypeName} />
          <Fact label="From" value={data.fromDate} />
          <Fact label="To" value={data.toDate} />
          <Fact label="Paid" value={data.paidLeave ? "Yes" : "Unpaid"} />
          {data.isHalfDay === 1 && <Fact label="Half day" value="Yes" />}
          {data.reason && <Fact label="Reason" value={data.reason} />}
        </Facts>
      );
    case "EXPENSE":
      return (
        <>
          {data.purpose && <Facts><Fact label="Purpose" value={data.purpose} /></Facts>}
          <HrTable
            rowKey={(r) => r.id}
            rows={data.lines || []}
            caption="Lines on this claim"
            empty=""
            cols={[
              { key: "typeName", label: "Kind", lead: true },
              { key: "expenseDate", label: "Date" },
              { key: "detail", label: "Detail", advisory: true, render: (r) => [r.fromPlace, r.toPlace].filter(Boolean).join(" to ") || r.description || "—" },
              { key: "claimedAmount", label: "Claimed", num: true, render: (r) => money(r.claimedAmount) },
              { key: "approvedAmount", label: "Allowed", num: true, render: (r) => (r.approvedAmount == null ? "—" : money(r.approvedAmount)) },
            ]}
          />
          {data.paidDate && (
            <Facts>
              <Fact label="Paid on" value={data.paidDate} />
              <Fact label="By" value={data.paymentMode} />
              {data.paymentReference && <Fact label="Reference" value={data.paymentReference} />}
            </Facts>
          )}
        </>
      );
    case "ADVANCE":
      return (
        <Facts>
          <Fact label="Asked" value={money(data.amount)} num />
          <Fact label="Approved" value={data.approvedAmount == null ? "—" : money(data.approvedAmount)} num />
          <Fact label="Recovered" value={money(data.recoveredAmount)} num />
          <Fact label="Outstanding" value={money(data.outstanding)} num />
          <Fact label="Over" value={data.installments ? `${data.installments} × ${money(data.installmentAmount)}` : "—"} />
          {data.recoverFrom && <Fact label="From" value={data.recoverFrom} />}
          {data.paidDate && <Fact label="Paid out" value={`${data.paidDate} · ${data.paymentMode || ""}`} />}
          {data.reason && <Fact label="Reason" value={data.reason} />}
        </Facts>
      );
    case "LOAN":
      return (
        <>
          <Facts>
            <Fact label="Asked" value={money(data.principal)} num />
            <Fact label="Approved" value={data.approvedPrincipal == null ? "—" : money(data.approvedPrincipal)} num />
            <Fact label="Repaid" value={money(data.recoveredAmount)} num />
            <Fact label="Outstanding" value={money(data.outstanding)} num />
            <Fact label="Over" value={data.installments ? `${data.installments} × ${money(data.installmentAmount)}` : "—"} />
            {data.disbursedDate && <Fact label="Disbursed" value={data.disbursedDate} />}
            {data.purpose && <Fact label="Purpose" value={data.purpose} />}
          </Facts>
          {(data.schedule || []).length > 0 && (
            <HrTable
              rowKey={(r) => r.id}
              rows={data.schedule}
              caption="Repayment schedule"
              empty=""
              cols={[
                { key: "installmentNo", label: "#", lead: true, render: (r) => `Instalment ${r.installmentNo}` },
                { key: "dueDate", label: "Due" },
                { key: "dueAmount", label: "Amount", num: true, render: (r) => money(r.dueAmount) },
                { key: "recoveredAmount", label: "Repaid", num: true, render: (r) => money(r.recoveredAmount) },
                { key: "scheduleStatus", label: "Status", render: (r) => <Status code={r.scheduleStatus} label={r.scheduleStatusLabel} /> },
              ]}
            />
          )}
        </>
      );
    case "ATTENDANCE":
      return (
        <>
          <div className="hris-readonly" style={{ marginBottom: 10 }}>
            <div className="hris-stat"><span>Recorded</span><b><Status code={data.attendanceStatus} label={data.attendanceStatusLabel} /></b><small>{t(data.checkIn)} – {t(data.checkOut)}</small></div>
            <div className="hris-stat"><span>Asked for</span><b>{data.proposedStatus ? data.proposedStatus.replace("_", " ").toLowerCase() : "as recorded"}</b><small>{t(data.proposedCheckIn)} – {t(data.proposedCheckOut)}</small></div>
            <div className="hris-stat"><span>Worked</span><b>{hoursOf(data.workingMinutes)}</b>{data.overtimeMinutes > 0 && <small>{hoursOf(data.overtimeMinutes)} overtime</small>}</div>
          </div>
          <Facts>
            {data.correctionReason && <Fact label="Reason" value={data.correctionReason} />}
            {data.correctionRequestedAt && <Fact label="Asked" value={fmtWhen(data.correctionRequestedAt)} />}
            {data.correctedBy && <Fact label="Decided by" value={data.correctedBy} />}
            {data.correctedAt && <Fact label="Decided" value={fmtWhen(data.correctedAt)} />}
            {data.source && <Fact label="Source" value={data.source} />}
          </Facts>
        </>
      );
    default:
      return null;
  }
}
