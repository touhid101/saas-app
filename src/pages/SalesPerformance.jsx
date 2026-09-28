/**
 * Sales Performance.
 *
 * ── What this replaces ────────────────────────────────────────────────────
 *
 * The previous version made no API calls. It built eight salespeople, their
 * monthly targets, their actuals, their commission and six months of trend from
 * a seeded random number generator in mockData.js:
 *
 *     target: int(rng, 400000, 900000),
 *     actual: round2(target * (0.7 + rng() * 0.5)),
 *     commission: 0.025,
 *
 * None of those people work here and none of those figures described this
 * business. It also filtered by "region", a dimension no sale records.
 *
 * ── How it works now ──────────────────────────────────────────────────────
 *
 * Everything is counted for the signed-in user's branch, one month at a time.
 * Attribution is the user who raised each invoice, which is the only record of
 * who made a sale — PID_EMPLOYEE is null on every sale in the database.
 *
 * Targets are stored and set by someone with the Edit right. A person without
 * one shows actuals and a blank target rather than a target of zero, which
 * every seller beats.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card, Pill } from "../components/UI.jsx";
import { moneyFull as money } from "../utils/format.js";
import { usePermission } from "../lib/usePermission.js";
import { useToast } from "../hooks/useToast.js";
import { downloadCSV } from "../utils/csv.js";
import svc, { thisMonth } from "../services/salesPerformanceService.js";

const Ic = ({ d, size = 15 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {(Array.isArray(d) ? d : [d]).map((p, i) => <path key={i} d={p} />)}
  </svg>
);

/** 2026-08-23 → 23 Aug. */
function fmtDay(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

const pctTone = (pct) =>
  pct == null ? "grey" : pct >= 100 ? "sage" : pct >= 75 ? "amber" : "clay";

/* ── Achievement bar ─────────────────────────────────────────────────────── */

function Achievement({ pct }) {
  if (pct == null) {
    return <span style={{ fontSize: 11.5, color: "var(--ink-soft)" }}>No target</span>;
  }
  const tone = pct >= 100 ? "var(--sage)" : pct >= 75 ? "var(--amber)" : "var(--clay)";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8, minWidth: 120 }}>
      <div style={{ flex: 1, height: 7, background: "var(--paper-deep)", borderRadius: 999, overflow: "hidden" }}>
        <div style={{ width: `${Math.min(pct, 100)}%`, height: "100%", background: tone, borderRadius: 999, transition: "width .35s ease" }} />
      </div>
      <span className="mono" style={{ fontSize: 11.5, fontWeight: 700, minWidth: 42, textAlign: "right", color: tone }}>
        {Number(pct).toFixed(0)}%
      </span>
    </div>
  );
}

/* ── Six-month trend, actual against target ──────────────────────────────── */

