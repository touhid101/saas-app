/**
 * AccKit — the few pieces the Accounts screens share on top of HrKit.
 *
 * HrKit is the application's page kit (page frame, KPI tiles, tables, drawers,
 * steppers, status pills). These helpers add what a ledger needs: money cells
 * that line up, a period picker, a branch picker for platform operators, and
 * a small async hook so every screen loads, errors and retries the same way.
 */
import React, { useCallback, useEffect, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { HrButton, HrTabBar, HrTabs, humanError, Filter, P } from "../../components/HrKit.jsx";
import { amount, drcr, isoMonthStart, isoToday } from "../../utils/financial.js";

export const ACC_CRUMB = { label: "Accounts" };

/** Loads once and on demand; keeps data, busy, error together. */
export function useAsync(fn, deps = []) {
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState(true);
  const [err, setErr] = useState(null);
  const load = useCallback(async () => {
    setBusy(true); setErr(null);
    try { setData(await fn()); } catch (e) { setErr(humanError(e, "Could not load this screen.")); }
    finally { setBusy(false); }
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [load]);
  return { data, busy, err, reload: load, setData };
}

/** Right-aligned money in tables. */
export const Money = ({ v, paren }) => <span className="acc-money">{amount(v, { paren })}</span>;
export const DrCr = ({ v }) => <span className="acc-money acc-drcr">{drcr(v)}</span>;

/** From / to dates, defaulting to this month. */
export function DateRange({ from, to, onChange }) {
  return (
    <>
      <Filter label="From"><input type="date" value={from} onChange={(e) => onChange({ from: e.target.value, to })} /></Filter>
      <Filter label="To"><input type="date" value={to} onChange={(e) => onChange({ from, to: e.target.value })} /></Filter>
    </>
  );
}

export function useDateRange(initialFrom = isoMonthStart(), initialTo = isoToday()) {
  const [range, setRange] = useState({ from: initialFrom, to: initialTo });
  return [range, setRange];
}

/** Platform operators may pick a branch; everyone else sees their own. */
export function BranchPicker({ value, onChange, allowAll = false }) {
  const { branches, currentUser } = useApp();
  const platform = currentUser?.role === "ROLE01" || currentUser?.roleCode === "ROLE01" || currentUser?.isSuperAdmin;
  if (!platform || !branches?.length) return null;
  return (
    <Filter label="Branch">
      <select value={value ?? ""} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
        {allowAll && <option value="">All branches</option>}
        {branches.map((b) => <option key={b.id ?? b.pidBranch} value={b.id ?? b.pidBranch}>{b.name ?? b.branchName}</option>)}
      </select>
    </Filter>
  );
}

/** A labelled figure, as on a voucher foot or a day sheet. */
export function Figure({ label, value, tone, hint, big }) {
  return (
    <div className={`acc-figure${big ? " is-big" : ""}${tone ? ` is-${tone}` : ""}`}>
      <span className="acc-figure-label">{label}</span>
      <span className="acc-figure-value">{value}</span>
      {hint && <span className="acc-figure-hint">{hint}</span>}
    </div>
  );
}

export function ReasonPrompt({ title, hint, label = "Reason", confirmLabel = "Confirm", onConfirm, onClose, busy }) {
  const [reason, setReason] = useState("");
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" style={{ maxWidth: 480 }}>
        <div className="modal-head"><h3>{title}</h3></div>
        <div className="modal-body">
          {hint && <p className="acc-hint">{hint}</p>}
          <div className="field"><label>{label}</label>
            <textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus placeholder="Say why — it is recorded with the entry" />
          </div>
        </div>
        <div className="modal-foot">
          <HrButton onClick={onClose} disabled={busy}>Cancel</HrButton>
          <HrButton variant="primary" busy={busy} disabled={!reason.trim()} onClick={() => onConfirm(reason.trim())}>{confirmLabel}</HrButton>
        </div>
      </div>
    </div>
  );
}

/** Source of a journal, as a short badge: "SALES · SALE" */
export const Source = ({ module, type }) => (
  <span className="acc-source"><b>{module || "—"}</b>{type ? <span> · {type}</span> : null}</span>
);

/**
 * The Accounts icon set — the same 24-grid line drawings as the sidebar, so a
 * page header, a tab and a menu item all say the same thing about a screen.
 */
export const AI = {
  dashboard: ["M4 3h16v18H4z", "M8 3v18", "M11 8h6", "M11 12h6", "M11 16h3"],
  journal:   ["M2 4h9a2 2 0 0 1 2 2v14a1 1 0 0 0-1-1H2z", "M22 4h-9a2 2 0 0 0-2 2v14a1 1 0 0 1 1-1h10z"],
  ledger:    ["M4 3h16v18H4z", "M4 8h16", "M12 8v13", "M8 12h2", "M8 16h2", "M15 12h3", "M15 16h3"],
  coa:       ["M6 3h6v4H6z", "M4 12h6v4H4z", "M14 12h6v4h-6z", "M14 19h6v2h-6z", "M9 7v3", "M6 10h11", "M7 10v2", "M17 10v2", "M17 16v3"],
  parties:   ["M7 7a3 3 0 1 0 0 .01", "M17 7a3 3 0 1 0 0 .01", "M2 20a5 5 0 0 1 10 0", "M12 20a5 5 0 0 1 10 0", "M9 13h6", "M13 11l2 2-2 2"],
  bank:      ["M3 9l9-5 9 5H3z", "M5 9v8", "M10 9v8", "M14 9v8", "M19 9v8", "M3 21h18", "M12 3v2"],
  till:      ["M3 8h18v4H3z", "M5 12v8h14v-8", "M9 16h6", "M12 4v4", "M8 4h8"],
  investors: ["M9 8a3 3 0 1 0 0 .01", "M3 21a6 6 0 0 1 12 0", "M14 13l3-3 2 2 3-4", "M22 8v3h-3"],
  profit:    ["M21 12A9 9 0 1 1 12 3v9z", "M14 2.5A9 9 0 0 1 21.5 10H14z"],
  settings:  ["M3 5h18v16H3z", "M3 10h18", "M8 3v4", "M16 3v4", "M10 15h4v4h-4z", "M11 15v-1a1 1 0 0 1 2 0v1"],
  reports:   ["M5 3h11l3 3v15H5z", "M16 3v3h3", "M8 9h8", "M8 13h8", "M8 17h5", "M14 17h2"],
  reconcile: ["M12 3v18", "M4 8h16", "M4 8l-2 6a4 4 0 0 0 8 0l-2-6", "M20 8l-2 6a4 4 0 0 0 8 0l-2-6", "M8 21h8"],
  // Smaller ideas the tiles and tabs use.
  wallet:    ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z", "M16 12h.01", "M3 10h11"],
  phone:     ["M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2"],
  coins:     ["M12 12a8 3 0 1 0 0 .01", "M4 12v4c0 1.7 3.6 3 8 3s8-1.3 8-3v-4", "M4 8c0 1.7 3.6 3 8 3s8-1.3 8-3-3.6-3-8-3-8 1.3-8 3z"],
  truck:     ["M1 7h12v9H1z", "M13 10h5l3 3v3h-8z", "M5 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M17 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"],
  box:       ["M21 8l-9-5-9 5 9 5 9-5z", "M3 8v8l9 5 9-5V8", "M12 13v8"],
  receipt:   ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z", "M9 7h6", "M9 11h6", "M9 15h3"],
  trendUp:   ["M3 17l6-6 4 4 8-8", "M14 7h7v7"],
  trendDown: ["M3 7l6 6 4-4 8 8", "M14 17h7v-7"],
  scale:     ["M12 3v18", "M4 8h16", "M4 8l-2 6a4 4 0 0 0 8 0l-2-6", "M20 8l-2 6a4 4 0 0 0 8 0l-2-6"],
  tag:       ["M20 12l-8 8-9-9V4h7l10 8z", "M7.5 7.5h.01"],
  percent:   ["M19 5L5 19", "M6.5 9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z", "M17.5 20a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z"],
  clock:     ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M12 6v6l4 2"],
  check:     ["M20 6L9 17l-5-5"],
  alert:     ["M12 9v4", "M12 17h.01", "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"],
  play:      ["M5 3l14 9-14 9V3z"],
  list:      ["M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01"],
  history:   ["M3 12a9 9 0 1 0 3-6.7", "M3 3v5h5", "M12 7v5l3 2"],
  layers:    ["M12 2l10 5-10 5L2 7l10-5z", "M2 12l10 5 10-5", "M2 17l10 5 10-5"],
  calendar:  P.calendar, search: P.search, download: P.download, plus: P.plus, edit: P.edit, lock: P.lock, undo: P.undo, x: P.x, eye: P.eye, printer: P.printer,
};
export const ICON = { ledger: AI.ledger, cash: AI.wallet, warn: AI.alert };

/**
 * Deep links between Accounts screens without a router change: the sending
 * screen leaves an intent for the target page ("open the ledger on the till
 * account"), the target reads it once on mount and clears it. Survives a
 * reload only until it is consumed, which is the point.
 */
const INTENT_KEY = "acc.intent";
export function sendIntent(page, params) {
  try { sessionStorage.setItem(INTENT_KEY, JSON.stringify({ page, ...params })); } catch { /* private mode: the link still opens the page */ }
}
let lastTaken = null;   // React StrictMode runs a state initialiser twice; the second run must see the same intent.
export function takeIntent(page) {
  try {
    if (lastTaken && lastTaken.page === page && Date.now() - lastTaken.at < 1500) return lastTaken.intent;
    const raw = sessionStorage.getItem(INTENT_KEY);
    if (!raw) return null;
    const i = JSON.parse(raw);
    if (i.page !== page) return null;
    sessionStorage.removeItem(INTENT_KEY);
    lastTaken = { page, at: Date.now(), intent: i };
    return i;
  } catch { return null; }
}

/** Change against a named period: signed, coloured by direction × whether up is good. */
export function Delta({ now, before, upIsGood = true, label = "previous period" }) {
  const a = Number(now) || 0, b = Number(before) || 0;
  if (!b && !a) return <span className="acc-delta is-flat">no change</span>;
  if (!b) return <span className="acc-delta is-flat">new · vs {label}</span>;
  const pct = ((a - b) / Math.abs(b)) * 100;
  const up = pct > 0.05, down = pct < -0.05;
  const good = up ? upIsGood : down ? !upIsGood : null;
  return (
    <span className={`acc-delta${good === true ? " is-good" : good === false ? " is-bad" : " is-flat"}`} title={`${amount(b)} in the ${label}`}>
      {up ? "▲" : down ? "▼" : "•"} {Math.abs(pct).toFixed(1)}% <span className="acc-delta-label">vs {label}</span>
    </span>
  );
}

/**
 * Sections of a page: the kit's top strip on anything wider than a phone and
 * its bottom bar on a phone — the HR pages' pairing, in one element. Inside a
 * drawer (`inline`) a phone gets a select instead, because a fixed bottom bar
 * would sit over the drawer.
 */
export function AccTabs({ tabs, active, onChange, inline = false }) {
  return (
    <>
      <HrTabBar tabs={tabs} active={active} onChange={onChange} />
      {inline
        ? <label className="acc-tabs-phone"><span className="sr-only">Section</span>
            <select value={active} onChange={(e) => onChange(e.target.value)}>{tabs.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}</select></label>
        : <HrTabs tabs={tabs} active={active} onChange={onChange} />}
    </>
  );
}
