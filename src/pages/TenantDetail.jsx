/**
 * One pharmacy, up close: its people, what it has bought, and what it owes.
 *
 * <b>Why the operator's own session is not used to read this.</b> Every request
 * here carries the acting-branch header, so the server answers as though the
 * pharmacy asked. That means one set of endpoints serves both the customer and
 * the person supporting them, instead of a parallel set of admin-only ones that
 * would drift from the originals and disagree about who may see what.
 *
 * <b>Modules: absent means unrestricted, not forbidden.</b> A pharmacy with no
 * grant at all gets everything its roles allow — that is what let the layer ship
 * without stopping every branch already trading. The screen says so in words,
 * because an empty checklist and "everything is allowed" look identical and mean
 * opposite things.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";
import { Card, Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import UserPermissions from "./UserPermissions.jsx";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import { viewRulePatch } from "../lib/permissionRules.js";
import uploadUrl from "../lib/uploadUrl.js";
import { EntryModal, branchCols } from "./MasterData.jsx";
import {
  getBranches, updateBranch, getOrganizations, getCompanies, getRegions,
} from "../services/masterDataService.js";

const bdt = (n) => "৳" + Number(n ?? 0).toLocaleString("en-IN", { maximumFractionDigits: 0 });
const day = (iso) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
};

const RIGHTS = [
  ["canView", "View"], ["canCreate", "Create"], ["canEdit", "Edit"],
  ["canDelete", "Delete"], ["canApprove", "Approve"], ["canExport", "Export"],
  ["canPrint", "Print"],
];

export default function TenantDetail({ tenant, onClose, onChanged }) {
  const addToast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState("users");
  const [users, setUsers] = useState([]);
  const [roles, setRoles] = useState([]);
  const [modules, setModules] = useState([]);
  const [unrestricted, setUnrestricted] = useState(true);
  const [billing, setBilling] = useState(null);
  const [busy, setBusy] = useState(null);
  const [newUser, setNewUser] = useState(null);
  // The person whose individual permissions are open, if any.
  const [tuning, setTuning] = useState(null);
  // Recording money is not a one-line question, so it is not asked with one.
  const [paying, setPaying] = useState(null);
  // Each tab searches its own list. Held separately so switching tabs does not
  // carry a half-typed filter into a list it does not belong to.
  const [userQ, setUserQ] = useState("");
  const [moduleQ, setModuleQ] = useState("");
  /* Held separately from `tenant`, which is a row handed down by the list and
     not refetched while this modal is open. Uploading would otherwise change
     the file and leave the picture on screen showing the old one. */
  const [logoUrl, setLogoUrl] = useState(tenant.logoUrl ?? null);
  const logoInputRef = React.useRef(null);

  /* Editing the branch itself — the address, the region, who it sells to, the
     coordinates, the delivery charges. This used to be possible only on Master
     Data, so a pharmacy opened from here could be given users and modules but
     never a region or a trade type. It is the same form, not a second one:
     `branchCols` is the single definition both screens render. */
  const [editBranch, setEditBranch] = useState(null);
  const [branchLookups, setBranchLookups] = useState({ orgs: [], companies: [], regions: [] });
  const [savingBranch, setSavingBranch] = useState(false);
  const [branchErrors, setBranchErrors] = useState(null);

  // The header that makes the server answer as this pharmacy. Everything below
  // reads and writes inside it.
  const as = { "X-Acting-Branch": String(tenant.branchId) };

  const load = useCallback(() => {
    setBusy("load");
    return Promise.all([
      apiClient.get("/api/v1/rbac/users", null, as),
      apiClient.get("/api/v1/rbac/roles", null, as),
      apiClient.get(`/api/admin/onboarding/branches/${tenant.branchId}/modules`),
      apiClient.get(`/api/admin/subscriptions/${tenant.branchId}`),
    ])
      .then(([u, r, m, b]) => {
        setUsers(u ?? []);
        // Super Admin is the platform's own role, not a job in a pharmacy.
        // Offering it here would invite somebody to hand a customer the role that
        // the acting-branch guard exists to keep out of their hands.
        setRoles((r ?? []).filter((x) => x.groupCode !== "ROLE01"));
        setModules(m?.modules ?? []);
        setUnrestricted(!!m?.unrestricted);
        setBilling(b ?? null);
      })
      .catch((e) => addToast(e?.message || "Could not load this pharmacy.", "error"))
      .finally(() => setBusy(null));
  }, [tenant.branchId, addToast]);   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load(); }, [load]);

  /* ── people ── */
  const addUser = async () => {
    if (!newUser.username?.trim() || !newUser.password || !newUser.pidGroup) {
      return addToast("A username, a password and a role are all needed.", "warning");
    }
    setBusy("user");
    try {
      await apiClient.post("/api/v1/rbac/users", {
        username: newUser.username.trim(),
        password: newUser.password,
        fullName: newUser.fullName?.trim() || newUser.username.trim(),
        email: newUser.email || null,
        phoneNo: newUser.phoneNo || null,
        pidGroup: Number(newUser.pidGroup),
        // Neither the organisation nor the branch is really taken from here: the
        // server reads both off the session and the acting header, because an
        // organisation in a request body is one the caller can change. The branch
        // is sent anyway so the payload reads honestly to anyone debugging it.
        pidBranch: tenant.branchId,
      }, as);
      addToast(`${newUser.username} can now sign in to ${tenant.branchName}.`, "success");
      setNewUser(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not add the user.", "error");
    } finally { setBusy(null); }
  };

  /* ── branding ──
     The mark shown in this pharmacy's own sidebar and browser tab. Uploaded
     here because this is where the pharmacy is administered, and because it is
     an attribute of the tenant rather than of any one tab below. */

  const uploadLogo = async (file) => {
    if (!file) return;
    setBusy("logo");
    try {
      const body = new FormData();
      body.append("file", file);
      const res = await apiClient.upload(
        `/api/branches/${tenant.branchId}/logo`, body);
      setLogoUrl(res?.logoUrl ?? null);
      addToast(`Logo updated for ${tenant.branchName}.`, "success");
      // The list behind shows the same pharmacies; its copy of the row is now
      // a version behind.
      onChanged?.();
    } catch (e) {
      addToast(e?.message || "Could not upload the logo.", "error");
    } finally {
      setBusy(null);
      // Cleared so choosing the same file again still fires a change event.
      if (logoInputRef.current) logoInputRef.current.value = "";
    }
  };

  const removeLogo = async () => {
    const ok = await confirm({
      title: `Remove the logo for ${tenant.branchName}?`,
      body: "Their sidebar and browser tab fall back to the first letter of the pharmacy name.",
      confirmLabel: "Remove logo", tone: "danger",
    });
    if (!ok) return;
    setBusy("logo");
    try {
      await apiClient.delete(`/api/branches/${tenant.branchId}/logo`);
      setLogoUrl(null);
      addToast("Logo removed.", "success");
      onChanged?.();
    } catch (e) {
      addToast(e?.message || "Could not remove the logo.", "error");
    } finally { setBusy(null); }
  };

  /**
   * Open the branch form, pre-filled with what the branch actually holds.
   *
   * The tenant row this modal was handed carries a name, a plan and a status —
   * not an address, a region or a trade type. So the row is read fresh before
   * the form opens, otherwise every field the list does not carry would come
   * up blank and saving would wipe it.
   */
  const openBranchForm = async () => {
    setBusy("branch");
    try {
      const [rows, orgs, regions] = await Promise.all([
        getBranches(), getOrganizations(), getRegions(),
      ]);
      const row = (rows || []).find((b) => String(b.id) === String(tenant.branchId));
      if (!row) throw new Error("That branch could not be read.");
      const companies = await getCompanies(row.orgId);
      setBranchLookups({ orgs: orgs || [], companies: companies || [], regions: regions || [] });
      setBranchErrors(null);
      setEditBranch(row);
    } catch (e) {
      addToast(e?.message || "Could not open the branch.", "error");
    } finally { setBusy(null); }
  };

  const saveBranch = async (data) => {
    setSavingBranch(true);
    setBranchErrors(null);
    try {
      await updateBranch(editBranch.id, { ...data, orgId: data.orgId || editBranch.orgId });
      addToast(`${data.name || tenant.branchName} saved.`, "success");
      setEditBranch(null);
      onChanged?.();
    } catch (e) {
      /* The server's own field errors, so a refusal lands on the field that
         caused it rather than in a toast the form cannot act on. */
      if (e?.fieldErrors && typeof e.fieldErrors === "object") setBranchErrors(e.fieldErrors);
      addToast(e?.message || "Could not save the branch.", "error");
    } finally { setSavingBranch(false); }
  };

  const toggleUser = async (u) => {
    const on = u.isActive === 1 || u.isActive === true;
    setBusy(u.pidUser ?? u.id);
    try {
      await apiClient.patch(
        `/api/v1/rbac/users/${u.pidUser ?? u.id}/${on ? "deactivate" : "activate"}`, null, as);
      addToast(`${u.userName ?? u.username} ${on ? "can no longer sign in" : "can sign in again"}.`, "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not change the user.", "error");
    } finally { setBusy(null); }
  };

  /* ── what they have bought ── */
  const setRight = (moduleId, key, value) =>
    setModules((rows) => rows.map((r) => {
      if (r.moduleId !== moduleId) return r;
      const isOn = (k) => r[k] === 1;
      // View arrives with whatever needs it and leaves with everything that
      // stood on it — see lib/permissionRules.js.
      const patch = viewRulePatch(key, !!value, isOn);
      const next = { ...r };
      for (const [k, on] of Object.entries(patch)) next[k] = on ? 1 : 0;
      return next;
    }));

  const toggleGrant = (moduleId, granted) =>
    setModules((rows) => rows.map((r) => (r.moduleId === moduleId ? { ...r, granted } : r)));

  /* ── what each tab shows ──────────────────────────────────────────────────
     Filtering and counting live here rather than inline in the markup, so the
     summary line and the rows below it can never disagree about what is on
     screen. */

  const activeUsers = users.filter((u) => u.isActive === 1 || u.isActive === true).length;

  const visibleUsers = useMemo(() => {
    const t = userQ.trim().toLowerCase();
    if (!t) return users;
    return users.filter((u) =>
      String(u.userName ?? u.username ?? "").toLowerCase().includes(t) ||
      String(u.fullName ?? "").toLowerCase().includes(t) ||
      String(u.roleName ?? u.groupName ?? "").toLowerCase().includes(t));
  }, [users, userQ]);

  const grantedCount = modules.filter((m) => m.granted).length;

  const visibleModules = useMemo(() => {
    const t = moduleQ.trim().toLowerCase();
    if (!t) return modules;
    return modules.filter((m) =>
      String(m.moduleName ?? "").toLowerCase().includes(t) ||
      String(m.moduleCode ?? "").toLowerCase().includes(t));
  }, [modules, moduleQ]);

  /* Headings come from the rows themselves: the parent of a listed module is
     usually listed too, and when it is not the module still has to appear. */
  const groupedModules = useMemo(() => {
    const nameById = new Map(modules.map((m) => [m.moduleId, m.moduleName]));
    const groups = new Map();
    for (const m of visibleModules) {
      const key = m.parentId != null && nameById.has(m.parentId) ? m.parentId : "__none";
      if (!groups.has(key)) {
        groups.set(key, { label: key === "__none" ? null : nameById.get(key), rows: [] });
      }
      groups.get(key).rows.push(m);
    }
    return [...groups.values()];
  }, [visibleModules, modules]);

  /** Every right on one module, on or off together — and the grant with them. */
  const toggleModuleRow = (moduleId) =>
    setModules((rows) => rows.map((r) => {
      if (r.moduleId !== moduleId) return r;
      const allOn = r.granted && RIGHTS.every(([k]) => r[k] === 1);
      const next = { ...r, granted: !allOn };
      for (const [k] of RIGHTS) next[k] = allOn ? 0 : 1;
      return next;
    }));

  /** One right, across every granted module currently listed. */
  const toggleModuleColumn = (right) => {
    const ids = new Set(visibleModules.filter((m) => m.granted).map((m) => m.moduleId));
    if (ids.size === 0) return;
    const allOn = visibleModules.filter((m) => m.granted).every((m) => m[right] === 1);
    setModules((rows) => rows.map((r) => {
      if (!ids.has(r.moduleId)) return r;
      const isOn = (k) => r[k] === 1;
      const patch = viewRulePatch(right, !allOn, isOn);
      const next = { ...r };
      for (const [k, on] of Object.entries(patch)) next[k] = on ? 1 : 0;
      return next;
    }));
  };

  const saveModules = async () => {
    setBusy("modules");
    try {
      const granted = modules.filter((m) => m.granted).map((m) => ({
        moduleId: m.moduleId,
        canView: m.canView, canCreate: m.canCreate, canEdit: m.canEdit,
        canDelete: m.canDelete, canApprove: m.canApprove,
        canExport: m.canExport, canPrint: m.canPrint,
      }));
      await apiClient.put(`/api/admin/onboarding/branches/${tenant.branchId}/modules`,
                          { modules: granted });
      addToast(granted.length === 0
        ? "This pharmacy is now unrestricted — everything its roles allow."
        : `${granted.length} module(s) granted.`, "success");
      await load();
      onChanged?.();
    } catch (e) {
      addToast(e?.message || "Could not save the modules.", "error");
    } finally { setBusy(null); }
  };

  /* ── money ── */
  const recordPayment = async () => {
    const { invoice, method, providerRef } = paying;
    setBusy(invoice.invoiceId);
    try {
      await apiClient.post(`/api/admin/subscriptions/invoices/${invoice.invoiceId}/payment`, {
        amount: invoice.amount,
        method,
        providerRef: providerRef?.trim() || null,
        // Per attempt, so a double-click or a retry after a dropped connection
        // settles the invoice once. The same guard a gateway webhook will use.
        idempotencyKey: `console-${invoice.invoiceId}-${Date.now()}`,
      });
      addToast(`Payment recorded. ${tenant.branchName} can trade again.`, "success");
      setPaying(null);
      await load();
      onChanged?.();
    } catch (e) {
      addToast(e?.message || "Could not record the payment.", "error");
    } finally { setBusy(null); }
  };

  /**
   * Cancels an invoice raised in error.
   *
   * <p>The server voids rather than deletes, so the wording here says cancel and
   * not delete — a billing history with a hole in it cannot be reconciled, and
   * promising a deletion that does not happen is worse than explaining why.
   */
  const voidInvoice = async (inv) => {
    const ok = await confirm({
      title: `Cancel invoice ${inv.invoiceNo}?`,
      body: `${bdt(inv.amount)} for ${day(inv.periodFrom)} – ${day(inv.periodTo)}. `
          + "It stays on the record marked cancelled rather than disappearing, so the "
          + "billing history still adds up. This cannot be undone.",
      confirmLabel: "Cancel the invoice",
      // Not "Cancel": on a dialog about cancelling something, a button called
      // Cancel could mean either answer.
      cancelLabel: "Keep it",
      tone: "danger",
    });
    if (!ok) return;
    setBusy(inv.invoiceId);
    try {
      await apiClient.delete(
        `/api/admin/subscriptions/invoices/${inv.invoiceId}?reason=${encodeURIComponent("cancelled from the console")}`);
      addToast(`Invoice ${inv.invoiceNo} cancelled.`, "success");
      await load();
      onChanged?.();
    } catch (e) {
      addToast(e?.message || "Could not cancel the invoice.", "error");
    } finally { setBusy(null); }
  };

  const raiseInvoice = async () => {
    setBusy("invoice");
    try {
      const inv = await apiClient.post(`/api/admin/subscriptions/${tenant.branchId}/invoice`, {});
      addToast(`Invoice ${inv.invoiceNo} raised for ${bdt(inv.amount)}.`, "success");
      await load();
      onChanged?.();
    } catch (e) {
      addToast(e?.message || "Could not raise the invoice.", "error");
    } finally { setBusy(null); }
  };

  const outstanding = billing?.outstanding ?? [];
  /* The number the operator is actually ringing about. Shown as one figure
     because "three invoices" does not answer "how much do they owe". */
  const owed = outstanding.reduce((s, i) => s + Number(i.amount ?? 0), 0);

  /* Days left on the subscription, negative once it has lapsed. Derived rather
     than sent, so it cannot be stale by the time somebody reads it. */
  const daysLeft = (() => {
    const iso = billing?.lastDayOfAccess ?? billing?.expiresOn;
    if (!iso) return null;
    const end = new Date(iso);
    if (Number.isNaN(end.getTime())) return null;
    return Math.ceil((end - new Date()) / 86400000);
  })();

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal td-modal" onClick={(e) => e.stopPropagation()}>
        <div className="td-head">
          <div className="td-person">
            {/* The mark this pharmacy shows in its own sidebar and browser tab.
                Editable here because this is where the tenant is administered,
                and it belongs to the pharmacy rather than to any tab below. */}
            <span className="td-logo">
              {logoUrl
                ? <img src={uploadUrl(logoUrl)} alt="" onError={() => setLogoUrl(null)} />
                : <span className="td-logo-initial" aria-hidden="true">
                    {(tenant.branchName || "?").trim().charAt(0).toUpperCase() || "?"}
                  </span>}
            </span>
            <div className="td-person-text">
              <h3>{tenant.branchName}</h3>
              <div className="td-sub">
                Branch {tenant.branchId} · {tenant.planName ?? "no plan"} ·{" "}
                <Pill tone={tenant.tenantStatus === "ACTIVE" ? "sage" : "clay"}>{tenant.tenantStatus}</Pill>
              </div>
              <div className="td-logo-actions">
                {/* The input is hidden and driven by the button: the browser's
                    own file control cannot be styled, and reads "No file
                    chosen" beside a logo that is plainly already chosen. */}
                <input ref={logoInputRef} type="file" accept="image/png,image/jpeg,image/webp"
                       style={{ display: "none" }}
                       onChange={(e) => uploadLogo(e.target.files?.[0])} />
                <button className="btn btn-ghost btn-sm" disabled={busy === "logo"}
                        onClick={() => logoInputRef.current?.click()}>
                  {busy === "logo" ? "Saving…" : logoUrl ? "Change logo" : "Upload logo"}
                </button>
                {logoUrl && (
                  <button className="btn btn-ghost btn-sm" disabled={busy === "logo"}
                          onClick={removeLogo}>Remove</button>
                )}
                {/* The same form Master Data uses, so the address, region,
                    trade type, coordinates and delivery charges are all
                    editable from the console that administers this tenant. */}
                <button className="btn btn-ghost btn-sm" disabled={busy === "branch"}
                        onClick={openBranchForm}>
                  {busy === "branch" ? "Opening…" : "Edit branch"}
                </button>
                <span className="td-logo-hint">PNG, JPG or WEBP · up to 2 MB</span>
              </div>
            </div>
          </div>
          <button className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>
        </div>

        <div className="tabs td-tabs">
          {[["users", `People${users.length ? ` (${users.length})` : ""}`],
            ["modules", "What they have"],
            ["billing", `Billing${outstanding.length ? ` (${outstanding.length})` : ""}`]].map(([k, label]) => (
            <button key={k} className={`tab-btn ${tab === k ? "active" : ""}`} onClick={() => setTab(k)}>
              {label}
            </button>
          ))}
        </div>

        <div className="td-body">
          {tab === "users" && (
            <>
              {/* Search and the headline count share a row: the count describes
                  the whole pharmacy, the search narrows what is under it, and
                  seeing both at once is what stops "3 people" reading as the
                  total when it is a filtered view. */}
              <div className="pg-toolbar">
                <input
                  className="pg-search" type="search" value={userQ}
                  onChange={(e) => setUserQ(e.target.value)}
                  placeholder={`Search ${users.length} ${users.length === 1 ? "person" : "people"}…`}
                  aria-label="Search people"
                />
                <div className="td-summary">
                  <Pill tone="teal">{users.length} {users.length === 1 ? "person" : "people"}</Pill>
                  <Pill tone={activeUsers ? "sage" : "clay"}>{activeUsers} can sign in</Pill>
                  <button className="btn btn-primary btn-sm"
                          onClick={() => setNewUser({ username: "", fullName: "", password: "",
                                                      pidGroup: roles[0]?.pidGroup ?? "" })}>
                    Add a person
                  </button>
                </div>
              </div>

              <div className="table-scroll">
                <table className="data-table td-people">
                  <thead><tr>
                    <th>Person</th><th>Role</th><th>Signs in</th><th className="num">Actions</th>
                  </tr></thead>
                  <tbody>
                    {visibleUsers.map((u) => {
                      const on = u.isActive === 1 || u.isActive === true;
                      const id = u.pidUser ?? u.id;
                      const name = u.fullName || u.userName || u.username || "—";
                      return (
                        <tr key={id} className={on ? undefined : "td-row-off"}>
                          <td>
                            <div className="td-person">
                              {/* An initial, not an avatar: there are no photos
                                  here, and a coloured disc gives the eye
                                  something to travel down a list by. */}
                              <span className="td-avatar" aria-hidden="true">
                                {name.trim().charAt(0).toUpperCase() || "?"}
                              </span>
                              <span className="td-person-text">
                                <span className="td-person-name">{name}</span>
                                <span className="td-code mono">{u.userName ?? u.username}</span>
                              </span>
                            </div>
                          </td>
                          <td>{u.roleName ?? u.groupName ?? u.roleCode ?? "—"}</td>
                          <td><Pill tone={on ? "sage" : "clay"}>{on ? "yes" : "no"}</Pill></td>
                          <td className="num">
                            <div className="td-row-actions">
                              <button className="btn btn-ghost btn-sm" disabled={busy === id}
                                      onClick={() => setTuning(u)}>
                                Permissions
                              </button>
                              <button className="btn btn-ghost btn-sm" disabled={busy === id}
                                      onClick={() => toggleUser(u)}>
                                {on ? "Disable" : "Enable"}
                              </button>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                    {users.length === 0 && (
                      <tr><td colSpan={4} className="empty-state">
                        Nobody can sign in to this pharmacy yet.
                      </td></tr>
                    )}
                    {users.length > 0 && visibleUsers.length === 0 && (
                      <tr><td colSpan={4} className="empty-state">
                        Nobody matches &ldquo;{userQ.trim()}&rdquo;.
                      </td></tr>
                    )}
                  </tbody>
                </table>
              </div>

              {newUser && (
                <Card eyebrow="They will sign in to this pharmacy only" title="Add a person">
                  <div className="reg-fields">
                    <label><span>Username</span>
                      <input value={newUser.username}
                             onChange={(e) => setNewUser({ ...newUser, username: e.target.value })} /></label>
                    <label><span>Full name</span>
                      <input value={newUser.fullName}
                             onChange={(e) => setNewUser({ ...newUser, fullName: e.target.value })} /></label>
                    <label><span>Password</span>
                      <input type="password" value={newUser.password} autoComplete="new-password"
                             onChange={(e) => setNewUser({ ...newUser, password: e.target.value })} /></label>
                    <label><span>Role</span>
                      <select value={newUser.pidGroup}
                              onChange={(e) => setNewUser({ ...newUser, pidGroup: e.target.value })}>
                        {roles.map((r) => (
                          <option key={r.pidGroup} value={r.pidGroup}>{r.groupName}</option>
                        ))}
                      </select></label>
                  </div>
                  <div className="reg-actions">
                    <button className="btn btn-ghost" onClick={() => setNewUser(null)}>Cancel</button>
                    <button className="btn btn-primary" onClick={addUser} disabled={busy === "user"}>
                      {busy === "user" ? "Adding…" : "Add"}
                    </button>
                  </div>
                </Card>
              )}
            </>
          )}

          {tab === "modules" && (
            <>
              <p className="tenant-note">
                {unrestricted
                  ? "Nothing is granted, which means this pharmacy has everything its roles allow. "
                    + "Tick modules below to restrict it to those; untick them all to go back."
                  : "Only the ticked modules are available to this pharmacy, and only to the extent "
                    + "its roles already allow — this can narrow what a role gives, never widen it."}
              </p>

              <div className="pg-toolbar">
                <input
                  className="pg-search" type="search" value={moduleQ}
                  onChange={(e) => setModuleQ(e.target.value)}
                  placeholder={`Search ${modules.length} modules…`}
                  aria-label="Search modules"
                />
                <div className="td-summary">
                  <Pill tone={unrestricted ? "amber" : "teal"}>
                    {unrestricted ? "Unrestricted" : `${grantedCount} of ${modules.length} granted`}
                  </Pill>
                </div>
              </div>

              {visibleModules.length === 0 ? (
                <div className="empty-state">No module matches &ldquo;{moduleQ.trim()}&rdquo;.</div>
              ) : (
                <div className="table-scroll pg-scroll td-modules">
                  <table className="data-table pg-table">
                    <thead>
                      <tr>
                        <th>Module</th>
                        <th className="num">Granted</th>
                        <th className="num">All</th>
                        {RIGHTS.map(([k, label]) => {
                          const settable = visibleModules.filter((m) => m.granted);
                          const allOn = settable.length > 0 && settable.every((m) => m[k] === 1);
                          return (
                            <th key={k} className="num pg-col"
                                onClick={() => toggleModuleColumn(k)}
                                title={settable.length
                                  ? `Turn ${label} on or off for every granted module listed`
                                  : `Grant a module first`}>
                              <span className={allOn ? "pg-col-on" : undefined}>{label}</span>
                            </th>
                          );
                        })}
                      </tr>
                    </thead>
                    <tbody>
                      {groupedModules.map((g, gi) => (
                        <React.Fragment key={g.label ?? `g${gi}`}>
                          {g.label && (
                            <tr className="pg-group">
                              <td colSpan={RIGHTS.length + 3}>{g.label}</td>
                            </tr>
                          )}
                          {g.rows.map((m) => {
                            const allOn = m.granted && RIGHTS.every(([k]) => m[k] === 1);
                            return (
                              <tr key={m.moduleId} className={m.granted ? "" : "row-picked"}>
                                <td className="pg-mod">
                                  {m.moduleName}
                                  <span className="td-code mono">{m.moduleCode}</span>
                                </td>
                                <td className="num">
                                  <input type="checkbox" checked={!!m.granted}
                                         onChange={(e) => toggleGrant(m.moduleId, e.target.checked)}
                                         aria-label={`Grant ${m.moduleName}`} />
                                </td>
                                <td className="num">
                                  <input type="checkbox" checked={allOn}
                                         onChange={() => toggleModuleRow(m.moduleId)}
                                         title="Grant this module with every right, or take it away"
                                         aria-label={`All rights on ${m.moduleName}`} />
                                </td>
                                {RIGHTS.map(([k, label]) => (
                                  <td key={k} className="num">
                                    {/* Disabled rather than hidden: an ungranted
                                        module keeps its shape in the row, so the
                                        columns still line up down the table. */}
                                    <input type="checkbox" disabled={!m.granted} checked={m[k] === 1}
                                           onChange={(e) => setRight(m.moduleId, k, e.target.checked)}
                                           title={m.granted ? label : `Grant ${m.moduleName} first`}
                                           aria-label={`${label} on ${m.moduleName}`} />
                                  </td>
                                ))}
                              </tr>
                            );
                          })}
                        </React.Fragment>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              <div className="reg-actions">
                <button className="btn btn-ghost" onClick={load}>Discard changes</button>
                <button className="btn btn-primary" onClick={saveModules} disabled={busy === "modules"}>
                  {busy === "modules" ? "Saving…" : "Save what they have"}
                </button>
              </div>
            </>
          )}

          {tab === "billing" && billing && (
            <>
              {/* The two questions asked on a billing call, answered before the
                  detail: how long have they got, and how much do they owe. */}
              <div className="td-billing-head">
                <div className={`td-bill-card ${daysLeft != null && daysLeft < 0 ? "td-bill-bad"
                                 : daysLeft != null && daysLeft <= 7 ? "td-bill-warn" : ""}`}>
                  <div className="td-bill-label">Access</div>
                  <div className="td-bill-value">
                    {daysLeft == null ? "—"
                      : daysLeft < 0 ? `Lapsed ${Math.abs(daysLeft)} day${Math.abs(daysLeft) === 1 ? "" : "s"} ago`
                      : daysLeft === 0 ? "Ends today"
                      : `${daysLeft} day${daysLeft === 1 ? "" : "s"} left`}
                  </div>
                  <div className="td-bill-sub">until {day(billing.lastDayOfAccess)}</div>
                </div>

                <div className={`td-bill-card ${owed > 0 ? "td-bill-warn" : ""}`}>
                  <div className="td-bill-label">Outstanding</div>
                  <div className="td-bill-value mono">{owed > 0 ? bdt(owed) : "Nothing"}</div>
                  <div className="td-bill-sub">
                    {outstanding.length === 0 ? "all invoices settled"
                      : `${outstanding.length} unpaid invoice${outstanding.length === 1 ? "" : "s"}`}
                  </div>
                </div>

                <div className="td-bill-card">
                  <div className="td-bill-label">Plan</div>
                  <div className="td-bill-value">{billing.planName ?? "—"}</div>
                  <div className="td-bill-sub mono">{bdt(billing.price)} · renews {day(billing.expiresOn)}</div>
                </div>
              </div>

              <div className="td-actions">
                <button className="btn btn-ghost btn-sm" onClick={raiseInvoice} disabled={busy === "invoice"}>
                  {busy === "invoice" ? "Raising…" : "Raise the next invoice"}
                </button>
              </div>

              {outstanding.length === 0 ? (
                <div className="sub-paid">Nothing outstanding.</div>
              ) : (
                <div className="sub-invoices">
                  {outstanding.map((i) => (
                    <div key={i.invoiceId} className="sub-invoice">
                      <div>
                        <div className="sub-invoice-no mono">{i.invoiceNo}</div>
                        <div className="sub-invoice-period">{day(i.periodFrom)} – {day(i.periodTo)}</div>
                      </div>
                      <div className="flex-gap">
                        <div className="sub-invoice-amt mono">{bdt(i.amount)}</div>
                        <button className="btn btn-ghost btn-sm" disabled={busy === i.invoiceId}
                                onClick={() => voidInvoice(i)}>Cancel</button>
                        <button className="btn btn-primary btn-sm" disabled={busy === i.invoiceId}
                                onClick={() => setPaying({ invoice: i, method: "BKASH", providerRef: "" })}>
                          {busy === i.invoiceId ? "Recording…" : "Record payment"}
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>

        {paying && (
          <div className="modal-backdrop" onClick={() => setPaying(null)}>
            <div className="modal modal-sm" onClick={(e) => e.stopPropagation()}>
              <h3>Record payment</h3>
              <p className="cat-modal-note">
                {bdt(paying.invoice.amount)} against {paying.invoice.invoiceNo}. Recording it
                settles the invoice and restores access straight away.
              </p>
              <div className="reg-fields">
                <label><span>How it was paid</span>
                  <select value={paying.method}
                          onChange={(e) => setPaying({ ...paying, method: e.target.value })}>
                    <option value="BKASH">bKash</option>
                    <option value="BANK">Bank transfer</option>
                    <option value="MANUAL">Cash</option>
                  </select></label>
                <label><span>Reference — optional for cash</span>
                  <input value={paying.providerRef} placeholder="TRX1A2B3C"
                         onChange={(e) => setPaying({ ...paying, providerRef: e.target.value })} /></label>
              </div>
              <div className="reg-actions">
                <button className="btn btn-ghost" onClick={() => setPaying(null)}>Cancel</button>
                <button className="btn btn-primary" onClick={recordPayment}
                        disabled={busy === paying.invoice.invoiceId}>
                  {busy === paying.invoice.invoiceId ? "Recording…" : `Record ${bdt(paying.invoice.amount)}`}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {tuning && (
        <UserPermissions
          branchId={tenant.branchId}
          user={tuning}
          actingHeaders={as}
          onClose={() => setTuning(null)}
        />
      )}

      {editBranch && (
        <EntryModal
          entity={{ label: "Branch", cols: branchCols(branchLookups) }}
          mode="edit"
          row={editBranch}
          saving={savingBranch}
          backendErrors={branchErrors}
          onClose={() => setEditBranch(null)}
          onSave={saveBranch}
        />
      )}
    </div>
  );
}
