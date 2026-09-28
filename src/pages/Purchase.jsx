/**
 * Purchase.jsx — Purchase invoice entry and history.
 *
 * Follows the house layout used by Sales Activity and Product Master:
 * `.page` → `.page-head` with an <h1> and `.page-sub` → KPI strip → Tabs →
 * content. Every colour is a CSS custom property from styles.css with no
 * hardcoded fallback, so the page follows all four themes.
 *
 * Entry is built around the way purchase invoices are actually typed: pick a
 * supplier once, then add line after line without the hands leaving the
 * keyboard, with the invoice total always visible. The totals panel is sticky
 * for exactly that reason — on a 40-line invoice the total is what the person
 * is watching, and scrolling to find it breaks the rhythm.
 *
 * Line totals shown here are a live preview. The server recalculates every one
 * of them on save; if the two disagree the server is right.
 */
import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { Card, Pill, Field, Modal, Tabs, QtyInput } from "../components/UI.jsx";
import SearchSelect from "../components/SearchSelect.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import {
  getPurchases, getPurchase, createPurchase, updatePurchase,
  approvePurchase, cancelPurchase, getFormLookups, searchProducts,
  calculateLineTotal, calculateInvoiceTotals, piecesFor, pricesForUnit, boxPricesFor,
  toFormValues, validateHeader, validateLine,
  toDateInput,
} from "../services/purchaseService.js";

/* ── Icon helper — matches the `Ic` convention used across the app ── */
/**
 * Money to four significant paisa.
 *
 * The shared `money` helper rounds to whole taka, which is right for invoice
 * totals and wrong for a per-piece buy price: a twelfth of a box price rarely
 * lands on a round number, and rounding it on screen makes the line total look
 * like arithmetic nobody can follow.
 */
const fine = (n) => {
  const v = Number(n) || 0;
  const dp = Math.abs(v) < 100 && Math.round(v * 100) !== Math.round(v * 10000) / 100 ? 4 : 2;
  return `৳ ${v.toFixed(dp)}`;
};

const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

const P = {
  search:  ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  plus:    "M12 5v14M5 12h14",
  trash:   ["M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"],
  edit:    ["M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"],
  eye:     ["M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"],
  check:   "M20 6 9 17l-5-5",
  back:    ["M19 12H5", "M12 19l-7-7 7-7"],
  x:       "M18 6L6 18M6 6l12 12",
  chevL:   "M15 18l-6-6 6-6",
  chevR:   "M9 18l6-6-6-6",
  truck:   ["M1 6h14v11H1z", "M15 9h4l3 3v5h-7z", "M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"],
  doc:     ["M9 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9z", "M13 2v7h7"],
  wallet:  ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z", "M1 10h22"],
  alert:   ["M12 9v4", "M12 17h.01", "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"],
  clock:   ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M12 6v6l4 2"],
  calc:    ["M4 2h16v20H4z", "M8 6h8M8 10h2M12 10h2M16 10h.01M8 14h2M12 14h2M16 14h.01M8 18h8"],
  box:     ["M21 8 12 3 3 8l9 5 9-5z", "M3 8v9l9 5 9-5V8", "M12 13v9"],
  lock:    ["M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4"],
  save:    ["M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z", "M17 21v-8H7v8", "M7 3v5h8"],
  send:    ["M22 2 11 13", "M22 2l-7 20-4-9-9-4 20-7z"],
  refresh: ["M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5"],
  filter:  ["M21 4H14M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3", "M14 2v4M8 10v4M16 18v4"],
};

const TABS = ["New Purchase", "Purchase History"];
const PAGE_SIZES = [10, 25, 50, 100];

