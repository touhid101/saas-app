/**
 * Delivery Management — real orders, scoped to whoever is looking.
 *
 * ── What this replaces ────────────────────────────────────────────────────
 *
 * The previous version made no API calls. Every tab read `DELIVERY_ORDERS` and
 * `DELIVERY_MEN` from mockData.js — and invented a delivery entity with its own
 * ids that does not exist in the schema. There is no delivery table: a delivery
 * *is* a sale, carrying a stage and an assigned employee. The page was also
 * entirely read-only, so nothing here could move an order along.
 *
 * ── Who sees what ─────────────────────────────────────────────────────────
 *
 * A driver sees their own round; a manager sees the branch. That narrowing is
 * done by the server on every endpoint below — this page does not filter by
 * driver, and could not be made to leak by editing it. What the role changes
 * here is only what gets *offered*: a driver is not shown an Assign control that
 * would always be refused, and the totals are labelled as theirs so they cannot
 * be mistaken for the branch's.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill, Tabs, Modal, Field } from "../components/UI.jsx";
// Two decimals — these are invoice totals and amounts owed. `money` rounds to
// whole taka, which reports a ৳1.39 delivery as ৳1.
import { moneyFull as money, num } from "../utils/format.js";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useToast } from "../hooks/useToast.js";
import { downloadCSV } from "../utils/csv.js";
import svc, { STAGES } from "../services/salesActivityService.js";
import ecomDelivery from "../services/ecomDeliveryService.js";

/* The stages that mean something to a delivery operation. A sale that has not
   been confirmed yet is not a delivery, and never appears on this page. */
const DELIVERY_STAGES = ["Confirmed", "Delivery Assigned", "On The Way", "Delivered", "Collected"];

const STAGE_TONE = {
  Confirmed: "amber", "Delivery Assigned": "amber",
  "On The Way": "teal", Delivered: "sage", Collected: "sage",
};
const STAGE_DOT = {
  Confirmed: "#B8752B", "Delivery Assigned": "#B8752B",
  "On The Way": "#6B5EA8", Delivered: "#4C7A5E", Collected: "#2E6A62",
};

