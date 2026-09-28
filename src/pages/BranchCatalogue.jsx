/**
 * The medicine catalogue, as a pharmacy sees it.
 *
 * <b>The same catalogue, pulled instead of pushed.</b> The operator's screen
 * (Catalogue.jsx) maintains these entries and sends them to chosen pharmacies.
 * This one lets a pharmacy help itself: browse the shared catalogue, tick what
 * it stocks, and copy those descriptions into its own product list.
 *
 * <b>Two things are deliberately missing.</b> There is no Add, Edit or Retire —
 * the catalogue is maintained centrally, and a pharmacy correcting a spelling
 * here would change it for every pharmacy. And there is no pharmacy picker: the
 * import goes to the branch you are signed in to, which the server decides and
 * this screen only reports.
 *
 * <b>What "import everything" means.</b> Everything that matches the search, on
 * every page — not the fifty rows on screen. The selection and the filter both
 * travel to the server, which runs the same query the list is drawn from, so the
 * number on the button is the number of rows written. It did not use to be:
 * ticking the header box selected one page and imported fifty, and importing
 * with a manufacturer filtered offered a hundred and copied three thousand.
 *
 * <b>Import is safe to repeat.</b> Anything already held is skipped, and the
 * result says how much of it did nothing, so nobody has to remember whether
 * they already ran it.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import { resolveImageUrl } from "../services/productMasterService.js";
import svc from "../services/branchCatalogueService.js";

const bdt = (n) => (n == null ? "—" : "৳" + Number(n).toLocaleString("en-IN", { maximumFractionDigits: 2 }));
const num = (n) => Number(n ?? 0).toLocaleString();

/** How many rows a page holds. 200 is the server's ceiling. */
const SIZES = [25, 50, 100, 200];

