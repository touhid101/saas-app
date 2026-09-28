/**
 * Quick Sales — POS terminal.
 *
 * Three panes: catalogue on the left, the bill in the middle, payment on the
 * right. Everything reachable from the keyboard, because a counter operator
 * should not have to find the mouse between customers.
 *
 * ── What this replaces ────────────────────────────────────────────────────
 *
 * The previous version ran on mockData.js and called `allocateFEFO(...)`, which
 * was never imported and did not exist — so adding anything to the cart threw
 * `ReferenceError` and the terminal could not take a single sale. Charging only
 * raised a toast: no invoice was written and no stock moved.
 *
 * ── How it works now ──────────────────────────────────────────────────────
 *
 * Same rules as the Sales page, same endpoints:
 *
 *   Catalogue   /api/inventory/stock?inStockOnly=true — only what can be sold,
 *               branch-scoped, carrying quantities and all six unit prices.
 *   Pricing     the customer's tier decides which of those six applies.
 *               Wholesaler and Special get BOX/STRIP/PCS; a Retailer gets
 *               pieces only, at the wholesaler's piece rate.
 *   Checkout    POST /api/sales — the server allocates batch-wise FEFO, moves
 *               the stock, and prices the invoice again from the same rule.
 *
 * Prices shown here come from the catalogue payload rather than a call per
 * product, so the grid stays instant. They are a preview: the server prices the
 * invoice independently on save, and if the two ever disagree the server is
 * right and this is the bug.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Search, ScanLine, Trash2, User, PauseCircle, PlayCircle, CheckCircle2,
  X, LogOut, CreditCard, ReceiptText, ShoppingCart, Wallet, Package, Info,
} from "lucide-react";
// Two decimals everywhere: every figure on this page is money that changes
// hands, and `money` rounds to whole taka — which showed a 1.02 tablet as 1.
import { moneyFull as money } from "../utils/format.js";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import PrintDocument from "../components/PrintDocument.jsx";
import { getCustomers, createCustomer } from "../services/masterDataService.js";
import { useApp } from "../lib/AppContext.jsx";
// The same resolver every other image in the app goes through: the session
// stores a path ("/uploads/logo/..."), which is not a URL the dev server can
// serve on its own.
import uploadUrl from "../lib/uploadUrl.js";
import inventoryService from "../services/inventoryService.js";
import salesService from "../services/salesService.js";
import UnlockModal from "../components/UnlockModal.jsx";
import ProductDetailSheet from "../components/ProductDetailSheet.jsx";
import CustomerPicker, { custId, custName, custCode, custType } from "../components/CustomerPicker.jsx";

/* Icons come from lucide-react, which the app already depends on.
   They were hand-drawn paths in this file: a barcode of five bare verticals, a
   card that was two rectangles, a receipt whose torn foot never read as one at
   16px. Drawing them here also meant they matched nothing else in the product.
   The wrapper keeps the old call shape — <Ic path={P.search} size={14} /> — so
   this is a change of artwork, not of every call site. */
const Ic = ({ path: Glyph, size = 16, color }) =>
  Glyph ? <Glyph size={size} color={color} strokeWidth={1.9} aria-hidden="true" /> : null;

const P = {
  search:  Search,
  barcode: ScanLine,
  trash:   Trash2,
  user:    User,
  pause:   PauseCircle,
  play:    PlayCircle,
  check:   CheckCircle2,
  x:       X,
  exit:    LogOut,
  card:    CreditCard,
  receipt: ReceiptText,
};

const PAYMENTS = ["CASH", "CARD", "BKASH", "NAGAD", "BANK", "CREDIT"];

/* ── Walk-in ──────────────────────────────────────────────────────────────
   An invoice must name a customer: PID_SUP_CUST is not nullable and the request
   rejects a null id. So counter sales are booked against one reusable record
   rather than a new customer per cash sale, which would fill the master file
   with single-use rows. Found by code, created on first use. */
const WALK_IN        = "WALKIN";       // the <select> value, not a customer id
const WALK_IN_CODE   = "WALKIN";
const WALK_IN_NAME   = "Walk-in Customer";
const WALK_IN_MOBILE = "01000000000";  // placeholder; the column is required

/** Quick-tender buttons — the notes a Bangladeshi till actually holds. */
const QUICK_CASH = [100, 200, 500, 1000, 2000];

/* ── Customer field names ─────────────────────────────────────────────────
   The API answers with `id` / `code` / `name` / `type`, while the request body
   it accepts uses `customerName` / `customerCode` / `customerType`. Reading the
   wrong one fails silently rather than loudly: every customer looked like an
   unnamed Retailer, and a walk-in lookup by code matched nothing — so a fresh
   walk-in customer was created for every counter sale. Read through these.

   They live with CustomerPicker now, which is the thing that reads them. */

/* ── Tier ─────────────────────────────────────────────────────────────────
   Unknown or missing type falls back to RETAILER, the most restrictive — a
   wrong guess should under-sell, never mis-price upward. */
const tierOf = (c) => {
  const t = custType(c).toUpperCase();
  if (t.startsWith("WHOLESALE")) return "WHOLESALER";
  if (t.startsWith("SPECIAL")) return "SPECIAL";
  return "RETAILER";
};

const TIER_UNITS = {
  WHOLESALER: ["BOX", "STRIP", "PCS"],
  SPECIAL:    ["BOX", "STRIP", "PCS"],
  RETAILER:   ["PCS"],
};

/**
 * The price this tier pays for one unit, from the catalogue's price block.
 *
 * Mirrors CustomerPricing on the server. A retailer buys pieces only but pays
 * the *wholesaler's* piece rate — the restriction is on the unit, not the price
 * list, which is why RETAILER reads the regular column.
 *
 * Null means this customer cannot buy this unit at all — their tier does not
 * allow it, or the product has no packing to work the unit's share of MRP out
 * from. Deliberately not zero: "not sellable this way" and "free" are
 * different, and only one of them should reach a customer.
 *
 * A product nobody has set a Sales% on is not one of those cases. The server
 * prices it at MRP, so it appears and can be sold; setting a discount later
 * changes the price rather than making the product exist.
 */
function tierPrice(prices, tier, unit) {
  if (!prices) return null;
  if (tier === "SPECIAL") {
    return unit === "BOX" ? prices.specialBoxPrice
         : unit === "STRIP" ? prices.specialStripPrice
         : prices.specialPcsPrice;
  }
  return unit === "BOX" ? prices.boxPrice
       : unit === "STRIP" ? prices.stripPrice
       : prices.pcsPrice;
}

/** Pieces in one unit, from the packing the branch's own lots carry. */
function pcsPerUnit(row, unit) {
  if (unit === "BOX") return row?.pcsPerBox || null;
  if (unit === "STRIP") return row?.pcsPerStrip || null;
  return 1;
}

const round2 = (v) => (Number.isFinite(v) ? Math.round((v + Number.EPSILON) * 100) / 100 : 0);

