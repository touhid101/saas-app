import React, { useState, useMemo } from "react";
import { Card, Pill, Field, Tabs } from "../components/UI.jsx";
import { COLLECTIONS, DELIVERY_MEN, DELIVERY_ORDERS } from "../lib/mockData.js";
import { money, num } from "../lib/calc.js";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { round2 } from "../lib/rng.js";

function toCSV(rows, headers) {
  return [headers.join(","), ...rows.map(r => headers.map(h => `"${r[h] ?? ""}"`).join(","))].join("\n");
}
function download(name, text) {
  const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([text], { type: "text/csv" })); a.download = name; a.click();
}

export default function Collection() {
  const [tab, setTab] = useState("Collection List");
  const [collections, setCollections] = useState(COLLECTIONS);
  const { addToast } = useApp();
  const perm = usePermission("collection");

  const addCollection = (c) => { setCollections(list => [c, ...list]); addToast(`Collection ${c.id} recorded`); };

  return (
    <div className="page">
      <div className="page-head">
        <div><h1>Collection Management</h1>
          <div className="page-sub">Record and manage cash collection from delivery men.</div>
        </div>
      </div>
      <Tabs tabs={["Collection List", ...(perm.canCreate ? ["New Collection"] : []), "Summary"]} active={tab} onChange={setTab} />
      {tab === "Collection List" && <CollectionList collections={collections} perm={perm} />}
      {tab === "New Collection"  && <NewCollection onSave={addCollection} />}
      {tab === "Summary"         && <CollectionSummary collections={collections} />}
    </div>
  );
}