const Ic = ({ d, size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {(Array.isArray(d) ? d : [d]).map((p, i) => <path key={i} d={p} />)}
  </svg>
);

const I = {
  search: ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  x:      "M18 6L6 18M6 6l12 12",
  down:   ["M12 3v12", "M7 12l5 5 5-5", "M4 21h16"],
  check:  "M20 6 9 17l-5-5",
  store:  ["M3 9l1.5-5h15L21 9", "M3 9h18v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z", "M8 21v-6h8v6"],
  info:   ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 11v5", "M12 8h.01"],
  lock:   ["M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4"],
};

/**
 * Catalogue thumbnail, with a coloured-initials fallback.
 *
 * Mirrors the operator's screen and Product Master, deliberately: the three
 * list the same kind of thing and should read the same way. An empty cell where
 * a picture belongs makes a row look broken.
 */
function Thumb({ row, size = 34 }) {
  const url = resolveImageUrl(row?.productImageUrl);
  /* A stored filename can outlive its file — the upload directory moves, or the
     row is restored from a backup the folder was not. Without this the tile
     rendered as a broken-image glyph rather than the initials it falls back to
     when there is no picture at all. */
  const [broken, setBroken] = useState(false);

  return (
    <span className="bc-thumb" style={{ width: size, height: size }}>
      {url && !broken
        ? <img src={url} alt="" onError={() => setBroken(true)} />
        : <span className="mono" style={{ fontSize: size * 0.33 }}>
            {(row?.productName || "?").slice(0, 2).toUpperCase()}
          </span>}
    </span>
  );
}

/**
 * Page numbers with an ellipsis, plus the range in words.
 *
 * The old pager was Previous / Next only. On three thousand medicines at fifty
 * a page that is sixty-five clicks to reach the end, and no way to tell where
 * you are beyond a number with nothing to compare it to.
 */
function Pager({ page, pages, total, size, onPage, onSize }) {
  const from = total === 0 ? 0 : page * size + 1;
  const to   = Math.min(total, (page + 1) * size);

  /* First, last, and a window around where you are. The gaps are rendered as
     text rather than buttons, because a button that goes nowhere is worse than
     a mark that says "there is more here". */
  const numbers = useMemo(() => {
    const out = [];
    const push = (n) => { if (!out.includes(n) && n >= 0 && n < pages) out.push(n); };
    push(0);
    for (let n = page - 1; n <= page + 1; n++) push(n);
    push(pages - 1);
    out.sort((a, b) => a - b);
    const withGaps = [];
    out.forEach((n, i) => {
      if (i > 0 && n - out[i - 1] > 1) withGaps.push("gap" + n);
      withGaps.push(n);
    });
    return withGaps;
  }, [page, pages]);

  return (
    <div className="bc-pager">
      <div className="bc-pager-count">
        {total === 0 ? "No medicines" : <>Showing <b>{num(from)}–{num(to)}</b> of <b>{num(total)}</b></>}
      </div>

      <div className="bc-pager-nav">
        <button className="bc-page-btn" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        {numbers.map((n) => (typeof n === "string"
          ? <span key={n} className="bc-page-gap">…</span>
          : <button key={n} className={`bc-page-btn bc-page-n ${n === page ? "on" : ""}`}
                    aria-current={n === page ? "page" : undefined}
                    onClick={() => onPage(n)}>{n + 1}</button>))}
        <button className="bc-page-btn" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>

      <label className="bc-pager-size">
        Rows
        <select value={size} onChange={(e) => onSize(Number(e.target.value))}>
          {SIZES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </label>
    </div>
  );
}

export default function BranchCatalogue() {
  const addToast = useToast();
  const confirm = useConfirm();

  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [companies, setCompanies] = useState([]);
  const [q, setQ] = useState("");
  const [company, setCompany] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [picked, setPicked] = useState(() => new Set());
  const [busy, setBusy] = useState(false);
  // Where an import would land. From the server, not from the signed-in user:
  // the name shown here and the branch actually written must be one answer.
  const [dest, setDest] = useState(null);
  const [lastRun, setLastRun] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return svc.search({ q, company, page, size })
      .then((d) => {
        setRows(d?.content ?? d?.items ?? []);
        setTotal(d?.totalElements ?? 0);
        setError(null);
      })
      .catch((e) => setError(e?.message || "Could not load the catalogue."))
      .finally(() => setLoading(false));
  }, [q, company, page, size]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    svc.companies().then(setCompanies);
    svc.target().then(setDest).catch(() => {});
  }, []);

  /* Changing what you are looking at changes what "everything" means, so a
     selection made against the old filter must not survive into the new one —
     it would import rows that are no longer on screen. */
  const refilter = (fn) => { fn(); setPage(0); setPicked(new Set()); };

  const toggle = (id) => {
    const next = new Set(picked);
    next.has(id) ? next.delete(id) : next.add(id);
    setPicked(next);
  };

  const allOnPage = rows.length > 0 && rows.every((r) => picked.has(r.id));
  const toggleAll = () => {
    const next = new Set(picked);
    if (allOnPage) rows.forEach((r) => next.delete(r.id));
    else rows.forEach((r) => next.add(r.id));
    setPicked(next);
  };

  /* The number that will actually be written. Nothing ticked means everything
     matching, which is what the server does with an empty list and this filter. */
  const willImport = picked.size || total;
  const everything = picked.size === 0;
  /* Worth offering only when the page is a part of something bigger — on a
     single-page result "select all matching" and "select the page" are the same
     act, and offering both invites the reader to look for a difference. */
  const offerAll = picked.size > 0 && picked.size < total;

  const runImport = async () => {
    /* Importing the whole catalogue is a different act from importing eleven
       ticked rows, and it is the one somebody does by accident. It gets a
       confirmation; a deliberate selection does not. */
    if (everything) {
      const scope = company || q
        ? `all ${num(total)} medicines matching this search`
        : `all ${num(total)} medicines`;
      const ok = await confirm({
        title: `Import ${scope}?`,
        body: "Nothing is ticked, so every match — on every page, not just this one — will be "
            + "copied into your product list. Anything you already hold is skipped. You can "
            + "price and stock them afterwards.",
        confirmLabel: "Import everything",
      });
      if (!ok) return;
    }

    setBusy(true);
    try {
      const res = await svc.importHere([...picked], { q, company });
      setLastRun(res);
      addToast(
        res?.imported === 0
          ? `Nothing new — you already hold all ${num(res?.alreadyHeld)} of these.`
          : `${num(res.imported)} added to your product list${res.alreadyHeld ? `, ${num(res.alreadyHeld)} already held` : ""}.`,
        res?.imported === 0 ? "warning" : "success");
      setPicked(new Set());
    } catch (e) {
      addToast(e?.message || "Could not import.", "error");
    } finally {
      setBusy(false);
    }
  };

  const pages = Math.max(1, Math.ceil(total / size));
  const canImport = dest?.canImport !== false;
  const filtered = Boolean(q || company);

  return (
    <div className="page bc">
      <header className="bc-head">
        <div className="bc-head-text">
          <h1>Medicine Catalogue</h1>
          <p>
            Copy what you stock into your own product list and price it yourself.
            The catalogue itself never changes.
          </p>
        </div>
        <div className="bc-head-side">
          <span className="bc-dest" title="Imports go here and nowhere else">
            <Ic d={I.store} size={13} />
            {dest?.branchName ?? "Your pharmacy"}
          </span>
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            {loading ? "Loading…" : "Refresh"}
          </button>
        </div>
      </header>

      {/* ── what you are looking at, and what Import would do to it ── */}
      <div className="bc-bar">
        <div className="bc-search">
          <Ic d={I.search} size={14} />
          <input value={q} placeholder="Name, generic, manufacturer or code…"
                 aria-label="Search the catalogue"
                 onChange={(e) => refilter(() => setQ(e.target.value))} />
          {q && (
            <button className="bc-clear" aria-label="Clear search"
                    onClick={() => refilter(() => setQ(""))}>
              <Ic d={I.x} size={12} />
            </button>
          )}
        </div>

        <select className="bc-select" value={company} aria-label="Filter by manufacturer"
                onChange={(e) => refilter(() => setCompany(e.target.value))}>
          <option value="">All manufacturers ({companies.length})</option>
          {companies.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>

        <div className="bc-bar-spacer" />

        <div className="bc-scope">
          <b>{num(willImport)}</b>
          <span>{everything ? (filtered ? "matching" : "in the catalogue") : "selected"}</span>
        </div>

        <button className="btn btn-primary bc-import"
                disabled={busy || !canImport || total === 0}
                onClick={runImport}>
          <Ic d={I.down} size={14} />
          {busy ? "Importing…" : `Import ${num(willImport)}`}
        </button>
      </div>

      {/* One line, and only when it has something to say. Set as a strip rather
          than loose text: it is the sentence that explains the button above it,
          and prose floating between two cards reads as something left over. */}
      <div className={`bc-note ${!canImport ? "warn" : everything ? "all" : "some"}`}>
        <span className="bc-note-mark" aria-hidden="true">
          <Ic d={!canImport ? I.lock : everything ? I.info : I.check} size={14} />
        </span>
        <p>
        {!canImport ? (
          <span className="bc-note-warn">
            You can browse the catalogue but not import from it — importing adds products, so it
            needs the Create right on Product Master.
          </span>
        ) : !everything ? (
          /* One link, not two. Clearing the selection and choosing "everything
             matching" are the same act here — an empty selection is what tells
             the server to take the whole result — so offering both as separate
             choices would invite the reader to look for a difference. */
          <>
            <b>{num(picked.size)}</b> ticked{allOnPage && offerAll && " — the whole of this page"},
            and only those will be imported.{" "}
            {offerAll && (
              <button className="bc-link" onClick={() => setPicked(new Set())}>
                Select all {num(total)} matching instead
              </button>
            )}
          </>
        ) : (
          <>
            Nothing ticked, so Import copies <b>every one of the {num(total)}</b>
            {filtered ? " matching this search" : " medicines"} — all pages, not just this one.
            Anything you already hold is skipped, so running it twice is safe.
          </>
        )}
        </p>
        {lastRun && (
          <span className="bc-last">
            Last import: <b>{num(lastRun.imported)}</b> added
            {lastRun.alreadyHeld > 0 && <>, <b>{num(lastRun.alreadyHeld)}</b> already held</>}.
          </span>
        )}
      </div>

      <Card pad={false}>
        <div className="bc-card-head">
          <div>
            <div className="bc-eyebrow">Catalogue</div>
            <h2>Medicines you can add</h2>
          </div>
          <Pill tone="teal">{num(total)} {total === 1 ? "entry" : "entries"}</Pill>
        </div>

        <div className="bc-table-wrap">
          <table className="data-table bc-table">
            <thead>
              <tr>
                <th style={{ width: 34 }}>
                  <input type="checkbox" checked={allOnPage} onChange={toggleAll}
                         aria-label="Select every row on this page"
                         title="Select every row on this page" />
                </th>
                <th style={{ width: 46 }}></th>
                <th>Medicine</th><th>Generic</th><th>Manufacturer</th>
                <th>Category</th>
                <th className="num">Suggested MRP</th><th className="num">Suggested TP</th>
                <th className="num" title="VAT amount on the trade price, per piece — copied to your product on import">IGTA</th>
                <th className="num" title="Trade price with IGTA — copied to your product on import">Buy Price</th>
                <th>Unit</th><th>Packaging</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className={picked.has(r.id) ? "row-picked" : ""}>
                  <td>
                    <input type="checkbox" checked={picked.has(r.id)}
                           onChange={() => toggle(r.id)}
                           aria-label={`Select ${r.productName}`} />
                  </td>
                  <td><Thumb row={r} /></td>
                  <td>
                    <span className="bc-name">{r.productName}</span>
                    {r.strength && <span className="bc-strength">{r.strength}</span>}
                    {r.prescriptionFlag === "Y" && <Pill tone="amber">Rx</Pill>}
                  </td>
                  <td>{r.genericName ?? "—"}</td>
                  <td>{r.companyName ?? "—"}</td>
                  <td>
                    {r.categoryName ?? "—"}
                    {r.subCategoryName && <span className="cat-sub"> › {r.subCategoryName}</span>}
                  </td>
                  <td className="num mono">{bdt(r.suggestedMrp)}</td>
                  <td className="num mono">{bdt(r.suggestedTp)}</td>
                  <td className="num mono">{bdt(r.igta)}</td>
                  <td className="num mono">{bdt(r.buyPrice)}</td>
                  <td>{r.unitName ?? "—"}</td>
                  <td className="mono">
                    {r.pcsPerLeaf && r.leafPerBox
                      ? r.leafPerBox + " × " + r.pcsPerLeaf
                      : r.pcsPerLeaf ? "strip of " + r.pcsPerLeaf : "—"}
                  </td>
                </tr>
              ))}

              {loading && rows.length === 0 && (
                <tr><td colSpan={10} className="empty-state">Loading the catalogue…</td></tr>
              )}
              {rows.length === 0 && !loading && !error && (
                <tr><td colSpan={10} className="empty-state">No medicine matches that.</td></tr>
              )}
              {error && (
                <tr>
                  <td colSpan={10} className="empty-state" role="alert">
                    {error}
                    <div style={{ marginTop: 12 }}>
                      <button className="btn btn-ghost btn-sm" onClick={load}>Retry</button>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <Pager page={page} pages={pages} total={total} size={size}
               onPage={(p) => { setPage(p); }}
               onSize={(s) => { setSize(s); setPage(0); }} />
      </Card>
    </div>
  );
}