const Ic = ({ path, size = 17 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);
const VAN = ["M1 6h14v11H1z", "M15 9h4l3 3v5h-7z",
             "M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"];

/* ═══════════════════════════════════════════════════════════════════════════
   PAGE
   ═══════════════════════════════════════════════════════════════════════════ */

export default function Delivery() {
  const [tab, setTab] = useState("Dashboard");
  const perm = usePermission("delivery");
  const { currentUser } = useApp();
  const isDriver = svc.isDriverRole(currentUser?.roleCode);

  // A driver has no use for a roster of other drivers, and the performance
  // endpoint gives them only their own row — so the tab is named for what they
  // will actually see rather than promising a comparison they do not get.
  const tabs = isDriver
    ? ["Dashboard", "Tracking", "My Performance"]
    : ["Dashboard", "Tracking", "Delivery Men", "Performance"];

  useEffect(() => {
    if (!tabs.includes(tab)) setTab("Dashboard");
  }, [tabs, tab]);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Delivery Management</h1>
          <div className="page-sub">
            {isDriver
              ? "Your round — track what you are carrying and record what you collect."
              : "Track delivery orders, manage delivery men, and monitor performance."}
          </div>
        </div>
        {isDriver && <Pill tone="teal">Your deliveries only</Pill>}
      </div>

      <Tabs tabs={tabs} active={tab} onChange={setTab} />

      {tab === "Dashboard"                        && <DeliveryDashboard isDriver={isDriver} />}
      {tab === "Tracking"                         && <DeliveryTracking perm={perm} isDriver={isDriver} />}
      {tab === "Delivery Men"                     && <DeliveryMenList />}
      {(tab === "Performance" || tab === "My Performance") && <DeliveryPerformance isDriver={isDriver} />}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   DASHBOARD
   ═══════════════════════════════════════════════════════════════════════════ */

function DeliveryDashboard({ isDriver }) {
  const addToast = useToast();
  const [summary, setSummary] = useState(null);
  const [recent, setRecent] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      svc.getDeliverySummary(),
      // The most recent dispatched orders. Sorted newest-first by the server, so
      // the first page is the latest activity without fetching everything.
      svc.listActivity({ ...svc.emptyFilter(), stage: "All" }, 0, 15),
    ])
      .then(([s, list]) => {
        if (cancelled) return;
        setSummary(s);
        setRecent(list.items.filter((o) => DELIVERY_STAGES.includes(o.stage)));
        setError(null);
      })
      .catch((e) => !cancelled && setError(e?.message || "Could not load the delivery dashboard."))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, []);

  if (loading)  return <div className="empty-state">Loading…</div>;
  if (error)    return <div className="empty-state" role="alert">{error}</div>;
  if (!summary) return null;

  const s = summary;
  const kpis = [
    ...(isDriver ? [] : [{ label: "Awaiting a driver", value: num(s.awaitingAssignment), color: "#B8752B" }]),
    { label: "Assigned",     value: num(s.byStage?.["Delivery Assigned"] ?? 0), color: "#B8752B" },
    { label: "On The Way",   value: num(s.byStage?.["On The Way"] ?? 0),        color: "#6B5EA8" },
    { label: "Delivered",    value: num(s.byStage?.Delivered ?? 0),             color: "#4C7A5E" },
    { label: "Collected",    value: num(s.byStage?.Collected ?? 0),             color: "#2E6A62" },
    { label: "Billed",       value: money(s.billed),                            color: "#2E6A62" },
    { label: "Collected ৳",  value: money(s.collected),                         color: "#4C7A5E" },
    { label: "Outstanding",  value: money(s.outstanding),                       color: "#A8452E" },
  ];

  return (
    <div>
      {/* Whose numbers these are. A driver reading a bare total has no way to
          tell it apart from the branch's. */}
      {s.ownRoundOnly && (
        <div style={{ marginBottom: 12, fontSize: 12.5, color: "var(--ink-soft)" }}>
          These figures cover the orders assigned to you.
        </div>
      )}

      <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(auto-fill, minmax(180px,1fr))", marginBottom: 20 }}>
        {kpis.map((k) => (
          <div key={k.label} className="kpi-card">
            <div className="kpi-top">
              <div className="kpi-icon" style={{ background: k.color + "22", color: k.color }}>
                <Ic path={VAN} />
              </div>
            </div>
            <div className="kpi-value" style={{ fontSize: 20 }}>{k.value}</div>
            <div className="kpi-label">{k.label}</div>
          </div>
        ))}
      </div>

      <Card eyebrow="Recent" title={isDriver ? "Your latest deliveries" : "Latest delivery activity"}>
        {recent.length === 0 ? (
          <div className="empty-state">
            {isDriver ? "Nothing has been assigned to you yet." : "No orders have been dispatched yet."}
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr><th>Invoice</th><th>Customer</th><th>Delivery Man</th>
                    <th>Amount</th><th>Collected</th><th>Due</th><th>Stage</th></tr>
              </thead>
              <tbody>
                {recent.map((o) => (
                  <tr key={o.id}>
                    <td className="mono" style={{ fontWeight: 700 }}>{o.invoiceNo}</td>
                    <td>{o.customerName}</td>
                    <td>{o.deliveryManName || <span style={{ color: "var(--ink-soft)" }}>—</span>}</td>
                    <td className="mono">{money(o.grandTotal)}</td>
                    <td className="mono" style={{ color: o.paidAmount > 0 ? "var(--sage)" : "var(--ink-soft)" }}>
                      {money(o.paidAmount)}
                    </td>
                    <td className="mono" style={{ color: o.due > 0 ? "var(--clay)" : "var(--sage)" }}>
                      {money(o.due)}
                    </td>
                    <td><Pill tone={STAGE_TONE[o.stage] || "grey"}>{o.stage}</Pill></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   TRACKING
   ═══════════════════════════════════════════════════════════════════════════ */

function DeliveryTracking({ perm = {}, isDriver }) {
  const addToast = useToast();
  const [stage, setStage] = useState("All");
  const [q, setQ] = useState("");
  const [rows, setRows] = useState([]);
  const [meta, setMeta] = useState({ total: 0, totalPages: 0 });
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [collectFor, setCollectFor] = useState(null);

  const SIZE = 25;

  /* [Phase 10] The run whose position is being shared, and the order whose
     handover is being written down. Sharing is per order and per session: it
     stops when the delivery does, and nothing is remembered afterwards. */
  const [sharing, setSharing] = useState(null);      // order number
  const [shareNote, setShareNote] = useState(null);
  const [proofFor, setProofFor] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return svc.listActivity({ ...svc.emptyFilter(), stage, q }, page, SIZE)
      .then((r) => {
        // Sales that are not yet a delivery have no place on this page. Filtered
        // here rather than in the query because "All" on this screen means "all
        // delivery stages", not every sale ever made.
        setRows(stage === "All" ? r.items.filter((o) => DELIVERY_STAGES.includes(o.stage)) : r.items);
        setMeta({ total: r.total, totalPages: r.totalPages });
      })
      .catch((e) => addToast(e?.message || "Could not load deliveries.", "error"))
      .finally(() => setLoading(false));
  }, [stage, q, page]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const id = setTimeout(load, q ? 300 : 0);
    return () => clearTimeout(id);
  }, [load, q]);

  /**
   * Moves an order along.
   *
   * The server decides whether this caller may make this transition; the button
   * is only offered where it would succeed. Reloading afterwards rather than
   * patching the row keeps the stage shown identical to the stage stored.
   */
  const advance = async (order, to) => {
    setBusy(true);
    try {
      await svc.advanceStage(order.id, to);
      addToast(`${order.invoiceNo} → ${to}`);
      await load();
    } catch (e) {
      addToast(e?.message || "That did not work.", "error");
    } finally {
      setBusy(false);
    }
  };

  /**
   * Shares where this delivery is, while it is out.
   *
   * <p>The browser is asked for a position every 20 seconds and the server is
   * told; it refuses anything it cannot honestly show — a reading good to five
   * kilometres, an order not on the road, somebody else's round. Switching the
   * page or the toggle off stops it: nothing follows a driver between jobs.
   */
  useEffect(() => {
    if (!sharing) return undefined;
    let stopped = false;
    const tick = async () => {
      try {
        const at = await ecomDelivery.currentPosition();
        const r = await ecomDelivery.reportPosition(sharing, at);
        if (!stopped) setShareNote(r?.kmFromCustomer != null
          ? `${r.kmFromCustomer} km from the customer`
          : "position sent");
      } catch (e) {
        if (stopped) return;
        setShareNote(e?.message || "Could not send the position.");
        // A refusal that will not change on the next tick — not on the road,
        // not this driver's round — is a reason to stop asking.
        if (/not out for delivery|not on your round/i.test(e?.message || "")) { setSharing(null); }
      }
    };
    tick();
    const id = setInterval(tick, 20000);
    return () => { stopped = true; clearInterval(id); };
  }, [sharing]);

  /**
   * Takes money against a delivery.
   *
   * The server settles the invoice when nothing meaningful is left owing and
   * leaves it open otherwise, so a part payment keeps the order on the round.
   */
  const saveProof = async (order, { receivedByName, note, photo }) => {
    setBusy(true);
    try {
      let at = {};
      try { at = await ecomDelivery.currentPosition({ timeout: 6000 }); } catch { /* the door may be indoors */ }
      await ecomDelivery.recordProof(order.ecomOrderNo, {
        receivedByName, note, photo,
        latitude: at.latitude, longitude: at.longitude,
      });
      addToast(`Handover recorded for ${order.ecomOrderNo}`);
      setProofFor(null);
      // Marking it delivered is the existing stage change; the customer's order
      // follows the invoice on the server side.
      if (order.stage === "On The Way") await svc.advanceStage(order.id, "Delivered");
      if (sharing === order.ecomOrderNo) setSharing(null);
      await load();
    } catch (e) {
      addToast(e?.message || "That did not work.", "error");
    } finally {
      setBusy(false);
    }
  };

  const doCollect = async (saleId, amount, payType) => {
    setBusy(true);
    try {
      await svc.collect(saleId, amount, payType);
      addToast(`Collected ${money(amount)}`);
      setCollectFor(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not record the collection.", "error");
    } finally {
      setBusy(false);
    }
  };

  const exportCSV = () => {
    downloadCSV("delivery-tracking.csv",
      rows.map((o) => ({
        Invoice: o.invoiceNo, Customer: o.customerName, Address: o.deliveryAddress || "",
        "Delivery Man": o.deliveryManName || "", Amount: o.grandTotal,
        Charge: o.deliveryCharge, Collected: o.paidAmount, Due: o.due, Stage: o.stage,
      })),
      ["Invoice", "Customer", "Address", "Delivery Man", "Amount", "Charge", "Collected", "Due", "Stage"]);
  };

  const sel = { padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--paper)", color: "var(--ink)" };

  return (
    <Card eyebrow="Live tracking" title={isDriver ? "Your deliveries" : "All delivery orders"}
      right={
        <div className="flex-gap">
          <select value={stage} onChange={(e) => { setStage(e.target.value); setPage(0); }} style={sel}>
            <option value="All">All stages</option>
            {DELIVERY_STAGES.map((s) => <option key={s}>{s}</option>)}
          </select>
          <input placeholder="Search invoice, customer…" value={q}
            onChange={(e) => { setQ(e.target.value); setPage(0); }}
            style={{ ...sel, width: 210 }} />
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" onClick={exportCSV} disabled={!rows.length}>
              Export page
            </button>
          )}
        </div>
      }
    >
      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="empty-state">
          {isDriver ? "You have no deliveries matching this filter." : "No deliveries match the filter."}
        </div>
      ) : (
        <>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Invoice</th><th>Customer</th><th>Address</th><th>Delivery Man</th>
                  <th>Amount</th><th>Charge</th><th>Due</th><th>Stage</th>
                  {perm.canEdit && <th style={{ minWidth: 120 }}>Action</th>}
                </tr>
              </thead>
              <tbody>
                {rows.map((o) => {
                  const next = svc.nextStage(o.stage);
                  // Offered only where the server would accept it: a driver may
                  // report On The Way and Delivered, nothing else.
                  const allowedForRole = next && (!isDriver || svc.DRIVER_STAGES.includes(next));
                  // Collected is reached by taking the money, never by declaring
                  // it. Offering it as a plain stage change would settle an
                  // invoice with a balance still outstanding and no payment
                  // recorded against it.
                  const needsMoney = next === "Collected" && o.due > 0;
                  const mayAdvance = allowedForRole && !needsMoney;
                  return (
                    <tr key={o.id}>
                      <td className="mono" style={{ fontWeight: 700 }}>
                        {o.invoiceNo}
                        {/* An online order has a number of its own — the one the
                            customer will read out if they ring. [Phase 9] */}
                        {o.ecomOrderNo && (
                          <div style={{ fontSize: 10.5, fontWeight: 600, color: "var(--teal)", marginTop: 2 }}>{o.ecomOrderNo}</div>
                        )}
                      </td>
                      <td>
                        {o.customerName}
                        {o.deliveryRecipient && o.deliveryRecipient !== o.customerName && (
                          <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>for {o.deliveryRecipient}</div>
                        )}
                        {/* Tapping a number on a phone rings it; on a desktop it
                            is still the number, which is what somebody about to
                            dial needs to see. */}
                        {o.deliveryPhone && (
                          <a href={`tel:${o.deliveryPhone}`} className="mono"
                             style={{ fontSize: 11, color: "var(--teal)", textDecoration: "none" }}>
                            {o.deliveryPhone}
                          </a>
                        )}
                      </td>
                      <td style={{ maxWidth: 200, fontSize: 12 }}>
                        <div style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                          {o.deliveryAddress || <span style={{ color: "var(--ink-soft)" }}>—</span>}
                        </div>
                        {/* The pin the customer dropped, which is the difference
                            between "House 4, Road 7" and finding House 4. */}
                        {o.deliveryLatitude != null && o.deliveryLongitude != null && (
                          <a target="_blank" rel="noreferrer"
                             href={`https://www.google.com/maps/search/?api=1&query=${o.deliveryLatitude},${o.deliveryLongitude}`}
                             style={{ fontSize: 11, fontWeight: 600, color: "var(--teal)", textDecoration: "none" }}>
                            Open the map pin
                          </a>
                        )}
                      </td>
                      <td>{o.deliveryManName || <span style={{ color: "var(--ink-soft)" }}>Not assigned</span>}</td>
                      <td className="mono">{money(o.grandTotal)}</td>
                      <td className="mono">{money(o.deliveryCharge)}</td>
                      <td className="mono" style={{ color: o.due > 0 ? "var(--clay)" : "var(--sage)", fontWeight: o.due > 0 ? 700 : 400 }}>
                        {money(o.due)}
                      </td>
                      <td>
                        <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
                          <span className="del-status-dot" style={{ background: STAGE_DOT[o.stage] || "var(--line)" }} />
                          <Pill tone={STAGE_TONE[o.stage] || "grey"}>{o.stage}</Pill>
                        </span>
                      </td>
                      {perm.canEdit && (
                        <td>
                          <div style={{ display: "flex", gap: 5, flexWrap: "wrap" }}>
                            {/* An online order arriving is a handover worth
                                recording, so it opens the dialog rather than
                                moving the stage on its own. [Phase 10] */}
                            {mayAdvance && !(o.ecomOrderNo && next === "Delivered") && (
                              <button className="btn btn-teal btn-sm" disabled={busy}
                                onClick={() => advance(o, next)} style={{ fontSize: 11 }}>
                                {next} →
                              </button>
                            )}
                            {mayAdvance && o.ecomOrderNo && next === "Delivered" && (
                              <button className="btn btn-teal btn-sm" disabled={busy}
                                onClick={() => setProofFor(o)} style={{ fontSize: 11 }}>
                                Delivered →
                              </button>
                            )}
                            {/* While a parcel is on the road, the driver's own
                                device can say where it is. Off by default, one
                                order at a time, and it stops when the delivery
                                does. */}
                            {o.ecomOrderNo && o.stage === "On The Way" && (
                              <button className="btn btn-sm" disabled={busy}
                                onClick={() => { setShareNote(null); setSharing(sharing === o.ecomOrderNo ? null : o.ecomOrderNo); }}
                                title={sharing === o.ecomOrderNo ? (shareNote || "Sharing this delivery's position") : "Send this delivery's position to the customer"}
                                style={{ fontSize: 11,
                                         background: sharing === o.ecomOrderNo ? "var(--teal)" : "transparent",
                                         color: sharing === o.ecomOrderNo ? "#fff" : "var(--teal)",
                                         border: "1px solid var(--teal)" }}>
                                {sharing === o.ecomOrderNo ? "Sharing…" : "Share location"}
                              </button>
                            )}
                            {o.due > 0 && (
                              <button className="btn btn-sm" disabled={busy} onClick={() => setCollectFor(o)}
                                style={{ fontSize: 11, background: "var(--clay-soft)", color: "var(--clay)", border: "1px solid var(--clay)" }}>
                                Collect
                              </button>
                            )}
                            {!mayAdvance && o.due <= 0 && !o.ecomOrderNo && (
                              <span style={{ color: "var(--ink-soft)", fontSize: 11 }}>—</span>
                            )}
                          </div>
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {sharing && (
            <div className="del-note" style={{ marginTop: 10, fontSize: 12.5, color: "var(--ink-soft)" }}>
              Sharing the position of <b className="mono">{sharing}</b> every 20 seconds
              {shareNote ? ` — ${shareNote}` : "…"}. It stops when you switch it off, leave this page,
              or the delivery is marked done.
            </div>
          )}

          {meta.totalPages > 1 && (
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", paddingTop: 12, flexWrap: "wrap", gap: 8 }}>
              <span style={{ fontSize: 13, color: "var(--ink-soft)" }}>
                Showing {rows.length} of {meta.total}
              </span>
              <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                <button className="btn btn-ghost btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← Prev</button>
                <span style={{ fontSize: 13 }}>Page {page + 1} / {meta.totalPages}</span>
                <button className="btn btn-ghost btn-sm" disabled={page + 1 >= meta.totalPages} onClick={() => setPage((p) => p + 1)}>Next →</button>
              </div>
            </div>
          )}
        </>
      )}
      {proofFor && (
        <ProofModal order={proofFor} busy={busy}
                    onSave={(fields) => saveProof(proofFor, fields)}
                    onClose={() => setProofFor(null)} />
      )}
      {collectFor && (
        <CollectModal order={collectFor} busy={busy}
          onCollect={doCollect} onClose={() => setCollectFor(null)} />
      )}
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   COLLECT
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Records money taken at the door.
 *
 * The outstanding figure is what the customer is actually asked for, so the
 * amount defaults to it. Paying less is allowed and leaves the order open —
 * there is still something to go back for — which the note below spells out
 * rather than letting the operator discover it from the stage not changing.
 */
/* ── What happened at the door ─────────────────────────────────────────────
   A photograph, a name, or a note — one of the three, because "delivered" with
   nothing behind it is exactly the claim that gets disputed later. The dialog
   asks for the position too, quietly, and does not mind if the door is indoors
   and the device cannot say. */
function ProofModal({ order, busy, onSave, onClose }) {
  const [receivedByName, setName] = useState(order.deliveryRecipient || order.customerName || "");
  const [note, setNote] = useState("");
  const [photo, setPhoto] = useState(null);
  const enough = receivedByName.trim() || note.trim() || photo;

  return (
    <Modal title={`Handed over — ${order.ecomOrderNo || order.invoiceNo}`} onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Not yet</button>
        <button className="btn btn-primary" disabled={!enough || busy} onClick={() => onSave({ receivedByName, note, photo })}>
          {busy ? "Recording…" : "Record and mark delivered"}
        </button>
      </>}>
      <div style={{ fontSize: 13, color: "var(--ink-soft)", marginBottom: 12, lineHeight: 1.6 }}>
        {order.deliveryAddress || "this address"} · {money(order.grandTotal)}
        {order.due > 0 && <> · <b style={{ color: "var(--clay)" }}>{money(order.due)} still to collect</b></>}
      </div>
      <div style={{ display: "grid", gap: 10 }}>
        <label style={{ fontSize: 12, color: "var(--ink-soft)" }}>
          Who took it
          <input value={receivedByName} onChange={(e) => setName(e.target.value)}
                 placeholder="Name at the door"
                 style={{ width: "100%", padding: "9px 12px", borderRadius: 8, border: "1px solid var(--line)", fontSize: 13, background: "var(--paper)", color: "var(--ink)", marginTop: 4 }} />
        </label>
        <label style={{ fontSize: 12, color: "var(--ink-soft)" }}>
          Photograph (optional)
          <input type="file" accept="image/*" capture="environment"
                 onChange={(e) => setPhoto(e.target.files?.[0] || null)}
                 style={{ display: "block", marginTop: 4, fontSize: 12.5 }} />
        </label>
        <label style={{ fontSize: 12, color: "var(--ink-soft)" }}>
          Note (optional)
          <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={300}
                 placeholder="Left with the guard, customer not in…"
                 style={{ width: "100%", padding: "9px 12px", borderRadius: 8, border: "1px solid var(--line)", fontSize: 13, background: "var(--paper)", color: "var(--ink)", marginTop: 4 }} />
        </label>
      </div>
      {!enough && <p style={{ fontSize: 12, color: "var(--ink-soft)", marginTop: 10 }}>
        One of the three is enough — a name, a photograph, or a note.
      </p>}
    </Modal>
  );
}

function CollectModal({ order, busy, onCollect, onClose }) {
  const outstanding = order.due || 0;
  const [amt, setAmt] = useState(String(outstanding || ""));
  const [payType, setPayType] = useState(order.paymentType || "CASH");
  const entered = Number(amt) || 0;
  const over = entered > outstanding;
  const remaining = Math.round((outstanding - entered) * 100) / 100;

  return (
    <Modal title={`Collect — ${order.invoiceNo}`} onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={entered <= 0 || over || busy}
          onClick={() => onCollect(order.id, entered, payType)}>
          {busy ? "Recording…" : "Confirm"}
        </button>
      </>}>
      <div style={{ background: "var(--paper)", borderRadius: 10, padding: "14px 16px", marginBottom: 16 }}>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, fontSize: 13 }}>
          <div><span style={{ color: "var(--ink-soft)" }}>Customer</span>
            <div style={{ fontWeight: 700, marginTop: 2 }}>{order.customerName}</div></div>
          <div><span style={{ color: "var(--ink-soft)" }}>Invoice total</span>
            <div style={{ fontWeight: 700, fontFamily: "monospace", marginTop: 2 }}>{money(order.grandTotal)}</div></div>
        </div>
        <div style={{ marginTop: 10, padding: "10px 12px", background: "var(--clay-soft)", borderRadius: 8, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ fontWeight: 700, color: "var(--clay)" }}>Outstanding</span>
          <span style={{ fontWeight: 800, fontFamily: "monospace", color: "var(--clay)", fontSize: 16 }}>{money(outstanding)}</span>
        </div>
      </div>

      <Field label="Amount collected (৳)" error={over ? "Cannot exceed the outstanding amount" : undefined}>
        <input type="number" min="0" step="0.01" max={outstanding} value={amt}
          onChange={(e) => setAmt(e.target.value)}
          style={{ fontSize: 18, fontWeight: 700, fontFamily: "monospace" }} />
      </Field>
      <Field label="Payment method">
        <select value={payType} onChange={(e) => setPayType(e.target.value)}>
          {["CASH", "CARD", "BKASH", "NAGAD", "BANK"].map((p) => <option key={p}>{p}</option>)}
        </select>
      </Field>

      {entered > 0 && !over && (
        <div style={{ fontSize: 13, color: "var(--ink-soft)", marginTop: -6 }}>
          Remaining after this:{" "}
          <strong style={{ color: remaining > 0 ? "var(--clay)" : "var(--sage)" }}>{money(remaining)}</strong>
          {remaining > 0 && " — the order stays on your round."}
        </div>
      )}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   DELIVERY MEN
   ═══════════════════════════════════════════════════════════════════════════ */

function DeliveryMenList() {
  const [men, setMen] = useState([]);
  const [perf, setPerf] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([svc.listDeliveryMen(), svc.getDeliveryPerformance()])
      .then(([m, p]) => { if (!cancelled) { setMen(m); setPerf(p || []); setError(null); } })
      .catch((e) => !cancelled && setError(e?.message || "Could not load the delivery team."))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, []);

  // Workload comes from the performance aggregate rather than a second count:
  // one source, so the roster and the performance tab cannot disagree.
  const byId = useMemo(
    () => Object.fromEntries(perf.map((r) => [String(r.deliveryManId), r])),
    [perf]);

  if (loading) return <div className="empty-state">Loading…</div>;
  if (error)   return <div className="empty-state" role="alert">{error}</div>;

  return (
    <Card eyebrow="Staff" title="Delivery team">
      {men.length === 0 ? (
        <div className="empty-state">
          No delivery men are set up. Add an employee of type <strong>Delivery Man</strong> in
          Master Data › Employees — they also need a login there to see their own round.
        </div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Name</th><th>Vehicle</th><th>Phone</th>
                  <th>On the road</th><th>Completed</th><th>Outstanding</th></tr>
            </thead>
            <tbody>
              {men.map((dm) => {
                const p = byId[String(dm.id)];
                return (
                  <tr key={dm.id}>
                    <td style={{ fontWeight: 700 }}>{dm.name}</td>
                    <td className="mono">{dm.vehicleNo || "—"}</td>
                    <td className="mono" style={{ fontSize: 12 }}>{dm.phone || "—"}</td>
                    <td className="mono">{p ? p.inProgress : 0}</td>
                    <td className="mono">{p ? p.delivered : 0}</td>
                    <td className="mono" style={{ color: Number(p?.outstanding) > 0 ? "var(--clay)" : "var(--ink-soft)", fontWeight: Number(p?.outstanding) > 0 ? 700 : 400 }}>
                      {money(p?.outstanding ?? 0)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PERFORMANCE
   ═══════════════════════════════════════════════════════════════════════════ */

function DeliveryPerformance({ isDriver }) {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    svc.getDeliveryPerformance()
      .then((r) => { if (!cancelled) { setRows(r || []); setError(null); } })
      .catch((e) => !cancelled && setError(e?.message || "Could not load performance."))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, []);

  if (loading) return <div className="empty-state">Loading…</div>;
  if (error)   return <div className="empty-state" role="alert">{error}</div>;

  const pct = (v) => (v == null ? "—" : `${Number(v).toFixed(1)}%`);
  const bar = (v) => (v == null ? 0 : Math.min(100, Number(v)));
  const tone = (v, good, ok) =>
    v == null ? "var(--line)" : v >= good ? "var(--sage)" : v >= ok ? "var(--amber)" : "var(--clay)";

  return (
    <Card eyebrow="Analytics" title={isDriver ? "Your delivery record" : "Delivery man performance"}>
      {rows.length === 0 ? (
        <div className="empty-state">
          {isDriver ? "Nothing has been assigned to you yet." : "No orders have been dispatched yet."}
        </div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Delivery Man</th><th>Assigned</th><th>Delivered</th><th>Completion</th>
                <th>Billed</th><th>Collected</th><th>Outstanding</th><th>Collection</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.deliveryManId}>
                  <td style={{ fontWeight: 700 }}>
                    {r.deliveryManName}
                    {r.active === false && (
                      <span style={{ marginLeft: 6, fontSize: 10, color: "var(--ink-soft)" }}>(inactive)</span>
                    )}
                    {r.vehicleNo && (
                      <div style={{ fontSize: 10.5, color: "var(--ink-soft)", fontFamily: "monospace" }}>{r.vehicleNo}</div>
                    )}
                  </td>
                  <td className="mono">{r.assigned}</td>
                  <td className="mono">{r.delivered}</td>
                  <td>
                    <div className="flex-gap">
                      <div className="prog-bar" style={{ width: 80 }}>
                        <div className="prog-fill" style={{ width: `${bar(r.completionRate)}%`, background: tone(r.completionRate, 80, 50) }} />
                      </div>
                      <span className="mono" style={{ fontSize: 12 }}>{pct(r.completionRate)}</span>
                    </div>
                  </td>
                  <td className="mono">{money(r.billed)}</td>
                  <td className="mono" style={{ color: "var(--sage)" }}>{money(r.collected)}</td>
                  <td className="mono" style={{ color: Number(r.outstanding) > 0 ? "var(--clay)" : "var(--ink-soft)", fontWeight: Number(r.outstanding) > 0 ? 700 : 400 }}>
                    {money(r.outstanding)}
                  </td>
                  {/* The figure that matters for cash on delivery: goods handed
                      over without the money coming back is a loss wearing the
                      costume of a completed run. */}
                  <td className="mono" style={{ fontWeight: 700, color: tone(r.collectionRate, 95, 75) }}>
                    {pct(r.collectionRate)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
