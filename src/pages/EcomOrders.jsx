/**
 * E-Commerce Orders — the pharmacy's board for what was ordered online.
 *
 * docs/ecommerce Phase 8. Every row is already a real invoice: an online order
 * created one at checkout, with the stock off the shelf and the ledger posted,
 * which is why this screen never makes a sale. It moves one along:
 *
 *   Confirm       — the pharmacy accepts it (invoice → Confirmed)
 *   Start packing — the shop floor; the invoice deliberately does not move
 *   Mark ready    — likewise
 *   Dispatch      — pick the driver (invoice → Delivery Assigned → On The Way)
 *   Delivered     — (invoice → Delivered; the money is collected on Sales
 *                    Activity or Delivery, where collections have always lived)
 *   Cancel        — reverses the invoice, its stock and its journals
 *
 * The server holds the state machine and the rights: Confirm and Cancel need
 * Approve on this module, the rest need Edit, and it refuses a move it does not
 * allow whatever this screen offers.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Modal, Field } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import {
  HrPage, Kpis, Kpi, HrTable, Status, Filter, HrFilterBar, HrSearch, HrButton,
  HrPagination, HrErrorState, HrDrawer, HrTabs, Pane, Facts, Fact, HrTimeline,
  humanError, fmtWhen, usePagedList, P,
} from "../components/HrKit.jsx";
import svc, { STATUSES, STATUS_LABEL, ACTIONS, STAGE_NOTE } from "../services/ecomOrderBoardService.js";
import { getDeliveryMen } from "../services/masterDataService.js";

const CRUMBS = [{ label: "E-Commerce" }, { label: "Orders" }];

const money = (v) => "৳ " + Number(v || 0).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/* A phone number as people read it: 8801712345678 → 01712-345678 */
const phone = (m) => {
  if (!m) return "—";
  const local = m.length === 13 && m.startsWith("880") ? m.slice(2) : m;
  return local.length === 11 ? `${local.slice(0, 5)}-${local.slice(5)}` : local;
};