const money = (v) => v == null || v === ""
  ? "—"
  : `৳${Number(v).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const statusTone = (s) => (s === "A" ? "sage" : s === "D" ? "grey" : "amber");

const inputStyle = {
  padding: "7px 10px", borderRadius: 8, border: "1px solid var(--line)",
  fontSize: 13, background: "var(--paper)", color: "var(--ink)",
};

const Spinner = ({ label = "Loading…" }) => (
  <div className="empty-state">
    <svg width={24} height={24} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" style={{ animation: "spin 1s linear infinite" }}>
      <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
    </svg>
    <div style={{ marginTop: 8, fontSize: 13 }}>{label}</div>
  </div>
);

const Req = () => <span style={{ color: "var(--clay)", marginLeft: 2 }} aria-hidden="true">*</span>;

const Section = ({ icon, title, sub, right }) => (
  <div style={{
    display: "flex", alignItems: "center", gap: 11,
    margin: "4px 0 14px", paddingBottom: 10, borderBottom: "1px solid var(--line)",
  }}>
    <span style={{
      width: 28, height: 28, borderRadius: 8, flexShrink: 0,
      background: "var(--amber-soft)", color: "var(--amber-deep)",
      display: "inline-flex", alignItems: "center", justifyContent: "center",
    }}><Ic path={icon} size={14} /></span>
    <div>
      <div style={{ fontSize: 14, fontWeight: 700, lineHeight: 1.2 }}>{title}</div>
      {sub && <div style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>{sub}</div>}
    </div>
    {right && <div style={{ marginLeft: "auto" }}>{right}</div>}
  </div>
);

/* ═══════════════════════════════════════════════════════════════════════════
   PAGE
   ═══════════════════════════════════════════════════════════════════════════ */

export default function Purchase() {
  const { addToast } = useApp();
  const perm = usePermission("purchase");

  const [tab, setTab] = useState(TABS[0]);
  const [view, setView] = useState("tabs");        // tabs | detail
  const [detailId, setDetailId] = useState(null);
  const [editingId, setEditingId] = useState(null);

  const [lookups, setLookups] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [historyKey, setHistoryKey] = useState(0);  // bumped to force a reload

  useEffect(() => {
    let dead = false;
    getFormLookups()
      .then((d) => { if (!dead) setLookups(d); })
      .catch((e) => { if (!dead) setLookupError(e.message || "Could not load dropdown data."); });
    return () => { dead = true; };
  }, []);

  if (!perm.canView) {
    return (
      <div className="page">
        <div className="page-head"><div><h1>Purchase</h1></div></div>
        <Card><div className="empty-state">You do not have permission to view Purchase.</div></Card>
      </div>
    );
  }

  const openDetail = (id) => { setDetailId(id); setView("detail"); };
  const openEdit = (id) => { setEditingId(id); setView("tabs"); setTab(TABS[0]); };
  const backToList = () => {
    setView("tabs"); setDetailId(null); setEditingId(null);
    setTab(TABS[1]); setHistoryKey((k) => k + 1);
  };
  const afterSave = () => {
    setEditingId(null); setTab(TABS[1]); setHistoryKey((k) => k + 1);
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Purchase</h1>
          <div className="page-sub">
            Batch-wise purchase invoices. Line totals, invoice totals, the invoice
            number and the batch reference are all produced by the server.
          </div>
        </div>
        {view === "detail" && (
          <div className="flex-gap">
            <button className="btn btn-ghost btn-sm" onClick={backToList}>
              <Ic path={P.back} size={13} /> Back to history
            </button>
          </div>
        )}
      </div>

      {lookupError && (
        <div style={{
          padding: "12px 16px", background: "var(--clay-soft)", border: "1px solid var(--clay)",
          borderRadius: "var(--radius-sm)", fontSize: 13, marginBottom: 16, color: "var(--clay)",
          display: "flex", alignItems: "center", gap: 9,
        }}>
          <Ic path={P.alert} size={16} />
          <span><b>Dropdown data unavailable:</b> {lookupError}</span>
        </div>
      )}

      {view === "detail" ? (
        <PurchaseDetail
          purchaseId={detailId} perm={perm} addToast={addToast}
          onEdit={() => openEdit(detailId)} onChanged={backToList}
        />
      ) : (
        <>
          <Tabs tabs={TABS} active={tab} onChange={(t) => { setTab(t); if (t !== TABS[0]) setEditingId(null); }} />

          {tab === TABS[0] && (
            <PurchaseEntry
              key={editingId ?? "new"}
              purchaseId={editingId} lookups={lookups} perm={perm}
              addToast={addToast} onSaved={afterSave}
              onCancel={() => { setEditingId(null); setTab(TABS[1]); }}
            />
          )}

          {tab === TABS[1] && (
            <PurchaseHistory
              key={historyKey}
              lookups={lookups} perm={perm} addToast={addToast}
              onView={openDetail} onEdit={openEdit}
              onNew={() => { setEditingId(null); setTab(TABS[0]); }}
            />
          )}
        </>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ENTRY
   ═══════════════════════════════════════════════════════════════════════════ */

function PurchaseEntry({ purchaseId, lookups, perm, addToast, onSaved, onCancel }) {
  const confirm = useConfirm();
  const isEdit = purchaseId != null;

  const [values, setValues] = useState(() => toFormValues(null, lookups?.defaults));
  const [errors, setErrors] = useState({});
  const [lineErrors, setLineErrors] = useState({});
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  /* [POS] Which pane is on screen below the desktop breakpoint.
     Modelled on QuickSales, which keeps the same single piece of state and lets
     the stylesheet decide whether it means anything: at desktop width every
     pane is shown and this is inert, so there is no second layout to keep in
     step and no branch in the render. */
  const [pane, setPane] = useState("invoice");

  /* [TP] The central trade-price box. Held apart from the invoice values because
     it is an entry aid, not a field of the purchase — nothing sends it to the
     server, and reopening a saved invoice must not show it pre-filled as though
     it were part of the record. */
  const [bulkTp, setBulkTp] = useState("");
  const [meta, setMeta] = useState(null);          // invoiceNo/batchNo when editing

  useEffect(() => {
    if (!isEdit && lookups?.defaults && !dirty) {
      setValues(toFormValues(null, lookups.defaults));
    }
  }, [lookups, isEdit]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!isEdit) return;
    let dead = false;
    setLoading(true);
    getPurchase(purchaseId)
      .then((p) => {
        if (dead) return;
        setValues(toFormValues(p, lookups?.defaults));
        setMeta({ invoiceNo: p.invoiceNo, batchNo: p.batchNo, status: p.status, editable: p.editable });
        setLoading(false);
      })
      .catch((e) => {
        if (dead) return;
        addToast(e.message || "Could not load the invoice.", "error");
        setLoading(false);
      });
    return () => { dead = true; };
  }, [purchaseId, isEdit]); // eslint-disable-line react-hooks/exhaustive-deps

  /* Warn before a browser navigation discards unsaved lines. */
  useEffect(() => {
    if (!dirty) return undefined;
    const h = (e) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, [dirty]);

  const set = (k, v) => {
    setValues((s) => ({ ...s, [k]: v }));
    setDirty(true);
    setErrors((e) => (e[k] ? { ...e, [k]: undefined } : e));
  };

  const totals = useMemo(
    () => calculateInvoiceTotals(values.lines ?? [], values),
    [values]
  );

  /* While "pay the invoice discount too" is ticked, the amount handed over is
     the bill plus the invoice discount — and it has to stay so as the invoice
     is edited.

     It was set once, in the tickbox's own onChange, and then never again. Tick
     it, change a line or the discount percentage, and Given Amount kept the
     figure from the moment of the tick: PU260900007 was saved with ৳36.08
     against a ৳168.15 bill, so ৳16.82 went to advance, only ৳19.26 settled the
     invoice, and ৳132.07 stayed due on a bill the buyer believed was paid in
     full. The tickbox's own comment claimed the amount follows the tick; this
     is what makes that true.

     The invoice discount only — never the line discounts. TP% and the per-line
     cuts are the supplier's own price; they were never charged and there is
     nothing to hand back. The invoice cut is the one agreed on the gross and
     returned later, and it is the figure Supplier Payment uses for the same
     tick. Adding the line discounts asked the buyer to pay ৳143.43 across on a
     bill whose invoice discount was ৳117.01. */
  useEffect(() => {
    /* The same condition `readOnly` is built from, spelled out here because
       that constant is declared after an early return and so cannot be read
       from a hook — a hook after a conditional return is not allowed either.
       An approved invoice is never rewritten by this. */
    const locked = isEdit && meta && meta.editable === false;
    if (locked || !values.payInvoiceDiscount) return;

    /* Not on CREDIT. On every other method the bill is settled at the counter,
       so "pay the discount too" means handing over the bill plus the invoice
       discount and the amount can follow the bill as it changes.

       CREDIT is the opposite: the point of it is that only part is paid now.
       Forcing the given amount to the gross total made a credit purchase settle
       in full the moment the box was ticked, and re-forced it after every edit —
       so a buyer paying 100 against a 168.15 bill could not keep that 100 on
       screen. There, the discount is added to whatever is being handed over and
       the rest stays due. */
    if (values.paymentType === "CREDIT") return;

    const gross = (totals.amountToBePaid + totals.invoiceDisAmt).toFixed(2);
    // Guarded on equality: this writes into the state it reads from.
    if (values.givenAmount === gross) return;
    setValues((v) => (v.payInvoiceDiscount ? { ...v, givenAmount: gross } : v));
  }, [isEdit, meta, values.payInvoiceDiscount, values.givenAmount, values.paymentType,
      totals.amountToBePaid, totals.invoiceDisAmt]);

  // The slice of the cash that settles no part of this bill, and the due once
  // it is set aside. Mirrors resolveInvoiceAdvance on the server.
  const invoiceAdvance = values.payInvoiceDiscount
    ? (totals.invoiceDisAmt || 0)
    : (Number(values.advanceAmount) || 0);
  const dueAfterAdvance =
    totals.amountToBePaid - ((Number(values.givenAmount) || 0) - invoiceAdvance);
  // Cash beyond the bill, before any advance is declared against it.
  const overpay = (Number(values.givenAmount) || 0) - totals.amountToBePaid;

  /* ── line operations ── */

  /* ── keyboard-first entry ──────────────────────────────────────────────
     A purchase is typed from a paper invoice: eyes on the paper, hands on the
     keyboard. Everything below exists so a whole invoice can be entered without
     the cursor ever being aimed at anything.

       F2 / Ctrl+K   the product search, from anywhere on the page
       Enter         in the search, adds the highlighted product…
                     …and the cursor lands on its quantity, selected
       Enter         in a line field, moves to the next field on that line;
                     from the last one, back to the search for the next product
       Ctrl+Enter    save
       Escape        leaves the field you are in                              */
  const searchRef = useRef(null);

  /* Each supplier represents one manufacturer, so choosing a supplier chooses
     what can be bought on this invoice. The lookup carries the maker and the
     count with the option, so naming both costs no extra request. */
  const selectedSupplier = (lookups?.suppliers ?? [])
      .find((s) => String(s.id) === String(values.supplierId)) || null;
  const [focusLine, setFocusLine] = useState(null);

  /** Puts the cursor in a newly added line's quantity, with the 1 selected so
      typing replaces it rather than appending to it. */
  useEffect(() => {
    if (!focusLine) return;
    const el = document.querySelector(`[data-qty-for="${focusLine}"]`);
    if (el) { el.focus(); el.select?.(); }
    setFocusLine(null);
  }, [focusLine]);

  /**
   * Enter walks the line, then hands back to the search.
   *
   * <p>Reads the row from the DOM rather than from an index the component would
   * have to keep in step: the fields on a line differ by sales type and by
   * whether the invoice is read-only, and a hard-coded order would send the
   * cursor to a field that is not there.
   */
  const onLineKeyDown = (e) => {
    if (e.key !== "Enter") return;
    e.preventDefault();
    const row = e.target.closest("tr");
    if (!row) return;

    const go = (el) => { if (el) { el.focus(); el.select?.(); } return !!el; };

    /* Product → Qty → TP → next product.
       The short way round, because it is the way an invoice is actually read: a
       line's quantity and its trade price are what change from the paper, and
       the discount columns are usually left as the master has them. Walking the
       whole row in DOM order meant four presses of Enter between one product
       and the next, three of them through fields nobody had touched.
       Everything skipped is still one Tab away. */
    if (e.target.matches("[data-qty-for]")) {
      if (go(row.querySelector("[data-tp-for]"))) return;
      searchRef.current?.focus();
      return;
    }
    if (e.target.matches("[data-tp-for]")) { searchRef.current?.focus(); return; }

    /* Anything else — a discount the buyer stepped into with Tab — carries on
       along the row and hands back to the search at the end of it. Read from
       the DOM rather than from a fixed list: the fields on a line change with
       the sales type and with MRP purchases, and a hard-coded order would send
       the cursor at a field that is not rendered. */
    const fields = [...row.querySelectorAll("input:not([disabled]), select:not([disabled])")]
        .filter((el) => el.type !== "hidden" && el.tabIndex !== -1);
    if (!go(fields[fields.indexOf(e.target) + 1])) searchRef.current?.focus();
  };

  const addLine = (product) => {
    const salesType = product.pcsPerBox > 1 ? "BOX" : "PCS";
    // Priced for the unit, not straight off the master. Identical for BOX, but
    // a product with no box packing defaults to PCS and would otherwise open
    // with a box price against a single piece.
    const priced = pricesForUnit(salesType, product);
    const line = {
      key: `line-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      productId: product.id,
      productName: product.productName,
      productCode: product.productCode,
      genericName: product.genericName,
      companyName: product.companyName,
      quantity: 1,
      bonusQty: "",
      lineKind: "PURCHASE",
      tpPrice: priced.tpPrice,
      mrpPrice: priced.mrpPrice,
      igta: priced.igta,
      // The master's box figures, kept so switching units always converts from
      // them rather than from the price currently on screen.
      ...boxPricesFor(product),
      tpPct: "",
      discountPct: "",
      flatDiscount: "",
      salesType,
      pcsPerBox: product.pcsPerBox,
      pcsPerStrip: product.pcsPerStrip,
      currentStock: product.currentStock,
    };
    /* Newest first. Appending put it below the fold once an invoice ran past a
       screenful, so the line just added — the one about to be typed into — was
       the one you could not see. */
    setValues((s) => ({ ...s, lines: [line, ...(s.lines ?? [])] }));
    setDirty(true);
    // Hands the cursor to the quantity of the line just added. Typing a name,
    // pressing Enter and then reaching for the mouse is the slowest part of
    // taking in a supplier invoice, and it happens once per line.
    setFocusLine(line.key);
  };

  const updateLine = (key, patch) => {
    /* The second half of "integer only". QtyInput guards what can be typed into
       the box, but the box is not the only way a quantity reaches this state —
       a unit switch recalculates it, and a future caller will patch it directly.
       Enforcing it here as well means the rule holds wherever the change came
       from, which is the difference between a validated field and a validated
       value. Blank stays blank so the field can be cleared while retyping.

       [V69] The floor is one on a purchase line and zero on a gift, so it has to
       be applied where the kind is known — against the merged line, not the
       patch. Applied to the patch alone it read every quantity as a purchase
       and raised the gift's 0 to 1, which then failed the save as a gift
       carrying a purchase quantity. */
    setValues((s) => ({
      ...s,
      lines: s.lines.map((l) => {
        if (l.key !== key) return l;
        const next = { ...l, ...patch };
        if ("quantity" in patch && patch.quantity !== "" && patch.quantity != null) {
          next.quantity = next.lineKind === "GIFT"
            ? 0
            : Math.max(1, Math.round(Number(patch.quantity) || 1));
        }
        return next;
      }),
    }));
    setDirty(true);
    setLineErrors((e) => (e[key] ? { ...e, [key]: undefined } : e));
  };

  /**
   * [TP] Puts one trade-price percentage on every line at once.
   *
   * <p>A supplier quotes one TP% for the whole invoice far more often than a
   * different one per product, and typing it into fifteen rows is fifteen
   * chances to mistype it. This writes the same figure to every line and then
   * gets out of the way — each row stays editable exactly as before, so the one
   * product that was agreed differently is corrected in place.
   *
   * <p>Deliberately a deliberate act rather than a bound value. If the central
   * box drove the lines continuously, correcting a single row would be undone
   * by the next keystroke anywhere else, and there would be no way to express
   * "12% everywhere except this one".
   *
   * <p>Nothing here computes money. It sets the same field the row's own box
   * sets, and PurchaseCalculator does the arithmetic afterwards exactly as it
   * always has.
   */
  const applyTpToAllLines = () => {
    const raw = String(bulkTp ?? "").trim();
    if (raw === "") return;

    // Clamped to the range the per-line box already enforces, so the central
    // route cannot introduce a value the individual one would have refused.
    const pct = Math.min(100, Math.max(0, Number(raw)));
    if (!Number.isFinite(pct)) return;

    setValues((s) => ({ ...s, lines: s.lines.map((l) => ({ ...l, tpPct: pct })) }));
    setDirty(true);
    setLineErrors({});
    addToast(
      values.lines.length === 1
        ? `TP ${pct}% applied to the line.`
        : `TP ${pct}% applied to all ${values.lines.length} lines.`,
      "success");
  };

  const removeLine = (key) => {
    setValues((s) => ({ ...s, lines: s.lines.filter((l) => l.key !== key) }));
    setDirty(true);
  };

  /**
   * The shortcuts that work from anywhere on the page.
   *
   * <p>Bound to the document rather than to a wrapper, so they answer wherever
   * the cursor happens to be — including inside a line field, which is where it
   * spends most of an invoice. F2 is deliberately one key: it is pressed once
   * per product and a two-key chord for the commonest action on the screen is a
   * chord typed a hundred times a day. Ctrl+Enter is the save, and is a chord on
   * purpose — nothing that writes should be one keystroke away from a typo.
   */
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "F2" || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k")) {
        e.preventDefault();
        searchRef.current?.focus();
        searchRef.current?.select?.();
        return;
      }
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
        e.preventDefault();
        if (!readOnly && !saving && canSave) save("D");
        return;
      }
      // Leaves the field without leaving the page. Escape used to reach the
      // route guard and offer to discard a half-typed invoice.
      if (e.key === "Escape" && document.activeElement?.tagName === "INPUT") {
        document.activeElement.blur();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  });

  /* ── save ── */

  const save = async (status) => {
    if (saving) return;

    const headerErrors = validateHeader(values);
    const perLine = {};
    (values.lines ?? []).forEach((l) => {
      const e = validateLine(l, values.purchaseType);
      if (Object.keys(e).length) perLine[l.key] = e;
    });

    if (!values.lines?.length) headerErrors.lines = "Add at least one product line.";

    if (Object.keys(headerErrors).length || Object.keys(perLine).length) {
      setErrors(headerErrors);
      setLineErrors(perLine);
      addToast("Please correct the highlighted fields.", "error");
      return;
    }

    setSaving(true);
    try {
      const payload = { ...values, status };
      const saved = isEdit
        ? await updatePurchase(purchaseId, payload)
        : await createPurchase(payload);

      addToast(
        status === "A"
          ? `Invoice ${saved.invoiceNo} approved — stock received.`
          : `Invoice ${saved.invoiceNo} saved as ${saved.statusLabel.toLowerCase()}.`
      );
      setDirty(false);
      onSaved();
    } catch (err) {
      // Map a server field error back onto the form where we can.
      if (err.errorCode === "VALIDATION_FAILED" && err.data) setErrors(err.data);
      addToast(err.message || "Could not save the invoice.", "error");
    } finally {
      setSaving(false);
    }
  };

  const cancel = async () => {
    if (dirty && !(await confirm({
      title: "Discard your changes?",
      body: "This invoice has edits that have not been saved.",
      detail: "Leaving now loses them. There is no draft kept behind the scenes.",
      confirmLabel: "Discard changes", tone: "danger",
    }))) return;
    onCancel();
  };

  if (loading) return <Card><Spinner label="Loading invoice…" /></Card>;

  const readOnly = isEdit && meta && meta.editable === false;
  const canSave = isEdit ? perm.canEdit : perm.canCreate;

  return (
    <>
      {readOnly && (
        <div style={{
          padding: "12px 16px", background: "var(--amber-soft)", border: "1px solid var(--amber)",
          borderRadius: "var(--radius-sm)", fontSize: 13, marginBottom: 16, color: "var(--amber-deep)",
          display: "flex", alignItems: "center", gap: 9,
        }}>
          <Ic path={P.lock} size={15} />
          <span>
            Invoice <b className="mono">{meta.invoiceNo}</b> is approved. Its stock has been
            received, so it can no longer be edited — raise a purchase return to correct it.
          </span>
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "1fr minmax(260px, 320px)", gap: 16, alignItems: "start" }}
        className={`purchase-split pu-body pu-pane-${pane}`}>
        {/* ── Left: header + lines ── */}
        <div style={{ display: "grid", gap: 16 }}>
          <Card className="pu-sec pu-sec-invoice">
            <Section icon={P.doc} title="Invoice Details"
              sub={isEdit ? "Invoice number and batch reference never change." : "Both numbers are generated when you save."}
              right={meta && (
                <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                  <Pill tone="teal">{meta.invoiceNo}</Pill>
                  <Pill tone={statusTone(meta.status)}>{meta.status === "A" ? "APPROVED" : meta.status === "D" ? "DRAFT" : "PENDING"}</Pill>
                </div>
              )} />

            <div className="pu-head-grid">
              <Field label={<>Supplier<Req /></>} error={errors.supplierId}>
                <SearchSelect
                  value={values.supplierId}
                  onChange={(v) => set("supplierId", v)}
                  options={(lookups?.suppliers ?? []).map((s) => ({ id: s.id, name: s.name }))}
                  placeholder="Select supplier…"
                  disabled={readOnly}
                  invalid={!!errors.supplierId}
                  ariaLabel="Supplier"
                  emptyLabel="No supplier matches that." />
                {/* What this supplier can actually sell you, stated as soon as
                    they are chosen — and stated plainly when the answer is
                    "nothing", which is a setup problem rather than a mistake
                    the buyer made. */}
                {selectedSupplier && (
                  selectedSupplier.companyId
                    ? <div className="pu-supplier-note">
                        Supplies <b>{selectedSupplier.companyName}</b>
                        {" · "}{Number(selectedSupplier.productCount || 0).toLocaleString()} product
                        {Number(selectedSupplier.productCount) === 1 ? "" : "s"} on your list
                      </div>
                    : <div className="pu-supplier-note warn">
                        No manufacturer is assigned to {selectedSupplier.name}, so no products can be
                        added. Set their Product Company in Master&nbsp;Data › Suppliers first.
                      </div>
                )}
              </Field>

              <Field label="Purchase Date" error={errors.transactionDate}>
                <input type="date" value={values.transactionDate} max={toDateInput()} disabled={readOnly}
                  className={errors.transactionDate ? "field-error" : ""}
                  onChange={(e) => set("transactionDate", e.target.value)} />
              </Field>

              <Field label="Challan No">
                <input value={values.challanNo} maxLength={50} disabled={readOnly}
                  placeholder="Optional" className="mono"
                  onChange={(e) => set("challanNo", e.target.value)} />
              </Field>

              <Field label={<>Costing Basis<Req /></>} error={errors.purchaseType}>
                <SearchSelect
                  value={values.purchaseType}
                  onChange={(v) => set("purchaseType", v)}
                  options={(lookups?.purchaseTypes ?? []).map((t) => ({ id: t.id, name: t.name }))}
                  disabled={readOnly}
                  invalid={!!errors.purchaseType}
                  ariaLabel="Costing basis" />
              </Field>

              <Field label="Product Type">
                <SearchSelect
                  value={values.productType}
                  onChange={(v) => set("productType", v)}
                  options={(lookups?.productTypes ?? []).map((t) => ({ id: t.id, name: t.name }))}
                  disabled={readOnly}
                  ariaLabel="Product type" />
              </Field>

              {(lookups?.branches ?? []).length > 1 && (
                <Field label="Branch">
                  <SearchSelect
                    value={values.branchId}
                    onChange={(v) => set("branchId", v)}
                    options={[{ id: "", name: "Default (my branch)" },
                              ...(lookups?.branches ?? []).map((b) => ({ id: b.id, name: b.name }))]}
                    placeholder="Default (my branch)"
                    disabled={readOnly}
                    ariaLabel="Branch" />
                </Field>
              )}

              <Field label="Remarks">
                <input value={values.remarks} maxLength={100} disabled={readOnly}
                  placeholder="Optional note"
                  onChange={(e) => set("remarks", e.target.value)} />
              </Field>
            </div>

          </Card>

          {!readOnly && (
            <Card className="pu-sec pu-sec-products">
              <Section icon={P.box} title="Add Products"
                sub="Search by name, code, SKU or barcode — prices come from Product Master" />
              <ProductPicker onPick={addLine} addToast={addToast} inputRef={searchRef}
                supplier={selectedSupplier}
                alreadyAdded={new Set((values.lines ?? []).map((l) => l.productId))} />
            </Card>
          )}

          <Card pad={false} className="pu-sec pu-sec-lines">
            <div style={{ padding: "16px 20px 0" }}>
              <Section icon={P.calc}
                title={`Invoice Lines (${values.lines?.length ?? 0})`}
                sub={<>
                  {values.purchaseType === "MRP"
                    ? "MRP basis: qty × (MRP − (MRP × disc% + flat))"
                    : "MNP basis: qty × (TP + IGTA) − (qty × TP × TP%), then discounts"}
                  {" · "}Batch and expiry are generated on save
                  {" ("}expiry = purchase date + 365 days{")"}
                </>}
                right={<div className="pu-lines-head-right">
                  {errors.lines && <span style={{ color: "var(--clay)", fontSize: 12 }}>{errors.lines}</span>}

                  {/* [TP] One trade-price percentage for the whole invoice.
                      Shown only on the MNP basis, because that is the only
                      formula that reads TP% — on MRP the per-line column is not
                      rendered either, and a control that changes nothing is
                      worse than one that is absent. */}
                  {!readOnly && values.purchaseType !== "MRP" && (values.lines?.length ?? 0) > 0 && (
                    <div className="pu-bulk-tp">
                      <label htmlFor="pu-bulk-tp">TP % for all lines</label>
                      <div className="pu-bulk-tp-controls">
                        <input
                          id="pu-bulk-tp"
                          type="number" step="1" min="0" max="100"
                          className="mono"
                          placeholder="0"
                          value={bulkTp}
                          onChange={(e) => setBulkTp(e.target.value)}
                          onKeyDown={(e) => {
                            // Enter applies, so the whole thing is one reach of
                            // the hand: type, Enter, back to the product search.
                            if (e.key === "Enter") { e.preventDefault(); applyTpToAllLines(); }
                          }}
                          title="Type one TP % and apply it to every line. Each line stays editable afterwards." />
                        <button type="button" className="btn btn-ghost btn-sm"
                          onClick={applyTpToAllLines}
                          disabled={String(bulkTp).trim() === ""}
                          title={String(bulkTp).trim() === ""
                            ? "Type a TP % first"
                            : `Set TP ${bulkTp}% on all ${values.lines.length} line${values.lines.length === 1 ? "" : "s"}`}>
                          <Ic path={P.check} size={13} /> Apply to all
                        </button>
                      </div>
                    </div>
                  )}
                </div>} />
            </div>

            {!values.lines?.length ? (
              <div className="empty-state">
                <div style={{ marginBottom: 4 }}>No lines yet.</div>
                <div style={{ fontSize: 12.5 }}>Search for a product above to add the first one.</div>
              </div>
            ) : (
              <div className="table-wrap pu-lines-wrap" style={{ padding: "0 20px" }}>
                <table className="data-table pu-lines-table" style={{ fontSize: 12.5 }}>
                  <thead>
                    <tr>
                      <th className="pu-th-product">Product</th>
                      <th className="pu-th-qty" style={{ textAlign: "right" }}>Qty<Req /></th>
                      {/* [V67] "Buy 10, get 12" — the 2 goes here. It reaches the
                          shelf and lowers the cost per unit; it never touches
                          the line's money. */}
                      <th className="pu-th-bonus" style={{ textAlign: "right" }}>Bonus</th>
                      <th className="pu-th-unit">Unit</th>
                      <th className="pu-th-price" style={{ textAlign: "right" }}>
                        {values.purchaseType === "MRP" ? "MRP" : "TP + IGTA"}
                      </th>
                      {values.purchaseType !== "MRP" && <th className="pu-th-tp" style={{ textAlign: "right" }}>TP %</th>}
                      <th className="pu-th-disc" style={{ textAlign: "right" }}>Disc %</th>
                      <th className="pu-th-flat" style={{ textAlign: "right" }}>Flat</th>
                      <th className="pu-th-total" style={{ textAlign: "right" }}>Total</th>
                      {!readOnly && <th className="pu-th-remove" />}
                    </tr>
                  </thead>
                  <tbody>
                    {values.lines.map((l) => (
                      <LineRow
                        key={l.key} line={l} purchaseType={values.purchaseType}
                        errors={lineErrors[l.key] ?? {}} readOnly={readOnly}
                        salesTypes={lookups?.salesTypes ?? []}
                        onChange={(patch) => updateLine(l.key, patch)}
                        onLineKeyDown={onLineKeyDown}
                        onRemove={() => removeLine(l.key)} />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div style={{ height: 16 }} />
          </Card>
        </div>

        {/* ── Right: sticky totals ── */}
        {/* Layout in the stylesheet, not inline: an inline display beats every
            selector, and the pane rules below the desktop breakpoint have to be
            able to hide this rail. */}
        <div className="pu-sec pu-sec-total">
          <Card>
            <Section icon={P.wallet} title="Totals" />

            <div style={{ display: "grid", gap: "0 12px" }}>
              <TotalRow label="Line total" value={money(totals.totalAmount)} />

              {/* What the lines already gave away, before the invoice cut.
                  It is netted into "Line total" above, so it is shown as a
                  fact rather than as another subtraction — otherwise the
                  column reads as if it were deducted twice. */}
              {totals.lineDiscount > 0 && (
                <TotalRow label="Line discount" value={money(totals.lineDiscount)} tone="clay" />
              )}

              <Field label="Invoice Discount %" error={errors.invoiceDisPct}>
                <input type="number" step="1" min="0" max="100" className="mono"
                  value={values.invoiceDisPct} disabled={readOnly} placeholder="0"
                  onChange={(e) => set("invoiceDisPct", e.target.value)} />
              </Field>
              <TotalRow label="Invoice discount" value={`− ${money(totals.invoiceDisAmt)}`} tone="clay" />

              {/* Line + invoice. This was missing entirely from the entry
                  screen — the figure existed in the totals and was only ever
                  printed on a saved invoice, so while typing one nobody could
                  see what the bill had actually given away. */}
              {totals.totalDiscount > 0 && (
                <TotalRow label="Total discount" value={money(totals.totalDiscount)} tone="clay" strong />
              )}

              <Field label="Tax" error={errors.totalTax}>
                <input type="number" step="1" min="0" className="mono"
                  value={values.totalTax} disabled={readOnly} placeholder="0"
                  onChange={(e) => set("totalTax", e.target.value)} />
              </Field>

              <Field label="Service Charge" error={errors.serviceCharge}>
                <input type="number" step="1" min="0" className="mono"
                  value={values.serviceCharge} disabled={readOnly} placeholder="0"
                  onChange={(e) => set("serviceCharge", e.target.value)} />
              </Field>

              {/* [V68] Cashback sits beside the charges rather than with the
                  discounts, because it is not a discount: it reduces what is
                  owed AND what the goods cost, and an accountant reading the
                  invoice later needs the two told apart. */}
              <Field label="Supplier Cashback" error={errors.cashbackAmount}
                     hint="Incentive taken off this bill — lowers the cost of the goods too">
                <input type="number" step="1" min="0" className="mono"
                  value={values.cashbackAmount} disabled={readOnly} placeholder="0"
                  onChange={(e) => set("cashbackAmount", e.target.value)} />
              </Field>
              {Number(values.cashbackAmount) > 0 && (
                <TotalRow label="Supplier cashback" value={`− ${money(totals.cashbackAmount)}`} tone="sage" />
              )}

              <div style={{
                margin: "6px 0 12px", padding: "12px 14px", borderRadius: "var(--radius-sm)",
                background: "var(--teal-soft)", border: "1px solid var(--teal)",
              }}>
                <div style={{ fontSize: 11, color: "var(--teal)", fontWeight: 700, letterSpacing: ".06em" }}>
                  AMOUNT TO BE PAID
                </div>
                <div className="kpi-value" style={{ fontSize: 24, color: "var(--teal)", marginTop: 4 }}>
                  {money(totals.amountToBePaid)}
                </div>
              </div>

              {/* Payment method sits between what is owed and what was handed
                  over, because that is the order the decision is actually made:
                  see the amount, choose how it is paid, enter what was given.
                  Tiles rather than a dropdown — six fixed options worth seeing
                  at a glance, and one tap on touch instead of open-scroll-select. */}
              <div style={{ margin: "2px 0 14px" }}>
                <div className="card-eyebrow" style={{ marginBottom: 8 }}>Payment Method</div>
                <div style={{
                  display: "grid", gap: 7,
                  gridTemplateColumns: "repeat(auto-fit, minmax(78px, 1fr))",
                }}>
                  {(lookups?.paymentTypes ?? []).map((t) => {
                    const active = values.paymentType === t.id;
                    return (
                      <button key={t.id} type="button" disabled={readOnly}
                        aria-pressed={active}
                        onClick={() => set("paymentType", t.id)}
                        style={{
                          padding: "10px 8px", borderRadius: 10, minHeight: 46,
                          border: `1.5px solid ${active ? "var(--amber)" : "var(--line)"}`,
                          background: active ? "var(--amber-soft)" : "var(--paper)",
                          color: active ? "var(--amber-deep)" : "var(--ink)",
                          fontSize: 12.5, fontWeight: active ? 700 : 600,
                          cursor: readOnly ? "not-allowed" : "pointer",
                          opacity: readOnly ? 0.6 : 1,
                          transition: "all .12s", lineHeight: 1.25,
                        }}>
                        {t.name}
                      </button>
                    );
                  })}
                </div>
              </div>

              <Field label="Given Amount" error={errors.givenAmount}>
                <input type="number" step="1" min="0" className="mono"
                  value={values.givenAmount} disabled={readOnly} placeholder="0"
                  onChange={(e) => set("givenAmount", e.target.value)} />
              </Field>

              {/* Cash over the bill that is not the invoice discount. Without
                  this the save is refused and there is no way forward from the
                  form — the server can only accept an advance that somebody
                  declared, and this is where they declare it. */}
              {!readOnly && !values.payInvoiceDiscount && overpay > 0.005 && (() => {
                const typed = Number(values.advanceAmount) > 0;
                const invDisc = totals.invoiceDisAmt || 0;
                /* The cash over the bill is at least the invoice discount: the
                   likely reading is that the discount was handed across. Only
                   that slice is an advance; anything beyond it is line
                   discount the supplier never charged, so the amount, not the
                   advance, is what has to change. Offering the whole overpay
                   as the advance — as this once did — booked ৳30.36 as
                   recoverable on a bill whose invoice discount was ৳19.12. */
                const discountInIt = invDisc > 0 && overpay + 0.005 >= invDisc;
                const rest = Math.max(0, overpay - invDisc);
                const payDiscount = () => {
                  setValues((v) => ({
                    ...v, payInvoiceDiscount: true, advanceAmount: "",
                    givenAmount: v.paymentType === "CREDIT"
                      ? Math.max(0, (Number(v.givenAmount) || 0) - rest).toFixed(2)
                      : (totals.amountToBePaid + invDisc).toFixed(2),
                  }));
                  setDirty(true);
                };
                return (
                <div style={{ padding: "9px 11px", borderRadius: 9, marginTop: 8,
                              background: typed ? "var(--sage-soft)" : "var(--amber-soft)",
                              border: `1px solid ${typed ? "var(--sage)" : "var(--amber-deep)"}` }}>
                  <div style={{ fontSize: 12, lineHeight: 1.5 }}>
                    <b>{money(overpay)} more than this bill.</b>{" "}
                    {typed
                      ? <>
                          Recorded as an advance the supplier owes back — it will appear under Supplier Payment → Advances once approved.
                          {invDisc > 0 && Number(values.advanceAmount) > invDisc + 0.005 && (
                            <span style={{ color: "var(--ink-soft)" }}>
                              {" "}Only the invoice discount ({money(invDisc)}) is normally handed across and returned;
                              line discounts were never charged.
                            </span>
                          )}
                        </>
                      : discountInIt
                        ? <span style={{ color: "var(--ink-soft)" }}>
                            {money(invDisc)} of it is the invoice discount, which the supplier returns.
                            {rest > 0.005 && <> The other {money(rest)} is line discount the supplier never
                              charged — paying it means handing over {money(rest)} for nothing.</>}
                          </span>
                        : <span style={{ color: "var(--ink-soft)" }}>
                            If the supplier is returning it, record it as an advance. Otherwise correct
                            the amount — an undeclared overpayment is refused on save.
                          </span>}
                  </div>
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center", marginTop: 8 }}>
                    {!typed && discountInIt && (
                      <button type="button" className="btn btn-primary btn-sm" onClick={payDiscount}>
                        Pay the invoice discount too — {money(invDisc)}
                      </button>
                    )}
                    {!typed && (
                      <button type="button" className="btn btn-ghost btn-sm"
                        onClick={() => set("givenAmount", totals.amountToBePaid.toFixed(2))}>
                        Correct the amount to {money(totals.amountToBePaid)}
                      </button>
                    )}
                    <button type="button" className="btn btn-ghost btn-sm"
                      onClick={() => { set("advanceAmount", typed ? "" : overpay.toFixed(2)); }}>
                      {typed ? "Not an advance" : `Record all ${money(overpay)} as an advance`}
                    </button>
                    {typed && (
                      <input type="number" step="1" min="0" className="mono"
                        style={{ ...inputStyle, width: 120 }}
                        value={values.advanceAmount}
                        onChange={(e) => set("advanceAmount", e.target.value)} />
                    )}
                  </div>
                </div>
                );
              })()}

              {/* Only where there is an invoice discount to hand across. The
                  line discounts and TP% are not offered: they are the
                  supplier's price, not money the supplier holds. */}
              {!readOnly && totals.invoiceDisAmt > 0 && (
                <label style={{ display: "flex", gap: 8, alignItems: "flex-start", cursor: "pointer",
                                padding: "9px 11px", borderRadius: 9, marginTop: 8,
                                background: values.payInvoiceDiscount ? "var(--sage-soft)" : "var(--paper)",
                                border: `1px solid ${values.payInvoiceDiscount ? "var(--sage)" : "var(--line)"}` }}>
                  <input type="checkbox" checked={!!values.payInvoiceDiscount} style={{ marginTop: 2 }}
                    onChange={(e) => {
                      const on = e.target.checked;
                      // The amount follows the tick, so what is on screen is
                      // always what will be handed over.
                      setValues((v) => ({
                        ...v,
                        payInvoiceDiscount: on,
                        // The two routes are exclusive: leaving a typed advance
                        // behind when the tick goes on would have the server
                        // refuse them as contradicting each other.
                        advanceAmount: on ? "" : v.advanceAmount,
                        /* On CREDIT the discount is added to what is actually
                           being handed over, because only part of a credit bill
                           is paid now — everything else settles in full, so the
                           amount is the gross total. Unticking reverses whichever
                           of the two was applied. */
                        givenAmount: v.paymentType === "CREDIT"
                          ? Math.max(0, (Number(v.givenAmount) || 0)
                              + (on ? totals.invoiceDisAmt : -totals.invoiceDisAmt)).toFixed(2)
                          : on
                            ? (totals.amountToBePaid + totals.invoiceDisAmt).toFixed(2)
                            : totals.amountToBePaid.toFixed(2),
                      }));
                      setDirty(true);
                    }} />
                  {/* Short on purpose. The amount was printed three times on
                      one panel — here, again in the explanation, and again as
                      "Of which advance" in the totals — and the explanation ran
                      to four lines for a tickbox. It says what is paid, what
                      comes back, and nothing else. */}
                  {/* The invoice slice only. The line discounts and TP% are
                      the supplier's own price and were never charged; the
                      invoice cut is the one handed across and returned later.
                      Supplier Payment offers the same figure for the same tick. */}
                  <span style={{ fontSize: 12, lineHeight: 1.5 }}>
                    <b>Pay the invoice discount too — {money(totals.invoiceDisAmt)}</b>
                    <br />
                    <span style={{ color: "var(--ink-soft)" }}>
                      Pay {money(totals.amountToBePaid + totals.invoiceDisAmt)} instead
                      of {money(totals.amountToBePaid)}. The difference becomes an
                      advance <b>the supplier owes back</b> — it settles no more of this bill.
                    </span>
                  </span>
                </label>
              )}

              {/* Only the non-advance part settles the bill, so the due here
                  has to be read the same way the server reads it. Using the
                  raw given amount would show a discounted invoice as fully
                  paid on the strength of money the supplier is holding. */}
              <TotalRow
                label={dueAfterAdvance >= 0 ? "Due" : "Change"}
                value={money(Math.abs(dueAfterAdvance))}
                tone={dueAfterAdvance > 0 ? "clay" : "sage"} strong />
              {/* Shown only for an advance that was typed. When it came from
                  the tickbox, the block directly above already names the same
                  amount and says it settles no more of this bill — repeating
                  it here printed the figure a third time on one panel and
                  explained nothing the reader had not just been told. */}
              {invoiceAdvance > 0 && !values.payInvoiceDiscount && (
                <TotalRow label="Of which advance" value={money(invoiceAdvance)} tone="sage" />
              )}
            </div>

            <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 12, lineHeight: 1.55 }}>
              These figures update as you type. The server recalculates all of them
              when you save.
            </div>
          </Card>

          {!readOnly && (
            <Card>
              <div className="pu-actions">
                {/* Save as Draft is the only way to save from here now.
                    "Save as Pending" and "Save & Approve" were removed at the
                    user's request — approving from the entry form receives
                    stock in the same click that creates the invoice, with no
                    step in between at which anyone checks it. Both statuses
                    are still reachable: Pending through Submit for Approval
                    below, and approval from the invoice itself once saved. */}
                <button className="btn btn-primary"
                  disabled={saving || !canSave} onClick={() => save("D")}
                  title={canSave ? "Save this invoice as a draft you can still edit"
                                 : "Add a supplier and at least one line first"}>
                  <Ic path={P.save} size={14} />
                  {saving ? "Saving…" : "Save as Draft"}
                </button>

                {/* Kept for someone who cannot approve: without it they would
                    have no way to hand the invoice on at all. */}
                {!perm.canApprove && (
                  <button className="btn btn-ghost"
                    disabled={saving || !canSave} onClick={() => save("P")}
                    title={canSave ? "Hand this invoice on for someone else to approve"
                                   : "Add a supplier and at least one line first"}>
                    <Ic path={P.send} size={14} />
                    Submit for Approval
                  </button>
                )}

                <button className="btn btn-ghost btn-sm" onClick={cancel} disabled={saving}
                  title="Discard this invoice and start again">
                  <Ic path={P.x} size={13} />
                  Cancel
                </button>
              </div>

              <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 10, lineHeight: 1.55 }}>
                {/* The note has to describe the buttons that are actually
                    here. It told approvers about a Pending button they no
                    longer have, and left them with no idea where approval
                    moved to. */}
                {perm.canApprove
                  ? "Saving does not move stock. Approve the invoice from the list to receive it, "
                    + "which also locks it against further editing."
                  : "You can create and edit invoices freely. An administrator approves them, which receives the stock and locks the invoice."}
              </div>
            </Card>
          )}
        </div>
      </div>

      {/* [POS] The bottom bar, below the desktop breakpoint only — the stylesheet
          hides it once every pane fits side by side, exactly as QuickSales does
          with its own. Same metrics, same states, same 48px targets, so the two
          screens feel like one application on a phone.

          The tabs carry their own state for the same reason QuickSales' do: a
          buyer entering products can see the line count and what the invoice
          now comes to without leaving the pane to find out. */}
      <nav className="pu-tabs" aria-label="Purchase sections">
        <button type="button" className={`pu-tab ${pane === "invoice" ? "is-on" : ""}`}
          onClick={() => setPane("invoice")} aria-current={pane === "invoice"}>
          <Ic path={P.doc} size={18} />
          <span>Invoice</span>
          {!values.supplierId && <em className="pu-tab-dot" title="No supplier chosen yet" />}
        </button>

        {!readOnly && (
          <button type="button" className={`pu-tab ${pane === "products" ? "is-on" : ""}`}
            onClick={() => setPane("products")} aria-current={pane === "products"}>
            <Ic path={P.box} size={18} />
            <span>Products</span>
          </button>
        )}

        <button type="button" className={`pu-tab ${pane === "lines" ? "is-on" : ""}`}
          onClick={() => setPane("lines")} aria-current={pane === "lines"}>
          <Ic path={P.calc} size={18} />
          <span>Lines</span>
          {(values.lines?.length ?? 0) > 0 && (
            <em className="pu-tab-badge">{values.lines.length}</em>
          )}
        </button>

        <button type="button" className={`pu-tab ${pane === "total" ? "is-on" : ""}`}
          onClick={() => setPane("total")} aria-current={pane === "total"}>
          <Ic path={P.wallet} size={18} />
          {/* The amount rather than the word, once there is one — the same
              choice QuickSales makes on its Pay tab. */}
          <span>{totals.amountToBePaid > 0 ? money(totals.amountToBePaid) : "Totals"}</span>
        </button>
      </nav>
    </>
  );
}

