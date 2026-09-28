/**
 * Adding or editing one catalogue entry.
 *
 * <b>Shaped like Product Master's create form, on purpose.</b> The two screens
 * describe the same kind of thing and are used by the same people; a modal with
 * a flat list of boxes on one and a stepped full-page form on the other made them
 * feel like two applications. Same sections, same image panel, same footer.
 *
 * <b>What it is not.</b> A catalogue entry has no price, no stock, no shelf and no
 * supplier — those belong to a pharmacy. The prices here are called suggested
 * because they are where a pharmacy starts, not what it must charge, and the form
 * says so rather than leaving somebody to assume otherwise.
 *
 * <b>The image.</b> Held until the entry exists, then uploaded — the file goes to
 * its own endpoint keyed by the entry's id, so there is nothing to attach it to
 * until the entry has been saved. Same order Product Master uses.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";
import { Card, Field, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import apiClient from "../lib/apiClient.js";
import { resolveImageUrl } from "../services/productMasterService.js";

const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

const P = {
  pill:  ["M10.5 20.5a7.07 7.07 0 0 1-10-10l6-6a7.07 7.07 0 0 1 10 10z", "M8.5 8.5l7 7"],
  calc:  ["M4 3h16v18H4z", "M8 7h8", "M8 11h.01M12 11h.01M16 11h.01", "M8 15h.01M12 15h.01M16 15h8"],
  box:   ["M21 8l-9-5-9 5 9 5 9-5z", "M3 8v8l9 5 9-5V8", "M12 13v8"],
  image: ["M3 3h18v18H3z", "M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M21 15l-5-5L5 21"],
  power: ["M18.36 6.64a9 9 0 1 1-12.73 0", "M12 2v10"],
  back:  ["M19 12H5", "M12 19l-7-7 7-7"],
  x:     ["M18 6L6 18", "M6 6l12 12"],
};

/** Section divider — mirrors Product Master's, down to the numbered eyebrow. */
const Section = ({ n, title, sub, icon }) => (
  <div style={{
    display: "flex", alignItems: "center", gap: 11,
    margin: "26px 0 14px", paddingBottom: 10, borderBottom: "1px solid var(--line)",
  }}>
    <span style={{
      width: 28, height: 28, borderRadius: 8, flexShrink: 0,
      background: "var(--amber-soft)", color: "var(--amber-deep)",
      display: "inline-flex", alignItems: "center", justifyContent: "center",
    }}><Ic path={icon} size={14} /></span>
    <div>
      <div className="card-eyebrow" style={{ marginBottom: 2 }}>Step {n}</div>
      <div style={{ fontSize: 14, fontWeight: 700, lineHeight: 1.2 }}>{title}</div>
    </div>
    {sub && (
      <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginLeft: "auto",
                    textAlign: "right", maxWidth: 380 }}>{sub}</div>
    )}
  </div>
);

const Req = () => <span style={{ color: "var(--clay)", marginLeft: 2 }} aria-hidden="true">*</span>;