function Trend({ points }) {
  const max = Math.max(
    1,
    ...points.map(p => Math.max(Number(p.billed || 0), Number(p.target || 0)))
  );

  return (
    <div style={{ display: "flex", alignItems: "flex-end", gap: 10, height: 160 }}>
      {points.map(p => {
        const billed = Number(p.billed || 0);
        const target = p.target == null ? null : Number(p.target);
        const met = target != null && billed >= target;
        return (
          <div key={p.month} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", height: "100%" }}>
            <div style={{ flex: 1, width: "100%", display: "flex", alignItems: "flex-end", justifyContent: "center", position: "relative" }}>
              {/* The target sits as a line across the bar rather than beside it:
                  the question is whether the bar reached it, and two adjacent
                  bars make that a comparison instead of a reading. */}
              {target != null && (
                <div title={`Target ${money(target)}`}
                  style={{
                    position: "absolute", left: "10%", right: "10%",
                    bottom: `${(target / max) * 100}%`,
                    borderTop: "2px dashed var(--ink-soft)", opacity: .75,
                  }} />
              )}
              <div
                title={`${p.label}: ${money(billed)} from ${p.invoices} invoice${p.invoices === 1 ? "" : "s"}`}
                style={{
                  width: "62%",
                  height: `${Math.max(2, (billed / max) * 100)}%`,
                  background: target == null
                    ? "linear-gradient(180deg, var(--teal), #2E6A62)"
                    : met ? "linear-gradient(180deg, var(--sage), #4C7A5E)"
                          : "linear-gradient(180deg, var(--amber), #8F5A1E)",
                  borderRadius: "5px 5px 2px 2px",
                  transition: "height .35s ease",
                }} />
            </div>
            <div style={{ fontSize: 10, color: "var(--ink-soft)", marginTop: 7, fontFamily: "'IBM Plex Mono', monospace" }}>
              {p.label}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/* ── Setting a target ────────────────────────────────────────────────────── */

function TargetEditor({ row, month, onSaved, onCancel }) {
  const addToast = useToast();
  const [amount, setAmount] = useState(row.target == null ? "" : String(row.target));
  const [pct, setPct] = useState(row.commissionPct == null ? "" : String(row.commissionPct));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const save = async () => {
    setBusy(true); setErr("");
    try {
      const fresh = await svc.setTarget({
        userId: row.userId, month, targetAmount: amount, commissionPct: pct,
      });
      addToast(amount === "" || Number(amount) === 0 ? "Target cleared" : "Target saved");
      onSaved(fresh);
    } catch (e) {
      setErr(e?.message || "Could not save the target.");
    } finally {
      setBusy(false);
    }
  };

  const field = {
    padding: "6px 9px", borderRadius: 7, border: "1px solid var(--line)",
    background: "var(--paper)", color: "var(--ink)", fontSize: 13, width: "100%",
    fontFamily: "'IBM Plex Mono', monospace",
  };

  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 12, alignItems: "flex-end", padding: "4px 0" }}>
      <label style={{ fontSize: 11, color: "var(--ink-soft)", flex: "1 1 150px", minWidth: 130 }}>
        Monthly target (৳)
        <input type="number" min="0" step="100" value={amount} autoFocus
          onChange={e => setAmount(e.target.value)}
          onKeyDown={e => { if (e.key === "Enter") save(); if (e.key === "Escape") onCancel(); }}
          placeholder="0 to clear"
          style={{ ...field, marginTop: 3 }} />
      </label>
      <label style={{ fontSize: 11, color: "var(--ink-soft)", flex: "0 1 120px", minWidth: 110 }}>
        Commission %
        <input type="number" min="0" max="100" step="0.1" value={pct}
          onChange={e => setPct(e.target.value)}
          onKeyDown={e => { if (e.key === "Enter") save(); if (e.key === "Escape") onCancel(); }}
          placeholder="0"
          style={{ ...field, marginTop: 3 }} />
      </label>
      <div style={{ display: "flex", gap: 8 }}>
        <button className="btn btn-primary btn-sm" onClick={save} disabled={busy}>
          {busy ? "Saving…" : "Save"}
        </button>
        <button className="btn btn-ghost btn-sm" onClick={onCancel} disabled={busy}>Cancel</button>
      </div>
      {err && <div style={{ fontSize: 12, color: "var(--clay)", flexBasis: "100%" }} role="alert">{err}</div>}
      <div style={{ fontSize: 11.5, color: "var(--ink-soft)", flexBasis: "100%" }}>
        Commission is calculated on what was billed. Leave the target at 0 to remove it.
      </div>
    </div>
  );
}

/* ── Page ────────────────────────────────────────────────────────────────── */

const SORTS = {
  billed:      { label: "Billed",      get: r => Number(r.billed || 0) },
  achievement: { label: "Achievement", get: r => (r.achievementPct == null ? -1 : Number(r.achievementPct)) },
  invoices:    { label: "Invoices",    get: r => Number(r.invoices || 0) },
  outstanding: { label: "Outstanding", get: r => Number(r.outstanding || 0) },
  name:        { label: "Name",        get: r => (r.displayName || "").toLowerCase() },
};

export default function SalesPerformance() {
  const perm = usePermission("salesperformance");

  const [month, setMonth] = useState(thisMonth());
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [q, setQ] = useState("");
  const [sortBy, setSortBy] = useState("billed");
  const [editing, setEditing] = useState(null);   // userId whose target is open
  const [expanded, setExpanded] = useState(null); // person whose detail is open

  const load = useCallback(() => {
    setLoading(true);
    return svc.getPerformance(month)
      .then(d => { setData(d); setError(null); })
      .catch(e => setError(e?.message || "Could not load sales performance."))
      .finally(() => setLoading(false));
  }, [month]);

  useEffect(() => { load(); }, [load]);

  const rows = useMemo(() => {
    const all = data?.rows ?? [];
    const term = q.trim().toLowerCase();
    const found = term
      ? all.filter(r =>
          (r.displayName || "").toLowerCase().includes(term) ||
          (r.person || "").toLowerCase().includes(term) ||
          (r.roleName || "").toLowerCase().includes(term))
      : all;
    const get = SORTS[sortBy].get;
    return [...found].sort((a, b) => {
      const x = get(a), y = get(b);
      return typeof x === "string" ? x.localeCompare(y) : y - x;
    });
  }, [data, q, sortBy]);

  const totals = data?.totals;
  const canEdit = !!data?.canSetTargets && perm.canEdit !== false;

  const exportCSV = () => {
    const headers = ["Person", "Role", "Invoices", "Customers", "Billed", "Collected",
                     "Outstanding", "Discount", "Avg Invoice", "Target", "Achievement %",
                     "Commission %", "Commission", "Last Sale"];
    const out = rows.map(r => ({
      Person: r.displayName, Role: r.roleName ?? "Not a login",
      Invoices: r.invoices, Customers: r.customers,
      Billed: r.billed, Collected: r.collected, Outstanding: r.outstanding,
      Discount: r.discount, "Avg Invoice": r.averageInvoice,
      Target: r.target ?? "", "Achievement %": r.achievementPct ?? "",
      "Commission %": r.commissionPct ?? "", Commission: r.commission ?? "",
      "Last Sale": r.lastSale ?? "",
    }));
    downloadCSV(`sales-performance-${month}.csv`, out, headers);
  };

  const kpis = totals ? [
    { label: "Billed",      value: money(totals.billed),      tone: "var(--teal)",  note: `${totals.invoices.toLocaleString()} invoices` },
    { label: "Collected",   value: money(totals.collected),   tone: "var(--sage)",  note: "Capped at the amount billed" },
    { label: "Outstanding", value: money(totals.outstanding), tone: Number(totals.outstanding) > 0 ? "var(--clay)" : "var(--ink-soft)", note: "Still owed on these invoices" },
    { label: "Team achievement",
      value: totals.achievementPct == null ? "—" : `${Number(totals.achievementPct).toFixed(0)}%`,
      tone: totals.achievementPct == null ? "var(--ink-soft)"
           : Number(totals.achievementPct) >= 100 ? "var(--sage)" : "var(--amber)",
      note: totals.achievementPct == null ? "No targets set"
           : `${totals.withTarget} of ${totals.people} have a target` },
    { label: "Commission",  value: money(totals.commission),  tone: "var(--amber-deep)", note: "On billed, where a rate is set" },
    { label: "People selling", value: String(totals.people),  tone: "var(--ink)", note: "Raised at least one invoice" },
  ] : [];

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Sales Performance</h1>
          <div className="page-sub" style={{ maxWidth: 640 }}>
            Counted from invoices raised in this branch, credited to whoever raised them.
          </div>
        </div>
        <div className="flex-gap">
          <label style={{ fontSize: 11, color: "var(--ink-soft)" }}>Month
            <input type="month" value={month} onChange={e => setMonth(e.target.value)}
              style={{ display: "block", marginTop: 3, padding: "7px 10px", borderRadius: 8,
                       border: "1px solid var(--line)", background: "var(--paper)",
                       color: "var(--ink)", fontSize: 13 }} />
          </label>
          {perm.canExport && (
            <button className="btn btn-primary btn-sm" onClick={exportCSV} disabled={!rows.length}>
              Export CSV
            </button>
          )}
        </div>
      </div>

      {error ? (
        <Card>
          <div className="empty-state" role="alert">
            {error}
            <div style={{ marginTop: 12 }}>
              <button className="btn btn-ghost btn-sm" onClick={load}>Retry</button>
            </div>
          </div>
        </Card>
      ) : loading && !data ? (
        <Card><div className="empty-state">Loading…</div></Card>
      ) : (
        <>
          <div className="kpi-grid" style={{ gridTemplateColumns: "repeat(auto-fit,minmax(190px,1fr))", marginBottom: 18 }}>
            {kpis.map(k => (
              <div key={k.label} className="kpi-card">
                <div className="kpi-value" style={{ color: k.tone, fontSize: 21 }}>{k.value}</div>
                <div className="kpi-label">{k.label}</div>
                <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 4 }}>{k.note}</div>
              </div>
            ))}
          </div>

          <Card
            eyebrow="Six months to date"
            title="Billed each month"
            right={
              <div style={{ fontSize: 11.5, display: "flex", gap: 14, color: "var(--ink-soft)", flexWrap: "wrap" }}>
                <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
                  <span style={{ width: 10, height: 10, background: "var(--sage)", borderRadius: 2 }} /> Target met
                </span>
                <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
                  <span style={{ width: 10, height: 10, background: "var(--amber)", borderRadius: 2 }} /> Below target
                </span>
                <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
                  <span style={{ width: 14, borderTop: "2px dashed var(--ink-soft)" }} /> Target
                </span>
              </div>
            }
          >
            <Trend points={data?.trend ?? []} />
          </Card>

          <div style={{ height: 16 }} />

          <Card
            eyebrow="This month"
            title="By person"
            right={
              <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                <input
                  placeholder="Find a person…"
                  value={q}
                  onChange={e => setQ(e.target.value)}
                  style={{ padding: "6px 10px", borderRadius: 8, border: "1px solid var(--line)",
                           background: "var(--paper)", color: "var(--ink)", fontSize: 13, width: 170 }}
                />
                <select value={sortBy} onChange={e => setSortBy(e.target.value)}
                  style={{ padding: "6px 10px", borderRadius: 8, border: "1px solid var(--line)",
                           background: "var(--paper)", color: "var(--ink)", fontSize: 13 }}>
                  {Object.entries(SORTS).map(([k, s]) => (
                    <option key={k} value={k}>Sort by {s.label}</option>
                  ))}
                </select>
              </div>
            }
          >
            {!rows.length ? (
              <div className="empty-state">
                {q.trim()
                  ? `Nobody matches "${q.trim()}".`
                  : "No invoices were raised in this branch that month."}
              </div>
            ) : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Person</th>
                      <th style={{ textAlign: "right" }}>Invoices</th>
                      <th style={{ textAlign: "right" }}>Customers</th>
                      <th style={{ textAlign: "right" }}>Billed</th>
                      <th style={{ textAlign: "right" }}>Outstanding</th>
                      <th style={{ textAlign: "right" }}>Target</th>
                      <th style={{ minWidth: 130 }}>Achievement</th>
                      <th style={{ textAlign: "right" }}>Commission</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map(r => {
                      const open = expanded === r.person;
                      // Both null means "nobody is editing" and "this row has no
                      // login" — which matched, so the target editor sat open under
                      // every seed and import row.
                      const isEditing = editing != null && editing === r.userId;
                      return (
                        <React.Fragment key={r.person}>
                          <tr
                            style={{ cursor: "pointer", background: open ? "var(--paper)" : undefined }}
                            onClick={() => setExpanded(open ? null : r.person)}
                          >
                            <td>
                              <div style={{ fontWeight: 650 }}>{r.displayName}</div>
                              <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
                                {r.roleName ?? "Not a login"}
                              </div>
                            </td>
                            <td className="mono" style={{ textAlign: "right" }}>{r.invoices.toLocaleString()}</td>
                            <td className="mono" style={{ textAlign: "right" }}>{r.customers.toLocaleString()}</td>
                            <td className="mono" style={{ textAlign: "right", fontWeight: 650 }}>{money(r.billed)}</td>
                            <td className="mono" style={{ textAlign: "right", color: Number(r.outstanding) > 0 ? "var(--clay)" : undefined }}>
                              {money(r.outstanding)}
                            </td>
                            <td className="mono" style={{ textAlign: "right", color: r.target == null ? "var(--ink-soft)" : undefined }}>
                              {r.target == null ? "—" : money(r.target)}
                            </td>
                            <td><Achievement pct={r.achievementPct} /></td>
                            <td className="mono" style={{ textAlign: "right" }}>
                              {r.commission == null ? "—" : money(r.commission)}
                              {r.commissionPct != null && (
                                <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
                                  {Number(r.commissionPct)}%
                                </div>
                              )}
                            </td>
                            <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                              {canEdit && r.targetable && (
                                <button
                                  className="btn btn-ghost btn-sm"
                                  onClick={e => { e.stopPropagation(); setEditing(isEditing ? null : r.userId); setExpanded(r.person); }}
                                >
                                  {r.target == null ? "Set target" : "Edit"}
                                </button>
                              )}
                              {!r.targetable && (
                                <span title="These invoices were not raised by a login, so there is nobody to hold to a target."
                                      style={{ fontSize: 11, color: "var(--ink-soft)" }}>
                                  no login
                                </span>
                              )}
                            </td>
                          </tr>

                          {(open || isEditing) && (
                            <tr>
                              <td colSpan={9} style={{ background: "var(--paper)", paddingTop: 4, paddingBottom: 12 }}>
                                {isEditing ? (
                                  <TargetEditor
                                    row={r}
                                    month={month}
                                    onCancel={() => setEditing(null)}
                                    onSaved={fresh => { setEditing(null); if (fresh) setData(fresh); }}
                                  />
                                ) : (
                                  <div style={{ display: "flex", flexWrap: "wrap", gap: 26, fontSize: 12.5 }}>
                                    {[
                                      ["Collected", money(r.collected)],
                                      ["Average invoice", money(r.averageInvoice)],
                                      ["Discount given", money(r.discount)],
                                      ["Last sale", fmtDay(r.lastSale)],
                                      ["Signed in as", r.person],
                                    ].map(([label, value]) => (
                                      <div key={label}>
                                        <div style={{ fontSize: 10.5, textTransform: "uppercase", letterSpacing: ".07em", color: "var(--ink-soft)" }}>
                                          {label}
                                        </div>
                                        <div className="mono" style={{ fontWeight: 650, marginTop: 2 }}>{value}</div>
                                      </div>
                                    ))}
                                  </div>
                                )}
                              </td>
                            </tr>
                          )}
                        </React.Fragment>
                      );
                    })}
                  </tbody>
                  {totals && (
                    <tfoot>
                      <tr style={{ borderTop: "2px solid var(--ink)", fontWeight: 800 }}>
                        <td>Total</td>
                        <td className="mono" style={{ textAlign: "right" }}>{totals.invoices.toLocaleString()}</td>
                        <td />
                        <td className="mono" style={{ textAlign: "right" }}>{money(totals.billed)}</td>
                        <td className="mono" style={{ textAlign: "right" }}>{money(totals.outstanding)}</td>
                        <td className="mono" style={{ textAlign: "right" }}>
                          {totals.target == null ? "—" : money(totals.target)}
                        </td>
                        <td>
                          {totals.achievementPct == null
                            ? <span style={{ fontSize: 11.5, color: "var(--ink-soft)", fontWeight: 500 }}>No targets</span>
                            : <Pill tone={pctTone(totals.achievementPct)}>
                                {Number(totals.achievementPct).toFixed(0)}%
                              </Pill>}
                        </td>
                        <td className="mono" style={{ textAlign: "right" }}>{money(totals.commission)}</td>
                        <td />
                      </tr>
                    </tfoot>
                  )}
                </table>
              </div>
            )}

            {/* Said once, plainly, because a performance table invites the wrong
                reading if nobody says what it is counting. */}
            <div style={{ marginTop: 14, paddingTop: 12, borderTop: "1px solid var(--line)",
                          fontSize: 11.5, color: "var(--ink-soft)", lineHeight: 1.6 }}>
              <div style={{ display: "flex", gap: 7, alignItems: "flex-start" }}>
                <Ic d={["M12 16v-4", "M12 8h.01", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"]} size={14} />
                <span>
                  Credited to the user who raised each invoice — nothing else records who made a
                  sale. Confirmed invoices onward, merge documents excluded, so these totals match
                  the Sales Register. Achievement compares only the billing of people who have a
                  target against the sum of those targets.
                  {totals?.withTarget === 0 && " No targets are set for this month yet."}
                </span>
              </div>
            </div>
          </Card>
        </>
      )}
    </div>
  );
}
