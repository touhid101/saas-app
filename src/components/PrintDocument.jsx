/**
 * The two printed layouts for a sale.
 *
 * `A4` is the office invoice and `Pos` is the 80mm till receipt. Both read one
 * document from the server, so the copy handed across the counter and the copy
 * filed cannot disagree about the same invoice number. Neither does arithmetic:
 * every figure here, down to the amount in words, arrives already computed.
 *
 * The letterhead — name, address, phone, logo — comes from the branch that
 * raised the sale. Nothing about it is hard-coded, so a group running several
 * pharmacies prints each under its own identity.
 *
 * Both are designed for paper: black on white, no theme variables, and no
 * colour used to say something a rule already says. The hierarchy runs total,
 * then lines, then everything else, because that is the order the figure is
 * checked in.
 */
import React, { useEffect, useState } from "react";
import uploadUrl from "../lib/uploadUrl.js";
import { getInvoiceDocument } from "../services/invoiceDocumentService.js";

/* ── formatting ──────────────────────────────────────────────────────────── */

/** Money without a currency mark: the column heading already says what it is. */
const n2 = (v) =>
  Number(v ?? 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** A quantity, without forcing decimals onto whole units. */
const qty = (v) => {
  const x = Number(v ?? 0);
  return Number.isInteger(x) ? String(x) : String(Number(x.toFixed(3)));
};

const MONTHS = ["JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"];

/** 2026-08-28 → 28-AUG-2026, unambiguous in every locale. */
function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return `${String(d.getDate()).padStart(2, "0")}-${MONTHS[d.getMonth()]}-${d.getFullYear()}`;
}

/** 28-AUG-26 03:29 PM — when this copy was printed, not when the sale happened. */
function fmtStamp(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  let h = d.getHours();
  const ampm = h >= 12 ? "PM" : "AM";
  h = h % 12 || 12;
  return `${String(d.getDate()).padStart(2, "0")}-${MONTHS[d.getMonth()]}-${String(d.getFullYear()).slice(2)}`
       + ` ${String(h).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")} ${ampm}`;
}

/**
 * Paid, part paid or unpaid — read from the figures rather than from the stored
 * status, so the stamp and the totals block can never contradict each other.
 */
function settlement(totals) {
  const due = Number(totals?.balanceDue ?? 0);
  const paid = Number(totals?.paid ?? 0);
  if (due <= 0) return { label: "Paid", due: false };
  if (paid > 0) return { label: "Part Paid", due: true };
  return { label: "Unpaid", due: true };
}

/* ── letterhead ──────────────────────────────────────────────────────────── */

function Brand({ branch }) {
  const logo = uploadUrl(branch?.logoUrl);
  return (
    <div className="pd-brand">
      {logo && <img className="pd-logo" src={logo} alt="" />}
      <div className="pd-brand-text">
        <div className="pd-org">{branch?.tradingName || branch?.name || ""}</div>
        {branch?.address && <div className="pd-org-line">{branch.address}</div>}
        {branch?.phone && <div className="pd-org-line">Phone: {branch.phone}</div>}
        {branch?.email && <div className="pd-org-line">{branch.email}</div>}
      </div>
    </div>
  );
}

/* ── The customer's balance, as a reminder ───────────────────────────────── */

/**
 * What the customer owes in total, printed under the invoice.
 *
 * Three figures rather than one: what was already outstanding, what this sale
 * adds, and the total. A single number hides whether the debt is new, which is
 * the first thing anyone asks when handed one.
 *
 * The server omits this block entirely for a walk-in sale and when nothing is
 * owed, so there is no case here for "nothing to say".
 */
