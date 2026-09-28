/**
 * Who can reach one module — the permission matrix read down the other axis.
 *
 * <b>Why it exists.</b> The Permissions tab answers "what can this role do?".
 * The opposite question — "who can reach Purchase Invoice?" — could only be
 * answered by opening nine roles in turn and remembering what each one said, and
 * granting a new page to five roles meant five separate saves.
 *
 * <b>It touches only this module.</b> Saving a role from the Permissions tab
 * replaces every module that role holds; doing that from here would silently
 * disturb twenty other pages this screen never showed. The server writes one
 * (role, module) pair at a time for exactly that reason.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";
import { useToast } from "../hooks/useToast.js";

const RIGHTS = [
  ["canView",    "View"],
  ["canCreate",  "Create"],
  ["canEdit",    "Edit"],
  ["canDelete",  "Delete"],
  ["canApprove", "Approve"],
  ["canExport",  "Export"],
  ["canPrint",   "Print"],
];

export default function ModuleRoles({ module, onClose, onSaved }) {
  const addToast = useToast();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    return apiClient
      .get(`/api/v1/rbac/modules/${module.pidModule}/roles`)
      .then((d) => setRows(Array.isArray(d) ? d : []))
      .catch((e) => addToast(e?.message || "Could not load roles.", "error"))
      .finally(() => setLoading(false));
  }, [module.pidModule, addToast]);

  useEffect(() => { load(); }, [load]);

  /* A right that acts on a page nobody can open is not a right, so View comes on
     with anything that needs it and takes them with it when it goes. The server
     applies the same rule, so the two cannot disagree. */
  const toggle = (pidGroup, key) => {
    setRows((prev) => prev.map((r) => {
      if (r.pidGroup !== pidGroup || r.locked) return r;
      const next = { ...r, [key]: r[key] === 1 ? 0 : 1 };
      if (key !== "canView" && next[key] === 1) next.canView = 1;
      if (key === "canView" && next.canView === 0) {
        for (const [k] of RIGHTS) next[k] = 0;
      }
      return next;
    }));
  };

  const toggleRole = (pidGroup) => {
    setRows((prev) => prev.map((r) => {
      if (r.pidGroup !== pidGroup || r.locked) return r;
      const on = RIGHTS.every(([k]) => r[k] === 1);
      const next = { ...r };
      for (const [k] of RIGHTS) next[k] = on ? 0 : 1;
      return next;
    }));
  };

  const held = useMemo(() => rows.filter((r) => r.canView === 1).length, [rows]);

  const save = async () => {
    setBusy(true);
    try {
      const payload = rows.filter((r) => !r.locked).map((r) => {
        const out = { pidGroup: r.pidGroup };
        for (const [k] of RIGHTS) out[k] = r[k] ?? 0;
        return out;
      });
      await apiClient.put(`/api/v1/rbac/modules/${module.pidModule}/roles`, { roles: payload });
      addToast(`${module.moduleName} updated.`, "success");
      await load();
      onSaved?.();
    } catch (e) {
      addToast(e?.message || "Could not save.", "error");
    } finally { setBusy(false); }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal modal-wide" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h2>{module.moduleName}</h2>
            <div className="page-sub">
              <span className="mono">{module.moduleCode}</span>
              {" · "}
              {held === 0 ? "No role can reach it"
                : `${held} role${held === 1 ? "" : "s"} can reach it`}
            </div>
          </div>
          <button className="icon-btn" aria-label="Close" onClick={onClose}>×</button>
        </div>

        {/* The body carries its own padding: .modal has none, so content placed
            straight inside it sits flush against the left edge. */}
        <div className="mr-body">
          <p className="mr-note">
            Granting here changes this page only — every other page a role holds is left
            exactly as it was. Super Admin is not editable: a console that can remove its
            own access is one somebody eventually locks themselves out of.
          </p>

          {loading ? (
            <div className="empty-state">Loading…</div>
          ) : (
            <div className="mr-scroll">
              <table className="mr-table">
                <thead>
                  <tr>
                    <th className="mr-role-col">Role</th>
                    {RIGHTS.map(([k, label]) => <th key={k}>{label}</th>)}
                    <th className="mr-all-col"></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.pidGroup} className={r.locked ? "mr-locked" : undefined}>
                      <td className="mr-role-col">
                        {/* Name and code stacked, not run together: side by side
                            they read as one word — "PharmacistROLE03". */}
                        <div className="mr-role-name">
                          {r.roleName}
                          {r.locked && <span className="mr-always">always</span>}
                        </div>
                        <div className="mr-role-code">{r.roleCode}</div>
                      </td>
                      {RIGHTS.map(([k]) => (
                        <td key={k}>
                          <input
                            type="checkbox"
                            className="mr-box"
                            checked={r[k] === 1}
                            disabled={r.locked || busy}
                            onChange={() => toggle(r.pidGroup, k)}
                            aria-label={`${k} for ${r.roleName}`}
                          />
                        </td>
                      ))}
                      <td className="mr-all-col">
                        <button className="btn btn-ghost btn-sm"
                                disabled={r.locked || busy}
                                onClick={() => toggleRole(r.pidGroup)}>
                          {RIGHTS.every(([k]) => r[k] === 1) ? "Clear" : "All"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="mr-foot">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="btn btn-primary" onClick={save} disabled={busy || loading}>
            {busy ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}
