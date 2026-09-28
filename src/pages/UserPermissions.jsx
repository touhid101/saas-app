/**
 * What one person at a pharmacy may do.
 *
 * <b>Nothing here is locked.</b> The role and what the pharmacy has decide the
 * default — the answer for anyone nobody has looked at individually. This screen
 * is where somebody does look, and their decision is the answer.
 *
 * <b>Why no greyed-out boxes.</b> There were, and they were the whole problem: a
 * pharmacy on the Basic template could never give one senior person Approve, and
 * the tooltip blamed their role, which granted it plainly. A box you cannot tick
 * has to be right every time or it is a wall in front of the person best placed
 * to judge. The default is a good guess; it is not a better judge than the
 * administrator reading the screen.
 *
 * <b>Unticking is not the same as "same as the pharmacy".</b> The screen marks
 * which rights are exceptions and which way they go, because a right that merely
 * looks off tells you nothing about whether it will follow the default when the
 * default changes.
 *
 * <b>The one thing this screen cannot do</b> is hand out a module the pharmacy
 * does not have. That boundary is commercial, and it is not a person's to move.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";
import uploadUrl from "../lib/uploadUrl.js";
import { Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { viewRulePatch } from "../lib/permissionRules.js";

const RIGHTS = [
  ["canView",    "View"],
  ["canCreate",  "Create"],
  ["canEdit",    "Edit"],
  ["canDelete",  "Delete"],
  ["canApprove", "Approve"],
  ["canExport",  "Export"],
  ["canPrint",   "Print"],
];

/**
 * What one cell is, in one word.
 *
 * The four states are answers to four different questions, and a checkbox that
 * only says on or off answers none of them: whether it can be changed, whether
 * somebody changed it, and which way.
 */
export function cellState(cell) {
  if (!cell) return "default";
  const on = cell.effective === 1;
  // `fallback` is what role and plan would have given. Derived when it is
  // missing, so a server that has not been restarted since it was added does
  // not paint every ticked box as an exception.
  const def = (cell.fallback ?? Math.min(cell.role ?? 0, cell.plan ?? 0)) === 1;
  if (on === def) return "default";
  return on ? "raised" : "lowered";
}

const STATE_HINT = {
  default: "Following the default for this pharmacy.",
  raised:  "Granted to this person — more than the default.",
  lowered: "Withheld from this person — less than the default.",
};

/**
 * A checkbox that can also say "some".
 *
 * <p>`indeterminate` is not an attribute — it can only be set on the DOM node —
 * so React cannot express it in JSX and it has to be written in a ref callback.
 * Without it a row with six of seven rights on rendered as an empty box, which
 * reads as "nothing is granted here" when very nearly everything is.
 */
function TriCheckbox({ checked, indeterminate, ...rest }) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (ref.current) ref.current.indeterminate = !checked && !!indeterminate;
  }, [checked, indeterminate]);
  return <input ref={ref} type="checkbox" checked={checked} {...rest} />;
}

/**
 * @param pathFor  optional — where this person's permissions live, given their
 *                 id. Defaults to the platform console's address, so the
 *                 Pharmacies page is unaffected. The branch's own RBAC page
 *                 passes its endpoint instead, which carries no branch in the
 *                 URL because the server takes it from the session. One dialog,
 *                 two callers: the rules on screen cannot drift between them.
 */
