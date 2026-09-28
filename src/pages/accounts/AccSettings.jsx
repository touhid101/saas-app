/**
 * AccSettings — periods, policies, payment-mode mapping, tax rates, cost
 * centres and the accounting rules. Everything here is data the engine
 * reads; nothing here posts.
 */
import React, { useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { Modal } from "../../components/UI.jsx";
import { HrPage, HrTable, HrButton, HrTabBar, Status, HrPageSkeleton, HrErrorState, humanError, PolicyNote, P, Kpis, Kpi, HrFilterBar, Filter } from "../../components/HrKit.jsx";
import { getPeriods, setPeriodStatus, createYear, getSettings, saveSettings, savePaymentMode, saveTaxRate, saveCostCenter, saveRule, getChart } from "../../services/accountsService.js";
import { fmtDate, fmtDateTime, isoToday, pct } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, useAsync, takeIntent } from "./AccKit.jsx";

const SETTING_LABELS = {
  fiscal_year_start_month: ["Fiscal year starts in month", "1 = January, 7 = July. Applies to years created from now on."],
  currency: ["Currency", "Reporting currency code."],
  cash_tolerance: ["Cash closing tolerance", "A difference within this amount closes without a reason."],
  post_cash_variance: ["Post cash short / over to the ledger", "Y: the ledger till is made equal to the counted cash at closing. N: the difference is recorded on the day sheet only."],
  closing_requires_review: ["Closing needs a reviewer", "Y: a submitted closing waits for Approve on Daily Cash."],
  require_day_open: ["Cash postings need an open day", "Y: a cash posting on a date whose day sheet has not been opened is refused."],
  late_posting_policy: ["Cash posting into a closed day", "REFUSE, or NEXT_DAY to date it on the next day that is not closed."],
  expense_claim_basis: ["Expense claims", "ACCRUAL: expense at approval, settled at payment. CASH: both at payment."],
  posting_enabled: ["Posting to the ledger", "N switches the books off: events are recorded SKIPPED and can be replayed."],
  manual_journal_auto_post: ["Manual journals post on save", "Y: a user holding Approve posts as they save; N: every manual journal is a draft first."],
  use_cash_in_transit: ["Delivery-man cash goes through Cash in Transit", "Y: Collect books the rider's cash as in transit until the Collection register hands it in. N: Collect books the till directly."],
};

export default function AccSettings() {
  const perm = usePermission("accsettings");
  const [intent] = useState(() => takeIntent("accsettings"));
  const [tab, setTab] = useState(intent?.tab || "periods");
  return (
    <HrPage className="acc-page" icon={AI.settings} crumbs={[ACC_CRUMB]} title="Periods & Settings" subtitle="Fiscal years and periods, the policies the engine follows, payment-mode mapping, tax rates, cost centres and the accounting rules.">
      <AccTabs tabs={[{ id: "periods", label: "Periods", icon: AI.calendar }, { id: "settings", label: "Policies", icon: AI.settings }, { id: "modes", label: "Payment modes", icon: AI.wallet }, { id: "tax", label: "Tax rates", icon: AI.percent }, { id: "cc", label: "Cost centres", icon: AI.layers }, { id: "rules", label: "Accounting rules", icon: AI.journal }]} active={tab} onChange={setTab} />
      {tab === "periods" && <Periods perm={perm} />}
      {tab !== "periods" && <SettingsTabs tab={tab} perm={perm} />}
    </HrPage>
  );
}

