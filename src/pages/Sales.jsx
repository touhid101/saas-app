/**
 * Sales — batch-wise FEFO invoicing, priced by customer type.
 *
 * The rule this page exists to make obvious:
 *
 *   Wholesaler   BOX, STRIP, PCS   regular prices
 *   Special      BOX, STRIP, PCS   special prices
 *   Retailer     PCS only          the wholesaler's piece rate
 *
 * The unit restriction and the price list are separate. A retailer is limited
 * to pieces but is not on a different list — hence the piece price matching the
 * wholesaler's, which looks like a bug until you know the rule.
 *
 * Nothing is priced in the browser. /api/sales/pricing returns the price, the
 * allowed units and the FEFO batch split; the server prices the invoice again
 * on save from the same rule. The totals below are a preview.
 *
 * Replaces a version built on mockData.js with a client-side allocateFEFO().
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill, Field, Tabs, Modal, useToast, Table, QtyInput } from "../components/UI.jsx";
import { usePermission } from "../lib/usePermission.js";
import PrintDocument from "../components/PrintDocument.jsx";
import UnlockModal from "../components/UnlockModal.jsx";
import { getCustomers } from "../services/masterDataService.js";
import CustomerPicker, { custId } from "../components/CustomerPicker.jsx";
import inventoryService from "../services/inventoryService.js";
import salesService, { TIER_TONES, STAGES } from "../services/salesService.js";

const money = (n) =>
  "৳ " + Number(n || 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const qtyFmt = (n) => Number(n || 0).toLocaleString("en-BD", { maximumFractionDigits: 2 });
const todayIso = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

const PAYMENT_TYPES = ["CASH", "CARD", "BKASH", "NAGAD", "BANK", "CREDIT"];

/**
 * The customer's pricing tier.
 *
 * /api/customers returns it as `type`; the detail endpoint and some callers use
 * `customerType`. Both are read, because getting this wrong is silent — every
 * customer renders as "Retailer" and nobody can sell a box.
 *
 * The fallback matches the server's: an unrecorded type is the most restrictive
 * tier, never the most generous.
 */
function customerTypeOf(c) {
  return c?.type ?? c?.customerType ?? "Retailer";
}

/**
 * Money and percentage fields.
 *
 * These keep the browser's own wheel behaviour suppressed — a delivery charge
 * or a discount has no natural "one more", and nudging one by scrolling past it
 * is an accident, not an adjustment. Quantities are the opposite case and use
 * {@link QtyInput}, which steps deliberately.
 */
function NumberInput(props) {
  const ref = React.useRef(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const stop = (e) => { if (document.activeElement === el) e.preventDefault(); };
    el.addEventListener("wheel", stop, { passive: false });
    return () => el.removeEventListener("wheel", stop);
  }, []);
  return <input {...props} ref={ref} type="number" />;
}

/** Only a Submitted invoice can still be changed. */
const EDITABLE_STAGE = "Submitted";
/**
 * Whether this invoice can still be changed.
 *
 * Stage is the usual gate. A merged order adds a second one: it is delivered
 * and collected under its merge, so editing it in place would change what that
 * merge is worth without the merge knowing. Splitting the merge is the way
 * back — the server refuses the edit either way.
 */
// A merge is composed of the orders inside it and its lines are copies of
// theirs, so neither half is edited here: the wrapper would repost stock the
// orders already issued, and an order would change what the merge is worth
// without the merge knowing. Both are corrected by splitting the merge.
/** The notes a Bangladeshi till actually holds — the same set as Quick Sales. */
const QUICK_CASH = [100, 200, 500, 1000, 2000];

/** Keeps a tapped-up total off the floating-point fringe (500 + 0.1 + 0.2). */
const round2 = (v) =>
  (Number.isFinite(v) ? Math.round((v + Number.EPSILON) * 100) / 100 : 0);

const isEditable = (s) =>
  (s?.stage || "").toLowerCase() === EDITABLE_STAGE.toLowerCase()
  && !s?.mergedIntoId
  && !s?.mergeInvoice;

