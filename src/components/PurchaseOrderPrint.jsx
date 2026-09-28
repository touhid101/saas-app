/**
 * A purchase order, laid out for paper and addressed to one supplier.
 *
 * <b>Why it looks like the invoice.</b> It reuses the `pd-*` classes the sales
 * invoice is built from — same letterhead, same party block, same line table,
 * same totals stack. Both documents leave the same pharmacy, and a supplier
 * receiving one should recognise it as coming from the same place the customer's
 * invoice does. Sharing the stylesheet also means a change to the letterhead
 * reaches both, rather than one drifting away from the other.
 *
 * <b>What is deliberately different.</b> An invoice records a sale that has
 * happened; this asks for goods that have not arrived. So there is no paid or
 * balance-due block, no batch or expiry (the supplier assigns those), the
 * emphasis is on quantity rather than money, and the signature slots are the
 * ones an order needs — raised, approved, and the supplier's own acknowledgement.
 *
 * <b>One supplier per sheet.</b> A requisition spans several suppliers; each
 * gets only its own part. Sending the whole requisition would show one supplier
 * what is being bought from its competitors, and at what price.
 */
import React, { useEffect, useState } from "react";
import uploadUrl from "../lib/uploadUrl.js";
import { getPurchaseOrderDocument } from "../services/purchaseDocumentService.js";

/* ── formatting ──────────────────────────────────────────────────────────── */

const n2 = (v) =>
  Number(v ?? 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const qty = (v) => {
  const x = Number(v ?? 0);
  return Number.isInteger(x) ? String(x) : String(Number(x.toFixed(3)));
};

const MONTHS = ["JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"];

function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return `${String(d.getDate()).padStart(2, "0")}-${MONTHS[d.getMonth()]}-${d.getFullYear()}`;
}

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

const Total = ({ label, value, sep }) => (
  <div className={sep ? "pd-total-row pd-sep" : "pd-total-row"}>
    <span>{label}</span><span className="pd-num">{value}</span>
  </div>
);

/* ── the sheet ───────────────────────────────────────────────────────────── */

