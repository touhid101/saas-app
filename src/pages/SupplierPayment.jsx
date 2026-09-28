/**
 * Supplier Payment — money out, what is still owed, and how suppliers perform.
 *
 * Four tabs because they are four jobs: seeing who is owed what, tracking the
 * money suppliers hold of ours, recording that they were paid, and judging the
 * relationship over time.
 *
 * The Advances tab is the one that runs backwards. On a discounted invoice the
 * gross is sometimes handed over and the supplier returns the discount later,
 * so that slice is a receivable, not a payment — netted against what we owe
 * rather than folded into it.
 *
 * The number that matters here — outstanding — is not computed on this screen.
 * It comes from the same database view the Supplier Outstanding report reads, so
 * the two cannot disagree. Before that view existed, outstanding was derived
 * separately in each report and every copy counted draft purchases as debt.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
// Two decimals — these are payments, and `money` rounds to whole taka.
import { moneyFull as money, fmtDate } from "../utils/format.js";
import { Card, Pill, Field, Tabs, Modal } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { downloadCSV } from "../utils/csv.js";
import svc, { PAYMENT_MODES, BANK_MODES, CHEQUE_MODES } from "../services/supplierPaymentService.js";

const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);
const P = {
  wallet: "M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zM16 12h.01",
  ledger: "M9 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9zM13 2v7h7M9 13h6M9 17h4",
  warn:   "M12 9v4M12 17h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
};

const inputStyle = {
  padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)",
  background: "var(--paper)", color: "var(--ink)",
};

export default function SupplierPayment() {
  const [tab, setTab] = useState("Outstanding");
  const perm = usePermission("supplierpayment");

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Supplier Payment</h1>
          <div className="page-sub">
            What each supplier is owed, what has been paid, and how the account has run.
          </div>
        </div>
      </div>
      <Tabs tabs={["Outstanding", "Advances", "Payments", "Performance"]} active={tab} onChange={setTab} />
      {tab === "Outstanding" && <Outstanding perm={perm} />}
      {tab === "Advances"    && <Advances perm={perm} />}
      {tab === "Payments"    && <PaymentHistory perm={perm} />}
      {tab === "Performance" && <Performance perm={perm} />}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   OUTSTANDING
   ═══════════════════════════════════════════════════════════════════════════ */