function CollectionList({ collections, perm = {} }) {
  const [q, setQ] = useState("");
  const [dmFilter, setDmFilter] = useState("All");
  const list = useMemo(() => {
    let data = collections;
    if (dmFilter !== "All") data = data.filter(c => c.deliveryManId === dmFilter);
    if (q) data = data.filter(c => JSON.stringify(c).toLowerCase().includes(q.toLowerCase()));
    return data;
  }, [collections, q, dmFilter]);

  const totalCollected = list.reduce((s, c) => s + c.collectedAmount, 0);
  const totalOutstanding = list.reduce((s, c) => s + c.outstanding, 0);

  return (
    <div>
      <div className="col-summary-card" style={{ marginBottom: 16 }}>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 20 }}>
          <div><div style={{ fontSize: 11.5, opacity: 0.8 }}>Total Collected</div><div style={{ fontSize: 24, fontFamily: "monospace", fontWeight: 700 }}>{money(totalCollected)}</div></div>
          <div><div style={{ fontSize: 11.5, opacity: 0.8 }}>Outstanding</div><div style={{ fontSize: 24, fontFamily: "monospace", fontWeight: 700 }}>{money(totalOutstanding)}</div></div>
          <div><div style={{ fontSize: 11.5, opacity: 0.8 }}>Records</div><div style={{ fontSize: 24, fontFamily: "monospace", fontWeight: 700 }}>{list.length}</div></div>
        </div>
      </div>
      <Card eyebrow="Collection Records" title="All Collections"
        right={
          <div className="flex-gap">
            <select value={dmFilter} onChange={e => setDmFilter(e.target.value)} style={{ padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)" }}>
              <option value="All">All Delivery Men</option>
              {DELIVERY_MEN.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
            <input placeholder="Search…" value={q} onChange={e => setQ(e.target.value)} style={{ padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", width: 200 }} />
            {perm.canExport && (
              <button className="btn btn-ghost btn-sm" onClick={() => {
                const rows = list.slice(0, 60).map(c => ({ ID: c.id, Invoice: c.saleId, Customer: c.customerName, "Delivery Man": c.deliveryManName || "—", "Invoice Amt": c.invoiceAmount, Collected: c.collectedAmount, Outstanding: c.outstanding, Date: c.collectionDate, Payment: c.paymentType }));
                download("collections.csv", toCSV(rows, ["ID","Invoice","Customer","Delivery Man","Invoice Amt","Collected","Outstanding","Date","Payment"]));
              }}>Export CSV</button>
            )}
          </div>
        }
      >
        <table className="data-table">
          <thead><tr><th>Col. ID</th><th>Invoice</th><th>Customer</th><th>Delivery Man</th><th>Invoice Amt</th><th>Collected</th><th>Outstanding</th><th>Date</th><th>Payment</th><th>Voucher</th><th>Status</th></tr></thead>
          <tbody>
            {list.slice(0, 60).map(c => (
              <tr key={c.id}>
                <td className="mono" style={{ fontWeight: 700 }}>{c.id}</td>
                <td className="mono">{c.saleId}</td>
                <td>{c.customerName}</td>
                <td>{c.deliveryManName || "—"}</td>
                <td className="mono">{money(c.invoiceAmount)}</td>
                <td className="mono" style={{ color: "var(--sage)", fontWeight: 700 }}>{money(c.collectedAmount)}</td>
                <td className="mono" style={{ color: c.outstanding > 0 ? "var(--clay)" : "var(--sage)" }}>{money(c.outstanding)}</td>
                <td className="mono">{c.collectionDate}</td>
                <td><Pill tone="teal">{c.paymentType}</Pill></td>
                <td className="mono">{c.voucherNo}</td>
                <td><Pill tone="sage">{c.status}</Pill></td>
              </tr>
            ))}
          </tbody>
        </table>
        {list.length === 0 && <div className="empty-state">No collections found.</div>}
      </Card>
    </div>
  );
}

function NewCollection({ onSave }) {
  const [dmId, setDmId] = useState(DELIVERY_MEN[0]?.id || "");
  const [deliveryOrderId, setDeliveryOrderId] = useState("");
  const [amount, setAmount] = useState("");
  const [paymentType, setPaymentType] = useState("Cash");
  const [date, setDate] = useState("2026-08-10");
  const [errors, setErrors] = useState({});

  const dmOrders = DELIVERY_ORDERS.filter(d => d.deliveryManId === dmId && d.status !== "Collected" && d.due > 0);
  const selectedOrder = dmOrders.find(d => d.id === deliveryOrderId);

  const submit = () => {
    const err = {};
    if (!dmId) err.dmId = "Select delivery man";
    if (!deliveryOrderId) err.deliveryOrderId = "Select delivery order";
    if (!amount || Number(amount) <= 0) err.amount = "Collection amount must be > 0";
    if (selectedOrder && Number(amount) > selectedOrder.due) err.amount = `Cannot exceed outstanding due: ${money(selectedOrder.due)}`;
    setErrors(err);
    if (Object.keys(err).length) return;
    const id = `COL-${Date.now().toString().slice(-8)}`;
    onSave({
      id, deliveryOrderId, saleId: selectedOrder?.saleId,
      deliveryManId: dmId, deliveryManName: DELIVERY_MEN.find(d => d.id === dmId)?.name,
      customerId: selectedOrder?.customerId, customerName: selectedOrder?.customerName,
      invoiceAmount: selectedOrder?.invoiceAmount || 0,
      collectedAmount: round2(Number(amount)),
      outstanding: round2((selectedOrder?.due || 0) - Number(amount)),
      collectionDate: date, paymentType,
      voucherNo: `VCHR-${Date.now().toString().slice(-6)}`,
      status: "Confirmed", insertedBy: "USR001",
    });
    setDeliveryOrderId(""); setAmount(""); setErrors({});
  };

  return (
    <div className="grid-2" style={{ alignItems: "start" }}>
      <Card eyebrow="Collection Entry" title="New Collection">
        <Field label="Delivery Man *" error={errors.dmId}>
          <select value={dmId} onChange={e => { setDmId(e.target.value); setDeliveryOrderId(""); }} className={errors.dmId ? "field-error" : ""}>
            <option value="">— Select —</option>
            {DELIVERY_MEN.filter(d => d.status === "Active").map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </Field>
        <Field label="Delivery Order *" error={errors.deliveryOrderId}>
          <select value={deliveryOrderId} onChange={e => { setDeliveryOrderId(e.target.value); setAmount(""); }} className={errors.deliveryOrderId ? "field-error" : ""}>
            <option value="">— Select Delivery Order —</option>
            {dmOrders.map(d => <option key={d.id} value={d.id}>{d.id} — {d.customerName} — Due: {money(d.due)}</option>)}
          </select>
        </Field>
        {selectedOrder && (
          <div style={{ background: "var(--paper)", borderRadius: 8, padding: "10px 14px", marginBottom: 12, fontSize: 13 }}>
            <div><b>Invoice Amount:</b> {money(selectedOrder.invoiceAmount)}</div>
            <div><b>Already Paid:</b> {money(selectedOrder.paidAmount)}</div>
            <div style={{ color: "var(--clay)", fontWeight: 700 }}><b>Outstanding Due:</b> {money(selectedOrder.due)}</div>
          </div>
        )}
        <Field label="Collection Amount (৳) *" error={errors.amount}>
          <input type="number" min="0" value={amount} onChange={e => setAmount(e.target.value)} placeholder="Enter amount collected" className={errors.amount ? "field-error" : ""} />
        </Field>
        <Field label="Payment Type">
          <select value={paymentType} onChange={e => setPaymentType(e.target.value)}>
            {["Cash","bKash","Nagad","Bank Transfer"].map(p => <option key={p}>{p}</option>)}
          </select>
        </Field>
        <Field label="Collection Date">
          <input type="date" value={date} onChange={e => setDate(e.target.value)} />
        </Field>
        <button className="btn btn-primary" style={{ width: "100%", marginTop: 8 }} onClick={submit}>Record Collection</button>
      </Card>
      <Card eyebrow="Pending Collections" title="Outstanding Orders">
        <table className="data-table">
          <thead><tr><th>Order</th><th>Customer</th><th>Invoice Amt</th><th>Due</th></tr></thead>
          <tbody>
            {DELIVERY_ORDERS.filter(d => d.due > 0 && d.deliveryManId === dmId).slice(0, 15).map(d => (
              <tr key={d.id} style={{ cursor: "pointer", background: deliveryOrderId === d.id ? "var(--amber-soft)" : "" }} onClick={() => setDeliveryOrderId(d.id)}>
                <td className="mono">{d.id}</td>
                <td>{d.customerName}</td>
                <td className="mono">{money(d.invoiceAmount)}</td>
                <td className="mono" style={{ color: "var(--clay)", fontWeight: 700 }}>{money(d.due)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

function CollectionSummary({ collections }) {
  const byDM = DELIVERY_MEN.map(dm => {
    const cols = collections.filter(c => c.deliveryManId === dm.id);
    return { ...dm, count: cols.length, total: cols.reduce((s, c) => s + c.collectedAmount, 0), outstanding: cols.reduce((s, c) => s + c.outstanding, 0) };
  });
  return (
    <Card eyebrow="Summary" title="Collection by Delivery Man">
      <table className="data-table">
        <thead><tr><th>Delivery Man</th><th>Collections</th><th>Total Collected</th><th>Outstanding</th></tr></thead>
        <tbody>
          {byDM.sort((a, b) => b.total - a.total).map(dm => (
            <tr key={dm.id}>
              <td style={{ fontWeight: 700 }}>{dm.name}</td>
              <td className="mono">{dm.count}</td>
              <td className="mono" style={{ color: "var(--sage)", fontWeight: 700 }}>{money(dm.total)}</td>
              <td className="mono" style={{ color: dm.outstanding > 0 ? "var(--clay)" : "var(--sage)" }}>{money(dm.outstanding)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