export default function EcomOrders() {
  const toast = useToast();
  const perm = usePermission("ecomorders");

  /* The board opens on what still needs doing. Months of delivered orders
     under the same list would bury a morning's work. */
  const [status, setStatus] = useState("OPEN");
  const [q, setQ] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [counts, setCounts] = useState(null);
  const [open, setOpen] = useState(null);       // order number in the drawer

  const list = usePagedList(
    ({ page, size }) => svc.listOrders({ status, q, from, to, page, size }),
    [status, q, from, to],
  );

  const loadCounts = useCallback(async () => {
    try { setCounts(await svc.getStatusCounts()); } catch { setCounts(null); }
  }, []);
  useEffect(() => { loadCounts(); }, [loadCounts]);

  const refresh = () => { list.reload(); loadCounts(); };

  const cols = [
    { key: "orderNo", label: "Order", lead: true,
      render: (r) => (
        <div>
          <div className="num" style={{ fontWeight: 700 }}>{r.orderNo}</div>
          <div className="ec-sub">{r.invoiceNo ? `invoice ${r.invoiceNo}` : "no invoice"}{r.invoiceStage ? ` · ${r.invoiceStage}` : ""}</div>
        </div>
      ) },
    { key: "placedAt", label: "Placed", sortable: true, sortValue: (r) => r.placedAt || "",
      render: (r) => fmtWhen(r.placedAt) },
    { key: "customerName", label: "Customer",
      render: (r) => (
        <div>
          <div style={{ fontWeight: 600 }}>{r.customerName}</div>
          <div className="ec-sub num">{phone(r.customerPhone)}</div>
          {/* [V112] "Wholesaler" and "wholesaler in Rangpur" are different facts
              when an order looks wrong, so the region rides with the type. A
              retail account has neither. */}
          {r.customerType && r.customerType !== "RETAILER" && (
            <div className="ec-sub">
              {r.customerType.toLowerCase()}
              {r.customerRegion ? " · " + r.customerRegion : " · region not approved"}
            </div>
          )}
        </div>
      ) },
    { key: "deliveryArea", label: "Goes to", advisory: true,
      render: (r) => r.deliveryArea || <span className="hr-dash">—</span> },
    { key: "lineCount", label: "Items", render: (r) => (
      <span>{r.lineCount} line{r.lineCount === 1 ? "" : "s"}{r.prescription ? " · Rx" : ""}</span>
    ) },
    { key: "grandTotal", label: "Total", render: (r) => (
      <div>
        <div className="num" style={{ fontWeight: 700 }}>{money(r.grandTotal)}</div>
        <div className="ec-sub">{r.unpaid ? "unpaid" : "paid"} · {r.paymentMethod === "COD" ? "cash on delivery" : r.paymentMethod}</div>
      </div>
    ) },
    { key: "deliveryManName", label: "Delivery man", advisory: true,
      render: (r) => r.deliveryManName || <span className="hr-dash">—</span> },
    { key: "status", label: "Status", render: (r) => <Status code={r.status} label={STATUS_LABEL[r.status] || r.status} /> },
    { key: "act", label: "", actions: true,
      render: (r) => (
        <HrButton size="sm" icon={P.eye} onClick={(e) => { e.stopPropagation(); setOpen(r.orderNo); }}>Open</HrButton>
      ) },
  ];

  const active = (status && status !== "OPEN" ? 1 : 0) + (q ? 1 : 0) + (from ? 1 : 0) + (to ? 1 : 0);

  return (
    <HrPage crumbs={CRUMBS} title="E-Commerce Orders"
            subtitle="What customers ordered online from this pharmacy — confirm, pack, dispatch, deliver"
            icon={P.inbox}
            actions={<HrButton icon={P.reset} onClick={refresh}>Refresh</HrButton>}>

      <Kpis>
        <Kpi label="Needs work" value={counts ? (counts.OPEN ?? 0) : "…"}
             warn={!!counts && (counts.NEW ?? 0) > 0}
             onClick={() => setStatus("OPEN")} tone={status === "OPEN" ? "teal" : undefined} />
        {STATUSES.map((s) => (
          <Kpi key={s} label={STATUS_LABEL[s]} value={counts ? (counts[s] ?? 0) : "…"}
               warn={s === "NEW" && counts && (counts.NEW ?? 0) > 0}
               onClick={() => setStatus(status === s ? "OPEN" : s)}
               tone={status === s ? "teal" : undefined} />
        ))}
      </Kpis>

      <HrFilterBar active={active} onReset={() => { setStatus("OPEN"); setQ(""); setFrom(""); setTo(""); }}>
        <Filter label="Status">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="OPEN">Still needs work</option>
            <option value="">All orders</option>
            {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </select>
        </Filter>
        <Filter label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Filter>
        <Filter label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Filter>
        <Filter label="Search" grow>
          <HrSearch value={q} onChange={setQ} placeholder="Order number, customer or mobile" />
        </Filter>
      </HrFilterBar>

      {list.err
        ? <HrErrorState title="The order board could not be read" detail={list.err} onRetry={list.reload} />
        : <HrTable cols={cols} rows={list.rows} rowKey={(r) => r.orderNo} loading={list.busy}
                   onRowClick={(r) => setOpen(r.orderNo)}
                   empty={status === "OPEN" ? "Nothing is waiting — every online order is finished." : "No orders match."} />}

      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages}
                    onPage={list.setPage} onSize={list.setSize} />

      {open && (
        <OrderDrawer orderNo={open} perm={perm} toast={toast}
                     onClose={() => setOpen(null)}
                     onChanged={refresh} />
      )}
    </HrPage>
  );
}

/* ── One order ────────────────────────────────────────────────────────── */

