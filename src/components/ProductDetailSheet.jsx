/**
 * Everything known about one product, without leaving the till.
 *
 * Opened from the ⓘ on a catalogue tile. It answers what a customer asks across
 * the counter — what does it cost per strip, how many are left, when does it
 * expire, who makes it — and lets the cashier add it in a specific unit.
 *
 * <p>A sheet on the right, not a centred dialog: the bill stays visible behind
 * it, so checking a price never hides what has already been rung up.
 *
 * <p>This is not how a unit is normally chosen. Every tile and every bill line
 * carries its units inline, one tap each. The buttons here are for someone who
 * already opened the panel to check something and then decided to sell it.
 */
import React, { useEffect, useState } from "react";
import {
  X, Building2, Package, CalendarClock, Barcode, Layers, Plus, Boxes, Pill,
} from "lucide-react";
import { moneyFull as money } from "../utils/format.js";
import uploadUrl from "../lib/uploadUrl.js";

/**
 * The dosage form, read out of the product name.
 *
 * <p>Ordered longest-first where terms overlap: "eye drop" must be tested
 * before "drop", and "capsule" before "cap", or the shorter term swallows the
 * longer one. Used for the picture a product has no photograph for — and the
 * form is the thing worth encoding, because "Napa" is a tablet, a syrup and a
 * suppository, and handing over the wrong one is a real mistake.
 */
function dosageForm(name) {
  const n = (name || "").toLowerCase();
  if (/\b(inj|injection|vial|ampoule|iv|im)\b/.test(n)) return "Injection";
  if (/\b(syrup|susp|suspension|solution|elixir|syp)\b/.test(n)) return "Syrup";
  if (/\b(eye drop|ear drop|nasal|drops?)\b/.test(n)) return "Drops";
  if (/\b(cream|ointment|gel|lotion|balm)\b/.test(n)) return "Cream";
  if (/\b(inhaler|puff|nebul)\b/.test(n)) return "Inhaler";
  if (/\b(sachet|powder|granule|ors)\b/.test(n)) return "Sachet";
  if (/\b(cap|caps|capsule)\b/.test(n)) return "Capsule";
  return "Tablet";
}

/** A stable colour per product, so the same medicine looks the same every day. */
function hueOf(seed) {
  let h = 0;
  for (let i = 0; i < String(seed).length; i++) h = (h * 31 + String(seed).charCodeAt(i)) % 360;
  return h;
}

/**
 * The picture.
 *
 * <p>The photograph from the product master where one exists — through
 * `uploadUrl`, because the catalogue stores a path and in development the page
 * and the files are served from different ports. Where there is none, a tinted
 * panel carrying the dosage form, which is more use than a grey box.
 */
function ProductImage({ row }) {
  const [failed, setFailed] = useState(false);
  const src = row.productImageUrl && !failed ? uploadUrl(row.productImageUrl) : null;
  const hue = hueOf(row.productName || row.productId);

  if (src) {
    return (
      <div className="pd-image">
        {/* contain, not cover: a pack shot is artwork with its own margins, and
            cropping it to fill the frame cuts the strength off the box. */}
        <img src={src} alt="" onError={() => setFailed(true)} />
      </div>
    );
  }
  return (
    <div
      className="pd-image pd-image-fallback"
      style={{
        background: `hsl(${hue} 62% 93%)`,
        color: `hsl(${hue} 55% 32%)`,
      }}
    >
      <Pill size={38} strokeWidth={1.5} />
      <span className="pd-image-form">{dosageForm(row.productName)}</span>
    </div>
  );
}