export default function UserPermissions({ branchId, user, actingHeaders, pathFor, onClose }) {
  const addToast = useToast();
  const [data, setData] = useState(null);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [q, setQ] = useState("");
  /* A picture that will not load falls back to the initial, in state rather
     than by hiding the <img>: hiding it leaves the circle empty, which is worse
     than the letter it replaced. A stored avatar can outlive its file — the
     upload directory moves, or the row is restored from a backup the folder was
     not — so this is a case to expect, not an impossible one. */
  const [avatarBroken, setAvatarBroken] = useState(false);

  const userId = user?.pidUser ?? user?.id;
  const path = pathFor
    ? pathFor(userId)
    : `/api/admin/onboarding/branches/${branchId}/users/${userId}/permissions`;

  const load = useCallback(() => {
    setLoading(true);
    return apiClient
      .get(path, null, actingHeaders)
      .then((d) => {
        setData(d);
        setRows((d?.modules ?? []).map((m) => {
          const row = {
            moduleId: m.moduleId, moduleCode: m.moduleCode,
            moduleName: m.moduleName, parentId: m.parentId, parentName: m.parentName,
          };
          for (const [k] of RIGHTS) row[k] = m[k];
          return row;
        }));
      })
      .catch((e) => addToast(e?.message || "Could not load permissions.", "error"))
      .finally(() => setLoading(false));
  }, [branchId, userId, actingHeaders, addToast]);

  useEffect(() => { load(); }, [load]);

  /* ── counts and filtering ─────────────────────────────────────────────── */

  const exceptions = useMemo(
    () => rows.reduce((n, r) => n + RIGHTS.filter(([k]) => r[k]?.override != null).length, 0),
    [rows]);

  const raised = useMemo(
    () => rows.reduce((n, r) => n + RIGHTS.filter(([k]) => cellState(r[k]) === "raised").length, 0),
    [rows]);

  const visible = useMemo(() => {
    const term = q.trim().toLowerCase();
    if (!term) return rows;
    return rows.filter((r) =>
      (r.moduleName || "").toLowerCase().includes(term) ||
      (r.moduleCode || "").toLowerCase().includes(term));
  }, [rows, q]);

  /* Headings come from the parent name the server sends, not from finding the
     parent among the rows: the parent is a container, and a container is
     deliberately not a row here — there is no page behind it to permit. */
  const grouped = useMemo(() => {
    const groups = new Map();
    for (const r of visible) {
      const key = r.parentName ? r.parentId : "__none";
      if (!groups.has(key)) {
        groups.set(key, { label: key === "__none" ? null : r.parentName, rows: [] });
      }
      groups.get(key).rows.push(r);
    }
    /* Ungrouped pages first — Dashboard and the like are top-level, and burying
       them under the last heading would say they belong to it. */
    return [...groups.entries()]
      .sort((a, b) => (a[0] === "__none" ? -1 : b[0] === "__none" ? 1 : 0))
      .map(([, g]) => g);
  }, [visible]);

  /* ── writing a cell ───────────────────────────────────────────────────── */

  /** What role and plan would have given, when the server did not spell it out. */
  const defaultOf = (c) => c?.fallback ?? Math.min(c?.role ?? 0, c?.plan ?? 0);

  /** Write a {right: boolean} patch into one row, keeping override in step. */
  const applyPatch = (row, patch) => {
    const next = { ...row };
    for (const [k, on] of Object.entries(patch)) {
      const c = row[k];
      if (!c) continue;                       // this editor does not carry that right
      const v = on ? 1 : 0;
      // Null means "keep following the default", so a value equal to the
      // default is stored as null rather than as a decision that will not
      // move when the pharmacy's own setting does.
      next[k] = { ...c, effective: v, override: v === defaultOf(c) ? null : v };
    }
    return next;
  };

  const toggle = (moduleId, right) => {
    setRows((prev) => prev.map((r) => {
      if (r.moduleId !== moduleId) return r;
      const isOn = (k) => r[k]?.effective === 1;
      // Nothing is refused here. The role and the plan decide the default; an
      // administrator looking at one person decides the answer.
      return applyPatch(r, viewRulePatch(right, !isOn(right), isOn));
    }));
  };

  /** Every right on one module, on or off together. */
  const toggleRow = (moduleId) => {
    setRows((prev) => prev.map((r) => {
      if (r.moduleId !== moduleId) return r;
      const present = RIGHTS.filter(([k]) => r[k]);
      if (present.length === 0) return r;
      const allOn = present.every(([k]) => r[k].effective === 1);
      const patch = {};
      for (const [k] of present) patch[k] = !allOn;
      return applyPatch(r, patch);
    }));
  };

  /** One right, across every module currently listed. */
  const toggleColumn = (right) => {
    const visibleIds = new Set(visible.map((r) => r.moduleId));
    const settable = visible.filter((r) => r[right]);
    if (settable.length === 0) return;
    const allOn = settable.every((r) => r[right].effective === 1);

    setRows((prev) => prev.map((r) => {
      if (!visibleIds.has(r.moduleId) || !r[right]) return r;
      const isOn = (k) => r[k]?.effective === 1;
      return applyPatch(r, viewRulePatch(right, !allOn, isOn));
    }));
  };

  /** Put one module back to whatever the pharmacy's own setting says. */
  const resetRow = (moduleId) => {
    setRows((prev) => prev.map((r) => {
      if (r.moduleId !== moduleId) return r;
      const next = { ...r };
      for (const [k] of RIGHTS) {
        if (next[k]) next[k] = { ...next[k], effective: defaultOf(next[k]), override: null };
      }
      return next;
    }));
  };

  const followDefaults = () => {
    setRows((prev) => prev.map((r) => {
      const next = { ...r };
      for (const [k] of RIGHTS) {
        if (next[k]) next[k] = { ...next[k], effective: defaultOf(next[k]), override: null };
      }
      return next;
    }));
  };


  const save = async () => {
    setBusy(true);
    try {
      const payload = rows.map((r) => {
        const out = { moduleId: r.moduleId };
        // The effective value is sent, and the server decides what counts as an
        // exception by comparing it with the role and the plan — so the two can
        // never drift apart through a client that computed it differently.
        for (const [k] of RIGHTS) out[k] = r[k]?.effective ?? 0;
        return out;
      });
      const kept = await apiClient.put(
        path,
        { modules: payload }, actingHeaders);
      addToast(kept === 0
        ? `${data?.fullName || data?.userName} now follows the pharmacy defaults exactly.`
        : `Saved — ${kept} module(s) differ from the defaults.`, "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not save permissions.", "error");
    } finally { setBusy(false); }
  };

  const columnLabel = (right, label) => {
    const settable = visible.filter((r) => r[right]);
    const allOn = settable.length > 0 && settable.every((r) => r[right].effective === 1);
    return (
      <th key={right} className="num pg-col"
          onClick={() => !busy && toggleColumn(right)}
          title={`Turn ${label} on or off for every module listed`}>
        <span className={allOn ? "pg-col-on" : undefined}>{label}</span>
      </th>
    );
  };

  /* The same disc, name and username as the row this modal was opened from, so
     the screen that follows a click looks like the thing that was clicked. */
  const displayName = data?.fullName || data?.userName || user?.userName || "—";
  const displayUser = data?.userName || user?.userName || user?.username;
  /* Stored paths are relative to the backend, which is a different origin in
     development — uploadUrl is what makes them resolve in both. */
  const avatar = uploadUrl(user?.avatarUrl ?? data?.avatarUrl);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal modal-wide td-modal" onClick={(e) => e.stopPropagation()}>
        <div className="td-head">
          <div className="td-person">
            {/* The person's actual picture, as the list that opened this shows
                it. The initial stays as the fallback — and remains the whole
                answer for anyone who has never uploaded one.

                The URL comes from the row rather than the permissions payload:
                the endpoint returns rights, not a profile, and both callers
                already hand over the user they were looking at. */}
            <span className="td-avatar" aria-hidden="true">
              {avatar && !avatarBroken
                ? <img src={avatar} alt="" onError={() => setAvatarBroken(true)} />
                : (displayName.trim().charAt(0).toUpperCase() || "?")}
            </span>
            <span className="td-person-text">
              <span className="td-person-name">{displayName}</span>
              <span className="td-sub">
                {data?.roleName ?? "—"}
                {displayUser && <> · <span className="mono">{displayUser}</span></>}
              </span>
            </span>
          </div>
          <button className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>
        </div>

        {loading ? (
          <div className="empty-state">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="empty-state">
            This pharmacy has no modules, so there is nothing to set here.
          </div>
        ) : (
          <>
            <div className="pg-toolbar">
              <input
                className="pg-search" type="search" value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder={`Search ${rows.length} modules…`}
                aria-label="Filter modules"
              />
              {/* Counts beside the search, as on the other two tabs — the
                  count describes the whole list, the search narrows what is
                  under it, and seeing both at once stops one being read as the
                  other. The legend follows, because it explains the marks the
                  counts are counting. */}
              <div className="td-summary">
                {exceptions > 0
                  ? <Pill tone="amber">{exceptions} exception{exceptions === 1 ? "" : "s"}</Pill>
                  : <Pill tone="sage">follows the pharmacy</Pill>}
                {raised > 0 && <Pill tone="sage">{raised} above default</Pill>}
                <div className="pg-legend">
                  <span><i className="pg-key pg-key-default" /> default</span>
                  <span><i className="pg-key pg-key-raised" /> granted here</span>
                  <span><i className="pg-key pg-key-lowered" /> withheld here</span>
                </div>
              </div>
            </div>

            {visible.length === 0 ? (
              <div className="empty-state">No module matches &ldquo;{q}&rdquo;.</div>
            ) : (
              <div className="table-scroll pg-scroll">
                <table className="data-table pg-table">
                  {/* Fixed widths for the tick columns. Auto-sizing gave the
                      module name most of the table and spread nine columns of
                      checkboxes across what was left, so a box and its heading
                      could sit an inch apart. */}
                  <colgroup>
                    <col className="pg-c-mod" />
                    <col className="pg-c-all" />
                    {RIGHTS.map(([k]) => <col key={k} className="pg-c-right" />)}
                    <col className="pg-c-reset" />
                  </colgroup>
                  <thead>
                    <tr>
                      <th>Module</th>
                      <th className="num pg-sep">All</th>
                      {RIGHTS.map(([k, label]) => columnLabel(k, label))}
                      <th className="num" />
                    </tr>
                  </thead>
                  <tbody>
                    {grouped.map((g, gi) => (
                      <React.Fragment key={g.label ?? `g${gi}`}>
                        {g.label && (
                          <tr className="pg-group">
                            <td colSpan={RIGHTS.length + 3}>{g.label}</td>
                          </tr>
                        )}
                        {g.rows.map((r) => {
                          const present = RIGHTS.filter(([k]) => r[k]);
                          const onCount = present.filter(([k]) => r[k].effective === 1).length;
                          const allOn = present.length > 0 && onCount === present.length;
                          const someOn = onCount > 0 && !allOn;
                          const rowChanged = RIGHTS.some(([k]) => r[k]?.override != null);
                          return (
                            <tr key={r.moduleId} className={rowChanged ? "pg-row-changed" : undefined}>
                              <td className="pg-mod">
                                <span className="pg-mod-name">{r.moduleName}</span>
                                <span className="td-code mono">{r.moduleCode}</span>
                              </td>
                              <td className="num pg-sep">
                                <TriCheckbox
                                  checked={allOn} indeterminate={someOn}
                                  disabled={busy}
                                  onChange={() => toggleRow(r.moduleId)}
                                  title={allOn ? "Every right is on — click to clear the row"
                                    : someOn ? `${onCount} of ${present.length} rights on — click to turn them all on`
                                    : "Turn every right on this module on"}
                                  aria-label={`All rights on ${r.moduleName}`}
                                />
                              </td>
                              {RIGHTS.map(([k, label]) => {
                                const cell = r[k];
                                const state = cellState(cell);
                                return (
                                  <td key={k} className="num" data-label={label}>
                                    <input
                                      type="checkbox"
                                      checked={cell?.effective === 1}
                                      disabled={busy}
                                      onChange={() => toggle(r.moduleId, k)}
                                      title={`${label} — ${STATE_HINT[state]}`}
                                      aria-label={`${label} on ${r.moduleName}`}
                                      className={`pg-cell pg-${state}`}
                                    />
                                  </td>
                                );
                              })}
                              <td className="num">
                                <button
                                  className="pg-reset" type="button"
                                  disabled={!rowChanged || busy}
                                  onClick={() => resetRow(r.moduleId)}
                                  title={rowChanged
                                    ? "Put this module back to the pharmacy default"
                                    : "Already following the pharmacy default"}
                                  aria-label={`Reset ${r.moduleName}`}
                                >
                                  {/* An icon rather than the ↺ character: the
                                      glyph rendered at a different size and
                                      baseline in every font the app falls back
                                      through, so the column never sat straight. */}
                                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                                       stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                                       strokeLinejoin="round" aria-hidden="true">
                                    <path d="M3 12a9 9 0 1 0 3-6.7" />
                                    <path d="M3 4v5h5" />
                                  </svg>
                                </button>
                              </td>
                            </tr>
                          );
                        })}
                      </React.Fragment>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}

        <div className="reg-actions">
          <button className="btn btn-ghost" onClick={followDefaults} disabled={busy || exceptions === 0}>
            Follow the pharmacy defaults
          </button>
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="btn btn-primary" onClick={save} disabled={busy || loading}>
            {busy ? "Saving…" : "Save permissions"}
          </button>
        </div>
      </div>
    </div>
  );
}
