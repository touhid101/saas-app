/**
 * AccChart — the chart of accounts: the hierarchy, each account's posting
 * role, and adding or editing accounts. System accounts can be renamed and
 * re-parented but not retired while they carry a role.
 */
import React, { useMemo, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { Modal } from "../../components/UI.jsx";
import { HrPage, HrTable, HrFilterBar, Filter, HrSearch, HrButton, HrPageSkeleton, HrErrorState, Status, humanError, P } from "../../components/HrKit.jsx";
import { getChart, createAccount, updateAccount, ACCOUNT_TYPES } from "../../services/accountsService.js";
import { ACC_CRUMB, AI, useAsync } from "./AccKit.jsx";

export default function AccChart({ onNavigate }) {
  const perm = usePermission("acccoa");
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(getChart, []);
  const [q, setQ] = useState("");
  const [type, setType] = useState("");
  const [editing, setEditing] = useState(null);
  const rows = useMemo(() => {
    const all = data || [];
    const byId = Object.fromEntries(all.map((a) => [a.pidAccount, a]));
    const depth = (a) => { let d = 0, p = a.pidParent; while (p && byId[p]) { d++; p = byId[p].pidParent; } return d; };
    return all.map((a) => ({ ...a, depth: depth(a) }))
      .filter((a) => !type || a.accountType === type)
      .filter((a) => !q || `${a.accountCode} ${a.accountName} ${a.roleKey || ""}`.toLowerCase().includes(q.toLowerCase()));
  }, [data, q, type]);
  if (busy && !data) return <HrPageSkeleton label="Reading the chart…" kpis={0} rows={12} />;
  if (err && !data) return <HrErrorState hint={err} onRetry={reload} />;
  const cols = [
    { key: "accountCode", label: "Code", lead: true, render: (a) => <span style={{ paddingLeft: a.depth * 14 }}><b>{a.accountCode}</b></span> },
    { key: "accountName", label: "Account", render: (a) => <span className={a.isGroup ? "acc-group" : ""}>{a.accountName}</span> },
    { key: "accountType", label: "Type" },
    { key: "normalSide", label: "Normal", render: (a) => (a.normalSide === "D" ? "Debit" : "Credit") },
    { key: "roleKey", label: "Posting role", render: (a) => a.roleKey ? <code className="acc-role">{a.roleKey}</code> : <span className="acc-muted">—</span> },
    { key: "allowManual", label: "Manual", render: (a) => a.isGroup ? "" : a.allowManual === 1 ? "Yes" : "System only" },
    { key: "isActive", label: "Status", render: (a) => <Status code={a.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> },
  ];
  const save = async (form) => {
    try {
      if (form.pidAccount) await updateAccount(form.pidAccount, form); else await createAccount(form);
      addToast(form.pidAccount ? "Account updated." : "Account added."); setEditing(null); reload();
    } catch (e) { addToast(humanError(e, "Could not save the account."), "error"); }
  };
  return (
    <HrPage className="acc-page" icon={AI.coa} crumbs={[ACC_CRUMB]} title="Chart of Accounts" subtitle="The hierarchy the books post to. A posting role names the account a rule uses; move a role and the rules follow it."
            actions={perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setEditing({})}>New account</HrButton>}>
      <HrFilterBar>
        <Filter label="Search" grow><HrSearch value={q} onChange={setQ} delay={0} placeholder="Code, name or role…" /></Filter>
        <Filter label="Type"><select value={type} onChange={(e) => setType(e.target.value)}><option value="">All</option>{ACCOUNT_TYPES.map((t) => <option key={t}>{t}</option>)}</select></Filter>
      </HrFilterBar>
      <HrTable cols={cols} rows={rows} rowKey={(a) => a.pidAccount} onRowClick={perm.canEdit ? (a) => setEditing(a) : undefined} caption={`${rows.length} account(s)`} empty="No account matches." />
      {editing && <AccountForm initial={editing} all={data || []} onClose={() => setEditing(null)} onSave={save} />}
    </HrPage>
  );
}

function AccountForm({ initial, all, onClose, onSave }) {
  const isNew = !initial.pidAccount;
  const [f, setF] = useState({ pidAccount: initial.pidAccount, accountCode: initial.accountCode || "", accountName: initial.accountName || "", accountType: initial.accountType || "EXPENSE",
    pidParent: initial.pidParent || "", normalSide: initial.normalSide || "", roleKey: initial.roleKey || "", allowManual: initial.allowManual ?? 1, isActive: initial.isActive ?? 1, description: initial.description || "", isGroup: initial.isGroup ?? 0 });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const parents = all.filter((a) => a.accountType === f.accountType && a.pidAccount !== f.pidAccount);
  const [busy, setBusy] = useState(false);
  const submit = async () => { setBusy(true); await onSave({ ...f, pidParent: f.pidParent ? Number(f.pidParent) : null, normalSide: f.normalSide || null, roleKey: f.roleKey || "" }); setBusy(false); };
  return (
    <Modal title={isNew ? "New account" : `${initial.accountCode} ${initial.accountName}`} subtitle={isNew ? "A child takes its parent's type." : initial.isSystem === 1 ? "A system account: rename or re-parent it; its role can move to another account." : ""} onClose={onClose} width={560}
      footer={<><HrButton onClick={onClose}>Cancel</HrButton><HrButton variant="primary" busy={busy} onClick={submit} disabled={!f.accountName || (isNew && !f.accountCode)}>{isNew ? "Add account" : "Save"}</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Code</label><input value={f.accountCode} onChange={(e) => set("accountCode", e.target.value)} disabled={!isNew} placeholder="e.g. 6210" /></div>
        <div className="field"><label>Type</label><select value={f.accountType} onChange={(e) => { set("accountType", e.target.value); set("pidParent", ""); }} disabled={!isNew}>{ACCOUNT_TYPES.map((t) => <option key={t}>{t}</option>)}</select></div>
        <div className="field acc-span2"><label>Name</label><input value={f.accountName} onChange={(e) => set("accountName", e.target.value)} /></div>
        <div className="field"><label>Parent</label><select value={f.pidParent} onChange={(e) => set("pidParent", e.target.value)}><option value="">— top level —</option>{parents.map((a) => <option key={a.pidAccount} value={a.pidAccount}>{a.accountCode} {a.accountName}</option>)}</select></div>
        <div className="field"><label>Normal balance</label><select value={f.normalSide} onChange={(e) => set("normalSide", e.target.value)}><option value="">By type</option><option value="D">Debit</option><option value="C">Credit</option></select></div>
        <div className="field"><label>Posting role</label><input value={f.roleKey} onChange={(e) => set("roleKey", e.target.value.toUpperCase().replace(/[^A-Z_]/g, ""))} placeholder="e.g. RENT (optional)" /></div>
        <div className="field"><label>Manual journals</label><select value={f.allowManual} onChange={(e) => set("allowManual", Number(e.target.value))}><option value={1}>Allowed</option><option value={0}>System only</option></select></div>
        {isNew && <div className="field"><label>Kind</label><select value={f.isGroup} onChange={(e) => set("isGroup", Number(e.target.value))}><option value={0}>Posting account</option><option value={1}>Group (holds children)</option></select></div>}
        {!isNew && <div className="field"><label>Status</label><select value={f.isActive} onChange={(e) => set("isActive", Number(e.target.value))}><option value={1}>Active</option><option value={0}>Retired</option></select></div>}
        <div className="field acc-span2"><label>Description</label><input value={f.description} onChange={(e) => set("description", e.target.value)} /></div>
      </div>
    </Modal>
  );
}
