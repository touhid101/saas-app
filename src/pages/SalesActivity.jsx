/**
 * Sales Activity — real orders, from submission to collection.
 *
 * ── What this replaces ────────────────────────────────────────────────────
 *
 * The previous version made no API calls at all. It seeded 150 invented orders
 * from mockData.js and six hard-coded delivery men, then mutated them in local
 * state: assigning a driver, advancing a stage or recording a collection
 * changed nothing on the server and was lost on navigation.
 *
 * ── How it works now ──────────────────────────────────────────────────────
 *
 *   List        GET  /api/sales-activity — one page at a time, every filter
 *               applied in the query so a search finds matches beyond page one.
 *   Chips       GET  /api/sales-activity/stage-counts — counted across the
 *               branch, so they do not shift as you page.
 *   Drivers     GET  /api/employees/delivery-men — exactly those the server
 *               will accept; assigning anyone else is refused with a reason.
 *   Actions     assign / bulk-assign / advance / collect, each re-reading the
 *               affected row from the response rather than guessing at it.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
// Two decimals: these are invoice totals and amounts owed, and `money`
// rounds to whole taka — a 1.39 due showed as 1, and a 0.40 balance as 0.
import { moneyFull as money } from "../utils/format.js";
import { Card, Pill, Field, Modal } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { downloadCSV } from "../utils/csv.js";
import { usePermission } from "../lib/usePermission.js";
import { useApp } from "../lib/AppContext.jsx";
import svc, { STAGES, ASSIGNABLE } from "../services/salesActivityService.js";

const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

const STAGE_TONE = {
  Submitted: "amber", Confirmed: "teal",
  "Delivery Assigned": "sage", "On The Way": "teal",
  Delivered: "sage", Collected: "sage",
  // Not a stage: where a document goes when it stops being one.
  Cancelled: "clay",
};
const PAY_TONE = { Paid: "sage", Partial: "amber", Unpaid: "clay" };
const STAGE_COLOR = {
  Submitted: "#B8752B", Confirmed: "#2E6A62",
  "Delivery Assigned": "#4C7A5E", "On The Way": "#6B5EA8",
  Delivered: "#2E6A62", Collected: "#228B22",
  Cancelled: "#A4462F",
};
const NEXT_LABEL = {
  Submitted: "Confirm", Confirmed: "Assign DM",
  "Delivery Assigned": "Dispatch", "On The Way": "Delivered",
  Delivered: "Collect",
};

/* ── Stage progress bar ── */
function StageProgress({ stage }) {
  const idx = STAGES.indexOf(stage);
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 3, width: "100%" }}>
      {STAGES.map((s, i) => (
        <div key={s} title={s} style={{
          flex: 1, height: 6, borderRadius: 99,
          background: i <= idx ? STAGE_COLOR[stage] : "var(--line)",
          transition: "background .3s",
        }} />
      ))}
    </div>
  );
}

/* ── Delivery man tile ── */
function DmTile({ dm, selected, onPick, compact = false }) {
  return (
    <button type="button" onClick={() => onPick(dm.id)}
      style={{
        display: "flex", alignItems: "center", gap: compact ? 8 : 12,
        padding: compact ? "8px 12px" : "12px 14px", borderRadius: compact ? 8 : 10,
        border: `1.5px solid ${selected ? "var(--amber)" : "var(--line)"}`,
        background: selected ? "var(--amber-soft)" : "var(--paper)",
        cursor: "pointer", transition: "all .12s", textAlign: "left",
        fontSize: compact ? 12.5 : 13,
      }}>
      <div style={{
        width: compact ? 28 : 36, height: compact ? 28 : 36, borderRadius: "50%",
        background: selected ? "var(--amber)" : "var(--teal)", color: "#fff",
        display: "flex", alignItems: "center", justifyContent: "center",
        fontWeight: 700, fontSize: compact ? 11 : 12, fontFamily: "monospace", flexShrink: 0,
      }}>{dm.initials}</div>
      <div>
        <div style={{ fontWeight: 700 }}>{dm.name}</div>
        <div style={{ fontSize: compact ? 10.5 : 12, color: "var(--ink-soft)", fontFamily: "monospace" }}>
          {[dm.vehicleNo, !compact && dm.phone].filter(Boolean).join(" · ")}
        </div>
      </div>
      {selected && <span style={{ fontSize: 10, background: "var(--amber)", color: "#fff", borderRadius: 4, padding: "1px 6px", marginLeft: "auto" }}>Current</span>}
    </button>
  );
}

