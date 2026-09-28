/**
 * The catalogue's master lists — manufacturers, generics, categories,
 * sub-categories, units and packaging.
 *
 * <b>These are not a pharmacy's master data.</b> Nothing here carries a branch.
 * They describe the shared catalogue, and importing a catalogue entry gives the
 * receiving pharmacy its own copies — so renaming something here corrects the
 * catalogue and changes nobody's shelf. The page says so, because the two look
 * alike and the difference is the whole point.
 *
 * <b>Why the page exists.</b> These used to be typed into the catalogue form as
 * free text, which let one manufacturer become three. A list cannot be misspelt.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";
import { Card, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";

/* Each list differs only in what a row is called and which fields it carries, so
   they are described once here rather than written out six times. */
const LISTS = [
  {
    key: "companies", label: "Manufacturers", singular: "Manufacturer",
    path: "companies",
    blurb: "Who makes the medicine — Square, Beximco, Incepta.",
    fields: [{ k: "name", label: "Name", required: true }],
  },
  {
    key: "generics", label: "Generics", singular: "Generic",
    path: "generics",
    blurb: "The active ingredient, such as Paracetamol.",
    fields: [{ k: "name", label: "Name", required: true },
             { k: "description", label: "Description" }],
  },
  {
    key: "categories", label: "Categories", singular: "Category",
    path: "categories",
    blurb: "How the catalogue groups medicines — Analgesic, Antacid.",
    fields: [{ k: "name", label: "Name", required: true }],
  },
  {
    key: "subCategories", label: "Sub-categories", singular: "Sub-category",
    path: "sub-categories",
    blurb: "Always beneath one category. Two categories may each have a “Tablet”.",
    fields: [{ k: "categoryId", label: "Category", required: true, from: "categories" },
             { k: "name", label: "Name", required: true }],
  },
  {
    key: "units", label: "Units", singular: "Unit",
    path: "units",
    blurb: "How it is sold — strip, box, bottle, vial.",
    fields: [{ k: "name", label: "Name", required: true }],
  },
  {
    key: "leafs", label: "Leaf/Strip", singular: "Leaf/Strip",
    path: "leafs",
    blurb: "A named strip and how many pieces it holds — the same thing a pharmacy’s Leaf/Strip master describes.",
    fields: [{ k: "name", label: "Leaf Type", required: true },
             { k: "noOfPcs", label: "PCS per Leaf", required: true, num: true }],
  },
  {
    key: "boxes", label: "Box", singular: "Box",
    path: "boxes",
    // The leaf is required: a box that does not name its strip cannot say how
    // many pieces it holds, which is the one thing the import needs it for.
    blurb: "A named box, the strip it is built from, and how many of them fit.",
    fields: [{ k: "name", label: "Box Title", required: true },
             { k: "leafId", label: "Leaf/Strip", required: true, from: "leafs" },
             { k: "noOfLeaf", label: "Leaves per Box", required: true, num: true }],
  },
];