function Outstanding({ perm }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [q, setQ] = useState("");
  const [dueOnly, setDueOnly] = useState(true);
  const [loading, setLoading] = useState(true);
  const [paying, setPaying] = useState(null);     // supplier account
  const [statement, setStatement] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return svc.getOutstanding({ q, dueOnly })
      .then(setRows)
      .catch((e) => addToast(e?.message || "Could not load supplier balances.", "error"))
      .finally(() => setLoading(false));
  }, [q, dueOnly]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const id = setTimeout(load, q ? 300 : 0);
    return () => clearTimeout(id);
  }, [load, q]);

  const totals = useMemo(() => rows.reduce((t, r) => ({
    outstanding: t.outstanding + r.outstanding,
    onAccount: t.onAccount + r.onAccount,
    advance: t.advance + r.advanceBalance,
    d0: t.d0 + r.ageing.d0, d31: t.d31 + r.ageing.d31,
    d61: t.d61 + r.ageing.d61, d90: t.d90 + r.ageing.d90,
  }), { outstanding: 0, onAccount: 0, advance: 0, d0: 0, d31: 0, d61: 0, d90: 0 }), [rows]);

  const exportCSV = () => downloadCSV("supplier-outstanding.csv",
    rows.map((r) => ({
      Supplier: r.supplierName, Mobile: r.mobileNo || "", Invoices: r.invoices,
      Billed: r.billed, Paid: r.paid, "Return Credit": r.returnCredit,
      "Unapplied Credit": r.unappliedCredit,
      Unapplied: r.onAccount, Advance: r.advanceBalance,
      Outstanding: r.outstanding, Net: r.netPosition,
      "0-30": r.ageing.d0, "31-60": r.ageing.d31,
      "61-90": r.ageing.d61, "90+": r.ageing.d90,
      "Last Purchase": r.lastPurchase || "", "Last Paid": r.lastPaymentDate || "",
    })),
    ["Supplier", "Mobile", "Invoices", "Billed", "Paid", "Return Credit", "Unapplied Credit", "Unapplied",
     "Advance", "Outstanding", "Net", "0-30", "31-60", "61-90", "90+",
     "Last Purchase", "Last Paid"]);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      {/* ── the position at a glance ── */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(150px,1fr))", gap: 12 }}>
        <Stat label="Total outstanding" value={money(totals.outstanding)} tone="clay" big />
        <Stat label="0–30 days"    value={money(totals.d0)} />
        <Stat label="31–60 days"   value={money(totals.d31)} tone={totals.d31 > 0 ? "amber" : undefined} />
        <Stat label="61–90 days"   value={money(totals.d61)} tone={totals.d61 > 0 ? "amber" : undefined} />
        <Stat label="Over 90 days" value={money(totals.d90)} tone={totals.d90 > 0 ? "clay" : undefined} />
        {totals.onAccount > 0 && (
          <Stat label="Paid, unapplied" value={money(totals.onAccount)} tone="sage" />
        )}
        {totals.advance > 0 && (
          <Stat label="Advance held by suppliers" value={money(totals.advance)} tone="sage" />
        )}
      </div>

      <Card eyebrow="Accounts payable" title="What we owe suppliers"
        right={
          <div className="flex-gap">
            <input placeholder="Supplier name…" value={q} onChange={(e) => setQ(e.target.value)}
              style={{ ...inputStyle, width: 200 }} />
            <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12.5, cursor: "pointer" }}>
              <input type="checkbox" checked={dueOnly} onChange={(e) => setDueOnly(e.target.checked)} />
              Only those with a balance
            </label>
            {perm.canExport && (
              <button className="btn btn-ghost btn-sm" onClick={exportCSV} disabled={!rows.length}>Export</button>
            )}
          </div>
        }>

        <div style={{ fontSize: 12, color: "var(--ink-soft)", marginBottom: 10 }}>
          Approved purchases only, net of every payment recorded against the supplier and of
          purchase returns credited to the account. Ageing counts days since the invoice —
          no credit terms are recorded anywhere in the system, so nothing here can honestly
          be called overdue. <b>Unapplied</b> is money paid on account and not yet matched to a
          bill, so the buckets less unapplied give outstanding. <b>Advance</b> is our money the
          supplier is holding — an invoice discount handed over early, say — and it runs the other
          way, so <b>Net</b> is outstanding less advance: negative means they owe us.
        </div>

        {loading ? (
          <div className="empty-state">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="empty-state">
            {dueOnly ? "Nothing is outstanding to any supplier." : "No supplier accounts to show."}
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Supplier</th><th>Invoices</th><th>Billed</th><th>Paid</th>
                  <th>Credit</th><th>0–30</th><th>31–60</th><th>61–90</th><th>90+</th>
                  <th>Credit Note</th><th>Unapplied</th><th>Advance</th>
                  <th>Outstanding</th><th>Net</th><th></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.supplierId}>
                    <td>
                      <button onClick={() => setStatement(r)}
                        style={{ background: "none", border: "none", padding: 0, cursor: "pointer",
                                 fontWeight: 600, color: "var(--teal)", textAlign: "left" }}
                        title="Open the running statement">
                        {r.supplierName}
                      </button>
                      {r.mobileNo && (
                        <div className="mono" style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>{r.mobileNo}</div>
                      )}
                      {r.creditWarning && (
                        <div style={{ display: "flex", alignItems: "center", gap: 4, marginTop: 3,
                                      fontSize: 10.5, fontWeight: 700, color: "var(--clay)" }}>
                          <Ic path={P.warn} size={11} />{r.creditWarning}
                        </div>
                      )}
                    </td>
                    <td className="mono">{r.invoices}</td>
                    <td className="mono">{money(r.billed)}</td>
                    <td className="mono" style={{ color: "var(--sage)" }}>{money(r.paid)}</td>
                    <td className="mono" style={{ color: r.returnCredit > 0 ? "var(--amber-deep)" : "var(--ink-soft)" }}>
                      {money(r.returnCredit)}
                    </td>
                    <Aged v={r.ageing.d0} />
                    <Aged v={r.ageing.d31} tone="amber" />
                    <Aged v={r.ageing.d61} tone="amber" />
                    <Aged v={r.ageing.d90} tone="clay" />
                    {/* A credit note raised against the account rather than one
                        invoice. It is already off Outstanding, which is why it
                        sits outside the ageing buckets: those are invoice-level
                        and nobody decided which invoice this cancels. */}
                    <td className="mono"
                        style={{ color: r.unappliedCredit > 0 ? "var(--amber-deep)" : "var(--ink-soft)",
                                 fontWeight: r.unappliedCredit > 0 ? 700 : 400 }}
                        title={r.unappliedCredit > 0
                          ? `${money(r.unappliedCredit)} of return credit is not tied to any invoice. `
                            + "It is already deducted from Outstanding and will come off the next "
                            + "purchase unless you apply it to a specific bill."
                          : "No unapplied return credit"}>
                      {r.unappliedCredit > 0 ? money(r.unappliedCredit) : "—"}
                    </td>
                    <td className="mono" style={{ color: r.onAccount > 0 ? "var(--sage)" : "var(--ink-soft)",
                                                  fontWeight: r.onAccount > 0 ? 700 : 400 }}
                      title={r.onAccount > 0
                        ? "Paid on account and not matched to any one bill — it reduces the balance but settles no invoice"
                        : undefined}>
                      {r.onAccount > 0 ? money(r.onAccount) : "—"}
                    </td>
                    <td className="mono" style={{ color: r.advanceBalance > 0 ? "var(--sage)" : "var(--ink-soft)",
                                                  fontWeight: r.advanceBalance > 0 ? 700 : 400 }}
                      title={r.advanceBalance > 0
                        ? "Our money the supplier is holding — recoverable, so it runs the other way"
                        : undefined}>
                      {r.advanceBalance > 0 ? money(r.advanceBalance) : "—"}
                    </td>
                    <td className="mono" style={{ fontWeight: 800,
                        color: r.outstanding > 0 ? "var(--clay)" : "var(--ink-soft)" }}>
                      {money(r.outstanding)}
                    </td>
                    <td className="mono" style={{ fontWeight: 800,
                        color: r.netPosition < 0 ? "var(--sage)" : "var(--ink)" }}
                      title={r.netPosition < 0
                        ? "Negative: on net the supplier owes us"
                        : "Positive: on net we owe the supplier"}>
                      {money(r.netPosition)}
                    </td>
                    <td>
                      {perm.canCreate && r.outstanding > 0 && (
                        <button className="btn btn-primary btn-sm" onClick={() => setPaying(r)}
                          style={{ fontSize: 11, whiteSpace: "nowrap" }}>
                          <Ic path={P.wallet} size={12} /> Pay
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {paying && (
        <PayModal account={paying} onClose={() => setPaying(null)}
          onDone={() => { setPaying(null); load(); }} />
      )}
      {statement && (
        <StatementModal account={statement} onClose={() => setStatement(null)} />
      )}
    </div>
  );
}

const Aged = ({ v, tone }) => (
  <td className="mono" style={{ fontSize: 12, color: v > 0 ? `var(--${tone || "ink"})` : "var(--ink-soft)",
                                fontWeight: v > 0 && tone ? 700 : 400 }}>
    {v > 0 ? money(v) : "—"}
  </td>
);

function Stat({ label, value, tone, big }) {
  return (
    <div style={{ padding: "12px 14px", borderRadius: 10, border: "1px solid var(--line)", background: "var(--paper)" }}>
      <div style={{ fontSize: 10.5, textTransform: "uppercase", letterSpacing: ".06em", color: "var(--ink-soft)" }}>
        {label}
      </div>
      <div className="mono" style={{ fontSize: big ? 22 : 17, fontWeight: 800, marginTop: 3,
                                     color: tone ? `var(--${tone})` : "var(--ink)" }}>
        {value}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PAY
   ═══════════════════════════════════════════════════════════════════════════ */

function PayModal({ account, onClose, onDone }) {
  const addToast = useToast();
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [purchaseId, setPurchaseId] = useState("");   // "" = on account
  const [amount, setAmount] = useState("");
  const [mode, setMode] = useState("CASH");
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [bankName, setBankName] = useState("");
  const [chequeNo, setChequeNo] = useState("");
  const [chequeDate, setChequeDate] = useState("");
  const [referenceNo, setReferenceNo] = useState("");
  const [remarks, setRemarks] = useState("");
  // The trade practice: hand over the gross on a discounted invoice, and the
  // supplier returns the discount later.
  const [payDiscount, setPayDiscount] = useState(false);

  useEffect(() => {
    svc.getSupplier(account.supplierId)
      .then((d) => {
        setDetail(d);
        // Oldest open bill preselected — that is the order they should be paid
        // in, and the list already arrives that way.
        const first = d.openInvoices[0];
        if (first) { setPurchaseId(String(first.purchaseId)); setAmount(String(first.due.toFixed(2))); }
      })
      .catch((e) => addToast(e?.message || "Could not load the supplier account.", "error"))
      .finally(() => setLoading(false));
  }, [account.supplierId]);   // eslint-disable-line react-hooks/exhaustive-deps

  const open = detail?.openInvoices ?? [];
  const selected = open.find((i) => String(i.purchaseId) === purchaseId) || null;

  const pickInvoice = (id) => {
    setPurchaseId(id);
    const inv = open.find((i) => String(i.purchaseId) === id);
    // Prefill the exact balance. Overwritable, but the common case is settling
    // a bill in full, and retyping a figure already on screen invites a typo.
    setAmount(inv ? String(inv.due.toFixed(2)) : "");
    // The tick belongs to the invoice, not to the form: moving to a bill with
    // no discount must not leave it set and quietly book an advance.
    setPayDiscount(false);
  };

  const discount = selected?.invoiceDiscAmt ?? 0;
  const canPayDiscount = discount > 0;

  const toggleDiscount = (on) => {
    setPayDiscount(on);
    if (!selected) return;
    // The amount follows the tick, so what is on screen is always what will be
    // handed over. Leaving the operator to add the discount by hand is how the
    // advance ends up a few paisa off the figure the supplier owes back.
    setAmount(String((on ? selected.due + discount : selected.due).toFixed(2)));
  };

  const n = Number(amount) || 0;
  // The advance is the slice that settles no bill.
  const advance = payDiscount ? discount : Math.max(0, n - (selected ? selected.due : n));
  const settling = n - advance;
  const overPay = selected && settling - selected.due > 0.01;
  const needsCheque = CHEQUE_MODES.includes(mode);
  const needsBank = BANK_MODES.includes(mode);
  const canSave = n > 0 && !overPay && !saving && (!needsCheque || chequeNo.trim());

  const submit = async () => {
    if (!canSave) return;
    setSaving(true);
    try {
      const p = await svc.pay({
        supplierId: account.supplierId,
        purchaseId: purchaseId || null,
        paymentDate: date, amount: n, paymentMode: mode,
        // The flag rather than the figure when the tick is on: the server then
        // takes the advance from the invoice itself, so what is booked as
        // recoverable is the discount on the invoice, not a number typed here.
        payInvoiceDiscount: payDiscount || undefined,
        advanceAmount: !payDiscount && advance > 0 ? advance : undefined,
        bankName, chequeNo, chequeDate, referenceNo, remarks,
      });
      addToast(p.advanceAmount > 0
        ? `${p.voucherNo} — ${money(p.amount)} paid, ${money(p.advanceAmount)} recoverable`
        : `${p.voucherNo} — ${money(p.amount)} paid to ${account.supplierName}`);
      onDone();
    } catch (e) {
      addToast(e?.message || "Could not record the payment.", "error");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={`Pay ${account.supplierName}`} onClose={onClose} width={780}
      footer={
        <div style={{ display: "flex", gap: 10, alignItems: "center", width: "100%" }}>
          <div style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
            Outstanding <b className="mono" style={{ color: "var(--clay)" }}>{money(account.outstanding)}</b>
            {account.onAccount > 0 && (
              <> · <b className="mono" style={{ color: "var(--sage)" }}>{money(account.onAccount)}</b> already
              paid on account and unapplied</>
            )}
          </div>
          <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
            <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button className="btn btn-primary" onClick={submit} disabled={!canSave}>
              {saving ? "Recording…" : `Pay ${money(n)}`}
            </button>
          </div>
        </div>
      }>

      {loading ? (
        <div className="empty-state">Loading account…</div>
      ) : (
        <div style={{ display: "grid", gap: 14 }}>
          {/* ── which bill ── */}
          <div>
            <div style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase",
                          letterSpacing: ".06em", color: "var(--ink-soft)", marginBottom: 6 }}>
              What is being settled
            </div>
            {open.length === 0 ? (
              <div className="empty-state" style={{ padding: 12 }}>
                No approved purchase has a balance. Anything paid now is recorded on account.
              </div>
            ) : (
              <div className="table-wrap" style={{ maxHeight: 210, overflowY: "auto" }}>
                <table className="data-table">
                  <thead>
                    <tr><th style={{ width: 28 }}></th><th>Invoice</th><th>Date</th><th>Age</th>
                        <th>Billed</th><th>Paid</th><th>Credit</th><th>Due</th></tr>
                  </thead>
                  <tbody>
                    {open.map((i) => (
                      <tr key={i.purchaseId} onClick={() => pickInvoice(String(i.purchaseId))}
                        style={{ cursor: "pointer",
                                 background: purchaseId === String(i.purchaseId) ? "var(--amber-soft)" : "" }}>
                        <td><input type="radio" readOnly checked={purchaseId === String(i.purchaseId)} /></td>
                        <td className="mono" style={{ fontWeight: 700 }}>{i.invoiceNo}</td>
                        <td className="mono" style={{ fontSize: 12 }}>{i.transactionDate}</td>
                        <td className="mono" style={{ fontSize: 12,
                            color: i.ageDays > 60 ? "var(--clay)" : "var(--ink-soft)" }}>
                          {i.ageDays}d
                        </td>
                        <td className="mono">{money(i.billed)}</td>
                        <td className="mono" style={{ color: "var(--sage)" }}>
                          {money(i.paidAtInvoice + i.paidLater)}
                        </td>
                        <td className="mono" style={{ color: i.returnCredit > 0 ? "var(--amber-deep)" : "var(--ink-soft)" }}>
                          {i.returnCredit > 0 ? money(i.returnCredit) : "—"}
                        </td>
                        <td className="mono" style={{ fontWeight: 700, color: "var(--clay)" }}>{money(i.due)}</td>
                      </tr>
                    ))}
                    <tr onClick={() => pickInvoice("")}
                      style={{ cursor: "pointer", background: purchaseId === "" ? "var(--amber-soft)" : "" }}>
                      <td><input type="radio" readOnly checked={purchaseId === ""} /></td>
                      <td colSpan={7} style={{ fontStyle: "italic", color: "var(--ink-soft)" }}>
                        On account — not against any one bill
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* ── pay the discount across too ── */}
          {canPayDiscount && (
            <label style={{ display: "flex", gap: 9, alignItems: "flex-start", cursor: "pointer",
                            padding: "10px 12px", borderRadius: 9,
                            background: payDiscount ? "var(--sage-soft)" : "var(--paper)",
                            border: `1px solid ${payDiscount ? "var(--sage)" : "var(--line)"}` }}>
              <input type="checkbox" checked={payDiscount} style={{ marginTop: 2 }}
                onChange={(e) => toggleDiscount(e.target.checked)} />
              <span style={{ fontSize: 12.5, lineHeight: 1.5 }}>
                <b>Pay the invoice discount too — {money(discount)}</b>
                <br />
                <span style={{ color: "var(--ink-soft)" }}>
                  {selected.invoiceNo} was discounted by {money(discount)}. Ticking this hands over
                  the gross {money(selected.due + discount)} and records the {money(discount)} as an
                  advance <b>the supplier owes back</b> — it does not settle any more of the bill.
                </span>
              </span>
            </label>
          )}

          {/* ── the payment ── */}
          <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            <Field label="Amount" error={overPay
              ? `Only ${money(selected.due)} is outstanding, and ${money(settling - selected.due)} of this is not covered by an advance.`
              : undefined}>
              <input type="number" min="0" step="0.01" value={amount}
                onChange={(e) => setAmount(e.target.value)}
                style={{ ...inputStyle, width: 140, fontFamily: "monospace", fontWeight: 700,
                         borderColor: overPay ? "var(--clay)" : "var(--line)" }} />
            </Field>
            <Field label="Paid on">
              <input type="date" value={date} max={new Date().toISOString().slice(0, 10)}
                onChange={(e) => setDate(e.target.value)} style={inputStyle} />
            </Field>
            <Field label="Mode">
              <select value={mode} onChange={(e) => setMode(e.target.value)} style={{ minWidth: 130 }}>
                {PAYMENT_MODES.map((m) => <option key={m}>{m}</option>)}
              </select>
            </Field>
            {needsBank && (
              <Field label="Bank">
                <input value={bankName} onChange={(e) => setBankName(e.target.value)}
                  placeholder="Bank name" style={{ ...inputStyle, width: 170 }} />
              </Field>
            )}
            {needsCheque && (
              <>
                <Field label="Cheque no">
                  <input value={chequeNo} onChange={(e) => setChequeNo(e.target.value)}
                    placeholder="Required" style={{ ...inputStyle, width: 140 }} />
                </Field>
                <Field label="Cheque date">
                  <input type="date" value={chequeDate} onChange={(e) => setChequeDate(e.target.value)}
                    style={inputStyle} />
                </Field>
              </>
            )}
            {!needsCheque && (
              <Field label="Reference">
                <input value={referenceNo} onChange={(e) => setReferenceNo(e.target.value)}
                  placeholder="Txn ID, slip no" style={{ ...inputStyle, width: 170 }} />
              </Field>
            )}
            <Field label="Note">
              <input value={remarks} onChange={(e) => setRemarks(e.target.value)}
                placeholder="Optional" style={{ ...inputStyle, width: 200 }} />
            </Field>
          </div>

          {advance > 0 && (
            <div style={{ fontSize: 12.5, padding: "9px 12px", borderRadius: 8,
                          background: "var(--sage-soft)", border: "1px solid var(--sage)" }}>
              Of {money(n)}, <b>{money(settling)}</b>{" "}
              {selected ? <>settles {selected.invoiceNo}</> : "goes on account"} and{" "}
              <b>{money(advance)}</b> is an advance {account.supplierName} owes back. It shows on the
              Advances tab until it is recovered.
            </div>
          )}

          {purchaseId === "" && n > 0 && advance === 0 && (
            <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>
              Recorded on account: it reduces what {account.supplierName} is owed overall, but no
              single invoice is marked settled by it.
            </div>
          )}

          {detail?.advance?.balance > 0 && (
            <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>
              {account.supplierName} already holds <b className="mono" style={{ color: "var(--sage)" }}>
              {money(detail.advance.balance)}</b> of ours from earlier advances.
            </div>
          )}

          {detail?.recentPayments?.length > 0 && (
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase",
                            letterSpacing: ".06em", color: "var(--ink-soft)", marginBottom: 6 }}>
                Recent payments
              </div>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                {detail.recentPayments.map((p) => (
                  <span key={p.id} title={p.remarks || ""}
                    style={{ fontSize: 11.5, padding: "4px 9px", borderRadius: 7, fontFamily: "monospace",
                             background: "var(--paper)", border: "1px solid var(--line)",
                             textDecoration: p.cancelled ? "line-through" : "none",
                             opacity: p.cancelled ? 0.55 : 1 }}>
                    {p.voucherNo} · {money(p.amount)} · {p.paymentDate}
                    {p.purchaseInvoiceNo ? ` · ${p.purchaseInvoiceNo}` : " · on account"}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   STATEMENT
   ═══════════════════════════════════════════════════════════════════════════ */

function StatementModal({ account, onClose }) {
  const addToast = useToast();
  const [lines, setLines] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    svc.getStatement(account.supplierId)
      .then(setLines)
      .catch((e) => addToast(e?.message || "Could not load the statement.", "error"))
      .finally(() => setLoading(false));
  }, [account.supplierId]);   // eslint-disable-line react-hooks/exhaustive-deps

  const tone = {
    PURCHASE: "clay", RETURN: "amber", PAYMENT: "sage",
    // Neither moves the balance on its own, so neither is coloured like money.
    ADVANCE: "teal", RECOVERY: "teal", "ADV CREDIT": "sage",
  };

  return (
    <Modal title={`Statement — ${account.supplierName}`} onClose={onClose} width={820}
      footer={
        <div style={{ display: "flex", width: "100%", alignItems: "center" }}>
          <span style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
            Closing balance{" "}
            <b className="mono" style={{ color: "var(--clay)" }}>
              {money(lines.length ? lines[lines.length - 1].balance : 0)}
            </b>
          </span>
          <button className="btn btn-ghost" style={{ marginLeft: "auto" }}
            onClick={() => downloadCSV(`statement-${account.supplierName}.csv`,
              lines.map((l) => ({
                Date: l.date, Type: l.kind, Document: l.docNo,
                Debit: l.debit, Credit: l.credit, Balance: l.balance, Note: l.note || "",
              })),
              ["Date", "Type", "Document", "Debit", "Credit", "Balance", "Note"])}
            disabled={!lines.length}>
            Export
          </button>
        </div>
      }>
      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : lines.length === 0 ? (
        <div className="empty-state">Nothing has been booked against this supplier yet.</div>
      ) : (
        <>
          <div style={{ fontSize: 12, color: "var(--ink-soft)", marginBottom: 10 }}>
            Purchases raise the balance; payments and returns credited to the account reduce it.
            Returns refunded in cash are not shown — that money came back separately and does not
            change what is owed. An <b>advance</b> is listed at zero because it settles no bill;
            it becomes money the supplier owes back, tracked on the Advances tab.
          </div>
          <div className="table-wrap" style={{ maxHeight: 420, overflowY: "auto" }}>
            <table className="data-table">
              <thead>
                <tr><th>Date</th><th>Type</th><th>Document</th><th>Mode</th>
                    <th>Debit</th><th>Credit</th><th>Balance</th></tr>
              </thead>
              <tbody>
                {lines.map((l, i) => (
                  <tr key={`${l.kind}-${l.docNo}-${i}`}>
                    <td className="mono" style={{ fontSize: 12 }}>{l.date}</td>
                    <td><Pill tone={tone[l.kind] || "grey"}>{l.kind}</Pill></td>
                    <td className="mono" style={{ fontWeight: 600 }}>{l.docNo}</td>
                    <td style={{ fontSize: 12, color: "var(--ink-soft)" }}>{l.mode || "—"}</td>
                    <td className="mono">{l.debit > 0 ? money(l.debit) : "—"}</td>
                    <td className="mono" style={{ color: "var(--sage)" }}>
                      {l.credit > 0 ? money(l.credit) : "—"}
                    </td>
                    <td className="mono" style={{ fontWeight: 700 }}>{money(l.balance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   HISTORY
   ═══════════════════════════════════════════════════════════════════════════ */

function PaymentHistory({ perm }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [meta, setMeta] = useState({ total: 0, totalPages: 0 });
  const [page, setPage] = useState(0);
  const [f, setF] = useState({ q: "", from: "", to: "", mode: "All", includeCancelled: false });
  const [loading, setLoading] = useState(true);
  const [cancelling, setCancelling] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return svc.listPayments({ ...f, page, size: 25 })
      .then((r) => { setRows(r.items); setMeta({ total: r.total, totalPages: r.totalPages }); })
      .catch((e) => addToast(e?.message || "Could not load payments.", "error"))
      .finally(() => setLoading(false));
  }, [f, page]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const id = setTimeout(load, f.q ? 300 : 0);
    return () => clearTimeout(id);
  }, [load, f.q]);

  const set = (patch) => { setF((s) => ({ ...s, ...patch })); setPage(0); };

  const live = rows.filter((r) => !r.cancelled);
  const total = live.reduce((s, r) => s + r.amount, 0);

  return (
    <Card eyebrow="Ledger" title="Payments made"
      right={
        <div className="flex-gap">
          <input placeholder="Voucher, cheque, reference…" value={f.q}
            onChange={(e) => set({ q: e.target.value })} style={{ ...inputStyle, width: 200 }} />
          <input type="date" value={f.from} onChange={(e) => set({ from: e.target.value })} style={inputStyle} />
          <input type="date" value={f.to} onChange={(e) => set({ to: e.target.value })} style={inputStyle} />
          <select value={f.mode} onChange={(e) => set({ mode: e.target.value })} style={inputStyle}>
            <option>All</option>
            {PAYMENT_MODES.map((m) => <option key={m}>{m}</option>)}
          </select>
          <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12.5, cursor: "pointer" }}>
            <input type="checkbox" checked={f.includeCancelled}
              onChange={(e) => set({ includeCancelled: e.target.checked })} />
            Show cancelled
          </label>
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" disabled={!rows.length}
              onClick={() => downloadCSV("supplier-payments.csv",
                rows.map((r) => ({
                  Voucher: r.voucherNo, Date: r.paymentDate, Supplier: r.supplierName,
                  Against: r.purchaseInvoiceNo || "on account", Mode: r.paymentMode,
                  Amount: r.cancelled ? 0 : r.amount, Advance: r.advanceAmount,
                  Type: r.isRecovery ? "RECOVERY" : "PAYMENT",
                  State: r.cancelled ? "CANCELLED" : "LIVE",
                  Note: r.cancelReason || r.remarks || "",
                })),
                ["Voucher", "Date", "Supplier", "Against", "Mode", "Amount", "Advance",
                 "Type", "State", "Note"])}>
              Export page
            </button>
          )}
        </div>
      }>

      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="empty-state">No payments match. Suppliers are paid from the Outstanding tab.</div>
      ) : (
        <>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr><th>Voucher</th><th>Date</th><th>Supplier</th><th>Against</th>
                    <th>Mode</th><th>Cheque / Ref</th><th>Amount</th><th>Advance</th>
                    <th>Note</th><th></th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} style={{ opacity: r.cancelled ? 0.5 : 1 }}>
                    <td className="mono" style={{ fontWeight: 700,
                        textDecoration: r.cancelled ? "line-through" : "none" }}>
                      {r.voucherNo}
                    </td>
                    <td className="mono" style={{ fontSize: 12 }}>{r.paymentDate}</td>
                    <td>{r.supplierName}</td>
                    <td className="mono" style={{ color: r.purchaseInvoiceNo ? "var(--teal)" : "var(--ink-soft)" }}>
                      {r.purchaseInvoiceNo || "on account"}
                    </td>
                    <td><Pill tone="grey">{r.paymentMode}</Pill></td>
                    <td className="mono" style={{ fontSize: 12 }}>{r.chequeNo || r.referenceNo || "—"}</td>
                    <td className="mono" style={{ fontWeight: 700,
                        color: r.cancelled ? "var(--ink-soft)" : r.isRecovery ? "var(--teal)" : "var(--sage)" }}
                      title={r.isRecovery ? "Money back from the supplier" : undefined}>
                      {r.isRecovery ? `(${money(r.amount)})` : money(r.amount)}
                    </td>
                    <td className="mono" style={{ color: r.advanceAmount > 0 ? "var(--sage)" : "var(--ink-soft)",
                                                  fontWeight: r.advanceAmount > 0 ? 700 : 400 }}>
                      {r.advanceAmount > 0 ? money(r.advanceAmount) : "—"}
                    </td>
                    <td style={{ fontSize: 12, color: "var(--ink-soft)", maxWidth: 220,
                                 overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {r.cancelled ? `Cancelled — ${r.cancelReason}` : (r.remarks || "—")}
                    </td>
                    <td>
                      {perm.canEdit && !r.cancelled && !r.fromInvoice && (
                        <button className="btn btn-ghost btn-sm" style={{ fontSize: 11 }}
                          onClick={() => setCancelling(r)}>Reverse</button>
                      )}
                      {r.fromInvoice && (
                        <span style={{ fontSize: 10.5, color: "var(--ink-soft)" }}
                          title="Mirrored from the cash keyed on the purchase — correct it there">
                          from invoice
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", paddingTop: 12 }}>
            <span style={{ fontSize: 13, color: "var(--ink-soft)" }}>
              {rows.length} of {meta.total} · live on this page{" "}
              <b className="mono" style={{ color: "var(--sage)" }}>{money(total)}</b>
            </span>
            {meta.totalPages > 1 && (
              <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                <button className="btn btn-ghost btn-sm" disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}>← Prev</button>
                <span style={{ fontSize: 13 }}>Page {page + 1} / {meta.totalPages}</span>
                <button className="btn btn-ghost btn-sm" disabled={page + 1 >= meta.totalPages}
                  onClick={() => setPage((p) => p + 1)}>Next →</button>
              </div>
            )}
          </div>
        </>
      )}

      {cancelling && (
        <CancelModal payment={cancelling} onClose={() => setCancelling(null)}
          onDone={() => { setCancelling(null); load(); }} />
      )}
    </Card>
  );
}

function CancelModal({ payment, onClose, onDone }) {
  const addToast = useToast();
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!reason.trim()) return;
    setBusy(true);
    try {
      await svc.cancelPayment(payment.id, reason.trim());
      addToast(`${payment.voucherNo} reversed.`);
      onDone();
    } catch (e) {
      addToast(e?.message || "Could not reverse the payment.", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={`Reverse ${payment.voucherNo}`} onClose={onClose} width={480}
      footer={
        <div style={{ display: "flex", gap: 8, marginLeft: "auto" }}>
          <button className="btn btn-ghost" onClick={onClose}>Keep it</button>
          <button className="btn btn-primary" onClick={submit} disabled={!reason.trim() || busy}>
            {busy ? "Reversing…" : "Reverse payment"}
          </button>
        </div>
      }>
      <p style={{ fontSize: 13, marginBottom: 12 }}>
        {money(payment.amount)} to <b>{payment.supplierName}</b>
        {payment.purchaseInvoiceNo ? <> against <b className="mono">{payment.purchaseInvoiceNo}</b></> : " on account"}.
        Reversing puts the amount back on their outstanding balance.
      </p>
      <p style={{ fontSize: 12, color: "var(--ink-soft)", marginBottom: 10 }}>
        The voucher is kept, not deleted — a bounced cheque or a duplicate entry is
        something the next person to look will need to see.
      </p>
      <Field label="Why">
        <input value={reason} onChange={(e) => setReason(e.target.value)} autoFocus
          placeholder="Cheque bounced, entered twice…" style={{ ...inputStyle, width: "100%" }} />
      </Field>
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PERFORMANCE
   ═══════════════════════════════════════════════════════════════════════════ */

function Performance({ perm }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [range, setRange] = useState({ from: "", to: "", q: "" });
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    return svc.getPerformance(range)
      .then(setRows)
      .catch((e) => addToast(e?.message || "Could not load performance.", "error"))
      .finally(() => setLoading(false));
  }, [range]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const id = setTimeout(load, range.q ? 300 : 0);
    return () => clearTimeout(id);
  }, [load, range.q]);

  const set = (patch) => setRange((s) => ({ ...s, ...patch }));
  const maxSpend = Math.max(1, ...rows.map((r) => r.billed));

  return (
    <Card eyebrow="Analysis" title="Supplier performance"
      right={
        <div className="flex-gap">
          <input placeholder="Supplier…" value={range.q}
            onChange={(e) => set({ q: e.target.value })} style={{ ...inputStyle, width: 170 }} />
          <input type="date" value={range.from} onChange={(e) => set({ from: e.target.value })} style={inputStyle} />
          <input type="date" value={range.to} onChange={(e) => set({ to: e.target.value })} style={inputStyle} />
          {perm.canExport && (
            <button className="btn btn-ghost btn-sm" disabled={!rows.length}
              onClick={() => downloadCSV("supplier-performance.csv",
                rows.map((r) => ({
                  Supplier: r.supplierName, Invoices: r.invoices, Billed: r.billed,
                  Paid: r.paid, Outstanding: r.due, Returns: r.returnCount,
                  Returned: r.returned, "Return %": r.returnPct,
                  "Avg Days To Pay": r.avgDaysToPay ?? "",
                  "Last Purchase": r.lastPurchase || "",
                })),
                ["Supplier", "Invoices", "Billed", "Paid", "Outstanding", "Returns",
                 "Returned", "Return %", "Avg Days To Pay", "Last Purchase"])}>
              Export
            </button>
          )}
        </div>
      }>

      <div style={{ fontSize: 12, color: "var(--ink-soft)", marginBottom: 10 }}>
        <b>Return %</b> is the value sent back over the value bought — the nearest thing to a
        quality score this data can honestly give.{" "}
        <b>Days to pay</b> is weighted by amount, so one large late cheque is not hidden behind a
        dozen small prompt ones; it measures us rather than the supplier, and it is blank where
        nothing in the window was paid against a named bill.
      </div>

      {loading ? (
        <div className="empty-state">Loading…</div>
      ) : rows.length === 0 ? (
        <div className="empty-state">
          No supplier activity in this window. Only approved purchases count.
        </div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Supplier</th><th>Share of spend</th><th>Invoices</th><th>Billed</th>
                <th>Paid</th><th>Outstanding</th><th>Returns</th><th>Return %</th>
                <th>Days to pay</th><th>Last Purchase</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.supplierId}>
                  <td style={{ fontWeight: 600 }}>
                    {r.supplierName}
                    {r.mobileNo && (
                      <div className="mono" style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>{r.mobileNo}</div>
                    )}
                  </td>
                  <td style={{ minWidth: 90 }}>
                    <div style={{ height: 6, borderRadius: 3, background: "var(--line)", overflow: "hidden" }}>
                      <div style={{ height: "100%", width: `${(r.billed / maxSpend) * 100}%`,
                                    background: "var(--teal)" }} />
                    </div>
                  </td>
                  <td className="mono">{r.invoices}</td>
                  <td className="mono" style={{ fontWeight: 700 }}>{money(r.billed)}</td>
                  <td className="mono" style={{ color: "var(--sage)" }}>{money(r.paid)}</td>
                  <td className="mono" style={{ color: r.due > 0 ? "var(--clay)" : "var(--ink-soft)" }}>
                    {money(r.due)}
                  </td>
                  <td className="mono">{r.returnCount || "—"}</td>
                  <td className="mono" style={{ fontWeight: r.returnPct >= 5 ? 700 : 400,
                      color: r.returnPct >= 5 ? "var(--clay)" : r.returnPct > 0 ? "var(--amber-deep)" : "var(--ink-soft)" }}>
                    {r.returnPct > 0 ? `${r.returnPct}%` : "—"}
                  </td>
                  <td className="mono">
                    {r.avgDaysToPay == null
                      ? <span style={{ color: "var(--ink-soft)" }} title="Nothing in this window was paid against a named invoice">—</span>
                      : `${r.avgDaysToPay}d`}
                  </td>
                  <td className="mono" style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>
                    {r.lastPurchase ? fmtDate(r.lastPurchase) : "—"}
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

/* ═══════════════════════════════════════════════════════════════════════════
   ADVANCES — our money, sitting with the supplier
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Money paid beyond the bills, and what has come back.
 *
 * Netted per supplier rather than listed per voucher: a supplier returns a sum,
 * not a particular payment, so tying a recovery to one voucher would be an
 * allocation nobody made. The voucher-level detail lives in the Supplier
 * Advance Register report, which is what you chase from.
 */
function Advances({ perm }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [openOnly, setOpenOnly] = useState(true);
  const [loading, setLoading] = useState(true);
  const [recovering, setRecovering] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return svc.getAdvances({ openOnly })
      .then(setRows)
      .catch((e) => addToast(e?.message || "Could not load advances.", "error"))
      .finally(() => setLoading(false));
  }, [openOnly]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load(); }, [load]);

  const held = rows.reduce((t, r) => t + r.balance, 0);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(170px,1fr))", gap: 12 }}>
        <Stat label="Held by suppliers" value={money(held)} tone="sage" big />
        <Stat label="Advanced" value={money(rows.reduce((t, r) => t + r.advanced, 0))} />
        <Stat label="Recovered" value={money(rows.reduce((t, r) => t + r.recovered, 0))} />
      </div>

      <Card eyebrow="Receivable" title="Advances suppliers hold of ours"
        right={
          <div className="flex-gap">
            <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12.5, cursor: "pointer" }}>
              <input type="checkbox" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} />
              Only what is still held
            </label>
            {perm.canExport && (
              <button className="btn btn-ghost btn-sm" disabled={!rows.length}
                onClick={() => downloadCSV("supplier-advances.csv",
                  rows.map((r) => ({
                    Supplier: r.supplierName, Advanced: r.advanced, Recovered: r.recovered,
                    Balance: r.balance, "First Advance": r.firstAdvance || "",
                    "Last Movement": r.lastMovement || "",
                  })),
                  ["Supplier", "Advanced", "Recovered", "Balance", "First Advance", "Last Movement"])}>
                Export
              </button>
            )}
          </div>
        }>

        <div style={{ fontSize: 12, color: "var(--ink-soft)", marginBottom: 10 }}>
          Money paid over and above the bills — most often an invoice discount handed across on the
          understanding the supplier returns it. This is a <b>receivable</b>: it runs the opposite
          way to everything else on this screen, and it is why a supplier can show a negative net
          position while still having unpaid invoices.
        </div>

        {loading ? (
          <div className="empty-state">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="empty-state">
            No supplier is holding an advance. One is created by ticking{" "}
            <b>Pay the invoice discount too</b> when paying a discounted bill, or by entering an
            advance amount on any payment.
          </div>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr><th>Supplier</th><th>Advanced</th><th>Recovered</th><th>Still Held</th>
                    <th>Since</th><th>Last Movement</th><th></th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.supplierId}>
                    <td style={{ fontWeight: 600 }}>{r.supplierName}</td>
                    <td className="mono">{money(r.advanced)}</td>
                    <td className="mono" style={{ color: r.recovered > 0 ? "var(--teal)" : "var(--ink-soft)" }}>
                      {r.recovered > 0 ? money(r.recovered) : "—"}
                    </td>
                    <td className="mono" style={{ fontWeight: 800,
                        color: r.balance > 0 ? "var(--sage)" : "var(--ink-soft)" }}>
                      {money(r.balance)}
                    </td>
                    <td className="mono" style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>
                      {r.firstAdvance ? fmtDate(r.firstAdvance) : "—"}
                    </td>
                    <td className="mono" style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>
                      {r.lastMovement ? fmtDate(r.lastMovement) : "—"}
                    </td>
                    <td>
                      {perm.canCreate && r.balance > 0 && (
                        <button className="btn btn-primary btn-sm" style={{ fontSize: 11, whiteSpace: "nowrap" }}
                          onClick={() => setRecovering(r)}>
                          Recover
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {recovering && (
        <RecoverModal advance={recovering} onClose={() => setRecovering(null)}
          onDone={() => { setRecovering(null); load(); }} />
      )}
    </div>
  );
}

/**
 * Getting an advance back.
 *
 * The two routes are not the same event and the form does not let them blur:
 * money returning, or the advance being taken as credit on a later bill. The
 * second moves no cash, so it must name the invoice it settles — recorded as a
 * cash receipt it would show money arriving that never did.
 */
function RecoverModal({ advance, onClose, onDone }) {
  const addToast = useToast();
  const [how, setHow] = useState("MONEY");        // MONEY | CREDIT
  const [amount, setAmount] = useState(String(advance.balance.toFixed(2)));
  const [mode, setMode] = useState("CASH");
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [purchaseId, setPurchaseId] = useState("");
  const [bills, setBills] = useState([]);
  const [referenceNo, setReferenceNo] = useState("");
  const [remarks, setRemarks] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // Only needed for the credit route, but fetched up front so switching to it
    // does not stall on a request.
    svc.getInvoices(advance.supplierId, { openOnly: true })
      .then(setBills)
      .catch(() => setBills([]));
  }, [advance.supplierId]);

  const chooseCredit = (on) => {
    setHow(on ? "CREDIT" : "MONEY");
    setMode(on ? "ADJUSTMENT" : "CASH");
    setPurchaseId("");
  };

  const bill = bills.find((b) => String(b.purchaseId) === purchaseId) || null;
  const n = Number(amount) || 0;
  const overHeld = n - advance.balance > 0.01;
  const overBill = bill && n - bill.due > 0.01;
  const canSave = n > 0 && !overHeld && !overBill && !busy
                  && (how === "MONEY" || purchaseId !== "");

  const submit = async () => {
    if (!canSave) return;
    setBusy(true);
    try {
      const r = await svc.recoverAdvance({
        supplierId: advance.supplierId,
        purchaseId: how === "CREDIT" ? Number(purchaseId) : null,
        recoveryDate: date, amount: n, paymentMode: mode,
        referenceNo, remarks,
      });
      addToast(`${r.voucherNo} — ${money(r.amount)} recovered from ${advance.supplierName}`);
      onDone();
    } catch (e) {
      addToast(e?.message || "Could not record the recovery.", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={`Recover from ${advance.supplierName}`} onClose={onClose} width={620}
      footer={
        <div style={{ display: "flex", width: "100%", alignItems: "center", gap: 10 }}>
          <span style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
            Held <b className="mono" style={{ color: "var(--sage)" }}>{money(advance.balance)}</b>
          </span>
          <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
            <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
            <button className="btn btn-primary" onClick={submit} disabled={!canSave}>
              {busy ? "Recording…" : `Recover ${money(n)}`}
            </button>
          </div>
        </div>
      }>
      <div style={{ display: "grid", gap: 14 }}>
        <div style={{ display: "grid", gap: 8 }}>
          <label style={{ display: "flex", gap: 9, alignItems: "flex-start", cursor: "pointer",
                          padding: "10px 12px", borderRadius: 9,
                          background: how === "MONEY" ? "var(--amber-soft)" : "var(--paper)",
                          border: `1px solid ${how === "MONEY" ? "var(--amber-deep)" : "var(--line)"}` }}>
            <input type="radio" checked={how === "MONEY"} onChange={() => chooseCredit(false)}
              style={{ marginTop: 2 }} />
            <span style={{ fontSize: 12.5, lineHeight: 1.5 }}>
              <b>They paid it back</b><br />
              <span style={{ color: "var(--ink-soft)" }}>
                Money returning to the business. No invoice is touched.
              </span>
            </span>
          </label>
          <label style={{ display: "flex", gap: 9, alignItems: "flex-start", cursor: "pointer",
                          padding: "10px 12px", borderRadius: 9,
                          background: how === "CREDIT" ? "var(--amber-soft)" : "var(--paper)",
                          border: `1px solid ${how === "CREDIT" ? "var(--amber-deep)" : "var(--line)"}` }}>
            <input type="radio" checked={how === "CREDIT"} onChange={() => chooseCredit(true)}
              style={{ marginTop: 2 }} />
            <span style={{ fontSize: 12.5, lineHeight: 1.5 }}>
              <b>Credited against a later bill</b><br />
              <span style={{ color: "var(--ink-soft)" }}>
                No money moves — the advance is used up settling an invoice.
              </span>
            </span>
          </label>
        </div>

        {how === "CREDIT" && (
          bills.length === 0 ? (
            <div className="empty-state" style={{ padding: 12 }}>
              No approved bill has a balance to credit it against. Take it back in money instead,
              or wait for the next invoice.
            </div>
          ) : (
            <Field label="Credit against">
              <select value={purchaseId} onChange={(e) => {
                setPurchaseId(e.target.value);
                const b = bills.find((x) => String(x.purchaseId) === e.target.value);
                // Never more than either limit — the bill cannot take more than
                // it owes, and we cannot recover more than was advanced.
                if (b) setAmount(String(Math.min(b.due, advance.balance).toFixed(2)));
              }} style={{ minWidth: 320 }}>
                <option value="">Choose the invoice…</option>
                {bills.map((b) => (
                  <option key={b.purchaseId} value={b.purchaseId}>
                    {b.invoiceNo} · {b.transactionDate} · {money(b.due)} due
                  </option>
                ))}
              </select>
            </Field>
          )
        )}

        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <Field label="Amount" error={
            overHeld ? `Only ${money(advance.balance)} is held.`
            : overBill ? `${bill.invoiceNo} only has ${money(bill.due)} outstanding.`
            : undefined}>
            <input type="number" min="0" step="0.01" value={amount}
              onChange={(e) => setAmount(e.target.value)}
              style={{ ...inputStyle, width: 140, fontFamily: "monospace", fontWeight: 700,
                       borderColor: (overHeld || overBill) ? "var(--clay)" : "var(--line)" }} />
          </Field>
          <Field label="Received on">
            <input type="date" value={date} max={new Date().toISOString().slice(0, 10)}
              onChange={(e) => setDate(e.target.value)} style={inputStyle} />
          </Field>
          {how === "MONEY" && (
            <>
              <Field label="How">
                <select value={mode} onChange={(e) => setMode(e.target.value)} style={{ minWidth: 120 }}>
                  {PAYMENT_MODES.filter((m) => m !== "ADJUSTMENT").map((m) => <option key={m}>{m}</option>)}
                </select>
              </Field>
              <Field label="Reference">
                <input value={referenceNo} onChange={(e) => setReferenceNo(e.target.value)}
                  placeholder="Txn ID, slip no" style={{ ...inputStyle, width: 160 }} />
              </Field>
            </>
          )}
          <Field label="Note">
            <input value={remarks} onChange={(e) => setRemarks(e.target.value)}
              placeholder="Optional" style={{ ...inputStyle, width: 190 }} />
          </Field>
        </div>

        {how === "CREDIT" && bill && (
          <div style={{ fontSize: 12.5, padding: "9px 12px", borderRadius: 8,
                        background: "var(--sage-soft)", border: "1px solid var(--sage)" }}>
            {bill.invoiceNo} will be settled by <b>{money(n)}</b> without any money changing hands,
            and {advance.supplierName} will hold <b>{money(advance.balance - n)}</b> of ours afterwards.
          </div>
        )}
      </div>
    </Modal>
  );
}