function AccountNote({ account, compact }) {
  if (!account?.invoices?.length) return null;

  /* Age earns emphasis, not the amount. A small sum ninety days out is the one
     worth chasing; a large one from yesterday is simply this week's trade. */
  const age = (d) => (d >= 90 ? "pd-age-old" : d >= 30 ? "pd-age-mid" : "");

  if (compact) {
    return (
      <div className="pd-account pd-account-pos">
        <span className="pd-account-title">Account statement</span>
        {account.invoices.map((r) => (
          <div key={r.invoiceNo} className="pd-account-row">
            <span>
              {fmtDate(r.date)} · {r.invoiceNo}
              {r.current && " (this)"}
              <span className={`pd-age ${age(r.ageDays)}`}> {r.ageDays}d</span>
            </span>
            <span className="pd-num">{n2(r.due)}</span>
          </div>
        ))}
        {account.moreCount > 0 && (
          <div className="pd-account-row pd-account-more">
            <span>+{account.moreCount} earlier invoice{account.moreCount === 1 ? "" : "s"}</span>
            <span className="pd-num">{n2(account.moreDue)}</span>
          </div>
        )}
        <div className="pd-account-row pd-account-total">
          <span>Grand total due</span>
          <span className="pd-num">{n2(account.grandTotal)}</span>
        </div>
        {account.currentIsDraft && (
          <div className="pd-account-foot">This invoice is a draft, not counted above.</div>
        )}
      </div>
    );
  }

  return (
    <div className="pd-account">
      <div className="pd-account-head">
        <span className="pd-account-title">Account statement — outstanding invoices</span>
        <span className="pd-account-sub">
          {account.openInvoices} unpaid
          {account.oldestDue ? ` · oldest ${fmtDate(account.oldestDue)}` : ""}
        </span>
      </div>

      <table className="pd-account-table">
        <thead>
          <tr>
            <th className="pd-l">Invoice Date</th>
            <th className="pd-l">Invoice No</th>
            <th>Invoice Total</th>
            <th>Due</th>
            <th>Due Age</th>
          </tr>
        </thead>
        <tbody>
          {account.invoices.map((r) => (
            <tr key={r.invoiceNo} className={r.current ? "pd-account-current" : ""}>
              <td className="pd-l pd-num">{fmtDate(r.date)}</td>
              <td className="pd-l pd-num">
                {r.invoiceNo}{r.current && <span className="pd-this"> this invoice</span>}
              </td>
              <td className="pd-num">{n2(r.invoiceTotal)}</td>
              <td className="pd-num pd-strong">{n2(r.due)}</td>
              <td className={`pd-num ${age(r.ageDays)}`}>{r.ageDays} days</td>
            </tr>
          ))}

          {account.moreCount > 0 && (
            <tr className="pd-account-more">
              <td className="pd-l" colSpan={3}>
                +{account.moreCount} earlier invoice{account.moreCount === 1 ? "" : "s"}
              </td>
              <td className="pd-num pd-strong">{n2(account.moreDue)}</td>
              <td />
            </tr>
          )}
        </tbody>
        <tfoot>
          <tr>
            <td className="pd-r" colSpan={3}>Grand total due</td>
            <td className="pd-num">{n2(account.grandTotal)}</td>
            <td />
          </tr>
        </tfoot>
      </table>

      {account.currentIsDraft && (
        <p className="pd-account-foot">
          This invoice is still a draft and is not counted above. It joins the statement
          once it is confirmed.
        </p>
      )}
    </div>
  );
}
/* ═══════════════════════════════════════════════════════════════════════════
   A4 INVOICE
   ═══════════════════════════════════════════════════════════════════════════ */