const TotalRow = ({ label, value, tone, strong }) => (
  <div style={{
    display: "flex", justifyContent: "space-between", alignItems: "baseline",
    padding: "7px 0", borderBottom: "1px solid var(--line)",
  }}>
    <span style={{ fontSize: 12, color: "var(--ink-soft)" }}>{label}</span>
    <span className="mono" style={{
      fontSize: strong ? 15 : 13, fontWeight: strong ? 800 : 600,
      color: tone ? `var(--${tone})` : "var(--ink)",
    }}>{value}</span>
  </div>
);

/**
 * The landed cost of a purchase line, per box, strip and piece.
 *
 * `totalPrice ÷ pieces`, computed server-side, so it carries the trade price,
 * IGTA and the line discount together — the figure the stock is valued at and
 * profit is measured against. The TP column beside it is the rate *before* any
 * of that, which is why the two differ.
 *
 * A dash means the product has no packing configured for that unit, not zero.
 */
function BuyPrice({ cost }) {
  if (!cost || cost.costPerPcs == null) {
    return <span style={{ color: "var(--ink-soft)" }}>—</span>;
  }
  const row = (label, value) => (
    <div style={{ display: "flex", justifyContent: "space-between", gap: 8 }}>
      <span style={{ fontSize: 9.5, textTransform: "uppercase", letterSpacing: ".04em",
                     color: "var(--ink-soft)" }}>{label}</span>
      <span className="mono">{value == null ? "—" : money(value)}</span>
    </div>
  );
  return (
    /* Every reduction is in this figure, and the tooltip has to say so: the
       line total already carries TP%, the line discount and any flat amount,
       and the server then takes off this line's share of the invoice discount
       before dividing by the pieces. It is the cost the stock is actually
       valued at. The old wording named only two of the four. */
    <div style={{ fontSize: 11, lineHeight: 1.3, minWidth: 92 }}
         title="Landed cost per unit — trade price and IGTA, less TP%, the line discount and this line's share of the invoice discount">
      {row("Box", cost.costPerBox)}
      {row("Strip", cost.costPerStrip)}
      {row("Pcs", cost.costPerPcs)}
    </div>
  );
}

