/**
 * Purchase Return — goods going back to the supplier.
 *
 * The mirror of Sales Return, with one difference that shapes the whole screen:
 * this takes stock *out*. A customer's return is already at the counter and can
 * always be accepted. Sending goods back needs them still on the shelf, so each
 * line has two limits — what was bought and not yet returned, and what the batch
 * still holds — and the screen shows both, plus which one is binding.
 *
 * Without that, a storekeeper raises a debit note for stock dispensed last week,
 * the posting refuses at the last step, and the supplier has already been told
 * the goods are coming back.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
// Two decimals — a credit note is money, and `money` rounds to whole taka.
import { moneyFull as money } from "../utils/format.js";
import { Card, Pill, Field, Tabs } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { downloadCSV } from "../utils/csv.js";
import svc, { REASONS, RETURNABLE_STATUS } from "../services/purchaseReturnService.js";
import { getPurchases } from "../services/purchaseService.js";

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

export default function PurchaseReturn() {
  const [tab, setTab] = useState("Raise a return");
  const perm = usePermission("purchasereturn");

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Purchase Return</h1>
          <div className="page-sub">
            Goods returned to the supplier — out of the batch they were received into.
          </div>
        </div>
      </div>
      <Tabs tabs={["Raise a return", "Returns raised"]} active={tab} onChange={setTab} />
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
  const [purchases, setPurchases] = useState([]);
  const [searching, setSearching] = useState(false);

  const [invoice, setInvoice] = useState(null);
  const [loadingInvoice, setLoadingInvoice] = useState(false);
  const [qty, setQty] = useState({});
  const [reason, setReason] = useState("");
  const [payType, setPayType] = useState("ADJUSTMENT");
  const [remarks, setRemarks] = useState("");
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(null);

  /* ── find the purchase ────────────────────────────────────────────────── */

  const search = useCallback(async (term) => {
    setSearching(true);
    try {
      // Approved only. An unapproved purchase has received nothing into stock,
      // so offering it would be offering something the save refuses.
      const r = await getPurchases({ search: term || null, status: RETURNABLE_STATUS, size: 25 });
      setPurchases(r?.items ?? r?.content ?? []);
    } catch (e) {
      addToast(e?.message || "Could not search purchases.", "error");
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

  const openInvoice = async (purchaseId) => {
    setLoadingInvoice(true);
    setDone(null);
    try {
      const inv = await svc.getReturnable(purchaseId);
      setInvoice(inv);
      setQty({});
    } catch (e) {
      addToast(e?.message || "Could not open that purchase.", "error");
    } finally {
      setLoadingInvoice(false);
    }
  };

  /* ── the return being built ───────────────────────────────────────────── */

  const lines = invoice?.lines ?? [];

  const setLineQty = (line, raw) => {
    // Clamped as typed. The ceiling is a fact about stock and paperwork, not an
    // opinion — letting someone fill in a form that is already refused is a
    // worse way to tell them.
    const n = raw === "" ? "" : Math.max(0, Math.min(Number(raw) || 0, line.returnableQty));
    setQty((s) => ({ ...s, [line.lineId]: String(n) }));
  };

  const credit = useMemo(() => lines.reduce((sum, l) => {
    const n = Number(qty[l.lineId]) || 0;
    return sum + n * l.creditPerUnit;
  }, 0), [lines, qty]);

  const chosen = lines.filter((l) => (Number(qty[l.lineId]) || 0) > 0);
  const canSave = invoice?.returnable && chosen.length > 0 && !saving && perm.canCreate;

  const submit = async () => {
    if (!canSave) return;
    setSaving(true);
    try {
      const created = await svc.createReturn({
        originalPurchaseId: invoice.purchaseId,
        reason, paymentType: payType, remarks,
        lines: chosen.map((l) => ({ originalLineId: l.lineId, quantity: Number(qty[l.lineId]) })),
      });
      addToast(`Debit note ${created.invoiceNo} raised — ${money(created.amountTobePaid)} credited`);
      setDone(created);
      // Re-read: stock has moved, so every returnable figure on screen is stale
      // and a second note from the same view could oversend.
      await openInvoice(invoice.purchaseId);
      setReason(""); setRemarks("");
    } catch (e) {
      addToast(e?.message || "Could not raise the return.", "error");
    } finally {
      setSaving(false);
    }
  };

  const inputStyle = { padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--paper)", color: "var(--ink)" };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      {/* ── pick the purchase ── */}
      <Card eyebrow="Step 1" title="Find the purchase">
        <div className="search-box" style={{ maxWidth: 420, marginBottom: 12 }}>
          <Ic path={P.search} />
          <input placeholder="Invoice number or supplier…" value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1 }} />
          {q && <button onClick={() => setQ("")} style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>}
        </div>

        {searching ? (
          <div className="empty-state">Searching…</div>
        ) : purchases.length === 0 ? (
          <div className="empty-state">
            No approved purchases match. Only an <strong>approved</strong> purchase has received
            stock — before that, correct the purchase itself rather than returning against it.
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>Invoice</th><th>Supplier</th><th>Date</th><th>Amount</th><th></th></tr></thead>
              <tbody>
                {purchases.map((p) => (
                  <tr key={p.id} style={{ background: invoice?.purchaseId === p.id ? "var(--amber-soft)" : "" }}>
                    <td className="mono" style={{ fontWeight: 700 }}>{p.invoiceNo}</td>
                    <td>{p.supplierName || "—"}</td>
                    <td className="mono" style={{ fontSize: 12 }}>{p.transactionDate}</td>
                    {/* The purchase LIST dto spells it `amountToBePaid`; the transaction dto
                            spells it `amountTobePaid`. Accept either — reading the wrong one
                            silently shows the pre-discount figure. */}
                    <td className="mono">{money(p.amountToBePaid ?? p.amountTobePaid ?? p.totalAmount)}</td>
                    <td>
                      <button className="btn btn-ghost btn-sm" onClick={() => openInvoice(p.id)} style={{ fontSize: 11 }}>
                        {invoice?.purchaseId === p.id ? "Reload" : "Select"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {loadingInvoice && <div className="empty-state">Loading purchase…</div>}

      {invoice && !loadingInvoice && (
        <Card eyebrow="Step 2" title={`What is going back — ${invoice.invoiceNo}`}
          right={<Pill tone={invoice.returnable ? "sage" : "clay"}>
            {invoice.returnable ? "Returnable" : "Nothing to return"}
          </Pill>}>

          {!invoice.returnable && (
            <div className="empty-state" role="alert">{invoice.blockedReason}</div>
          )}

          {invoice.returnable && (
            <>
              <div style={{ display: "flex", gap: 18, flexWrap: "wrap", fontSize: 12.5, color: "var(--ink-soft)", marginBottom: 12 }}>
                <span>Supplier: <strong style={{ color: "var(--ink)" }}>{invoice.supplierName}</strong></span>
                <span>Dated: <strong style={{ color: "var(--ink)" }}>{invoice.transactionDate}</strong></span>
                <span>Invoice total: <strong style={{ color: "var(--ink)" }}>{money(invoice.amountTobePaid)}</strong></span>
                {Number(invoice.invoiceDiscPct) > 0 && (
                  // Stated, because the credit will otherwise look short: you
                  // paid less than the line price, so you claim back less.
                  <span>Invoice discount: <strong style={{ color: "var(--amber-deep)" }}>
                    {Number(invoice.invoiceDiscPct)}%</strong> — credits are net of it</span>
                )}
              </div>

              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Product</th><th>Batch</th><th>Expiry</th><th>Unit</th>
                      <th>Bought</th><th>Returned</th><th>In Stock</th><th>Can Return</th>
                      <th>Credit / unit</th><th style={{ minWidth: 110 }}>Returning</th><th>Credit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l) => {
                      const finished = l.returnableQty <= 0;
                      const n = Number(qty[l.lineId]) || 0;
                      return (
                        <tr key={l.lineId} style={{ opacity: finished ? 0.55 : 1 }}>
                          <td style={{ fontWeight: 600 }}>
                            {l.productName}
                            {l.expired && (
                              <span title="This batch is past its expiry"
                                style={{ marginLeft: 6, fontSize: 9, fontWeight: 800, padding: "1px 5px", borderRadius: 4, background: "var(--clay-soft)", color: "var(--clay)" }}>
                                EXPIRED
                              </span>
                            )}
                          </td>
                          <td className="mono" style={{ fontSize: 12 }}>{l.batchNo || "—"}</td>
                          <td className="mono" style={{ fontSize: 12 }}>{l.expiryDate || "—"}</td>
                          <td>{l.purchaseUnit}</td>
                          <td className="mono">{l.purchasedQty}</td>
                          <td className="mono" style={{ color: l.alreadyReturned > 0 ? "var(--amber-deep)" : "var(--ink-soft)" }}>
                            {l.alreadyReturned}
                          </td>
                          {/* Shown beside the paperwork figure so a smaller
                              "can return" is explained rather than puzzling. */}
                          <td className="mono" style={{ fontSize: 12, color: "var(--ink-soft)" }}
                            title={l.inStockDisplay}>
                            {l.inStockDisplay}
                          </td>
                          <td className="mono" style={{ fontWeight: 700 }}>
                            {l.returnableQty}
                            {l.limitedBy && (
                              <span title={l.limitedBy}
                                style={{ marginLeft: 5, fontSize: 9, fontWeight: 800, padding: "1px 5px", borderRadius: 4, background: "var(--amber-soft)", color: "var(--amber-deep)", cursor: "help" }}>
                                STOCK
                              </span>
                            )}
                          </td>
                          <td className="mono">{money(l.creditPerUnit)}</td>
                          <td>
                            {finished ? (
                              <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>
                                {l.notYetReturned <= 0 ? "fully returned" : "none in stock"}
                              </span>
                            ) : (
                              <input type="number" min="0" max={l.returnableQty} step="1"
                                value={qty[l.lineId] ?? ""}
                                onChange={(e) => setLineQty(l, e.target.value)}
                                placeholder="0"
                                style={{ ...inputStyle, width: 90, fontFamily: "monospace", fontWeight: 700 }} />
                            )}
                          </td>
                          <td className="mono" style={{ fontWeight: n > 0 ? 700 : 400, color: n > 0 ? "var(--sage)" : "var(--ink-soft)" }}>
                            {money(n * l.creditPerUnit)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {lines.some((l) => l.limitedBy) && (
                <div style={{ marginTop: 10, fontSize: 12, color: "var(--ink-soft)" }}>
                  Lines marked <strong style={{ color: "var(--amber-deep)" }}>STOCK</strong> are
                  capped by what is left on the shelf — the rest has been sold, and goods that have
                  left cannot be sent back.
                </div>
              )}

              <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end", marginTop: 16 }}>
                <Field label="Reason">
                  <select value={reason} onChange={(e) => setReason(e.target.value)} style={{ minWidth: 200 }}>
                    <option value="">—</option>
                    {REASONS.map((r) => <option key={r}>{r}</option>)}
                  </select>
                </Field>
                <Field label="Credit as">
                  <select value={payType} onChange={(e) => setPayType(e.target.value)}>
                    {["ADJUSTMENT", "CASH", "BANK", "REPLACEMENT"].map((p) => <option key={p}>{p}</option>)}
                  </select>
                </Field>
                <Field label="Note">
                  <input value={remarks} onChange={(e) => setRemarks(e.target.value)}
                    placeholder="Optional" style={{ minWidth: 220 }} />
                </Field>

                <div style={{ marginLeft: "auto", textAlign: "right" }}>
                  <div style={{ fontSize: 11, color: "var(--ink-soft)", textTransform: "uppercase", letterSpacing: ".06em" }}>
                    Total credit
                  </div>
                  <div className="mono" style={{ fontSize: 22, fontWeight: 800, color: "var(--sage)" }}>
                    {money(credit)}
                  </div>
                </div>
              </div>

              <div style={{ marginTop: 14, display: "flex", gap: 10, alignItems: "center" }}>
                <button className="btn btn-primary" onClick={submit} disabled={!canSave}
                  title={!perm.canCreate ? "You do not have permission to raise purchase returns" : undefined}>
                  <Ic path={P.undo} size={13} />
                  {saving ? "Raising…" : `Raise debit note for ${chosen.length} line${chosen.length === 1 ? "" : "s"}`}
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
              <strong style={{ color: "var(--sage)" }}>Debit note {done.invoiceNo} raised.</strong>{" "}
              <span style={{ fontSize: 13 }}>
                {money(done.amountTobePaid)} credited, and the stock is out of its batch.
              </span>
            </div>
          )}

          {invoice.priorReturns?.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink-soft)", textTransform: "uppercase", letterSpacing: ".06em", marginBottom: 6 }}>
                Already returned against this purchase
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

  const exportCSV = () => downloadCSV("purchase-returns.csv",
    rows.map((r) => ({
      "Debit Note": r.invoiceNo, Against: r.againstInvoiceNo || "", Date: r.transactionDate,
      Supplier: r.supplierName, Credit: r.credit, "Credit As": r.paymentType || "", Note: r.remarks || "",
    })),
    ["Debit Note", "Against", "Date", "Supplier", "Credit", "Credit As", "Note"]);

  const inputStyle = { padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)", background: "var(--paper)", color: "var(--ink)" };

  return (
    <Card eyebrow="History" title="Returns raised"
      right={
        <div className="flex-gap">
          <input placeholder="Note no, purchase or supplier…" value={filter.q}
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
        <div className="empty-state">No purchase returns have been raised yet.</div>
      ) : (
        <>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr><th style={{ width: 32 }}></th><th>Debit Note</th><th>Against Purchase</th>
                    <th>Date</th><th>Supplier</th><th>Credit</th><th>Credit As</th><th>Note</th></tr>
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
                      <td>{r.supplierName}</td>
                      <td className="mono" style={{ fontWeight: 700, color: "var(--sage)" }}>{money(r.credit)}</td>
                      <td>{r.paymentType || "—"}</td>
                      <td style={{ fontSize: 12, color: "var(--ink-soft)", maxWidth: 240, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {r.remarks || "—"}
                      </td>
                    </tr>
                    {expand === r.id && (
                      <tr>
                        <td colSpan={8} style={{ background: "var(--paper)", padding: "12px 16px" }}>
                          <table className="data-table" style={{ fontSize: 12.5 }}>
                            <thead><tr><th>Product</th><th>Batch</th><th>Expiry</th><th>Qty</th><th>Unit</th><th>Credit</th></tr></thead>
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