function A4({ doc }) {
  const { branch, invoice, customer, lines, totals, amountInWords, account } = doc;
  const state = settlement(totals);

  return (
    <div className="pd-a4">
      <header className="pd-mast">
        <Brand branch={branch} />
        <div>
          <div className="pd-doc-title">Invoice</div>
          <div className="pd-doc-sub pd-num">{invoice?.invoiceNo}</div>
          <div style={{ textAlign: "right" }}>
            <span className={state.due ? "pd-stamp pd-stamp-due" : "pd-stamp"}>{state.label}</span>
          </div>
        </div>
      </header>

      <section className="pd-parties">
        <div>
          <div className="pd-panel-label">Billed to</div>
          <div className="pd-party-name">{customer?.name}</div>
          {customer?.address && <div className="pd-party-line">{customer.address}</div>}
          {customer?.mobile && <div className="pd-party-line pd-num">{customer.mobile}</div>}
        </div>

        <dl className="pd-facts">
          <dt>Invoice date</dt>
          <dd className="pd-num">{fmtDate(invoice?.date)}</dd>

          {invoice?.challanNo && (<><dt>Challan</dt><dd className="pd-num">{invoice.challanNo}</dd></>)}

          <dt>Payment</dt>
          <dd>{invoice?.paymentType || "—"}</dd>

          <dt>Served by</dt>
          <dd>{invoice?.createdBy || "—"}</dd>

          {invoice?.deliveryManName && (
            <>
              <dt>Delivery</dt>
              <dd>
                {invoice.deliveryManName}
                {invoice.deliveryManPhone ? ` · ${invoice.deliveryManPhone}` : ""}
              </dd>
            </>
          )}
        </dl>
      </section>

      <table className="pd-table">
        <thead>
          <tr>
            <th className="pd-l" style={{ width: "6mm" }}>#</th>
            <th className="pd-l">Product</th>
            <th>MRP</th>
            <th>Unit</th>
            <th>Unit Qty</th>
            <th>Total MRP</th>
            <th>Discount</th>
            <th>Unit Price</th>
            <th>Pieces</th>
            <th>Amount</th>
          </tr>
        </thead>
        <tbody>
          {(lines ?? []).map((l) => (
            <tr key={l.sl}>
              <td className="pd-l pd-num pd-muted">{l.sl}</td>
              <td className="pd-l">
                <div className="pd-item">{l.product}</div>
                {/* Batch and expiry belong on a pharmacy invoice: it is what a
                    customer quotes when something has to come back. */}
                {(l.batchNo || l.expiryDate) && (
                  <div className="pd-item-sub pd-num">
                    {l.batchNo ? `Batch ${l.batchNo}` : ""}
                    {l.batchNo && l.expiryDate ? " · " : ""}
                    {l.expiryDate ? `Exp ${fmtDate(l.expiryDate)}` : ""}
                  </div>
                )}
              </td>
              <td className="pd-num">{n2(l.mrp)}</td>
              <td>{l.unit}</td>
              <td className="pd-num">{qty(l.unitQty)}</td>
              <td className="pd-num">{n2(l.totalMrp)}</td>
              <td className="pd-num">{Number(l.discountAmount) ? n2(l.discountAmount) : "—"}</td>
              <td className="pd-num">{n2(l.unitPrice)}</td>
              <td className="pd-num pd-muted">{qty(l.quantityPcs)}</td>
              <td className="pd-num pd-strong">{n2(l.totalPrice)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="pd-foot">
        <div className="pd-totals">
          <Total label="Total MRP" value={n2(totals?.totalMrp)} />
          <Total label="Less discount" value={n2(totals?.discount)} />
          <Total label="Subtotal" value={n2(totals?.subtotal)} sep />
          {Number(totals?.specialDiscount) > 0 && (
            <Total label="Special discount" value={n2(totals.specialDiscount)} />
          )}
          {Number(totals?.deliveryCharge) > 0 && (
            <Total label="Delivery charge" value={n2(totals.deliveryCharge)} />
          )}

          <div className="pd-total-row pd-total-grand">
            <span>Total Bill</span><span className="pd-num">{n2(totals?.totalBill)}</span>
          </div>

          {Number(totals?.returnAmount) > 0 && (
            <Total label="Returned" value={n2(totals.returnAmount)} />
          )}
          <Total label={`Paid${invoice?.paymentType ? ` (${invoice.paymentType})` : ""}`}
                 value={n2(totals?.paid)} />
          <div className="pd-total-row pd-total-due">
            <span>Balance Due</span><span className="pd-num">{n2(totals?.balanceDue)}</span>
          </div>
        </div>
      </div>

      <div className="pd-words">
        <span className="pd-words-label">Amount in words</span>
        {amountInWords}
      </div>

      <AccountNote account={account} />

      {invoice?.remarks && <div className="pd-note"><b>Note:</b> {invoice.remarks}</div>}

      <div className="pd-sign">
        <div className="pd-sign-slot">Assemble by</div>
        <div className="pd-sign-slot">Checked by</div>
        <div className="pd-sign-slot">Authorised signature</div>
      </div>

      <footer className="pd-a4-foot">
        <span>{branch?.tradingName || branch?.name}</span>
        <span className="pd-num">
          {invoice?.invoiceNo} · Printed {fmtStamp(invoice?.printedAt)}
        </span>
      </footer>
    </div>
  );
}

const Total = ({ label, value, sep }) => (
  <div className={sep ? "pd-total-row pd-sep" : "pd-total-row"}>
    <span>{label}</span><span className="pd-num">{value}</span>
  </div>
);

/* ═══════════════════════════════════════════════════════════════════════════
   POS RECEIPT — 80mm
   ═══════════════════════════════════════════════════════════════════════════ */

function Pos({ doc }) {
  const { branch, invoice, customer, lines, totals, amountInWords, account } = doc;
  const logo = uploadUrl(branch?.logoUrl);
  const state = settlement(totals);

  return (
    <div className="pd-pos">
      <div className="pd-pos-brand">
        {logo && <img className="pd-logo" src={logo} alt="" />}
        <div className="pd-org">{branch?.tradingName || branch?.name || ""}</div>
        {branch?.address && <div className="pd-org-line">{branch.address}</div>}
        {branch?.phone && <div className="pd-org-line">Phone: {branch.phone}</div>}
      </div>

      <div className="pd-pos-title">Sales Receipt</div>

      <div className="pd-rule" />

      <div className="pd-pos-row"><span>Invoice</span><span className="pd-num">{invoice?.invoiceNo}</span></div>
      <div className="pd-pos-row"><span>Date</span><span className="pd-num">{fmtDate(invoice?.date)}</span></div>
      <div className="pd-pos-row"><span>Customer</span><span>{customer?.name}</span></div>
      {customer?.mobile && (
        <div className="pd-pos-row"><span>Mobile</span><span className="pd-num">{customer.mobile}</span></div>
      )}
      <div className="pd-pos-row"><span>Served by</span><span>{invoice?.createdBy || "—"}</span></div>
      {invoice?.deliveryManName && (
        <div className="pd-pos-row"><span>Delivery</span><span>{invoice.deliveryManName}</span></div>
      )}

      <table className="pd-pos-table">
        <thead>
          <tr><th className="pd-l">Item</th><th>Qty</th><th>Rate</th><th>Amount</th></tr>
        </thead>
        <tbody>
          {(lines ?? []).map((l) => (
            <tr key={l.sl}>
              <td className="pd-l">
                <div className="pd-pos-item">{l.product}</div>
                {/* The unit sits under the name, not beside it: product names are
                    long and the three number columns have to stay aligned. */}
                <div className="pd-pos-unit">
                  {l.unit}{l.batchNo ? ` · ${l.batchNo}` : ""}
                </div>
              </td>
              <td className="pd-num">{qty(l.unitQty)}</td>
              <td className="pd-num">{n2(l.unitPrice)}</td>
              <td className="pd-num">{n2(l.totalPrice)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="pd-rule" />

      <div className="pd-pos-totals">
        <PosTotal label="MRP total" value={n2(totals?.totalMrp)} />
        <PosTotal label="Discount" value={n2(totals?.discount)} />
        <PosTotal label="Subtotal" value={n2(totals?.subtotal)} />
        {Number(totals?.specialDiscount) > 0 && (
          <PosTotal label="Special discount" value={n2(totals.specialDiscount)} />
        )}
        {Number(totals?.deliveryCharge) > 0 && (
          <PosTotal label="Delivery" value={n2(totals.deliveryCharge)} />
        )}

        <div className="pd-pos-total pd-pos-grand">
          <span>Total Bill</span><span className="pd-num">{n2(totals?.totalBill)}</span>
        </div>

        {Number(totals?.returnAmount) > 0 && (
          <PosTotal label="Returned" value={n2(totals.returnAmount)} />
        )}
        <PosTotal label={`Paid${invoice?.paymentType ? ` (${invoice.paymentType})` : ""}`}
                  value={n2(totals?.paid)} />
        <div className="pd-pos-total pd-pos-due">
          <span>Balance Due</span><span className="pd-num">{n2(totals?.balanceDue)}</span>
        </div>
      </div>

      <div className="pd-rule" />

      <div className="pd-pos-words">{amountInWords}</div>
      <div className="pd-pos-title" style={{ marginTop: 6 }}>{state.label}</div>

      <AccountNote account={account} compact />

      <div className="pd-pos-thanks">Thank you!</div>
      <div className="pd-pos-foot pd-num">Printed {fmtStamp(invoice?.printedAt)}</div>
    </div>
  );
}

const PosTotal = ({ label, value }) => (
  <div className="pd-pos-total"><span>{label}</span><span className="pd-num">{value}</span></div>
);

/* ═══════════════════════════════════════════════════════════════════════════
   THE OVERLAY
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Loads a sale and shows it in the chosen layout, ready to print.
 *
 * @param format "a4" | "pos"
 */
export default function PrintDocument({ saleId, format = "a4", onClose }) {
  const [doc, setDoc] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    getInvoiceDocument(saleId)
      .then((d) => { if (live) setDoc(d); })
      .catch((e) => { if (live) setError(e?.message || "Could not load this invoice."); });
    return () => { live = false; };
  }, [saleId]);

  /* @page cannot be scoped to a component or switched by a class, so the paper
     size is injected while this overlay is mounted and removed with it. Without
     the removal, printing one receipt would leave every later page 80mm wide. */
  useEffect(() => {
    const el = document.createElement("style");
    el.setAttribute("data-print-page", format);
    el.textContent = format === "pos"
      ? "@page { size: 80mm auto; margin: 3mm; }"
      : "@page { size: A4 portrait; margin: 10mm; }";
    document.head.appendChild(el);
    return () => el.remove();
  }, [format]);

  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose?.(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="pd-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
      <div className="pd-shell" role="dialog" aria-label="Print preview">
        <div className="pd-toolbar no-print">
          <span className="pd-toolbar-title">
            {format === "pos" ? "Receipt" : "Invoice"}
            <span className="pd-toolbar-meta">
              {format === "pos" ? "80mm roll" : "A4 portrait"}
              {doc?.invoice?.invoiceNo ? ` · ${doc.invoice.invoiceNo}` : ""}
            </span>
          </span>
          <div className="pd-toolbar-actions">
            <button className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>
            <button className="btn btn-primary btn-sm" disabled={!doc}
                    onClick={() => window.print()}>Print</button>
          </div>
        </div>

        <div className="pd-paper-wrap">
          {error ? (
            <div className="empty-state" role="alert">{error}</div>
          ) : !doc ? (
            <div className="empty-state">Preparing…</div>
          ) : format === "pos" ? (
            <Pos doc={doc} />
          ) : (
            <A4 doc={doc} />
          )}
        </div>
      </div>
    </div>
  );
}