export default function Sales() {
  const [tab, setTab] = useState("New Sale");
  // The invoice being edited, or null for a fresh one. Held here so switching
  // to Invoices and picking one lands back on the same form.
  const [editing, setEditing] = useState(null);
  const perm = usePermission("sales");

  // The supervisor's authorisation, held only for as long as this edit is open.
  // Leaving the screen drops it, and it expires on the server regardless.
  const [unlockToken, setUnlockToken] = useState(null);

  const startEdit = (sale, token = null) => {
    setEditing(sale);
    setUnlockToken(token);
    setTab("New Sale");
  };
  const finishEdit = () => { setEditing(null); setUnlockToken(null); setTab("Invoices"); };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Sales</h1>
          <div className="page-sub">
            Batch-wise FEFO allocation, priced by customer type.
          </div>
        </div>
      </div>

      <Tabs
        tabs={[editing ? `Editing ${editing.invoiceNo}` : "New Sale", "Invoices"]}
        active={tab === "New Sale" && editing ? `Editing ${editing.invoiceNo}` : tab}
        onChange={(t) => setTab(t.startsWith("Editing") ? "New Sale" : t)}
      />

      {tab === "New Sale" && (
        <NewSale
          key={editing?.id ?? "new"}   /* remount so the form reloads cleanly */
          perm={perm}
          editing={editing}
          unlockToken={unlockToken}
          onSaved={finishEdit}
          onCancelEdit={() => { setEditing(null); setUnlockToken(null); }}
        />
      )}
      {tab === "Invoices" && <InvoiceList onEdit={startEdit} />}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   NEW SALE
   ═══════════════════════════════════════════════════════════════════════════ */

function NewSale({ perm, editing, unlockToken, onSaved, onCancelEdit }) {
  // useToast returns a tuple [fire, node] — not an object. Destructuring it as
  // one leaves `showToast` undefined, and because the catch block calls it too,
  // a successful save throws twice and silently skips the reset.
  const [showToast, toast] = useToast();

  const [customers, setCustomers] = useState([]);
  const [products, setProducts] = useState([]);
  const [loadError, setLoadError] = useState(null);

  const [customerId, setCustomerId] = useState("");
  const [header, setHeader] = useState({
    transactionDate: todayIso(),
    paymentType: "CASH",
    deliveryAddress: "",
    deliveryCharge: "",
    invoiceDiscPct: "",
    givenAmount: "",
    remarks: "",
  });

  const [lines, setLines] = useState([]);
  const [picker, setPicker] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    // Products come from the stock ledger, not the catalogue.
    //
    // A sales picker should only offer what can actually be sold — listing the
    // whole catalogue meant choosing a product, waiting for pricing, and only
    // then being told there is none. The ledger is branch-scoped and already
    // carries the quantity, so the list can show it.
    Promise.all([
      getCustomers({ size: 200 }),
      inventoryService.getStockLedger({ inStockOnly: true, size: 300 }),
    ])
      .then(([c, p]) => {
        if (!alive) return;
        setCustomers(c?.items ?? []);
        setProducts(p?.items ?? []);
      })
      .catch((e) => alive && setLoadError(e?.message || "Could not load customers and products."));
    return () => { alive = false; };
  }, []);

  const customer = useMemo(
    () => customers.find((c) => String(c.id ?? c.pidCustomer) === String(customerId)) || null,
    [customers, customerId]
  );

  // Load the invoice being edited into the form.
  //
  // Its detail rows are per batch — a 5-box line that spanned two lots is two
  // rows — so they are folded back into one editable line per product+unit.
  // Showing the split here would invite the user to edit an allocation the
  // server owns.
  useEffect(() => {
    if (!editing) return;
    let alive = true;

    salesService.getSale(editing.id)
      .then((sale) => {
        if (!alive) return;
        setCustomerId(String(sale.customerId ?? ""));
        setHeader({
          transactionDate: sale.transactionDate ?? todayIso(),
          paymentType: sale.paymentType ?? "CASH",
          deliveryAddress: sale.deliveryAddress ?? "",
          deliveryCharge: sale.deliveryCharge ?? "",
          invoiceDiscPct: sale.invoiceDiscPct ?? "",
          givenAmount: sale.givenAmount ?? "",
          remarks: sale.remarks ?? "",
        });

        // The sale response does not carry product names, so they come from the
        // catalogue already loaded above.
        const nameOf = (id) => {
          const p = products.find((x) => (x.id ?? x.productId ?? x.pidProduct) === id);
          return p?.productName || `#${id}`;
        };

        const merged = new Map();
        for (const d of sale.lines || []) {
          const unit = d.salesUnit || d.salesType || "PCS";
          const key = `${d.productId}|${unit}`;
          const batch = { batchNo: d.batchNo, expiryDate: d.expiryDate };
          const prev = merged.get(key);
          if (prev) {
            prev.quantity += Number(d.quantity || 0);
            prev.batches.push(batch);
          } else {
            merged.set(key, {
              productId: d.productId,
              productName: nameOf(d.productId),
              salesUnit: unit,
              quantity: Number(d.quantity || 0),
              unitPrice: Number(d.salesPrice || 0),
              discountPct: Number(d.discountPct || 0),
              batches: [batch],
            });
          }
        }
        setLines([...merged.values()]);
      })
      .catch((e) => setLoadError(e?.message || "Could not load the invoice."));

    return () => { alive = false; };
  }, [editing, products]);

  // Changing the customer changes which units are sellable and at what price,
  // so lines priced for the previous customer are no longer valid.
  //
  // Only a real switch clears them. Comparing against the previous value rather
  // than counting renders matters: loading an invoice for edit also sets the
  // customer, and a naive "skip the first run" guard is consumed by the mount
  // pass — so the edit's own lines were wiped the moment they arrived.
  const prevCustomer = React.useRef(customerId);
  useEffect(() => {
    const prev = prevCustomer.current;
    prevCustomer.current = customerId;
    if (!prev || prev === customerId) return;
    setLines((ls) => (ls.length ? [] : ls));
  }, [customerId]);

  const totals = useMemo(
    () => salesService.invoiceTotals(lines, header.invoiceDiscPct, header.deliveryCharge, header.givenAmount),
    [lines, header.invoiceDiscPct, header.deliveryCharge, header.givenAmount]
  );

  // The picker stays open — a counter sale is several products in one go, and
  // it closes itself when the user is done. Closing here made every item a
  // fresh round of reopen, re-filter, re-find.
  const addLine = (line) => setLines((prev) => [...prev, line]);

  const removeLine = (i) => setLines((prev) => prev.filter((_, x) => x !== i));

  const save = async () => {
    if (!customerId) return showToast("Choose a customer first.");
    if (!lines.length) return showToast("Add at least one product.");

    setSaving(true);
    try {
      const payload = { customerId: Number(customerId), ...header, lines };
      if (editing) {
        const res = await salesService.updateSale(editing.id, payload, unlockToken);
        showToast(`Invoice ${res?.invoiceNo || editing.invoiceNo} updated.`);
      } else {
        const res = await salesService.createSale(payload);
        showToast(`Invoice ${res?.invoiceNo || ""} created.`);
      }
      setLines([]);
      setHeader((h) => ({ ...h, givenAmount: "", remarks: "" }));
      onSaved?.();
    } catch (e) {
      showToast(e?.message || `Could not ${editing ? "update" : "create"} the invoice.`);
    } finally {
      setSaving(false);
    }
  };

  if (loadError) return <div className="empty-state" role="alert">{loadError}</div>;

  return (
    <div className="sales-split">
      <div className="sales-main">
        <Card
          title="Customer"
          eyebrow="Type decides the sellable units and the price list"
        >
          <div className="sales-customer-row">
            <Field label="Customer">
              <CustomerPicker
                id="sales-customer"
                customers={customers}
                selected={customer}
                placeholder="Search by name or mobile…"
                onPick={(c) => {
                  if (!c) { setCustomerId(""); return; }
                  // A customer found by searching may not be among the page held
                  // locally, so it joins the list — otherwise the name would
                  // resolve to nothing the moment it was chosen, and with it the
                  // tier that decides the price list.
                  setCustomers((cs) =>
                    cs.some((x) => String(custId(x)) === String(custId(c))) ? cs : [...cs, c]);
                  setCustomerId(String(custId(c)));
                }}
              />
            </Field>

            {customer && <TierBadge customer={customer} />}
          </div>
        </Card>

        <Card
          title="Items"
          eyebrow="Allocated earliest-expiry-first, batch by batch"
          right={
            <button
              className="btn btn-primary"
              disabled={!customerId || !perm?.canCreate}
              onClick={() => setPicker(true)}
              title={!customerId ? "Choose a customer first" : undefined}
            >
              Add product
            </button>
          }
        >
          {lines.length === 0 ? (
            <div className="empty-state">
              {customerId
                ? "No items yet. Add a product to begin."
                : "Choose a customer first — the price depends on their type."}
            </div>
          ) : (
            <Table head={["Product", "Unit", "Qty", "Price", "Disc %", "Batches", "Total", ""]}>
              {lines.map((l, i) => (
                <tr key={i}>
                  <td>{l.productName}</td>
                  <td><Pill tone="teal">{l.salesUnit}</Pill></td>
                  <td className="mono">{qtyFmt(l.quantity)}</td>
                  <td className="mono">{money(l.unitPrice)}</td>
                  <td className="mono">{l.discountPct || 0}</td>
                  <td className="mono batch-cell">
                    {(l.batches || []).map((b) => (
                      <span key={b.batchNo} className="batch-chip" title={`expires ${b.expiryDate}`}>
                        {/* A loaded invoice knows its batches but not the piece
                            split, so the count is shown only when allocated. */}
                        {b.batchNo}
                        {b.allocatedPcs != null && ` · ${qtyFmt(b.allocatedPcs)} pcs`}
                      </span>
                    ))}
                  </td>
                  <td className="mono">{money(salesService.lineTotal(l))}</td>
                  <td>
                    <button className="btn btn-ghost btn-sm" onClick={() => removeLine(i)}
                            aria-label={`Remove ${l.productName}`}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      </div>

      {/* Totals rail */}
      <div className="sales-rail">
        <Card
          title={editing ? `Invoice ` : "Invoice"}
          eyebrow={editing ? "Editing — the previous allocation is returned to stock on save"
                           : "Preview — the server recomputes on save"}
        >
          <Field label="Date">
            <input className="input" type="date" value={header.transactionDate}
                   onChange={(e) => setHeader({ ...header, transactionDate: e.target.value })} />
          </Field>

          <Field label="Payment">
            <div className="pay-tiles">
              {PAYMENT_TYPES.map((p) => (
                <button
                  key={p}
                  type="button"
                  className={`pay-tile${header.paymentType === p ? " pay-tile-on" : ""}`}
                  onClick={() => setHeader({ ...header, paymentType: p })}
                  aria-pressed={header.paymentType === p}
                >
                  {p}
                </button>
              ))}
            </div>
          </Field>

          <Field label="Invoice discount %">
            <NumberInput className="input" min="0" max="100" step="0.01"
                   value={header.invoiceDiscPct}
                   onChange={(e) => setHeader({ ...header, invoiceDiscPct: e.target.value })} />
          </Field>

          <Field label="Delivery charge">
            <NumberInput className="input" min="0" step="0.01"
                   value={header.deliveryCharge}
                   onChange={(e) => setHeader({ ...header, deliveryCharge: e.target.value })} />
          </Field>

          <Field label="Given amount">
            <NumberInput className="input pos-paid" min="0" step="0.01"
                   value={header.givenAmount}
                   onChange={(e) => setHeader({ ...header, givenAmount: e.target.value })} />
            {/* Tender buttons, identical to the till's. They add up, so 500+100
                is two taps rather than mental arithmetic at a counter. */}
            <div className="pos-cash-row">
              {QUICK_CASH.map((n) => (
                <button key={n} type="button" className="pos-cash"
                  onClick={() => setHeader((h) => ({
                    ...h, givenAmount: String(round2((Number(h.givenAmount) || 0) + n)) }))}>
                  +{n}
                </button>
              ))}
              <button type="button" className="pos-cash pos-cash-exact"
                title="Set the given amount to exactly what is payable"
                onClick={() => setHeader((h) => ({ ...h, givenAmount: String(totals.payable) }))}>
                Exact
              </button>
            </div>
          </Field>

          <div className="totals-block">
            <TotalRow label="Subtotal" value={money(totals.subtotal)} />
            <TotalRow label="Invoice discount" value={`− ${money(totals.invoiceDisc)}`} />
            <TotalRow label="Delivery" value={money(header.deliveryCharge || 0)} />
            <TotalRow label="Payable" value={money(totals.payable)} strong />
            <TotalRow
              label={totals.due > 0 ? "Due" : "Change"}
              value={money(Math.abs(totals.due))}
              tone={totals.due > 0 ? "clay" : "sage"}
            />
          </div>

          {editing && (
            <button className="btn btn-ghost btn-block" style={{ marginTop: 8 }}
                    onClick={onCancelEdit} disabled={saving}>
              Cancel edit
            </button>
          )}

          {!perm?.canCreate && (
            <p className="muted" style={{ fontSize: 12, marginTop: 8 }}>
              You do not have permission to create sales.
            </p>
          )}

          {/* Last in the card, and pinned to its bottom edge: on a long invoice
              the thing you are about to commit should never have to be scrolled
              back to. Anything placed after this would sit underneath it. */}
          <button
            className="sales-charge"
            disabled={saving || !lines.length || !customerId || !perm?.canCreate}
            onClick={save}
            title={!perm?.canCreate ? "You do not have permission to create sales" : undefined}
          >
            {saving
              ? (editing ? "Saving…" : "Creating…")
              : <>{editing ? "Update" : "Create invoice"} {money(totals.payable)}</>}
          </button>
        </Card>
      </div>

      {picker && (
        <ProductPicker
          customerId={Number(customerId)}
          customerName={customer?.customerName ?? customer?.name}
          products={products}
          onAdd={addLine}
          onClose={() => setPicker(false)}
        />
      )}

      {toast}
    </div>
  );
}

function TierBadge({ customer }) {
  const type = customerTypeOf(customer);
  const tier = type.toUpperCase().startsWith("WHOLESALE") ? "WHOLESALER"
             : type.toUpperCase().startsWith("SPECIAL") ? "SPECIAL" : "RETAILER";
  const units = salesService.TIER_UNITS[tier];

  return (
    <div className="tier-badge">
      <Pill tone={TIER_TONES[tier]}>{type}</Pill>
      <span className="muted">
        Can buy in {units.join(", ")}
        {tier === "SPECIAL" && " at special prices"}
        {tier === "RETAILER" && " at the wholesaler piece rate"}
      </span>
    </div>
  );
}

function TotalRow({ label, value, strong, tone }) {
  return (
    <div className={`total-row${strong ? " total-row-strong" : ""}`}>
      <span>{label}</span>
      <span className={`mono${tone ? ` tone-${tone}` : ""}`}>{value}</span>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PRODUCT PICKER — where the tier rules become visible
   ═══════════════════════════════════════════════════════════════════════════ */

function ProductPicker({ customerId, customerName, products, onAdd, onClose }) {
  const [search, setSearch] = useState("");
  const [companyId, setCompanyId] = useState("");
  const [productId, setProductId] = useState(null);
  const [unit, setUnit] = useState("PCS");
  const [quantity, setQuantity] = useState("1");
  const [discountPct, setDiscountPct] = useState("");

  /** What this picker session has put on the invoice, newest last. */
  const [added, setAdded] = useState([]);

  const [pricing, setPricing] = useState(null);
  // `firstLoad` blocks the pane; `refreshing` is a quiet indicator. Splitting
  // them is what keeps the quantity input mounted — see below.
  const [firstLoad, setFirstLoad] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);

  /** Manufacturers present in the catalogue, for the filter. */
  const companies = useMemo(() => {
    const seen = new Map();
    for (const p of products) {
      if (p.companyName && !seen.has(p.companyName)) seen.set(p.companyName, p.companyId ?? p.companyName);
    }
    return [...seen.keys()].sort();
  }, [products]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return products
      .filter((p) => !companyId || p.companyName === companyId)
      .filter((p) => !q
        || (p.productName || "").toLowerCase().includes(q)
        || (p.sku || "").toLowerCase().includes(q)
        || (p.companyName || "").toLowerCase().includes(q))
      .slice(0, 60);
  }, [products, search, companyId]);

  // Which product the currently held `pricing` belongs to, so a quantity change
  // is known to be a refresh rather than a first load.
  const pricingRef = React.useRef(null);

  // Debounced: typing a three-digit quantity should ask the server once, not
  // three times, and each reply racing the next is how a stale allocation ends
  // up on screen.
  const [debouncedQty, setDebouncedQty] = useState(quantity);
  useEffect(() => {
    const t = setTimeout(() => setDebouncedQty(quantity), 250);
    return () => clearTimeout(t);
  }, [quantity]);

  // Ask the server what this customer may buy of this product. Re-asked when
  // the quantity changes so the batch split shown is the one that will happen.
  //
  // Two flags, not one: a re-fetch must NOT unmount the form. It used to set a
  // single `loading`, and the render swapped the whole pane for "Loading…" —
  // so every keystroke destroyed and recreated the quantity input, and the
  // caret jumped out of the field mid-number.
  useEffect(() => {
    if (!productId) return;
    let alive = true;

    const initial = pricingRef.current !== productId;
    if (initial) setFirstLoad(true); else setRefreshing(true);
    setError(null);

    salesService
      .getPricing({ customerId, productId, qty: debouncedQty, unit })
      .then((p) => {
        if (!alive) return;
        pricingRef.current = productId;
        setPricing(p);
        // Snap to a unit this customer can actually buy — a retailer landing on
        // the default PCS is fine, but a stale BOX selection is not.
        const allowed = (p.units || []).filter((u) => u.allowed).map((u) => u.unit);
        if (allowed.length && !allowed.includes(unit)) setUnit(allowed[0]);
      })
      .catch((e) => alive && setError(e?.message || "Could not load pricing."))
      .finally(() => { if (alive) { setFirstLoad(false); setRefreshing(false); } });

    return () => { alive = false; };
  }, [customerId, productId, debouncedQty, unit]);

  const option = pricing ? salesService.unitOption(pricing, unit) : null;
  const canAdd = Boolean(option?.allowed && option?.price != null
                      && Number(quantity) > 0
                      && pricing?.allocation?.fullyAllocated);

  /**
   * Adds the current selection and stays open for the next one.
   *
   * A counter sale is several products in one go, so closing after each would
   * mean reopening, re-filtering and re-finding the customer's tier for every
   * item. The line goes straight onto the invoice — `added` is only a record of
   * what this session put there, so the user can see their progress without the
   * invoice table behind the modal.
   */
  const add = () => {
    if (!canAdd) return;
    onAdd({
      productId,
      productName: pricing.productName,
      salesUnit: unit,
      quantity: Number(quantity),
      unitPrice: Number(option.price),
      discountPct: discountPct === "" ? 0 : Number(discountPct),
      batches: (pricing.allocation?.lines || []).map((b) => ({
        batchNo: b.batchNo, expiryDate: b.expiryDate, allocatedPcs: b.allocatedPcs,
      })),
    });

    setAdded((prev) => [...prev, {
      key: `${productId}-${unit}-${prev.length}`,
      productName: pricing.productName,
      unit,
      quantity: Number(quantity),
      lineTotal: Number(option.price) * Number(quantity)
                 * (1 - (discountPct === "" ? 0 : Number(discountPct)) / 100),
    }]);

    // Clear the selection, keep the search and company filter — the next item
    // is usually from the same shelf.
    setProductId(null);
    setPricing(null);
    pricingRef.current = null;
    setQuantity("1");
    setDiscountPct("");
  };

  return (
    <Modal
      title={`Add products — ${customerName || "customer"}`}
      onClose={onClose}
      width={780}
      footer={
        <>
          {added.length > 0 && (
            <span className="picker-added-count">
              {added.length} item{added.length === 1 ? "" : "s"} added
            </span>
          )}
          {/* "Done" rather than "Cancel" once something has been added — the
              lines are already on the invoice and this button does not undo
              them, so calling it Cancel would be a lie. */}
          <button className="btn btn-ghost" onClick={onClose}>
            {added.length ? "Done" : "Cancel"}
          </button>
          <button className="btn btn-primary" disabled={!canAdd} onClick={add}>
            Add to invoice
          </button>
        </>
      }
    >
      <div className="picker-grid">
        <div className="picker-list">
          <input
            className="input"
            type="search"
            placeholder="Search product, SKU or company…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search products"
          />

          <select
            className="input"
            value={companyId}
            onChange={(e) => setCompanyId(e.target.value)}
            aria-label="Filter by company"
          >
            <option value="">All companies</option>
            {companies.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>

          <ul className="picker-items">
            {filtered.map((p) => {
              const id = p.id ?? p.productId ?? p.pidProduct;
              return (
                <li key={id}>
                  <button
                    type="button"
                    className={`picker-item${productId === id ? " picker-item-on" : ""}`}
                    onClick={() => setProductId(id)}
                  >
                    <span className="picker-item-main">
                      <span>{p.productName}</span>
                      {/* The manufacturer distinguishes two products that share
                          a generic name — which in a pharmacy catalogue is
                          most of them. */}
                      {p.companyName && (
                        <span className="picker-item-company">{p.companyName}</span>
                      )}
                    </span>
                    <span className="picker-item-right">
                      <span className="mono muted">{p.sku || ""}</span>
                      {/* Stock on the row, so the choice is informed before the
                          click rather than after the pricing round-trip. */}
                      <span className="picker-item-stock mono">
                        {p.stockDisplay || `${qtyFmt(p.stockPcs)} pcs`}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
            {filtered.length === 0 && <li className="empty-state">No products match.</li>}
          </ul>
        </div>

        <div className="picker-detail">
          {/* What this session has already put on the invoice. Without it the
              modal gives no sign anything happened, and the user cannot see the
              invoice table underneath. */}
          {added.length > 0 && (
            <div className="picker-added">
              <div className="picker-added-head">Added to this invoice</div>
              <ul className="picker-added-list">
                {added.map((a) => (
                  <li key={a.key}>
                    <span>{a.productName}</span>
                    <span className="mono muted">
                      {qtyFmt(a.quantity)} {a.unit.toLowerCase()} · {money(a.lineTotal)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {!productId && (
            <div className="empty-state">
              {added.length
                ? "Pick the next product, or press Done."
                : "Pick a product to see its price and stock."}
            </div>
          )}
          {productId && firstLoad && <div className="empty-state">Loading…</div>}
          {productId && error && <div className="empty-state" role="alert">{error}</div>}

          {/* Rendered whenever pricing is held — deliberately NOT gated on the
              refresh flag. Gating it there unmounts the quantity input on every
              keystroke, and the caret jumps out of the field mid-number. */}
          {productId && !firstLoad && !error && pricing && (
            <>
              <div className="picker-head">
                <div>
                  <div className="picker-title">{pricing.productName}</div>
                  <div className="muted">
                    In stock: <strong>{pricing.availableDisplay}</strong>
                    {" · "}{qtyFmt(pricing.availablePcs)} pcs
                  </div>
                </div>
                <Pill tone={TIER_TONES[pricing.tier]}>{pricing.tierLabel}</Pill>
              </div>

              {/* Every unit is shown, including the ones this customer cannot
                  have — a disabled tile with a reason explains itself; a
                  missing tile just looks broken. */}
              <div className="unit-tiles">
                {(pricing.units || []).map((u) => (
                  <button
                    key={u.unit}
                    type="button"
                    className={`unit-tile${unit === u.unit ? " unit-tile-on" : ""}${u.allowed ? "" : " unit-tile-off"}`}
                    disabled={!u.allowed}
                    onClick={() => setUnit(u.unit)}
                    title={u.reason || `${u.pcsPerUnit} pcs per ${u.unit.toLowerCase()}`}
                    aria-pressed={unit === u.unit}
                  >
                    <div className="unit-tile-unit">{u.unit}</div>
                    <div className="unit-tile-price mono">
                      {u.price == null ? "—" : money(u.price)}
                    </div>
                    <div className="unit-tile-sub">
                      {u.allowed
                        ? <>max {u.maxQty ?? "—"} · {u.pcsPerUnit ?? "?"} pcs</>
                        : "unavailable"}
                    </div>
                  </button>
                ))}
              </div>

              {option && !option.allowed && (
                <div className="banner banner-clay" role="note">{option.reason}</div>
              )}

              {option?.allowed && option.discountPct != null && (
                <p className="muted" style={{ fontSize: 12.5, margin: "2px 0 10px" }}>
                  {money(option.mrp)} MRP per {unit.toLowerCase()} less {option.discountPct}%
                  {pricing.tier === "RETAILER" && " — retailers pay the wholesaler piece rate"}
                </p>
              )}

              <div className="picker-qty">
                <Field label={`Quantity (${unit.toLowerCase()})`}>
                  {/* Capped at what the branch can actually supply in this
                      unit, so the + button stops at the real limit rather than
                      running on into a shortfall banner. */}
                  <QtyInput
                    value={quantity}
                    onChange={setQuantity}
                    min={1}
                    max={option?.maxQty ?? undefined}
                    step={1}
                    ariaLabel={`Quantity in ${unit.toLowerCase()}`}
                  />
                </Field>
                <Field label="Line discount %">
                  <NumberInput className="input" min="0" max="100" step="0.01"
                         value={discountPct} onChange={(e) => setDiscountPct(e.target.value)} />
                </Field>
              </div>

              {/* A quiet marker instead of swapping the pane out — the pane
                  staying put is what keeps the caret in the quantity field. */}
              <div className="picker-refresh" aria-live="polite">
                {refreshing ? "Recalculating allocation…" : " "}
              </div>

              <BatchPreview allocation={pricing.allocation} unit={unit} pcsPerUnit={option?.pcsPerUnit} />
            </>
          )}
        </div>
      </div>
    </Modal>
  );
}

/** The lots FEFO would consume, in the order it would consume them. */
/**
 * The lots FEFO would consume.
 *
 * Cost and quantity are both shown in the unit being sold, not in pieces —
 * a box price beside a piece count invites the two to be multiplied together,
 * which is precisely the mistake the legacy FEFO procedure made.
 */
function BatchPreview({ allocation, unit, pcsPerUnit }) {
  if (!allocation) return null;

  if (!allocation.fullyAllocated) {
    return (
      <div className="banner banner-clay" role="alert">
        Not enough stock — {qtyFmt(allocation.allocatedPcs)} of{" "}
        {qtyFmt(allocation.requestedPcs)} pcs available.
      </div>
    );
  }

  const per = Number(pcsPerUnit) > 0 ? Number(pcsPerUnit) : 1;
  const u = (unit || "PCS").toLowerCase();
  const inUnits = (pcs) => (per === 1 ? qtyFmt(pcs) : qtyFmt(Number(pcs) / per));
  const costInUnits = (costPerPcs) =>
    costPerPcs == null ? "—" : money(Number(costPerPcs) * per);

  return (
    <div className="batch-preview">
      <div className="batch-preview-head">
        Allocation — earliest expiry first
      </div>
      <Table head={["Batch", "Expires", per === 1 ? "Pieces" : `Qty (${u})`, `Cost/${u}`]}>
        {(allocation.lines || []).map((b) => (
          <tr key={b.stockId}>
            <td className="mono">{b.batchNo}</td>
            <td className="mono">{b.expiryDate || "—"}</td>
            <td className="mono">
              {inUnits(b.allocatedPcs)}
              {per > 1 && (
                <span className="muted" style={{ fontSize: 10.5 }}> · {qtyFmt(b.allocatedPcs)} pcs</span>
              )}
            </td>
            <td className="mono">
              {costInUnits(b.costPerPcs)}
              {per > 1 && b.costPerPcs != null && (
                <span className="muted" style={{ fontSize: 10.5 }}> · {money(b.costPerPcs)}/pc</span>
              )}
            </td>
          </tr>
        ))}
      </Table>
      {(allocation.lines || []).length > 1 && (
        <p className="muted" style={{ fontSize: 12, marginTop: 6 }}>
          This quantity spans {allocation.lines.length} batches — the invoice will carry
          one line per batch, each with its own batch number.
        </p>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   INVOICES
   ═══════════════════════════════════════════════════════════════════════════ */
/**
 * The invoice list, and the invoice behind it.
 *
 * Filters are pushed to the server, never applied to the page that comes back:
 * narrowing rows already fetched would miss matches on page two while the count
 * still included them. Every control here maps to a query parameter.
 */
function InvoiceList({ onEdit }) {
  const [viewing, setViewing] = useState(null);   // sale id, or null for the list
  const [printing, setPrinting] = useState(null); // sale id being printed on A4
  const perm = usePermission("sales");
  // Null until asked. The screen is not the guard — the edit checks again —
  // but a button that promises an edit and then refuses one is its own failure.
  const [mayEdit, setMayEdit] = useState(null);
  const [unlocking, setUnlocking] = useState(null);

  const [f, setF] = useState({
    q: "", stage: "ALL", paymentStatus: "ALL", customerId: "", from: "", to: "",
  });
  const [page, setPage] = useState(0);
  const [customers, setCustomers] = useState([]);
  const [state, setState] = useState({ loading: true, error: null, data: null });

  useEffect(() => {
    getCustomers({ size: 200 })
      .then((r) => setCustomers(r?.items ?? r?.content ?? []))
      .catch(() => setCustomers([]));   // the picker degrades to "All customers"
  }, []);

  useEffect(() => {
    // Asked once per screen rather than per row: the answer is about the user,
    // not the invoice. Assume an unlock is needed if the question fails, so a
    // dropped request cannot hand out an edit.
    salesService.getEditAuthority(0)
      .then((a) => setMayEdit(a.canEdit))
      .catch(() => setMayEdit(false));
  }, []);

  /** Edit directly, or ask a supervisor first. */
  const startEdit = (sale) => {
    if (mayEdit) onEdit?.(sale, null);
    else setUnlocking(sale);
  };

  const load = useCallback(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    salesService
      .listSales({ ...f, page, size: 25 })
      .then((d) => alive && setState({ loading: false, error: null, data: d }))
      .catch((e) => alive && setState({
        loading: false, error: e?.message || "Could not load invoices.", data: null,
      }));
    return () => { alive = false; };
  }, [f, page]);

  useEffect(() => {
    // Debounced only for typing. Choosing a date or a stage should feel
    // immediate; waiting 300ms after a click reads as lag.
    const wait = f.q ? 300 : 0;
    const id = setTimeout(load, wait);
    return () => clearTimeout(id);
  }, [load, f.q]);

  const set = (patch) => { setF((s) => ({ ...s, ...patch })); setPage(0); };

  /** Inclusive range ending today, as the API wants it. */
  const lastDays = (n) => {
    const end = new Date();
    const start = new Date();
    start.setDate(start.getDate() - (n - 1));
    const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    set({ from: iso(start), to: iso(end) });
  };

  const clearAll = () => {
    setF({ q: "", stage: "ALL", paymentStatus: "ALL", customerId: "", from: "", to: "" });
    setPage(0);
  };

  const active = f.q || f.stage !== "ALL" || f.paymentStatus !== "ALL"
                 || f.customerId || f.from || f.to;

  if (viewing) {
    return (
      <>
        <SaleDetail id={viewing} onBack={() => setViewing(null)} onEdit={onEdit} />
        {printing && <PrintDocument saleId={printing} format="a4" onClose={() => setPrinting(null)} />}
      </>
    );
  }

  const rows = state.data?.items ?? [];
  const totalPages = state.data?.totalPages ?? 0;

  return (
    <Card
      title="Invoices"
      eyebrow="Sales raised from this branch"
      right={
        <div className="flex-gap" style={{ flexWrap: "wrap", justifyContent: "flex-end" }}>
          <input className="input" style={{ width: 210 }} value={f.q}
            placeholder="Invoice, customer, delivery man…"
            aria-label="Search invoices"
            onChange={(e) => set({ q: e.target.value })} />

          <select className="input" value={f.customerId} aria-label="Filter by customer"
            onChange={(e) => set({ customerId: e.target.value })}>
            <option value="">All customers</option>
            {customers.map((c) => (
              <option key={c.id ?? c.pidCustomer} value={c.id ?? c.pidCustomer}>
                {c.name ?? c.customerName}
              </option>
            ))}
          </select>

          <select className="input" value={f.stage} aria-label="Filter by stage"
            onChange={(e) => set({ stage: e.target.value })}>
            <option value="ALL">All stages</option>
            {STAGES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>

          <select className="input" value={f.paymentStatus} aria-label="Filter by payment status"
            onChange={(e) => set({ paymentStatus: e.target.value })}>
            <option value="ALL">Any payment</option>
            {["Paid", "Partial", "Unpaid"].map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
      }
    >
      {/* ── dates ── */}
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap",
                    paddingBottom: 12, marginBottom: 12, borderBottom: "1px solid var(--line)" }}>
        <span style={{ fontSize: 11, textTransform: "uppercase", letterSpacing: ".06em",
                       color: "var(--ink-soft)" }}>
          Date
        </span>
        <input type="date" className="input" value={f.from} max={f.to || undefined}
          aria-label="From date" onChange={(e) => set({ from: e.target.value })} />
        <span style={{ color: "var(--ink-soft)", fontSize: 12 }}>to</span>
        <input type="date" className="input" value={f.to} min={f.from || undefined}
          aria-label="To date" onChange={(e) => set({ to: e.target.value })} />

        {[["Today", 1], ["7 days", 7], ["30 days", 30]].map(([label, n]) => (
          <button key={label} className="btn btn-ghost btn-sm" onClick={() => lastDays(n)}>
            {label}
          </button>
        ))}
        {active && (
          <button className="btn btn-ghost btn-sm" onClick={clearAll}
            style={{ marginLeft: "auto", color: "var(--clay)" }}>
            Clear filters
          </button>
        )}
      </div>

      {state.loading && <div className="empty-state">Loading…</div>}
      {state.error && <div className="empty-state" role="alert">{state.error}</div>}
      {state.data && rows.length === 0 && (
        <div className="empty-state">
          {active
            ? "No invoice matches these filters."
            : "No invoices yet."}
        </div>
      )}

      {rows.length > 0 && (
        <>
          <Table head={["Invoice", "Date", "Customer", "Lines", "Subtotal", "Payable", "Due", "Stage", ""]}>
            {rows.map((s) => (
              <tr key={s.id ?? s.pidTranMst}>
                <td className="mono">{s.invoiceNo}</td>
                <td className="mono">{s.transactionDate}</td>
                <td>{s.customerName || "—"}</td>
                <td className="mono">{s.lines?.length ?? s.lineCount ?? "—"}</td>
                {/* The API names these `subtotal` and `amountTobePaid` — not
                    `totalAmount` / `amountToBePaid`, which read as 0.00. */}
                <td className="mono">{money(s.subtotal)}</td>
                <td className="mono">{money(s.amountTobePaid)}</td>
                <td className="mono" style={{ color: Number(s.due) > 0 ? "var(--clay)" : "var(--ink-soft)" }}>
                  {money(s.due)}
                </td>
                <td>
                  <Pill tone={isEditable(s) ? "amber" : "teal"}>{s.stage || "—"}</Pill>
                  {s.mergedIntoId && (
                    <div style={{ fontSize: 10, fontWeight: 700, color: "var(--teal)", marginTop: 3 }}
                      title="Delivered and collected under a merged invoice">
                      merged · view only
                    </div>
                  )}
                  {s.mergeInvoice && (
                    <div style={{ fontSize: 10, fontWeight: 700, color: "var(--teal)", marginTop: 3 }}
                      title="A grouping invoice over several orders">
                      merged invoice
                    </div>
                  )}
                </td>
                <td>
                  <div style={{ display: "flex", gap: 4, justifyContent: "flex-end" }}>
                    <button className="btn btn-ghost btn-sm"
                      onClick={() => setViewing(s.id ?? s.pidTranMst)}
                      title={`Open ${s.invoiceNo}`}>
                      View
                    </button>
                    {perm.canPrint !== false && (
                      <button className="btn btn-ghost btn-sm"
                        onClick={() => setPrinting(s.id ?? s.pidTranMst)}
                        title={`Print ${s.invoiceNo} on A4`}>
                        Print
                      </button>
                    )}
                    {/* Only a Submitted invoice can still be changed. Past that
                        the goods are on their way and the correction is a
                        return, so the button explains itself rather than
                        disappearing. */}
                    <button
                      className="btn btn-ghost btn-sm"
                      disabled={!isEditable(s)}
                      onClick={() => startEdit(s)}
                      title={s.mergedIntoId
                        ? `${s.invoiceNo} has been merged — split the merge to change it`
                        : s.mergeInvoice
                        ? `${s.invoiceNo} is a merged invoice — change the orders inside it`
                        : !isEditable(s)
                        ? `${s.invoiceNo} is ${s.stage} — raise a sales return to correct it`
                        : mayEdit === false
                          ? `${s.invoiceNo} is saved — a supervisor must authorise a change`
                          : `Edit ${s.invoiceNo}`}
                    >
                      {/* The padlock is the whole point: the cashier should
                          know before clicking that this needs a supervisor. */}
                      {isEditable(s) && mayEdit === false ? "🔒 Edit" : "Edit"}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </Table>

          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center",
                        gap: 12, flexWrap: "wrap", paddingTop: 12 }}>
            <span style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
              Showing <b className="mono">{rows.length}</b> of{" "}
              <b className="mono">{state.data.total}</b>
            </span>
            {totalPages > 1 && (
              <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                <button className="btn btn-ghost btn-sm" disabled={page === 0}
                  onClick={() => setPage((p) => p - 1)}>← Prev</button>
                <span style={{ fontSize: 13 }}>Page {page + 1} / {totalPages}</span>
                <button className="btn btn-ghost btn-sm" disabled={page + 1 >= totalPages}
                  onClick={() => setPage((p) => p + 1)}>Next →</button>
              </div>
            )}
          </div>
        </>
      )}
      {unlocking && (
        <UnlockModal
          sale={unlocking}
          onClose={() => setUnlocking(null)}
          onUnlocked={(token) => { const sale = unlocking; setUnlocking(null); onEdit?.(sale, token); }} />
      )}
      {printing && (
        <PrintDocument saleId={printing} format="a4" onClose={() => setPrinting(null)} />
      )}
    </Card>
  );
}

/**
 * One invoice, in full.
 *
 * Fetched fresh rather than handed the list row: the list carries a summary,
 * and a detail built from it would silently omit the lines — the reason anyone
 * opens an invoice in the first place.
 */
function SaleDetail({ id, onBack, onEdit }) {
  const [state, setState] = useState({ loading: true, error: null, sale: null });

  useEffect(() => {
    let alive = true;
    setState({ loading: true, error: null, sale: null });
    salesService.getSale(id)
      .then((s) => alive && setState({ loading: false, error: null, sale: s }))
      .catch((e) => alive && setState({
        loading: false, error: e?.message || "Could not load this invoice.", sale: null,
      }));
    return () => { alive = false; };
  }, [id]);

  const { loading, error, sale } = state;

  if (loading) return <Card title="Invoice"><div className="empty-state">Loading…</div></Card>;
  if (error) {
    return (
      <Card title="Invoice"
        right={<button className="btn btn-ghost btn-sm" onClick={onBack}>← Back to invoices</button>}>
        <div className="empty-state" role="alert">{error}</div>
      </Card>
    );
  }

  const lines = sale.lines ?? [];
  const returned = lines.reduce((n, l) => n + (Number(l.returnQty) || 0), 0);

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <Card
        eyebrow="Invoice"
        title={sale.invoiceNo}
        right={
          <div className="flex-gap">
            <button className="btn btn-ghost btn-sm" onClick={onBack}>← Back to invoices</button>
            {isEditable(sale) && (
              <button className="btn btn-primary btn-sm" onClick={() => onEdit?.(sale)}>
                Edit invoice
              </button>
            )}
          </div>
        }>
        <div style={{ display: "grid", gap: "0 24px",
                      gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))" }}>
          <div>
            <DetailRow label="Date">{sale.transactionDate}</DetailRow>
            <DetailRow label="Customer">{sale.customerName || "Walk-in"}</DetailRow>
            <DetailRow label="Stage">
              <Pill tone={isEditable(sale) ? "amber" : "teal"}>{sale.stage || "—"}</Pill>
            </DetailRow>
            <DetailRow label="Payment">{sale.paymentType || "—"}</DetailRow>
          </div>
          <div>
            <DetailRow label="Payment status">
              <Pill tone={Number(sale.due) > 0 ? "clay" : "sage"}>{sale.paymentStatus || "—"}</Pill>
            </DetailRow>
            <DetailRow label="Delivery man">{sale.deliveryManName || "Not assigned"}</DetailRow>
            <DetailRow label="Address">{sale.deliveryAddress || "—"}</DetailRow>
            <DetailRow label="Note">{sale.remarks || "—"}</DetailRow>
          </div>
        </div>
      </Card>

      <Card eyebrow={`${lines.length} line${lines.length === 1 ? "" : "s"}`} title="What was sold"
        right={returned > 0
          ? <Pill tone="amber">{qtyFmt(returned)} returned</Pill>
          : null}>
        <Table head={["Product", "Batch", "Expiry", "Qty", "Unit", "Price", "Returned", "Total"]}>
          {lines.map((l) => (
            <tr key={l.lineId}>
              <td style={{ fontWeight: 600 }}>{l.productName || `#${l.productId}`}</td>
              <td className="mono">{l.batchNo || "—"}</td>
              <td className="mono">{l.expiryDate || "—"}</td>
              <td className="mono">{qtyFmt(l.quantity)}</td>
              <td>{l.salesUnit || "PCS"}</td>
              <td className="mono">{money(l.salesPrice)}</td>
              {/* Shown per line, not just as an invoice total: a partly
                  returned invoice still reads as fully sold otherwise. */}
              <td className="mono" style={{ color: Number(l.returnQty) > 0 ? "var(--amber-deep)" : "var(--ink-soft)" }}>
                {Number(l.returnQty) > 0 ? qtyFmt(l.returnQty) : "—"}
              </td>
              <td className="mono" style={{ fontWeight: 700 }}>{money(l.totalPrice)}</td>
            </tr>
          ))}
        </Table>

        <div style={{ maxWidth: 320, marginLeft: "auto", marginTop: 14 }}>
          <TotalRow label="Subtotal" value={money(sale.subtotal)} />
          {Number(sale.invoiceDiscAmt) > 0 && (
            <TotalRow label={`Invoice discount${sale.invoiceDiscPct ? ` (${Number(sale.invoiceDiscPct)}%)` : ""}`}
              value={`− ${money(sale.invoiceDiscAmt)}`} tone="clay" />
          )}
          {Number(sale.deliveryCharge) > 0 && (
            <TotalRow label="Delivery charge" value={money(sale.deliveryCharge)} />
          )}
          <TotalRow label="Payable" value={money(sale.amountTobePaid)} strong />
          <TotalRow label="Received" value={money(sale.givenAmount)} tone="sage" />
          <TotalRow label={Number(sale.due) >= 0 ? "Due" : "Change"}
            value={money(Math.abs(Number(sale.due) || 0))}
            tone={Number(sale.due) > 0 ? "clay" : "sage"} strong />
        </div>
      </Card>
    </div>
  );
}

function DetailRow({ label, children }) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", gap: 12,
                  padding: "7px 0", borderBottom: "1px solid var(--line)", fontSize: 13 }}>
      <span style={{ color: "var(--ink-soft)" }}>{label}</span>
      <span style={{ textAlign: "right" }}>{children}</span>
    </div>
  );
}
