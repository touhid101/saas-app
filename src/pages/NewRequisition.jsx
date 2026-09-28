/**
 * Raising a requisition by hand.
 *
 * <b>Why this exists.</b> A requisition could only be created by the Copilot,
 * which writes one from the stock ledger. That covers reordering what is running
 * out; it does not cover a pharmacist asked for something the shop does not
 * stock, or a line the Copilot has no history to reason about.
 *
 * <b>It is the same document either way.</b> Both land in DRAFT and go through
 * the same approval, so a typed line and a suggested one are indistinguishable
 * once saved. The only difference recorded is the source.
 *
 * <b>Two ways in, because buyers work two ways.</b> A whole manufacturer at
 * once, or a set ticked off the product list. They add to the same table and can
 * be mixed.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import reqService from "../services/requisitionService.js";

const money = (n) =>
  "৳ " + Number(n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const UNITS = ["BOX", "STRIP", "PCS"];
const PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];

const Ic = ({ d, size = 15 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {(Array.isArray(d) ? d : [d]).map((p, i) => <path key={i} d={p} />)}
  </svg>
);

const ICON = {
  trash:  ["M3 6h18", "M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2",
           "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"],
  search: ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  plus:   ["M12 5v14", "M5 12h14"],
  box:    ["M21 8 12 3 3 8l9 5 9-5z", "M3 8v9l9 5 9-5V8"],
};

/** A blank line, with the defaults a buyer would otherwise type every time. */
const blankLine = (product) => ({
  key: `${product.id}-${Math.random().toString(36).slice(2, 8)}`,
  pidProduct: product.id,
  productName: product.productName,
  productCode: product.productCode,
  companyName: product.companyName,
  currentStock: product.currentStock,
  requestedQty: 1,
  // Stock is bought by the box, so that is what a requisition asks for. A line
  // can still be switched to strips or pieces where that is what is wanted.
  unit: "BOX",
  // Buy price = trade price + IGTA per box: the figure a purchase-order line
  // carries and the one the split prices with, so the estimate here and the
  // orders it becomes agree. Trade price alone understated every line by the
  // IGTA. Still an estimate — what the supplier charges is settled on the
  // purchase.
  estimatedUnitCost: product.buyPrice ?? (product.tpPrice != null ? Number(product.tpPrice) + Number(product.igta || 0) : ""),
  priority: "MEDIUM",
});

/* ═══════════════════════════════════════════════════════════════════════════
   ADDING BY MANUFACTURER
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Every product from one company, in one press.
 *
 * The count sits on the option and on the button rather than being discovered
 * afterwards: a buyer about to create forty lines should be told it is forty
 * before pressing, not left to scroll the table and find out.
 */