/* ── Assign modal ── */
function AssignDMModal({ order, deliveryMen, busy, onAssign, onClose }) {
  const [dmId, setDmId] = useState(order.deliveryManId || deliveryMen[0]?.id || "");
  return (
    <Modal title={`Assign Delivery Man — ${order.invoiceNo}`} onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!dmId || busy}
          onClick={() => onAssign(order.id, dmId)}>
          {busy ? "Assigning…" : "Assign & Dispatch"}
        </button>
      </>}>
      {deliveryMen.length === 0 ? (
        <div className="empty-state">
          No delivery men are set up in this branch. Add an employee of type
          <strong> Delivery Man</strong> in Master Data › Employees.
        </div>
      ) : (
        <>
          <div style={{ fontSize: 13, color: "var(--ink-soft)", marginBottom: 12 }}>
            This advances the order to <strong>Delivery Assigned</strong>.
          </div>
          <div style={{ display: "grid", gap: 10 }}>
            {deliveryMen.map((d) => (
              <DmTile key={d.id} dm={d} selected={String(dmId) === String(d.id)} onPick={setDmId} />
            ))}
          </div>
        </>
      )}
    </Modal>
  );
}

/* ── Cancel modal ── */
function CancelModal({ order, busy, onCancel, onClose }) {
  const [reason, setReason] = useState("");
  return (
    <Modal title={`Cancel ${order.invoiceNo}`} onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Keep the order</button>
        <button className="btn btn-primary" disabled={busy}
          style={{ background: "var(--clay)", borderColor: "var(--clay)" }}
          onClick={() => onCancel(order, reason)}>
          {busy ? "Cancelling…" : "Cancel the order"}
        </button>
      </>}>
      <div style={{ fontSize: 13, color: "var(--ink-soft)", marginBottom: 12, lineHeight: 1.6 }}>
        The {money(order.grandTotal)} invoice for <strong>{order.customerName}</strong> is called off:
        every piece on it goes back to the batch it came from, and the journals it
        raised are reversed. The document stays, marked cancelled, with this reason on it.
        {order.ecomOrderNo && <> The customer sees <strong>{order.ecomOrderNo}</strong> cancelled too.</>}
      </div>
      <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>Reason</div>
      <input value={reason} maxLength={300} autoFocus
        onChange={(e) => setReason(e.target.value)}
        placeholder="Customer changed their mind, wrong item, duplicate…"
        style={{ width: "100%", padding: "9px 12px", borderRadius: 8, border: "1px solid var(--line)", fontSize: 13, background: "var(--paper)", color: "var(--ink)" }} />
    </Modal>
  );
}

/* ── Collection modal ── */
function CollectionModal({ order, busy, onCollect, onMarkCollected, onClose }) {
  const outstanding = order.due || 0;
  /* An invoice with nothing owed has nothing to collect. The dialog used to
     render anyway — an empty amount box above a permanently disabled Confirm —
     which is how a delivered order came to have no route to Collected at all.
     It now says so, and offers the action that is actually available. */
  const settled = outstanding <= 0;
  const [amt, setAmt] = useState(String(outstanding || ""));
  const [payType, setPayType] = useState(order.paymentType || "CASH");
  const entered = Number(amt) || 0;
  const over = entered > outstanding;

  return (
    <Modal title={`Record Collection — ${order.invoiceNo}`} onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
        {settled ? (
          <button className="btn btn-primary" disabled={busy}
            onClick={() => onMarkCollected(order)}>
            {busy ? "Saving…" : "Mark as collected"}
          </button>
        ) : (
          <button className="btn btn-primary" disabled={entered <= 0 || over || busy}
            onClick={() => onCollect(order.id, entered, payType)}>
            {busy ? "Recording…" : "Confirm Collection"}
          </button>
        )}
      </>}>
      <div style={{ background: "var(--paper)", borderRadius: 10, padding: "14px 16px", marginBottom: 16 }}>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 8, fontSize: 13 }}>
          <div><span style={{ color: "var(--ink-soft)" }}>Customer</span><div style={{ fontWeight: 700, marginTop: 2 }}>{order.customerName}</div></div>
          <div><span style={{ color: "var(--ink-soft)" }}>Invoice</span><div style={{ fontWeight: 700, fontFamily: "monospace", marginTop: 2 }}>{order.invoiceNo}</div></div>
          <div><span style={{ color: "var(--ink-soft)" }}>Invoice Total</span><div style={{ fontWeight: 700, fontFamily: "monospace", marginTop: 2 }}>{money(order.grandTotal)}</div></div>
          <div><span style={{ color: "var(--ink-soft)" }}>Paid So Far</span><div style={{ fontWeight: 700, fontFamily: "monospace", color: "var(--sage)", marginTop: 2 }}>{money(order.paidAmount)}</div></div>
        </div>
        <div style={{ marginTop: 10, padding: "10px 12px", background: settled ? "var(--sage-soft)" : "var(--clay-soft)", borderRadius: 8, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ fontWeight: 700, color: settled ? "var(--sage)" : "var(--clay)" }}>
            {settled ? "Nothing outstanding" : "Outstanding"}
          </span>
          <span style={{ fontWeight: 800, fontFamily: "monospace", color: settled ? "var(--sage)" : "var(--clay)", fontSize: 16 }}>{money(outstanding)}</span>
        </div>
      </div>
      {settled ? (
        <p style={{ fontSize: 13, color: "var(--ink-soft)", lineHeight: 1.55, margin: 0 }}>
          This invoice was paid in full — at the till, or by an earlier
          collection. There is nothing to take, so the only thing left is to
          record that the order is closed.
        </p>
      ) : (
        <>
          <Field label="Amount to Collect (৳)" error={over ? "Cannot exceed the outstanding amount" : undefined}>
            <input type="number" min="0" step="0.01" max={outstanding} value={amt}
              onChange={(e) => setAmt(e.target.value)}
              style={{ fontSize: 18, fontWeight: 700, fontFamily: "monospace" }} />
          </Field>
          <Field label="Payment Method">
            <select value={payType} onChange={(e) => setPayType(e.target.value)}>
              {["CASH", "CARD", "BKASH", "NAGAD", "BANK"].map((p) => <option key={p}>{p}</option>)}
            </select>
          </Field>
          {entered > 0 && !over && (
            <div style={{ fontSize: 13, color: "var(--ink-soft)", marginTop: -6 }}>
              {/* A part payment leaves the order where it is — there is still
                  something to go back for. Only settling it in full collects it. */}
              Remaining after collection:{" "}
              <strong style={{ color: outstanding - entered > 0 ? "var(--clay)" : "var(--sage)" }}>
                {money(outstanding - entered)}
              </strong>
              {outstanding - entered > 0 && " — the order stays open."}
            </div>
          )}
        </>
      )}
    </Modal>
  );
}