function Sheet({ doc }) {
  const { branch, order, supplier, lines, totals, amountInWords } = doc;
  /* The server sends the status already in words. An order not yet received is
     the one worth marking, so it takes the emphasised stamp. */
  const pending = /not yet received/i.test(order?.status || "");

  return (
    <div className="pd-a4">
      <header className="pd-mast">
        <Brand branch={branch} />
        <div>
          <div className="pd-doc-title">Purchase Order</div>
          <div className="pd-doc-sub pd-num">{order?.orderNo}</div>
          {/* Saying it on the sheet stops an order being filed as a delivery
              note — the two look alike once they are on a desk. */}
          {order?.status && (
            <div style={{ textAlign: "right" }}>
              <span className={pending ? "pd-stamp pd-stamp-due" : "pd-stamp"}>{order.status}</span>
            </div>
          )}
        </div>
      </header>

      <section className="pd-parties">
        <div>
          <div className="pd-panel-label">Supplier</div>
          <div className="pd-party-name">{supplier?.name}</div>
          {supplier?.contactPerson && <div className="pd-party-line">Attn: {supplier.contactPerson}</div>}
          {supplier?.address && <div className="pd-party-line">{supplier.address}</div>}
          {(supplier?.mobile || supplier?.phone) && (
            <div className="pd-party-line pd-num">
              {[supplier.mobile, supplier.phone].filter(Boolean).join(" · ")}
            </div>
          )}
          {supplier?.email && <div className="pd-party-line">{supplier.email}</div>}
        </div>

        <dl className="pd-facts">
          <dt>Order date</dt>
          <dd className="pd-num">{fmtDate(order?.date)}</dd>

          {/* The reason this document exists: the supplier and the buyer need a
              shared reference for the shortage this order came out of. */}
          {order?.requisitionNo && (
            <>
              <dt>Requisition</dt>
              <dd className="pd-num">
                {order.requisitionNo}
                {order.requisitionDate ? ` · ${fmtDate(order.requisitionDate)}` : ""}
              </dd>
            </>
          )}

          <dt>Terms</dt>
          <dd>{order?.paymentType || "—"}</dd>

          <dt>Raised by</dt>
          <dd>{order?.createdBy || "—"}</dd>
        </dl>
      </section>

      <table className="pd-table">
        <thead>
          <tr>
            <th className="pd-l" style={{ width: "6mm" }}>#</th>
            <th className="pd-l">Product</th>
            <th>Unit</th>
            <th>Order Qty</th>
            <th>Pcs / Unit</th>
            <th>Total Pcs</th>
            <th>Unit Price</th>
            <th>MRP</th>
            <th>Amount</th>
          </tr>
        </thead>
        <tbody>
          {(lines ?? []).map((l) => (
            <tr key={l.sl}>
              <td className="pd-l pd-num pd-muted">{l.sl}</td>
              <td className="pd-l"><div className="pd-item">{l.product}</div></td>
              <td>{l.unit || "—"}</td>
              {/* The quantity is what this document is really for, so it is the
                  emphasised column here — not the amount, as on an invoice. */}
              <td className="pd-num pd-strong">{qty(l.quantity)}</td>
              <td className="pd-num pd-muted">{l.piecesPerUnit ?? "—"}</td>
              <td className="pd-num pd-muted">{l.quantityPcs == null ? "—" : qty(l.quantityPcs)}</td>
              <td className="pd-num">{n2(l.unitCost)}</td>
              <td className="pd-num pd-muted">{n2(l.mrp)}</td>
              <td className="pd-num">{n2(l.totalPrice)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="pd-foot">
        <div className="pd-totals">
          <Total label={`Lines · units`} value={`${totals?.lineCount ?? 0} · ${qty(totals?.totalQuantity)}`} />
          <Total label="Subtotal" value={n2(totals?.subtotal)} sep />
          {Number(totals?.discount) > 0 && <Total label="Less discount" value={n2(totals.discount)} />}
          {Number(totals?.tax) > 0 && <Total label="Tax" value={n2(totals.tax)} />}
          {Number(totals?.serviceCharge) > 0 && (
            <Total label="Service charge" value={n2(totals.serviceCharge)} />
          )}
          <div className="pd-total-row pd-total-grand">
            <span>Order Value</span><span className="pd-num">{n2(totals?.grandTotal)}</span>
          </div>
        </div>
      </div>

      <div className="pd-words">
        <span className="pd-words-label">Amount in words</span>
        {amountInWords}
      </div>

      {order?.remarks && <div className="pd-note"><b>Note:</b> {order.remarks}</div>}

      <div className="pd-note">
        Please supply the above against this order and quote the order number on your
        invoice and delivery challan. Prices are as agreed; confirm before despatch if
        any differ.
      </div>

      {/* An order is authorised by the buyer and acknowledged by the supplier —
          which is not the same set of signatures an invoice carries. */}
      <div className="pd-sign">
        <div className="pd-sign-slot">Raised by</div>
        <div className="pd-sign-slot">Authorised signature</div>
        <div className="pd-sign-slot">Supplier acknowledgement</div>
      </div>

      <footer className="pd-a4-foot">
        <span>{branch?.tradingName || branch?.name}</span>
        <span className="pd-num">
          {order?.orderNo}
          {order?.requisitionNo ? ` · ${order.requisitionNo}` : ""}
          {" · Printed "}{fmtStamp(order?.printedAt)}
        </span>
      </footer>
    </div>
  );
}

/**
 * Loads one purchase order and shows it ready to print.
 *
 * @param purchaseId the order to print
 * @param sequence   optional {position, total, onNext} — when a split produced
 *                   several orders the buyer is printing all of them, and
 *                   saying "2 of 4" with a way forward beats closing this and
 *                   hunting for the next row in the table behind it
 */
export default function PurchaseOrderPrint({ purchaseId, sequence, onClose }) {
  const [doc, setDoc] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    getPurchaseOrderDocument(purchaseId)
      .then((d) => { if (live) setDoc(d); })
      .catch((e) => { if (live) setError(e?.message || "Could not load this order."); });
    return () => { live = false; };
  }, [purchaseId]);

  /* @page cannot be scoped to a component, so the paper size is injected while
     this overlay is mounted and removed with it — otherwise printing an order
     after a till receipt would inherit the 80mm roll. */
  useEffect(() => {
    const el = document.createElement("style");
    el.setAttribute("data-print-page", "a4");
    el.textContent = "@page { size: A4 portrait; margin: 10mm; }";
    document.head.appendChild(el);
    return () => el.remove();
  }, []);

  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose?.(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="pd-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
      <div className="pd-shell" role="dialog" aria-label="Purchase order print preview">
        <div className="pd-toolbar no-print">
          <span className="pd-toolbar-title">
            Purchase order
            <span className="pd-toolbar-meta">
              {sequence?.total > 1 ? `${sequence.position} of ${sequence.total} · ` : ""}
              A4 portrait
              {doc?.order?.orderNo ? ` · ${doc.order.orderNo}` : ""}
              {doc?.supplier?.name ? ` · ${doc.supplier.name}` : ""}
            </span>
          </span>
          <div className="pd-toolbar-actions">
            <button className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>
            <button className="btn btn-primary btn-sm" disabled={!doc}
                    onClick={() => window.print()}>Print</button>
            {/* Appears only while a supplier is still unprinted, so the last
                sheet does not offer a step that goes nowhere. */}
            {sequence?.onNext && sequence.position < sequence.total && (
              <button className="btn btn-teal btn-sm" onClick={sequence.onNext}>
                Next supplier →
              </button>
            )}
          </div>
        </div>

        <div className="pd-paper-wrap">
          {error ? (
            <div className="empty-state" role="alert">{error}</div>
          ) : !doc ? (
            <div className="empty-state">Preparing…</div>
          ) : (
            <Sheet doc={doc} />
          )}
        </div>
      </div>
    </div>
  );
}