export default function ProductDetailSheet({ row, units, tier, onAdd, onClose }) {
  /* Escape closes. A panel with no visible way out is a trap for anyone not
     using touch, and the close button is the far corner of the screen. */
  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const days = row.earliestExpiry
    ? Math.round((new Date(row.earliestExpiry) - new Date()) / 86400000)
    : null;
  const expTone = days == null ? null : days < 0 ? "gone" : days <= 60 ? "urgent" : days <= 180 ? "soon" : null;
  const outOfStock = Number(row.stockPcs || 0) <= 0;
  const low = !outOfStock && row.alertQty > 0 && Number(row.stockPcs) <= Number(row.alertQty);

  return (
    <div className="pd-overlay" role="dialog" aria-label={`${row.productName} details`}>
      <button type="button" className="pd-scrim" aria-label="Close" onClick={onClose} />

      <aside className="pd-sheet">
        <header className="pd-head">
          <ProductImage row={row} />
          <div className="pd-head-text">
            <h2 className="pd-name">{row.productName}</h2>
            <p className="pd-company">{row.companyName || "—"}</p>
            <div className="pd-badges">
              <span className="pd-badge pd-badge-form">{dosageForm(row.productName)}</span>
              {outOfStock && <span className="pd-badge pd-badge-red">OUT OF STOCK</span>}
              {low && <span className="pd-badge pd-badge-amber">LOW STOCK</span>}
              {expTone === "gone" && <span className="pd-badge pd-badge-red">EXPIRED</span>}
              {expTone === "urgent" && <span className="pd-badge pd-badge-red">{days}d LEFT</span>}
              {expTone === "soon" && <span className="pd-badge pd-badge-amber">EXP SOON</span>}
            </div>
          </div>
          <button type="button" className="pd-close" onClick={onClose} aria-label="Close">
            <X size={17} />
          </button>
        </header>

        <div className="pd-body">
          {/* ── Sell it ── */}
          <section className="pd-section">
            <h3 className="pd-section-title">Add to the bill</h3>
            {units.length === 0 ? (
              <p className="pd-empty">
                No price is set for a {tier.toLowerCase()} on this product, so it
                cannot be sold here yet.
              </p>
            ) : (
              <div className="pd-units">
                {units.map((u) => (
                  <button
                    key={u.unit}
                    type="button"
                    className="pd-unit"
                    disabled={outOfStock}
                    onClick={() => onAdd(row, u.unit)}
                    title={`Add one ${u.unit.toLowerCase()} at ${money(u.price)}`}
                  >
                    <span className="pd-unit-left">
                      <Plus size={14} />
                      <b>{u.unit}</b>
                      {u.per > 1 && <em>{u.per} pcs</em>}
                    </span>
                    <span className="pd-unit-price mono">{money(u.price)}</span>
                  </button>
                ))}
              </div>
            )}
          </section>

          {/* ── The facts ── */}
          <section className="pd-section">
            <h3 className="pd-section-title">Details</h3>
            <dl className="pd-facts">
              <Fact icon={<Building2 size={13} />} label="Company" value={row.companyName || "—"} />
              <Fact icon={<Package size={13} />} label="In stock" value={row.stockDisplay || `${row.stockPcs} pcs`} />
              <Fact
                icon={<Layers size={13} />}
                label="Packing"
                value={[
                  row.pcsPerBox ? `${row.pcsPerBox} pcs / box` : null,
                  row.pcsPerStrip ? `${row.pcsPerStrip} pcs / strip` : null,
                ].filter(Boolean).join(" · ") || "—"}
              />
              <Fact icon={<CalendarClock size={13} />} label="Earliest expiry" value={row.earliestExpiry || "—"} />
              {row.batchCount != null && (
                <Fact icon={<Boxes size={13} />} label="Batches" value={String(row.batchCount)} />
              )}
              <Fact icon={<Barcode size={13} />} label="SKU" value={row.sku || "—"} mono />
              {row.barcode && <Fact icon={<Barcode size={13} />} label="Barcode" value={row.barcode} mono />}
            </dl>
          </section>

          {/* The price printed on the strip, for the customer who reads it and
              asks why the till says something else. */}
          {row.prices && (
            <section className="pd-section">
              <h3 className="pd-section-title">Printed MRP</h3>
              <div className="pd-mrp">
                {[["Box", row.prices.boxMrp], ["Strip", row.prices.stripMrp], ["Pcs", row.prices.pcsMrp]]
                  .map(([k, v]) => (
                    <div key={k} className="pd-mrp-cell">
                      <span className="pd-mrp-label">{k}</span>
                      <span className="pd-mrp-value mono">{v == null ? "—" : money(Number(v))}</span>
                    </div>
                  ))}
              </div>
            </section>
          )}
        </div>
      </aside>
    </div>
  );
}

function Fact({ icon, label, value, mono }) {
  return (
    <div className="pd-fact">
      <dt>{icon}{label}</dt>
      <dd className={mono ? "mono" : undefined}>{value}</dd>
    </div>
  );
}
