/**
 * Sales Return — goods coming back, against the invoice that sent them out.
 *
 * SRS FR-11. The link to the original invoice is not paperwork: the sale line
 * carries the batch the goods left from, the expiry on that batch, and the price
 * they were sold at. So this screen never lets anyone compose a return by hand —
 * you pick an invoice, the server says what is still returnable on each line,
 * and all that is entered is how much of each is coming back.
 *
 * Two tabs, because they are two different jobs: raising a return, and looking
 * one up afterwards.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
// Two decimals — a refund is money handed back, and `money` rounds to whole taka.
import { moneyFull as money } from "../utils/format.js";
import { Card, Pill, Field, Tabs } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { downloadCSV } from "../utils/csv.js";
import svc, { REASONS } from "../services/salesReturnService.js";
import salesActivity from "../services/salesActivityService.js";

const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);
const P = {
  search: "M11 3a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM21 21l-4.35-4.35",
  undo:   "M3 7v6h6M3 13a9 9 0 1 0 3-7.7L3 8",
};

export default function SalesReturn() {
  const [tab, setTab] = useState("Raise a return");
  const perm = usePermission("salesreturn");
  const tabs = ["Raise a return", "Returns raised"];

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Sales Return</h1>
          <div className="page-sub">
            Goods returned against an invoice — back into the batch they were sold from.
          </div>
        </div>
      </div>
      <Tabs tabs={tabs} active={tab} onChange={setTab} />
      {tab === "Raise a return" && <RaiseReturn perm={perm} />}
      {tab === "Returns raised" && <ReturnsList perm={perm} />}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   RAISE
   ═══════════════════════════════════════════════════════════════════════════ */

