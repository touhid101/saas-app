/**
 * Requisitions — what we are short of, and turning it into orders.
 *
 * This page used to run entirely on mock data and never called the API, so
 * requisitions the Copilot wrote to the database did not appear on it at all.
 * Everything here is now the real records.
 *
 * The shape of the work it supports: a requisition is raised (by hand or by the
 * Copilot), submitted, sent for approval, approved — and then <b>split</b>. The
 * split is the step that matters most and the one the system did not have. A
 * requisition spans as many manufacturers as the shortage does; a purchase order
 * is always one supplier. Splitting groups the lines by who can supply them and
 * shows the proposal before creating anything, because that is the point at
 * which money is committed and the last point at which changing your mind is
 * free.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill, Tabs, Table, QtyInput } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import { useApp } from "../lib/AppContext.jsx";
import NewRequisition from "./NewRequisition.jsx";
import PurchaseOrderPrint from "../components/PurchaseOrderPrint.jsx";
import reqService, { label as stageLabel, STAGE_TONE } from "../services/requisitionService.js";

const money = (n) =>
  "৳ " + Number(n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const PRIORITY_TONE = { CRITICAL: "clay", HIGH: "amber", MEDIUM: "teal", LOW: "grey" };

/** Why the system picked a supplier — the buyer should be able to weigh it. */
const BASIS = {
  LAST_PURCHASE: { label: "last bought here", tone: "sage" },
  MANUFACTURER:  { label: "represents the maker", tone: "amber" },
  OVERRIDE:      { label: "your choice", tone: "teal" },
  NONE:          { label: "no supplier found", tone: "clay" },
};

const TABS = ["All", "Pending Approval", "Approved", "Converted"];
const TAB_STAGE = {
  "All": null,
  "Pending Approval": "PENDING_APPROVAL",
  "Approved": "APPROVED",
  "Converted": "CONVERTED",
};