/* ═══════════════════════════════════════════════════════════════════════════
   PRODUCT TILE
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * One product in the catalogue.
 *
 * `units` are the ones this customer may actually buy — allowed by their tier,
 * priced, and with a packing on the lots to convert by. Tapping the tile adds
 * the largest; the chips below add a specific one.
 *
 * Those chips are not decoration. A line's identity is product + unit, and
 * tapping the tile only ever adds the default — so with a box line already on
 * the bill there was no way to add a strip or loose pieces of the same product.
 * Tapping again just incremented the box.
 */
function ProductTile({ row, units, inCart, onAdd, onInspect }) {
  const expiring = row.earliestExpiry
    && (new Date(row.earliestExpiry) - new Date()) / 86400000 <= 30;
  const primary = units[0] || null;

  return (
    <div className={`pos-tile${inCart ? " pos-tile-in" : ""}${primary ? "" : " pos-tile-off"}`}>
      {/* Details, without adding anything. Outside the body button because a
          button cannot contain another one — browsers resolve that by dropping
          one of them, and which one is not something a caller can predict. */}
      <button
        type="button"
        className="pos-tile-info"
        onClick={() => onInspect(row)}
        aria-label={`Details for ${row.productName}`}
        title="Product details"
      >
        <Info size={13} />
      </button>

      <button
        type="button"
        className="pos-tile-body"
        disabled={!primary}
        onClick={() => primary && onAdd(row, primary.unit)}
        title={primary
          ? `${row.productName} — ${row.stockDisplay} in stock`
          : `${row.productName} has no price set for this customer type`}
      >
        <div className="pos-tile-head">
          <span className="pos-tile-name">{row.productName}</span>
          {expiring && <span className="pos-tag pos-tag-amber">EXP SOON</span>}
        </div>

        <div className="pos-tile-meta">{row.companyName || row.sku || ""}</div>

        <div className="pos-tile-foot">
          <span className="pos-tile-price mono">
            {primary ? money(primary.price) : "—"}
            <span className="pos-tile-unit">/{primary ? primary.unit.toLowerCase() : "—"}</span>
          </span>
          {/* Stock in shelf terms, from the packing frozen on each lot. */}
          <span className="pos-tile-stock mono">{row.stockDisplay}</span>
        </div>
      </button>

      {units.length > 1 && (
        <div className="pos-tile-units">
          {units.map((u) => (
            <button key={u.unit} type="button" className="pos-tile-unit-btn"
              onClick={() => onAdd(row, u.unit)}
              title={`Add one ${u.unit.toLowerCase()} at ${money(u.price)}`}>
              +{u.unit}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   QUANTITY
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * The line quantity: step it, or type it.
 *
 * <p>It was a read-only number between two buttons, so selling thirty boxes
 * meant thirty clicks. The buttons stay exactly as they were — they are the
 * fast path for the common one or two — and the number itself is now a field.
 *
 * <p><b>Committed on blur or Enter, not on every keystroke.</b> Typing "12"
 * passes through "1", and a stock check that fires per character would warn
 * about a quantity nobody asked for. The draft is local until it is finished
 * with; Escape abandons it.
 *
 * <p>Nothing is validated here. The value goes to the same {@code setQty} the
 * buttons use, which floors at one and refuses more than the branch holds — so
 * a typed quantity cannot get past a rule a clicked one obeys.
 */
function QtyStep({ line, onSet }) {
  const [draft, setDraft] = useState(null);
  /* A ref, not state. Escape blurs the field, and blur commits — but a
     setDraft(null) in the same handler has not applied by the time commit
     runs, so it read the very draft Escape was abandoning and saved it.
     Pressing Escape on "7" set the quantity to 7. */
  const abandoned = useRef(false);

  const commit = () => {
    if (abandoned.current) { abandoned.current = false; setDraft(null); return; }
    if (draft === null) return;
    const wanted = Number(draft);
    setDraft(null);
    // An empty or nonsense entry means "leave it alone", not "set it to one".
    if (!Number.isFinite(wanted) || wanted < 1) return;
    if (wanted !== line.quantity) onSet(wanted);
  };

  return (
    <div className="pos-step">
      <button type="button" onClick={() => onSet(line.quantity - 1)}
        disabled={line.quantity <= 1} aria-label="Decrease">−</button>

      <input
        className="pos-step-qty mono"
        inputMode="numeric"
        value={draft ?? line.quantity}
        aria-label={`Quantity of ${line.productName}`}
        title="Type a quantity, or use − and +"
        onChange={(e) => setDraft(e.target.value.replace(/[^\d]/g, ""))}
        onFocus={(e) => e.target.select()}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") { e.preventDefault(); e.currentTarget.blur(); }
          if (e.key === "Escape") { abandoned.current = true; e.currentTarget.blur(); }
          // The till's shortcuts are single keys; they must not fire while a
          // quantity is being typed into.
          e.stopPropagation();
        }} />

      <button type="button" onClick={() => onSet(line.quantity + 1)}
        aria-label="Increase">+</button>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   POS
   ═══════════════════════════════════════════════════════════════════════════ */

export default function QuickSales({ onExit }) {
  const addToast = useToast();
  const perm = usePermission("quicksales");
  const { currentUser } = useApp();
  const branchId = currentUser?.branchId ?? currentUser?.pidBranch ?? null;
  /* Both come from the session the operator signed in with — the same source
     the sidebar reads, so the till and the rest of the app can never disagree
     about which pharmacy this is. */
  const branchName = currentUser?.branchName || "";
  const branchLogo = currentUser?.branchLogoUrl ? uploadUrl(currentUser.branchLogoUrl) : null;

  const searchRef = useRef(null);
  const barcodeRef = useRef(null);
  const paidRef = useRef(null);

  const [products, setProducts] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);

  const [q, setQ] = useState("");
  const [companyFilter, setCompanyFilter] = useState("");
  const [barcode, setBarcode] = useState("");

  const [cart, setCart] = useState([]);
  const [customerId, setCustomerId] = useState(WALK_IN);
  const [payMethod, setPayMethod] = useState("CASH");
  const [paidAmt, setPaidAmt] = useState("");
  const [discPct, setDiscPct] = useState("");
  const [held, setHeld] = useState([]);
  const [charging, setCharging] = useState(false);
  const [receipt, setReceipt] = useState(null);
  const [quickOpen, setQuickOpen] = useState(false);

  // Reopening a saved invoice from the till. `editing` holds the invoice, and
  // `unlockToken` the supervisor's authorisation when one was needed — dropped
  // the moment the edit finishes or is abandoned.
  const [recentOpen, setRecentOpen] = useState(false);

  /* Which pane a phone shows. Three panes stacked in one viewport give each a
     third of the screen and push Charge below the fold; on a narrow screen the
     till becomes one pane at a time with a tab bar, which is how every counter
     app on a handset works. Ignored entirely above the breakpoint — the CSS
     shows all three and this value stops mattering. */
  const [pane, setPane] = useState("catalogue");

  /* The product whose details are open, or null. Holds the catalogue row
     itself rather than an id: everything the sheet shows is already on it, so
     opening the panel costs no request. */
  const [inspecting, setInspecting] = useState(null);

  /* The wall clock. A till is reconciled against the hour it took the money in,
     and a cashier should not have to leave the terminal to find out what the
     machine thinks the time is. Ticks on the minute — a seconds hand on a POS
     is movement in the corner of the eye and nothing more. */
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 30000);
    return () => clearInterval(t);
  }, []);
  // Held here rather than inside the panel. The panel is a click-to-dismiss
  // overlay, so a dialog rendered within it had its own clicks bubble up and
  // close it — a wrong password dismissed the box instead of reporting itself.
  const [unlocking, setUnlocking] = useState(null);
  const [editing, setEditing] = useState(null);
  const [unlockToken, setUnlockToken] = useState(null);
  // Null until asked. Assume an unlock is needed if the question fails, so a
  // dropped request can never hand out an edit.
  const [mayEdit, setMayEdit] = useState(null);

  useEffect(() => {
    salesService.getEditAuthority(0)
      .then((a) => setMayEdit(a.canEdit))
      .catch(() => setMayEdit(false));
  }, []);

  /**
   * The bill, mirrored synchronously so handlers can read it without an updater.
   *
   * Every cart change is a decision — is there stock for it? — that may need to
   * be refused with a message. Deciding inside a `setCart` updater is wrong on
   * both counts: React double-invokes updaters under StrictMode, so the toast
   * fired twice; and when the decision is "refuse" the updater returns the same
   * array, React skips the re-render, and a toast deferred to an effect never
   * arrives at all. That is the pair of symptoms — one message shown twice, the
   * next shown never.
   *
   * So updaters stay pure: handlers read `cartRef.current`, decide, toast
   * directly, and write a finished array through `writeCart`.
   */
  const cartRef = useRef(cart);
  const writeCart = useCallback((next) => { cartRef.current = next; setCart(next); }, []);

  const customer = useMemo(
    () => (customerId === WALK_IN ? null
      : customers.find((c) => String(custId(c)) === String(customerId)) || null),
    [customers, customerId]
  );
  // A walk-in has no record to read a type from, and an over-the-counter cash
  // sale is a retail sale — so it lands on RETAILER, the same as an unknown type.
  const tier = tierOf(customer);
  const allowedUnits = TIER_UNITS[tier];

  /** The unit the grid prices in — the largest this tier may buy. */
  const gridUnit = allowedUnits[0];

  /**
   * The units this customer can actually buy this product in, largest first.
   *
   * Three things have to line up: the tier permits the unit, a price exists for
   * it, and the lots carry a packing to convert it by. Any one missing and the
   * unit is not offered rather than offered and then refused.
   */
  const unitsFor = useCallback((row) => allowedUnits
    .map((u) => ({ unit: u, price: tierPrice(row.prices, tier, u), per: pcsPerUnit(row, u) }))
    .filter((u) => u.price != null && u.per), [allowedUnits, tier]);

  /* ── load ──────────────────────────────────────────────────────────── */

  const reload = useCallback(() => {
    setLoading(true);
    return Promise.all([
      inventoryService.getStockLedger({ inStockOnly: true, size: 300 }),
      getCustomers({ size: 200 }),
    ])
      .then(([p, c]) => {
        setProducts(p?.items ?? []);
        setCustomers(c?.items ?? []);
        setLoadError(null);
      })
      .catch((e) => setLoadError(e?.message || "Could not load the terminal."))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { reload(); }, [reload]);

  // The tier changes which units are sellable and at what price, so a cart
  // priced for the previous customer is no longer valid.
  const prevCustomer = useRef(customerId);
  // Raised while recalling a held bill: those lines were priced for the customer
  // being switched *to*, so the switch must not throw them away.
  const keepCart = useRef(false);
  useEffect(() => {
    const prev = prevCustomer.current;
    prevCustomer.current = customerId;
    if (keepCart.current) { keepCart.current = false; return; }
    if (!prev || prev === customerId) return;
    if (cartRef.current.length) {
      addToast("Cart cleared — prices depend on the customer type", "warning");
    }
    writeCart([]);
  }, [customerId]); // eslint-disable-line react-hooks/exhaustive-deps

  const companies = useMemo(
    () => [...new Set(products.map((p) => p.companyName).filter(Boolean))].sort(),
    [products]
  );

  const filtered = useMemo(() => {
    const term = q.trim().toLowerCase();
    return products
      .filter((p) => !companyFilter || p.companyName === companyFilter)
      .filter((p) => !term
        || (p.productName || "").toLowerCase().includes(term)
        || (p.sku || "").toLowerCase().includes(term)
        || (p.companyName || "").toLowerCase().includes(term));
  }, [products, q, companyFilter]);

  const cartIds = useMemo(() => new Set(cart.map((l) => l.productId)), [cart]);

  /* ── cart ──────────────────────────────────────────────────────────── */

  /**
   * Pieces of this product already spoken for by other lines of the bill.
   *
   * Counting them is the point. The same product can sit on the bill twice — a
   * box line and a loose-pieces line — and checking each against full stock
   * independently lets the terminal sell the same tablets twice over.
   *
   * Always computed from the updater's own `lines`, never from the `cart`
   * closure, so two changes in one tick cannot both read the pre-change total.
   */
  const committedPcs = (lines, productId, ...exceptKeys) => lines
    .filter((l) => l.productId === productId && !exceptKeys.includes(l.key))
    .reduce((s, l) => s + l.quantity * (l.pcsPerUnit || 1), 0);

  const addToCart = useCallback((row, unit = gridUnit) => {
    const price = tierPrice(row.prices, tier, unit);
    if (price == null) {
      addToast(`${row.productName} has no ${unit.toLowerCase()} price for a ${tier.toLowerCase()}`, "warning");
      return;
    }

    const per = pcsPerUnit(row, unit);
    if (!per) {
      addToast(`${row.productName} has no ${unit.toLowerCase()} packing configured`, "warning");
      return;
    }

    const c = cartRef.current;
    const key = `${row.productId}|${unit}`;
    const existing = c.find((l) => l.key === key);
    const nextQty = (existing?.quantity ?? 0) + 1;

    if (nextQty * per + committedPcs(c, row.productId, key) > Number(row.stockPcs || 0)) {
      addToast(`Only ${row.stockDisplay} of ${row.productName} left`, "warning");
      return;
    }

    writeCart(existing
      ? c.map((l) => (l.key === key ? { ...l, quantity: nextQty } : l))
      : [...c, {
          key,
          productId: row.productId,
          productName: row.productName,
          sku: row.sku,
          unit,
          quantity: 1,
          unitPrice: Number(price),
          pcsPerUnit: per,
          discountPct: 0,
          stockPcs: Number(row.stockPcs || 0),
          stockDisplay: row.stockDisplay,
          row,
        }]);
  }, [tier, gridUnit, addToast]);

  const setQty = (key, qty) => {
    const c = cartRef.current;
    const line = c.find((l) => l.key === key);
    if (!line) return;
    const n = Math.max(1, Number(qty) || 1);
    if (n * line.pcsPerUnit + committedPcs(c, line.productId, key) > line.stockPcs) {
      addToast(`Only ${line.stockDisplay} of ${line.productName} left`, "warning");
      return;
    }
    writeCart(c.map((l) => (l.key === key ? { ...l, quantity: n } : l)));
  };

  const setUnit = (key, unit) => {
    const c = cartRef.current;
    const line = c.find((l) => l.key === key);
    if (!line) return;

    const price = tierPrice(line.row.prices, tier, unit);
    const per = pcsPerUnit(line.row, unit);
    if (price == null || !per) {
      addToast(`${line.productName} cannot be sold in ${unit.toLowerCase()} here`, "warning");
      return;
    }

    // The key carries the unit, so switching one line's unit can land on a key
    // another line already holds. Merge the two rather than emit a duplicate
    // key, which React renders as a single row while state still holds both.
    const nextKey = `${line.productId}|${unit}`;
    const twin = nextKey === key ? null : c.find((l) => l.key === nextKey);
    const qty = line.quantity + (twin?.quantity ?? 0);

    if (qty * per + committedPcs(c, line.productId, key, nextKey) > line.stockPcs) {
      addToast(`Not enough stock for ${qty} ${unit.toLowerCase()}`, "warning");
      return;
    }

    writeCart(c
      .filter((l) => l.key !== nextKey || l.key === key)
      .map((l) => (l.key === key
        ? { ...l, key: nextKey, unit, quantity: qty, unitPrice: Number(price), pcsPerUnit: per }
        : l)));
  };

  const removeLine = (key) => writeCart(cartRef.current.filter((l) => l.key !== key));
  const clearCart = () => { writeCart([]); setPaidAmt(""); setDiscPct(""); };

  /** Abandon an edit and go back to ringing up a new sale. */
  const cancelEdit = () => {
    setEditing(null);
    setUnlockToken(null);
    clearCart();
    setCustomerId(WALK_IN);
  };

  /**
   * Loads a saved invoice back into the till.
   *
   * <p>Each saved line is matched to the live catalogue rather than trusted as
   * it stands: the cart needs the current stock and the packing to police
   * quantities, and a line rebuilt without them would let the operator ring up
   * more than the shelf holds. A product that has since been deactivated is
   * reported rather than silently dropped — a line vanishing from an invoice
   * being corrected is exactly the kind of quiet loss this whole feature is
   * meant to prevent.
   */
  const reopen = async (sale, token) => {
    setRecentOpen(false);
    try {
      const full = await salesService.getSale(sale.id ?? sale.pidTranMst);
      const missing = [];
      const lines = [];

      // The till's catalogue is in-stock only, which is right for selling and
      // wrong for reopening: an invoice that took the last of something has
      // removed its own product from that list. Refusing on that basis made
      // exactly the invoices most worth correcting the ones that could not be.
      //
      // Fetched only when something is genuinely absent, so the ordinary case
      // costs nothing.
      let shelf = products;
      const absent = (full.lines ?? []).some(
        (l) => !shelf.some((r) => r.productId === l.productId));
      if (absent) {
        const everything = await inventoryService.getStockLedger({ size: 300 });
        shelf = everything?.items ?? products;
      }

      for (const l of full.lines ?? []) {
        const row = shelf.find((r) => r.productId === l.productId);
        if (!row) { missing.push(l.productName || `#${l.productId}`); continue; }
        const unit = l.salesUnit || "PCS";
        const per = pcsPerUnit(row, unit);
        lines.push({
          key: `${l.productId}|${unit}`,
          productId: l.productId,
          productName: row.productName,
          sku: row.sku,
          unit,
          quantity: Number(l.quantity) || 1,
          unitPrice: Number(l.salesPrice) || 0,
          pcsPerUnit: per || 1,
          discountPct: Number(l.discountPct) || 0,
          // The invoice is currently holding its own pieces, so they are part
          // of what it may use. Without adding them back the cart would refuse
          // the quantities already on the invoice.
          stockPcs: Number(row.stockPcs || 0) + (Number(l.quantity) || 0) * (per || 1),
          stockDisplay: row.stockDisplay,
          row,
        });
      }

      if (missing.length) {
        // No longer "out of stock" — that is handled above. This is a product
        // removed from the catalogue entirely, which the till cannot price.
        addToast(`No longer in the catalogue: ${missing.join(", ")}. Correct this on the Sales page.`, "warning");
        return;
      }

      setEditing(full);
      setUnlockToken(token);

      // These lines were priced for the invoice's own customer, so switching
      // to them must not trigger the tier guard that clears a cart priced for
      // somebody else. Same reason Recall raises it.
      const nextCustomer = full.customerId ? String(full.customerId) : WALK_IN;
      if (nextCustomer !== String(customerId)) keepCart.current = true;
      writeCart(lines);
      setCustomerId(nextCustomer);
      setPayMethod(full.paymentType || "CASH");
      setDiscPct(full.invoiceDiscPct ? String(full.invoiceDiscPct) : "");
      setPaidAmt(full.givenAmount != null ? String(full.givenAmount) : "");
      addToast(`${full.invoiceNo} reopened — change it and save again.`);
    } catch (e) {
      addToast(e?.message || "Could not reopen that invoice.", "error");
    }
  };

  /* ── totals ────────────────────────────────────────────────────────── */

  const totals = useMemo(() => {
    const subtotal = round2(cart.reduce(
      (s, l) => s + round2(l.quantity * l.unitPrice * (1 - (Number(l.discountPct) || 0) / 100)), 0));
    const invoiceDisc = round2(subtotal * ((Number(discPct) || 0) / 100));
    const payable = round2(subtotal - invoiceDisc);
    const paid = Number(paidAmt) || 0;
    return { subtotal, invoiceDisc, payable, due: round2(Math.max(0, payable - paid)),
             change: round2(Math.max(0, paid - payable)) };
  }, [cart, discPct, paidAmt]);

  /* ── barcode ───────────────────────────────────────────────────────── */

  const onBarcode = (e) => {
    if (e.key !== "Enter") return;
    const code = barcode.trim();
    if (!code) return;
    const hit = products.find(
      (p) => (p.sku || "").toLowerCase() === code.toLowerCase()
          || String(p.barcode || "") === code);
    if (hit) { addToCart(hit); setBarcode(""); }
    else { addToast(`Nothing in stock matches "${code}"`, "error"); setBarcode(""); }
  };

  /* ── hold / recall ─────────────────────────────────────────────────── */

  const hold = () => {
    if (!cart.length) return;

    // Snapshotted before the updater, not inside it. React invokes a state
    // updater when it chooses — by which point clearCart() below has already
    // set cartRef.current to []. Reading the ref in there held an empty bill,
    // so Recall restored nothing while still counting down the held list.
    //
    // The same trap the cart itself documents: a setState updater is not the
    // place to read mutable state.
    const snapshot = cartRef.current;
    const heldCustomer = customerId;

    setHeld((h) => [...h, {
      id: `H${Date.now().toString().slice(-5)}`,
      cart: snapshot,
      customerId: heldCustomer,
      at: new Date().toLocaleTimeString("en-BD"),
    }]);
    clearCart();
    addToast(`Bill held (${snapshot.length} item${snapshot.length === 1 ? "" : "s"}) — F9 to recall`);
  };

  const recall = () => {
    if (!held.length) { addToast("No held bills", "warning"); return; }
    const last = held[held.length - 1];
    // Only when the customer actually changes, or the flag would survive to
    // suppress the next genuine switch.
    // An empty hold would silently restore nothing; say so rather than
    // consuming it and leaving an empty till that looks like a failure.
    if (!last.cart?.length) {
      addToast("That held bill has no lines — discarding it.", "warning");
      setHeld((h) => h.slice(0, -1));
      return;
    }

    const nextCustomer = last.customerId ?? WALK_IN;
    if (String(nextCustomer) !== String(customerId)) keepCart.current = true;
    setCustomerId(nextCustomer);
    writeCart(last.cart);
    setHeld((h) => h.slice(0, -1));
    addToast(`Recalled ${last.cart.length} item${last.cart.length === 1 ? "" : "s"}`);
  };

  /* ── charge ────────────────────────────────────────────────────────── */

  /**
   * The customer id a walk-in sale is booked against, creating the record once.
   *
   * Looked up by code rather than name so renaming it in Master Data does not
   * start a second one. Creation needs the branch: customers are branch-scoped,
   * and one made against the wrong branch would not come back in this terminal's
   * own list.
   */
  const resolveWalkInId = async () => {
    const existing = customers.find((c) => custCode(c) === WALK_IN_CODE);
    if (existing) return custId(existing);

    if (!branchId) {
      throw new Error("Your account has no branch assigned, so a walk-in customer cannot be created.");
    }
    const created = await createCustomer({
      customerName: WALK_IN_NAME,
      customerCode: WALK_IN_CODE,
      mobileNo: WALK_IN_MOBILE,
      customerType: "Retailer",
      branchId,
      status: "Y",
    });
    const id = created?.id ?? created?.pidCustomer ?? created?.data?.id;
    if (!id) throw new Error("Could not create the walk-in customer record.");
    // Keep it in the list so the next walk-in sale finds it without a round trip.
    setCustomers((cs) => [...cs, { id, name: WALK_IN_NAME, code: WALK_IN_CODE, type: "Retailer" }]);
    return id;
  };

  const charge = async () => {
    // F12 repeats while held down, and the button stays mounted during the
    // request — without this the same bill can be posted twice.
    if (charging || !cart.length) return;
    if (!customerId) { addToast("Choose a customer first", "warning"); return; }
    // Only a new sale has to be tendered for. An invoice being corrected
    // already has whatever was paid against it recorded, and demanding the
    // cash again would block every correction to a bill left on account.
    if (!editing && payMethod !== "CREDIT" && (Number(paidAmt) || 0) < totals.payable) {
      addToast(`Take at least ${money(totals.payable)}`, "error");
      paidRef.current?.focus();
      return;
    }

    setCharging(true);
    try {
      // The server allocates FEFO, moves the stock and prices the invoice
      // again from the same rule — these lines are the request, not the record.
      const buyerId = customerId === WALK_IN ? await resolveWalkInId() : Number(customerId);
      const transactionDate = editing?.transactionDate ?? new Date().toISOString().slice(0, 10);

      const payload = {
        customerId: Number(buyerId),
        transactionDate,
        paymentType: payMethod,
        invoiceDiscPct: discPct === "" ? null : Number(discPct),
        givenAmount: paidAmt === "" ? null : Number(paidAmt),
        /* Rung up at the till: the goods leave with the customer as the bill
           prints. The server reads this to decide the stage — settled is
           Collected, owed for is Delivered. Only this screen sends it; an
           order raised on the Sales page has not been delivered by anyone. */
        counterSale: true,
        lines: cart.map((l) => ({
          productId: l.productId,
          quantity: l.quantity,
          salesUnit: l.unit,
          discountPct: l.discountPct || null,
        })),
      };

      // Saving an edit rather than a new sale sends the supervisor's
      // authorisation with it. The server checks it again regardless of what
      // this screen believed about the operator's rights.
      const sale = editing
        ? await salesService.updateSale(editing.id, payload, unlockToken)
        : await salesService.createSale(payload);

      if (editing) {
        addToast(`${sale?.invoiceNo || editing.invoiceNo} updated.`);
        setEditing(null);
        setUnlockToken(null);
        clearCart();
        setCustomerId(WALK_IN);
      } else {
        setReceipt({ sale, lines: cart, paid: Number(paidAmt) || 0, method: payMethod,
                     change: totals.change });
        clearCart();
      }
      // Stock has moved, so the catalogue's quantities are now stale.
      reload();
    } catch (e) {
      addToast(e?.message || "Could not complete the sale", "error");
    } finally {
      setCharging(false);
    }
  };

  /* ── keyboard ──────────────────────────────────────────────────────── */

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "F2") { e.preventDefault(); searchRef.current?.focus(); }
      if (e.key === "F3") { e.preventDefault(); barcodeRef.current?.focus(); }
      if (e.key === "F4") { e.preventDefault(); document.getElementById("pos-customer")?.focus(); }
      if (e.key === "F6") { e.preventDefault(); paidRef.current?.focus(); }
      if (e.key === "F8") { e.preventDefault(); hold(); }
      if (e.key === "F9") { e.preventDefault(); recall(); }
      if (e.key === "F12") { e.preventDefault(); charge(); }
      if (e.key === "Escape" && receipt) setReceipt(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  /* ── render ────────────────────────────────────────────────────────── */

  if (loadError) {
    return (
      <div className="page">
        <div className="empty-state" role="alert">
          {loadError}
          <div style={{ marginTop: 12 }}>
            <button className="pos-btn pos-btn-ghost" onClick={reload}>Retry</button>{" "}
            <button className="pos-btn pos-btn-ghost" onClick={onExit}>Exit</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="pos">
      <header className="pos-topbar">
        {/* Which pharmacy this till belongs to. A platform serving many
            branches shows the same terminal to all of them, and the operator
            of a second shop needs to see at a glance that they are not signed
            in to the first. The mark falls back to the branch's initial, which
            is what most pharmacies have — few have uploaded a logo. */}
        <span className="pos-brand">
          {branchLogo
            ? <img src={branchLogo} alt="" className="pos-brand-logo" />
            : <span className="pos-brand-mark">{(branchName || "P").trim().charAt(0).toUpperCase()}</span>}
          <span className="pos-brand-text">
            <span className="pos-topbar-title">{branchName || "Quick Sales"}</span>
            <span className="pos-topbar-sub">
              {loading ? "loading…" : `${products.length} product${products.length === 1 ? "" : "s"} in stock`}
            </span>
          </span>
        </span>

        {held.length > 0 && (
          <span className="pos-tag pos-tag-amber">{held.length} HELD</span>
        )}

        {/* The day and the time, for a till that is reconciled by the hour. */}
        <span className="pos-clock" title="Terminal time">
          <span className="pos-clock-time mono">
            {now.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}
          </span>
          <span className="pos-clock-date">
            {now.toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short" })}
          </span>
        </span>

        <button className="pos-exit" onClick={onExit} title="Back to the dashboard">
          <Ic path={P.exit} size={14} /> Exit
        </button>
      </header>

      <div className={`pos-body pos-pane-${pane}`}>
      {/* ── LEFT: catalogue ── */}
      <section className="pos-catalogue">
        <div className="pos-search-row">
          <div className="pos-input-wrap">
            <Ic path={P.search} size={14} />
            <input ref={searchRef} className="pos-input" value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Search name, SKU or company  (F2)" aria-label="Search products" />
          </div>
          <div className="pos-input-wrap">
            <Ic path={P.barcode} size={14} />
            <input ref={barcodeRef} className="pos-input mono" value={barcode}
              onChange={(e) => setBarcode(e.target.value)} onKeyDown={onBarcode}
              placeholder="Scan barcode  (F3)" aria-label="Scan barcode" />
          </div>
        </div>

        <div className="pos-chips">
          <button type="button" className={`pos-chip${!companyFilter ? " pos-chip-on" : ""}`}
            onClick={() => setCompanyFilter("")}>All</button>
          {companies.map((c) => (
            <button key={c} type="button"
              className={`pos-chip${companyFilter === c ? " pos-chip-on" : ""}`}
              onClick={() => setCompanyFilter(c)}>{c}</button>
          ))}
        </div>

        {loading && <div className="empty-state">Loading catalogue…</div>}

        {!loading && filtered.length === 0 && (
          <div className="empty-state">
            {products.length === 0
              ? "Nothing is in stock in this branch."
              : "No product matches."}
          </div>
        )}

        {!loading && filtered.length > 0 && (
          <div className="pos-grid">
            {filtered.map((row) => (
              <ProductTile
                key={row.productId}
                row={row}
                units={unitsFor(row)}
                inCart={cartIds.has(row.productId)}
                onAdd={addToCart}
                onInspect={setInspecting}
              />
            ))}
          </div>
        )}
      </section>

      {/* ── CENTRE: bill ── */}
      <section className="pos-bill">
        <header className="pos-bill-head">
          <span>Bill</span>
          <span className="pos-bill-count">
            {cart.length} line{cart.length === 1 ? "" : "s"}
          </span>
          {cart.length > 0 && (
            <button className="pos-mini" onClick={clearCart} title="Clear the bill">
              <Ic path={P.trash} size={13} /> Clear
            </button>
          )}
        </header>

        {cart.length === 0 ? (
          <div className="pos-empty">
            <Ic path={P.card} size={26} />
            <p>{customerId ? "Tap a product to start the bill." : "Choose a customer, then tap a product."}</p>
            <p className="pos-keys">F2 search · F3 scan · F4 customer · F8 hold · F12 charge</p>
          </div>
        ) : (
          <ul className="pos-lines">
            {cart.map((l) => (
              <li key={l.key} className="pos-line">
                <div className="pos-line-main">
                  <div className="pos-line-name">{l.productName}</div>
                  <div className="pos-line-sub mono">
                    {money(l.unitPrice)} × {l.quantity} {l.unit.toLowerCase()}
                    {l.pcsPerUnit > 1 && ` · ${l.quantity * l.pcsPerUnit} pcs`}
                  </div>
                </div>

                {allowedUnits.length > 1 && (
                  <select className="pos-unit" value={l.unit}
                    onChange={(e) => setUnit(l.key, e.target.value)}
                    aria-label={`Unit for ${l.productName}`}>
                    {allowedUnits.map((u) => <option key={u} value={u}>{u}</option>)}
                  </select>
                )}

                <QtyStep line={l} onSet={(n) => setQty(l.key, n)} />

                <div className="pos-line-total mono">
                  {money(round2(l.quantity * l.unitPrice))}
                </div>

                <button className="pos-line-x" onClick={() => removeLine(l.key)}
                  aria-label={`Remove ${l.productName}`}>
                  <Ic path={P.x} size={13} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ── RIGHT: payment ── */}
      <aside className="pos-pay">
        <div className="pos-field">
          <label htmlFor="pos-customer">Customer <span className="pos-hint">F4</span></label>
          <div className="pos-customer-row">
            <CustomerPicker
              id="pos-customer"
              className="pos-select"
              customers={customers}
              selected={customer}
              walkIn={{ label: "Walk-in — cash sale", meta: "no record kept" }}
              hideCode={WALK_IN_CODE}
              onPick={(c) => {
                if (!c) { setCustomerId(WALK_IN); return; }
                // A customer found by searching may not be among the first page
                // held locally, so it joins the list — otherwise the name would
                // resolve to nothing the moment it was chosen.
                setCustomers((cs) =>
                  cs.some((x) => String(custId(x)) === String(custId(c))) ? cs : [...cs, c]);
                setCustomerId(String(custId(c)));
              }}
            />
            <button type="button" className="pos-add-cust" onClick={() => setQuickOpen(true)}
              title="Add a new customer without leaving the till">+</button>
          </div>
          <div className="pos-tier">
            <span className={`pos-tag pos-tag-${tier === "SPECIAL" ? "amber" : tier === "WHOLESALER" ? "teal" : "grey"}`}>
              {tier}
            </span>
            <span>sells in {allowedUnits.join(", ")}</span>
          </div>
        </div>

        <div className="pos-field">
          <label>Payment <span className="pos-hint">F6</span></label>
          <div className="pos-pay-grid">
            {PAYMENTS.map((p) => (
              <button key={p} type="button"
                className={`pos-pay-btn${payMethod === p ? " pos-pay-on" : ""}`}
                onClick={() => setPayMethod(p)} aria-pressed={payMethod === p}>
                {p}
              </button>
            ))}
          </div>
        </div>

        <div className="pos-field">
          <label htmlFor="pos-disc">Bill discount %</label>
          <input id="pos-disc" className="pos-select mono" type="number" min="0" max="100" step="0.01"
            value={discPct} onChange={(e) => setDiscPct(e.target.value)} placeholder="0" />
        </div>

        <div className="pos-field">
          <label htmlFor="pos-paid">Cash received</label>
          <input ref={paidRef} id="pos-paid" className="pos-select mono pos-paid" type="number" min="0"
            step="0.01" value={paidAmt} onChange={(e) => setPaidAmt(e.target.value)}
            placeholder="0.00" />
          {/* Tender buttons — faster than typing, and they add up, so 500+100
              is two taps rather than mental arithmetic at a queue. */}
          <div className="pos-cash-row">
            {QUICK_CASH.map((n) => (
              <button key={n} type="button" className="pos-cash"
                onClick={() => setPaidAmt(String((Number(paidAmt) || 0) + n))}>
                +{n}
              </button>
            ))}
            <button type="button" className="pos-cash pos-cash-exact"
              onClick={() => setPaidAmt(String(totals.payable))}>Exact</button>
          </div>
        </div>

        <div className="pos-totals">
          <div className="pos-total-row"><span>Subtotal</span><span className="mono">{money(totals.subtotal)}</span></div>
          <div className="pos-total-row"><span>Bill discount</span><span className="mono">− {money(totals.invoiceDisc)}</span></div>
          <div className="pos-total-row pos-total-payable">
            <span>Payable</span><span className="mono">{money(totals.payable)}</span>
          </div>
          <div className={`pos-total-row ${totals.change > 0 ? "pos-change" : "pos-due"}`}>
            <span>{totals.change > 0 ? "Change" : "Due"}</span>
            <span className="mono">{money(totals.change > 0 ? totals.change : totals.due)}</span>
          </div>
        </div>

        <div className="pos-actions">
          <button className="pos-btn pos-btn-ghost" onClick={hold} disabled={!cart.length}>
            <Ic path={P.pause} size={13} /> Hold <span className="pos-hint">F8</span>
          </button>
          <button className="pos-btn pos-btn-ghost" onClick={recall} disabled={!held.length}>
            <Ic path={P.play} size={13} /> Recall{held.length ? ` (${held.length})` : ""} <span className="pos-hint">F9</span>
          </button>
          {/* The way back into a sale already rung up. Icon-only so it sits on
              the same row as Hold and Recall rather than pushing Charge out of
              view, and padlocked when this operator would need an
              administrator — so they know before clicking, not after. */}
          <button className="pos-btn pos-btn-ghost pos-btn-icon" onClick={() => setRecentOpen(true)}
            aria-label="Reopen a saved invoice"
            title={mayEdit === false
              ? "Reopen a saved invoice — needs an administrator"
              : "Reopen a saved invoice"}>
            <Ic path={P.receipt} size={15} />
            {mayEdit === false && <span className="pos-lock" aria-hidden="true">🔒</span>}
          </button>
        </div>

        {editing && (
          // An operator who walks away mid-correction must not come back and
          // ring the next customer onto somebody else's invoice.
          <div className="pos-quick-note" style={{ marginBottom: 8 }}>
            Editing <b className="mono">{editing.invoiceNo}</b>{" "}
            <button type="button" className="pos-btn pos-btn-ghost" onClick={cancelEdit}>
              Cancel edit
            </button>
          </div>
        )}
        <button className="pos-charge" onClick={charge}
          disabled={!cart.length || charging || !(editing ? true : perm?.canCreate)}
          title={!perm?.canCreate && !editing ? "You do not have permission to make sales" : undefined}>
          {charging
            ? (editing ? "Saving…" : "Charging…")
            : editing
              ? <>Update {money(totals.payable)} <span className="pos-hint">F12</span></>
              : <>Charge {money(totals.payable)} <span className="pos-hint">F12</span></>}
        </button>
      </aside>

      </div>

      {/* Phones only — the stylesheet hides it once all three panes fit side by
          side. The bill tab carries its line count and the pay tab the amount
          owed, so a cashier browsing the catalogue can still see where the sale
          stands without switching pane to find out. */}
      <nav className="pos-tabs" aria-label="Terminal panes">
        <button type="button"
          className={`pos-tab ${pane === "catalogue" ? "is-on" : ""}`}
          onClick={() => setPane("catalogue")}>
          <Package size={18} />
          <span>Products</span>
        </button>
        <button type="button"
          className={`pos-tab ${pane === "bill" ? "is-on" : ""}`}
          onClick={() => setPane("bill")}>
          <ShoppingCart size={18} />
          <span>Bill</span>
          {cart.length > 0 && <em className="pos-tab-badge">{cart.length}</em>}
        </button>
        <button type="button"
          className={`pos-tab ${pane === "pay" ? "is-on" : ""}`}
          onClick={() => setPane("pay")}>
          <Wallet size={18} />
          <span>{totals.payable > 0 ? money(totals.payable) : "Pay"}</span>
        </button>
      </nav>

      {inspecting && (
        <ProductDetailSheet
          row={inspecting}
          units={unitsFor(inspecting)}
          tier={tier}
          onAdd={(row, unit) => { addToCart(row, unit); setInspecting(null); }}
          onClose={() => setInspecting(null)}
        />
      )}

      {quickOpen && (
        <QuickCustomer
          branchId={branchId}
          onClose={() => setQuickOpen(false)}
          onCreated={(c) => {
            setCustomers((cs) => [...cs, c]);
            setCustomerId(String(c.id));
            setQuickOpen(false);
            addToast(`${custName(c)} added — ${custType(c)}`);
          }}
        />
      )}

      {receipt && <Receipt data={receipt} onClose={() => setReceipt(null)} />}
      {recentOpen && (
        <RecentInvoices mayEdit={mayEdit} onReopen={reopen}
          onUnlockNeeded={(sale) => { setRecentOpen(false); setUnlocking(sale); }}
          onClose={() => setRecentOpen(false)} />
      )}
      {unlocking && (
        <UnlockModal
          sale={unlocking}
          onClose={() => setUnlocking(null)}
          onUnlocked={(token) => { const sale = unlocking; setUnlocking(null); reopen(sale, token); }} />
      )}
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   QUICK CUSTOMER
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Add a customer without leaving the till.
 *
 * Only the three fields the server actually requires, plus the type — because
 * the type decides both the price and which units may be sold, so guessing it
 * later would misprice everything already on the bill. Anything else can be
 * filled in from Master Data afterwards.
 */
function QuickCustomer({ branchId, onClose, onCreated }) {
  const [name, setName] = useState("");
  const [mobile, setMobile] = useState("");
  const [type, setType] = useState("Retailer");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const nameRef = useRef(null);

  useEffect(() => { nameRef.current?.focus(); }, []);

  // The server's own rule, checked here so a queue does not wait on a round
  // trip to learn the number is the wrong length. Blank is allowed: a walk-in
  // buying paracetamol has no reason to give a number, and refusing to save the
  // customer stops the sale rather than improving the record.
  const mobileOk = mobile.trim() === "" || /^0[0-9]{10}$/.test(mobile.trim());
  const canSave = name.trim() && mobileOk && !saving;

  const submit = async (e) => {
    e.preventDefault();
    if (!canSave) return;
    if (!branchId) { setError("Your account has no branch assigned."); return; }
    setSaving(true);
    setError(null);
    try {
      const created = await createCustomer({
        customerName: name.trim(), mobileNo: mobile.trim(),
        customerType: type, branchId, status: "Y",
      });
      const id = created?.id ?? created?.pidCustomer ?? created?.data?.id;
      if (!id) throw new Error("The customer was saved but returned no id.");
      onCreated({ id, name: name.trim(), type, mobileNo: mobile.trim() });
    } catch (err) {
      setError(err?.message || "Could not save the customer.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="pos-receipt-backdrop" onClick={onClose}>
      <form className="pos-quick" onClick={(e) => e.stopPropagation()} onSubmit={submit}
        role="dialog" aria-label="New customer">
        <h3>New customer</h3>

        <label htmlFor="qc-name">Name</label>
        <input ref={nameRef} id="qc-name" className="pos-select" value={name}
          onChange={(e) => setName(e.target.value)} placeholder="Customer name" />

        <label htmlFor="qc-mobile">Mobile <span className="pos-quick-opt">optional</span></label>
        <input id="qc-mobile" className="pos-select mono" value={mobile}
          onChange={(e) => setMobile(e.target.value)} placeholder="01XXXXXXXXX — or leave blank"
          inputMode="numeric" />
        {mobile && !mobileOk && (
          <span className="pos-quick-warn">11 digits starting with 0, or leave it blank</span>
        )}

        <label htmlFor="qc-type">Type</label>
        <select id="qc-type" className="pos-select" value={type}
          onChange={(e) => setType(e.target.value)}>
          <option>Retailer</option>
          <option>Wholesaler</option>
          <option>Special</option>
        </select>
        <span className="pos-quick-note">
          {type === "Retailer"
            ? "Buys pieces only, at the wholesale piece rate."
            : "Buys boxes, strips or pieces."}
        </span>

        {error && <div className="pos-quick-error" role="alert">{error}</div>}

        <div className="pos-receipt-actions">
          <button type="button" className="pos-btn pos-btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="pos-charge pos-charge-sm" disabled={!canSave}>
            {saving ? "Saving…" : "Add & select"}
          </button>
        </div>
      </form>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   RECEIPT
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Shown after the server has accepted the sale, so every figure here is the
 * server's own — the invoice number, the totals, and the batch each line was
 * allocated from. The cart is only used for names.
 */
function Receipt({ data, onClose }) {
  const { sale, paid, method, change } = data;
  const [printId, setPrintId] = useState(null);

  return (
    <div className="pos-receipt-backdrop" onClick={onClose}>
      <div className="pos-receipt" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Receipt">
        <div className="pos-receipt-head">
          <div className="pos-receipt-tick"><Ic path={P.check} size={20} /></div>
          <h3>Sale complete</h3>
          <div className="mono pos-receipt-inv">{sale?.invoiceNo}</div>
        </div>

        <table className="pos-receipt-table">
          <thead>
            <tr><th>Item</th><th>Batch</th><th>Qty</th><th>Amount</th></tr>
          </thead>
          <tbody>
            {(sale?.lines ?? []).map((l) => (
              <tr key={l.lineId}>
                <td>{l.productName || `#${l.productId}`}</td>
                <td className="mono">{l.batchNo || "—"}</td>
                <td className="mono">{Number(l.quantity)} {(l.salesUnit || "").toLowerCase()}</td>
                <td className="mono">{money(l.totalPrice)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="pos-receipt-totals">
          <div><span>Payable</span><span className="mono">{money(sale?.amountTobePaid)}</span></div>
          <div><span>Paid ({method})</span><span className="mono">{money(paid)}</span></div>
          <div className="pos-receipt-change"><span>Change</span><span className="mono">{money(change)}</span></div>
        </div>

        <div className="pos-receipt-actions">
          {/* Opens the 80mm layout rather than printing this modal. window.print()
              here put the app's own dialog on the paper — buttons and all. */}
          <button className="pos-btn pos-btn-ghost" onClick={() => setPrintId(sale?.id ?? sale?.pidTranMst)}>
            Print receipt
          </button>
          <button className="pos-charge pos-charge-sm" onClick={onClose}>New sale</button>
        </div>

        {printId && (
          <PrintDocument saleId={printId} format="pos" onClose={() => setPrintId(null)} />
        )}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   RECENT INVOICES — reopening a sale from the till
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * The invoices raised here today, and the way back into one.
 *
 * A counter operator who mis-rings a sale looks for it on the till, not on a
 * different screen elsewhere in the application. Reopening one still takes
 * either the edit right or a supervisor's password — the padlock says which
 * before anything is clicked, so the supervisor is fetched before a queue forms
 * rather than after.
 */
function RecentInvoices({ mayEdit, onReopen, onUnlockNeeded, onClose }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Only what can still be changed. Listing a delivered invoice with an
    // Edit button that always refuses is worse than not listing it — there
    // the answer is a sales return, not a longer list.
    //
    // Deliberately not limited to today: an invoice rung up last night and
    // still unconfirmed is exactly the one somebody comes in to fix.
    salesService.listSales({ stage: "Submitted", size: 25 })
      .then((r) => setRows(r.items ?? []))
      .catch((e) => addToast(e?.message || "Could not load today's invoices.", "error"))
      .finally(() => setLoading(false));
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps

  const start = (sale) => {
    if (mayEdit) onReopen(sale, null);
    else onUnlockNeeded(sale);
  };

  return (
    <div className="pos-receipt-backdrop" onClick={onClose}>
      <div className="pos-quick" onClick={(e) => e.stopPropagation()}
        role="dialog" aria-label="Reopen an invoice"
        style={{ maxWidth: 660, width: "92%" }}>
        <h3>Reopen an invoice</h3>
        <div className="pos-quick-note" style={{ marginBottom: 10 }}>
          Only invoices still at <b>Submitted</b> can be changed. Once one is on its way to the
          customer, the correction is a sales return.
        </div>

        {loading ? (
          <div className="empty-state">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="empty-state">No invoice is still open for changes.</div>
        ) : (
          <div style={{ maxHeight: "52vh", overflowY: "auto" }}>
            <table className="data-table">
              <thead>
                <tr><th>Invoice</th><th>Customer</th><th>Lines</th><th>Total</th><th></th></tr>
              </thead>
              <tbody>
                {rows.map((s) => (
                  <tr key={s.id}>
                    <td className="mono" style={{ fontWeight: 700 }}>{s.invoiceNo}</td>
                    <td>{s.customerName || "Walk-in"}</td>
                    <td className="mono">{s.lines?.length ?? "—"}</td>
                    <td className="mono">{money(s.amountTobePaid)}</td>
                    <td style={{ textAlign: "right" }}>
                      <button className="pos-btn pos-btn-ghost" onClick={() => start(s)}
                        title={mayEdit === false
                          ? `${s.invoiceNo} is saved — a supervisor must authorise a change`
                          : `Reopen ${s.invoiceNo}`}>
                        {mayEdit === false ? "🔒 Edit" : "Edit"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="pos-receipt-actions">
          <button type="button" className="pos-btn pos-btn-ghost" onClick={onClose}>Close</button>
        </div>
      </div>

    </div>
  );
}