function Periods({ perm }) {
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(() => getPeriods(), []);
  const act = async (fn, msg) => { try { await fn(); addToast(msg); reload(); } catch (e) { addToast(humanError(e), "error"); } };
  const all = data?.periods || [];
  const today = isoToday();
  const years = [...new Set(all.map((p) => String(p.startDate).slice(0, 4)))].sort().reverse();
  const [year, setYear] = useState("");
  const shownYear = year || today.slice(0, 4);
  const periods = all.filter((p) => String(p.startDate).slice(0, 4) === shownYear);
  const openPast = all.filter((p) => p.periodStatus === "OPEN" && p.endDate < today.slice(0, 8) + "01").length;
  return (
    <>
      <PolicyNote>A period is OPEN for ordinary posting, CLOSED for adjustments and reversals only, LOCKED for nothing. Closing a period with draft journals in it is refused. Locking is permanent.</PolicyNote>
      <Kpis>
        <Kpi icon={AI.calendar} label="Current period" value={all.find((p) => p.startDate <= today && p.endDate >= today)?.periodName || "—"} tone="teal" />
        <Kpi icon={AI.clock} label="Past periods still open" value={openPast} warn={openPast > 0} note={openPast > 0 ? "close them so old months cannot move" : "all past months closed"} />
        <Kpi icon={AI.lock} label="Locked" value={all.filter((p) => p.periodStatus === "LOCKED").length} />
        <Kpi icon={AI.layers} label="Fiscal years" value={years.length} />
      </Kpis>
      <HrFilterBar>
        <Filter label="Fiscal year"><select value={shownYear} onChange={(e) => setYear(e.target.value)}>{years.map((y) => <option key={y}>{y}</option>)}</select></Filter>
        {perm.canCreate && <Filter label=" "><HrButton icon={P.plus} onClick={() => act(() => createYear((Number(years[0] || today.slice(0, 4)) + 1) + "-01-15"), "Next fiscal year created.")}>Create next fiscal year</HrButton></Filter>}
      </HrFilterBar>
      <HrTable rows={periods} rowKey={(p) => p.pidPeriod} loading={busy} error={err} onRetry={reload} empty="No period in this year." rowClass={(p) => (p.startDate <= today && p.endDate >= today ? "is-current" : "")}
        cols={[{ key: "periodName", label: "Period", lead: true, render: (p) => <><b>{p.periodName}</b>{p.startDate <= today && p.endDate >= today && <span className="acc-now">now</span>}</> }, { key: "startDate", label: "From", render: (p) => fmtDate(p.startDate) }, { key: "endDate", label: "To", render: (p) => fmtDate(p.endDate) },
               { key: "periodStatus", label: "Status", render: (p) => <Status code={p.periodStatus} /> }, { key: "closedBy", label: "Closed", render: (p) => (p.closedAt ? `${fmtDateTime(p.closedAt)} · ${p.closedBy}` : <span className="acc-muted">—</span>) },
               { key: "lockedBy", label: "Locked", render: (p) => (p.lockedAt ? `${fmtDateTime(p.lockedAt)} · ${p.lockedBy}` : <span className="acc-muted">—</span>) },
               { key: "actions", label: "", actions: true, render: (p) => perm.canApprove && <span className="acc-row-actions">
                  {p.periodStatus === "OPEN" && <HrButton size="sm" icon={AI.check} onClick={() => act(() => setPeriodStatus(p.pidPeriod, "CLOSED"), `${p.periodName} closed.`)}>Close</HrButton>}
                  {p.periodStatus === "CLOSED" && <HrButton size="sm" icon={AI.undo} onClick={() => act(() => setPeriodStatus(p.pidPeriod, "OPEN"), `${p.periodName} reopened.`)}>Reopen</HrButton>}
                  {p.periodStatus !== "LOCKED" && <HrButton size="sm" icon={AI.lock} className="acc-danger" title="Permanent: nothing can be posted into a locked period" onClick={() => { if (window.confirm(`Lock ${p.periodName}? Nothing can ever be posted into it again.`)) act(() => setPeriodStatus(p.pidPeriod, "LOCKED"), `${p.periodName} locked.`); }}>Lock</HrButton>}</span> }]} />
    </>
  );
}

