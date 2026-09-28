/**
 * Invoice Merge — a customer's open orders combined into one delivery.
 *
 * What a merge is here, because it decides everything on this screen: the
 * merged invoice is a grouping document. It carries the combined total so the
 * round can be delivered and collected once, and it carries no lines and moves
 * no stock. The source orders keep both — they are what the goods went out on —
 * so they stay on the invoice list, read-only, while Sales Activity shows the
 * merge in their place.
 *
 * Only today's unconfirmed orders can be merged. A confirmed order is already
 * promised to the customer, and yesterday's belongs to a round that has run.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill, Field } from "../components/UI.jsx";
import { moneyFull as money, fmtDate } from "../utils/format.js";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import { getCustomers } from "../services/masterDataService.js";
import mergeService from "../services/invoiceMergeService.js";

const inputStyle = {
  padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)",
  background: "var(--paper)", color: "var(--ink)",
};

export default function InvoiceMerge() {
  const addToast = useToast();
  const confirm = useConfirm();
  const perm = usePermission("merge");

  const [customers, setCustomers] = useState([]);
  const [customerId, setCustomerId] = useState("");
  const [orders, setOrders] = useState([]);
  const [selected, setSelected] = useState([]);
  const [remarks, setRemarks] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const [merges, setMerges] = useState([]);
  const [loadingMerges, setLoadingMerges] = useState(true);

  useEffect(() => {
    getCustomers({ size: 200 })
      .then((r) => setCustomers(r?.items ?? r?.content ?? []))
      .catch(() => setCustomers([]));
  }, []);

  const loadMerges = useCallback(() => {
    setLoadingMerges(true);
    return mergeService.listMerges()
      .then(setMerges)
      .catch((e) => addToast(e?.message || "Could not load today's merges.", "error"))
      .finally(() => setLoadingMerges(false));
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { loadMerges(); }, [loadMerges]);

  const loadOrders = useCallback((id) => {
    if (!id) { setOrders([]); setSelected([]); return; }
    setLoading(true);
    mergeService.getEligible(id)
      .then((rows) => { setOrders(rows); setSelected([]); })
      .catch((e) => { addToast(e?.message || "Could not load orders.", "error"); setOrders([]); })
      .finally(() => setLoading(false));
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps

  const pickCustomer = (id) => { setCustomerId(id); loadOrders(id); };

  const toggle = (id) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  // Everything on this list is already mergeable — the server filtered it to
  // today's unconfirmed orders for one customer — so "all" needs no second
  // opinion about which rows qualify.
  const allSelected  = orders.length > 0 && selected.length === orders.length;
  const someSelected = selected.length > 0 && !allSelected;
  const toggleAll = () =>
    setSelected(allSelected ? [] : orders.map((o) => o.id));

  const chosen = orders.filter((o) => selected.includes(o.id));
  const chosenTotal = useMemo(
    () => chosen.reduce((sum, o) => sum + o.amount, 0), [chosen]);

  const customerName = customers.find(
    (c) => String(c.id ?? c.pidCustomer) === String(customerId))?.name ?? "";

  const doMerge = async () => {
    if (selected.length < 2) {
      addToast("Pick at least two orders — one order merged with nothing is just that order.", "warning");
      return;
    }
    const ok = await confirm({
      title: `Merge ${selected.length} orders into one?`,
      body: `${chosen.map((o) => o.invoiceNo).join(", ")} will be delivered and collected as a single `
          + `invoice for ${money(chosenTotal)}.`,
      detail: "The orders stay on the invoice list as the record of what went out, but become "
            + "read-only. Sales Activity will show the merge in their place.",
      confirmLabel: "Merge orders", tone: "teal",
    });
    if (!ok) return;

    setBusy(true);
    try {
      const m = await mergeService.mergeOrders({
        customerId: Number(customerId), orderIds: selected, remarks });
      addToast(`${m.invoiceNo} created from ${m.sources.length} orders — ${money(m.total)}`);
      setRemarks("");
      loadOrders(customerId);
      loadMerges();
    } catch (e) {
      addToast(e?.message || "Could not merge those orders.", "error");
    } finally {
      setBusy(false);
    }
  };

  const doSplit = async (m) => {
    const ok = await confirm({
      title: `Split ${m.invoiceNo} back into its orders?`,
      body: `Its ${m.sources.length} orders become separate again and editable, each back on Sales `
          + `Activity as its own delivery.`,
      detail: "The merged document is cancelled rather than deleted — it was issued a number and "
            + "somebody may have written it down.",
      confirmLabel: "Split the merge", tone: "danger",
    });
    if (!ok) return;
    try {
      await mergeService.splitMerge(m.id, "Split from the merge screen");
      addToast(`${m.invoiceNo} split back into ${m.sources.length} orders.`);
      loadMerges();
      loadOrders(customerId);
    } catch (e) {
      addToast(e?.message || "Could not split that merge.", "error");
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Invoice Merge</h1>
          <div className="page-sub">
            Combine a customer&rsquo;s open orders into one delivery. The orders remain the record
            of what went out; the merge is what gets delivered and collected.
          </div>
        </div>
      </div>

      <div className="grid-2" style={{ alignItems: "start", gap: 16 }}>
        {/* ── choose ── */}
        <Card eyebrow="Step 1" title="Orders that can be merged"
          right={
            <select value={customerId} onChange={(e) => pickCustomer(e.target.value)}
              style={{ ...inputStyle, minWidth: 220 }} aria-label="Customer">
              <option value="">Choose a customer…</option>
              {customers.map((c) => (
                <option key={c.id ?? c.pidCustomer} value={c.id ?? c.pidCustomer}>
                  {c.name ?? c.customerName}
                </option>
              ))}
            </select>
          }>

          <div style={{ fontSize: 12, color: "var(--ink-soft)", marginBottom: 10 }}>
            Today&rsquo;s orders still at <b>Submitted</b>. A confirmed order is already promised to
            the customer, and an earlier one belongs to a round that has been run — neither can be
            regrouped.
          </div>

          {!customerId ? (
            <div className="empty-state">Choose a customer to see what can be merged.</div>
          ) : loading ? (
            <div className="empty-state">Loading…</div>
          ) : orders.length === 0 ? (
            <div className="empty-state">
              {customerName || "This customer"} has no unconfirmed orders today.
            </div>
          ) : (
            <>
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th style={{ width: 32 }}>
                        {/* Half-ticked while only some are chosen, so the box
                            never claims the list is in a state it is not. */}
                        <input
                          type="checkbox"
                          checked={allSelected}
                          ref={(el) => { if (el) el.indeterminate = someSelected; }}
                          onChange={toggleAll}
                          aria-label={allSelected ? "Clear the selection" : "Select every order"}
                          title={allSelected ? "Clear the selection" : "Select every order"}
                          style={{ cursor: "pointer" }}
                        />
                      </th>
                      <th>Invoice</th><th>Stage</th><th>Payment</th>
                      <th>Lines</th><th>Amount</th><th>Paid</th>
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map((o) => (
                      <tr key={o.id} onClick={() => toggle(o.id)}
                        style={{ cursor: "pointer",
                                 background: selected.includes(o.id) ? "var(--amber-soft)" : "" }}>
                        <td>
                          <input type="checkbox" readOnly checked={selected.includes(o.id)}
                            aria-label={`Include ${o.invoiceNo}`} />
                        </td>
                        <td className="mono" style={{ fontWeight: 700 }}>{o.invoiceNo}</td>
                        <td><Pill tone="amber">{o.stage}</Pill></td>
                        <td>{o.paymentType || "—"}</td>
                        <td className="mono">{o.lineCount}</td>
                        <td className="mono" style={{ fontWeight: 700 }}>{money(o.amount)}</td>
                        <td className="mono" style={{ color: o.paid > 0 ? "var(--sage)" : "var(--ink-soft)" }}>
                          {o.paid > 0 ? money(o.paid) : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end", marginTop: 14 }}>
                <Field label="Note">
                  <input value={remarks} onChange={(e) => setRemarks(e.target.value)}
                    placeholder="Why these are going together" style={{ ...inputStyle, minWidth: 240 }} />
                </Field>
                <div style={{ marginLeft: "auto", textAlign: "right" }}>
                  <div style={{ fontSize: 11, textTransform: "uppercase", letterSpacing: ".06em",
                                color: "var(--ink-soft)", display: "flex", gap: 8,
                                justifyContent: "flex-end", alignItems: "baseline" }}>
                    <span>{selected.length} of {orders.length} selected</span>
                    <button type="button" onClick={toggleAll}
                      style={{ background: "none", border: "none", padding: 0, cursor: "pointer",
                               font: "inherit", color: "var(--teal)", textDecoration: "underline" }}>
                      {allSelected ? "Clear" : "Select all"}
                    </button>
                  </div>
                  <div className="mono" style={{ fontSize: 22, fontWeight: 800, color: "var(--teal)" }}>
                    {money(chosenTotal)}
                  </div>
                </div>
              </div>

              <div style={{ marginTop: 12, display: "flex", gap: 10, alignItems: "center" }}>
                <button className="btn btn-primary" onClick={doMerge}
                  disabled={selected.length < 2 || busy || !perm.canCreate}
                  title={!perm.canCreate ? "You do not have permission to merge invoices" : undefined}>
                  {busy
                    ? "Merging…"
                    : selected.length === 0
                      ? "Merge orders"
                      : `Merge ${selected.length} order${selected.length === 1 ? "" : "s"}`}
                </button>
                {selected.length === 1 && (
                  <span style={{ fontSize: 12, color: "var(--ink-soft)" }}>
                    Pick one more — a merge needs at least two.
                  </span>
                )}
              </div>
            </>
          )}
        </Card>

        {/* ── done ── */}
        <Card eyebrow="Today" title="Merges raised">
          {loadingMerges ? (
            <div className="empty-state">Loading…</div>
          ) : merges.length === 0 ? (
            <div className="empty-state">Nothing has been merged today.</div>
          ) : (
            <div style={{ display: "grid", gap: 12 }}>
              {merges.map((m) => (
                <div key={m.id} style={{
                  padding: "12px 14px", borderRadius: 10, background: "var(--paper)",
                  border: `1px solid ${m.cancelled ? "var(--line)" : "var(--teal)"}`,
                  opacity: m.cancelled ? 0.6 : 1 }}>
                  <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
                    <b className="mono" style={{ fontSize: 14,
                        textDecoration: m.cancelled ? "line-through" : "none" }}>
                      {m.invoiceNo}
                    </b>
                    <Pill tone={m.cancelled ? "grey" : "teal"}>
                      {m.cancelled ? "Split" : m.stage}
                    </Pill>
                    <span style={{ fontSize: 12.5 }}>{m.customerName}</span>
                    <span className="mono" style={{ marginLeft: "auto", fontWeight: 800 }}>
                      {money(m.total)}
                    </span>
                  </div>

                  <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 6 }}>
                    {fmtDate(m.mergeDate)} · {m.sources.length} order{m.sources.length === 1 ? "" : "s"}
                  </div>

                  <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: 8 }}>
                    {m.sources.map((s) => (
                      <span key={s.id} className="mono"
                        style={{ fontSize: 11, padding: "3px 8px", borderRadius: 6,
                                 background: "var(--card)", border: "1px solid var(--line)" }}
                        title={`${money(s.amount)} · ${s.lineCount} lines`
                               + (m.cancelled ? " · released by the split" : " · read-only while merged")}>
                        {s.invoiceNo}
                      </span>
                    ))}
                  </div>

                  {!m.cancelled && perm.canDelete && (
                    <button className="btn btn-ghost btn-sm" style={{ marginTop: 10, fontSize: 11 }}
                      onClick={() => doSplit(m)}>
                      Split back into orders
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