export default function CatalogueMasters() {
  const addToast = useToast();
  const confirm = useConfirm();

  const [data, setData] = useState({
    companies: [], generics: [], categories: [], subCategories: [], units: [], leafs: [], boxes: [],
  });
  const [active, setActive] = useState("companies");
  const [draft, setDraft] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const list = useMemo(() => LISTS.find((l) => l.key === active), [active]);

  const load = useCallback(() => {
    setLoading(true);
    return apiClient
      .get("/api/admin/global-masters")
      .then((d) => setData((prev) => ({ ...prev, ...(d ?? {}) })))
      .catch((e) => addToast(e?.message || "Could not load the lists.", "error"))
      .finally(() => setLoading(false));
  }, [addToast]);

  useEffect(() => { load(); }, [load]);

  const rows = data[active] ?? [];

  /* Sub-category rows show which category they sit under; without it the list
     reads as a flat set of names with no way to tell two "Tablet"s apart. */
  const categoryName = useCallback(
    (id) => (data.categories ?? []).find((c) => c.id === id)?.name ?? "—",
    [data.categories]);

  /* A box row says which strip it is built from; the id alone tells nobody
     anything. */
  const leafName = useCallback(
    (id) => (data.leafs ?? []).find((l) => l.id === id)?.name ?? "—",
    [data.leafs]);

  const save = async () => {
    for (const f of list.fields) {
      if (f.required && (draft[f.k] === "" || draft[f.k] == null)) {
        return addToast(`${f.label} is required.`, "warning");
      }
    }
    setBusy(true);
    try {
      const body = {};
      for (const f of list.fields) {
        body[f.k] = f.num || f.from ? Number(draft[f.k]) : draft[f.k];
      }
      if (draft.id) await apiClient.put(`/api/admin/global-masters/${list.path}/${draft.id}`, body);
      else await apiClient.post(`/api/admin/global-masters/${list.path}`, body);
      addToast(draft.id ? `${list.singular} updated.` : `${list.singular} added.`, "success");
      setDraft(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not save.", "error");
    } finally { setBusy(false); }
  };

  const retire = async (row) => {
    const ok = await confirm({
      title: `Retire ${row.name}?`,
      body: "It stops being offered on new catalogue entries. Pharmacies keep everything they already imported.",
      confirmLabel: "Retire it",
      cancelLabel: "Keep it",
    });
    if (!ok) return;
    setBusy(true);
    try {
      await apiClient.delete(`/api/admin/global-masters/${list.key}/${row.id}`);
      addToast(`${row.name} retired.`, "success");
      await load();
    } catch (e) {
      // The server refuses while catalogue entries still use it, and says how
      // many — which is the useful part, so it is shown as-is.
      addToast(e?.message || "Could not retire it.", "error");
    } finally { setBusy(false); }
  };

  const startNew = () => {
    const blank = { id: null };
    for (const f of list.fields) blank[f.k] = "";
    setDraft(blank);
  };

  return (
    <>
      <div className="page-head">
        <h1 className="page-title">Catalogue Lists</h1>
        <div className="page-sub">
          The manufacturers, generics, categories, units and packaging the medicine
          catalogue is built from.
        </div>
      </div>

      <Card>
        <p className="cat-modal-note" style={{ margin: 0 }}>
          These belong to the catalogue, not to any pharmacy. Importing a medicine gives
          the receiving pharmacy its own copies — so correcting a name here fixes the
          catalogue and changes nobody’s shelf.
        </p>
      </Card>

      <Card>
        <div className="gm-tabs">
          {LISTS.map((l) => (
            <button
              key={l.key}
              className={`gm-tab ${l.key === active ? "gm-tab-on" : ""}`}
              onClick={() => { setActive(l.key); setDraft(null); }}
            >
              {l.label}
              <span className="gm-count">{(data[l.key] ?? []).length}</span>
            </button>
          ))}
        </div>

        <div className="gm-bar">
          <span className="gm-blurb">{list.blurb}</span>
          <button className="btn btn-primary btn-sm" onClick={startNew} disabled={busy}>
            + New {list.singular}
          </button>
        </div>

        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                {active === "subCategories" && <th>Category</th>}
                {active === "generics" && <th>Description</th>}
 {active === "leafs" && <th className="num">PCS per Leaf</th>}
                               {active === "boxes" && <th>Leaf/Strip</th>}
                               {active === "boxes" && <th className="num">Leaves per Box</th>}
                <th className="num"></th>
              </tr>
            </thead>
            <tbody>
              {loading && (
                <tr><td colSpan={5} className="muted">Loading…</td></tr>
              )}
              {!loading && rows.length === 0 && (
                <tr><td colSpan={5} className="muted">
                  Nothing in this list yet. Add the first one.
                </td></tr>
              )}
              {!loading && rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  {active === "subCategories" && <td>{categoryName(r.categoryId)}</td>}
                  {active === "generics" && <td>{r.description || "—"}</td>}
 {active === "leafs" && <td className="num mono">{r.noOfPcs}</td>}
                                   {active === "boxes" && <td>{leafName(r.leafId)}</td>}
                                   {active === "boxes" && <td className="num mono">{r.noOfLeaf}</td>}
                  <td className="num">
                    <button className="btn btn-ghost btn-sm" disabled={busy}
                            onClick={() => setDraft({ ...r })}>Edit</button>
                    <button className="btn btn-ghost btn-sm" disabled={busy}
                            onClick={() => retire(r)}>Retire</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {draft && (
        <div className="modal-backdrop" onClick={() => setDraft(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h2>{draft.id ? `Edit ${list.singular}` : `New ${list.singular}`}</h2>
              <button className="icon-btn" aria-label="Close" onClick={() => setDraft(null)}>×</button>
            </div>

            <div className="reg-fields">
              {list.fields.map((f) => (
                <label key={f.k}>
                  <span>{f.label}{f.required ? " *" : ""}</span>
                  {f.from ? (
                    <select className="cat-select" value={draft[f.k] ?? ""}
                            onChange={(e) => setDraft({ ...draft, [f.k]: e.target.value })}>
                      <option value="">—</option>
                      {(data[f.from] ?? []).map((o) => (
                        <option key={o.id} value={o.id}>{o.name}</option>
                      ))}
                    </select>
                  ) : (
                    <input
                      type={f.num ? "number" : "text"}
                      min={f.num ? 1 : undefined}
                      value={draft[f.k] ?? ""}
                      onChange={(e) => setDraft({ ...draft, [f.k]: e.target.value })}
                    />
                  )}
                </label>
              ))}
            </div>

            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setDraft(null)} disabled={busy}>Cancel</button>
              <button className="btn btn-primary" onClick={save} disabled={busy}>
                {draft.id ? "Save changes" : `Add ${list.singular}`}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