/* ── One editable line ── */
function LineRow({ line, purchaseType, errors, readOnly, salesTypes, onChange, onRemove, onLineKeyDown }) {
  const total = calculateLineTotal({ ...line, purchaseType });
  const pcs = piecesFor(line.salesType, line);

  /* [V67] What arrived, and what it really cost.
     The bonus never enters `total` — the line is priced for what was bought —
     so the effective rate is simply that money spread over more units. This is
     the same division the server does when it values the batch, which is why
     the figure on screen is the one COGS will use. */
  const isGift = line.lineKind === "GIFT";
  const received = (Number(line.quantity) || 0) + (Number(line.bonusQty) || 0);
  const effectiveRate = received > 0 ? total / received : 0;

  // Pieces follow the goods, not the invoice: the free units are on the shelf.
  const totalPcs = received * pcs;
  // Line prices are quoted in the line's own unit, so its MRP divides by the
  // pieces in that unit — not by the pieces in a box.
  const mrpPerPc = pcs > 0 ? (Number(line.mrpPrice) || 0) / pcs : 0;
  const aboveMrp = mrpPerPc > 0 && totalPcs > 0 && total / totalPcs > mrpPerPc;
  const cell = { width: 70, ...inputStyle, padding: "5px 7px", textAlign: "right" };

  return (
    <tr className="pu-line-row" onKeyDown={onLineKeyDown}>
      <td className="pu-td-product" style={{ minWidth: 190 }}>
        <div style={{ fontWeight: 700 }}>{line.productName}</div>
        <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
          <span className="mono">{line.productCode}</span>
          {line.genericName ? ` · ${line.genericName}` : ""}
        </div>
        {/* [V69] A gift is a line with no money: the supplier gave this rather
            than sold it. Ticking it clears the quantity, because a gift is not
            bought — leaving a stale 10 behind is how the save gets refused for
            a shape nobody chose. */}
        {!readOnly && (
          <label style={{ display: "inline-flex", alignItems: "center", gap: 5, marginTop: 3,
                          fontSize: 10.5, color: isGift ? "var(--amber-deep)" : "var(--ink-soft)",
                          fontWeight: isGift ? 700 : 400, cursor: "pointer" }}>
            <input type="checkbox" checked={isGift} style={{ margin: 0 }}
              onChange={(e) => {
                const gift = e.target.checked;
                onChange(gift
                  ? { lineKind: "GIFT", quantity: 0 }
                  : { lineKind: "PURCHASE", quantity: 1 });
                /* Ticking the box disables the quantity beside it, and if the
                   cursor was in that box it is left on nothing. Hand it to the
                   field that now matters — Bonus on a gift, Quantity back again
                   on an ordinary line — after the render that flips disabled,
                   or focus lands on an element the browser is about to refuse. */
                const sel = gift ? "data-bonus-for" : "data-qty-for";
                requestAnimationFrame(() => {
                  const el = document.querySelector(`[${sel}="${line.key}"]`);
                  if (el) { el.focus(); el.select?.(); }
                });
              }} />
            Free gift
          </label>
        )}
        {readOnly && isGift && (
          <div style={{ fontSize: 10, fontWeight: 700, color: "var(--amber-deep)", marginTop: 2 }}>
            FREE GIFT
          </div>
        )}
      </td>

      <td data-label="Qty" style={{ textAlign: "right" }}>
        {/* Whole units only, and that now includes what can be typed. A
            purchase line is boxes or strips off a supplier invoice and nobody
            orders 0.01 of one; min was 0.0001, so Down from 1 clamped onto the
            minimum and put a fraction in the field. */}
        {/* [V69] A gift is not bought, so its purchase quantity is zero and
            there is nothing to type here. Disabled rather than merely floored at
            zero: an editable box that refuses every value the user puts in it
            is worse than one that plainly says it is not for them. The units
            they mean go in Bonus, one column to the right. */}
        <QtyInput
          value={isGift ? 0 : line.quantity}
          data-qty-for={line.key}
          onChange={(v) => onChange({ quantity: v })}
          min={isGift ? 0 : 1}
          step={1}
          integer
          disabled={readOnly || isGift}
          title={isGift ? "A free gift has no purchase quantity — put the units in Bonus" : undefined}
          className={`mono ${errors.quantity ? "field-error" : ""}`}
          ariaLabel="Line quantity"
        />
        {errors.quantity && <div style={{ color: "var(--clay)", fontSize: 10 }}>{errors.quantity}</div>}
      </td>

      {/* [V67] Free units. Whole units like the quantity beside it — a supplier
          gives away boxes, not fractions of one — and blank rather than 0 when
          there is no bonus, so a column of zeros does not read as data. */}
      <td data-label="Bonus" style={{ textAlign: "right" }}>
        <QtyInput
          value={line.bonusQty ?? ""}
          data-bonus-for={line.key}
          onChange={(v) => onChange({ bonusQty: v })}
          min={0}
          step={1}
          integer
          disabled={readOnly}
          className="mono"
          ariaLabel="Bonus quantity"
        />
        {received > Number(line.quantity || 0) && (
          <div className="mono" style={{ fontSize: 9.5, color: "var(--sage)", marginTop: 2 }}
               title="What actually reaches the shelf, and what the cost is spread over">
            {received} recd
          </div>
        )}
      </td>

      <td data-label="Unit">
        <select value={line.salesType} disabled={readOnly} style={{ ...inputStyle, padding: "5px 7px", width: 82 }}
          onChange={(e) => onChange({
            salesType: e.target.value,
            // The price has to move with the unit. Switching to PCS and leaving
            // the box price behind is how a tablet ends up costing more than
            // the box, and the receipt is then refused at approval — long after
            // the person who could explain it has moved on.
            ...pricesForUnit(e.target.value, line),
          })}>
          {(salesTypes.length ? salesTypes : [{ id: "PCS", name: "Pieces" }])
            .map((t) => <option key={t.id} value={t.id}>{t.id}</option>)}
        </select>
        <div className="mono" style={{ fontSize: 10, color: "var(--ink-soft)", marginTop: 2 }}>
          {totalPcs > 0 ? `${totalPcs} pcs` : "—"}
        </div>
        {totalPcs > 0 && total > 0 && (
          // The per-piece cost against the per-piece MRP — the same comparison
          // approval makes, shown while it can still be corrected.
          <div className="mono" style={{ fontSize: 10, marginTop: 1,
              color: aboveMrp ? "var(--clay)" : "var(--ink-soft)",
              fontWeight: aboveMrp ? 700 : 400 }}
            title={aboveMrp
              ? "Costs more per piece than it may be sold for — approval will refuse this"
              : "Cost per piece, against MRP per piece"}>
            {(total / totalPcs).toFixed(2)}/pc{mrpPerPc > 0 ? ` vs ${mrpPerPc.toFixed(2)}` : ""}
          </div>
        )}
      </td>

      {/* The buy price for the unit actually chosen. `money` rounds to whole
          taka, which hides the difference entirely on a piece price — a tablet
          at 15.6175 and one at 16 look the same and multiply out differently. */}
      <td data-label={purchaseType === "MRP" ? "MRP" : "TP"} className="mono" style={{ textAlign: "right", whiteSpace: "nowrap" }}>
        {purchaseType === "MRP"
          ? <>
              {fine(line.mrpPrice)}
              <div style={{ fontSize: 10, color: "var(--ink-soft)" }}>MRP / {line.salesType}</div>
            </>
          : <>
              {fine(line.tpPrice)}
              <div style={{ fontSize: 10, color: "var(--ink-soft)" }}>
                + {fine(line.igta)} · per {line.salesType}
              </div>
            </>}
        {/* [V67] The number the bonus exists to change. Shown only when there
            is a bonus, because with none it is the rate directly above it. */}
        {received > (Number(line.quantity) || 0) && effectiveRate > 0 && (
          <div className="mono" style={{ fontSize: 10, fontWeight: 700, color: "var(--sage)", marginTop: 2 }}
               title={`${money(total)} over ${received} ${(line.salesType || "unit").toLowerCase()} — what the shelf is valued at and what a sale will cost against`}>
            {fine(effectiveRate)} eff.
          </div>
        )}
        {line.pcsPerBox > 1 && line.salesType !== "BOX" && (
          // Where the figure came from, because the master quotes a box and a
          // scaled-down price otherwise looks like somebody mistyped it.
          <div style={{ fontSize: 9.5, color: "var(--ink-soft)" }}
            title={`The product master prices a box of ${line.pcsPerBox}. This is that price for one ${line.salesType}.`}>
            box of {line.pcsPerBox}
          </div>
        )}
      </td>

      {purchaseType !== "MRP" && (
        <td data-label="TP %" style={{ textAlign: "right" }}>
          <input type="number" step="1" min="0" max="100" value={line.tpPct} disabled={readOnly}
            data-tp-for={line.key}
            placeholder="0" className={`mono ${errors.tpPct ? "field-error" : ""}`} style={cell}
            onChange={(e) => onChange({ tpPct: e.target.value })} />
        </td>
      )}

      <td data-label="Disc %" style={{ textAlign: "right" }}>
        <input type="number" step="1" min="0" max="100" value={line.discountPct} disabled={readOnly}
          placeholder="0" className={`mono ${errors.discountPct ? "field-error" : ""}`} style={cell}
          onChange={(e) => onChange({ discountPct: e.target.value })} />
      </td>

      <td data-label="Flat" style={{ textAlign: "right" }}>
        <input type="number" step="1" min="0" value={line.flatDiscount} disabled={readOnly}
          placeholder="0" className={`mono ${errors.flatDiscount ? "field-error" : ""}`} style={cell}
          onChange={(e) => onChange({ flatDiscount: e.target.value })} />
        {errors.flatDiscount && <div style={{ color: "var(--clay)", fontSize: 10 }}>{errors.flatDiscount}</div>}
      </td>

      <td data-label="Total" className="mono pu-td-total" style={{ textAlign: "right", fontWeight: 700, whiteSpace: "nowrap" }}>
        {money(total)}
      </td>

      {!readOnly && (
        <td className="pu-td-remove">
          <button className="icon-btn pu-row-del" onClick={onRemove}
                  title={`Remove ${line.productName || "this line"} from the invoice`}>
            <Ic path={P.trash} size={13} />
          </button>
        </td>
      )}
    </tr>
  );
}