function SettingsTabs({ tab, perm }) {
  const { addToast } = useApp();
  const { data, busy, err, reload } = useAsync(getSettings, []);
  const [draft, setDraft] = useState(null);
  const [editing, setEditing] = useState(null);
  if (busy && !data) return <HrPageSkeleton label="Reading settings…" kpis={0} rows={6} />;
  if (err && !data) return <HrErrorState hint={err} onRetry={reload} />;
  const s = draft || data.settings;
  const save = async () => { try { await saveSettings(s); addToast("Settings saved."); setDraft(null); reload(); } catch (e) { addToast(humanError(e), "error"); } };
  const saveOne = async (fn, msg) => { try { await fn(); addToast(msg); setEditing(null); reload(); } catch (e) { addToast(humanError(e), "error"); } };
  if (tab === "settings") return (
    <>
      <div className="acc-settings">
        {Object.entries(SETTING_LABELS).map(([k, [label, hint]]) => (
          <div key={k} className="acc-setting">
            <div><b>{label}</b><p className="acc-muted">{hint}</p></div>
            {["post_cash_variance", "closing_requires_review", "require_day_open", "posting_enabled", "manual_journal_auto_post", "use_cash_in_transit"].includes(k)
              ? <select value={s[k]} disabled={!perm.canEdit} onChange={(e) => setDraft({ ...s, [k]: e.target.value })}><option value="Y">Yes</option><option value="N">No</option></select>
              : k === "late_posting_policy" ? <select value={s[k]} disabled={!perm.canEdit} onChange={(e) => setDraft({ ...s, [k]: e.target.value })}><option>REFUSE</option><option>NEXT_DAY</option></select>
              : k === "expense_claim_basis" ? <select value={s[k]} disabled={!perm.canEdit} onChange={(e) => setDraft({ ...s, [k]: e.target.value })}><option>ACCRUAL</option><option>CASH</option></select>
              : <input value={s[k] ?? ""} disabled={!perm.canEdit} onChange={(e) => setDraft({ ...s, [k]: e.target.value })} />}
          </div>))}
      </div>
      {perm.canEdit && <div className="acc-actions"><HrButton variant="primary" disabled={!draft} onClick={save}>Save settings</HrButton>{draft && <HrButton onClick={() => setDraft(null)}>Discard</HrButton>}</div>}
    </>
  );
  if (tab === "modes") return (
    <>
      <PolicyNote>The business screens write free-text payment types. This table says which cash account each one is. An unmapped mode is treated as the till and logged.</PolicyNote>
      <div className="acc-actions">{perm.canEdit && <HrButton icon={P.plus} onClick={() => setEditing({ kind: "mode", row: {} })}>Map a mode</HrButton>}</div>
      <HrTable rows={data.paymentModes} rowKey={(m) => m.pidPaymentMode} onRowClick={perm.canEdit ? (m) => setEditing({ kind: "mode", row: m }) : undefined}
        cols={[{ key: "modeKey", label: "Mode", lead: true, render: (m) => <b>{m.modeKey}</b> }, { key: "modeLabel", label: "Label" }, { key: "accountRole", label: "Cash account role", render: (m) => m.accountRole ? <code className="acc-role">{m.accountRole}</code> : <span className="acc-muted">no cash (credit)</span> },
               { key: "pidOrg", label: "Scope", render: (m) => (m.pidOrg ? "This pharmacy" : "Default") }, { key: "isActive", label: "Status", render: (m) => <Status code={m.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> }]} />
      {editing?.kind === "mode" && <ModeForm initial={editing.row} banks={data.bankAccounts} onClose={() => setEditing(null)} onSave={(b) => saveOne(() => savePaymentMode(b), "Payment mode saved.")} />}
    </>
  );
  if (tab === "tax") return (
    <>
      <PolicyNote>Rates are configuration, never code. Product pricing keeps its own VAT % for calculating prices; these rows decide the accounts tax lands on.</PolicyNote>
      <div className="acc-actions">{perm.canEdit && <HrButton icon={P.plus} onClick={() => setEditing({ kind: "tax", row: {} })}>New tax rate</HrButton>}</div>
      <HrTable rows={data.taxRates} rowKey={(t) => t.pidTaxRate} onRowClick={perm.canEdit ? (t) => setEditing({ kind: "tax", row: t }) : undefined} empty="No tax rate configured."
        cols={[{ key: "taxCode", label: "Code", lead: true, render: (t) => <b>{t.taxCode}</b> }, { key: "taxName", label: "Name" }, { key: "taxType", label: "Type" }, { key: "ratePct", label: "Rate", num: true, render: (t) => pct(t.ratePct) },
               { key: "effectiveFrom", label: "Effective", render: (t) => `${fmtDate(t.effectiveFrom)}${t.effectiveTo ? " – " + fmtDate(t.effectiveTo) : ""}` }, { key: "isInclusive", label: "Inclusive", render: (t) => (t.isInclusive === 1 ? "Yes" : "No") },
               { key: "isActive", label: "Status", render: (t) => <Status code={t.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> }]} />
      {editing?.kind === "tax" && <TaxForm initial={editing.row} onClose={() => setEditing(null)} onSave={(b) => saveOne(() => saveTaxRate(b), "Tax rate saved.")} />}
    </>
  );
  if (tab === "cc") return (
    <>
      <div className="acc-actions">{perm.canEdit && <HrButton icon={P.plus} onClick={() => setEditing({ kind: "cc", row: {} })}>New cost centre</HrButton>}</div>
      <HrTable rows={data.costCenters} rowKey={(c) => c.pidCostCenter} onRowClick={perm.canEdit ? (c) => setEditing({ kind: "cc", row: c }) : undefined} empty="No cost centre yet. Journals can carry one as a dimension."
        cols={[{ key: "ccCode", label: "Code", lead: true, render: (c) => <b>{c.ccCode}</b> }, { key: "ccName", label: "Name" }, { key: "description", label: "Description" }, { key: "isActive", label: "Status", render: (c) => <Status code={c.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> }]} />
      {editing?.kind === "cc" && <CcForm initial={editing.row} onClose={() => setEditing(null)} onSave={(b) => saveOne(() => saveCostCenter(b), "Cost centre saved.")} />}
    </>
  );
  return <Rules rules={data.rules} perm={perm} onSaved={reload} />;
}

function ModeForm({ initial, banks, onClose, onSave }) {
  const [f, setF] = useState({ modeKey: initial.modeKey || "", modeLabel: initial.modeLabel || "", accountRole: initial.accountRole || "", pidBankAccount: initial.pidBankAccount || "", isActive: initial.isActive ?? 1 });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <Modal title={initial.modeKey ? `Mode ${initial.modeKey}` : "Map a payment mode"} onClose={onClose} width={480}
      footer={<><HrButton onClick={onClose}>Cancel</HrButton><HrButton variant="primary" onClick={() => onSave({ ...f, pidBankAccount: f.pidBankAccount ? Number(f.pidBankAccount) : null })} disabled={!f.modeKey}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Mode (as the screens send it)</label><input value={f.modeKey} onChange={(e) => set("modeKey", e.target.value)} placeholder="e.g. UPAY" disabled={!!initial.modeKey} /></div>
        <div className="field"><label>Label</label><input value={f.modeLabel} onChange={(e) => set("modeLabel", e.target.value)} /></div>
        <div className="field"><label>Cash account role</label><select value={f.accountRole} onChange={(e) => set("accountRole", e.target.value)}><option value="">No cash (credit)</option>{["CASH_REGISTER", "BANK_DEFAULT", "MOBILE_WALLET", "PETTY_CASH", "SUPPLIER_ADVANCE"].map((r) => <option key={r}>{r}</option>)}</select></div>
        <div className="field"><label>Specific bank account</label><select value={f.pidBankAccount} onChange={(e) => set("pidBankAccount", e.target.value)}><option value="">— the default —</option>{(banks || []).map((b) => <option key={b.pidBankAccount} value={b.pidBankAccount}>{b.bankName} {b.accountNo}</option>)}</select></div>
        <div className="field"><label>Status</label><select value={f.isActive} onChange={(e) => set("isActive", Number(e.target.value))}><option value={1}>Active</option><option value={0}>Retired</option></select></div>
      </div>
    </Modal>
  );
}

function TaxForm({ initial, onClose, onSave }) {
  const [f, setF] = useState({ pidTaxRate: initial.pidTaxRate, taxCode: initial.taxCode || "", taxName: initial.taxName || "", taxType: initial.taxType || "VAT", ratePct: initial.ratePct ?? "", effectiveFrom: initial.effectiveFrom || isoToday(), effectiveTo: initial.effectiveTo || "", isInclusive: initial.isInclusive ?? 0, isActive: initial.isActive ?? 1 });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <Modal title={initial.pidTaxRate ? initial.taxName : "New tax rate"} onClose={onClose} width={520}
      footer={<><HrButton onClick={onClose}>Cancel</HrButton><HrButton variant="primary" onClick={() => onSave({ ...f, effectiveTo: f.effectiveTo || null, ratePct: Number(f.ratePct) })} disabled={!f.taxCode || !f.taxName || f.ratePct === ""}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Code</label><input value={f.taxCode} onChange={(e) => set("taxCode", e.target.value)} /></div>
        <div className="field"><label>Name</label><input value={f.taxName} onChange={(e) => set("taxName", e.target.value)} /></div>
        <div className="field"><label>Type</label><select value={f.taxType} onChange={(e) => set("taxType", e.target.value)}>{["VAT", "SALES_TAX", "WITHHOLDING", "OTHER"].map((t) => <option key={t}>{t}</option>)}</select></div>
        <div className="field"><label>Rate %</label><input type="number" min="0" max="100" step="0.0001" value={f.ratePct} onChange={(e) => set("ratePct", e.target.value)} className="hr-num" /></div>
        <div className="field"><label>Effective from</label><input type="date" value={f.effectiveFrom} onChange={(e) => set("effectiveFrom", e.target.value)} /></div>
        <div className="field"><label>Effective to</label><input type="date" value={f.effectiveTo} onChange={(e) => set("effectiveTo", e.target.value)} /></div>
        <div className="field"><label>Prices include tax</label><select value={f.isInclusive} onChange={(e) => set("isInclusive", Number(e.target.value))}><option value={0}>No (exclusive)</option><option value={1}>Yes (inclusive)</option></select></div>
        <div className="field"><label>Status</label><select value={f.isActive} onChange={(e) => set("isActive", Number(e.target.value))}><option value={1}>Active</option><option value={0}>Retired</option></select></div>
      </div>
    </Modal>
  );
}

function CcForm({ initial, onClose, onSave }) {
  const [f, setF] = useState({ pidCostCenter: initial.pidCostCenter, ccCode: initial.ccCode || "", ccName: initial.ccName || "", description: initial.description || "", isActive: initial.isActive ?? 1 });
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <Modal title={initial.pidCostCenter ? initial.ccName : "New cost centre"} onClose={onClose} width={480}
      footer={<><HrButton onClick={onClose}>Cancel</HrButton><HrButton variant="primary" onClick={() => onSave(f)} disabled={!f.ccCode || !f.ccName}>Save</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Code</label><input value={f.ccCode} onChange={(e) => set("ccCode", e.target.value)} /></div>
        <div className="field"><label>Name</label><input value={f.ccName} onChange={(e) => set("ccName", e.target.value)} /></div>
        <div className="field acc-span2"><label>Description</label><input value={f.description} onChange={(e) => set("description", e.target.value)} /></div>
        <div className="field"><label>Status</label><select value={f.isActive} onChange={(e) => set("isActive", Number(e.target.value))}><option value={1}>Active</option><option value={0}>Retired</option></select></div>
      </div>
    </Modal>
  );
}

function Rules({ rules, perm, onSaved }) {
  const { addToast } = useApp();
  const [open, setOpen] = useState(null);
  const [lines, setLines] = useState([]);
  const [busy, setBusy] = useState(false);
  const [roles, setRoles] = useState([]);
  React.useEffect(() => { getChart().then((c) => setRoles(c.filter((a) => a.roleKey).map((a) => a.roleKey))).catch(() => {}); }, []);
  const edit = (r) => { setOpen(r); setLines(r.lines.map((l) => ({ ...l }))); };
  const save = async () => {
    setBusy(true);
    try { await saveRule(open.rule.eventType, { lines: lines.map((l) => ({ side: l.side, accountRole: l.accountRole, amountKey: l.amountKey, partyType: l.partyType || null, description: l.description })) }); addToast("Rule saved for this pharmacy."); setOpen(null); onSaved(); }
    catch (e) { addToast(humanError(e), "error"); }
    finally { setBusy(false); }
  };
  return (
    <>
      <PolicyNote>Each rule is the accounting of one business event: which posting role is debited and credited, from which amount of the event. Defaults are shared; saving one here makes a copy for this pharmacy. Amount keys are those the event carries — the description of each line says what.</PolicyNote>
      <HrTable rows={rules} rowKey={(r) => r.rule.pidRule} onRowClick={perm.canApprove ? edit : undefined} empty="No rule."
        cols={[{ key: "eventType", label: "Event", lead: true, render: (r) => <b>{r.rule.eventType}</b> }, { key: "ruleName", label: "Rule", render: (r) => <>{r.rule.ruleName}<div className="acc-muted">{r.rule.description}</div></> },
               { key: "lines", label: "Lines", render: (r) => <span className="acc-rule-lines">{r.lines.map((l) => <span key={l.pidRuleLine} className={`acc-rule-line is-${l.side}`}>{l.side === "D" ? "Dr" : "Cr"} {l.accountRole} ← {l.amountKey}</span>)}</span> },
               { key: "scope", label: "Scope", render: (r) => (r.rule.pidOrg ? "This pharmacy" : "Default") }, { key: "active", label: "Status", render: (r) => <Status code={r.rule.isActive === 1 ? "ACTIVE" : "INACTIVE"} /> }]} />
      {open && (
        <Modal title={`Rule — ${open.rule.eventType}`} subtitle="Debits and credits must be able to balance for the amounts the event carries. Keep the amount keys; change the roles." onClose={() => setOpen(null)} width={760}
          footer={<><HrButton onClick={() => setOpen(null)} disabled={busy}>Cancel</HrButton><HrButton variant="primary" busy={busy} onClick={save} disabled={!lines.length}>Save for this pharmacy</HrButton></>}>
          <table className="hr-table acc-lines-table">
            <thead><tr><th>#</th><th>Side</th><th>Account role</th><th>Amount key</th><th>Party</th><th>Description</th><th /></tr></thead>
            <tbody>{lines.map((l, i) => (
              <tr key={i}><td>{i + 1}</td>
                <td><select value={l.side} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, side: e.target.value } : x)))}><option value="D">Dr</option><option value="C">Cr</option></select></td>
                <td><input list="acc-roles" value={l.accountRole} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, accountRole: e.target.value } : x)))} /></td>
                <td><input value={l.amountKey} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, amountKey: e.target.value } : x)))} /></td>
                <td><select value={l.partyType || ""} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, partyType: e.target.value } : x)))}><option value="">—</option>{["CUSTOMER", "SUPPLIER", "EMPLOYEE", "INVESTOR", "TENANT"].map((p) => <option key={p}>{p}</option>)}</select></td>
                <td><input value={l.description || ""} onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))} /></td>
                <td><HrButton size="sm" onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}>Remove</HrButton></td></tr>))}
            </tbody>
          </table>
          <datalist id="acc-roles">{roles.map((r) => <option key={r} value={r} />)}<option value="@MODE" /></datalist>
          <HrButton size="sm" icon={P.plus} onClick={() => setLines((ls) => [...ls, { side: "D", accountRole: "", amountKey: "AMOUNT", partyType: "", description: "" }])}>Add a line</HrButton>
        </Modal>
      )}
    </>
  );
}