export default function Requisition({ onNavigate }) {
  const addToast = useToast();
  const perm = usePermission("requisition");
  const { branches, currentUser } = useApp();

  // A requisition is raised for a branch and delivered to it, so the list shows
  // one branch at a time. Defaults to where the user works.
  const [branchId, setBranchId] = useState("");
  const allBranches = branchId === "ALL";

  // The form replaces the list rather than floating over it, matching how Sales
  // and Product Master handle the same choice: a line table in a modal is a
  // table you cannot see the top of once you scroll.
  const [raising, setRaising] = useState(false);

  useEffect(() => {
    if (!branchId && currentUser?.branchId) setBranchId(String(currentUser.branchId));
  }, [currentUser, branchId]);

  const [tab, setTab] = useState("All");
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [openId, setOpenId] = useState(null);
  const [splitFor, setSplitFor] = useState(null);

  /* Reprinting the supplier copies of a requisition already split.
     { orders, at } — the sheets and which one is on screen. */
  const [reprint, setReprint] = useState(null);
  /* Bumped on every Refresh so an open card drops its cached lines and reads
     them again — stock, sales and today's prices are live figures, and a
     Refresh that only re-listed the summaries left them stale. */
  const [refreshKey, setRefreshKey] = useState(0);

  const openReprint = useCallback(async (r) => {
    try {
      const orders = await reqService.splitOrders(r.id);
      if (!orders.length) {
        // Converted but with nothing to show means the orders were deleted
        // since. Saying so beats opening an empty print preview.
        return addToast(`No purchase orders remain for ${r.requisitionNo}.`, "warning");
      }
      setReprint({ orders, at: 0 });
    } catch (e) {
      addToast(e?.message || "Could not load the orders for this requisition.", "error");
    }
  }, [addToast]);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    setRefreshKey((k) => k + 1);
    return reqService.listRequisitions({
        stage: TAB_STAGE[tab], size: 100,
        branchId: allBranches ? undefined : (branchId || undefined),
        allBranches,
      })
      .then((p) => setRows(p.items))
      .catch((e) => setError(e?.message || "Could not load requisitions."))
      .finally(() => setLoading(false));
  }, [tab, branchId, allBranches]);

  useEffect(() => { load(); }, [load]);

  const pending = rows.filter((r) => r.stage === "PENDING_APPROVAL").length;
  const fromAi = rows.filter((r) => r.source === "AI").length;

  if (raising) {
    return (
      <NewRequisition
        branchName={(branches ?? []).find((b) => String(b.id) === String(branchId))?.name}
        onCancel={() => setRaising(false)}
        onSaved={() => { setRaising(false); load(); }}
      />
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Requisition</h1>
          <div className="page-sub">
            What the shelves are short of, and the purchase orders it becomes. Requisitions
            raised by the Copilot appear here for review.
          </div>
        </div>
        <div className="flex-gap">
          <select className="req-branch" value={branchId} disabled={loading}
                  onChange={(e) => { setBranchId(e.target.value); setOpenId(null); }}
                  aria-label="Branch">
            {(branches ?? []).map((b) => (
              <option key={b.id} value={b.id}>{b.name}</option>
            ))}
            <option value="ALL">Every branch</option>
          </select>
          {pending > 0 && <Pill tone="amber">{pending} awaiting approval</Pill>}
          {fromAi > 0 && <Pill tone="teal">{fromAi} from the Copilot</Pill>}
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            {loading ? "Loading…" : "Refresh"}
          </button>
          {/* The Copilot writes one from the stock ledger; this types one. Same
              document, same approval — it just does not need a sales history to
              exist first. */}
          {perm.canCreate && (
            <button className="btn btn-primary btn-sm" onClick={() => setRaising(true)}>
              New requisition
            </button>
          )}
        </div>
      </div>

      <Tabs tabs={TABS} active={tab} onChange={(t) => { setTab(t); setOpenId(null); }} />

      {error && (
        <Card>
          <div className="empty-state" style={{ color: "var(--clay)" }}>
            {error}
            <div style={{ marginTop: 10 }}>
              <button className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
            </div>
          </div>
        </Card>
      )}

      {!error && loading && rows.length === 0 && (
        <Card><div className="empty-state">Loading…</div></Card>
      )}

      {!error && !loading && rows.length === 0 && (
        <Card>
          <div className="empty-state">
            {tab === "All"
              ? "No requisitions yet. The Copilot can raise one from the reorder list."
              : `Nothing at ${tab.toLowerCase()}.`}
            {tab === "All" && onNavigate && (
              <div style={{ marginTop: 12 }}>
                <button className="btn btn-ghost btn-sm" onClick={() => onNavigate("ai")}>
                  Open the Copilot →
                </button>
              </div>
            )}
          </div>
        </Card>
      )}

      <div className="stack">
        {rows.map((r) => (
          <RequisitionCard
            key={r.id} summary={r} perm={perm}
            open={openId === r.id} refreshKey={refreshKey}
            onToggle={() => setOpenId(openId === r.id ? null : r.id)}
            onChanged={load}
            onSplit={() => setSplitFor(r)}
            onReprint={() => openReprint(r)}
            addToast={addToast}
          />
        ))}
      </div>

      {splitFor && (
        <SplitModal
          requisition={splitFor}
          onClose={() => setSplitFor(null)}
          onDone={(res) => {
            setSplitFor(null);
            load();
            addToast(`${res.ordersCreated} draft purchase order${res.ordersCreated === 1 ? "" : "s"} created — ${money(res.total)}`);
          }}
          onNavigate={onNavigate}
          addToast={addToast}
        />
      )}

      {/* Reprinting an already-split requisition, one supplier at a time. */}
      {reprint?.orders?.[reprint.at] && (
        <PurchaseOrderPrint
          purchaseId={reprint.orders[reprint.at].purchaseId}
          sequence={{
            position: reprint.at + 1,
            total: reprint.orders.length,
            onNext: () => setReprint((r) => ({ ...r, at: r.at + 1 })),
          }}
          onClose={() => setReprint(null)}
        />
      )}
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   One requisition
   ══════════════════════════════════════════════════════════════════════════ */

function RequisitionCard({ summary, perm, open, refreshKey, onToggle, onChanged, onSplit, onReprint, addToast }) {
  const [detail, setDetail] = useState(null);
  const [busy, setBusy] = useState(false);
  const [edited, setEdited] = useState({});
  const confirm = useConfirm();

  // A Refresh from the page re-reads the lines of whichever card is open.
  const [seenKey, setSeenKey] = useState(refreshKey);
  useEffect(() => {
    if (refreshKey === seenKey) return;
    setSeenKey(refreshKey);
    if (detail && !busy) { setDetail(null); setEdited({}); }
  }, [refreshKey, seenKey, detail, busy]);

  useEffect(() => {
    if (!open || detail) return;
    reqService.getRequisition(summary.id)
      .then(setDetail)
      .catch((e) => addToast(e?.message || "Could not open that requisition.", "error"));
  }, [open, detail, summary.id, addToast]);

  const qtyOf = (l) => edited[l.lineId] ?? l.requestedQty;
  /* Priced at today's trade price + IGTA per box — the figure the purchase
     order will carry and the one the split totals — so "Estimated" here and
     the split's total are the same number. The estimate the requisition was
     raised on is kept and shown when it differs. */
  const costOf = (l) => l.unitCostNow ?? l.estimatedUnitCost;
  const total = useMemo(
    () => (detail?.lines ?? []).reduce((s, l) => s + qtyOf(l) * costOf(l), 0),
    [detail, edited]);  // eslint-disable-line react-hooks/exhaustive-deps
  const totalAtRaise = useMemo(
    () => (detail?.lines ?? []).reduce((s, l) => s + qtyOf(l) * l.estimatedUnitCost, 0),
    [detail, edited]);  // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (fn, message) => {
    setBusy(true);
    try {
      await fn();
      addToast(message);
      setDetail(null);
      setEdited({});
      onChanged();
    } catch (e) {
      addToast(e?.message || "That did not work.", "error");
    } finally {
      setBusy(false);
    }
  };

  /** Quantity changes are saved before the approval, so what is approved is what was seen. */
  const saveEdits = async () => {
    for (const [lineId, qty] of Object.entries(edited)) {
      await reqService.setLineQty(summary.id, Number(lineId), qty);
    }
  };

  const stage = summary.stage;

  /* A line can be taken off only while the requisition is still being decided.
     The same stages the server allows, so the icon is never offered for a call
     that would come back refused — and it disappears at approval, when the list
     is what was agreed to and orders are split from it. */
  const canRemoveLines =
    perm.canEdit && ["DRAFT", "SUBMITTED", "PENDING_APPROVAL"].includes(stage);
  const onlyLineLeft = (detail?.lines?.length ?? 0) <= 1;

  const removeLine = async (line) => {
    if (!(await confirm({
      title: `Remove ${line.productName}?`,
      body: "It comes off this requisition. Nothing else changes — you can add it again "
          + "from the product list while the requisition is still open.",
      confirmLabel: "Remove item", tone: "danger",
    }))) return;

    setBusy(true);
    try {
      const next = await reqService.removeLine(summary.id, line.lineId);
      setDetail(next ?? null);
      // A quantity typed against a line that no longer exists must not be saved.
      setEdited(({ [line.lineId]: _gone, ...rest }) => rest);
      addToast(`${line.productName} removed.`);
      onChanged();
    } catch (e) {
      addToast(e?.message || "Could not remove that item.", "error");
    } finally { setBusy(false); }
  };

  return (
    <Card
      eyebrow={
        <span style={{ display: "flex", alignItems: "center", gap: 7 }}>
          {summary.source === "AI" && <span className="req-ai-tag">Copilot</span>}
          {summary.reqDate} · {summary.lineCount} line{summary.lineCount === 1 ? "" : "s"}
        </span>
      }
      title={summary.requisitionNo}
      right={
        <div className="flex-gap">
          <Pill tone={STAGE_TONE[stage] || "grey"}>{stageLabel(stage)}</Pill>
          <button className="btn btn-ghost btn-sm" onClick={onToggle}>
            {open ? "Hide" : "Open"}
          </button>
        </div>
      }
    >
      {open && !detail && <div className="empty-state">Loading…</div>}

      {open && detail && (
        <>
          <Table head={[
            "Product", "Maker", "Stock", "Sells/month",
            stage === "PENDING_APPROVAL" ? "Order (editable)" : "Order",
            "Est. cost", "Priority",
            ...(canRemoveLines ? [""] : []),
          ]}>
            {detail.lines.map((l) => (
              <tr key={l.lineId}>
                <td style={{ fontWeight: 600 }}>{l.productName}</td>
                <td style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>{l.companyName || "—"}</td>
                <td className="mono" style={{ color: l.currentStock === 0 ? "var(--clay)" : "inherit" }}
                    title="Live branch stock — the Inventory page's Current Stock">
                  {Number(l.currentStock).toLocaleString()} pcs
                  {l.stockAtRaise != null && Math.round(l.stockAtRaise) !== Math.round(l.currentStock) && (
                    <div className="req-was">was {Number(l.stockAtRaise).toLocaleString()} when raised</div>
                  )}
                </td>
                <td className="mono" title="Pieces sold in the last 30 days, net of returns — the Copilot's sells/month">
                  {Math.round(l.avgMonthlySales).toLocaleString()} pcs
                  {l.salesAtRaise != null && Math.round(l.salesAtRaise) !== Math.round(l.avgMonthlySales) && (
                    <div className="req-was">was {Math.round(l.salesAtRaise).toLocaleString()} when raised</div>
                  )}
                </td>
                <td>
                  {stage === "PENDING_APPROVAL" && perm.canApprove ? (
                    <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                      <QtyInput value={qtyOf(l)} min={1} step={1} ariaLabel={`Boxes of ${l.productName}`}
                        className="qty-amber"
                        onChange={(v) => setEdited((p) => ({ ...p, [l.lineId]: Math.max(1, Number(v)) }))} />
                      <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>{l.unit.toLowerCase()}</span>
                      {l.aiRecommendedQty != null && qtyOf(l) !== l.aiRecommendedQty && (
                        <span className="req-modified">Copilot said {l.aiRecommendedQty}</span>
                      )}
                    </div>
                  ) : (
                    <span className="mono">{l.requestedQty} {l.unit.toLowerCase()}</span>
                  )}
                </td>
                <td className="mono" title={`${money(costOf(l))} per ${(l.unit || "BOX").toLowerCase()} — trade price + IGTA, the purchase-order price`}>
                  {money(qtyOf(l) * costOf(l))}
                  {Math.abs(costOf(l) - l.estimatedUnitCost) >= 0.005 && (
                    <div className="req-was">est. {money(qtyOf(l) * l.estimatedUnitCost)} when raised</div>
                  )}
                </td>
                <td>{l.priority && <Pill tone={PRIORITY_TONE[l.priority] || "grey"}>{l.priority}</Pill>}</td>
                {canRemoveLines && (
                  <td className="req-line-action">
                    {/* Shown disabled rather than hidden on the last line: a
                        control that vanishes at one row reads as a bug, and the
                        title says why it cannot be used. */}
                    <button
                      className="req-line-remove"
                      disabled={busy || onlyLineLeft}
                      onClick={() => removeLine(l)}
                      aria-label={`Remove ${l.productName} from this requisition`}
                      title={onlyLineLeft
                        ? "The only item left — cancel the requisition instead"
                        : `Remove ${l.productName}`}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                           stroke="currentColor" strokeWidth="1.9"
                           strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                        <path d="M3 6h18" />
                        <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                        <path d="M19 6l-1 14H6L5 6" />
                        <path d="M10 11v6M14 11v6" />
                      </svg>
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </Table>

          {detail.remarks && (
            <div style={{ fontSize: 12, color: "var(--ink-soft)", marginTop: 10 }}>{detail.remarks}</div>
          )}
          {detail.rejectionReason && (
            <div style={{ fontSize: 12, color: "var(--clay)", marginTop: 10 }}>
              Rejected — {detail.rejectionReason}
            </div>
          )}

          <div className="flex-gap" style={{ marginTop: 14, flexWrap: "wrap", alignItems: "center" }}>
            <div>
              <div className="ai-stat-label">Estimated</div>
              <div className="mono" style={{ fontSize: 19, fontWeight: 800, color: "var(--teal)" }}>
                {money(total)}
              </div>
              <div className="req-was">
                Trade price + IGTA per box at today's prices — the same basis as the split.
                {Math.abs(total - totalAtRaise) >= 0.005 && <> Raised at {money(totalAtRaise)}.</>}
              </div>
            </div>
            <span style={{ flex: 1 }} />

            {stage === "DRAFT" && perm.canEdit && (
              <button className="btn btn-ghost btn-sm" disabled={busy}
                onClick={() => act(() => reqService.submit(summary.id), `${summary.requisitionNo} submitted`)}>
                Submit
              </button>
            )}
            {stage === "SUBMITTED" && perm.canEdit && (
              <button className="btn btn-primary btn-sm" disabled={busy}
                onClick={() => act(() => reqService.sendForApproval(summary.id),
                                   `${summary.requisitionNo} sent for approval`)}>
                Send for approval
              </button>
            )}
            {stage === "PENDING_APPROVAL" && perm.canApprove && (
              <>
                <button className="btn btn-ghost btn-sm" disabled={busy}
                  onClick={async () => {
                    const ok = await confirm({
                      title: `Reject ${summary.requisitionNo}?`,
                      body: "It goes back as rejected and no order is raised from it.",
                      confirmLabel: "Reject", tone: "danger",
                    });
                    if (ok) act(() => reqService.reject(summary.id, "Rejected on review"),
                               `${summary.requisitionNo} rejected`);
                  }}>
                  Reject
                </button>
                <button className="btn btn-primary btn-sm" disabled={busy}
                  onClick={() => act(async () => {
                    await saveEdits();
                    await reqService.approve(summary.id);
                  }, `${summary.requisitionNo} approved`)}>
                  {Object.keys(edited).length ? "Save changes & approve" : "Approve"}
                </button>
              </>
            )}
            {stage === "APPROVED" && perm.canEdit && (
              <button className="btn btn-teal btn-sm" onClick={onSplit}>
                Split into purchase orders →
              </button>
            )}
            {stage === "CONVERTED" && (
              <>
                <span style={{ fontSize: 12, color: "var(--ink-soft)" }}>
                  Already turned into purchase orders.
                </span>
                {/* Reprinting matters more than it sounds: a supplier mislays a
                    sheet, or the buyer sends it a week after the split. Without
                    this the copies existed only in the dialog that confirmed the
                    split, and closing it lost them. */}
                <button className="btn btn-ghost btn-sm" onClick={onReprint}>
                  Print supplier copies
                </button>
              </>
            )}
          </div>
        </>
      )}
    </Card>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   The split
   ══════════════════════════════════════════════════════════════════════════ */

function SplitModal({ requisition, onClose, onDone, onNavigate, addToast }) {
  const [preview, setPreview] = useState(null);
  const [overrides, setOverrides] = useState({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  /* Which created order is being printed, by position — null when none is.
     An index rather than an id, because the buyer usually prints all of them in
     turn and the sheet needs to know where it is in that run. */
  const [printAt, setPrintAt] = useState(null);

  const load = useCallback((ov) => {
    setLoading(true);
    reqService.splitPreview(requisition.id, ov)
      .then(setPreview)
      .catch((e) => addToast(e?.message || "Could not work out the split.", "error"))
      .finally(() => setLoading(false));
  }, [requisition.id, addToast]);

  useEffect(() => { load(overrides); }, [load, overrides]);

  /** Every supplier the proposal already names — the choices for moving a line. */
  const suppliers = useMemo(() => {
    const seen = new Map();
    for (const g of preview?.groups ?? []) seen.set(g.supplierId, g.supplierName);
    return [...seen.entries()].map(([id, name]) => ({ id, name }));
  }, [preview]);

  const doSplit = async () => {
    setBusy(true);
    try {
      const res = await reqService.split(requisition.id, { overrides });
      setResult(res);
    } catch (e) {
      addToast(e?.message || "Could not create the purchase orders.", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" style={{ maxWidth: 900 }}>
        <div className="modal-head">
          <div className="card-title">
            {result ? "Purchase orders created" : `Split ${requisition.requisitionNo}`}
          </div>
        </div>

        <div className="modal-body">
          {result ? (
            <>
              <div className="req-split-note">
                Created as <b>drafts</b>. Nothing has been ordered and no stock has moved — open
                each one, check it against what the supplier confirms, and approve it when the
                goods arrive.
              </div>
              {/* Each supplier gets its own sheet, carrying only its own lines
                  and the requisition number both sides will quote afterwards.
                  One sheet per supplier is the whole point: sending the
                  requisition would show each of them what the others are
                  supplying, and at what price. */}
              <Table head={["Purchase", "Supplier", "Lines", "Total", "Status", ""]}>
                {result.orders.map((o, i) => (
                  <tr key={o.purchaseId}>
                    <td className="mono" style={{ fontWeight: 700 }}>{o.invoiceNo}</td>
                    <td>{o.supplierName}</td>
                    <td className="mono">{o.lineCount}</td>
                    <td className="mono" style={{ fontWeight: 700 }}>{money(o.total)}</td>
                    <td><Pill tone="grey">Draft</Pill></td>
                    <td className="num">
                      <button className="btn btn-ghost btn-sm"
                              title={`Print the order for ${o.supplierName}`}
                              onClick={() => setPrintAt(i)}>
                        Print
                      </button>
                    </td>
                  </tr>
                ))}
              </Table>
            </>
          ) : loading && !preview ? (
            <div className="empty-state">Working out who supplies what…</div>
          ) : preview ? (
            <>
              <div className="req-split-note">
                One purchase order per supplier. Each line shows why that supplier was chosen —
                move any of them and the totals re-add. Nothing is created until you confirm.
              </div>

              {preview.groups.map((g) => (
                <div key={g.supplierId} className="req-group">
                  <div className="req-group-head">
                    <b>{g.supplierName}</b>
                    <span className="ai-stat-sub">
                      {g.lineCount} line{g.lineCount === 1 ? "" : "s"}
                    </span>
                    <span className="mono req-group-total">{money(g.total)}</span>
                  </div>
                  <Table head={["Product", "Boxes", "Per box", "Total", "Why", "Move to"]}>
                    {g.lines.map((l) => (
                      <tr key={l.lineId}>
                        <td style={{ fontWeight: 600 }}>{l.productName}</td>
                        <td className="mono">{l.boxes}</td>
                        <td className="mono">
                          {money(l.unitCost)}
                          {l.lastPaidPerBox != null && l.lastPaidPerBox !== l.unitCost && (
                            <div className="ai-prod-sub mono">
                              last paid {money(l.lastPaidPerBox)}
                            </div>
                          )}
                        </td>
                        <td className="mono" style={{ fontWeight: 700 }}>{money(l.lineTotal)}</td>
                        <td>
                          <Pill tone={BASIS[l.basis]?.tone || "grey"}>
                            {BASIS[l.basis]?.label || l.basis}
                          </Pill>
                          {l.lastPurchasedOn && (
                            <div className="ai-prod-sub">{l.lastPurchasedOn}</div>
                          )}
                        </td>
                        <td>
                          <select
                            value={overrides[l.lineId] ?? l.supplierId ?? ""}
                            onChange={(e) => setOverrides((p) => ({
                              ...p, [l.lineId]: e.target.value ? Number(e.target.value) : undefined }))}
                            aria-label={`Supplier for ${l.productName}`}>
                            {suppliers.map((s) => (
                              <option key={s.id} value={s.id}>{s.name}</option>
                            ))}
                          </select>
                        </td>
                      </tr>
                    ))}
                  </Table>
                </div>
              ))}

              {preview.unassigned.length > 0 && (
                <div className="req-group req-group-warn">
                  <div className="req-group-head">
                    <b>No supplier found</b>
                    <span className="ai-stat-sub">
                      these cannot be ordered until one is chosen
                    </span>
                  </div>
                  <Table head={["Product", "Maker", "Boxes"]}>
                    {preview.unassigned.map((l) => (
                      <tr key={l.lineId}>
                        <td style={{ fontWeight: 600 }}>{l.productName}</td>
                        <td>{l.companyName || "—"}</td>
                        <td className="mono">{l.boxes}</td>
                      </tr>
                    ))}
                  </Table>
                </div>
              )}
            </>
          ) : null}
        </div>

        <div className="modal-foot">
          {result ? (
            <>
              <button className="btn btn-ghost" onClick={onClose}>Close</button>
              <div style={{ flex: 1 }} />
              {/* The buyer is normally sending all of them, so this starts at
                  the first and the preview steps through the rest. They stay
                  separate documents — one supplier must never see another's
                  lines or prices. */}
              <button className="btn btn-teal" onClick={() => setPrintAt(0)}
                      disabled={!result.orders?.length}>
                Print supplier copies
              </button>
              {onNavigate && (
                <button className="btn btn-primary" onClick={() => { onClose(); onNavigate("purchase"); }}>
                  Open Purchase →
                </button>
              )}
            </>
          ) : (
            <>
              <button className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
              <div style={{ flex: 1 }} />
              {preview && (
                <span className="mono req-split-total">
                  {preview.groups.length} order{preview.groups.length === 1 ? "" : "s"} · {money(preview.total)}
                </span>
              )}
              <button className="btn btn-primary" onClick={doSplit}
                disabled={busy || loading || !preview?.ready}
                title={preview && !preview.ready
                  ? "Every line needs a supplier before the orders can be created"
                  : undefined}>
                {busy ? "Creating…" : "Create purchase orders"}
              </button>
            </>
          )}
        </div>
      </div>

      {/* Over the dialog, not inside it: the sheet has to be the only thing on
          the page when the browser renders it. */}
      {printAt != null && result?.orders?.[printAt] && (
        <PurchaseOrderPrint
          purchaseId={result.orders[printAt].purchaseId}
          sequence={{
            position: printAt + 1,
            total: result.orders.length,
            onNext: () => setPrintAt((i) => i + 1),
          }}
          onClose={() => setPrintAt(null)}
        />
      )}
    </div>
  );
}
