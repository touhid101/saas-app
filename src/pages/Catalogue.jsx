/**
 * The shared medicine catalogue, and pushing it into pharmacies.
 *
 * <b>What this screen is not.</b> It is not a product list. A product belongs to
 * a pharmacy and has a price, a shelf and a supplier; these are descriptions a
 * pharmacy copies to make one. The distinction matters on screen because it is
 * what lets a Super Admin correct a spelling here without repricing three hundred
 * shelves — so the prices are labelled "suggested" and the import step says
 * plainly that it copies.
 *
 * <b>Import is safe to repeat.</b> Anything a pharmacy already holds is skipped,
 * and the result says how much of it did nothing, so nobody has to remember
 * whether they already ran it.
 */
import React, { useCallback, useEffect, useState } from "react";
import apiClient from "../lib/apiClient.js";
import subscriptionService from "../services/subscriptionService.js";
import { Card, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import CatalogueForm from "./CatalogueForm.jsx";
import { resolveImageUrl } from "../services/productMasterService.js";

/* Product Master's icon set, drawn the same way and at the same weights. The
   two screens list the same kind of thing; a reader who learns one should not
   have to learn the other. */
const Ic = ({ path, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

const P = {
  search:  ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  plus:    "M12 5v14M5 12h14",
  refresh: ["M21 12a9 9 0 1 1-3-6.7", "M21 3v6h-6"],
  edit:    ["M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"],
  archive: ["M3 3h18v5H3z", "M5 8v12a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8", "M10 12h4"],
  layers:  ["M12 2 2 7l10 5 10-5-10-5Z", "M2 12l10 5 10-5", "M2 17l10 5 10-5"],
  image:   ["M3 3h18v18H3z", "M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M21 15l-5-5L5 21"],
  factory: ["M3 21h18", "M4 21V10l6 4V10l6 4V7l4 3v11"],
  store:   ["M3 9 5 3h14l2 6", "M4 9v12h16V9", "M9 21v-6h6v6"],
  send:    ["M22 2 11 13", "M22 2l-7 20-4-9-9-4 20-7z"],
  x:       "M18 6L6 18M6 6l12 12",
};

const bdt = (n) => (n == null ? "—" : "৳" + Number(n).toLocaleString("en-IN", { maximumFractionDigits: 2 }));

/**
 * Catalogue thumbnail, with a coloured-initials fallback.
 *
 * <p>Mirrors Product Master's, deliberately: the two screens list the same kind
 * of thing and should read the same way. The fallback is not decoration — an
 * empty cell where a picture belongs makes a row look broken, and the column
 * keeps its width either way.
 */
const Thumb = ({ row, size = 34 }) => (
  <div style={{
    width: size, height: size, borderRadius: 8, overflow: "hidden", flexShrink: 0,
    background: "var(--teal)", display: "flex", alignItems: "center", justifyContent: "center",
  }}>
    {resolveImageUrl(row?.productImageUrl)
      ? <img src={resolveImageUrl(row.productImageUrl)} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
      : <span className="mono" style={{ color: "#fff", fontWeight: 700, fontSize: size * 0.33 }}>
          {(row?.productName || "?").slice(0, 2).toUpperCase()}
        </span>}
  </div>
);

const BLANK = {
  productName: "", strength: "", suggestedMrp: "", suggestedTp: "", igta: "", buyPrice: "",
  productCode: "", barcode: "", prescriptionFlag: "N",
  // Chosen from the catalogue's own lists, not typed. The server writes the
  // matching *Name onto the entry, which is what the import reads.
  pidGlobalCompany: "", pidGlobalGeneric: "", pidGlobalCategory: "",
  pidGlobalSubCategory: "", pidGlobalUnit: "",
  // Leaf and box, the same two masters a pharmacy has. Choosing a box settles
  // both numbers, because a box names the strip it is built from.
  pidGlobalLeaf: "", pidGlobalBox: "",
};

export default function Catalogue() {
  const addToast = useToast();
  const confirm = useConfirm();
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [companies, setCompanies] = useState([]);
  const [branches, setBranches] = useState([]);
  const [q, setQ] = useState("");
  const [company, setCompany] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [picked, setPicked] = useState(() => new Set());
  const [targets, setTargets] = useState(() => new Set());
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  // All six in one request: the form needs every one of them before it can
  // render, and six round trips is six chances to appear half-populated.
  const [masters, setMasters] = useState({
    companies: [], generics: [], categories: [], subCategories: [], units: [], leafs: [], boxes: [],
  });

  /* Only the sub-categories under the chosen category. Offering all of them
     invites picking one that belongs somewhere else, which the server refuses. */
  const load = useCallback(() => {
    setLoading(true);
    return apiClient
      .get("/api/admin/global-products", { q, company, page, size: 50 })
      .then((d) => { setRows(d?.content ?? d?.items ?? []); setTotal(d?.totalElements ?? 0); })
      .catch((e) => addToast(e?.message || "Could not load the catalogue.", "error"))
      .finally(() => setLoading(false));
  }, [q, company, page, addToast]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    apiClient.get("/api/admin/global-products/companies").then(setCompanies).catch(() => {});
    apiClient.get("/api/admin/global-masters")
      .then((d) => setMasters((m) => ({ ...m, ...(d ?? {}) })))
      .catch(() => addToast("Could not load the catalogue lists.", "error"));
    subscriptionService.getTenants().then(setBranches).catch(() => {});
  }, []);

  const toggle = (set, setter, id) => {
    const next = new Set(set);
    next.has(id) ? next.delete(id) : next.add(id);
    setter(next);
  };

  const allOnPage = rows.length > 0 && rows.every((r) => picked.has(r.id));
  const toggleAll = () => {
    const next = new Set(picked);
    if (allOnPage) rows.forEach((r) => next.delete(r.id));
    else rows.forEach((r) => next.add(r.id));
    setPicked(next);
  };

  const runImport = async () => {
    if (targets.size === 0) return addToast("Choose at least one pharmacy.", "warning");
    setBusy(true);
    try {
      const res = await apiClient.post("/api/admin/global-products/import", {
        branchIds: [...targets],
        // Empty means the whole catalogue, which is what "none ticked" should do:
        // somebody who ticked nothing and pressed Import meant all of it.
        productIds: [...picked],
      });
      const done = (res ?? []).reduce((n, r) => n + r.imported, 0);
      const held = (res ?? []).reduce((n, r) => n + r.alreadyHeld, 0);
      addToast(done === 0
        ? `Nothing new — those pharmacies already hold all ${held} of these.`
        : `${done} product${done === 1 ? "" : "s"} imported${held ? `, ${held} already held` : ""}.`,
        "success");
      setPicked(new Set());
    } catch (e) {
      addToast(e?.message || "Could not import.", "error");
    } finally { setBusy(false); }
  };

  const retire = async (row) => {
    const ok = await confirm({
      title: `Retire ${row.productName}?`,
      body: "It stops being offered for import. Pharmacies that already took a copy keep "
          + "theirs, with their own price and stock — nothing disappears from a shelf.",
      confirmLabel: "Retire",
      tone: "danger",
    });
    if (!ok) return;
    try {
      await apiClient.delete(`/api/admin/global-products/${row.id}`);
      addToast("Retired from the catalogue.", "success");
      await load();
    } catch (e) { addToast(e?.message || "Could not retire it.", "error"); }
  };

  const pages = Math.max(1, Math.ceil(total / 50));
  // Of the page in view, not the whole catalogue: the rows here are all this
  // screen has, and a total it cannot see would be invented.
  const withPicture = rows.filter((r) => r.productImageUrl).length;

  const kpis = [
    { label: "In the catalogue", value: total.toLocaleString(), tone: "teal",  icon: P.layers },
    { label: "With a picture",   value: withPicture,            tone: "sage",  icon: P.image },
    { label: "Manufacturers",    value: companies.length,       tone: "amber", icon: P.factory },
    { label: "Pharmacies to send to", value: branches.length,   tone: "clay",  icon: P.store },
  ];

 /* The form replaces the list rather than floating over it. A stepped form in a
     modal is a form you cannot see the top of once you scroll, which is exactly
     why Product Master does not use one either. */
  if (draft) {
    return (
      <CatalogueForm
        entry={draft.id ? draft : null}
        masters={masters}
        onDone={() => { setDraft(null); load(); }}
        onCancel={() => setDraft(null)}
      />
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          {/* Cased as the title bar cases it. The two sat one above the other
              reading "Medicine Catalogue" and "Medicine catalogue", which is
              the kind of detail that makes a screen look unfinished. */}
          <h1>Medicine Catalogue</h1>
          <div className="page-sub">
            The shared list · pharmacies import copies and price them themselves
          </div>
        </div>
        <div className="flex-gap">
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            <Ic path={P.refresh} size={13} /> {loading ? "Loading…" : "Refresh"}
          </button>
          <button className="btn btn-primary btn-sm" onClick={() => setDraft({ ...BLANK })}>
            <Ic path={P.plus} size={14} /> Add medicine
          </button>
        </div>
      </div>

      <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(168px, 1fr))" }}>
        {kpis.map((k) => (
          <div className="kpi-card" key={k.label} style={{ padding: "14px 16px" }}>
            <div className="kpi-top" style={{ marginBottom: 8 }}>
              <div className="kpi-icon"
                   style={{ width: 30, height: 30, background: `var(--${k.tone}-soft)`, color: `var(--${k.tone})` }}>
                <Ic path={k.icon} size={15} />
              </div>
            </div>
            <div className="kpi-value" style={{ fontSize: 21 }}>{k.value}</div>
            <div className="kpi-label" style={{ marginTop: 4 }}>{k.label}</div>
          </div>
        ))}
      </div>

      {/* Search and manufacturer, in a bordered bar of their own.
          They were bare controls on the page background: .filter-bar is only
          styled inside .report-shell, so on this screen the class did nothing
          and the row read as two stray inputs. */}
      <div className="cat-filters">
        <div className="search-box" style={{ flex: 2, minWidth: 240, maxWidth: 380 }}>
          <Ic path={P.search} size={14} />
          <input placeholder="Name, generic, manufacturer or code…" value={q}
                 aria-label="Search the catalogue"
                 onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          {q && (
            <button className="cat-clear" aria-label="Clear search" onClick={() => { setQ(""); setPage(0); }}>
              <Ic path={P.x} size={12} />
            </button>
          )}
        </div>
        <label className="cat-field">
          <span>Manufacturer</span>
          <select value={company} onChange={(e) => { setCompany(e.target.value); setPage(0); }}>
            <option value="">All manufacturers</option>
            {companies.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>
      </div>

      {/* What is selected, and how to stop selecting it. This was a sentence
          floating at the end of the filter row, where it read as a caption for
          the manufacturer box rather than as the state of the page. */}
      <div className="cat-selbar">
        <span className={picked.size ? "cat-selcount on" : "cat-selcount"}>
          {picked.size
            ? `${picked.size} medicine${picked.size === 1 ? "" : "s"} selected`
            : "Nothing selected"}
        </span>
        <span className="cat-selnote">
          {picked.size
            ? "Import sends only these."
            : `Import sends the whole catalogue — all ${total.toLocaleString()}.`}
        </span>
        {picked.size > 0 && (
          <button className="btn btn-ghost btn-sm" onClick={() => setPicked(new Set())}>
            Clear selection
          </button>
        )}
      </div>

      {/* The eyebrow said "Send to pharmacies" and the title said "Import",
          which is one thought printed twice. The eyebrow places the card, the
          title says what pressing the button does. */}
      <Card eyebrow="Catalogue › Import" title="Send copies to pharmacies">
        <div className="cat-import">
          <div className="cat-branches">
            {branches.map((b) => (
              <label key={b.branchId} className={`cat-branch ${targets.has(b.branchId) ? "on" : ""}`}>
                <input type="checkbox" checked={targets.has(b.branchId)}
                       onChange={() => toggle(targets, setTargets, b.branchId)} />
                <span>{b.branchName}</span>
                <Pill tone={b.tenantStatus === "ACTIVE" ? "sage" : "clay"}>{b.tenantStatus}</Pill>
              </label>
            ))}
          </div>
          <div className="cat-import-do">
            <p>
              Each pharmacy gets its own copy to price, stock and reorder. Anything they
              already hold is skipped, so running this twice is safe.
            </p>
            <button className="btn btn-primary" disabled={busy || targets.size === 0} onClick={runImport}>
              <Ic path={P.send} size={14} />{" "}
              {/* A disabled button reading "to 0 pharmacies" states the count of
                  a thing nobody chose. It should say what is missing instead. */}
              {busy ? "Importing…"
                : targets.size === 0 ? "Choose a pharmacy"
                : `Import ${(picked.size || total).toLocaleString()} to ${targets.size} pharmac${targets.size === 1 ? "y" : "ies"}`}
            </button>
          </div>
        </div>
      </Card>

      <Card pad={false}>
        <div style={{ padding: "14px 18px 0" }}>
          <div className="card-head">
            <div>
              <div className="card-eyebrow">Catalogue › Medicines</div>
              <div className="card-title">Every medicine pharmacies can import</div>
            </div>
            <Pill tone="teal">{total.toLocaleString()} entr{total === 1 ? "y" : "ies"}</Pill>
          </div>
        </div>

        <div className="table-scroll cat-table" style={{ padding: "0 18px 4px" }}>
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: 34 }}>
                  <input type="checkbox" checked={allOnPage} onChange={toggleAll} aria-label="Select page" />
                </th>
                <th style={{ width: 46 }}></th>
                <th>Medicine</th><th>Type</th><th>Generic</th><th>Manufacturer</th>
                <th>Category</th>
                <th className="num">Suggested MRP</th><th className="num">Suggested TP</th>
                <th className="num" title="VAT amount on the trade price, per piece">IGTA</th>
                <th className="num" title="Trade price with IGTA">Buy Price</th>
                <th>Unit</th><th>Packaging</th><th></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className={picked.has(r.id) ? "row-picked" : ""}>
                  <td>
                    <input type="checkbox" checked={picked.has(r.id)}
                           onChange={() => toggle(picked, setPicked, r.id)}
                           aria-label={`Select ${r.productName}`} />
                  </td>
                  <td><Thumb row={r} /></td>
                  <td>
                    {/* Name over strength, as Product Master stacks name over
                        generic. The strength was carried by the row and shown
                        nowhere, which made two dosages of one medicine read as
                        duplicates. */}
                    <div className="cat-name">
                      <span>{r.productName}</span>
                      {r.prescriptionFlag === "Y" && <Pill tone="amber">Rx</Pill>}
                    </div>
                    {/* Only when there is one. A second line reading "—", or an
                        internal code, costs every row its height and says
                        nothing a reader wanted. */}
                    {r.strength && <div className="cat-strength">{r.strength}</div>}
                  </td>
                  <td>
                    <span className={`type-pill ${r.productType === "GENERAL" ? "type-pill-general" : "type-pill-pharma"}`}>
                      {r.productType === "GENERAL" ? "General" : "Pharma"}
                    </span>
                  </td>
                  <td>{r.genericName ?? "—"}</td>
                  <td>{r.companyName ?? "—"}</td>
                  <td>
                    {r.categoryName ?? "—"}
                    {r.subCategoryName && (
                      <span className="cat-sub"> › {r.subCategoryName}</span>
                    )}
                  </td>
                  <td className="num mono">{bdt(r.suggestedMrp)}</td>
                  <td className="num mono">{bdt(r.suggestedTp)}</td>
                  <td className="num mono">{bdt(r.igta)}</td>
                  <td className="num mono">{bdt(r.buyPrice)}</td>
                  <td>{r.unitName ?? "—"}</td>
                  <td className="mono">
                    {/* Both numbers or neither: half a packaging description
                        describes nothing, and the import creates no box from it. */}
                    {r.pcsPerLeaf && r.leafPerBox
                      ? r.leafPerBox + " × " + r.pcsPerLeaf
                      : r.pcsPerLeaf ? "strip of " + r.pcsPerLeaf : "—"}
                  </td>
                  {/* Icon buttons, as every other list in the application uses.
                      Two words repeated down a page of fifty rows is a hundred
                      words competing with the data. */}
                  <td className="cat-row-actions">
                    <div>
                      <button className="icon-btn" title={`Edit ${r.productName}`}
                              onClick={() => setDraft({ ...BLANK, ...r,
                                          pidGlobalCompany: r.pidGlobalCompany ?? "",
                                          pidGlobalGeneric: r.pidGlobalGeneric ?? "",
                                          pidGlobalCategory: r.pidGlobalCategory ?? "",
                                          pidGlobalSubCategory: r.pidGlobalSubCategory ?? "",
                                          pidGlobalUnit: r.pidGlobalUnit ?? "",
                                          pidGlobalLeaf: r.pidGlobalLeaf ?? "",
                                          pidGlobalBox: r.pidGlobalBox ?? "" })}>
                        <Ic path={P.edit} size={14} />
                      </button>
                      <button className="icon-btn" title={`Retire ${r.productName}`} onClick={() => retire(r)}>
                        <Ic path={P.archive} size={14} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {loading && rows.length === 0 && (
                <tr><td colSpan={12} className="empty-state">Loading the catalogue…</td></tr>
              )}
              {rows.length === 0 && !loading && (
                <tr>
                  <td colSpan={12} className="empty-state">
                    {q || company ? "Nothing matches that." : "The catalogue is empty. Add a medicine to begin."}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {pages > 1 && (
          <div className="cat-pager">
            <button className="btn btn-ghost btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              Previous
            </button>
            <span>Page {page + 1} of {pages}</span>
            <button className="btn btn-ghost btn-sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>
              Next
            </button>
          </div>
        )}
      </Card>

    </div>
  );
}