function CompanyBulkAdd({ onAddMany, companies, companyId, setCompanyId }) {
  const addToast = useToast();
  const [busy, setBusy] = useState(false);

  const chosen = companies.find((c) => String(c.companyId) === String(companyId));

  const run = async () => {
    if (!chosen) return;
    setBusy(true);
    try {
      /* The whole manufacturer, and the server now means it: a request carrying
         a manufacturer is not capped. This asked for 500 before and was cut to
         50 without saying so, which is how a buyer ended up ordering a fraction
         of a maker's range and had no way to see what was missing. */
      const rows = await reqService.searchProducts("", 0, chosen.companyId);
      const list = Array.isArray(rows) ? rows : [];
      const added = onAddMany(list);
      const skipped = list.length - added;

      /* Said against the count on the option beside it. If the two disagree the
         buyer should hear it from us rather than discover it in the table —
         the usual cause is a product retired since the counts were read. */
      const expected = Number(chosen.products) || 0;
      const shortfall = expected > list.length ? expected - list.length : 0;

      addToast(
        added === 0
          ? `Every ${chosen.companyName} product is already on this requisition.`
          : `Added ${added} of ${list.length} ${chosen.companyName} product${list.length === 1 ? "" : "s"}`
            + (skipped ? `, ${skipped} already there.` : ".")
            + (shortfall ? ` ${shortfall} no longer available.` : ""),
        added === 0 ? "warning" : "success");
    } catch (e) {
      addToast(e?.message || "Could not add that manufacturer's products.", "error");
    } finally { setBusy(false); }
  };

  if (companies.length === 0) return null;

  return (
    <section className="req-add-block">
      <h3 className="req-add-head"><Ic d={ICON.box} size={14} /> By manufacturer</h3>
      <p className="req-add-hint">
        Adds every product you stock from one maker — and narrows the list beside it
        to the same maker, so you can pick a few instead.
      </p>
      <div className="req-add-row">
        <select className="input" value={companyId} disabled={busy}
                onChange={(e) => setCompanyId(e.target.value)} aria-label="Manufacturer">
          <option value="">Choose a manufacturer…</option>
          {companies.map((c) => (
            <option key={c.companyId} value={c.companyId}>
              {c.companyName} — {c.products} product{c.products === 1 ? "" : "s"}
            </option>
          ))}
        </select>
        <button className="btn btn-teal" disabled={!chosen || busy} onClick={run}>
          {busy ? "Adding…" : chosen ? `Add all ${chosen.products}` : "Add all"}
        </button>
      </div>
    </section>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PICKING FROM THE PRODUCT LIST
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Tick several, then add them together.
 *
 * <p>Clicking a result used to add it immediately and close the list, so adding
 * six products meant six searches. Selecting is now separate from adding: the
 * list stays open, ticks accumulate, and one button commits them.
 */
function ProductPicker({ onAddMany, alreadyAdded, companyId, companyName }) {
  const addToast = useToast();
  const [q, setQ] = useState("");
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(null);
  const [ticked, setTicked] = useState(() => new Set());

  /* Debounced, and a blank box lists the branch rather than nothing. Requiring
     two characters meant you had to know the name of the thing you were looking
     for, which is the opposite of a picker. */
  useEffect(() => {
    const term = q.trim();
    let live = true;
    setBusy(true);
    setFailed(null);
    const t = setTimeout(() => {
      /* Wrapped so a synchronous throw lands in the same catch as a failed
         request. One bad call once escaped the chain entirely and the box sat on
         "searching…" for ever, with no error and no results. */
      Promise.resolve()
        .then(() => reqService.searchProducts(term, 100, companyId || null))
        .then((r) => { if (live) setRows(Array.isArray(r) ? r : []); })
        .catch((e) => {
          if (!live) return;
          setRows([]);
          setFailed(e?.message || "Could not search products.");
        })
        .finally(() => { if (live) setBusy(false); });
    }, term ? 250 : 0);
    return () => { live = false; clearTimeout(t); };
  }, [q, companyId]);

  const selectable = rows.filter((r) => !alreadyAdded.has(r.id));
  const allTicked = selectable.length > 0 && selectable.every((r) => ticked.has(r.id));

  const toggle = (id) => {
    const next = new Set(ticked);
    next.has(id) ? next.delete(id) : next.add(id);
    setTicked(next);
  };

  const toggleAll = () => {
    const next = new Set(ticked);
    if (allTicked) selectable.forEach((r) => next.delete(r.id));
    else selectable.forEach((r) => next.add(r.id));
    setTicked(next);
  };

  const commit = () => {
    const added = onAddMany(rows.filter((r) => ticked.has(r.id)));
    addToast(`Added ${added} product${added === 1 ? "" : "s"}.`, "success");
    setTicked(new Set());
  };

  return (
    <section className="req-add-block">
      <h3 className="req-add-head">
        <Ic d={ICON.search} size={14} /> From your product list
        {companyName && <span className="req-filter-chip">{companyName}</span>}
      </h3>
      <p className="req-add-hint">
        {companyName
          ? `Showing ${companyName} only. Tick what you need, then add them together.`
          : "Tick as many as you need, then add them together."}
      </p>

      <input
        className="input"
        placeholder="Search by name, code or barcode — or leave blank to see everything"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        aria-label="Find a product"
      />

      <div className="req-list" role="group" aria-label="Products in this branch">
        {busy && <div className="req-list-msg">Searching…</div>}

        {!busy && failed && <div className="req-list-msg is-error" role="alert">{failed}</div>}

        {!busy && !failed && rows.length === 0 && (
          <div className="req-list-msg">
            {q.trim()
              ? `Nothing matches “${q.trim()}”${companyName ? ` under ${companyName}` : ""}.`
              : companyName
                ? `No ${companyName} products in this branch.`
                : "No products in this branch yet."}
          </div>
        )}

        {!busy && !failed && rows.length > 0 && (
          <>
            <label className="req-list-all">
              <input type="checkbox" checked={allTicked} onChange={toggleAll}
                     disabled={selectable.length === 0} />
              <span>{allTicked ? "Clear all" : `Select all ${selectable.length}`}</span>
              <span className="req-list-shown">{rows.length} shown</span>
            </label>

            {rows.map((p) => {
              const already = alreadyAdded.has(p.id);
              return (
                <label key={p.id} className={`req-list-row${already ? " is-added" : ""}`}>
                  <input type="checkbox" checked={ticked.has(p.id)} disabled={already}
                         onChange={() => toggle(p.id)} />
                  <span className="req-list-main">
                    <span className="req-list-name">
                      {p.productName}
                      {already && <Pill tone="grey">added</Pill>}
                      {p.status === "N" && <Pill tone="amber">inactive</Pill>}
                    </span>
                    <span className="req-list-meta">
                      {p.companyName || "—"} · {p.productCode || p.sku || "no code"}
                    </span>
                  </span>
                  <span className="req-list-stock">
                    {p.currentStock != null
                      ? `${Number(p.currentStock).toLocaleString()} in stock` : "—"}
                  </span>
                </label>
              );
            })}
          </>
        )}
      </div>

      <div className="req-add-row">
        <button className="btn btn-primary" disabled={ticked.size === 0} onClick={commit}>
          <Ic d={ICON.plus} size={14} /> Add {ticked.size ? `${ticked.size} ` : ""}selected
        </button>
        {ticked.size > 0 && (
          <button className="btn btn-ghost btn-sm" onClick={() => setTicked(new Set())}>
            Clear selection
          </button>
        )}
      </div>
    </section>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   THE FORM
   ═══════════════════════════════════════════════════════════════════════════ */

export default function NewRequisition({ branchName, onSaved, onCancel }) {
  const addToast = useToast();
  const [lines, setLines] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  /* The manufacturer is one choice with two effects — it decides what "Add all"
     adds and what the list beside it shows. Held here rather than inside either
     panel so the two cannot disagree about which maker is selected. */
  const [companies, setCompanies] = useState([]);
  const [companyId, setCompanyId] = useState("");
  useEffect(() => { reqService.productCompanies().then(setCompanies); }, []);
  const companyName = companies.find(
    (c) => String(c.companyId) === String(companyId))?.companyName ?? null;

  const added = useMemo(() => new Set(lines.map((l) => l.pidProduct)), [lines]);

  /**
   * Adds products, skipping anything already on the requisition.
   *
   * Returns how many were actually added, so the caller can say so — "added 12"
   * and "added 12, 3 already there" are different outcomes and the buyer should
   * not have to count rows to tell them apart.
   */
  const addMany = useCallback((products) => {
    let count = 0;
    setLines((ls) => {
      const have = new Set(ls.map((l) => l.pidProduct));
      const fresh = products.filter((p) => !have.has(p.id));
      count = fresh.length;
      return fresh.length ? [...ls, ...fresh.map(blankLine)] : ls;
    });
    return count;
  }, []);

  const patch = (key, changes) =>
    setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...changes } : l)));

  const drop = (key) => setLines((ls) => ls.filter((l) => l.key !== key));

  const estimated = lines.reduce(
    (sum, l) => sum + (Number(l.requestedQty) || 0) * (Number(l.estimatedUnitCost) || 0), 0);

  const save = async () => {
    if (lines.length === 0) { setError("Add at least one product."); return; }
    const bad = lines.find((l) => !(Number(l.requestedQty) > 0));
    if (bad) { setError(`${bad.productName} needs a quantity above zero.`); return; }

    setBusy(true); setError(null);
    try {
      const created = await reqService.createRequisition({ lines });
      addToast(`${created?.requisitionNo ?? "Requisition"} saved as a draft.`, "success");
      onSaved?.(created);
    } catch (e) {
      setError(e?.message || "Could not save the requisition.");
    } finally { setBusy(false); }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <button className="btn btn-ghost btn-sm" style={{ marginBottom: 10 }} onClick={onCancel}>
            ← All requisitions
          </button>
          <h1>New requisition</h1>
          <div className="page-sub">
            Raised for {branchName || "your pharmacy"} and saved as a draft — it goes through the
            same approval as one the Copilot raises.
          </div>
        </div>

        {/* The two figures that decide whether this is ready, kept in view while
            the table below scrolls. */}
        <div className="req-tally">
          <div>
            <div className="req-tally-value">{lines.length}</div>
            <div className="req-tally-label">line{lines.length === 1 ? "" : "s"}</div>
          </div>
          <div>
            <div className="req-tally-value">{money(estimated)}</div>
            <div className="req-tally-label">estimated</div>
          </div>
        </div>
      </div>

      <Card eyebrow="Step 1" title="Choose what to order">
        <div className="req-add-grid">
          <CompanyBulkAdd onAddMany={addMany} companies={companies}
                          companyId={companyId} setCompanyId={setCompanyId} />
          <ProductPicker onAddMany={addMany} alreadyAdded={added}
                         companyId={companyId} companyName={companyName} />
        </div>
      </Card>

      <div style={{ height: 14 }} />

      <Card pad={false}>
        <div style={{ padding: "14px 18px 0" }}>
          <div className="card-head">
            <div>
              <div className="card-eyebrow">Step 2</div>
              <div className="card-title">
                {lines.length === 0 ? "Nothing added yet"
                  : `${lines.length} line${lines.length === 1 ? "" : "s"} to order`}
              </div>
            </div>
            {lines.length > 0 && (
              <button className="btn btn-ghost btn-sm" onClick={() => setLines([])}>Clear all</button>
            )}
          </div>
        </div>

        {lines.length === 0 ? (
          <div className="empty-state" style={{ margin: "8px 18px 18px" }}>
            Pick a manufacturer or search your products above.
          </div>
        ) : (
          <div className="table-scroll" style={{ padding: "0 18px 4px" }}>
            <table className="data-table req-lines">
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num" style={{ width: 104 }}>Quantity</th>
                  <th style={{ width: 96 }}>Unit</th>
                  <th className="num" style={{ width: 124 }}>Est. cost</th>
                  <th style={{ width: 134 }}>Priority</th>
                  <th className="num" style={{ width: 120 }}>Line total</th>
                  <th style={{ width: 44 }} aria-label="Remove"></th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.key}>
                    <td>
                      <div className="req-line-name">{l.productName}</div>
                      <div className="req-line-meta">
                        {l.companyName || "—"}
                        {l.currentStock != null && <> · {Number(l.currentStock).toLocaleString()} in stock</>}
                      </div>
                    </td>
                    <td className="num">
                      <input className="input num" type="number" min="0.01" step="1"
                             value={l.requestedQty}
                             onChange={(e) => patch(l.key, { requestedQty: e.target.value })}
                             aria-label={`Quantity for ${l.productName}`} />
                    </td>
                    <td>
                      <select className="input" value={l.unit}
                              onChange={(e) => patch(l.key, { unit: e.target.value })}
                              aria-label={`Unit for ${l.productName}`}>
                        {UNITS.map((u) => <option key={u} value={u}>{u}</option>)}
                      </select>
                    </td>
                    <td className="num">
                      <input className="input num" type="number" min="0" step="0.01"
                             value={l.estimatedUnitCost}
                             onChange={(e) => patch(l.key, { estimatedUnitCost: e.target.value })}
                             aria-label={`Estimated cost for ${l.productName}`} />
                    </td>
                    <td>
                      {/* Coloured by value, so an approver scanning the column
                          sees the critical lines without reading every row. */}
                      <select className={`input req-priority is-${l.priority.toLowerCase()}`}
                              value={l.priority}
                              onChange={(e) => patch(l.key, { priority: e.target.value })}
                              aria-label={`Priority for ${l.productName}`}>
                        {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
                      </select>
                    </td>
                    <td className="num mono">
                      {money((Number(l.requestedQty) || 0) * (Number(l.estimatedUnitCost) || 0))}
                    </td>
                    <td className="num">
                      <button className="icon-btn req-remove" onClick={() => drop(l.key)}
                              title={`Remove ${l.productName}`}
                              aria-label={`Remove ${l.productName}`}>
                        <Ic d={ICON.trash} size={15} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan={5} className="num req-total-label">Estimated total</td>
                  <td className="num mono req-total-value">{money(estimated)}</td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </Card>

      <div style={{ height: 14 }} />

      <Card>
        {error && (
          <div style={{ color: "var(--clay)", fontSize: 13, marginBottom: 10 }} role="alert">{error}</div>
        )}
        <div className="flex-gap">
          <button className="btn btn-primary" disabled={busy || lines.length === 0} onClick={save}>
            {busy ? "Saving…" : "Save as draft"}
          </button>
          <button className="btn btn-ghost" onClick={onCancel} disabled={busy}>Cancel</button>
          <span style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>
            Nothing is ordered yet — a draft has to be sent for approval first.
          </span>
        </div>
      </Card>
    </div>
  );
}