function OrderDrawer({ orderNo, perm, toast, onClose, onChanged }) {
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  const [tab, setTab] = useState("items");
  const [ask, setAsk] = useState(null);      // the action awaiting a dialog
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setD(await svc.getOrder(orderNo)); setErr(null); }
    catch (e) { setErr(humanError(e, "The order could not be read.")); }
  }, [orderNo]);
  useEffect(() => { load(); }, [load]);

  const canApprove = perm.canApprove || perm.isSuperAdmin;
  const canEdit = perm.canEdit || perm.isSuperAdmin;

  const run = async (action, extra = {}) => {
    setBusy(true);
    try {
      const after = await svc.act(orderNo, action, extra);
      setD(after);
      setAsk(null);
      toast(`${orderNo} · ${STATUS_LABEL[after.status] || after.status}`);
      onChanged();
    } catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  /** Offered only when the server would accept it and the matrix allows it. */
  const offered = (d?.allowedActions || []).filter((a) => {
    const meta = ACTIONS[a];
    if (!meta) return false;
    return meta.needsApprove ? canApprove : canEdit;
  });

  const footer = d && (
    <div className="ec-row" style={{ justifyContent: "flex-end", gap: 8, flexWrap: "wrap" }}>
      {offered.length === 0 && <span className="ec-fine">Nothing further to do here.</span>}
      {offered.map((a) => {
        const meta = ACTIONS[a];
        const needsDialog = meta.needsDriver || meta.needsReason;
        return (
          <HrButton key={a} disabled={busy}
                    variant={meta.danger ? "danger" : (a === d.nextAction ? "primary" : undefined)}
                    icon={meta.danger ? P.ban : P.check}
                    onClick={() => (needsDialog ? setAsk(a) : run(a))}>
            {meta.label}
          </HrButton>
        );
      })}
    </div>
  );

  return (
    <HrDrawer title={orderNo}
              subtitle={d ? `${d.customerName} · ${phone(d.customerPhone)}` : ""}
              onClose={onClose} footer={footer} width={620}>
      {err && <HrErrorState title="Could not load" detail={err} onRetry={load} />}
      {!err && !d && <div className="ec-fine">Loading…</div>}
      {d && (
        <>
          <div className="ec-row" style={{ gap: 8, marginBottom: 12, flexWrap: "wrap" }}>
            <Status code={d.status} label={STATUS_LABEL[d.status] || d.status} />
            {d.customerType && <span className="ec-tag">{d.customerType.toLowerCase()} pricing</span>}
            {d.customerType && d.customerType !== "RETAILER" && (
              <span className="ec-tag">{d.customerRegion || "no approved region"}</span>
            )}
            {d.invoiceNo && <span className="ec-tag num">{d.invoiceNo} · {d.invoiceStage}</span>}
            {d.lines?.some((l) => l.prescription) && <Status code="RX" label="Prescription item" />}
          </div>

          <div className="ec-note" style={{ marginBottom: 12 }}>{STAGE_NOTE[d.status]}</div>

          {d.status === "CANCELLED" && d.cancelReason && (
            <div className="ec-note" style={{ marginBottom: 12 }}>
              Cancelled {d.cancelledByKind === "CUSTOMER" ? "by the customer" : "by the pharmacy"}: {d.cancelReason}
            </div>
          )}

          <HrTabs active={tab} onChange={setTab} tabs={[
            { id: "items", label: `Items (${d.lines?.length || 0})` },
            { id: "delivery", label: "Delivery" },
            { id: "history", label: "History" },
          ]} />

          <Pane id="items" active={tab}>
            {(d.lines || []).map((l, i) => (
              <div key={i} className="ec-card" style={{ marginBottom: 10 }}>
                <div className="ec-row" style={{ justifyContent: "space-between", gap: 10 }}>
                  <div>
                    <b>{l.name}</b>{l.prescription && <span className="ec-tag" style={{ marginLeft: 6 }}>Rx</span>}
                    <div className="ec-sub">{[l.generic, l.company].filter(Boolean).join(" · ")}</div>
                  </div>
                  <div style={{ textAlign: "right" }}>
                    <div className="num" style={{ fontWeight: 700 }}>{money(l.lineTotal)}</div>
                    <div className="ec-sub num">{Number(l.qty)} × {l.unit} @ {money(l.unitPrice)}</div>
                  </div>
                </div>
                {/* Which lots the invoice actually took, so the parcel matches
                    the document — and the expiry on the strip matches the bill. */}
                {(l.batches || []).length > 0 && (
                  <div className="ec-sub" style={{ marginTop: 6 }}>
                    Pick from: {l.batches.map((b) => `${b.batchNo || "—"} (${Number(b.qty)} ${b.unit}${b.expiry ? `, exp ${b.expiry}` : ""})`).join(" · ")}
                  </div>
                )}
              </div>
            ))}
            <Facts>
              <Fact label="Subtotal" value={money(d.subtotal)} num />
              <Fact label="Discount" value={money(d.discountAmount)} num />
              {/* What a promotion took off, split out so the board's figures
                  add up the same way the customer's own order does. */}
              {Number(d.couponAmount) > 0 && (
                <Fact label={"Coupon " + (d.couponCode || "")} value={"− " + money(d.couponAmount)} num />
              )}
              {Number(d.pointsValue) > 0 && (
                <Fact label={"Points (" + Number(d.pointsRedeemed) + ")"} value={"− " + money(d.pointsValue)} num />
              )}
              <Fact label="Delivery" value={Number(d.deliveryCharge) > 0 ? money(d.deliveryCharge) : "Free"} num />
              <Fact label="Total" value={money(d.grandTotal)} num />
              <Fact label="Payment" value={d.paymentMethod === "COD" ? "Cash on delivery" : d.paymentMethod} />
              <Fact label="Still owed on the invoice" value={d.invoiceDue == null ? "—" : money(d.invoiceDue)} num />
            </Facts>
          </Pane>

          <Pane id="delivery" active={tab}>
            <Facts>
              {/* The pharmacy the customer chose — the one that fulfils and
                  invoices. On a branch-scoped board it is always this branch,
                  and saying so is what makes a reassignment visible. */}
              <Fact label="Pharmacy" value={d.fulfilBranchName || "—"} />
              <Fact label="Recipient" value={d.deliveryRecipient || d.customerName} />
              <Fact label="Phone" value={phone(d.deliveryPhone || d.customerPhone)} />
              <Fact label="Address" value={d.deliveryAddress || "—"} />
              <Fact label="Pin" value={d.deliveryLatitude != null ? `${d.deliveryLatitude}, ${d.deliveryLongitude}` : "not shared"} />
              <Fact label="Delivery man" value={d.deliveryManName || "not assigned"} />
              <Fact label="Placed" value={fmtWhen(d.placedAt)} />
              <Fact label="Confirmed" value={d.confirmedAt ? fmtWhen(d.confirmedAt) : "—"} />
              <Fact label="Delivered" value={d.deliveredAt ? fmtWhen(d.deliveredAt) : "—"} />
            </Facts>
            {d.customerNote && <div className="ec-note" style={{ marginTop: 12 }}>Note from the customer: {d.customerNote}</div>}
            {d.deliveryLatitude != null && (
              <a className="ec-link" target="_blank" rel="noreferrer"
                 href={`https://www.google.com/maps/search/?api=1&query=${d.deliveryLatitude},${d.deliveryLongitude}`}>
                Open the pin in Maps
              </a>
            )}
          </Pane>

          <Pane id="history" active={tab}>
            <HrTimeline items={(d.history || []).map((h) => ({
              title: STATUS_LABEL[h.status] || h.status,
              when: fmtWhen(h.at),
              who: h.by,
              note: h.reason,
              tone: h.status === "CANCELLED" ? "clay" : h.status === "DELIVERED" ? "sage" : "teal",
            }))} empty="Nothing recorded yet." />
          </Pane>

          {ask === "DISPATCH" && (
            <DispatchModal busy={busy} onClose={() => setAsk(null)}
                           onDispatch={(dmId) => run("DISPATCH", { deliveryManId: dmId })} />
          )}
          {ask === "CANCEL" && (
            <CancelModal order={d} busy={busy} onClose={() => setAsk(null)}
                         onCancel={(reason) => run("CANCEL", { reason })} />
          )}
        </>
      )}
    </HrDrawer>
  );
}