/* ── Type-ahead product picker ── */
function ProductPicker({ onPick, addToast, alreadyAdded, inputRef, supplier }) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const boxRef = useRef(null);

  /* Nothing can be searched until the invoice says who it is from: a supplier
     decides which manufacturer's products may go on it, so a picker offered
     before that would list things the save is going to refuse. */
  const blocked = !supplier ? "Choose a supplier first — the supplier decides which products can go on this invoice."
    : !supplier.companyId ? `${supplier.name} has no manufacturer assigned, so there is nothing to add. Set their Product Company in Master Data › Suppliers.`
    : null;

  /* Debounced so typing does not fire a request per keystroke. */
  useEffect(() => {
    if (blocked) { setResults([]); setOpen(false); return undefined; }
    if (!q.trim()) { setResults([]); setOpen(false); return undefined; }
    /* 150ms, not 300. At a typing speed anyone entering invoices all day
       reaches, 300 meant the list arrived after the next keystroke had already
       been pressed — so the operator waited on their own hands. */
    const t = setTimeout(async () => {
      setLoading(true);
      try {
        const rows = await searchProducts(q.trim(), 15, supplier?.id ?? null);
        setResults(rows ?? []);
        setHighlight(0);
        setOpen(true);
      } catch (e) {
        addToast(e.message || "Product search failed.", "error");
      } finally { setLoading(false); }
    }, 150);
    return () => clearTimeout(t);
  }, [q, supplier?.id, blocked]); // eslint-disable-line react-hooks/exhaustive-deps

  /* Close on an outside click. */
  useEffect(() => {
    const h = (e) => { if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, []);

  const pick = (p) => {
    onPick(p);
    setQ(""); setResults([]); setOpen(false);
    /* The parent moves the cursor to the new line's quantity; this only clears
       the box behind it, so that pressing Enter twice — once to add, once to
       accept the quantity of 1 — lands back here ready to type the next name. */
  };

  const onKeyDown = (e) => {
    if (!open || !results.length) return;
    if (e.key === "ArrowDown") { e.preventDefault(); setHighlight((h) => Math.min(h + 1, results.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setHighlight((h) => Math.max(h - 1, 0)); }
    else if (e.key === "Enter") { e.preventDefault(); pick(results[highlight]); }
    else if (e.key === "Escape") setOpen(false);
  };

  return (
    <div ref={boxRef} style={{ position: "relative" }}>
      <div className="search-box">
        <Ic path={P.search} size={14} />
        <input
          ref={inputRef}
          autoFocus
          disabled={!!blocked}
          value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onKeyDown}
          onFocus={() => results.length && setOpen(true)}
          placeholder={blocked
            ? "Choose a supplier first…"
            : `Search ${supplier.companyName} products by name, code, SKU or barcode…`}
          aria-label="Search products to add"
          style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1, color: "var(--ink)" }} />
        {loading && <span style={{ fontSize: 11, color: "var(--ink-soft)" }}>searching…</span>}
        {q && !loading && (
          <button onClick={() => { setQ(""); setOpen(false); }} aria-label="Clear"
            style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>
        )}
      </div>

      {blocked && <div className="pu-picker-blocked">{blocked}</div>}

      {open && (
        <div style={{
          position: "absolute", top: "100%", left: 0, right: 0, zIndex: 40, marginTop: 4,
          background: "var(--card)", border: "1px solid var(--line)",
          borderRadius: "var(--radius-sm)", boxShadow: "var(--shadow-lg)",
          maxHeight: 320, overflowY: "auto",
        }}>
          {!results.length ? (
            <div style={{ padding: 16, fontSize: 13, color: "var(--ink-soft)", textAlign: "center" }}>
              No products match “{q}”.
            </div>
          ) : results.map((p, i) => {
            const added = alreadyAdded.has(p.id);
            return (
              <button key={p.id} onMouseEnter={() => setHighlight(i)} onClick={() => pick(p)}
                style={{
                  display: "flex", width: "100%", textAlign: "left", gap: 10, alignItems: "center",
                  padding: "9px 12px", border: "none", cursor: "pointer",
                  background: i === highlight ? "var(--paper)" : "transparent",
                  borderBottom: "1px solid var(--line)",
                }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 700, display: "flex", gap: 6, alignItems: "center" }}>
                    {p.productName}
                    {added && <Pill tone="grey">added</Pill>}
                  </div>
                  <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
                    <span className="mono">{p.productCode}</span>
                    {p.genericName ? ` · ${p.genericName}` : ""}
                    {p.companyName ? ` · ${p.companyName}` : ""}
                  </div>
                </div>
                <div className="mono" style={{ fontSize: 11, textAlign: "right", color: "var(--ink-soft)", whiteSpace: "nowrap" }}>
                  <div>TP {money(p.tpPrice)}</div>
                  <div>MRP {money(p.mrpPrice)}</div>
                  {p.pcsPerBox > 1 && (
                    <div style={{ fontSize: 10 }}>per box of {p.pcsPerBox}</div>
                  )}
                </div>
              </button>
            );
          })}
        </div>
      )}

      <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 8 }}>
        ↑ ↓ to move, Enter to add, Esc to close.
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   HISTORY
   ═══════════════════════════════════════════════════════════════════════════ */