/* ── Delivery performance ── */

/**
 * How each driver is doing.
 *
 * Counts and money side by side, because they answer different questions. A
 * high completion rate with a large outstanding balance means the goods went
 * out and the cash did not come back — which is a worse problem than a driver
 * who simply finished fewer runs.
 *
 * Delivery staff see only their own row; the server scopes it, so this
 * component needs no role check.
 */
function DeliveryPerformance({ rows, loading, onRefresh }) {
  const pct = (v) => (v == null ? "—" : `${Number(v).toFixed(1)}%`);
  const tone = (v, good, ok) =>
    v == null ? "var(--ink-soft)" : v >= good ? "var(--sage)" : v >= ok ? "var(--amber-deep)" : "var(--clay)";

  return (
    <Card>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
        <h3 style={{ margin: 0, fontSize: 15 }}>Delivery performance</h3>
        <span style={{ fontSize: 12, color: "var(--ink-soft)" }}>
          Completed runs, and whether the money came back with them
        </span>
        <button className="btn btn-ghost btn-sm" style={{ marginLeft: "auto" }}
          onClick={onRefresh} disabled={loading}>
          {loading ? "Loading…" : "Refresh"}
        </button>
      </div>

      {rows.length === 0 ? (
        <div className="empty-state">
          {loading ? "Loading…" : "No invoices have been dispatched yet."}
        </div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Delivery Man</th><th>Vehicle</th>
                <th>Assigned</th><th>Delivered</th><th>In progress</th><th>Completion</th>
                <th>Billed</th><th>Collected</th><th>Outstanding</th><th>Collection</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.deliveryManId}>
                  <td style={{ fontWeight: 600 }}>
                    {r.deliveryManName}
                    {r.active === false && (
                      <span style={{ marginLeft: 6, fontSize: 10, color: "var(--ink-soft)" }}>(inactive)</span>
                    )}
                  </td>
                  <td className="mono" style={{ fontSize: 12 }}>{r.vehicleNo || "—"}</td>
                  <td className="mono">{r.assigned}</td>
                  <td className="mono">{r.delivered}</td>
                  <td className="mono">{r.inProgress}</td>
                  <td className="mono" style={{ fontWeight: 700, color: tone(r.completionRate, 80, 50) }}>
                    {pct(r.completionRate)}
                  </td>
                  <td className="mono">{money(r.billed)}</td>
                  <td className="mono" style={{ color: "var(--sage)" }}>{money(r.collected)}</td>
                  <td className="mono" style={{ color: Number(r.outstanding) > 0 ? "var(--clay)" : "var(--ink-soft)", fontWeight: Number(r.outstanding) > 0 ? 700 : 400 }}>
                    {money(r.outstanding)}
                  </td>
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

/* ── Expanded row ── */
function OrderDetail({ order, deliveryMen, onAssign, mayAssign }) {
  const stageIdx = STAGES.indexOf(order.stage);
  // Two separate questions: is this stage still assignable, and is this user
  // allowed to assign at all. A driver fails the second on every order.
  const stageAssignable = ASSIGNABLE.has(order.stage);
  const canAssign = mayAssign && stageAssignable;
  const dm = deliveryMen.find((d) => String(d.id) === String(order.deliveryManId));
  const L = { fontSize: 10.5, color: "var(--ink-soft)", fontWeight: 700, textTransform: "uppercase", letterSpacing: ".08em", marginBottom: 3 };

  return (
    <div style={{ background: "var(--paper)", borderRadius: 12, padding: "16px 18px", margin: "2px 0 6px" }}>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(150px,1fr))", gap: 12, marginBottom: 14 }}>
        <div>
          <div style={L}>Customer</div>
          <div style={{ fontWeight: 600 }}>{order.customerName}</div>
          {order.deliveryAddress && <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>{order.deliveryAddress}</div>}
        </div>
        <div>
          <div style={L}>Payment</div>
          <div style={{ fontWeight: 600 }}>{order.paymentType || "—"}</div>
          {order.deliveryCharge > 0 && <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>Charge: {money(order.deliveryCharge)}</div>}
        </div>
        <div>
          <div style={L}>Delivery Man</div>
          {order.deliveryManName ? (
            <>
              <div style={{ fontWeight: 700, color: "var(--teal)" }}>{order.deliveryManName}</div>
              {dm?.vehicleNo && (
                <div style={{ fontSize: 12, color: "var(--ink-soft)", fontFamily: "monospace" }}>
                  {[dm.vehicleNo, dm.phone].filter(Boolean).join(" · ")}
                </div>
              )}
            </>
          ) : <div style={{ color: "var(--ink-soft)", fontSize: 13 }}>Not assigned</div>}
        </div>
        <div>
          <div style={L}>Summary</div>
          <div style={{ fontFamily: "monospace", fontSize: 13 }}>{money(order.grandTotal)}</div>
          <div style={{ fontSize: 12, color: order.due > 0 ? "var(--clay)" : "var(--sage)", fontWeight: 700 }}>
            Due: {money(order.due)}
          </div>
        </div>
      </div>

      <div style={{ marginBottom: 14 }}>
        <div style={L}>Delivery Progress</div>
        <StageProgress stage={order.stage} />
        <div style={{ display: "flex", justifyContent: "space-between", marginTop: 5 }}>
          {STAGES.map((s, i) => (
            <span key={s} style={{ fontSize: 9, color: i <= stageIdx ? STAGE_COLOR[order.stage] : "var(--ink-soft)", fontWeight: i === stageIdx ? 800 : 400 }}>
              {s.split(" ")[0]}
            </span>
          ))}
        </div>
      </div>

      {canAssign && deliveryMen.length > 0 && (
        <div style={{ background: "var(--card)", border: "1.5px solid var(--line)", borderRadius: 10, padding: "12px 14px", marginBottom: 14 }}>
          <div style={{ ...L, marginBottom: 8 }}>
            {order.deliveryManId ? "Reassign Delivery Man" : "Assign Delivery Man"}
          </div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {deliveryMen.map((d) => (
              <DmTile key={d.id} dm={d} compact
                selected={String(d.id) === String(order.deliveryManId)}
                onPick={(id) => onAssign(order.id, id)} />
            ))}
          </div>
        </div>
      )}

      <table className="data-table" style={{ fontSize: 12.5 }}>
        <thead><tr><th>Product</th><th>Batch</th><th>Qty</th><th>Unit</th><th>Price</th><th>Total</th></tr></thead>
        <tbody>
          {order.lines.length === 0 ? (
            <tr><td colSpan={6} style={{ color: "var(--ink-soft)" }}>No lines on this invoice.</td></tr>
          ) : order.lines.map((l) => (
            <tr key={l.lineId}>
              <td style={{ fontWeight: 600 }}>{l.productName}</td>
              <td className="mono">{l.batchNo || "—"}</td>
              <td className="mono">{l.qty}</td>
              <td>{l.unitMode}</td>
              <td className="mono">{money(l.unitPrice)}</td>
              <td className="mono">{money(l.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PAGE
   ═══════════════════════════════════════════════════════════════════════════ */

export default function SalesActivity() {
  const addToast = useToast();
  const perm = usePermission("activity");
  const { currentUser } = useApp();

  /**
   * A driver, rather than an office user.
   *
   * <p>The server already scopes their list and refuses anything outside their
   * lane, so this changes nothing about what is *possible* — only what is
   * offered. Showing a driver an Assign button that always fails would be worse
   * than not showing it.
   */
  const isDriver = svc.isDriverRole(currentUser?.roleCode);
  const canAssign = perm.canEdit && !isDriver;

  const [orders, setOrders] = useState([]);
  const [meta, setMeta] = useState({ total: 0, totalPages: 0, page: 0 });
  const [counts, setCounts] = useState({});
  const [deliveryMen, setDeliveryMen] = useState([]);

  const [firstLoad, setFirstLoad] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const [filter, setFilter] = useState(svc.emptyFilter());
  const [page, setPage] = useState(0);

  const [expandId, setExpandId] = useState(null);
  const [assignFor, setAssignFor] = useState(null);
  const [collectFor, setCollectFor] = useState(null);
  const [cancelFor, setCancelFor] = useState(null);

  const [bulkMode, setBulkMode] = useState(false);
  const [showPerf, setShowPerf] = useState(false);
  const [perfRows, setPerfRows] = useState([]);
  const [perfLoading, setPerfLoading] = useState(false);
  const [selected, setSelected] = useState(new Set());
  const [bulkDmId, setBulkDmId] = useState("");

  const SIZE = 20;

  /* ── load ────────────────────────────────────────────────────────────── */

  const load = useCallback(async (f, p) => {
    setRefreshing(true);
    try {
      const [list, stageCounts] = await Promise.all([
        svc.listActivity(f, p, SIZE),
        svc.getStageCounts(),
      ]);
      setOrders(list.items);
      setMeta({ total: list.total, totalPages: list.totalPages, page: list.page });
      setCounts(stageCounts || {});
      setError(null);
    } catch (e) {
      setError(e?.message || "Could not load sales activity.");
    } finally {
      setRefreshing(false);
      setFirstLoad(false);
    }
  }, []);

  // Search is debounced; the rest apply at once. Typing must not fire a request
  // per keystroke, but choosing a stage should not feel delayed.
  const debounce = useRef(null);
  useEffect(() => {
    clearTimeout(debounce.current);
    debounce.current = setTimeout(() => load(filter, page), filter.q ? 300 : 0);
    return () => clearTimeout(debounce.current);
  }, [filter, page, load]);

  useEffect(() => {
    svc.listDeliveryMen()
       .then(setDeliveryMen)
       .catch(() => setDeliveryMen([]));   // the list still works without them
  }, []);

  const refresh = () => load(filter, page);

  /**
   * Performance figures, respecting the date range already on screen.
   *
   * Loaded on demand rather than with the list: it is an aggregate over every
   * invoice ever dispatched, and most visits to this page are to work the
   * queue, not to review it.
   */
  const loadPerformance = useCallback(() => {
    setPerfLoading(true);
    return svc.getDeliveryPerformance({ from: filter.from || null, to: filter.to || null })
      .then((rows) => setPerfRows(rows || []))
      .catch((e) => addToast(e?.message || "Could not load performance.", "error"))
      .finally(() => setPerfLoading(false));
  }, [filter.from, filter.to]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { if (showPerf) loadPerformance(); }, [showPerf, loadPerformance]);

  /** Filters change what the page contains, so page 0 is the only honest place. */
  const setF = (patch) => { setFilter((f) => ({ ...f, ...patch })); setPage(0); };
  const clearFilters = () => { setFilter(svc.emptyFilter()); setPage(0); };

  /* ── actions ─────────────────────────────────────────────────────────── */

  /**
   * Runs a server action, then reloads.
   *
   * Reloading rather than patching local state is deliberate: an assignment can
   * change the stage, a collection can change the stage and the payment status,
   * and guessing which is the sort of drift that made the old page's numbers
   * disagree with the database.
   */
  const run = async (fn, success) => {
    setBusy(true);
    try {
      await fn();
      addToast(success);
      await refresh();
      return true;
    } catch (e) {
      addToast(e?.message || "That did not work.", "error");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const doAssign = async (saleId, dmId) => {
    const dm = deliveryMen.find((d) => String(d.id) === String(dmId));
    const ok = await run(() => svc.assignDeliveryMan(saleId, dmId),
                         `${dm?.name || "Delivery man"} assigned`);
    if (ok) setAssignFor(null);
  };

  const doAdvance = async (order) => {
    const next = svc.nextStage(order.stage);
    if (!next) return;
    // Two stages are not a plain transition: one needs a person, the other needs
    // money. Both open the dialog that collects it rather than advancing blind.
    if (next === "Delivery Assigned") { setAssignFor(order); return; }
    if (next === "Collected") {
      /* Nothing owed — a cash sale settled at the till, or an invoice already
         paid off. There is no collection to record, and the dialog would offer
         an amount box that must stay empty above a button that can never be
         pressed. That left a delivered order with no way to reach Collected at
         all: the only route to the stage demanded money that was not owed. */
      if ((Number(order.due) || 0) <= 0) {
        await run(() => svc.advanceStage(order.id, next),
                  `${order.invoiceNo} → Collected — nothing was outstanding`);
        return;
      }
      setCollectFor(order);
      return;
    }
    await run(() => svc.advanceStage(order.id, next), `${order.invoiceNo} → ${next}`);
  };

  /* Closing an order that owes nothing. The stage endpoint already handles
     this — it was simply never called, because every route to Collected went
     through the collection dialog. */
  const doMarkCollected = async (order) => {
    const ok = await run(() => svc.advanceStage(order.id, "Collected"),
                         `${order.invoiceNo} → Collected`);
    if (ok) setCollectFor(null);
  };

  /* Calling an order off. The server does the real work — stock back, journals
     reversed — and refuses if the goods have gone or money has been taken, so
     the button is offered on its rule rather than on a guess. */
  const doCancel = async (order, reason) => {
    const ok = await run(() => svc.cancel(order.id, reason),
                         `${order.invoiceNo} cancelled — stock returned`);
    if (ok) setCancelFor(null);
  };

  const doCollect = async (saleId, amount, payType) => {
    const ok = await run(() => svc.collect(saleId, amount, payType),
                         `Collection of ${money(amount)} recorded`);
    if (ok) setCollectFor(null);
  };

  const doBulkAssign = async () => {
    if (!bulkDmId)      { addToast("Pick a delivery man first", "warning"); return; }
    if (!selected.size) { addToast("Select at least one order", "warning"); return; }
    const dm = deliveryMen.find((d) => String(d.id) === String(bulkDmId));
    const ok = await run(() => svc.bulkAssign([...selected], bulkDmId),
                         `${dm?.name} assigned to ${selected.size} order${selected.size > 1 ? "s" : ""}`);
    if (ok) { setSelected(new Set()); setBulkMode(false); setBulkDmId(""); }
  };

  /* ── derived ─────────────────────────────────────────────────────────── */

  const eligible = useMemo(
    () => orders.filter((o) => o.stage === "Confirmed" || o.stage === "Submitted"),
    [orders]);

  const toggleSelect = (id) => setSelected((prev) => {
    const n = new Set(prev);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });

  const exportCSV = () => {
    downloadCSV("sales-activity.csv",
      orders.map((o) => ({
        Invoice: o.invoiceNo, Customer: o.customerName, Date: o.date,
        Total: o.grandTotal, Paid: o.paidAmount, Due: o.due,
        Stage: o.stage, Payment: o.paymentStatus, DeliveryMan: o.deliveryManName || "",
      })),
      ["Invoice", "Customer", "Date", "Total", "Paid", "Due", "Stage", "Payment", "DeliveryMan"]);
  };

  const colSpan = bulkMode ? 11 : 10;
  const inputStyle = { padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", fontSize: 13, background: "var(--paper)", color: "var(--ink)" };

  /* ── render ──────────────────────────────────────────────────────────── */

  if (error && firstLoad) {
    return (
      <div className="page">
        <div className="empty-state" role="alert">
          {error}
          <div style={{ marginTop: 12 }}>
            <button className="btn btn-ghost btn-sm" onClick={refresh}>Retry</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Sales Activity</h1>
          <div className="page-sub">
            Track orders from submission to collection. Assign delivery men directly on each invoice.
          </div>
        </div>
        <div className="flex-gap">
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" onClick={exportCSV} disabled={!orders.length}>
              <Ic path="M12 3v13 M6 11l6 6 6-6 M4 20h16" /> Export page
            </button>
          )}
          {canAssign && (
            <button className={`btn btn-sm ${bulkMode ? "btn-primary" : "btn-ghost"}`}
              onClick={() => { setBulkMode((v) => !v); setSelected(new Set()); }}>
              <Ic path={["M1 6h14v11H1z", "M15 9h4l3 3v5h-7z", "M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"]} size={13} />
              Bulk Assign DM
            </button>
          )}
          <button className={`btn btn-sm ${showPerf ? "btn-primary" : "btn-ghost"}`}
            onClick={() => setShowPerf((v) => !v)}>
            <Ic path={["M3 3v18h18", "M7 15l4-4 3 3 5-6"]} size={13} />
            {isDriver ? "My performance" : "Performance"}
          </button>
          <Pill tone="teal">{meta.total} orders</Pill>
          {refreshing && !firstLoad && <span style={{ fontSize: 12, color: "var(--ink-soft)" }}>updating…</span>}
        </div>
      </div>

      {bulkMode && (
        <div style={{ background: "linear-gradient(135deg,var(--teal-soft),var(--card))", border: "2px solid var(--teal)", borderRadius: 14, padding: "14px 18px", marginBottom: 14, display: "flex", flexWrap: "wrap", gap: 14, alignItems: "center" }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 13.5, color: "var(--teal)" }}>Bulk Assign Delivery Man</div>
            <div style={{ fontSize: 12, color: "var(--ink-soft)", marginTop: 2 }}>
              Select Submitted or Confirmed orders on this page, then pick a delivery man.
            </div>
          </div>
          <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", marginLeft: "auto" }}>
            <button className="btn btn-ghost btn-sm" onClick={() => setSelected(new Set(eligible.map((o) => o.id)))}>
              Select all eligible ({eligible.length})
            </button>
            {selected.size > 0 && <button className="btn btn-ghost btn-sm" onClick={() => setSelected(new Set())}>Clear</button>}
            <select value={bulkDmId} onChange={(e) => setBulkDmId(e.target.value)}
              style={{ ...inputStyle, borderColor: bulkDmId ? "var(--teal)" : "var(--line)", background: "var(--card)", cursor: "pointer" }}>
              <option value="">— Pick delivery man —</option>
              {deliveryMen.map((d) => (
                <option key={d.id} value={d.id}>{d.name}{d.vehicleNo ? ` · ${d.vehicleNo}` : ""}</option>
              ))}
            </select>
            <button className="btn btn-teal" disabled={!selected.size || !bulkDmId || busy}
              onClick={doBulkAssign} style={{ opacity: (!selected.size || !bulkDmId) ? 0.5 : 1 }}>
              {busy ? "Assigning…" : `Assign to ${selected.size || 0} order${selected.size !== 1 ? "s" : ""}`}
            </button>
          </div>
        </div>
      )}

      {/* Counts come from the server across the whole branch, so they stay put
          as you page and are not the size of the current slice. */}
      <div style={{ display: "flex", gap: 7, flexWrap: "wrap", marginBottom: 16 }}>
        {["All", ...STAGES, svc.CANCELLED].map((s) => {
          const active = filter.stage === s;
          const clr = STAGE_COLOR[s] || "var(--teal)";
          return (
            <button key={s} onClick={() => setF({ stage: s })}
              style={{ padding: "6px 14px", borderRadius: 999, fontSize: 12.5, fontWeight: 600, cursor: "pointer", border: active ? "none" : "1.5px solid var(--line)", background: active ? clr : "var(--card)", color: active ? "#fff" : "var(--ink)", display: "flex", alignItems: "center", gap: 6, transition: "all .12s" }}>
              {s}
              <span style={{ background: active ? "rgba(255,255,255,0.22)" : "var(--paper)", color: active ? "#fff" : "var(--ink-soft)", borderRadius: 999, padding: "1px 7px", fontFamily: "monospace", fontSize: 11, fontWeight: 700 }}>
                {counts[s] ?? 0}
              </span>
            </button>
          );
        })}
      </div>

      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end", marginBottom: 16, padding: "12px 16px", background: "var(--card)", borderRadius: "var(--radius)", border: "1px solid var(--line)" }}>
        <div className="search-box" style={{ flex: 2, maxWidth: 340 }}>
          <Ic path="M21 21l-4.3-4.3M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z" size={14} />
          <input placeholder="Search invoice, customer, delivery man…" value={filter.q}
            onChange={(e) => setF({ q: e.target.value })}
            style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1 }} />
          {filter.q && <button onClick={() => setF({ q: "" })} style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>}
        </div>
        <div>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>Payment</div>
          <select value={filter.paymentStatus} onChange={(e) => setF({ paymentStatus: e.target.value })} style={inputStyle}>
            <option value="All">All</option><option>Paid</option><option>Partial</option><option>Unpaid</option>
          </select>
        </div>
        <div>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>Source</div>
          <select value={filter.source} onChange={(e) => setF({ source: e.target.value })} style={inputStyle}>
            {svc.SOURCES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
        <div>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>Delivery Man</div>
          <select value={filter.deliveryManId} onChange={(e) => setF({ deliveryManId: e.target.value })} style={inputStyle}>
            <option value="All">All</option>
            <option value="unassigned">Unassigned</option>
            {deliveryMen.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>
        <div>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>From</div>
          <input type="date" value={filter.from} onChange={(e) => setF({ from: e.target.value })} style={inputStyle} />
        </div>
        <div>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 3 }}>To</div>
          <input type="date" value={filter.to} onChange={(e) => setF({ to: e.target.value })} style={inputStyle} />
        </div>
        {svc.hasActiveFilter(filter) && (
          <button className="btn btn-ghost btn-sm" onClick={clearFilters}>Clear all</button>
        )}
      </div>

      {showPerf && (
        <div style={{ marginBottom: 16 }}>
          <DeliveryPerformance rows={perfRows} loading={perfLoading} onRefresh={loadPerformance} />
        </div>
      )}

      <Card>
        {firstLoad ? (
          <div className="empty-state">Loading orders…</div>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    {bulkMode && <th style={{ width: 36, textAlign: "center" }}>☐</th>}
                    <th style={{ width: 32 }}></th>
                    <th>Invoice</th><th>Customer</th><th>Date</th>
                    <th>Amount</th><th>Due</th><th>Stage</th><th>Payment</th>
                    <th>Delivery Man</th>
                    <th style={{ minWidth: 200 }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((o) => {
                    const dm = deliveryMen.find((d) => String(d.id) === String(o.deliveryManId));
                    // A cancelled document is finished. Offering Advance or
                    // Collect on it would be offering work on an order whose
                    // goods are back on the shelf and whose journals are reversed.
                    const done = o.stage === svc.CANCELLED;
                    const next = done ? null : svc.nextStage(o.stage);
                    return (
                      <React.Fragment key={o.id}>
                        <tr style={{ background: expandId === o.id ? "var(--amber-soft)" : selected.has(o.id) ? "var(--teal-soft)" : "" }}>
                          {bulkMode && (
                            <td>
                              {(o.stage === "Confirmed" || o.stage === "Submitted") ? (
                                <input type="checkbox" checked={selected.has(o.id)}
                                  onChange={() => toggleSelect(o.id)}
                                  style={{ width: 16, height: 16, accentColor: "var(--teal)", cursor: "pointer" }} />
                              ) : <span style={{ fontSize: 10, color: "var(--ink-soft)" }}>—</span>}
                            </td>
                          )}
                          <td>
                            <button onClick={() => setExpandId(expandId === o.id ? null : o.id)}
                              style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)", padding: 4, borderRadius: 6 }}>
                              <Ic path={expandId === o.id ? "m18 15-6-6-6 6" : "m6 9 6 6 6-6"} />
                            </button>
                          </td>
                          <td className="mono" style={{ fontWeight: 700 }}>
                            {o.invoiceNo}
                            {/* An online order has a number of its own — the one the
                                customer reads out on the phone. Both are shown so the
                                counter knows they are the same document. */}
                            {o.ecomOrderNo && (
                              <div style={{ fontSize: 10.5, fontWeight: 600, color: "var(--teal)", marginTop: 2, display: "flex", alignItems: "center", gap: 4 }}>
                                <span style={{ background: "var(--teal-soft)", borderRadius: 4, padding: "0 5px", fontSize: 9.5, letterSpacing: .3 }}>ONLINE</span>
                                {o.ecomOrderNo}
                              </div>
                            )}
                          </td>
                          <td style={{ fontWeight: 600 }}>{o.customerName}</td>
                          <td className="mono" style={{ fontSize: 12 }}>{o.date}</td>
                          <td className="mono" style={{ fontWeight: 700 }}>{money(o.grandTotal)}</td>
                          <td className="mono" style={{ color: o.due > 0 ? "var(--clay)" : "var(--sage)", fontWeight: o.due > 0 ? 700 : 400 }}>{money(o.due)}</td>
                          <td>
                            <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
                              <span style={{ width: 7, height: 7, borderRadius: "50%", background: STAGE_COLOR[o.stage] || "var(--line)", display: "inline-block" }} />
                              <Pill tone={STAGE_TONE[o.stage] || "grey"}>{o.stage}</Pill>
                            </span>
                            {o.cancelReason && (
                              <div title={[o.cancelReason, o.cancelledBy && `by ${o.cancelledBy}`].filter(Boolean).join(" · ")}
                                style={{ fontSize: 10.5, color: "var(--ink-soft)", marginTop: 3, maxWidth: 160, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                                {o.cancelReason}
                              </div>
                            )}
                          </td>
                          <td><Pill tone={PAY_TONE[o.paymentStatus] || "grey"}>{o.paymentStatus}</Pill></td>
                          <td>
                            {o.deliveryManName ? (
                              <div>
                                <div style={{ fontSize: 12.5, fontWeight: 600, color: "var(--teal)" }}>{o.deliveryManName}</div>
                                {dm?.vehicleNo && <div style={{ fontSize: 10.5, color: "var(--ink-soft)", fontFamily: "monospace" }}>{dm.vehicleNo}</div>}
                              </div>
                            ) : <span style={{ color: "var(--ink-soft)", fontSize: 12 }}>Not assigned</span>}
                          </td>
                          <td>
                            <div style={{ display: "flex", gap: 5, flexWrap: "wrap" }}>
                              {/* A driver is offered the move only when the next
                                  stage is one they may actually make. Anything
                                  else is management's, and the server refuses it. */}
                              {next && perm.canEdit
                                && (!isDriver || svc.DRIVER_STAGES.includes(next)) && (
                                <button className="btn btn-teal btn-sm" disabled={busy}
                                  onClick={() => doAdvance(o)} style={{ fontSize: 11 }}>
                                  {NEXT_LABEL[o.stage] || "Advance"} →
                                </button>
                              )}
                              {/* Offered on the server's own rule: before the goods
                                  leave, and only while nothing has been paid. A
                                  driver never gets it — cancelling is the branch's
                                  decision, not the round's. */}
                              {perm.canEdit && !isDriver && !done && svc.isCancellable(o) && (
                                <button className="btn btn-sm" disabled={busy} onClick={() => setCancelFor(o)}
                                  style={{ fontSize: 11, background: "transparent", color: "var(--clay)", border: "1px solid var(--clay)" }}>
                                  Cancel
                                </button>
                              )}
                              {o.due > 0 && !done && perm.canEdit && (
                                <button className="btn btn-sm" disabled={busy} onClick={() => setCollectFor(o)}
                                  style={{ fontSize: 11, background: "var(--clay-soft)", color: "var(--clay)", border: "1px solid var(--clay)" }}>
                                  Collect
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                        {expandId === o.id && (
                          <tr>
                            <td colSpan={colSpan} style={{ padding: 0, background: "var(--paper)" }}>
                              <OrderDetail order={o} deliveryMen={deliveryMen}
                                onAssign={doAssign} mayAssign={canAssign} />
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {orders.length === 0 && (
              <div className="empty-state">
                {svc.hasActiveFilter(filter)
                  ? "No orders match the current filters."
                  : "No sales have been recorded yet."}
              </div>
            )}

            {meta.totalPages > 1 && (
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 0 0", flexWrap: "wrap", gap: 8 }}>
                <span style={{ fontSize: 13, color: "var(--ink-soft)" }}>
                  Showing {orders.length} of {meta.total} orders
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
      </Card>

      {assignFor && (
        <AssignDMModal order={assignFor} deliveryMen={deliveryMen} busy={busy}
          onAssign={doAssign} onClose={() => setAssignFor(null)} />
      )}
      {collectFor && (
        <CollectionModal order={collectFor} busy={busy}
          onCollect={doCollect} onMarkCollected={doMarkCollected}
          onClose={() => setCollectFor(null)} />
      )}
      {cancelFor && (
        <CancelModal order={cancelFor} busy={busy}
          onCancel={doCancel} onClose={() => setCancelFor(null)} />
      )}
    </div>
  );
}