/* ── Who takes it out ─────────────────────────────────────────────────── */

function DispatchModal({ busy, onClose, onDispatch }) {
  const [men, setMen] = useState(null);
  const [dmId, setDmId] = useState("");
  useEffect(() => {
    getDeliveryMen().then((rows) => {
      setMen(rows || []);
      if (rows?.length) setDmId(String(rows[0].id));
    }).catch(() => setMen([]));
  }, []);

  return (
    <Modal title="Dispatch this order" onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Not yet</button>
        <button className="btn btn-primary" disabled={!dmId || busy} onClick={() => onDispatch(dmId)}>
          {busy ? "Dispatching…" : "Dispatch"}
        </button>
      </>}>
      {men === null && <p className="ec-fine">Loading delivery men…</p>}
      {men?.length === 0 && (
        <div className="ec-note">
          No delivery men are set up in this branch. Add an employee of type
          <strong> Delivery Man</strong> in Master Data › Employees.
        </div>
      )}
      {men?.length > 0 && (
        <>
          <p className="ec-fine" style={{ marginBottom: 10 }}>
            The invoice is assigned and moved to <strong>On The Way</strong>, exactly as it would be
            from Sales Activity. The customer sees “On the way”.
          </p>
          <Field label="Delivery man">
            <select value={dmId} onChange={(e) => setDmId(e.target.value)}>
              {men.map((m) => (
                <option key={m.id} value={m.id}>{m.empName}{m.vehicleNo ? ` · ${m.vehicleNo}` : ""}</option>
              ))}
            </select>
          </Field>
        </>
      )}
    </Modal>
  );
}

/* ── Calling one off ──────────────────────────────────────────────────── */

function CancelModal({ order, busy, onClose, onCancel }) {
  const [reason, setReason] = useState("");
  return (
    <Modal title={`Cancel ${order.orderNo}`} onClose={onClose}
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>Keep the order</button>
        <button className="btn btn-primary" style={{ background: "var(--clay)", borderColor: "var(--clay)" }}
                disabled={busy} onClick={() => onCancel(reason)}>
          {busy ? "Cancelling…" : "Cancel the order"}
        </button>
      </>}>
      <p className="ec-fine" style={{ marginBottom: 10, lineHeight: 1.6 }}>
        The {money(order.grandTotal)} invoice {order.invoiceNo} is called off with it: every piece
        goes back to the batch it came from and the journals it raised are reversed. The customer
        sees the order cancelled, with this reason.
      </p>
      <Field label="Reason">
        <input value={reason} maxLength={300} autoFocus onChange={(e) => setReason(e.target.value)}
               placeholder="Out of stock at the shelf, customer asked, duplicate…" />
      </Field>
    </Modal>
  );
}