function RaiseReturn({ perm }) {
  const addToast = useToast();

  const [q, setQ] = useState("");
  const [invoices, setInvoices] = useState([]);
  const [searching, setSearching] = useState(false);

  const [invoice, setInvoice] = useState(null);   // the returnable view
  const [loadingInvoice, setLoadingInvoice] = useState(false);
  const [qty, setQty] = useState({});             // lineId → string
  const [reason, setReason] = useState("");
  const [payType, setPayType] = useState("CASH");
  const [remarks, setRemarks] = useState("");
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(null);

  /* ── find the invoice ─────────────────────────────────────────────────── */

  const search = useCallback(async (term) => {
    setSearching(true);
    try {
      // Only invoices where goods have actually left. Listing drafts would offer
      // choices that then explain they cannot be returned against.
      const r = await salesActivity.listActivity(
        { ...salesActivity.emptyFilter(), q: term }, 0, 25);
      setInvoices(r.items.filter((o) => svc.RETURNABLE_STAGES.includes(o.stage)));
    } catch (e) {
      addToast(e?.message || "Could not search invoices.", "error");
    } finally {
      setSearching(false);
    }
  }, [addToast]);

  const debounce = useRef(null);
  useEffect(() => {
    clearTimeout(debounce.current);
    debounce.current = setTimeout(() => search(q), q ? 300 : 0);
    return () => clearTimeout(debounce.current);
  }, [q, search]);

  const openInvoice = async (saleId) => {
    setLoadingInvoice(true);
    setDone(null);
    try {
      const inv = await svc.getReturnable(saleId);
      setInvoice(inv);
      setQty({});
    } catch (e) {
      addToast(e?.message || "Could not open that invoice.", "error");
    } finally {
      setLoadingInvoice(false);
    }
  };

  /* ── the return being built ───────────────────────────────────────────── */

  const lines = invoice?.lines ?? [];

  const setLineQty = (line, raw) => {
    // Clamped as it is typed rather than validated on save. The limit is a fact
    // about the invoice, not an opinion, and letting someone fill in a form that
    // is already refused is a worse way to tell them.
    const n = raw === "" ? "" : Math.max(0, Math.min(Number(raw) || 0, line.returnableQty));
    setQty((s) => ({ ...s, [line.lineId]: String(n) }));
  };

  const refund = useMemo(() => lines.reduce((sum, l) => {
    const n = Number(qty[l.lineId]) || 0;
    return sum + n * l.refundPerUnit;
  }, 0), [lines, qty]);

  const chosen = lines.filter((l) => (Number(qty[l.lineId]) || 0) > 0);
  const canSave = invoice?.returnable && chosen.length > 0 && !saving && perm.canCreate;

  const submit = async () => {
    if (!canSave) return;
    setSaving(true);
    try {
      const created = await svc.createReturn({
        originalSaleId: invoice.saleId,
        reason, paymentType: payType, remarks,
        lines: chosen.map((l) => ({ originalLineId: l.lineId, quantity: Number(qty[l.lineId]) })),
      });
      addToast(`Return ${created.invoiceNo} raised — ${money(created.amountTobePaid)} refunded`);
      setDone(created);
      // Re-read rather than adjust locally: the returnable figures have all
      // moved, and a stale screen invites returning the same goods twice.
      await openInvoice(invoice.saleId);
      setReason(""); setRemarks("");
    } catch (e) {
      addToast(e?.message || "Could not raise the return.", "error");
    } finally {
      setSaving(false);
    }
  };

  const inputStyle = { padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--paper)", color: "var(--ink)" };

  return (
    <div style={{ display: "grid", gridTemplateColumns: "minmax(0,1fr)", gap: 16 }}>
      {/* ── pick the invoice ── */}
      <Card eyebrow="Step 1" title="Find the invoice">
        <div className="search-box" style={{ maxWidth: 420, marginBottom: 12 }}>
          <Ic path={P.search} />
          <input placeholder="Invoice number, customer or delivery man…" value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1 }} />
          {q && <button onClick={() => setQ("")} style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>}
        </div>

        {searching ? (
          <div className="empty-state">Searching…</div>
        ) : invoices.length === 0 ? (
          <div className="empty-state">
            No dispatched invoices match. Only invoices past <strong>Confirmed</strong> can be
            returned against — before that, nothing has left the shelf.
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Invoice</th><th>Customer</th><th>Date</th><th>Amount</th><th>Stage</th><th></th></tr></thead>
              <tbody>
                {invoices.map((o) => (
                  <tr key={o.id} style={{ background: invoice?.saleId === o.id ? "var(--amber-soft)" : "" }}>
                    <td className="mono" style={{ fontWeight: 700 }}>{o.invoiceNo}</td>
                    <td>{o.customerName}</td>
                    <td className="mono" style={{ fontSize: 12 }}>{o.date}</td>
                    <td className="mono">{money(o.grandTotal)}</td>
                    <td><Pill tone="teal">{o.stage}</Pill></td>
                    <td>
                      <button className="btn btn-ghost btn-sm" onClick={() => openInvoice(o.id)}
                        style={{ fontSize: 11 }}>
                        {invoice?.saleId === o.id ? "Reload" : "Select"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* ── build the return ── */}
      {loadingInvoice && <div className="empty-state">Loading invoice…</div>}

      {invoice && !loadingInvoice && (
        <Card eyebrow="Step 2" title={`What is coming back — ${invoice.invoiceNo}`}
          right={<Pill tone={invoice.returnable ? "sage" : "clay"}>
            {invoice.returnable ? "Returnable" : "Nothing to return"}
          </Pill>}>

          {!invoice.returnable && (
            <div className="empty-state" role="alert">{invoice.blockedReason}</div>
          )}

          {invoice.returnable && (
            <>
              <div style={{ display: "flex", gap: 18, flexWrap: "wrap", fontSize: 12.5, color: "var(--ink-soft)", marginBottom: 12 }}>
                <span>Customer: <strong style={{ color: "var(--ink)" }}>{invoice.customerName}</strong></span>
                <span>Dated: <strong style={{ color: "var(--ink)" }}>{invoice.transactionDate}</strong></span>
                <span>Invoice total: <strong style={{ color: "var(--ink)" }}>{money(invoice.amountTobePaid)}</strong></span>
                {Number(invoice.invoiceDiscPct) > 0 && (
                  // Said plainly, because the refund will look "wrong" otherwise:
                  // the customer paid less than the line price, so they get less back.
                  <span>Invoice discount: <strong style={{ color: "var(--amber-deep)" }}>
                    {Number(invoice.invoiceDiscPct)}%</strong> — refunds are net of it</span>
                )}
              </div>

              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Product</th><th>Batch</th><th>Expiry</th><th>Unit</th>
                      <th>Sold</th><th>Returned</th><th>Can return</th>
                      <th>Refund / unit</th><th style={{ minWidth: 110 }}>Returning</th><th>Refund</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l) => {
                      const finished = l.returnableQty <= 0;
                      const n = Number(qty[l.lineId]) || 0;
                      return (
                        <tr key={l.lineId} style={{ opacity: finished ? 0.5 : 1 }}>
                          <td style={{ fontWeight: 600 }}>
                            {l.productName}
                            {l.expired && (
                              <span title="This batch is past its expiry — it comes back but cannot be resold"
                                style={{ marginLeft: 6, fontSize: 9, fontWeight: 800, padding: "1px 5px", borderRadius: 4, background: "var(--clay-soft)", color: "var(--clay)" }}>
                                EXPIRED
                              </span>
                            )}
                          </td>
                          <td className="mono" style={{ fontSize: 12 }}>{l.batchNo || "—"}</td>
                          <td className="mono" style={{ fontSize: 12 }}>{l.expiryDate || "—"}</td>
                          <td>{l.salesUnit}</td>
                          <td className="mono">{l.soldQty}</td>
                          <td className="mono" style={{ color: l.alreadyReturned > 0 ? "var(--amber-deep)" : "var(--ink-soft)" }}>
                            {l.alreadyReturned}
                          </td>
                          <td className="mono" style={{ fontWeight: 700 }}>{l.returnableQty}</td>
                          <td className="mono">{money(l.refundPerUnit)}</td>
                          <td>
                            {finished ? (
                              <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>fully returned</span>
                            ) : (
                              <input type="number" min="0" max={l.returnableQty} step="1"
                                value={qty[l.lineId] ?? ""}
                                onChange={(e) => setLineQty(l, e.target.value)}
                                placeholder="0"
                                style={{ ...inputStyle, width: 90, fontFamily: "monospace", fontWeight: 700 }} />
                            )}
                          </td>
                          <td className="mono" style={{ fontWeight: n > 0 ? 700 : 400, color: n > 0 ? "var(--clay)" : "var(--ink-soft)" }}>
                            {money(n * l.refundPerUnit)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end", marginTop: 16 }}>
                <Field label="Reason">
                  <select value={reason} onChange={(e) => setReason(e.target.value)} style={{ minWidth: 200 }}>
                    <option value="">—</option>
                    {REASONS.map((r) => <option key={r}>{r}</option>)}
                  </select>
                </Field>
                <Field label="Refund by">
                  <select value={payType} onChange={(e) => setPayType(e.target.value)}>
                    {["CASH", "CARD", "BKASH", "NAGAD", "BANK", "CREDIT"].map((p) => <option key={p}>{p}</option>)}
                  </select>
                </Field>
                <Field label="Note">
                  <input value={remarks} onChange={(e) => setRemarks(e.target.value)}
                    placeholder="Optional" style={{ minWidth: 220 }} />
                </Field>

                <div style={{ marginLeft: "auto", textAlign: "right" }}>
                  <div style={{ fontSize: 11, color: "var(--ink-soft)", textTransform: "uppercase", letterSpacing: ".06em" }}>
                    Total refund
                  </div>
                  <div className="mono" style={{ fontSize: 22, fontWeight: 800, color: "var(--clay)" }}>
                    {money(refund)}
                  </div>
                </div>
              </div>

              <div style={{ marginTop: 14, display: "flex", gap: 10, alignItems: "center" }}>
                <button className="btn btn-primary" onClick={submit} disabled={!canSave}
                  title={!perm.canCreate ? "You do not have permission to raise returns" : undefined}>
                  <Ic path={P.undo} size={13} />
                  {saving ? "Raising…" : `Raise return for ${chosen.length} line${chosen.length === 1 ? "" : "s"}`}
                </button>
                {chosen.length === 0 && (
                  <span style={{ fontSize: 12, color: "var(--ink-soft)" }}>
                    Enter a quantity against at least one line.
                  </span>
                )}
              </div>
            </>
          )}

          {done && (
            <div style={{ marginTop: 16, padding: "12px 14px", borderRadius: 10, background: "var(--sage-soft)", border: "1px solid var(--sage)" }}>
              <strong style={{ color: "var(--sage)" }}>Return {done.invoiceNo} raised.</strong>{" "}
              <span style={{ fontSize: 13 }}>
                {money(done.amountTobePaid)} refunded, and the stock is back in its batch.
              </span>
            </div>
          )}

          {invoice.priorReturns?.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink-soft)", textTransform: "uppercase", letterSpacing: ".06em", marginBottom: 6 }}>
                Already returned against this invoice
              </div>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                {invoice.priorReturns.map((p) => (
                  <span key={p.returnId} title={p.remarks || ""}
                    style={{ fontSize: 11.5, padding: "4px 9px", borderRadius: 7, background: "var(--paper)", border: "1px solid var(--line)", fontFamily: "monospace" }}>
                    {p.invoiceNo} · {money(p.amount)} · {p.transactionDate}
                  </span>
                ))}
              </div>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   LIST
   ═══════════════════════════════════════════════════════════════════════════ */

function ReturnsList({ perm }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [meta, setMeta] = useState({ total: 0, totalPages: 0 });
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState({ q: "", from: "", to: "" });
  const [loading, setLoading] = useState(true);
  const [expand, setExpand] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return svc.listReturns({ ...filter, page, size: 25 })
      .then((r) => { setRows(r.items); setMeta({ total: r.total, totalPages: r.totalPages }); })
      .catch((e) => addToast(e?.message || "Could not load returns.", "error"))
      .finally(() => setLoading(false));
  }, [filter, page]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const id = setTimeout(load, filter.q ? 300 : 0);
    return () => clearTimeout(id);
  }, [load, filter.q]);

  const set = (patch) => { setFilter((f) => ({ ...f, ...patch })); setPage(0); };

  const exportCSV = () => downloadCSV("sales-returns.csv",
    rows.map((r) => ({
      Return: r.invoiceNo, Against: r.againstInvoiceNo || "", Date: r.transactionDate,
      Customer: r.customerName, Refund: r.refund, "Refund by": r.paymentType || "", Note: r.remarks || "",
    })),
    ["Return", "Against", "Date", "Customer", "Refund", "Refund by", "Note"]);

  const inputStyle = { padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--paper)", color: "var(--ink)" };

  return (
    <Card eyebrow="History" title="Returns raised"
      right={
        <div className="flex-gap">
          <input placeholder="Return no, invoice or customer…" value={filter.q}
            onChange={(e) => set({ q: e.target.value })} style={{ ...inputStyle, width: 230 }} />
          <input type="date" value={filter.from} onChange={(e) => set({ from: e.target.value })} style={inputStyle} />
          <input type="date" value={filter.to} onChange={(e) => set({ to: e.target.value })} style={inputStyle} />
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" onClick={exportCSV} disabled={!rows.length}>Export page</button>
          )}
        </div>
      }>
      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="empty-state">No returns have been raised yet.</div>
      ) : (
        <>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr><th style={{ width: 32 }}></th><th>Return</th><th>Against invoice</th>
                    <th>Date</th><th>Customer</th><th>Refund</th><th>Refund by</th><th>Note</th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <React.Fragment key={r.id}>
                    <tr style={{ background: expand === r.id ? "var(--amber-soft)" : "" }}>
                      <td>
                        <button onClick={() => setExpand(expand === r.id ? null : r.id)}
                          style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)", padding: 4 }}>
                          <Ic path={expand === r.id ? "m18 15-6-6-6 6" : "m6 9 6 6 6-6"} />
                        </button>
                      </td>
                      <td className="mono" style={{ fontWeight: 700 }}>{r.invoiceNo}</td>
                      <td className="mono" style={{ color: "var(--teal)" }}>{r.againstInvoiceNo || "—"}</td>
                      <td className="mono" style={{ fontSize: 12 }}>{r.transactionDate}</td>
                      <td>{r.customerName}</td>
                      <td className="mono" style={{ fontWeight: 700, color: "var(--clay)" }}>{money(r.refund)}</td>
                      <td>{r.paymentType || "—"}</td>
                      <td style={{ fontSize: 12, color: "var(--ink-soft)", maxWidth: 240, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {r.remarks || "—"}
                      </td>
                    </tr>
                    {expand === r.id && (
                      <tr>
                        <td colSpan={8} style={{ background: "var(--paper)", padding: "12px 16px" }}>
                          <table className="data-table" style={{ fontSize: 12.5 }}>
                            <thead><tr><th>Product</th><th>Batch</th><th>Expiry</th><th>Qty</th><th>Unit</th><th>Refund</th></tr></thead>
                            <tbody>
                              {r.lines.map((l) => (
                                <tr key={l.lineId}>
                                  <td style={{ fontWeight: 600 }}>{l.productName}</td>
                                  <td className="mono">{l.batchNo || "—"}</td>
                                  <td className="mono">{l.expiryDate || "—"}</td>
                                  <td className="mono">{l.qty}</td>
                                  <td>{l.unit}</td>
                                  <td className="mono">{money(l.total)}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </td>
                      </tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>

          {meta.totalPages > 1 && (
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", paddingTop: 12 }}>
              <span style={{ fontSize: 13, color: "var(--ink-soft)" }}>Showing {rows.length} of {meta.total}</span>
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
  );
}