const EMPTY_FILTERS = {
  search: "", supplierId: "", status: "ALL",
  purchaseType: "ALL", productType: "ALL",
  dateFrom: "", dateTo: "", dueOnly: false,
};

const STATUS_CHIPS = [
  { key: "ALL", label: "All", tone: "var(--teal)" },
  { key: "D", label: "Draft", tone: "var(--ink-soft)" },
  { key: "P", label: "Pending", tone: "var(--amber)" },
  { key: "A", label: "Approved", tone: "var(--sage)" },
];

function PurchaseHistory({ lookups, perm, addToast, onView, onEdit, onNew }) {
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [searchInput, setSearchInput] = useState("");
  const [showFilters, setShowFilters] = useState(false);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sortBy, setSortBy] = useState("transactionDate");
  const [sortDir, setSortDir] = useState("desc");
  const [busyId, setBusyId] = useState(null);
  const [confirm, setConfirm] = useState(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setFilters((f) => (f.search === searchInput ? f : { ...f, search: searchInput }));
      setPage(0);
    }, 350);
    return () => clearTimeout(t);
  }, [searchInput]);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const res = await getPurchases({ ...filters, page, size, sortBy, sortDir });
      setRows(res.items); setTotal(res.total); setTotalPages(res.totalPages);
    } catch (e) {
      setError(e.message || "Could not load purchases."); setRows([]); setTotal(0);
    } finally { setLoading(false); }
  }, [filters, page, size, sortBy, sortDir]);

  useEffect(() => { load(); }, [load]);

  const setFilter = (k, v) => { setFilters((f) => ({ ...f, [k]: v })); setPage(0); };
  const clearFilters = () => { setFilters(EMPTY_FILTERS); setSearchInput(""); setPage(0); };

  const toggleSort = (field) => {
    if (!field) return;
    if (sortBy === field) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else { setSortBy(field); setSortDir("desc"); }
    setPage(0);
  };

  const activeFilters = useMemo(() => {
    const f = filters;
    return [f.supplierId, f.dateFrom, f.dateTo].filter(Boolean).length
      + (f.purchaseType !== "ALL" ? 1 : 0)
      + (f.productType !== "ALL" ? 1 : 0)
      + (f.dueOnly ? 1 : 0);
  }, [filters]);

  const doApprove = async (row) => {
    setBusyId(row.id);
    try {
      await approvePurchase(row.id);
      addToast(`Invoice ${row.invoiceNo} approved — stock received.`);
      setConfirm(null); load();
    } catch (e) {
      addToast(e.message || "Could not approve the invoice.", "error");
    } finally { setBusyId(null); }
  };

  const doCancel = async (row) => {
    setBusyId(row.id);
    try {
      await cancelPurchase(row.id);
      addToast(`Invoice ${row.invoiceNo} cancelled.`);
      setConfirm(null);
      if (rows.length === 1 && page > 0) setPage((p) => p - 1); else load();
    } catch (e) {
      addToast(e.message || "Could not cancel the invoice.", "error");
      setConfirm(null);
    } finally { setBusyId(null); }
  };

  const kpis = useMemo(() => {
    const value = rows.reduce((s, r) => s + Number(r.amountToBePaid ?? 0), 0);
    const due = rows.reduce((s, r) => s + Math.max(Number(r.dueAmount ?? 0), 0), 0);
    const pending = rows.filter((r) => r.status === "P").length;
    return [
      { label: "Invoices", value: total, tone: "teal", icon: P.doc },
      { label: "Value (page)", value: money(value), tone: "sage", icon: P.wallet, wide: true },
      { label: "Outstanding (page)", value: money(due), tone: "clay", icon: P.alert, wide: true },
      { label: "Pending (page)", value: pending, tone: "amber", icon: P.clock },
    ];
  }, [rows, total]);

  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, total);

  return (
    <>
      <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))" }}>
        {kpis.map((k) => (
          <div className="kpi-card" key={k.label} style={{ padding: "14px 16px" }}>
            <div className="kpi-top" style={{ marginBottom: 8 }}>
              <div className="kpi-icon" style={{ width: 30, height: 30, background: `var(--${k.tone}-soft)`, color: `var(--${k.tone})` }}>
                <Ic path={k.icon} size={15} />
              </div>
            </div>
            <div className="kpi-value" style={{ fontSize: k.wide ? 17 : 21 }}>{k.value}</div>
            <div className="kpi-label" style={{ marginTop: 4 }}>{k.label}</div>
          </div>
        ))}
      </div>

      {/* Status chips */}
      <div style={{ display: "flex", gap: 7, flexWrap: "wrap", marginBottom: 16, alignItems: "center" }}>
        {STATUS_CHIPS.map((c) => {
          const active = filters.status === c.key;
          return (
            <button key={c.key} onClick={() => setFilter("status", c.key)}
              style={{
                padding: "6px 14px", borderRadius: 999, fontSize: 12.5, fontWeight: 600,
                cursor: "pointer", border: active ? "none" : "1.5px solid var(--line)",
                background: active ? c.tone : "var(--card)", color: active ? "#fff" : "var(--ink)",
                transition: "all .12s",
              }}>{c.label}</button>
          );
        })}
        <div style={{ marginLeft: "auto", display: "flex", gap: 7 }}>
          <button className={`btn btn-sm ${activeFilters ? "btn-primary" : "btn-ghost"}`}
            onClick={() => setShowFilters((s) => !s)}>
            Filters{activeFilters ? ` (${activeFilters})` : ""}
          </button>
          {perm.canCreate && (
            <button className="btn btn-primary btn-sm" onClick={onNew}>
              <Ic path={P.plus} size={13} /> New Purchase
            </button>
          )}
        </div>
      </div>

      {/* Filter bar */}
      <div style={{
        display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end", marginBottom: 16,
        padding: "12px 16px", background: "var(--card)", borderRadius: "var(--radius)",
        border: "1px solid var(--line)",
      }}>
        <div className="search-box" style={{ flex: 2, minWidth: 230, maxWidth: 360 }}>
          <Ic path={P.search} size={14} />
          <input placeholder="Search invoice, challan, batch or remarks…" value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)} aria-label="Search purchases"
            style={{ border: "none", background: "transparent", outline: "none", fontSize: 13, flex: 1, color: "var(--ink)" }} />
          {searchInput && (
            <button onClick={() => setSearchInput("")} aria-label="Clear search"
              style={{ background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)" }}>✕</button>
          )}
        </div>

        {showFilters && (
          <>
            <FilterField label="Supplier" active={!!filters.supplierId}>
              <SearchSelect
                value={filters.supplierId}
                onChange={(v) => setFilter("supplierId", v)}
                options={[{ id: "", name: "All suppliers" },
                          ...(lookups?.suppliers ?? []).map((s) => ({ id: s.id, name: s.name }))]}
                placeholder="All suppliers" ariaLabel="Filter by supplier"
                emptyLabel="No supplier matches that." />
            </FilterField>
            <FilterField label="Basis" active={filters.purchaseType !== "ALL"}>
              <SearchSelect
                value={filters.purchaseType}
                onChange={(v) => setFilter("purchaseType", v)}
                options={[{ id: "ALL", name: "All" }, { id: "MNP", name: "MNP" }, { id: "MRP", name: "MRP" }]}
                ariaLabel="Filter by costing basis" />
            </FilterField>
            <FilterField label="Product Type" active={filters.productType !== "ALL"}>
              <SearchSelect
                value={filters.productType}
                onChange={(v) => setFilter("productType", v)}
                options={[{ id: "ALL", name: "All" }, { id: "PHARMA", name: "Pharma" },
                          { id: "NON-PHARMA", name: "Non-Pharma" }]}
                ariaLabel="Filter by product type" />
            </FilterField>
            <FilterField label="From" active={!!filters.dateFrom}>
              <input type="date" value={filters.dateFrom} max={filters.dateTo || undefined}
                     onChange={(e) => setFilter("dateFrom", e.target.value)} style={inputStyle}
                     title="Earliest purchase date to list" />
            </FilterField>
            <FilterField label="To" active={!!filters.dateTo}>
              <input type="date" value={filters.dateTo} min={filters.dateFrom || undefined}
                     onChange={(e) => setFilter("dateTo", e.target.value)} style={inputStyle}
                     title="Latest purchase date to list" />
            </FilterField>
            <label style={{ display: "flex", alignItems: "center", gap: 7, fontSize: 12.5, paddingBottom: 7, cursor: "pointer" }}>
              <input type="checkbox" checked={filters.dueOnly} style={{ accentColor: "var(--amber)" }}
                onChange={(e) => setFilter("dueOnly", e.target.checked)} />
              Outstanding only
            </label>
          </>
        )}

        {(activeFilters > 0 || filters.search) && (
          <button className="btn btn-ghost btn-sm" onClick={clearFilters} style={{ marginLeft: "auto" }}
                  title="Clear every filter and show all invoices">
            <Ic path={P.filter} size={13} /> Clear all
          </button>
        )}
      </div>

      {/* Grid */}
      <Card pad={false}>
        <div style={{ padding: "14px 18px 0" }}>
          <div className="card-head">
            <div>
              <div className="card-eyebrow">Purchase</div>
              <div className="card-title">Invoice History</div>
            </div>
            <Pill tone="teal">{total} invoice{total === 1 ? "" : "s"}</Pill>
          </div>
        </div>

        {loading ? <Spinner label="Loading purchases…" />
          : error ? (
            <div className="empty-state">
              <div style={{ color: "var(--clay)", marginBottom: 10 }}>{error}</div>
              <button className="btn btn-ghost btn-sm" onClick={load} title="Load the list again">
                <Ic path={P.refresh} size={13} /> Try again
              </button>
            </div>
          ) : !rows.length ? (
            <div className="empty-state">
              {filters.search || activeFilters || filters.status !== "ALL" ? (
                <>
                  <div>No invoices match your filters.</div>
                  <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={clearFilters}
                          title="Clear every filter and show all invoices">
                    <Ic path={P.filter} size={13} /> Clear filters
                  </button>
                </>
              ) : (
                <>
                  <div style={{ marginBottom: 4 }}>No purchase invoices yet.</div>
                  {perm.canCreate && (
                    <button className="btn btn-primary btn-sm" style={{ marginTop: 10 }} onClick={onNew}>
                      <Ic path={P.plus} size={13} /> Create the first one
                    </button>
                  )}
                </>
              )}
            </div>
          ) : (
            <div className="table-wrap" style={{ padding: "0 18px" }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <SortTh label="Invoice" field="invoiceNo" {...{ sortBy, sortDir, toggleSort }} />
                    <SortTh label="Date" field="transactionDate" {...{ sortBy, sortDir, toggleSort }} />
                    <th>Supplier</th>
                    <th>Basis</th>
                    <th style={{ textAlign: "right" }}>Lines</th>
                    <SortTh label="Total" field="totalAmount" numeric {...{ sortBy, sortDir, toggleSort }} />
                    <SortTh label="Payable" field="amountToBePaid" numeric {...{ sortBy, sortDir, toggleSort }} />
                    <th style={{ textAlign: "right" }}>Due</th>
                    <SortTh label="Status" field="status" {...{ sortBy, sortDir, toggleSort }} />
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <div className="mono" style={{ fontWeight: 700, fontSize: 12 }}>{r.invoiceNo}</div>
                        <div className="mono" style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
                          batch {r.batchNo}{r.challanNo ? ` · ch ${r.challanNo}` : ""}
                        </div>
                      </td>
                      <td className="mono" style={{ fontSize: 12 }}>{r.transactionDate}</td>
                      <td>
                        <div style={{ fontWeight: 600 }}>{r.supplierName ?? "—"}</div>
                        {r.productType && (
                          <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>{r.productType}</div>
                        )}
                      </td>
                      <td><Pill tone={r.purchaseType === "MRP" ? "amber" : "teal"}>{r.purchaseType}</Pill></td>
                      <td className="mono" style={{ textAlign: "right" }}>{r.lineCount}</td>
                      <td className="mono" style={{ textAlign: "right" }}>{money(r.totalAmount)}</td>
                      <td className="mono" style={{ textAlign: "right", fontWeight: 700 }}>{money(r.amountToBePaid)}</td>
                      <td className="mono" style={{ textAlign: "right", color: Number(r.dueAmount) > 0 ? "var(--clay)" : "var(--ink-soft)" }}>
                        {money(r.dueAmount)}
                        {Math.abs(Number(r.paidAmount ?? 0) - (Number(r.givenAmount ?? 0) - Number(r.advanceAmount ?? 0))) > 0.005 && (
                          <div style={{ fontSize: 10, color: "var(--sage)" }}
                            title="Includes payments made on the Supplier Payment screen">
                            paid {money(r.paidAmount)}
                          </div>
                        )}
                        {Number(r.advanceAmount) > 0 && (
                          <div style={{ fontSize: 10, fontWeight: 700, color: "var(--sage)" }}
                            title={`${money(r.advanceAmount)} was paid over this bill and is recoverable from the supplier`}>
                            +{money(r.advanceAmount)} adv
                          </div>
                        )}
                      </td>
                      <td><Pill tone={statusTone(r.status)}>{r.statusLabel}</Pill></td>
                      <td>
                        <div style={{ display: "flex", gap: 4, justifyContent: "flex-end" }}>
                          <button className="icon-btn" title="View" onClick={() => onView(r.id)}>
                            <Ic path={P.eye} size={14} />
                          </button>
                          {perm.canEdit && r.editable && (
                            <button className="icon-btn" title="Edit" onClick={() => onEdit(r.id)}>
                              <Ic path={P.edit} size={14} />
                            </button>
                          )}
                          {perm.canApprove && r.editable && (
                            <button className="icon-btn" title="Approve & receive stock" disabled={busyId === r.id}
                              onClick={() => setConfirm({ kind: "approve", row: r })}>
                              <Ic path={P.check} size={14} />
                            </button>
                          )}
                          {perm.canDelete && r.editable && (
                            <button className="icon-btn" title="Cancel invoice" disabled={busyId === r.id}
                              onClick={() => setConfirm({ kind: "cancel", row: r })}>
                              <Ic path={P.trash} size={14} />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

        {!loading && !error && total > 0 && (
          <div style={{
            display: "flex", justifyContent: "space-between", alignItems: "center",
            gap: 12, flexWrap: "wrap", padding: "14px 18px", borderTop: "1px solid var(--line)",
          }}>
            <div style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
              Showing <b className="mono">{from}–{to}</b> of <b className="mono">{total}</b>
            </div>
            <div className="flex-gap">
              <select value={size} onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                aria-label="Rows per page" style={{ ...inputStyle, width: "auto" }}>
                {PAGE_SIZES.map((s) => <option key={s} value={s}>{s} / page</option>)}
              </select>
              <button className="btn btn-ghost btn-sm" disabled={page === 0} aria-label="Previous page"
                onClick={() => setPage((p) => Math.max(0, p - 1))}><Ic path={P.chevL} size={13} /></button>
              <span className="mono" style={{ fontSize: 12, minWidth: 92, textAlign: "center" }}>
                Page {page + 1} of {Math.max(totalPages, 1)}
              </span>
              <button className="btn btn-ghost btn-sm" disabled={page + 1 >= totalPages} aria-label="Next page"
                onClick={() => setPage((p) => p + 1)}><Ic path={P.chevR} size={13} /></button>
            </div>
          </div>
        )}
      </Card>

      {confirm?.kind === "approve" && (
        <Modal title="Approve invoice?" onClose={() => setConfirm(null)} width={460}
          footer={<>
            <button className="btn btn-ghost" onClick={() => setConfirm(null)}
                    title="Leave this invoice as it is">Cancel</button>
            <button className="btn btn-teal" disabled={busyId === confirm.row.id}
              onClick={() => doApprove(confirm.row)}>
              {busyId === confirm.row.id ? "Approving…" : "Approve & receive stock"}
            </button>
          </>}>
          <p style={{ fontSize: 13.5, lineHeight: 1.6 }}>
            <b className="mono">{confirm.row.invoiceNo}</b> will be approved and its{" "}
            {confirm.row.lineCount} line{confirm.row.lineCount === 1 ? "" : "s"} received into stock.
          </p>
          <p style={{ fontSize: 12.5, color: "var(--ink-soft)", marginTop: 10 }}>
            Once approved the invoice is frozen — corrections need a purchase return.
          </p>
        </Modal>
      )}

      {confirm?.kind === "cancel" && (
        <Modal title="Cancel invoice?" onClose={() => setConfirm(null)} width={460}
          footer={<>
            <button className="btn btn-ghost" onClick={() => setConfirm(null)}
                    title="Leave this invoice as it is">Keep it</button>
            <button className="btn btn-danger" disabled={busyId === confirm.row.id}
              onClick={() => doCancel(confirm.row)}>
              {busyId === confirm.row.id ? "Cancelling…" : "Cancel invoice"}
            </button>
          </>}>
          <p style={{ fontSize: 13.5, lineHeight: 1.6 }}>
            <b className="mono">{confirm.row.invoiceNo}</b> and its lines will be removed.
            No stock has been received for it, so nothing is returned.
          </p>
        </Modal>
      )}
    </>
  );
}

/**
 * One labelled control in the list filter bar.
 *
 * The active flag only tints — it never changes what is filtered. It exists
 * because a bar of six identical-looking controls gives no clue which of them
 * is the reason the list is short, and the answer to "where did my invoices go"
 * should be visible rather than found by resetting each one in turn.
 */
const FilterField = ({ label, children, active = false }) => (
  <div className={"pu-filter " + (active ? "on" : "")}>
    <div className="pu-filter-label">{label}</div>
    {children}
  </div>
);

const SortTh = ({ label, field, numeric, sortBy, sortDir, toggleSort }) => (
  <th onClick={() => toggleSort(field)}
    style={{ cursor: "pointer", textAlign: numeric ? "right" : "left" }}
    aria-sort={sortBy === field ? (sortDir === "asc" ? "ascending" : "descending") : undefined}>
    {label}
    {sortBy === field && <span style={{ marginLeft: 4, opacity: .75 }}>{sortDir === "asc" ? "▲" : "▼"}</span>}
  </th>
);

/* ═══════════════════════════════════════════════════════════════════════════
   DETAIL
   ═══════════════════════════════════════════════════════════════════════════ */

function PurchaseDetail({ purchaseId, perm, addToast, onEdit, onChanged }) {
  const confirm = useConfirm();
  const [inv, setInv] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    getPurchase(purchaseId)
      .then((p) => { setInv(p); setLoading(false); })
      .catch((e) => { setError(e.message || "Could not load the invoice."); setLoading(false); });
  }, [purchaseId]);

  useEffect(() => { load(); }, [load]);

  const doApprove = async () => {
    if (!(await confirm({
      title: "Approve and receive this stock?",
      body: "Approving moves every line into inventory at the cost shown, and freezes the invoice.",
      detail: "Once approved it can no longer be edited — a correction needs a purchase return.",
      confirmLabel: "Approve & receive stock", tone: "teal",
    }))) return;
    setBusy(true);
    try {
      await approvePurchase(purchaseId);
      addToast(`Invoice ${inv.invoiceNo} approved — stock received.`);
      load();
    } catch (e) {
      addToast(e.message || "Could not approve the invoice.", "error");
    } finally { setBusy(false); }
  };

  if (loading) return <Card><Spinner label="Loading invoice…" /></Card>;
  if (error || !inv) return (
    <Card>
      <div className="empty-state">
        <div style={{ color: "var(--clay)" }}>{error || "Invoice not found."}</div>
        <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={onChanged}>Back to history</button>
      </div>
    </Card>
  );

  const Row = ({ label, children, mono }) => (
    <div style={{ display: "flex", gap: 12, padding: "8px 0", borderBottom: "1px solid var(--line)" }}>
      <div style={{ width: 130, flexShrink: 0, fontSize: 12, color: "var(--ink-soft)" }}>{label}</div>
      <div className={mono ? "mono" : ""} style={{ fontSize: 13.5, fontWeight: 600 }}>{children ?? "—"}</div>
    </div>
  );

  return (
    <>
      <div style={{ display: "flex", gap: 10, marginBottom: 16, flexWrap: "wrap", alignItems: "center" }}>
        <Pill tone="teal">{inv.invoiceNo}</Pill>
        <Pill tone={statusTone(inv.status)}>{inv.statusLabel}</Pill>
        <Pill tone={inv.purchaseType === "MRP" ? "amber" : "teal"}>{inv.purchaseType}</Pill>
        {inv.productType && <Pill tone="grey">{inv.productType}</Pill>}
        <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
          {perm.canEdit && inv.editable && (
            <button className="btn btn-ghost btn-sm" onClick={onEdit}>
              <Ic path={P.edit} size={13} /> Edit
            </button>
          )}
          {perm.canApprove && inv.editable && (
            <button className="btn btn-teal btn-sm" onClick={doApprove} disabled={busy}>
              <Ic path={P.check} size={13} /> {busy ? "Approving…" : "Approve"}
            </button>
          )}
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr minmax(250px, 300px)", gap: 16, alignItems: "start" }}
        className="purchase-split">
        <div style={{ display: "grid", gap: 16 }}>
          <Card pad={false} className="pu-sec pu-sec-lines">
            <div style={{ padding: "16px 20px 0" }}>
              <Section icon={P.calc} title={`Lines (${inv.lineCount})`} />
            </div>
            <div className="table-wrap" style={{ padding: "0 20px" }}>
              <table className="data-table" style={{ fontSize: 12.5 }}>
                <thead>
                  <tr>
                    <th>Product</th>
                    <th style={{ textAlign: "right" }}>Qty</th>
                    <th>Unit</th>
                    <th style={{ textAlign: "right" }}>{inv.purchaseType === "MRP" ? "MRP" : "TP / IGTA"}</th>
                    <th style={{ textAlign: "right" }}>Disc</th>
                    <th>Buy Price</th>
                    <th>Batch / Expiry</th>
                    <th style={{ textAlign: "right" }}>Total</th>
                  </tr>
                </thead>
                <tbody>
                  {(inv.lines ?? []).map((l) => (
                    <tr key={l.id}>
                      <td>
                        <div style={{ fontWeight: 700 }}>{l.productName}</div>
                        <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
                          <span className="mono">{l.productCode}</span>
                          {l.genericName ? ` · ${l.genericName}` : ""}
                        </div>
                        {/* Manufacturer on its own line: it is what a buyer
                            checks an invoice against, and it was already in the
                            response, just never rendered. */}
                        {l.companyName && (
                          <div style={{ fontSize: 10.5, color: "var(--teal)" }}>{l.companyName}</div>
                        )}
                      </td>
                      <td className="mono" style={{ textAlign: "right" }}>{Number(l.quantity)}</td>
                      <td>
                        {l.salesType}
                        <div className="mono" style={{ fontSize: 10, color: "var(--ink-soft)" }}>
                          {Number(l.totalPcs)} pcs
                        </div>
                      </td>
                      <td className="mono" style={{ textAlign: "right" }}>
                        {inv.purchaseType === "MRP" ? money(l.mrpPrice) : <>
                          {money(l.mnpPrice)}
                          <div style={{ fontSize: 10, color: "var(--ink-soft)" }}>+ {money(l.igta)}</div>
                        </>}
                      </td>
                      <td className="mono" style={{ textAlign: "right" }}>
                        {Number(l.discountPct ?? 0)}%
                        {Number(l.flatDiscount) > 0 && (
                          <div style={{ fontSize: 10, color: "var(--ink-soft)" }}>+{money(l.flatDiscount)}</div>
                        )}
                      </td>
                      {/* The landed cost this delivery works out to — trade
                          price, IGTA and discount together. Distinct from the
                          TP column, which is the rate before any of them. */}
                      <td><BuyPrice cost={l.cost} /></td>
                      <td className="mono" style={{ fontSize: 11.5, whiteSpace: "nowrap" }}>
                        {l.batchNo ?? "—"}
                        <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
                          exp {l.expiryDate ?? "—"}
                        </div>
                      </td>
                      <td className="mono" style={{ textAlign: "right", fontWeight: 700 }}>{money(l.totalPrice)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ height: 16 }} />
          </Card>

          <Card title="Invoice Details" eyebrow="Header">
            <div style={{ display: "grid", gap: "0 24px", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))" }}>
              <div>
                <Row label="Invoice No" mono>{inv.invoiceNo}</Row>
                <Row label="Batch Ref" mono>{inv.batchNo}</Row>
                <Row label="Challan No" mono>{inv.challanNo}</Row>
                <Row label="Date" mono>{inv.transactionDate}</Row>
                <Row label="Supplier">{inv.supplier?.name}</Row>
                <Row label="Branch">{inv.branch?.name}</Row>
              </div>
              <div>
                <Row label="Costing Basis">{inv.purchaseType}</Row>
                <Row label="Product Type">{inv.productType}</Row>
                <Row label="Source">{inv.fromSource}</Row>
                <Row label="Created By">{inv.createdBy}</Row>
                <Row label="Created" mono>
                  {inv.createdDate ? new Date(inv.createdDate).toLocaleString() : "—"}
                </Row>
              </div>
            </div>
            {inv.remarks && (
              <div style={{ marginTop: 12, padding: "10px 12px", background: "var(--paper)", borderRadius: "var(--radius-sm)", fontSize: 13 }}>
                {inv.remarks}
              </div>
            )}
          </Card>
        </div>

        <Card>
          <Section icon={P.wallet} title="Totals" />
          <TotalRow label="Line total" value={money(inv.totalAmount)} />
          {/* The same three rows the entry screen shows, in the same order.
              This view had the total but not the line half, so an invoice read
              back after saving did not account for the difference between the
              two figures — TP% and line discounts. Derived rather than sent:
              total discount less the invoice cut is exactly what the lines
              gave away. */}
          {Number(inv.totalDiscount ?? 0) - Number(inv.invoiceDisAmt ?? 0) > 0 && (
            <TotalRow label="Line discount"
              value={money(Number(inv.totalDiscount) - Number(inv.invoiceDisAmt))} tone="clay" />
          )}
          <TotalRow label={`Invoice discount (${Number(inv.invoiceDisPct ?? 0)}%)`} value={`− ${money(inv.invoiceDisAmt)}`} tone="clay" />
          <TotalRow label="Total discount" value={money(inv.totalDiscount)} tone="clay" strong />
          <TotalRow label="Tax" value={money(inv.totalTax)} />
          <TotalRow label="Service charge" value={money(inv.serviceCharge)} />
          {Number(inv.cashbackAmount) > 0 && (
            <TotalRow label="Supplier cashback" value={`− ${money(inv.cashbackAmount)}`} tone="sage" />
          )}

          <div style={{
            margin: "12px 0", padding: "12px 14px", borderRadius: "var(--radius-sm)",
            background: "var(--teal-soft)", border: "1px solid var(--teal)",
          }}>
            <div style={{ fontSize: 11, color: "var(--teal)", fontWeight: 700, letterSpacing: ".06em" }}>
              AMOUNT TO BE PAID
            </div>
            <div className="kpi-value" style={{ fontSize: 24, color: "var(--teal)", marginTop: 4 }}>
              {money(inv.amountToBePaid)}
            </div>
          </div>

          <TotalRow label="Payment Method" value={inv.paymentType ?? "—"} />
          <TotalRow label="Given at invoice" value={money(inv.givenAmount)} tone="sage" />
          {Number(inv.returnCredit) > 0 && (
            <TotalRow label="Return credit" value={money(inv.returnCredit)} tone="amber" />
          )}
          {Math.abs(Number(inv.paidAmount ?? 0) - (Number(inv.givenAmount ?? 0) - Number(inv.advanceAmount ?? 0))) > 0.005 && (
            // Paid later, on the Supplier Payment screen. Without this the
            // invoice reports only the cash handed over at the counter and
            // reads as unpaid long after it was settled.
            <TotalRow label="Paid in total" value={money(inv.paidAmount)} tone="sage" />
          )}
          {Number(inv.advanceAmount) > 0 && (
            <TotalRow label="Of which advance" value={money(inv.advanceAmount)} tone="sage" />
          )}
          <TotalRow label={Number(inv.dueAmount) >= 0 ? "Due" : "Change"}
            value={money(Math.abs(Number(inv.dueAmount ?? 0)))}
            tone={Number(inv.dueAmount) > 0 ? "clay" : "sage"} strong />
          {Number(inv.advanceAmount) > 0 && (
            // Without this the invoice reads "given 665.33, due 0.00" and the
            // 66.53 is nowhere on the page you are looking at — which is where
            // anyone would look for it first.
            <div style={{ marginTop: 12, padding: "10px 12px", borderRadius: 9,
                          background: "var(--sage-soft)", border: "1px solid var(--sage)",
                          fontSize: 12.5, lineHeight: 1.55 }}>
              <b>{money(inv.advanceAmount)} is recoverable from {inv.supplier?.name ?? "the supplier"}.</b>
              {" "}It was handed over with this invoice but settles no part of it. Recover it under{" "}
              <b>Purchase → Supplier Payment → Advances</b>, either as money back or as credit on a
              later bill.
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