const Grid = ({ min = 200, children }) => (
  <div style={{ display: "grid", gap: "0 16px",
                gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))` }}>
    {children}
  </div>
);

const BLANK = {
  // This catalogue is mostly medicine, so PHARMA is the default and the fuller
  // form. General hides the generic rather than revealing anything.
  productType: "PHARMA",
  productName: "", strength: "", suggestedMrp: "", suggestedTp: "", igta: "", buyPrice: "",
  productCode: "", barcode: "", prescriptionFlag: "N",
  pidGlobalCompany: "", pidGlobalGeneric: "", pidGlobalCategory: "",
  pidGlobalSubCategory: "", pidGlobalUnit: "", pidGlobalLeaf: "", pidGlobalBox: "",
};

export default function CatalogueForm({ entry, masters, onDone, onCancel }) {
  const addToast = useToast();
  const confirm = useConfirm();
  const fileRef = useRef(null);

  const isEdit = !!entry?.id;
  const [v, setV] = useState(() => ({ ...BLANK, ...(entry ?? {}) }));
  const isGeneral = v.productType === "GENERAL";
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);

  /* The picked file and its preview, held until there is an entry to attach it
     to. `dropImage` is separate from "no file picked": one means remove what is
     there, the other means leave it alone. */
  const [picked, setPicked] = useState(null);
  const [preview, setPreview] = useState(null);
  const [dropImage, setDropImage] = useState(false);

  const set = (k, value) => { setV((d) => ({ ...d, [k]: value })); setDirty(true); };

  /* Buy price = TP + IGTA, the same arithmetic Product Master does on the
     server, offered here as a default: it follows the two figures until the
     operator types a buy price of their own, and stops the moment they do. */
  const [buyFollows, setBuyFollows] = useState(() => entry?.buyPrice == null || entry.buyPrice === "");
  const sum2 = (a, b) => { const x = Number(a), y = Number(b); return (a === "" && b === "") || Number.isNaN(x + y) ? "" : (x + y).toFixed(2); };
  const setTp = (tp) => setV((d) => { setDirty(true); return { ...d, suggestedTp: tp, buyPrice: buyFollows ? sum2(tp, d.igta ?? "") : d.buyPrice }; });
  const setIgta = (ig) => setV((d) => { setDirty(true); return { ...d, igta: ig, buyPrice: buyFollows ? sum2(d.suggestedTp ?? "", ig) : d.buyPrice }; });

  /* Revoke the object URL when it is replaced or the form closes — a preview left
     behind holds the file in memory for as long as the tab is open. */
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);

  /* Warn before a browser navigation discards unsaved edits, as Product Master does. */
  useEffect(() => {
    if (!dirty && !picked) return undefined;
    const warn = (e) => { e.preventDefault(); e.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty, picked]);

  /* Only sub-categories under the chosen category: offering the rest invites
     picking one that belongs elsewhere, which the server refuses. */
  const subCategories = useMemo(() => {
    const chosen = Number(v.pidGlobalCategory) || null;
    if (!chosen) return [];
    return (masters.subCategories ?? []).filter((s) => Number(s.categoryId) === chosen);
  }, [masters.subCategories, v.pidGlobalCategory]);

  /* Only boxes built from the chosen strip, for the same reason. */
  const boxes = useMemo(() => {
    const leaf = Number(v.pidGlobalLeaf) || null;
    const all = masters.boxes ?? [];
    return leaf ? all.filter((b) => Number(b.leafId) === leaf) : all;
  }, [masters.boxes, v.pidGlobalLeaf]);

  /* Through resolveImageUrl: the server returns a path relative to the API host,
     and in development Vite serves on a different port — a bare "/uploads/..."
     resolves against the wrong origin and 404s. */
  const shownImage = preview || (dropImage ? null : resolveImageUrl(entry?.productImageUrl));

  const pickImage = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      return addToast("That image is larger than 2 MB.", "warning");
    }
    if (preview) URL.revokeObjectURL(preview);
    setPicked(file);
    setPreview(URL.createObjectURL(file));
    setDropImage(false);
  };

  const clearImage = () => {
    if (preview) URL.revokeObjectURL(preview);
    setPicked(null);
    setPreview(null);
    // Only meaningful when there is a saved image to remove; on a new entry it
    // just cancels the pick.
    setDropImage(!!entry?.productImageUrl);
  };

  const num = (x) => (x === "" || x == null ? null : Number(x));

  const save = async () => {
    if (!v.productName?.trim()) return addToast("Give the medicine a name.", "warning");
    setBusy(true);
    try {
      /* Listed one by one rather than spread from the row.
         The list row carries fields the entry does not own — `id`, and
         `productImageUrl`, which the server derives — and the API rejects any
         property it does not recognise rather than silently dropping it. Naming
         what this form actually edits means a field added to the response can
         never break saving.

         The names the masters resolve to (companyName, genericName, …) are left
         out too: the server writes those from the chosen list entry, and sending
         them back would be the client asserting a value it does not decide. */
      const general = v.productType === "GENERAL";
      const body = {
        productType: general ? "GENERAL" : "PHARMA",
        productName: v.productName?.trim(),
        strength: v.strength || null,
        productCode: v.productCode || null,
        barcode: v.barcode || null,
        prescriptionFlag: v.prescriptionFlag || "N",
        suggestedMrp: num(v.suggestedMrp),
        suggestedTp: num(v.suggestedTp),
        igta: num(v.igta),
        buyPrice: num(v.buyPrice),
        pidGlobalCompany: num(v.pidGlobalCompany),
        // Cleared, not carried: a generic typed before the type was switched to
        // General is a value the form no longer shows and nobody re-confirmed.
        pidGlobalGeneric: general ? null : num(v.pidGlobalGeneric),
        pidGlobalCategory: num(v.pidGlobalCategory),
        pidGlobalSubCategory: num(v.pidGlobalSubCategory),
        pidGlobalUnit: num(v.pidGlobalUnit),
        pidGlobalLeaf: num(v.pidGlobalLeaf),
        pidGlobalBox: num(v.pidGlobalBox),
      };

      const saved = isEdit
        ? await apiClient.put(`/api/admin/global-products/${entry.id}`, body)
        : await apiClient.post("/api/admin/global-products", body);
      const id = saved?.id ?? entry?.id;

      /* The picture goes to its own endpoint, which needs the entry to exist —
         so it follows the save rather than travelling with it. */
      if (picked && id) {
        const form = new FormData();
        form.append("file", picked);
        await apiClient.upload(`/api/admin/global-products/${id}/image`, form);
      } else if (dropImage && id) {
        await apiClient.delete(`/api/admin/global-products/${id}/image`);
      }

      addToast(isEdit ? "Catalogue updated." : "Added to the catalogue.", "success");
      setDirty(false);
      onDone();
    } catch (e) {
      addToast(e?.message || "Could not save.", "error");
    } finally { setBusy(false); }
  };

  const cancel = async () => {
    if (dirty || picked) {
      const ok = await confirm({
        title: "Discard your changes?",
        body: "Nothing you have typed here has been saved yet.",
        confirmLabel: "Discard changes",
        cancelLabel: "Keep editing",
        tone: "danger",
      });
      if (!ok) return;
    }
    onCancel();
  };

  const pick = (k, label, list, opts = {}) => (
    <Field label={opts.required ? <>{label}<Req /></> : label}>
      <select value={v[k] ?? ""} disabled={opts.disabled}
              onChange={(e) => {
                set(k, e.target.value);
                // A sub-category belongs to one category and a box to one strip,
                // so changing the parent cannot leave the old child behind.
                if (k === "pidGlobalCategory") set("pidGlobalSubCategory", "");
                if (k === "pidGlobalLeaf") set("pidGlobalBox", "");
              }}>
        <option value="">{opts.emptyLabel ?? "—"}</option>
        {(list ?? []).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
      </select>
    </Field>
  );

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>
            {isEdit ? "Edit catalogue entry"
                    : isGeneral ? "Add a general product" : "Add a medicine"}
          </h1>
          <div className="page-sub">
            A description pharmacies copy to make their own product — not a product itself.
          </div>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={cancel} disabled={busy}>
          <Ic path={P.back} size={13} /> Back to catalogue
        </button>
      </div>

      <Card>
        {/* 1 — Identity */}
        <Section n={1} icon={P.pill} title="Basic Information"
                 sub="Identity and classification" />
        <Grid min={210}>
          <Field label={<>{isGeneral ? "Product name" : "Medicine name"}<Req /></>}>
            <input value={v.productName ?? ""} onChange={(e) => set("productName", e.target.value)} />
          </Field>
          <Field label="Strength">
            <input value={v.strength ?? ""} placeholder="500mg"
                   onChange={(e) => set("strength", e.target.value)} />
          </Field>
          <Field label={<>Product type<Req /></>}
                 hint={isGeneral ? "No generic needed" : "A generic is required"}>
            <select value={v.productType ?? "PHARMA"}
                    onChange={(e) => set("productType", e.target.value)}>
              <option value="PHARMA">Pharma — medicine</option>
              <option value="GENERAL">General — everything else</option>
            </select>
          </Field>
          {/* Removed rather than disabled for a general item: a greyed-out
              "Generic" still reads as something left unfilled. */}
          {!isGeneral && pick("pidGlobalGeneric", "Generic", masters.generics)}
          {pick("pidGlobalCompany", "Manufacturer", masters.companies)}
          {pick("pidGlobalCategory", "Category", masters.categories)}
          {pick("pidGlobalSubCategory", "Sub-category", subCategories, {
            disabled: !v.pidGlobalCategory,
            emptyLabel: v.pidGlobalCategory ? "—" : "Choose a category first",
          })}
          <Field label="Product code">
            <input value={v.productCode ?? ""} onChange={(e) => set("productCode", e.target.value)} />
          </Field>
          <Field label="Barcode">
            <input value={v.barcode ?? ""} onChange={(e) => set("barcode", e.target.value)} />
          </Field>
        </Grid>

        {/* 2 — Suggested pricing */}
        <Section n={2} icon={P.calc} title="Suggested Pricing"
                 sub="Where a pharmacy starts. Each one prices its own copy and the catalogue never changes it again." />
        <Grid min={200}>
          <Field label="Suggested MRP">
            <input type="number" min="0" step="0.01" value={v.suggestedMrp ?? ""}
                   onChange={(e) => set("suggestedMrp", e.target.value)} />
          </Field>
          <Field label="Suggested TP">
            <input type="number" min="0" step="0.01" value={v.suggestedTp ?? ""}
                   onChange={(e) => setTp(e.target.value)} />
          </Field>
          <Field label="IGTA" hint="VAT amount on the trade price, per piece. Copied to the pharmacy's product on import.">
            <input type="number" min="0" step="0.01" value={v.igta ?? ""}
                   onChange={(e) => setIgta(e.target.value)} />
          </Field>
          <Field label="Buy Price" hint={buyFollows ? "TP + IGTA, kept in step until you type your own." : "Your own figure — TP + IGTA is not applied."}>
            <input type="number" min="0" step="0.01" value={v.buyPrice ?? ""}
                   onChange={(e) => { setBuyFollows(e.target.value === ""); set("buyPrice", e.target.value); }} />
          </Field>
        </Grid>

        {/* 3 — Packaging */}
        <Section n={3} icon={P.box} title="Unit & Packaging"
                 sub="Each pharmacy gets its own unit, leaf/strip and box rows built from these." />
        <Grid min={200}>
          {pick("pidGlobalUnit", "Unit", masters.units)}
          {pick("pidGlobalLeaf", "Leaf/Strip", masters.leafs)}
          {pick("pidGlobalBox", "Box", boxes, {
            emptyLabel: v.pidGlobalLeaf ? "— (sold loose)" : "—",
          })}
        </Grid>
        <p style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 4, lineHeight: 1.6 }}>
          A box names the strip it is built from, so choosing one settles both numbers.
          A strip on its own is fine — not everything comes boxed.
        </p>

        {/* 4 — Picture */}
        <Section n={4} icon={P.image} title="Picture"
                 sub="JPG, PNG, WEBP or GIF up to 2 MB" />
        <div style={{ display: "flex", gap: 20, alignItems: "flex-start", flexWrap: "wrap" }}>
          <div style={{
            width: 132, height: 132, borderRadius: "var(--radius)", overflow: "hidden", flexShrink: 0,
            border: "1px dashed var(--line)", background: "var(--paper)",
            display: "flex", alignItems: "center", justifyContent: "center",
          }}>
            {shownImage
              ? <img src={shownImage} alt="Catalogue preview"
                     style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              : <div style={{ color: "var(--ink-soft)", textAlign: "center", fontSize: 11.5 }}>
                  <Ic path={P.image} size={22} />
                  <div style={{ marginTop: 6 }}>No image</div>
                </div>}
          </div>
          <div style={{ flex: "1 1 250px" }}>
            <input ref={fileRef} type="file" style={{ display: "none" }}
                   accept="image/jpeg,image/png,image/webp,image/gif" onChange={pickImage} />
            <div className="flex-gap" style={{ flexWrap: "wrap" }}>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => fileRef.current?.click()}>
                <Ic path={P.image} size={13} /> {shownImage ? "Replace image" : "Choose image"}
              </button>
              {shownImage && (
                <button type="button" className="btn btn-ghost btn-sm" onClick={clearImage}>
                  <Ic path={P.x} size={13} /> Remove
                </button>
              )}
            </div>
            <p style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 10, lineHeight: 1.6 }}>
              {isEdit
                ? "The picture uploads after the entry is saved, replacing the previous one."
                : "The picture uploads once the entry has been created."}
              {" "}Every pharmacy that imports this medicine afterwards gets its own copy of
              the file; pharmacies that already imported it keep what they have.
            </p>
          </div>
        </div>

        {/* 5 — Status */}
        <Section n={5} icon={P.power} title="Status" />
        <Grid min={200}>
          <Field label="Prescription only">
            <select value={v.prescriptionFlag ?? "N"}
                    onChange={(e) => set("prescriptionFlag", e.target.value)}>
              <option value="N">No</option>
              <option value="Y">Yes</option>
            </select>
          </Field>
        </Grid>

        <div className="reg-actions" style={{ marginTop: 26 }}>
          <button className="btn btn-ghost" onClick={cancel} disabled={busy}>Cancel</button>
          <button className="btn btn-primary" onClick={save} disabled={busy}>
            {busy ? "Saving…" : isEdit ? "Save changes" : "Add to catalogue"}
          </button>
        </div>
      </Card>
    </div>
  );
}
